package com.open115.pad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HLS 解析与「抓最小的那档」的判据测试。
 *
 * 输入都用 **2026-10-07 真机探针抓下来的真实 playlist 片段**（形状一致，域名/令牌已替换），
 * 改解析逻辑时这些用例要能挡住"分片 URL 解析不出来""取样点算错"这类只有真机才暴露的错。
 */
class VideoFrameGrabTest {

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="Audio-Group",NAME="stereo",DEFAULT=YES,URI="https://cpats01.115.com/audio/0.m3u8?ck="
        #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=3000000,RESOLUTION=1920x1080,AUDIO="Audio-Group",NAME="UD"
        https://cpats01.115.com/0123456789ab/video-1080p.m3u8
    """.trimIndent()

    private val media = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:10
        #EXT-X-MEDIA-SEQUENCE:0
        #EXTINF:10.000000,
        /abcdef1234567890/video-1080p/video-00001.ts
        #EXTINF:10.000000,
        /abcdef1234567890/video-1080p/video-00002.ts
        #EXTINF:5.000000,
        /abcdef1234567890/video-1080p/video-00003.ts
    """.trimIndent()

    @Test
    fun `master playlist 认出变体，音频组被忽略`() {
        val p = parseHlsPlaylist(master, "http://videoplay.115.com/m3u8/abc")
        assertTrue(p.isMaster)
        assertEquals(1, p.variants.size)
        assertEquals(3_000_000L, p.variants[0].bandwidth)
        assertEquals("https://cpats01.115.com/0123456789ab/video-1080p.m3u8", p.variants[0].url)
        // 音频 URI 在 `#EXT-X-MEDIA` 注释行里，绝不能当成视频分片
        assertTrue(p.segments.isEmpty())
    }

    @Test
    fun `media playlist 分片时有起始时间，根相对路径补成绝对`() {
        val p = parseHlsPlaylist(media, "https://cpats01.115.com/0123456789ab/video-1080p.m3u8")
        assertFalse(p.isMaster)
        assertEquals(3, p.segments.size)
        assertEquals(0.0, p.segments[0].startSec, 0.001)
        assertEquals(10.0, p.segments[1].startSec, 0.001)
        assertEquals(20.0, p.segments[2].startSec, 0.001)
        assertEquals(25.0, p.totalSec, 0.001)
        assertEquals(
            "https://cpats01.115.com/abcdef1234567890/video-1080p/video-00001.ts",
            p.segments[0].url,
        )
    }

    @Test
    fun `取样点落在哪个分片：10 秒分片下取 30% 是第三片`() {
        val p = parseHlsPlaylist(media, "https://cpats01.115.com/x.m3u8")
        // 总长 25s，30% = 7.5s → 第一个分片（0~10s）
        assertEquals(0.0, pickSegment(p.segments, 0.3)!!.startSec, 0.001)
        // 50% = 12.5s → 第二个分片
        assertEquals(10.0, pickSegment(p.segments, 0.5)!!.startSec, 0.001)
        // 末端（比例 1.0）不能越界取空，落到最后一片
        assertEquals(20.0, pickSegment(p.segments, 1.0)!!.startSec, 0.001)
        assertNull(pickSegment(emptyList(), 0.5))
    }

    @Test
    fun `加密与初始化段能被认出来`() {
        val enc = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://k/1.key"
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.0,
            seg1.m4s
        """.trimIndent()
        val p = parseHlsPlaylist(enc, "https://cdn/x/playlist.m3u8")
        assertTrue(p.encrypted)
        assertEquals("https://cdn/x/init.mp4", p.initSegment)
        assertEquals("https://cdn/x/seg1.m4s", p.segments[0].url)
        // METHOD=NONE 不算加密（重签名的 playlist 会出现这种写法）
        val none = parseHlsPlaylist("#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:1,\na.ts", "https://c/p.m3u8")
        assertFalse(none.encrypted)
    }

    @Test
    fun `挑最小转码档：排除原画与原盘`() {
        val urls = listOf(
            VideoUrlEntry("u999", 999, 0, 0),
            VideoUrlEntry("u100", 100, 0, 0),
            VideoUrlEntry("u5", 5, 2160, 3840),
            VideoUrlEntry("u4", 4, 1080, 1920),
            VideoUrlEntry("u3", 3, 720, 1280),
        )
        assertEquals(3, lowestTier(urls)!!.definition)
        // 只有原画（115 还没转码）→ 没有可用的转码档
        assertNull(lowestTier(listOf(VideoUrlEntry("u100", 100, 0, 0), VideoUrlEntry("u999", 999, 0, 0))))
        assertNull(lowestTier(emptyList()))
    }

    @Test
    fun `抓帧档位：优先转码档，没有转码档才退到原画`() {
        val mixed = listOf(
            VideoUrlEntry("u100", 100, 1080, 1920),
            VideoUrlEntry("u4", 4, 1080, 1920),
            VideoUrlEntry("u2", 2, 480, 854),
        )
        // 有转码档就用转码档（哪怕原画排在前面）
        assertEquals(2, previewTier(mixed)!!.definition)
        // 只有原画（用户说的"只给原画/原盘两个挡位"）：退到原画，它同样是 HLS，能抓
        assertEquals(100, previewTier(listOf(VideoUrlEntry("u100", 100, 0, 0), VideoUrlEntry("u999", 999, 0, 0)))!!.definition)
        // 原盘不是 HLS，不能拿它当抓帧源
        assertNull(previewTier(listOf(VideoUrlEntry("u999", 999, 0, 0))))
        assertNull(previewTier(emptyList()))
    }

    @Test
    fun `变体挑最低码率`() {
        val vs = listOf(HlsVariant(8_000_000, "a"), HlsVariant(1_500_000, "b"), HlsVariant(3_000_000, "c"))
        assertEquals("b", pickLowestVariant(vs)!!.url)
        assertNull(pickLowestVariant(emptyList()))
    }

    @Test
    fun `绝对 URL 三种写法`() {
        assertEquals("https://a/b.ts", absUrl("https://a/x/p.m3u8", "https://a/b.ts"))
        assertEquals("https://a/b.ts", absUrl("https://a/x/p.m3u8", "/b.ts"))
        assertEquals("https://a/x/b.ts", absUrl("https://a/x/p.m3u8", "b.ts"))
    }
}
