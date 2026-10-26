package com.open115.tv.receiver

import android.util.Log
import com.open115.tv.BuildConfig
import kotlinx.serialization.encodeToString
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val TAG = "TvReceiver"

/**
 * 局域网接收端：UDP 发现应答 + HTTP JSON 控制端点。
 *
 * 为什么手写 Socket 而不用 Ktor 之类的框架：整条协议只有 4 个端点、报文都是小 JSON，
 * 手写 ServerSocket 的解析量不到 100 行（115pad 的中转服务也是这个路子），
 * 换框架等于为了 4 个接口拖进一整套 HTTP 栈。
 *
 * 与 115pad 中转服务的两条硬纪律相同：
 * - 请求头读到空行为止且限长（局域网里可能有别家的歪包）；
 * - `Connection: close`：请求-响应一次一连接，不处理 keep-alive，简单且不会漏读 body。
 *
 * 网络线程池与主线程完全隔离；[statusProvider] 只读 PlayBus 的 StateFlow，线程安全。
 */
internal class ReceiverServer(
    private val deviceName: String,
    private val statusProvider: () -> StatusResponse,
    private val onPlay: (PlayRequest) -> Unit,
    private val onControl: (ControlRequest) -> Unit,
    private val onQueue: (QueueRequest) -> Unit,
    private val onSubsPush: (SubsPushRequest) -> Unit,
) {
    private data class HttpRequest(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "tv-receiver").apply { isDaemon = true }
    }
    private val sockets: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    private var running = false
    private var httpServer: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null

    /**
     * 启动监听。
     *
     * @throws IOException 端口被占/无网络权限：由调用方（前台服务）决定重试节奏。
     */
    @Synchronized
    fun start() {
        if (running) return
        val http = ServerSocket(TvProtocol.HTTP_PORT)
        val udp = DatagramSocket(null).apply {
            // 服务重启（换端口竞争、被系统杀掉后快速重来）时不该被上一次的绑定卡住
            reuseAddress = true
            bind(InetSocketAddress(TvProtocol.DISCOVER_PORT))
        }
        httpServer = http
        udpSocket = udp
        running = true
        executor.execute { acceptLoop(http) }
        executor.execute { udpLoop(udp) }
        Log.i(TAG, "接收端就绪: http=${TvProtocol.HTTP_PORT} udp=${TvProtocol.DISCOVER_PORT} name=$deviceName")
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { httpServer?.close() }
        runCatching { udpSocket?.close() }
        httpServer = null
        udpSocket = null
        for (s in sockets.toList()) runCatching { s.close() }
        sockets.clear()
    }

    // ---------- UDP 发现 ----------

    private fun udpLoop(udp: DatagramSocket) {
        val buf = ByteArray(2048)
        while (running) {
            val packet = DatagramPacket(buf, buf.size)
            val received = try {
                udp.receive(packet)
                true
            } catch (e: IOException) {
                // receive 的 SocketTimeoutException 与被 close 后的 SocketException 都走这里：
                // running 已翻假就是正常退出，否则只是这一轮超时，接着等
                if (running) Log.d(TAG, "udp receive: ${e.message}")
                false
            }
            if (!received || !running) continue
            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
            if (parseFindBeacon(text) == null) continue
            val reply = protocolJson.encodeToString(HereBeacon.serializer(), HereBeacon(name = deviceName))
            val bytes = reply.toByteArray(Charsets.UTF_8)
            runCatching { udp.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port)) }
                .onFailure { Log.d(TAG, "udp reply: ${it.message}") }
        }
    }

    // ---------- HTTP ----------

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
                    // 平板查完状态就断是常态
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
        socket.soTimeout = 10_000
        val req = readRequest(BufferedInputStream(socket.getInputStream())) ?: return
        val path = req.target.substringBefore('?').trim()
        when {
            req.method == "GET" && path == "/ping" -> respond(
                socket,
                "200 OK",
                protocolJson.encodeToString(PingResponse(ver = BuildConfig.VERSION_NAME)),
            )

            req.method == "GET" && path == "/status" -> respond(
                socket,
                "200 OK",
                protocolJson.encodeToString(statusProvider()),
            )

            req.method == "POST" && path == "/play" -> {
                val play = runCatching { protocolJson.decodeFromString<PlayRequest>(req.body) }.getOrNull()
                if (play == null || play.url.isBlank()) {
                    respond(socket, "200 OK", protocolJson.encodeToString(OkResponse(false, "播放指令无法解析")))
                } else {
                    Log.i(TAG, "收到投屏: ${play.title} pos=${play.positionMs}")
                    onPlay(play)
                    respond(socket, "200 OK", protocolJson.encodeToString(OkResponse(true)))
                }
            }

            req.method == "POST" && path == "/control" -> {
                val ctl = runCatching { protocolJson.decodeFromString<ControlRequest>(req.body) }.getOrNull()
                if (ctl == null) {
                    respond(socket, "200 OK", protocolJson.encodeToString(OkResponse(false, "控制指令无法解析")))
                } else {
                    onControl(ctl)
                    respond(socket, "200 OK", protocolJson.encodeToString(OkResponse(true)))
                }
            }

            // 连播队列：平板在 /play 之后异步补推（当前集已经开播，这里只追加后续集）
            req.method == "POST" && path == "/queue" -> {
                val q = runCatching { protocolJson.decodeFromString<QueueRequest>(req.body) }.getOrNull()
                if (q == null || q.queue.isEmpty()) {
                    respond(socket, "200 OK", protocolJson.encodeToString(OkResponse(false, "连播队列无法解析")))
                } else {
                    Log.i(TAG, "收到连播队列: ${q.queue.size} 条 anchor=${q.anchor}")
                    onQueue(q)
                    respond(socket, "200 OK", protocolJson.encodeToString(OkResponse(true)))
                }
            }

            // 外挂字幕推送：平板从在线字幕库代下载好的内容（base64），电视端落盘挂轨
            req.method == "POST" && path == "/subs" -> {
                val push = runCatching { protocolJson.decodeFromString<SubsPushRequest>(req.body) }.getOrNull()
                if (push == null || push.data.isBlank()) {
                    respond(socket, "200 OK", protocolJson.encodeToString(OkResponse(false, "字幕内容无法解析")))
                } else {
                    Log.i(TAG, "收到外挂字幕: ${push.name} base64=${push.data.length}")
                    onSubsPush(push)
                    respond(socket, "200 OK", protocolJson.encodeToString(OkResponse(true)))
                }
            }

            else -> respond(socket, "404 Not Found", protocolJson.encodeToString(OkResponse(false, "not found")))
        }
    }

    private fun respond(socket: Socket, status: String, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    /** 读到空行为止；头限长 16KB，body 限长 16MB（小 JSON 之外还可能来一路 base64 字幕） */
    private fun readRequest(input: InputStream): HttpRequest? {
        val head = StringBuilder()
        while (head.length < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
            // 标准客户端 CRLF，个别实现只发 LF，都认
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
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        val body = if (length > 0 && length <= 16L * 1024L * 1024L) {
            val buf = ByteArray(length.toInt())
            var read = 0
            while (read < buf.size) {
                val n = try {
                    input.read(buf, read, buf.size - read)
                } catch (e: IOException) {
                    break
                }
                if (n < 0) break
                read += n
            }
            String(buf, 0, read, Charsets.UTF_8)
        } else {
            ""
        }
        return HttpRequest(parts[0].uppercase(), parts[1], headers, body)
    }
}
