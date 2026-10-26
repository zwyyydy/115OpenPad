package com.open115.tv.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * 本机的局域网 IPv4（待机页展示、调试用）。
 *
 * 与 115pad 的 `cast/CastRelayServer.kt#lanIpv4` 同一套口径：
 * 优先 wlan/eth——开着 VPN（tun0）时它也有地址，但平板到不了 tun，
 * 拿 tun 的地址展示会误导"平板连不上"的排查。
 */
internal fun lanIpv4(): String? {
    val interfaces = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
    }.getOrDefault(emptyList())
    val candidates = ArrayList<Pair<String, String>>()
    for (ni in interfaces) {
        if (!runCatching { ni.isUp && !ni.isLoopback }.getOrDefault(false)) continue
        val name = ni.name.lowercase()
        for (addr in Collections.list(ni.inetAddresses)) {
            if (addr is Inet4Address && addr.isSiteLocalAddress) {
                addr.hostAddress?.let { candidates.add(name to it) }
            }
        }
    }

    fun preferred(name: String) =
        name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap") || name.startsWith("en")

    return (
        candidates.firstOrNull { preferred(it.first) }
            ?: candidates.firstOrNull { !it.first.startsWith("tun") && !it.first.startsWith("ppp") }
            ?: candidates.firstOrNull()
        )?.second
}

/**
 * 设备名：优先系统里用户自己设的"设备名称"（电视设置里那个名字），拿不到退 Build.MODEL。
 * 平板端投屏菜单里显示的就是这个名字——用户能一眼认出"这是客厅那台"。
 */
internal fun deviceName(context: Context): String {
    val custom = runCatching {
        Settings.Global.getString(context.contentResolver, "device_name")
    }.getOrNull()
    if (!custom.isNullOrBlank()) return custom
    val model = Build.MODEL
    return if (model.isNullOrBlank()) "Android TV" else model
}
