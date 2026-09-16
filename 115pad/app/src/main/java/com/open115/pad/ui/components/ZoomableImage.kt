package com.open115.pad.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 缩放手势状态：手势层只改这里，绘制层（graphicsLayer）只读这里。
 *
 * 把"下拉关闭"的位移 / 缩小量 / 透明度全部派生自一个 [dismissProgress]，
 * 回弹时只需把这个值动画归零，三个量自然同步。
 */
class ZoomState {

    var scale by mutableStateOf(1f)
        internal set
    var offset by mutableStateOf(Offset.Zero)
        internal set
    var dismissProgress by mutableStateOf(0f)
        internal set

    // 视口与"适配屏幕后的内容尺寸"，均以像素计。
    // 必须是 Compose 状态：HugeImage 的绘制依赖 fitRatio/content，
    // 若是普通字段，异步算出真实尺寸后 Canvas 不会重绘/不会拿到新值，
    // 会用组合时的 fit=1.0 把原图按原始像素铺出来（表现为严重放大裁切）。
    internal var viewport by mutableStateOf(Size.Zero)
        internal set
    internal var content by mutableStateOf(Size.Zero)
        internal set
    internal var fitRatio by mutableStateOf(1f)
        internal set
    internal var intrinsic by mutableStateOf(Size.Zero)
        internal set

    /** 原始像素对应的缩放倍数（双击"看原图"的目标值），至少 1 */
    val pixelScale: Float
        get() = if (fitRatio > 0f) (1f / fitRatio).coerceIn(1f, MAX_SCALE) else MAX_SCALE

    val isZoomed: Boolean get() = scale > 1.01f

    internal fun onViewport(size: IntSize) {
        viewport = Size(size.width.toFloat(), size.height.toFloat())
        recompute()
    }

    /** 由图片加载成功的回调传入原始像素尺寸 */
    internal fun onIntrinsic(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        intrinsic = Size(width.toFloat(), height.toFloat())
        recompute()
    }

    private fun recompute() {
        val vw = viewport.width
        val vh = viewport.height
        val iw = intrinsic.width
        val ih = intrinsic.height
        if (vw <= 0 || vh <= 0 || iw <= 0 || ih <= 0) return
        val ratio = minOf(vw / iw, vh / ih)
        fitRatio = ratio
        content = Size(iw * ratio, ih * ratio)
    }

