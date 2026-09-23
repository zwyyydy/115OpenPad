package com.open115.pad.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面取色的纯逻辑。
 *
 * 这一段全在 JVM 上跑 —— 所以算法里**不碰 `android.graphics.Color` 的静态方法**，
 * 那些在单测里是 "not mocked" 直接抛。用例对应的是这条算法真实踩过的坑，不是凑覆盖率。
 */
class ToneTest {

    private fun argb(h: Float, s: Float, v: Float): Int = 0xFF000000.toInt() or hsvToRgb(h, s, v)

    private fun hsvOf(rgb: Int) = rgbToHsv((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    private fun hueOf(rgb: Int) = hsvOf(rgb)[0]
    private fun satOf(rgb: Int) = hsvOf(rgb)[1]
    private fun valOf(rgb: Int) = hsvOf(rgb)[2]

    // ---------------- 色相必须按圆周平均 ----------------

    @Test
    fun `红色跨 0 度_圆周平均不会算成青色`() {
        // 一半 350° 一半 10°。全局算术平均是 180°（青）—— 而那正好是最刺眼的补色。
        //
        // 注：当前实现是**先分桶再在桶内算**，而 12 个等宽桶里 350° 与 10° 落在不同桶
        // （桶 11 与桶 0），所以这条断言守的不是"桶内跨 0°"，而是**别把它改成"把所有色相
        // 全局算术平均"**那类改写 —— 那种写法在这里会给出青色，是这条用例真正拦下的错误。
        val px = IntArray(400) { argb(if (it % 2 == 0) 350f else 10f, 0.9f, 0.6f) }
        val h = hueOf(pickTone(px)!!)
        assertTrue("色相应落在红色附近，实际 $h", h < 40f || h > 320f)
    }

    // ---------------- 明度不设上限 ----------------

    @Test
    fun `纯亮色不被滤空`() {
        // 荧光绿 #00FF00 的 v 就是 1.0。早先版本写了 `v > 0.96f` 就跳过，
        // 结果整张鲜艳海报被滤空、取色恒回落默认色 —— 界面上只表现为「底色一直是默认的」，不报错。
        // 「接近白」的判据是饱和度低，不是明度高。
        val tone = pickTone(IntArray(400) { 0xFF00FF00.toInt() })
        assertNotNull("纯亮色必须能取出色", tone)
        val h = hueOf(tone!!)
        assertTrue("应是绿色系，实际 $h", h in 90f..150f)
    }

    // ---------------- 灰图 / 黑白图取不出色 ----------------

    @Test
    fun `灰图与黑白图返回 null`() {
        assertNull("中灰 s=0，决定不了是「什么颜色」", pickTone(IntArray(400) { 0xFF808080.toInt() }))
        assertNull("纯黑 v=0", pickTone(IntArray(400) { 0xFF000000.toInt() }))
        assertNull("纯白 s=0", pickTone(IntArray(400) { 0xFFFFFFFF.toInt() }))
    }

    @Test
    fun `有效像素太少返回 null`() {
        // 只有 2.5% 的像素有颜色（不到 1/20）→ 那是噪点，不是这部片的颜色
        val px = IntArray(400) { if (it < 10) argb(0f, 0.9f, 0.6f) else 0xFF808080.toInt() }
        assertNull(pickTone(px))
    }

    // ---------------- 出参 clamp ----------------

    @Test
    fun `出参被压到能当底色的区间`() {
        // 极艳极亮的红：s=1 v=1。出来必须够暗（压得住白字）又不至于灰掉
        val tone = pickTone(IntArray(400) { argb(0f, 1f, 1f) })!!
        assertTrue("s 应被压到 ≤0.80，实际 ${satOf(tone)}", satOf(tone) <= 0.80f + 1e-3f)
        assertTrue("v 应被压到 ≤0.68，实际 ${valOf(tone)}", valOf(tone) <= 0.68f + 1e-3f)
        assertTrue("s 不该低于 0.42", satOf(tone) >= 0.42f - 1e-3f)
        assertTrue("v 不该低于 0.42", valOf(tone) >= 0.42f - 1e-3f)
    }

    @Test
    fun `偏灰的暗色会被抬到下限`() {
        // s=0.2 v=0.3 刚过参与门槛，出来应被抬到 s≥0.42 / v≥0.42（否则底太灰看不出是这部片）
        val tone = pickTone(IntArray(400) { argb(200f, 0.2f, 0.3f) })!!
        assertTrue(satOf(tone) >= 0.42f - 1e-3f)
        assertTrue(valOf(tone) >= 0.42f - 1e-3f)
    }

    // ---------------- 色彩空间往返 ----------------

    @Test
    fun `HSV 与 RGB 往返一致`() {
        for (h in listOf(0f, 60f, 120f, 200f, 300f, 359f)) {
            val rgb = hsvToRgb(h, 0.6f, 0.5f)
            val back = hsvOf(rgb)
            assertEquals("色相往返（$h）", h, back[0], 1.5f)
            assertEquals("饱和度往返（$h）", 0.6f, back[1], 0.02f)
            assertEquals("明度往返（$h）", 0.5f, back[2], 0.02f)
        }
    }

    @Test
    fun `黑白不会算出 NaN`() {
        assertEquals("黑色的 s", 0f, rgbToHsv(0, 0, 0)[1], 1e-6f)
        assertEquals("黑色的 v", 0f, rgbToHsv(0, 0, 0)[2], 1e-6f)
        assertEquals("白色的 v", 1f, rgbToHsv(255, 255, 255)[2], 1e-6f)
        assertTrue("纯白的 s 必须是 0", rgbToHsv(255, 255, 255)[1] < 1e-6f)
    }
}
