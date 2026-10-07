package com.open115.pad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dHash 与"两版画面是否同一份"的判据测试。
 *
 * 阈值与"同内容降质 4~11 / 不同内容 24~39"来自 2026-09-30 的真机实测；这里用**构造的灰度图**
 * 复现三种情形：同一画面（含被压暗的降质版）、不同画面、以及"帧数不够不给结论"。
 */
class FrameCompareTest {

    /** 造 9x8 灰度：`shift` 整体加减亮度，`invert` 翻转明暗 → 会改变 dHash 的可行性 */
    private fun gray(seed: Int, shift: Int = 0, invert: Boolean = false): IntArray {
        val out = IntArray(9 * 8)
        for (y in 0 until 8) {
            for (x in 0 until 9) {
                // 与坐标和种子有关的确定性图案
                val v = ((x * 37 + y * 91 + seed * 53) % 200) + 20
                out[y * 9 + x] = if (invert) 220 - v else (v + shift).coerceIn(0, 255)
            }
        }
        return out
    }

    @Test
    fun `同一画面整体变亮变暗，哈希不变`() {
        val a = dHashOf(gray(7))
        val brighter = dHashOf(gray(7, shift = 30))
        val darker = dHashOf(gray(7, shift = -20))
        assertEquals(a, brighter)
        assertEquals(0, hamming(a, darker))
    }

    @Test
    fun `不同内容距离明显大于同一内容`() {
        val a = dHashOf(gray(1))
        val b = dHashOf(gray(2))
        val same = dHashOf(gray(1, shift = 10))
        assertTrue("同一画面必须过阈值：${hamming(a, same)}", hamming(a, same) <= DHASH_SAME_THRESHOLD)
        assertTrue("不同画面不该过阈值：${hamming(a, b)}", hamming(a, b) > DHASH_SAME_THRESHOLD)
    }

    @Test
    fun `三帧多数表决`() {
        val base = List(3) { dHashOf(gray(it)) }
        // 两帧一致 + 一帧不同 → 判"画面相同"
        val mostlySame = listOf(base[0], base[1], dHashOf(gray(9)))
        val r1 = compareFrameHashes(base, mostlySame)!!
        assertEquals(2, r1.sameFrames)
        assertTrue(r1.same)
        // 三帧都不一致 → 判"不同"
        val allDiff = List(3) { dHashOf(gray(100 + it)) }
        val r2 = compareFrameHashes(base, allDiff)!!
        assertFalse(r2.same)
    }

    @Test
    fun `按位对齐比较，帧数不够就不给结论`() {
        val a = List(3) { dHashOf(gray(it)) }
        // 只剩两帧（有一个取样点抓帧失败）→ 用两帧比，仍可比
        val r = compareFrameHashes(a, a.take(2))!!
        assertEquals(2, r.totalFrames)
        assertTrue(r.same)
        // 一边一帧都没有 → null（"没比成"不等于"不同"）
        assertNull(compareFrameHashes(emptyList(), a))
        assertNull(compareFrameHashes(a, emptyList()))
    }

    @Test
    fun `距离文案分档`() {
        assertEquals("同一画面（0）", distanceHint(0))
        assertEquals("同一画面（14）", distanceHint(14))
        assertEquals("有差异（15）", distanceHint(15))
        assertEquals("明显不同（31）", distanceHint(31))
    }
}
