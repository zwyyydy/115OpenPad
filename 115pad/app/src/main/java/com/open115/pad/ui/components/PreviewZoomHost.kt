package com.open115.pad.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/**
 * 最近一次按压的坐标（窗口内容坐标）。由 [Modifier.observeLastTapPoint] 在应用根布局上
 * 以 Initial pass 采集 —— 只读不消费，对所有子组件的手势零影响；按压过程（含拖动）持续更新。
 */
object LastTapPoint {
    var position: Offset? = null
}

/** 挂在包含全部预览浮层的根布局上；必须先于子组件拿到事件（Initial pass），所以放在根部 */
fun Modifier.observeLastTapPoint(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull() ?: continue
            if (change.pressed) LastTapPoint.position = change.position
        }
    }
}

private val EnterSpec = tween<Float>(280, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
private val ExitSpec = tween<Float>(200, easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f))

/**
 * 预览浮层（图片画廊 / 文本预览 / 剧照浏览）统一的进出场过渡。
 *
 * 进场：整块浮层从最近一次点击的位置放大（0.84 → 1）并快速淡入（alpha 在前半程就到位，
 * 画面先立起来再继续放大就位）；退场反向缩回 + 后半程淡出，动画走完才回调 [onDismissed]
 * 清掉浮层状态。三个浮层此前都是条件组合的瞬时切换（点开闪现、退出闪断），这里统一收口。
 *
 * 用法：把原来的 `state?.let { XxxOverlay(..., onDismiss = { state = null }) }` 包一层，
 * `onDismiss` 改接 `requestClose`（内部先播退场动画再真正关闭）：
 * ```
 * state?.let { value ->
 *     PreviewZoomHost(onDismissed = { state = null }) { requestClose ->
 *         XxxOverlay(..., onDismiss = requestClose)
 *     }
 * }
 * ```
 *
 * 进出场共用同一个 [Animatable]，由下面唯一的 LaunchedEffect 驱动：closing 置位会让
 * effect 重启并接管动画（animateTo 自带互斥，未完成的进场被退场直接取消），不存在
 * 两条协程同时拉扯 progress 的竞态。
 */
@Composable
fun PreviewZoomHost(
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (requestClose: () -> Unit) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    // 组合发生在点击 UP 之后，LastTapPoint 里存的就是这次点击；拿不到（键盘/外设唤起）退化为中心
    val origin = remember { LastTapPoint.position }
    val progress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }

    val requestClose: () -> Unit = { closing = true }

    LaunchedEffect(closing) {
        if (closing) {
            progress.animateTo(0f, ExitSpec)
            onDismissed()
        } else {
            progress.animateTo(1f, EnterSpec)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .graphicsLayer {
                val t = progress.value
                alpha = (t * 2f).coerceAtMost(1f)
                val scale = 0.84f + 0.16f * t
                scaleX = scale
                scaleY = scale
                transformOrigin = if (size.width > 0 && size.height > 0) {
                    val o = origin
                    if (o != null) {
                        TransformOrigin(
                            (o.x / size.width).coerceIn(0f, 1f),
                            (o.y / size.height).coerceIn(0f, 1f),
                        )
                    } else {
                        TransformOrigin.Center
                    }
                } else {
                    TransformOrigin.Center
                }
            },
    ) {
        content(requestClose)
    }
}
