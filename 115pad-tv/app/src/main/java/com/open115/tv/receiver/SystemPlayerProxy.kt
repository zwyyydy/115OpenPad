package com.open115.tv.receiver

import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val TAG = "TvSysPlayerProxy"

/**
 * 把 115 直链转成"本机可播"的 HTTP 地址，交给电视**自带**的播放器。
 *
 * 为什么需要它：115 直链与申请时的 UA 绑定，而电视自带的播放器不会带我们的 UA，
 * 直拉必然 403。代理在电视本机把 UA 补上，再以普通 HTTP 文件喂给它；只绑 127.0.0.1，
 * 本机之外不可达（不像 115pad 的中转要暴露给局域网电视）。
 *
 * 协议侧的硬要求与 115pad 的中转一致：
 * - **Range/206**：系统播放器拖进度靠它，不回 206 会被当成不能 seek；
 * - Content-Length 能拿就拿，拿不到就让对方读到断开；
 * - `Accept-Encoding: identity`：gzip 会把字节范围的语义搅乱。
 *
 * ⚠️ 流是从**本进程**转发的：走这条路就要求本进程活着（见 ReceiverService 的常驻通知）——
 * 这也是它只当"内置播放器放不了时的兜底"的原因之一。另外交给系统播放器之后，
 * 我们既拿不到进度也控不了它（[TvProtocol.STATE_EXTERNAL]）。
 */
internal class SystemPlayerProxy {

    private data class Session(
        val sourceUrl: String,
        val mime: String,
        val ua: String?,
        val token: String,
    )

    private data class ProxyRequest(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
    )

