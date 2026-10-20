package com.open115.pad.data.magnet

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 磁力预览客户端的**纯解析层**测试。
 *
 * 下面几条 payload 是 2026-10-14 从 whatslink.info 实际抓到的原样响应（含截断的截图数组），
 * 用来把形状钉住：站方没有文档承诺，解析宽匹配/兜底被改坏时要能在这里发现。
 */
class WhatsLinkClientTest {

    private val client = WhatsLinkClient(
        base = "https://example.invalid",
        http = OkHttpClient(),
    )

    @Test
    fun `视频类种子 - 类型大小文件数截图都解析出来`() {
        val text = """
            {"error":"","type":"FOLDER","file_type":"folder","name":"Sintel","size":129302391,"count":11,
             "screenshots":[
               {"time":0,"screenshot":"https://whatslink.info/image/13e708d6f0868af9bd8d96f4516d14c2"},
               {"time":0,"screenshot":"https://whatslink.info/image/4cc70368cd7e02ba117596f84848b1a2"},
               {"time":0,"screenshot":"https://whatslink.info/image/95511fee927aa4a498faaa8bda9ba248"}
             ]}
        """.trimIndent()
        val p = client.parsePreview(text)
        assertTrue(p.resolved)
        assertEquals("Sintel", p.name)
        assertEquals("目录", p.kindLabel)
        assertEquals("123.3 MB", p.sizeText)
        assertEquals(11, p.count)
        assertEquals(3, p.screenshots.size)
        assertTrue(p.screenshots[0].startsWith("https://whatslink.info/image/"))
    }

    @Test
    fun `非视频种子 - 没有截图也不报错 - 单个文件也照实返回`() {
        val text = """{"error":"","type":"FILE","file_type":"archive",
            "name":"debian-13.7.0-amd64-netinst.iso","size":792723456,"count":1,"screenshots":null}"""
        val p = client.parsePreview(text)
        assertTrue(p.resolved)
        assertEquals("压缩包", p.kindLabel)
        assertEquals("756.0 MB", p.sizeText)
        assertEquals(1, p.count)
        assertTrue(p.screenshots.isEmpty())
    }

    @Test
    fun `解析不了的种子 - type UNKNOWN - resolved 为 false 且不带摘要`() {
        val text = """{"error":"","type":"UNKNOWN","file_type":"","name":"${"0".repeat(40)}",
            "size":0,"count":1,"screenshots":null}"""
        val p = client.parsePreview(text)
        assertFalse(p.resolved)
        assertNull(p.kindLabel)
        assertNull(p.sizeText)
        assertTrue(p.screenshots.isEmpty())
    }

    @Test
    fun `截图数组里的异常项丢掉 - 只认 http 开头的`() {
        val text = """{"error":"","type":"FOLDER","file_type":"folder","name":"x","size":1024,"count":2,
            "screenshots":[{"time":0,"screenshot":"https://whatslink.info/image/aa"},
                           {"time":0,"screenshot":""},
                           {"time":0},
                           "not-an-object"]}"""
        val p = client.parsePreview(text)
        assertEquals(1, p.screenshots.size)
    }

    @Test
    fun `响应里写明 error 时抛出来给人话`() {
        try {
            client.parsePreview("""{"error":"invalid link"}""")
            fail("应当抛 PreviewException")
        } catch (e: WhatsLinkClient.PreviewException) {
            assertEquals("invalid link", e.message)
        }
    }

    @Test
    fun `不是 JSON 的响应抛异常`() {
        try {
            client.parsePreview("<html>502 Bad Gateway</html>")
            fail("应当抛 PreviewException")
        } catch (e: WhatsLinkClient.PreviewException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `磁力链整条百分号编码 - 空格不能编成加号`() {
        val magnet = "magnet:?xt=urn:btih:${"a".repeat(40)}&dn=某资源 2024 1080p"
        val enc = client.encode(magnet)
        assertFalse(enc.contains("+"))
        assertFalse(enc.contains("&"))
        assertTrue(enc.startsWith("magnet%3A%3Fxt%3Durn%3Abtih%3A"))
        assertTrue(enc.contains("%20"))
    }
}
