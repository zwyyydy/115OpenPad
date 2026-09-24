package com.open115.pad.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滤镜参数：持久化编解码与预设匹配。
 *
 * 全是纯函数，不需要 Android 环境，所以放 test/ 走 JVM 单测 ——
 * 参数串是"逗号分隔 16 个 float"这种一改就容易错位的格式，肉眼对不出来；
 * 而它一旦错位，用户看到的是"画面颜色不对"而不是报错，更难查。
 */
class VideoFilterTest {

    @Test
    fun `默认参数是原图`() {
        assertTrue(FilterParams().isNeutral())
        assertTrue(FilterPresets.all[0].params.isNeutral())
    }

    @Test
    fun `任何一项偏离默认都不再算原图`() {
        // 逐个字段挑一个非默认值，确保 isNeutral 的判据覆盖了整组参数
        assertFalse(FilterParams(brightness = 0.1f).isNeutral())
        assertFalse(FilterParams(contrast = 1.1f).isNeutral())
        assertFalse(FilterParams(saturation = 0.5f).isNeutral())
        assertFalse(FilterParams(temperature = -0.1f).isNeutral())
        assertFalse(FilterParams(smooth = 0.2f).isNeutral())
        assertFalse(FilterParams(vignette = 0.3f).isNeutral())
        assertFalse(FilterParams(tint = 0.02f).isNeutral())
        assertFalse(FilterParams(shadowTintR = 0.01f).isNeutral())
        assertFalse(FilterParams(shadowTintG = 0.01f).isNeutral())
        assertFalse(FilterParams(shadowTintB = 0.01f).isNeutral())
        assertFalse(FilterParams(highlightTintR = 0.01f).isNeutral())
        assertFalse(FilterParams(highlightTintG = 0.01f).isNeutral())
        assertFalse(FilterParams(highlightTintB = 0.01f).isNeutral())
        assertFalse(FilterParams(gamma = 1.02f).isNeutral())
        assertFalse(FilterParams(fade = 0.1f).isNeutral())
        assertFalse(FilterParams(mono = 1f).isNeutral())
    }

    @Test
    fun `参数串往返不丢字段`() {
        // 每个字段都给一个互不相同的值：万一编解码顺序错位，往返对比就会炸出来
        val p = FilterParams(
            brightness = 0.01f, contrast = 1.02f, saturation = 1.03f, temperature = 0.04f,
            smooth = 0.05f, vignette = 0.06f,
            tint = 0.07f,
            shadowTintR = 0.08f, shadowTintG = 0.09f, shadowTintB = 0.10f,
            highlightTintR = 0.11f, highlightTintG = 0.12f, highlightTintB = 0.13f,
            gamma = 1.14f, fade = 0.15f, mono = 0.16f,
        )
        assertEquals(p, FilterParams.fromPrefString(p.toPrefString()))
    }

    @Test
    fun `每个预设都能原样存回来`() {
        FilterPresets.all.forEach { preset ->
            val back = FilterParams.fromPrefString(preset.params.toPrefString())
            assertEquals(preset.name, preset.params, back)
            assertEquals(preset.name, FilterPresets.indexOf(back), FilterPresets.all.indexOf(preset))
        }
    }

    @Test
    fun `坏数据一律退回原图`() {
        // 空串（从没设过）、字段数不对、字段不是数字 —— 都不能抛，播放路径上抛了就是打不开
        assertTrue(FilterParams.fromPrefString(null).isNeutral())
        assertTrue(FilterParams.fromPrefString("").isNeutral())
        assertTrue(FilterParams.fromPrefString("0,1,1").isNeutral())
        assertTrue(FilterParams.fromPrefString("a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p").isNeutral())
        assertTrue(
            FilterParams.fromPrefString("0,1,1,0,0,0,0,0,0,0,0,0,0,1,0,0,9").isNeutral(),
        )
    }

    @Test
    fun `拖过滑块后不再命中任何预设`() {
        val 电影感 = FilterPresets.all[5]
        assertEquals(5, FilterPresets.indexOf(电影感.params))
        val tweaked = 电影感.params.copy()
        tweaked.brightness += 0.1f
        assertEquals(-1, FilterPresets.indexOf(tweaked))
    }

    @Test
    fun `滑块范围覆盖得住预设取值`() {
        // 预设的值必须落在滑块量程内，否则选中预设后拖一下滑块会"跳"到量程边界
        FilterPresets.all.forEach { preset ->
            FILTER_SLIDERS.forEach { slider ->
                val v = slider.get(preset.params)
                assertTrue(
                    "${preset.name} 的 ${slider.label}=$v 超出量程 ${slider.range}",
                    v in slider.range,
                )
            }
        }
    }
}