    /** 单段字节范围（`bytes=start-` 的 end 为 null） */
    private data class ByteRange(val start: Long, val end: Long?)

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "tv-sys-proxy").apply { isDaemon = true }
    }
    private val sockets: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var session: Session? = null

    /**
     * 启动代理并返回系统播放器可用的地址。
     *
     * @throws IOException 端口/绑定失败：由调用方回退到内置播放器并提示。
     */
    @Synchronized
    fun start(sourceUrl: String, fileName: String, mime: String, ua: String?): String {
        stop()
        val token = ByteArray(8).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        session = Session(sourceUrl, mime, ua, token)
        running = true
        executor.execute { acceptLoop(ss) }
        // 路径里带上扩展名：系统播放器/部分电视媒体库靠它判类型
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val name = if (ext.isNotEmpty() && ext.length <= 5) "video.$ext" else "video"
        val url = "http://127.0.0.1:${ss.localPort}/$token/$name"
        Log.i(TAG, "系统播放器代理已启动: $url")
        return url
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        session = null
        for (s in sockets.toList()) runCatching { s.close() }
        sockets.clear()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept 失败: ${e.message}")
                break
            }
            sockets.add(socket)
            executor.execute {
                try {
                    handle(socket)
                } catch (e: IOException) {
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
        val req = readRequest(BufferedInputStream(socket.getInputStream())) ?: return
        val s = session ?: return
        val token = req.target.substringBefore('?').trim().trimStart('/').substringBefore('/')
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

    private fun serveGet(socket: Socket, req: ProxyRequest, s: Session) {
        val range = parseByteRange(req.headers["range"])
        val conn = openUpstream(s, range) ?: run {
            writeHead(socket, "502 Bad Gateway", listOf("Content-Length" to "0", "Connection" to "close"))
            return
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "回源失败 HTTP $code（range=${req.headers["range"]}）")
                writeHead(socket, "502 Bad Gateway", listOf("Content-Length" to "0", "Connection" to "close"))
                return
            }
            val length = conn.contentLengthLong
            val contentRange = conn.getHeaderField("Content-Range")
            val headers = ArrayList<Pair<String, String>>()
            headers.add("Content-Type" to s.mime)
            headers.add("Accept-Ranges" to "bytes")
            if (contentRange != null) headers.add("Content-Range" to contentRange)
            if (length >= 0) headers.add("Content-Length" to length.toString())
            headers.add("Connection" to "close")
            val status = if (code == 206 && contentRange != null) "206 Partial Content" else "200 OK"
            writeHead(socket, status, headers)
            val input = conn.inputStream
            val out = socket.getOutputStream()
            val buf = ByteArray(64 * 1024)
            while (running) {
                val n = try {
                    input.read(buf)
                } catch (e: IOException) {
                    Log.d(TAG, "回源中断: ${e.message}")
                    break
                }
                if (n < 0) break
                out.write(buf, 0, n)
                out.flush()
            }
            runCatching { socket.shutdownOutput() }
        } catch (e: IOException) {
            Log.d(TAG, "转发中断: ${e.message}")
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** HEAD：系统播放器播前常问一次总长；用 0-0 的回源请求把 total 从 Content-Range 里取出来 */
    private fun serveHead(socket: Socket, s: Session) {
        val conn = openUpstream(s, ByteRange(0, 0))
        if (conn == null) {
            writeHead(socket, "502 Bad Gateway", listOf("Content-Length" to "0", "Connection" to "close"))
            return
        }
        try {
            val total = contentRangeTotal(conn.getHeaderField("Content-Range"))
                ?: conn.contentLengthLong.takeIf { it > 0 }
            val headers = ArrayList<Pair<String, String>>()
            headers.add("Content-Type" to s.mime)
            headers.add("Accept-Ranges" to "bytes")
            if (total != null) headers.add("Content-Length" to total.toString())
            headers.add("Connection" to "close")
            writeHead(socket, "200 OK", headers)
        } catch (e: IOException) {
            Log.d(TAG, "HEAD 失败: ${e.message}")
            writeHead(socket, "502 Bad Gateway", listOf("Content-Length" to "0", "Connection" to "close"))
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun openUpstream(s: Session, range: ByteRange?): HttpURLConnection? = runCatching {
        (URL(s.sourceUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (!s.ua.isNullOrBlank()) setRequestProperty("User-Agent", s.ua)
            setRequestProperty("Accept-Encoding", "identity")
            if (range != null) {
                setRequestProperty("Range", "bytes=${range.start}-${range.end ?: ""}")
            }
            // 立即触发连接，让失败在这里暴露（而不是等到读 body）
            connect()
        }
    }.onFailure { Log.w(TAG, "回源连接失败: ${it.message}") }.getOrNull()

    private fun writeHead(socket: Socket, status: String, headers: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 ").append(status).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        val out = socket.getOutputStream()
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    /** 读请求头到空行为止（限长，防歪包）；CRLF 与纯 LF 都认 */
    private fun readRequest(input: InputStream): ProxyRequest? {
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
        return ProxyRequest(parts[0].uppercase(), parts[1], headers)
    }

    /**
     * 解析 `Range: bytes=start-end` / `bytes=start-`。
     * 不支持 `bytes=-N`（末尾 N 字节）：那要先知道总长、得多发一次请求，系统播放器基本不用。
     */
    private fun parseByteRange(header: String?): ByteRange? {
        val v = header?.trim() ?: return null
        if (!v.startsWith("bytes=", ignoreCase = true)) return null
        val spec = v.substringAfter('=').trim()
        if (spec.contains(',')) return null
        val dash = spec.indexOf('-')
        if (dash <= 0) return null
        val start = spec.substring(0, dash).trim().toLongOrNull() ?: return null
        if (start < 0) return null
        val endText = spec.substring(dash + 1).trim()
        val end = if (endText.isEmpty()) null else endText.toLongOrNull() ?: return null
        if (end != null && end < start) return null
        return ByteRange(start, end)
    }

    /** 从 `Content-Range: bytes 0-0/123456` 取总长 */
    private fun contentRangeTotal(value: String?): Long? =
        value?.substringAfterLast('/')?.trim()?.toLongOrNull()
}
