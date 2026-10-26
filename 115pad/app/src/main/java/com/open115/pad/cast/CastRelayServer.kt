package com.open115.pad.cast

import com.open115.pad.util.AppLog as Log
import com.open115.pad.util.APP_USER_AGENT
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val TAG = "CastRelay"

/**
 * 本机投屏中转：把 115 的下载直链以局域网 HTTP 地址转给电视。
 *
 * 为什么必须有这一层：115 的直链与申请时的 UA 绑定（见 `APP_USER_AGENT`），电视用自己的
 * UA 去拉会被 CDN 判 403。中转在平板侧把 UA 补上，再以普通 HTTP 文件的形式喂给电视。
 *
 * 协议侧的硬要求：
 * - **Range/206**：电视拖进度、以及很多盒子边下边播都靠它；不回 206 会被当成不能 seek。
 * - **Content-Length**：能拿到就报，拿不到就 `Connection: close` 让电视读到断开为止。
 * - **Accept-Encoding: identity**：开 gzip 会把字节范围语义打乱（OkHttp 还会自动解压，
 *   Content-Length 就对不上了）。
 * - 多个并发连接：电视经常同时开 2~4 条（探测 + 播放），每条都独立回源。
 *
 * 只把 token 路径暴露出去（`/<token>/<文件名>`），局域网里的其它设备扫不到有效地址。
 */
internal class CastRelayServer(private val http: OkHttpClient) {

    private data class Session(
        val sourceUrl: String,
        val mime: String,
        val token: String,
    )

