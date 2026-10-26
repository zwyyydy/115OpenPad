package com.open115.pad.cast

import com.open115.pad.util.AppLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

private const val TAG = "PadTvDiscover"

/**
 * 搜「115 openpad电视端」接收器：往局域网广播一条查询，谁应答就是谁。
 *
 * 和 DLNA 那套（组播 M-SEARCH + 逐台拉设备描述 XML）不同，这里收到的是**单播应答**，
 * 一条报文就带齐了设备名与端口，不需要再跟任何后续请求——发现耗时也就固定在 2 秒左右。
 *
 * 收不到应答不算异常（家里没装电视端时本来就该是空的），调用方把两个发现器的结果合并即可。
 */
internal suspend fun discoverPadTvHosts(
    timeoutMs: Long = 2_000,
): List<CastDevice> = withContext(Dispatchers.IO) {
    // 与 SSDP 发现同一条教训：平板挂着 VPN 时从默认路由发广播会走错网卡，绑到局域网地址上
    val socket = runCatching {
        val bindIp = lanIpv4()?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
        (if (bindIp != null) DatagramSocket(0, bindIp) else DatagramSocket()).apply {
            broadcast = true
            soTimeout = 250
        }
    }.getOrNull() ?: return@withContext emptyList()

    val found = LinkedHashMap<String, CastDevice>() // key = 电视 IP（同一台只记第一条应答）
    try {
        val payload = encodePadTvFind().toByteArray(Charsets.UTF_8)
        val targets = broadcastTargets().mapNotNull {
            runCatching { InetAddress.getByName(it) }.getOrNull()
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        var nextSend = 0L
        while (System.currentTimeMillis() < deadline) {
            if (System.currentTimeMillis() >= nextSend) {
                for (t in targets) {
                    runCatching {
                        socket.send(DatagramPacket(payload, payload.size, t, PadTv.DISCOVER_PORT))
                    }.onFailure { Log.d(TAG, "广播失败($t): ${it.message}") }
                }
                // UDP 丢包在 Wi-Fi 上很常见，两轮以上才稳
                nextSend = System.currentTimeMillis() + 700
            }
            val buf = ByteArray(2048)
            val resp = DatagramPacket(buf, buf.size)
            try {
                socket.receive(resp)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                Log.w(TAG, "接收失败: ${e.message}")
                break
            }
            val text = String(resp.data, 0, resp.length, Charsets.UTF_8)
            val here = parsePadTvHere(text) ?: continue
            val ip = resp.address?.hostAddress ?: continue
            found.putIfAbsent(ip, here.toCastDevice(ip))
        }
    } finally {
        runCatching { socket.close() }
    }
    found.values.sortedBy { it.name.lowercase() }
}

/** 受限广播与子网广播都发：个别路由器/AP 只放行其中一种 */
private fun broadcastTargets(): List<String> {
    val out = arrayListOf("255.255.255.255")
    lanIpv4()?.let { ip ->
        val prefix = ip.substringBeforeLast('.', "")
        if (prefix.isNotEmpty()) out.add("$prefix.255")
    }
    return out
}

/**
 * 手动填地址时的设备构造：不经过 UDP 发现，直接按"host[:port]"连。
 *
 * 什么时候需要它（广播发现在这些场景本来就没戏）：
 * - 路由器开了 AP 隔离 / 两个设备在不同网段（比如一台是模拟器的 NAT 网络）；
 * - 电视端 App 所在设备会回包但广播被挡。
 */
internal fun padTvDeviceOf(host: String, port: Int = PadTv.HTTP_PORT): CastDevice = CastDevice(
    name = host,
    model = "115 openpad电视端 · 手动地址",
    location = "padtv://$host:$port",
    udn = "padtv:$host:$port",
    avTransportUrl = "http://$host:$port",
    avTransportType = "115pad.tv.receiver",
    renderingControlUrl = null,
    renderingControlType = null,
    target = CastTarget.PadTv,
)

/**
 * 解析手动输入的地址。接受这些写法：
 * `192.0.2.110`、`192.0.2.110:48701`、`http://192.0.2.110:48701/`
 * 解析不出来返回 null（调用方据此禁用「连接」按钮）。
 */
internal fun parsePadTvAddress(input: String): Pair<String, Int>? {
    var text = input.trim()
    if (text.isEmpty()) return null
    text = text.removePrefix("http://").removePrefix("https://").trimEnd('/')
    if (text.isEmpty()) return null
    val host = text.substringBefore(':').trim()
    if (host.isEmpty()) return null
    val portText = text.substringAfter(':', "").trim()
    val port = if (portText.isEmpty()) PadTv.HTTP_PORT else portText.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    return host to port
}

private fun PadTvHere.toCastDevice(ip: String): CastDevice = CastDevice(
    name = name.ifBlank { "电视端" },
    // 投屏面板上显示的第二行；用户一眼能认出"这台不用中转、平板能退"
    model = "115 openpad电视端 · 免中转",
    location = "padtv://$ip:$port",
    udn = "padtv:$ip:$port",
    avTransportUrl = "http://$ip:$port",
    avTransportType = "115pad.tv.receiver",
    renderingControlUrl = null,
    renderingControlType = null,
    target = CastTarget.PadTv,
)
