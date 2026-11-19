package com.jiucai.provision

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.text.TextUtils
import java.net.Inet4Address
import java.net.NetworkInterface

/** 读取当前 WiFi 信息 + 频段 + 权限状态 */
object WifiInfoReader {

    data class Info(
        val ssid: String,
        val bssid: String,
        val phoneIp: String,
        val allIps: List<String>,
        val frequencyMhz: Int,
        val locationEnabled: Boolean,
        val locationGranted: Boolean,
        val nearbyGranted: Boolean,
        val vpnActive: Boolean,
        val wifiTransport: Boolean,
    ) {
        val valid: Boolean get() = !TextUtils.isEmpty(ssid) && ssid != "<unknown ssid>"

        /** 2400~2500 才是 2.4G；秤只支持 2.4G */
        val band: String
            get() = when {
                frequencyMhz in 2400..2500 -> "2.4G (${frequencyMhz}MHz)"
                frequencyMhz in 4900..5900 -> "5G (${frequencyMhz}MHz)"
                frequencyMhz in 5900..7125 -> "6G (${frequencyMhz}MHz)"
                frequencyMhz > 0 -> "${frequencyMhz}MHz"
                else -> "未知"
            }

        val is24G: Boolean get() = frequencyMhz in 2400..2500
    }

    @Suppress("DEPRECATION")
    fun read(context: Context): Info {
        val app = context.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val connection = wifi?.connectionInfo
        val ssid = (connection?.ssid ?: "").trim().removeSurrounding("\"")
        val bssid = (connection?.bssid ?: "").trim()
        val freq = runCatching { connection?.frequency ?: 0 }.getOrDefault(0)

        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val locationEnabled = runCatching { lm?.isLocationEnabled == true }.getOrDefault(false)

        val fine = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val nearby = if (Build.VERSION.SDK_INT >= 33) {
            app.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        } else true

        val all = allIpv4()
        return Info(
            vpnActive = vpnActive(app),
            wifiTransport = wifiTransport(app),
            ssid = ssid,
            bssid = bssid,
            phoneIp = wifiIp(wifi) ?: pickLanIp(all),
            allIps = all,
            frequencyMhz = freq,
            locationEnabled = locationEnabled,
            locationGranted = fine,
            nearbyGranted = nearby,
        )
    }

    /** VPN 开启时会接管所有流量，Esptouch 广播和握手回包都会被吞掉 */
    private fun vpnActive(app: Context): Boolean = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = cm.activeNetwork ?: return false
        cm.getNetworkCapabilities(nw)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    /** 当前活跃网络是不是 WiFi（VPN 开着时会导致这里为 false） */
    private fun wifiTransport(app: Context): Boolean = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = cm.activeNetwork ?: return false
        cm.getNetworkCapabilities(nw)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun wifiIp(wifi: WifiManager?): String? = runCatching {
        val ip = wifi?.connectionInfo?.ipAddress ?: 0
        if (ip == 0) null else "%d.%d.%d.%d".format(ip and 0xFF, ip shr 8 and 0xFF, ip shr 16 and 0xFF, ip shr 24 and 0xFF)
    }.getOrNull()

    private fun allIpv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    private fun pickLanIp(all: List<String>): String =
        all.firstOrNull { it.startsWith("192.168.") }
            ?: all.firstOrNull { it.startsWith("10.") }
            ?: all.firstOrNull { it.startsWith("172.") }
            ?: all.firstOrNull()
            ?: ""

    fun localIpv4(): String = pickLanIp(allIpv4())
}
