package com.jiucai.provision

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

private val Teal = Color(0xFF00A98F)
private val TealBg = Color(0xFFDFF5F0)
private val PageBg = Color(0xFFFFFFFF)
private val CardBg = Color(0xFFF7F8FA)
private val TextMain = Color(0xFF1F2329)
private val TextSub = Color(0xFF6B7280)
private val TextDim = Color(0xFF9AA3AF)
private val ErrColor = Color(0xFFE5484D)

class MainActivity : ComponentActivity() {

    private var pending: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            pending?.invoke()
            pending = null
        }

    private fun requestPermissions(then: () -> Unit) {
        // 关键：读 SSID 在 Android 10+ 一律需要 ACCESS_FINE_LOCATION
        // Android 13+ 还需要 NEARBY_WIFI_DEVICES
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.ACCESS_WIFI_STATE)
            add(Manifest.permission.CHANGE_WIFI_STATE)
        }.toTypedArray()
        pending = then
        permissionLauncher.launch(perms)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ProvisionScreen(onRequestPermission = ::requestPermissions)
            }
        }
    }

    @Composable
    private fun ProvisionScreen(onRequestPermission: (() -> Unit) -> Unit) {
        val context: Context = LocalContext.current
        val scope = rememberCoroutineScope()

        var ssid by remember { mutableStateOf("") }
        var bssid by remember { mutableStateOf("") }
        var phoneIp by remember { mutableStateOf("") }
        var locationEnabled by remember { mutableStateOf(false) }
        var locationGranted by remember { mutableStateOf(false) }
        var nearbyGranted by remember { mutableStateOf(true) }
        var bandText by remember { mutableStateOf("未知") }
        var is24G by remember { mutableStateOf(false) }
        var allIps by remember { mutableStateOf("") }
        var waited by remember { mutableStateOf(0) }
        var vpnActive by remember { mutableStateOf(false) }
        var wifiTransport by remember { mutableStateOf(false) }
        var password by remember { mutableStateOf("") }
        var serverIp by remember { mutableStateOf("") }
        var serverPort by remember { mutableStateOf("30101") }
        var step by remember { mutableStateOf(Provisioner.Step.IDLE) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val logs = remember { mutableStateListOf<String>() }

        val provisioner = remember {
            Provisioner(
                context = context,
                scope = scope,
                onLog = { line ->
                    logs.add(0, line)
                    if (logs.size > 200) logs.removeAt(logs.size - 1)
                },
                onStep = { step = it },
            )
        }

        fun refreshWifiInfo() {
            val info = WifiInfoReader.read(context)
            ssid = info.ssid
            bssid = info.bssid
            phoneIp = info.phoneIp
            locationEnabled = info.locationEnabled
            locationGranted = info.locationGranted
            nearbyGranted = info.nearbyGranted
            bandText = info.band
            is24G = info.is24G
            allIps = info.allIps.joinToString(" / ")
            vpnActive = info.vpnActive
            wifiTransport = info.wifiTransport
            logs.add(
                0,
                if (info.valid) {
                    "WiFi：${info.ssid}  频段=${info.band}  BSSID=${info.bssid}  手机IP=${info.phoneIp}" +
                        if (info.vpnActive) "  ⚠VPN已开启" else ""
                } else {
                    "读取 SSID 失败：定位权限=${if (info.locationGranted) "已授予" else "未授予"}，" +
                        "定位开关=${if (info.locationEnabled) "开" else "关"}，" +
                        "附近设备权限=${if (info.nearbyGranted) "已授予" else "未授予"}"
                }
            )
        }

        // 进页面自动读一次 + 自动弹权限
        val autoAsked = remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            refreshWifiInfo()
            if (!autoAsked.value) {
                autoAsked.value = true
                onRequestPermission { refreshWifiInfo() }
            }
        }

        // 等待阶段秒表
        androidx.compose.runtime.LaunchedEffect(step, busy) {
            waited = 0
            if (busy && (step == Provisioner.Step.ESPTOUCH || step == Provisioner.Step.WAIT_SCALE)) {
                while (busy && (step == Provisioner.Step.ESPTOUCH || step == Provisioner.Step.WAIT_SCALE)) {
                    kotlinx.coroutines.delay(1000)
                    waited++
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(PageBg)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text("韭菜配网", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
            Spacer(Modifier.height(4.dp))
            Text("把 WiFi 账号密码和自建服务器地址写入斐讯秤", fontSize = 12.sp, color = TextSub)
            Spacer(Modifier.height(16.dp))

            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("当前 WiFi", fontSize = 13.sp, color = TextSub)
                        Text(ssid.ifBlank { "未读取" }, fontSize = 13.sp, color = if (ssid.isBlank()) ErrColor else TextMain)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("频段", fontSize = 13.sp, color = TextSub)
                        Text(
                            if (is24G) bandText else "$bandText  ← 秤只支持 2.4G",
                            fontSize = 12.sp,
                            color = if (is24G) TextMain else ErrColor,
                            fontWeight = if (is24G) FontWeight.Normal else FontWeight.Medium,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("BSSID", fontSize = 13.sp, color = TextSub)
                        Text(bssid.ifBlank { "(无)" }, fontSize = 12.sp, color = TextMain)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("定位权限 / 附近设备", fontSize = 13.sp, color = TextSub)
                        Text(
                            "${if (locationGranted) "✅" else "❌定位"}  ${if (nearbyGranted) "✅" else "❌附近"}",
                            fontSize = 12.sp,
                            color = if (locationGranted && nearbyGranted) TextMain else ErrColor,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("手机 IP", fontSize = 13.sp, color = TextSub)
                        Text(phoneIp.ifBlank { "(未获取)" }, fontSize = 13.sp, color = TextMain)
                    }
                    if (allIps.isNotBlank()) {
                        Text("全部 IPv4：$allIps", fontSize = 10.sp, color = TextDim)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("VPN / 代理", fontSize = 13.sp, color = TextSub)
                        Text(
                            if (vpnActive) "⚠ 已开启，请先关闭" else "未开启 ✅",
                            fontSize = 12.sp,
                            color = if (vpnActive) ErrColor else TextMain,
                            fontWeight = if (vpnActive) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("系统定位开关", fontSize = 13.sp, color = TextSub)
                        Text(
                            if (locationEnabled) "已开启" else "已关闭（读不到 SSID 就把它打开）",
                            fontSize = 12.sp,
                            color = if (locationEnabled) TextMain else ErrColor,
                        )
                    }
                    OutlinedTextField(
                        value = ssid, onValueChange = { ssid = it },
                        label = { Text("WiFi 名称（SSID）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("WiFi 密码") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = serverIp, onValueChange = { serverIp = it },
                        label = { Text("服务器 IP") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = serverPort, onValueChange = { serverPort = it },
                        label = { Text("服务器端口") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onRequestPermission { refreshWifiInfo() } },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TealBg, contentColor = Teal),
                            modifier = Modifier.height(36.dp),
                        ) { Text("重新读取 WiFi", fontSize = 12.sp) }
                    }
                    Text("提示：手机必须连在 2.4G WiFi 上；秤先轻按 RESET 进入配网状态（显示 @ 闪烁）",
                        fontSize = 11.sp, color = TextDim)
                }
            }

            Spacer(Modifier.height(14.dp))

            Button(
                onClick = {
                    error = null
                    if (ssid.isBlank()) { error = "请填写 WiFi 名称"; return@Button }
                    if (password.isBlank()) { error = "请填写 WiFi 密码"; return@Button }
                    val port = serverPort.toIntOrNull()
                    if (port == null || port !in 1..65535) { error = "服务器端口不合法"; return@Button }
                    if (serverIp.split(".").size != 4) { error = "服务器 IP 不合法"; return@Button }
                    if (vpnActive) {
                        error = "检测到 VPN 正在运行 —— 它会吞掉 UDP 广播和秤的回包，请先关闭 VPN 再配网"
                        return@Button
                    }
                    if (!is24G && bandText != "未知") {
                        error = "当前是 $bandText，秤只支持 2.4G —— 请连 2.4G 的 WiFi 再试"
                        return@Button
                    }

                    onRequestPermission {
                        busy = true
                        logs.clear()
                        provisioner.start(ssid, bssid, password, serverIp, port)
                    }
                },
                enabled = !busy,
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text(if (busy) "配网进行中…" else "开始配网", fontSize = 16.sp) }

            if (busy) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { provisioner.stop(); busy = false },
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TealBg, contentColor = Teal),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) { Text("停止", fontSize = 14.sp) }
            }

            Spacer(Modifier.height(14.dp))

            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg)) {
                Column(Modifier.padding(14.dp)) {
                    Text("进度", fontSize = 13.sp, color = TextSub)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stepText(step) + if (waited > 0 && busy) "  已等待 ${waited}s" else "",
                        fontSize = 15.sp,
                        color = if (step == Provisioner.Step.CONFIRMED) Teal else TextMain,
                        fontWeight = FontWeight.Medium,
                    )
                    error?.let { Text(it, fontSize = 12.sp, color = ErrColor) }
                    if (step == Provisioner.Step.CONFIRMED) busy = false
                    if (step == Provisioner.Step.FAILED) busy = false
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("日志", fontSize = 13.sp, color = TextSub)
            Spacer(Modifier.height(6.dp))
            if (logs.isEmpty()) {
                Text("（暂无）", fontSize = 12.sp, color = TextDim)
            } else {
                logs.forEach { line ->
                    Text(line, fontSize = 11.sp, color = TextSub,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 1.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun stepText(step: Provisioner.Step): String = when (step) {
    Provisioner.Step.IDLE -> "待开始"
    Provisioner.Step.ESPTOUCH -> "① 正在广播 WiFi 信息…"
    Provisioner.Step.WAIT_SCALE -> "② 等待秤连上 WiFi…"
    Provisioner.Step.SERVER_SENT -> "③ 已发送服务器地址，等待确认…"
    Provisioner.Step.CONFIRMED -> "✅ 配网完成"
    Provisioner.Step.FAILED -> "❌ 配网失败"
}
