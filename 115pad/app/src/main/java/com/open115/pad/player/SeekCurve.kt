package com.open115.pad.player

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/**
 * 非线性动态灵敏度快进曲线（Dynamic Seek Curve）。
 *
 * 水平位移先做死区剥离再归一化为屏幕占比 ratioEff（0~1），映射为秒数偏移：
 * ```
 * Δt = sign(Δx) × maxSeekSeconds(totalMs) × ratioEff^p
 * ratioEff = (|Δx| − 死区) / (屏宽 − 死区)
 * ```
 * 幂指数 p > 1 时曲线初段斜率极缓、后段按幂次陡增：
 * 微调区（小位移）1px 只有亚秒级变化，方便精准对齐；
 * 加速区（大位移）单次滑满整屏即可跨越最大跨度；全程连续，无断崖跳变。
 *
 * @param exponent        曲率指数（幂次 p），2.0~2.5 推荐，越大初段越精细、后段越快
 * @param deadzonePx      死区宽度（px）：|Δx| 小于该值时输出恒为 0，防手指抖动；运行时一般传 scaledTouchSlop
 * @param minMaxSeconds   短视频（≤10 分钟）的最大单次滑行跨度（秒）
 * @param maxMaxSeconds   长视频（≥2 小时）的最大单次滑行跨度（秒）
 */
data class SeekCurveConfig(
    val exponent: Double = 2.2,
    val deadzonePx: Float = 0f,
    val minMaxSeconds: Float = 120f,
    val maxMaxSeconds: Float = 1800f,
    val shortVideoThresholdMs: Long = 10 * 60_000L,
    val longVideoThresholdMs: Long = 2 * 3_600_000L,
) {

    /**
     * 最大跨度按视频总时长自适应：
     * 短视频 120s，长视频 1800s（30 分钟），中间线性过渡；
     * 任何情况下不超过视频总长的一半，避免单次滑行直接对半跳。
     */
    fun maxSeekSeconds(totalDurationMs: Long): Float {
        if (totalDurationMs <= 0) return minMaxSeconds
        val halfSeconds = totalDurationMs / 2000f
        return when {
            totalDurationMs <= shortVideoThresholdMs -> minOf(minMaxSeconds, halfSeconds)
            totalDurationMs >= longVideoThresholdMs -> minOf(maxMaxSeconds, halfSeconds)
            else -> {
                val t = (totalDurationMs - shortVideoThresholdMs).toFloat() /
                    (longVideoThresholdMs - shortVideoThresholdMs)
                minOf(minMaxSeconds + (maxMaxSeconds - minMaxSeconds) * t, halfSeconds)
            }
        }
    }

    /**
     * 核心映射：水平位移（px）→ 秒数偏移（含方向）。
     * calculateSeekOffset(deltaX, screenWidth, totalDuration)
     */
    fun calculateSeekOffset(deltaX: Float, screenWidth: Float, totalDurationMs: Long): Float {
        val span = screenWidth - deadzonePx
        if (screenWidth <= 0f || span <= 0f) return 0f
        val effective = ((abs(deltaX) - deadzonePx) / span).coerceIn(0f, 1f)
        if (effective == 0f) return 0f
        return sign(deltaX) * maxSeekSeconds(totalDurationMs) *
            effective.toDouble().pow(exponent).toFloat()
    }

    /** 是否已进入"加速区"（|Δx| 超过屏幕宽度的 [BOOST_RATIO]），供 HUD 高亮提示 */
    fun isBoosting(deltaX: Float, screenWidth: Float): Boolean =
        screenWidth > 0f && abs(deltaX) / screenWidth >= BOOST_RATIO

    companion object {
        /** |ratio| 达到该占比即视为"快速搜寻"状态 */
        const val BOOST_RATIO = 0.45f
    }
}

/** 便捷顶层函数：默认配置下的位移 → 秒数换算 */
fun calculateSeekOffset(deltaX: Float, screenWidth: Float, totalDurationMs: Long): Float =
    SeekCurveConfig().calculateSeekOffset(deltaX, screenWidth, totalDurationMs)
