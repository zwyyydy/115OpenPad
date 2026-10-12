package com.open115.pad.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * 一次性入场淡入：内容组合时从 alpha 0 淡到 1，给「数据到齐/条件成立才出现」的内容
 * 补一层过渡，治整页一帧硬切。只动 alpha（合成器最便宜的活），不碰 scale/位移 ——
 * 整屏重内容缩放会逐帧重采样（媒体库动效四轮迭代的结论）。
 */
@Composable
fun FadeIn(
    modifier: Modifier = Modifier,
    durationMillis: Int = 220,
    content: @Composable () -> Unit,
) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) { alpha.animateTo(1f, tween(durationMillis)) }
    Box(modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
}

/**
 * 条件浮层的淡入淡出（带退场）：`state?.let { XxxPanel(it) }` 换成
 * `FadePresence(state) { XxxPanel(it) }` 即可，退场动画播完才真正把内容拆掉。
 *
 * 退场期间 [target] 已经变 null，所以留一份最近的非空值让内容继续渲染到淡出结束。
 * 注意：内容里如果自己 collect「可能变 null 的同一份状态」，退场时会拿到 null ——
 * 那种挂载点用 [FadeIn]（只进不出）更稳。
 */
@Composable
fun <T> FadePresence(
    target: T?,
    modifier: Modifier = Modifier,
    enterMillis: Int = 200,
    exitMillis: Int = 160,
    content: @Composable (T) -> Unit,
) {
    var last by remember { mutableStateOf(target) }
    if (target != null) last = target
    AnimatedVisibility(
        visible = target != null,
        enter = fadeIn(tween(enterMillis)),
        exit = fadeOut(tween(exitMillis)),
        modifier = modifier,
    ) {
        last?.let { content(it) }
    }
}

/**
 * 按压反馈：按下时整块缩一点（[GlassCard] 同款思路）。深底/图片上默认水波纹几乎
 * 看不出来，缩放是「按到了」最直接的说法；只走 graphicsLayer 不触发重布局，
 * 不会把 LazyColumn 里的格子挤一下。配 `indication = null` 用（见 [GlassCard]）。
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, label = "pressScale")
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 带入场淡入的 [Dialog]：全屏编辑器/面板这类自定义对话框用，内容淡入、窗口本身的
 * 变化交给系统。退场不动画 —— Compose 的 Dialog 窗口移除是即时的，要退场得每个
 * 调用点自己延迟清状态，不划算。
 */
@Composable
fun FadeDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    durationMillis: Int = 200,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        FadeIn(modifier, durationMillis) { content() }
    }
}

/**
 * M3 AlertDialog 的同款外观（surfaceContainerHigh 容器 + 28dp 圆角 + headlineSmall/
 * bodyMedium 字级），进场换成淡入 + 轻微缩放（0.92 → 1，小卡片缩放很便宜）。
 * 插槽签名与 M3 对齐，调用点把 `AlertDialog` 换成 `FadeAlertDialog` 即可。
 */
@Composable
fun FadeAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        val alpha = remember { Animatable(0f) }
        val scale = remember { Animatable(0.92f) }
        LaunchedEffect(Unit) {
            launch { scale.animateTo(1f, tween(200)) }
            alpha.animateTo(1f, tween(180))
        }
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            modifier = Modifier
                .widthIn(min = 280.dp, max = 560.dp)
                .graphicsLayer {
                    this.alpha = alpha.value
                    this.scaleX = scale.value
                    this.scaleY = scale.value
                },
        ) {
            Column {
                title?.let {
                    Box(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp)) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                            ProvideTextStyle(MaterialTheme.typography.headlineSmall, content = it)
                        }
                    }
                }
                text?.let {
                    Box(
                        Modifier.padding(
                            start = 24.dp,
                            end = 24.dp,
                            top = if (title != null) 16.dp else 24.dp,
                        ),
                    ) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium, content = it)
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                        .padding(top = if (title != null || text != null) 8.dp else 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}
