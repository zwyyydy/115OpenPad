package com.open115.pad.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * 本地字幕的字节处理：编码归一 / 格式嗅探 / 内容校验。
 *
 * 「加载本地字幕」这条链路全押在这几个纯函数上，而它们错了用户看到的是
 * 「字幕乱码」或「点了没反应」——不报错、也看不出是哪一步坏的，所以单独盯住。
 */
class SubtitleBytesTest {

    private val srt = """
        1
        00:00:01,000 --> 00:00:03,000
        你好，世界

    """.trimIndent()

    @Test
    fun `UTF-8 的 srt 原样返回`() {
        val bytes = srt.toByteArray(Charsets.UTF_8)
        assertSame(bytes, normalizeSubtitleEncoding(bytes))
    }

    @Test
    fun `GBK 的中文 srt 会被转成 UTF-8`() {
        val gbk = srt.toByteArray(Charset.forName("GBK"))
        // 前提：这份内容在 GBK 下确实不是合法 UTF-8，否则用例测不到东西
        assertFalse(String(gbk, Charsets.UTF_8) == srt)
        val out = normalizeSubtitleEncoding(gbk)
        assertEquals(srt, String(out, Charsets.UTF_8))
    }

    @Test
    fun `UTF-16 带 BOM 的 srt 转成不带 BOM 的 UTF-8`() {
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + srt.toByteArray(Charsets.UTF_16LE)
        assertEquals(srt, String(normalizeSubtitleEncoding(utf16), Charsets.UTF_8))
    }

    @Test
    fun `纯 ASCII 的 srt 不会被误判成 GBK`() {
        val ascii = "1\n00:00:01,000 --> 00:00:03,000\nhello\n".toByteArray(Charsets.US_ASCII)
        assertSame(ascii, normalizeSubtitleEncoding(ascii))
    }

    @Test
    fun `srt-ass-vtt 都认得出来`() {
        assertTrue(looksLikeSubtitle(srt.toByteArray(Charsets.UTF_8)))
        assertTrue(looksLikeSubtitle("[Script Info]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,嗨".toByteArray()))
        assertTrue(looksLikeSubtitle("WEBVTT\n\n00:00.000 --> 00:02.000\nhi".toByteArray()))
    }

    @Test
    fun `随手选中的视频文件不会被当成字幕`() {
        // 头部有 non-UTF8 字节 + 没有任何时间轴标记
        val junk = ByteArray(4096) { (it % 251).toByte() }
        assertFalse(looksLikeSubtitle(junk))
    }

    @Test
    fun `后缀可信时按后缀走，不可信时按内容嗅探`() {
        val ass = "[Script Info]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,嗨".toByteArray()
        assertEquals("ass", guessSubtitleExt("movie.zh.ass", ass))
        // SAF 常给这种没后缀的名字，只能看内容
        assertEquals("ass", guessSubtitleExt("字幕", ass))
        assertEquals("vtt", guessSubtitleExt("WEBVTT", "WEBVTT\n".toByteArray()))
    }

    @Test
    fun `嗅探只看开头，超大文件也不会整串解码`() {
        val marker = "1\n00:00:01,000 --> 00:00:03,000\nhi\n".toByteArray()
        // 标记埋在 100KB 之后（远超 64KB 嗅探窗口）→ 判不出来，正是我们要的代价可控
        val padded = ByteArray(100 * 1024) { ' ' .code.toByte() } + marker
        assertFalse(looksLikeSubtitle(padded))
        assertTrue(looksLikeSubtitle(marker + ByteArray(100 * 1024) { ' '.code.toByte() }))
    }

    @Test
    fun `时间轴平移在归一后的字节上照常工作`() {
        val out = shiftSubtitleTimestamps(srt.toByteArray(Charsets.UTF_8), 500L)
        assertTrue(String(out, Charsets.UTF_8).contains("00:00:01,500 --> 00:00:03,500"))
    }
}
