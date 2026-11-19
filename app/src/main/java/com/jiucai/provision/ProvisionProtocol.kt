package com.jiucai.provision

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.zip.Adler32

/**
 * 斐讯 S7 配网协议（从原版 App 反编译还原，逐字节对齐）。
 *
 * 阶段 1：秤连上 WiFi 后，向手机的 UDP 7551~7553 发送 32 字节包
 *         （XOR 0x55；CRC16/MODBUS 覆盖 [2..27]，大端存在 [30..31]；
 *           [26]==0 && [27]==0x10 表示"已连上 AP"）
 * 阶段 2：手机把「服务器 IP+端口」打包成 48 字节回包，发到秤的 UDP 7550，
 *         秤收到后回 [27]==0x20 的确认包
 */
object ProvisionProtocol {

    const val XOR_KEY = 0x55

    const val SCALE_UDP_PORT = 7550          // 回包目标端口
    val LISTEN_PORTS = intArrayOf(7551, 7552, 7553)

    const val TYPE_AP_CONNECTED = 0x10       // 秤：[26]=0, [27]=0x10
    const val TYPE_CONFIRM = 0x20            // 秤：[26]=0, [27]=0x20

    /** 产品码 → 机型（原版 q6.d） */
    private val MODEL_CODES = mapOf(
        byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1).toList() to "S7 / S7P",
        byteArrayOf(0, 0, 0, 1, 0, 0, 0, 2).toList() to "S9",
        byteArrayOf(0, 0, 0, 1, 0, 0, 0, 3).toList() to "S7PE",
    )

    data class Incoming(
        val type: Int,
        val mac: String,
        val model: String,
        val raw: ByteArray,
    )

    fun xor(data: ByteArray): ByteArray = ByteArray(data.size) { (data[it].toInt() xor XOR_KEY).toByte() }

    fun crc16Modbus(data: ByteArray, from: Int, until: Int): Int {
        var crc = 0xFFFF
        for (i in from until until) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    private fun hex(b: ByteArray, from: Int, until: Int) = buildString {
        for (i in from until until) {
            if (isNotEmpty()) append(':')
            append("%02X".format(b[i].toInt() and 0xFF))
        }
    }

    /** 解析秤发来的 32 字节包；不合法返回 null */
    fun parseIncoming(packet: ByteArray): Incoming? {
        if (packet.size < 32) return null
        val p = xor(packet.copyOf(32))
        if ((p[0].toInt() and 0xFF) != 0x55 || (p[1].toInt() and 0xFF) != 0xAA) return null

        val crc = crc16Modbus(p, 2, 28)
        val want = ((p[30].toInt() and 0xFF) shl 8) or (p[31].toInt() and 0xFF)
        if (crc != want) return null
        if (p[26].toInt() != 0) return null

        val type = p[27].toInt() and 0xFF
        if (type != TYPE_AP_CONNECTED && type != TYPE_CONFIRM) return null

        val code = p.copyOfRange(4, 12).toList()
        val model = MODEL_CODES[code] ?: "未知(%s)".format(
            p.copyOfRange(4, 12).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        )
        return Incoming(
            type = type,
            mac = hex(p, 12, 18),
            model = model,
            raw = packet.copyOf(32),
        )
    }

    /**
     * 构造 48 字节回包（原版 `jr.e` / ResponseIpCode）：
     * ```
     * [0..3]   55 AA 00 30
     * [4..11]  00 00 00 01 00 00 00 01
     * [12..17] 0
     * [18..23] 秤 MAC
     * [26..27] 40 10
     * [28..31] 服务器 IP
     * [32..33] 端口（大端）
     * [46..47] CRC16/MODBUS（覆盖 [2..43]，大端）
     * ```
     * 整包 XOR 0x55 后发出。
     */
    fun buildServerReply(macHex: String, serverIp: String, serverPort: Int): ByteArray {
        val mac = macHex.split(":").map { it.toInt(16).toByte() }
        require(mac.size == 6) { "MAC 格式错误: $macHex" }

        val ip = serverIp.split(".").map { it.toInt() }
        require(ip.size == 4) { "服务器 IP 格式错误: $serverIp" }

        val p = ByteArray(48)
        p[0] = 0x55
        p[1] = 0xAA.toByte()
        p[2] = 0x00
        p[3] = 48
        p[4] = 0
        p[5] = 0
        p[6] = 0
        p[7] = 1
        p[8] = 0
        p[9] = 0
        p[10] = 0
        p[11] = 1
        for (i in 0..5) p[18 + i] = mac[i]
        p[24] = 0
        p[25] = 0
        p[26] = 0x40
        p[27] = 0x10
        p[28] = ip[0].toByte()
        p[29] = ip[1].toByte()
        p[30] = ip[2].toByte()
        p[31] = ip[3].toByte()
        p[32] = ((serverPort shr 8) and 0xFF).toByte()
        p[33] = (serverPort and 0xFF).toByte()
        p[44] = 0
        p[45] = 0
        val crc = crc16Modbus(p, 2, 44)
        p[46] = ((crc shr 8) and 0xFF).toByte()
        p[47] = (crc and 0xFF).toByte()
        return xor(p)
    }

    /** 打开一个可用于收组的 UDP socket（自动挑 7551/7552/7553） */
    fun openListener(): Pair<DatagramSocket, Int> {
        var last: Exception? = null
        for (port in LISTEN_PORTS) {
            try {
                val socket = DatagramSocket(null)
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(port))
                socket.soTimeout = 1000
                return socket to port
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException("端口 7551~7553 全部被占用: ${last?.message}")
    }

    @Suppress("unused")
    private fun adler(data: ByteArray): Long = Adler32().apply { update(data) }.value

    fun addressOf(ip: String, port: Int): InetAddress = InetAddress.getByName(ip)
}
