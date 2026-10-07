package com.open115.pad.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签分类：哪些不进推荐统计、哪些算"系列"。
 *
 * 用例名字的**形状**取自真实媒体库的 `tags` 表（写成了占位写法）—— 这条规则最容易犯的错是"看着像就杀"，
 * 而库里真有一批**以画质词开头**的编号前缀（`SDXX` / `300XXX`）和含数字的内容标签
 * （`3D、4D` / `12小时+`），它们必须活着。判据只认**整段**匹配，这里把两类都钉住。
 */
class TagSignalsTest {

    private fun noise(name: String) = TagSignals.isNoise(name)

    @Test
    fun `分辨率与编码类的整词一律不算信号`() {
        listOf(
            "1080P", "720p", "480P", "2160P", "1080I", "4K", "8K", "2K",
            "AVC1", "HEVC", "HEV1", "HVC1", "H264", "H.265", "AVC-1", "HEVC", "VP09", "MP4V", "WMV2", "WVC1",
            "HD", "FHD", "UHD", "60fps", "10bit", "HDR10",
        ).forEach { assertTrue("[$it] 是画质/编码标签", noise(it)) }
    }

    @Test
    fun `全角与大小写都归一`() {
        assertTrue(noise("１０８０Ｐ"))
        assertTrue(noise("4k"))
        assertTrue(noise("　AVC1　"))
    }

    @Test
    fun `日文的画质整词也认`() {
        listOf("フルハイビジョン(FHD)", "ハイビジョン", "全高清 (FHD)", "HD高画质").forEach {
            assertTrue("[$it] 是画质标签", noise(it))
        }
    }

    @Test
    fun `多个画质 token 拼起来也算 —— 拆开每段都认`() {
        assertTrue(noise("1080P 60FPS"))
        assertTrue(noise("AVC1/1080P"))
        assertTrue(noise("FHD 60FPS"))
    }

    @Test
    fun `内容标签一个都不能误伤`() {
        listOf(
            "示例标签甲", "示例标签乙", "示例标签丙", "示例标签丁", "示例标签戊", "12小时+", "1995",
            "示例标签己", "VR", "3D、4D", "3D", "1995年", "2018", "另一个系列",
            // 以画质词开头的番号前缀：整段不匹配就不算
            "SDXX", "SDYY", "SDZZ", "300XXX", "259XXX", "8KVR", "4K示例",
        ).forEach { assertFalse("[$it] 不是画质标签，别误伤", noise(it)) }
    }

    @Test
    fun `空名与纯符号不算信号`() {
        assertTrue(TagSignals.isNoise(""))
        assertTrue(TagSignals.isNoise("   "))
        assertTrue(TagSignals.isNoise("·"))
    }

    @Test
    fun `系列前缀认得半角与全角冒号，取显示名时去掉前缀`() {
        assertTrue(TagSignals.isSeries("系列: 示例系列"))
        assertTrue(TagSignals.isSeries("系列：示例系列"))
        assertTrue(TagSignals.isSeries("  系列: 示例系列"))
        assertFalse(TagSignals.isSeries("另一个系列"))
        assertEquals("示例系列", TagSignals.seriesLabel("系列: 示例系列"))
        assertEquals("示例系列", TagSignals.seriesLabel("系列：示例系列"))
        // 给 SQL 的模式必须与 isSeries 同一个前缀
        assertEquals("系列%", TagSignals.SERIES_LIKE)
    }

    @Test
    fun `系列标签本身不是噪声`() {
        assertFalse(TagSignals.isNoise("系列: 1080P 60FPS"))
    }
}