    internal fun maxOffset(): Offset {
        val maxX = ((content.width * scale - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((content.height * scale - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(maxX, maxY)
    }

    internal fun clamp(o: Offset): Offset {
        val m = maxOffset()
        return Offset(o.x.coerceIn(-m.x, m.x), o.y.coerceIn(-m.y, m.y))
    }

    internal fun reset() {
        scale = 1f
        offset = Offset.Zero
        dismissProgress = 0f
    }

    internal companion object {
        /** 最大放大倍数：足以看清 1:1 细节，又不至于把低分辨率图拉成马赛克 */
        const val MAX_SCALE = 5f

        /** 下拉进度超过该值即判定关闭 */
        const val DISMISS_THRESHOLD = 0.28f

        /** 下拉进度 1.0 时图片整体下移的距离（占视口高度比例） */
        const val DISMISS_MAX_TRANSLATE = 0.45f
    }
}

/**
 * 可缩放的大图容器：手势 + 事件拦截核心。
 *
 * ┌──────────────┬─────────────────────────────────────────────────────────┐
 * │ 当前状态      │ 行为与事件消费                                            │
 * ├──────────────┼─────────────────────────────────────────────────────────┤
 * │ scale == 1   │ ① 竖直向下拖 → 等比缩小 + 背景渐隐，超阈值松手回调关闭        │
 * │ （未放大）     │ ② 水平方向**不消费** → 事件穿透给外层 Pager 翻页             │
 * │ scale > 1    │ ③ 双指捏合缩放（以指心为锚点）、单指平移（边界钳制）           │
 * │ （已放大）     │ ④ 平移到最左/最右仍有剩余水平位移 → 派发给 Pager 翻页         │
 * │ 任意状态      │ ⑤ 双击在"适应屏幕"与"原始像素"之间平滑切换；单击回调           │
 * └──────────────┴─────────────────────────────────────────────────────────┘
 *
 * ②/⑤ 的要点：进入手势模式之前**一个事件都不消费**，所以 detectTapGestures（单击/双击）
 * 和 HorizontalPager 的拖拽探测都能照常工作。
 *
 * ④ 的要点：HorizontalPager 自身实现了 NestedScroll 的 onPreScroll，
 * 因此把"自己钳制之后剩余的水平位移"用 NestedScrollDispatcher.dispatchRawDelta 派发给父级，
 * Pager 会接着这段位移翻页——两个手势层之间不需要互相仲裁。
 */
@Composable
fun ZoomableBox(
    state: ZoomState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onTap: (() -> Unit)? = null,
    onDismissRequest: (() -> Unit)? = null,
    onDismissProgress: ((Float) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dispatcher = remember { NestedScrollDispatcher() }
    val connection = remember { object : NestedScrollConnection {} }

    // 下拉关闭进度回传给宿主（用于整体背景渐隐）
    LaunchedEffect(state) {
        snapshotFlow { state.dismissProgress }.collect { onDismissProgress?.invoke(it) }
    }

    /** 以 anchor 为锚点把缩放平滑过渡到 target（双击切换用） */
    fun animateZoom(target: Float, anchor: Offset, durationMs: Long = 220) {
        scope.launch {
            val fromScale = state.scale
            val fromOffset = state.offset
            val start = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val p = ((now - start) / (durationMs * 1_000_000f)).coerceIn(0f, 1f)
                val eased = 1f - (1f - p) * (1f - p) // easeOutQuad
                val s = (fromScale + (target - fromScale) * eased).coerceIn(1f, ZoomState.MAX_SCALE)
                val ratio = s / fromScale
                state.scale = s
                state.offset = state.clamp(anchor - (anchor - fromOffset) * ratio)
                if (state.scale <= 1.001f) state.offset = Offset.Zero
                if (p >= 1f) break
            }
        }
    }

    fun animateDismissBack(durationMs: Long = 180) {
        scope.launch {
            val from = state.dismissProgress
            val start = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val p = ((now - start) / (durationMs * 1_000_000f)).coerceIn(0f, 1f)
                state.dismissProgress = from * (1f - p)
                if (p >= 1f) break
            }
            state.dismissProgress = 0f
        }
    }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { state.onViewport(it) }
            .nestedScroll(connection, dispatcher)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var mode = Mode.Idle
                    var dragY = 0f

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        val pan = event.calculatePan()
                        val zoom = event.calculateZoom()
                        val centroid = event.calculateCentroid(useCurrent = true)
                        dragY += pan.y

                        // ---- 模式判定：一旦确定，本次手势内不再改变 ----
                        if (mode == Mode.Idle) {
                            if (pressed.size >= 2) {
                                mode = Mode.Zoom
                            } else {
                                val total = pressed.first().position - down.position
                                if (total.getDistance() > viewConfiguration.touchSlop) {
                                    mode = when {
                                        state.scale > 1f -> Mode.Pan
                                        // 竖直向下为主的拖拽 → 进入"下拉关闭"
                                        total.y > 0f && abs(total.y) > abs(total.x) * 1.2f ->
                                            Mode.Dismiss
                                        // 其余（主要是水平）→ 不消费，留给 Pager
                                        else -> Mode.Horizontal
                                    }
                                }
                            }
                        }

                        when (mode) {
                            Mode.Zoom -> {
                                if (zoom != 1f) {
                                    val target = (state.scale * zoom)
                                        .coerceIn(1f, ZoomState.MAX_SCALE)
                                    val ratio = target / state.scale
                                    state.scale = target
                                    state.offset = state.clamp(
                                        centroid - (centroid - state.offset) * ratio,
                                    )
                                } else {
                                    state.offset = state.clamp(state.offset + pan)
                                }
                                event.changes.forEach { it.consume() }
                            }

                            Mode.Pan -> {
                                val clamped = state.clamp(state.offset + pan)
                                val consumedBySelf = clamped - state.offset
                                state.offset = clamped
                                // 平移到边界还有剩余水平位移 → 通过嵌套滚动派发给父级 Pager 翻页。
                                // 自己已消费的部分照实上报，剩余量由 Pager 的 onPostScroll 接管。
                                val leftover = pan - consumedBySelf
                                if (abs(leftover.x) > 0.5f) {
                                    dispatcher.dispatchPostScroll(
                                        consumed = consumedBySelf,
                                        available = Offset(leftover.x, 0f),
                                        source = NestedScrollSource.UserInput,
                                    )
                                }
                                event.changes.forEach { it.consume() }
                            }

                            Mode.Dismiss -> {
                                state.dismissProgress = (dragY / (size.height *
                                    ZoomState.DISMISS_MAX_TRANSLATE)).coerceIn(0f, 1f)
                                event.changes.forEach { it.consume() }
                            }

                            // 未放大时的水平滑动：不消费，交给 Pager
                            Mode.Idle, Mode.Horizontal -> Unit
                        }

                        if (!pressed.first().pressed) break
                    }

                    // ---- 手势结束：提交或回弹 ----
                    when (mode) {
                        Mode.Dismiss -> {
                            if (state.dismissProgress >= ZoomState.DISMISS_THRESHOLD) {
                                onDismissRequest?.invoke()
                            } else {
                                animateDismissBack()
                            }
                        }
                        Mode.Zoom, Mode.Pan -> {
                            if (state.scale <= 1.001f) state.offset = Offset.Zero
                        }
                        Mode.Idle, Mode.Horizontal -> Unit
                    }
                }
            }
            .pointerInput(enabled, onTap, onDismissRequest) {
                if (!enabled) return@pointerInput
                // 单击 / 双击：独立探测。上面的手势层在 Idle（未过 slop）时不消费事件，
                // 所以这里能正常收到。
                detectTapGestures(
                    onTap = { onTap?.invoke() },
                    onDoubleTap = { centroid ->
                        animateZoom(if (state.isZoomed) 1f else state.pixelScale, centroid)
                    },
                )
            },
    ) {
        // 下拉关闭：位移 / 略微缩小 / 渐隐，全部由 dismissProgress 派生。
        // 注意这里必须 fillMaxSize：内容里的 matchParentSize() 以这个 Box 为基准，
        // 若是 wrap-content，整条链路都会变成 0 尺寸（画面全黑的那种坑）。
        val progress = state.dismissProgress
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = state.scale * (1f - progress * 0.22f)
                    scaleY = state.scale * (1f - progress * 0.22f)
                    translationX = state.offset.x
                    translationY = state.offset.y + progress * size.height *
                        ZoomState.DISMISS_MAX_TRANSLATE
                    alpha = 1f - progress * 0.75f
                },
            content = content,
        )
    }
}

/** 内部手势模式 */
private enum class Mode { Idle, Zoom, Pan, Dismiss, Horizontal }
