package com.open115.pad.data

/**
 * 兜底海报的裁剪范围：归一化到源图的 0..1（左上角 + 右下角）。
 *
 * 存归一化值而不是像素：源图换了尺寸/换了清晰度档位也能用，且不依赖当时屏幕大小。
 */
data class PosterCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** 夹回图内，并保证宽高至少有一点点（避免解码出 0 像素） */
    fun clamped(): PosterCropRect {
        // 先给右下角留出最小边距，否则 left=1 时 coerceIn(1.001, 1) 会直接抛异常
        val l = left.coerceIn(0f, 1f - MIN_SIDE)
        val t = top.coerceIn(0f, 1f - MIN_SIDE)
        val r = right.coerceIn(l + MIN_SIDE, 1f)
        val b = bottom.coerceIn(t + MIN_SIDE, 1f)
        return PosterCropRect(l, t, r, b)
    }

    fun approxEquals(other: PosterCropRect, eps: Float = 0.002f): Boolean =
        kotlin.math.abs(left - other.left) <= eps &&
            kotlin.math.abs(top - other.top) <= eps &&
            kotlin.math.abs(right - other.right) <= eps &&
            kotlin.math.abs(bottom - other.bottom) <= eps

    companion object {
        private const val MIN_SIDE = 0.001f
    }
}

/**
 * 兜底海报的**默认**裁剪：背景图右半部分再按 2:3 居中取一块。
 *
 * 这就是早先"直接裁右半部分"在海报框里实际显示出来的区域 —— 右半张是 0.889 的宽高比，
 * 塞进 2:3 的海报框时上下会被裁掉一截。这里直接把那截算出来，裁出来的文件本身就是 2:3，
 * 展示端不用再二次裁切；手动编辑也从这块开始，所见即所得。
 */
fun defaultPosterCrop(srcW: Int, srcH: Int): PosterCropRect {
    if (srcW <= 0 || srcH <= 0) return PosterCropRect(0.5f, 0f, 1f, 1f)
    val aspect = 2f / 3f
    // 右半部分的宽度；若右半张比 2:3 还窄（少见的竖图），就按高度顶满来取
    val w = minOf(srcW / 2f, srcH * aspect)
    val h = w / aspect
    val left = (srcW * 0.75f - w / 2f) / srcW
    val top = (srcH / 2f - h / 2f) / srcH
    return PosterCropRect(left, top, left + w / srcW, top + h / srcH).clamped()
}