    private data class RelayRequest(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
    )

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "cast-relay").apply { isDaemon = true }
    }
    private val sockets: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var session: Session? = null

    /**
     * 启动中转并返回电视可访问的地址。
     *
     * @throws IllegalStateException 本机没有局域网 IPv4（没连 Wi-Fi）
     */
    @Synchronized
    fun start(sourceUrl: String, fileName: String, mime: String): String {
        stop()
        val ip = lanIpv4() ?: error("本机没有局域网地址，请先连接 Wi-Fi")
        val token = ByteArray(8).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val ss = ServerSocket(0, 16, InetAddress.getByName("0.0.0.0"))
        serverSocket = ss
        session = Session(sourceUrl, mime, token)
        running = true
        executor.execute { acceptLoop(ss) }
        val url = "http://$ip:${ss.localPort}/$token/${pathEncode(fileName)}"
        Log.i(TAG, "中转已启动: $url")
        return url
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        session = null
        // 关掉在传的 socket：电视侧立即断流，也让回源请求跟着结束。
        // 不能先 toList() 再关：sockets 是 ConcurrentHashMap 的 keySet，边迭代边有
        // accept/收尾线程 remove 时，KeyIterator 的 hasNext/next 可能对不上而抛
        // NoSuchElementException（单测里偶发复现）。直接在集合自己的 forEach 里关，
        // 它是弱一致遍历，不会因为并发增删抛异常。
        sockets.forEach { s -> runCatching { s.close() } }
        sockets.clear()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try {
                ss.accept()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept 失败: ${e.message}")
                break
            }
            sockets.add(socket)
            executor.execute {
                try {
                    handle(socket)
                } catch (e: IOException) {
                    // 电视主动断开是常态（拖进度会连开连断），不值得刷日志
                    Log.d(TAG, "连接结束: ${e.message}")
                } catch (e: Exception) {
                    Log.w(TAG, "处理请求失败: ${e.message}")
                } finally {
                    sockets.remove(socket)
                    runCatching { socket.close() }
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val input = BufferedInputStream(socket.getInputStream())
        val req = readRequest(input) ?: return
        val s = session ?: return
        val path = req.target.substringBefore('?').trim()
        val token = path.trimStart('/').substringBefore('/')
        if (token != s.token) {
            writeHead(socket, "404 Not Found", listOf("Content-Length" to "0", "Connection" to "close"))
            return
        }
        when (req.method.uppercase()) {
            "GET" -> serveGet(socket, req, s)
            "HEAD" -> serveHead(socket, s)
            else -> writeHead(
                socket,
                "405 Method Not Allowed",
                listOf("Allow" to "GET, HEAD", "Content-Length" to "0", "Connection" to "close"),
            )
        }
    }

    private fun serveGet(socket: Socket, req: RelayRequest, s: Session) {
        val range = parseByteRange(req.headers["range"])
        val builder = Request.Builder()
            .url(s.sourceUrl)
            .header("User-Agent", APP_USER_AGENT)
            .header("Accept-Encoding", "identity")
            .get()
        if (range != null) builder.header("Range", "bytes=${range.start}-${range.end ?: ""}")
        val call = http.newCall(builder.build())
        call.execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "回源失败 HTTP ${resp.code}（range=${req.headers["range"]}）")
                writeHead(
                    socket,
                    "502 Bad Gateway",
                    listOf("Content-Length" to "0", "Connection" to "close"),
                )
                return
            }
            val body = resp.body
            val length = body?.contentLength() ?: -1L
            val headers = ArrayList<Pair<String, String>>()
            headers.add("Content-Type" to s.mime)
            headers.add("Accept-Ranges" to "bytes")
            // 严格的 DLNA 渲染器（老索尼/部分盒子）会要求这个头才肯按流播，
            // 对普通 HTTP 客户端无副作用
            headers.add("transferMode.dlna.org" to "Streaming")
            val contentRange = resp.header("Content-Range")
            if (contentRange != null) headers.add("Content-Range" to contentRange)
            if (length >= 0) headers.add("Content-Length" to length.toString())
            headers.add("Connection" to "close")
            val status = if (resp.code == 206 && contentRange != null) "206 Partial Content" else "200 OK"
            writeHead(socket, status, headers)
            Log.d(
                TAG,
                "GET ${if (range != null) "range=${req.headers["range"]}" else "full"} " +
                    "→ $status len=$length",
            )
            val stream = body?.byteStream() ?: return
            val out = socket.getOutputStream()
            val buf = ByteArray(64 * 1024)
            while (running) {
                val n = try {
                    stream.read(buf)
                } catch (e: IOException) {
                    Log.d(TAG, "回源中断: ${e.message}")
                    break
                }
                if (n < 0) break
                out.write(buf, 0, n)
                out.flush()
            }
            runCatching { socket.shutdownOutput() }
        }
    }

    /** HEAD：电视在播放前常问一次总长度。用 0-0 的回源请求把 total 从 Content-Range 里取出来 */
    private fun serveHead(socket: Socket, s: Session) {
        val resp = runCatching {
            http.newCall(
                Request.Builder()
                    .url(s.sourceUrl)
                    .header("User-Agent", APP_USER_AGENT)
                    .header("Accept-Encoding", "identity")
                    .header("Range", "bytes=0-0")
                    .get()
                    .build(),
            ).execute()
        }.getOrNull()
        if (resp == null) {
            writeHead(socket, "502 Bad Gateway", listOf("Content-Length" to "0", "Connection" to "close"))
            return
        }
        resp.use {
            val total = contentRangeTotal(it.header("Content-Range"))
                ?: it.body?.contentLength()?.takeIf { len -> len > 0 }
            val headers = ArrayList<Pair<String, String>>()
            headers.add("Content-Type" to s.mime)
            headers.add("Accept-Ranges" to "bytes")
            headers.add("transferMode.dlna.org" to "Streaming")
            if (total != null) headers.add("Content-Length" to total.toString())
            headers.add("Connection" to "close")
            writeHead(socket, "200 OK", headers)
            Log.d(TAG, "HEAD → total=$total")
        }
    }

    private fun writeHead(socket: Socket, status: String, headers: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 ").append(status).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        socket.getOutputStream().write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        socket.getOutputStream().flush()
    }

    private fun readRequest(input: InputStream): RelayRequest? {
        // 只读到空行为止；请求头限长，防止局域网里有人发超大报文。
        // 两种换行都认：标准客户端是 CRLF，个别盒子/机顶盒发纯 LF。
        val head = StringBuilder()
        while (head.length < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
            if (head.endsWith("\r\n\r\n") || head.endsWith("\n\n")) break
        }
        val lines = head.toString().split("\r\n", "\n").filter { it.isNotEmpty() }
        val first = lines.firstOrNull() ?: return null
        val parts = first.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        return RelayRequest(parts[0].uppercase(), parts[1], headers)
    }
}

/**
 * 本机的局域网 IPv4。
 *
 * 优先 wlan/eth：开着 VPN（tun0）时它也有地址，但电视到不了 tun；
 * 拿 tun 的地址当投屏 URL 是"搜得到电视、电视却拉不动"的典型成因。
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
