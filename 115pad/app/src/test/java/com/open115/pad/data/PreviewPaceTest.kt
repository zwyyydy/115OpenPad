package com.open115.pad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批量预览图的阶梯节拍：档位边界与 ±10% 抖动范围 */
class PreviewPaceTest {

    @Test
    fun tierBoundaries() {
        // ≤10 部 2s；11~20 部 4s；21 部起 6s（0 也按第一档，实际不会用到）
        assertEquals(2_000L, previewPaceBaseMs(0))
        assertEquals(2_000L, previewPaceBaseMs(1))
        assertEquals(2_000L, previewPaceBaseMs(10))
        assertEquals(4_000L, previewPaceBaseMs(11))
        assertEquals(4_000L, previewPaceBaseMs(20))
        assertEquals(6_000L, previewPaceBaseMs(21))
        assertEquals(6_000L, previewPaceBaseMs(999))
    }

    @Test
    fun jitterStaysWithinTenPercent() {
        for (done in listOf(1, 10, 11, 20, 21, 100)) {
            val base = previewPaceBaseMs(done)
            repeat(200) {
                val ms = previewPaceMs(done)
                assertTrue(
                    "done=$done 抖动越界：$ms（基准 $base）",
                    ms in (base * 0.9).toLong()..(base * 1.1).toLong(),
                )
            }
        }
    }
}
