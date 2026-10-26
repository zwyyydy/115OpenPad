package com.open115.pad.cast

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI

/**
 * 本机中转的端到端测试：真起一个 HTTP 服务，假上游也真回 Range/206。
 *
 * 这里盯的是"电视能不能拉、能不能拖进度"：
 * - 无 Range 的整段 GET 必须带 Content-Length；
 * - Range 必须原样透传成 206 + Content-Range（差一字节就会黑屏/卡住）；
 * - HEAD 要报总长度（不少电视播前先问一次）；
 * - 换个 token 必须 404（不然同一 Wi-Fi 下谁都能拉）。
 *
 * 用 127.0.0.1 访问而不是返回的局域网地址：服务器绑的是 0.0.0.0，两种地址等价，
 * 但回环不受本机防火墙影响，测试结果稳定。
 */
class CastRelayServerTest {

    private var upstream: FakeUpstream? = null
    private var relay: CastRelayServer? = null
    private var baseUrl: String = ""
    private var path: String = ""

    private val data = ByteArray(1000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        // 没有局域网 IPv4（纯离线的 CI/容器）时中转本身就拒绝启动，跳过用例
        assumeNotNull(lanIpv4())
        val up = FakeUpstream(data)
        upstream = up

        val r = CastRelayServer(OkHttpClient())
        val url = r.start("http://127.0.0.1:${up.port}/file", "a b.mp4", "video/mp4")
        relay = r
        val uri = URI(url)
        assertNotNull(uri.port)
        baseUrl = "http://127.0.0.1:${uri.port}"
        path = uri.rawPath
        assertTrue(path.endsWith("a%20b.mp4"))
    }

    @After
    fun tearDown() {
        relay?.stop()
        upstream?.close()
    }

    @Test
    fun fullGetPassesThroughLengthAndBody() {
        val client = OkHttpClient()
        client.newCall(Request.Builder().url(baseUrl + path).build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("video/mp4", resp.header("Content-Type"))
            assertEquals("bytes", resp.header("Accept-Ranges"))
            assertEquals("1000", resp.header("Content-Length"))
            val body = resp.body?.bytes()
            assertEquals(1000, body?.size)
            assertEquals(data[999], body!![999])
        }
    }

    @Test
    fun rangeGetReturns206WithContentRange() {
        val client = OkHttpClient()
        client.newCall(
            Request.Builder().url(baseUrl + path).header("Range", "bytes=10-19").build(),
        ).execute().use { resp ->
            assertEquals(206, resp.code)
            assertEquals("bytes 10-19/1000", resp.header("Content-Range"))
            assertEquals("10", resp.header("Content-Length"))
            val body = resp.body?.bytes()
            assertEquals(10, body?.size)
            assertEquals(data[10], body!![0])
        }
    }

    @Test
    fun headReportsTotalLength() {
        val client = OkHttpClient()
        client.newCall(Request.Builder().url(baseUrl + path).head().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("1000", resp.header("Content-Length"))
            assertEquals("bytes", resp.header("Accept-Ranges"))
        }
    }

    @Test
    fun wrongTokenIs404() {
        val client = OkHttpClient()
        val wrongPath = path.replaceFirst(Regex("/[0-9a-f]{16}/"), "/deadbeefdeadbeef/")
        client.newCall(Request.Builder().url(baseUrl + wrongPath).build()).execute().use { resp ->
            assertEquals(404, resp.code)
        }
    }

    /**
     * 假上游：只支持 /file 的整段与单段 Range，`Connection: close`。
     * 刻意不用 com.sun.net.httpserver —— 它不在 Android 单测的编译类路径里。
     */
    private class FakeUpstream(private val data: ByteArray) : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort

        @Volatile
        private var running = true

        private val acceptThread = Thread {
            while (running) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                Thread {
                    runCatching { serve(socket) }
                }.apply { isDaemon = true }.start()
            }
        }.apply {
            isDaemon = true
            start()
        }

        private fun serve(socket: java.net.Socket) {
            socket.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val head = readHead(input) ?: return
                val rangeLine = head.lineSequence()
                    .firstOrNull { it.lowercase().startsWith("range:") }
                val range = parseByteRange(rangeLine?.substringAfter(':')?.trim())
                val out = s.getOutputStream()
                if (range != null) {
                    val start = range.start.toInt().coerceIn(0, data.size)
                    val end = (range.end ?: (data.size - 1L)).toInt().coerceIn(start, data.size - 1)
                    val len = end - start + 1
                    out.write(
                        ("HTTP/1.1 206 Partial Content\r\n" +
                            "Content-Range: bytes $start-$end/${data.size}\r\n" +
                            "Content-Length: $len\r\nConnection: close\r\n\r\n").toByteArray(),
                    )
                    out.write(data, start, len)
                } else {
                    out.write(
                        ("HTTP/1.1 200 OK\r\n" +
                            "Content-Length: ${data.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                    )
                    out.write(data)
                }
                out.flush()
            }
        }

        private fun readHead(input: InputStream): String? {
            val sb = StringBuilder()
            while (sb.length < 16 * 1024) {
                val b = input.read()
                if (b < 0) return null
                sb.append(b.toChar())
                if (sb.endsWith("\r\n\r\n") || sb.endsWith("\n\n")) return sb.toString()
            }
            return null
        }

        override fun close() {
            running = false
            runCatching { server.close() }
            acceptThread.interrupt()
        }
    }
}
