package com.open115.pad.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双指缩放/平移的纯逻辑：以捏合中心为不动点、平移按画面框夹范围、缩回 1 倍归零。
 *
 * 这些错了不会报错，只会表现为"放大后画面跑偏/能拖出黑边/缩回去还剩一半偏移"，
 * 所以单独盯住。
 */
class PlayerZoomTest {

    private fun state(w: Float = 1000f, h: Float = 500f) = VideoZoomState().apply {
        viewport = Size(w, h)
    }

    /** 画面中心缩放：只放大、不产生平移 */
    @Test
    fun zoomAroundCenterKeepsPanZero() {
        val s = state()
        s.apply(2f, 0f, 0f, Offset.Zero)
        assertEquals(2f, s.scale, 1e-4f)
        assertEquals(0f, s.panX, 1e-4f)
        assertEquals(0f, s.panY, 1e-4f)
    }

    /** 捏合中心偏离画面中心：该点下方的内容保持不动（pan 补偿） */
    @Test
    fun zoomAroundOffsetCentroidCompensatesPan() {
        val s = state()
        // 捏合中心在屏幕中心右侧 250px：2 倍 ⇒ panX = 250*(1-2) = -250
        s.apply(2f, 0f, 0f, Offset(250f, 0f))
        assertEquals(2f, s.scale, 1e-4f)
        assertEquals(-250f, s.panX, 1e-3f)
        assertEquals(0f, s.panY, 1e-3f)
    }

    /** 双指拖动不能把画面拖出边界：2 倍时最多各方向半个画面 */
    @Test
    fun panIsClampedToPictureBounds() {
        val s = state(1000f, 500f)
        s.apply(2f, 0f, 0f, Offset.Zero)
        s.apply(1f, 5000f, 5000f, Offset.Zero)
        assertEquals(500f, s.panX, 1e-3f)
        assertEquals(250f, s.panY, 1e-3f)
    }

    /** 缩回 1 倍：平移一起归零，不留半个画面的偏移 */
    @Test
    fun zoomOutToMinResetsPan() {
        val s = state()
        s.apply(2f, 100f, 100f, Offset.Zero)
        assertTrue(s.isZoomed)
        s.apply(0.4f, 0f, 0f, Offset.Zero)
        assertEquals(1f, s.scale, 1e-4f)
        assertEquals(0f, s.panX, 1e-4f)
        assertEquals(0f, s.panY, 1e-4f)
        assertFalse(s.isZoomed)
    }

    /** 放大倍数有上限 */
    @Test
    fun zoomScaleIsClamped() {
        val s = state()
        s.apply(100f, 0f, 0f, Offset.Zero)
        assertEquals(VideoZoomState.MAX_SCALE, s.scale, 1e-4f)
    }

    /** 画面框换尺寸（横竖屏切换）：旧平移要按新边界重新夹紧，不能停在新边界之外 */
    @Test
    fun viewportChangeReclampsPan() {
        val s = state(1000f, 500f)
        s.apply(2f, 0f, 0f, Offset.Zero)
        s.apply(1f, 5000f, 5000f, Offset.Zero)
        assertEquals(500f, s.panX, 1e-3f)
        assertEquals(250f, s.panY, 1e-3f)
        s.updateViewport(Size(200f, 400f))
        assertEquals(100f, s.panX, 1e-3f)
        assertEquals(200f, s.panY, 1e-3f)
    }
}
