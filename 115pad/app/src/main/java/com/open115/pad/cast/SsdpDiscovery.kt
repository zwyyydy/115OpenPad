package com.open115.pad.cast

import android.content.Context
import android.net.wifi.WifiManager
import com.open115.pad.util.AppLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

private const val TAG = "CastSsdp"
private const val SSDP_ADDR = "239.255.255.250"
private const val SSDP_PORT = 1900

/**
 * 局域网里的 DLNA 设备发现。
 *
 * 流程就是标准 SSDP：往 239.255.255.250:1900 发 M-SEARCH，收单播响应拿 LOCATION，
 * 再拉每台的设备描述 XML，筛出带 AVTransport 的 MediaRenderer。
 *
 * 两个兼容性细节：
 * - **同时发一条 `ssdp:all`**：部分国产电视/盒子只对 `ssdp:all` 有响应（对带 ST 的
 *   M-SEARCH 装死），只发 MediaRenderer 会在这些设备上"搜不到"。
 * - 发送间隔重复几次：UDP 丢包在 Wi-Fi 上很常见，单发一次经常少一台。
 *
 * 发现只在 Wi-Fi 下有意义；拿不到局域网地址时调用方会提示用户。
 */
internal suspend fun discoverCastDevices(
    context: Context,
    http: OkHttpClient,
    timeoutMs: Long = 3200,
): List<CastDevice> = withContext(Dispatchers.IO) {
    val multicast = runCatching {
        context.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("115pad-cast-ssdp")
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
    }.getOrNull()
    try {
        val locations = LinkedHashMap<String, String>() // location -> usn（排重用）
        // 绑在局域网地址上：平板同时挂着 VPN（tun0）时，从默认路由发组播可能走错网卡，
        // 电视收不到 M-SEARCH，表现为"一台都搜不到"
        val bindIp = lanIpv4()?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
        val socket = runCatching {
            if (bindIp != null) DatagramSocket(0, bindIp) else DatagramSocket()
        }.getOrNull() ?: return@withContext emptyList()
        try {
            socket.soTimeout = 250
            val target = InetAddress.getByName(SSDP_ADDR)
            val searches = listOf(
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "ssdp:all",
            )
            val payloads = searches.map { st ->
                val text = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: $st\r\n\r\n"
                text.toByteArray(Charsets.UTF_8)
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            // 0 / 900 / 1800ms 各发一轮；每轮两条
            var nextSend = 0L
            while (System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() >= nextSend) {
                    for (p in payloads) {
                        runCatching { socket.send(DatagramPacket(p, p.size, target, SSDP_PORT)) }
                    }
                    nextSend = System.currentTimeMillis() + 900
                }
                val buf = ByteArray(4096)
                val resp = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(resp)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    Log.w(TAG, "SSDP 接收失败: ${e.message}")
                    break
                }
                val text = String(resp.data, 0, resp.length, Charsets.UTF_8)
                val headers = parseSsdpHeaders(text)
                val location = headers["location"] ?: continue
                val st = headers["st"] ?: headers["nt"] ?: ""
                // ssdp:all 会把路由器/打印机都带回来，但它们的描述里没有 AVTransport，
                // 下面解析时会自然筛掉；这里只按 location 去重。
                locations.putIfAbsent(location, headers["usn"] ?: st)
            }
        } finally {
            runCatching { socket.close() }
        }

        if (locations.isEmpty()) return@withContext emptyList()
        // 设备描述并发拉：串行在十几台设备的路由器下会明显拖慢面板打开速度
        val descClient = http.newBuilder()
            .callTimeout(2200, TimeUnit.MILLISECONDS)
            .connectTimeout(1200, TimeUnit.MILLISECONDS)
            .readTimeout(1800, TimeUnit.MILLISECONDS)
            .build()
        val devices = coroutineScope {
            locations.keys.take(24).map { location ->
                async {
                    runCatching {
                        val req = Request.Builder().url(location).get().build()
                        descClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@use null
                            val bytes = resp.body?.bytes() ?: return@use null
                            if (bytes.size > 2 * 1024 * 1024) return@use null
                            parseDeviceDescription(bytes, location)
                        }
                    }.getOrNull()
                }
            }.awaitAll()
        }
        devices.filterNotNull()
            .distinctBy { it.udn }
            .sortedBy { it.name.lowercase() }
    } catch (e: Exception) {
        Log.w(TAG, "设备发现失败: ${e.message}")
        emptyList()
    } finally {
        runCatching { multicast?.release() }
    }
}
