package com.open115.pad.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * 非 VR 播放的「双指缩放画面」状态：缩放倍数 + 画面平移（屏幕像素）。
 *
 * 只作用在 TextureView 那条渲染路径上：画面框本身用 `graphicsLayer` 缩放/平移，
 * SurfaceView（VR / 滤镜的 GLSurfaceView）的内容由 SurfaceFlinger 合成，View 变换对
 * 它不生效，所以那两条路不启用（见 PlayerGestureOverlay 的 zoomEnabled）。
 *
 * [viewport] 是画面框（旋转后）的布局尺寸：平移按它夹在画面内，不让用户把画面拖出黑边。
 * 以画面中心为原点，`p = pan + scale * q`。
 */
internal class VideoZoomState {
    var scale by mutableFloatStateOf(MIN_SCALE)
        private set
    var panX by mutableFloatStateOf(0f)
        private set
    var panY by mutableFloatStateOf(0f)
        private set

    /** 画面框布局尺寸（像素）：由视频 Box 的 onSizeChanged 灌进来 */
    var viewport by mutableStateOf(Size.Zero)

    /**
     * 画面框尺寸变了（横竖屏切换 / letterbox 变化）：重设夹取范围。
     *
     * 播放页的 Activity 不随旋转重建，缩放状态会活着跨过转屏；旧平移是按旧尺寸算的像素值，
     * 不重夹就可能让画面停在新边界之外（露出一块黑边）。
     */
    fun updateViewport(size: Size) {
        viewport = size
        if (isZoomed) clampPan()
    }

    val isZoomed: Boolean get() = scale > MIN_SCALE + EPS

    /**
     * 双指手势的一步：以捏合中心为不动点缩放，再叠加双指平移。
     *
     * [centroidRel] 是捏合中心**相对屏幕中心**的偏移（不是绝对坐标）：画面框在屏幕上居中，
     * 所以它的中心就是屏幕中心；用绝对坐标减半屏会在画面框比屏幕小时算错。
     * 以捏合中心为不动点是双指缩放的常规手感：两指中间那块内容不会在缩放时"跑掉"。
     * 缩回 1 倍时平移一并归零（不留半个画面的偏移）。
     */
    fun apply(zoomDelta: Float, panDx: Float, panDy: Float, centroidRel: Offset) {
        val old = scale
        val next = (old * zoomDelta).coerceIn(MIN_SCALE, MAX_SCALE)
        if (next <= MIN_SCALE + EPS) {
            resetNow()
            return
        }
        val z = next / old
        scale = next
        // 中心固定的缩放：o' = c·(1-z) + z·o，再叠双指平移
        panX = centroidRel.x * (1f - z) + z * panX + panDx
        panY = centroidRel.y * (1f - z) + z * panY + panDy
        clampPan()
    }

    /** 直接设值（还原动画逐帧用） */
    fun set(newScale: Float, newPanX: Float, newPanY: Float) {
        scale = newScale.coerceIn(MIN_SCALE, MAX_SCALE)
        panX = newPanX
        panY = newPanY
        if (scale <= MIN_SCALE + EPS) {
            panX = 0f
            panY = 0f
        } else {
            clampPan()
        }
    }

    fun resetNow() {
        scale = MIN_SCALE
        panX = 0f
        panY = 0f
    }

    private fun clampPan() {
        val w = viewport.width
        val h = viewport.height
        if (w <= 0f || h <= 0f) return
        val maxX = (scale - 1f) * w / 2f
        val maxY = (scale - 1f) * h / 2f
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 4f
        private const val EPS = 0.001f
    }
}
