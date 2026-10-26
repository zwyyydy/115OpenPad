package com.open115.pad.data

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterCropSpecTest {

    private fun pixelAspect(rect: PosterCropRect, w: Int, h: Int): Float =
        (rect.width * w) / (rect.height * h)

    /** 默认兜底海报：右半张 + 2:3，且完全落在图内 */
    @Test
    fun defaultRectIsRightHalfPosterAspect() {
        val rect = defaultPosterCrop(1920, 1080)
        assertEquals(2f / 3f, pixelAspect(rect, 1920, 1080), 0.005f)
        assertTrue("应在右半张里", rect.left >= 0.5f - 1e-4f)
        assertTrue("不越过右边界", rect.right <= 1f + 1e-4f)
        assertTrue(rect.top >= -1e-4f && rect.bottom <= 1f + 1e-4f)
        // 16:9 右半张的 2:3 可见区：上下顶满，宽度约 0.375
        assertEquals(1f, rect.height, 0.002f)
        assertTrue(abs(rect.width - (1080f * 2f / 3f) / 1920f) < 0.002f)
    }

    /** 竖图/接近 2:3 的图也不会越界，且保持 2:3 */
    @Test
    fun defaultRectStaysInsideForPortraitSource() {
        val rect = defaultPosterCrop(800, 1600)
        assertEquals(2f / 3f, pixelAspect(rect, 800, 1600), 0.005f)
        assertTrue(rect.left >= 0.5f - 1e-4f)
        assertTrue(rect.right <= 1f + 1e-4f)
        assertTrue(rect.top >= -1e-4f)
        assertTrue(rect.bottom <= 1f + 1e-4f)
    }

    @Test
    fun clampedKeepsPositiveSizeAndInsideImage() {
        val rect = PosterCropRect(left = -1f, top = 0.2f, right = 2f, bottom = 2f).clamped()
        assertTrue(rect.left >= 0f && rect.right <= 1f)
        assertTrue(rect.top >= 0f && rect.bottom <= 1f)
        assertTrue(rect.width > 0f && rect.height > 0f)
    }

    @Test
    fun clampedHandlesDegenerateEdgeWithoutThrowing() {
        val rect = PosterCropRect(1f, 1f, 1f, 1f).clamped()
        assertTrue(rect.width > 0f && rect.height > 0f)
        assertTrue(rect.right <= 1f && rect.bottom <= 1f)
    }

    @Test
    fun approxEqualsToleratesRoundingOnly() {
        val a = PosterCropRect(0.5625f, 0f, 0.9375f, 1f)
        assertTrue(a.approxEquals(PosterCropRect(0.563f, 0.0005f, 0.937f, 0.9995f)))
        assertTrue(!a.approxEquals(PosterCropRect(0.30f, 0f, 0.67f, 1f)))
    }
}
