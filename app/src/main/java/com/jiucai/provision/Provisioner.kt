package com.jiucai.provision

import android.content.Context
import android.net.wifi.WifiManager
import com.espressif.iot.esptouch.EsptouchTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * 配网执行器：Esptouch 广播 WiFi 凭据 + UDP 握手告知服务器地址。
 *
 * 诊断增强版：
 *  - 同时监听 7551/7552/7553 三个端口（哪路有包就处理哪路）
 *  - 任何收到的 UDP 包都记录来源 + 原始 hex，便于判断"没收到包"还是"解析失败"
 *  - Esptouch 结果带耗时
 */
class Provisioner(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit,
    private val onStep: (Step) -> Unit,
) {

    enum class Step { IDLE, ESPTOUCH, WAIT_SCALE, SERVER_SENT, CONFIRMED, FAILED }

    private data class Received(val data: ByteArray, val from: InetAddress, val port: Int)

    private var job: Job? = null
    private val esptouchJobs = mutableListOf<Job>()
    private var esptouch: EsptouchTask? = null
    private val sockets = mutableListOf<DatagramSocket>()
    private val receiveJobs = mutableListOf<Job>()
    private val inbox = Channel<Received>(Channel.UNLIMITED)
    private var multicastLock: WifiManager.MulticastLock? = null

    val running: Boolean get() = job?.isActive == true

    fun start(
        ssid: String,
        bssid: String,
        password: String,
        serverIp: String,
        serverPort: Int,
    ) {
        if (running) return
        onStep(Step.IDLE)
        job = scope.launch {
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("jiucai-provision").apply {
                    setReferenceCounted(false)
                    acquire()
                }

                // 1) 三个端口全开（原版只用其中一个，但不确定秤打哪个）
                for (port in ProvisionProtocol.LISTEN_PORTS) {
                    try {
                        val sock = DatagramSocket(null)
                        sock.reuseAddress = true
                        sock.bind(InetSocketAddress(port))
                        sock.soTimeout = 1000
                        sockets.add(sock)
                        onLog("UDP 监听已启动：$port")
                    } catch (e: Exception) {
                        onLog("端口 $port 不可用：${e.message}")
                    }
                }
                if (sockets.isEmpty()) {
                    onStep(Step.FAILED)
                    onLog("7551~7553 全部被占用，无法配网")
                    return@launch
                }
                sockets.forEach { sock ->
                    receiveJobs.add(scope.launch(Dispatchers.IO) { receiveLoop(sock) })
                }

                // 回环自检：证明「UDP 收包 → 解析」这条链路本身是通的
                withContext(Dispatchers.IO) {
                    runCatching {
                        val probe = "SELFTEST".toByteArray()
                        sockets.first().send(
                            DatagramPacket(
                                probe, probe.size,
                                InetAddress.getByName("127.0.0.1"),
                                sockets.first().localPort,
                            )
                        )
                    }
                }
                delay(300)
                onLog("UDP 自检完成（若没收到 SELFTEST 说明系统拦截了 UDP，请检查是否有防火墙/安全软件）")

                // 2) Esptouch
                onStep(Step.ESPTOUCH)
                if (ssid.isBlank()) {
                    onStep(Step.FAILED)
                    onLog("SSID 为空：请先连上 WiFi 再点「重新读取 WiFi」")
                    return@launch
                }
                // Esptouch 库要求 BSSID 不能为空（会抛 NPE），无效时用占位值
                val bssidArg = if (BSSID_PATTERN.matches(bssid) && bssid != "02:00:00:00:00:00") bssid
                else "02:00:00:00:00:00".also { onLog("BSSID 无效（${bssid.ifBlank { "空" }}），使用占位值继续") }
                onLog("Esptouch 开始：SSID=$ssid  BSSID=$bssidArg")
                val startedAt = System.currentTimeMillis()
                val task = EsptouchTask(ssid, bssidArg, password, context)
                task.setEsptouchListener { result ->
                    runCatching {
                        scope.launch(Dispatchers.Main) {
                            onLog("Esptouch 收到设备响应：bssid=${result.bssid}  suc=${result.isSuc}")
                        }
                    }
                }
                esptouch = task
                esptouchJobs.add(
                    scope.launch(Dispatchers.IO) {
                        val results = runCatching { task.executeForResults(1) }.getOrNull()
                        val suc = results?.firstOrNull()?.let { runCatching { it.isSuc }.getOrDefault(false) } ?: false
                        val cost = System.currentTimeMillis() - startedAt
                        launch(Dispatchers.Main) {
                            onLog(
                                "Esptouch 结束（${cost}ms）：" +
                                    if (suc) "设备已连上 WiFi ✅" else "未确认（继续等待握手包）"
                            )
                        }
                    }
                )

                // 3) 等秤的握手包
                onStep(Step.WAIT_SCALE)
                onLog("等待秤发来握手包（最长 ${TIMEOUT_MS / 1000}s）…")
                val deadline = System.currentTimeMillis() + TIMEOUT_MS
                val startedWaiting = System.currentTimeMillis()
                var serverSent = false
                var rawCount = 0
                var lastTick = 0L

                while (isActive && System.currentTimeMillis() < deadline) {
                    val received = withContext(Dispatchers.IO) {
                        runCatching { kotlinx.coroutines.withTimeoutOrNull(500) { inbox.receive() } }.getOrNull()
                    }
                    if (received == null) {
                        val sec = (System.currentTimeMillis() - startedWaiting) / 1000
                        if (sec - lastTick >= 10) {
                            lastTick = sec
                            onLog("…已等待 ${sec}s，仍未收到秤的握手包")
                        }
                        continue
                    }

                    if (String(received.data) == "SELFTEST") {
                        onLog("UDP 回环自检 ✅ 收包链路正常")
                        continue
                    }
                    rawCount++
                    val hex = received.data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                    onLog("收到 ${received.from.hostAddress}:${received.port}  ${received.data.size}B  $hex")

                    val incoming = ProvisionProtocol.parseIncoming(received.data)
                    if (incoming == null) {
                        onLog("  → 解析失败（长度/CRC/类型不匹配）")
                        continue
                    }
                    onLog("  → 解析成功：${incoming.model}  MAC=${incoming.mac}  type=0x%02X".format(incoming.type))

                    when (incoming.type) {
                        ProvisionProtocol.TYPE_AP_CONNECTED -> if (!serverSent) {
                            onStep(Step.SERVER_SENT)
                            val reply = ProvisionProtocol.buildServerReply(
                                macHex = incoming.mac,
                                serverIp = serverIp,
                                serverPort = serverPort,
                            )
                            onLog("回包 hex：${reply.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}")
                            val sent = withContext(Dispatchers.IO) {
                                sendReply(sockets.first(), received.from, reply)
                            }
                            serverSent = true
                            onLog(
                                if (sent) "已把服务器地址 ${serverIp}:${serverPort} 发给秤（重发 $SEND_RETRY 次）"
                                else "回包发送失败"
                            )
                        }

                        ProvisionProtocol.TYPE_CONFIRM -> {
                            onLog("收到秤的确认包，配网完成 ✅（共收到 $rawCount 个包）")
                            onStep(Step.CONFIRMED)
                            return@launch
                        }
                    }
                }

                if (!isActive) return@launch
                onStep(Step.FAILED)
                onLog("超时（共收到 $rawCount 个包）。排查顺序：")
                onLog("  1) 秤是否已按 RESET → 屏幕显示 @ 闪烁")
                onLog("  2) 手机是否连在 2.4G WiFi（不能是 5G）")
                onLog("  3) 路由器是否开启「客户端隔离」")
                onLog("  4) 手机是否开着代理/VPN")
            } catch (e: Exception) {
                onStep(Step.FAILED)
                onLog("配网异常：${e.message}")
            } finally {
                release()
            }
        }
    }

    private suspend fun receiveLoop(sock: DatagramSocket) {
        val buf = ByteArray(256)
        while (true) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                sock.receive(packet)
                val data = packet.data.copyOf(packet.length)
                inbox.send(Received(data, packet.address, packet.port))
            } catch (_: SocketTimeoutException) {
                // 正常：每秒醒一次继续等
            } catch (_: Exception) {
                return
            }
        }
    }

    private fun sendReply(sock: DatagramSocket, scaleAddress: InetAddress, reply: ByteArray): Boolean {
        var ok = false
        repeat(SEND_RETRY) {
            try {
                sock.send(DatagramPacket(reply, reply.size, scaleAddress, ProvisionProtocol.SCALE_UDP_PORT))
                ok = true
                Thread.sleep(SEND_INTERVAL_MS)
            } catch (_: Exception) {
            }
        }
        return ok
    }

    fun stop() {
        release()
        job?.cancel()
        job = null
        onStep(Step.IDLE)
    }

    private fun release() {
        esptouchJobs.forEach { runCatching { it.cancel() } }
        esptouchJobs.clear()
        runCatching { esptouch?.interrupt() }
        esptouch = null
        receiveJobs.forEach { runCatching { it.cancel() } }
        receiveJobs.clear()
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    companion object {
        private val BSSID_PATTERN = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")
        const val TIMEOUT_MS = 150_000L
        const val SEND_RETRY = 10
        const val SEND_INTERVAL_MS = 100L
    }
}
