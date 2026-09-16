package com.open115.pad.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.open115.pad.util.Format
import kotlin.math.abs
import kotlin.math.roundToInt

/** 双击动作：快进 / 快退（左右侧可独立配置） */
enum class SeekAction { SEEK_FORWARD, SEEK_REWIND }

/**
 * 双击配置：左右区域动作与步长独立可配；中间固定为播放/暂停。
 * leftRatio/rightRatio：左右区域判定边界（占容器宽比例），默认前 1/3 与后 1/3。
 */
data class DoubleTapConfig(
    val leftAction: SeekAction = SeekAction.SEEK_REWIND,
    val rightAction: SeekAction = SeekAction.SEEK_FORWARD,
    val leftSeconds: Int = 10,
    val rightSeconds: Int = 15,
    val leftRatio: Float = 1f / 3f,
    val rightRatio: Float = 2f / 3f,
)

/** 双击水波纹反馈：side -1 左 / 1 右，id 用于区分动画轮次 */
data class DoubleTapFx(val side: Int, val label: String, val id: Long)

internal enum class DragMode { NONE, SEEK, BRIGHTNESS, VOLUME }

/** 一次手势周期内的状态（方向锁定后不再改变） */
internal class GestureDragState {
    var ignored = false
    var locked: DragMode = DragMode.NONE
    var accX = 0f
    var accY = 0f
    var width = 1f
    var height = 1f
    var startX = 0f
    var baseBrightness = 0.5f
    var baseVolume = 0
    var seekDeltaSec = 0f

    fun reset(offsetX: Float, offsetY: Float, w: Float, h: Float, edgePx: Float) {
        accX = 0f
        accY = 0f
        width = w
        height = h
        startX = offsetX
        seekDeltaSec = 0f
        locked = DragMode.NONE
        // 屏幕左右边缘 16dp 内起手不响应，规避系统返回手势
        ignored = offsetX < edgePx || offsetX > w - edgePx
    }

    /** 超过 touchSlop 后按 |Δx| vs |Δy| 一次性锁定方向，抬手前严格保持 */
    fun accumulate(dx: Float, dy: Float, touchSlop: Float) {
        accX += dx
        accY += dy
        if (locked == DragMode.NONE && (abs(accX) > touchSlop || abs(accY) > touchSlop)) {
            locked = if (abs(accX) > abs(accY)) DragMode.SEEK
            else if (startX < width / 3f) DragMode.BRIGHTNESS
            else if (startX > width * 2f / 3f) DragMode.VOLUME
            else DragMode.NONE // 中部竖直滑动不响应
        }
    }
}

/**
 * 播放手势覆盖层（覆盖在 PlayerView 之上的独立响应层）：
 * - 左侧竖直滑动：亮度 0.01~1.0（改 Window.screenBrightness）
 * - 右侧竖直滑动：媒体音量（STREAM_MUSIC，flag=0 不弹系统音量框）
 * - 全屏横滑：非线性动态灵敏度快进/快退（幂函数曲线，微调区极缓/加速区幂次放大），抬手才 seekTo
 * - 单击：切换控制器（3 秒无操作自动淡出，由调用方实现）
 * - 双击：左/右按 DoubleTapConfig 执行快退/快进（带水波纹反馈），中间播放/暂停
 * - 长按：倍速播放，长按后左右滑动微调倍速，松手恢复
 * 所有步长按屏幕宽/高百分比计算，防抖用系统 scaledTouchSlop，边缘预留 16dp。
 */
@Composable
internal fun PlayerGestureOverlay(
    modifier: Modifier,
    enabled: Boolean,
    player: Player,
    config: DoubleTapConfig,
    longPressSpeed: Float,
    edgeMarginDp: Int = 16,
    seekCurve: SeekCurveConfig = SeekCurveConfig(),
    onToggleController: () -> Unit,
    onSeekPreview: (Long, Int, Boolean) -> Unit,
    onSeekCommit: (Long) -> Unit,
    onSeekPreviewCancel: () -> Unit,
    onBrightness: (Float) -> Unit,
    onVolume: (Int, Int) -> Unit,
    onSpeedHud: (String?) -> Unit,
    onDoubleTapFx: (Int, String) -> Unit,
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val density = LocalDensity.current
    val edgePx = with(density) { edgeMarginDp.dp.toPx() }
    val touchSlop = remember { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }
    // 死区取系统 scaledTouchSlop：方向锁定的抖动阈值与微调死区保持一致
    val curve = remember(seekCurve, touchSlop) { seekCurve.copy(deadzonePx = touchSlop) }
    val state = remember { GestureDragState() }
    var speedMode by remember { mutableStateOf(false) }
    val speedAcc = remember { mutableStateOf(0f) }
    var seekDeltaSec by remember { mutableStateOf(0f) }

    var layer = modifier
    if (enabled) {
        // 长按倍速（长按后左右滑动微调，松手恢复 1x）
        layer = layer.pointerInput(longPressSpeed) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    speedMode = true
                    speedAcc.value = 0f
                    player.setPlaybackSpeed(longPressSpeed)
                    onSpeedHud(String.format(java.util.Locale.US, "x%.1f 快速播放中", longPressSpeed))
                },
                onDrag = { change, amt ->
                    change.consume()
                    speedAcc.value += amt.x
                    val s = (longPressSpeed + speedAcc.value / 400f).coerceIn(0.5f, 4f)
                    player.setPlaybackSpeed(s)
                    onSpeedHud(String.format(java.util.Locale.US, "x%.1f 快速播放中", s))
                },
                onDragEnd = {
                    speedMode = false
                    player.setPlaybackSpeed(1f)
                    onSpeedHud(null)
                },
                onDragCancel = {
                    speedMode = false
                    player.setPlaybackSpeed(1f)
                    onSpeedHud(null)
                },
            )
        }
        // 竖直/水平拖动（方向锁定）
        layer = layer.pointerInput(curve) {
            detectDragGestures(
                onDragStart = { offset ->
                    state.reset(offset.x, offset.y, size.width.toFloat(), size.height.toFloat(), edgePx)
                    val win = (context as? Activity)?.window
                    val b = win?.attributes?.screenBrightness ?: -1f
                    state.baseBrightness = if (b < 0f) 0.5f else b
                    state.baseVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                },
                onDrag = { change, amt ->
                    if (speedMode) {
                        change.consume()
                        return@detectDragGestures
                    }
                    change.consume()
                    state.accumulate(amt.x, amt.y, touchSlop)
                    when (state.locked) {
                        DragMode.SEEK -> {
                            // 非线性动态灵敏度（幂函数曲线）：死区内 0，微调区极缓，
                            // 加速区按 |ratio|^p 放大；拖动中只更新预览，抬手才真正 seek
                            val dur = player.duration.coerceAtLeast(0L)
                            val delta = curve.calculateSeekOffset(state.accX, state.width, dur)
                            state.seekDeltaSec = delta
                            val target = (player.currentPosition + (delta * 1000).toLong())
                                .coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
                            onSeekPreview(target, delta.roundToInt(), curve.isBoosting(state.accX, state.width))
                        }
                        DragMode.BRIGHTNESS -> {
                            val win = (context as? Activity)?.window ?: return@detectDragGestures
                            val v = (state.baseBrightness - state.accY / state.height)
                                .coerceIn(0.01f, 1f)
                            val lp = win.attributes
                            lp.screenBrightness = v
                            win.attributes = lp
                            onBrightness(v)
                        }
                        DragMode.VOLUME -> {
                            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val v = (state.baseVolume - state.accY / state.height * max)
                                .roundToInt().coerceIn(0, max)
                            // flag 传 0：不显示系统音量面板
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                            onVolume(v, max)
                        }
                        DragMode.NONE -> {}
                    }
                },
                onDragEnd = {
                    if (state.locked == DragMode.SEEK && state.seekDeltaSec != 0f) {
                        val dur = player.duration
                        val target = (player.currentPosition + (state.seekDeltaSec * 1000).toLong())
                            .coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
                        onSeekCommit(target)
                    }
                    state.seekDeltaSec = 0f
                    state.locked = DragMode.NONE
                },
                onDragCancel = {
                    state.seekDeltaSec = 0f
                    state.locked = DragMode.NONE
                },
            )
        }
        // 单击 / 双击 / 长按触发
        layer = layer.pointerInput(config) {
            detectTapGestures(
                onTap = { onToggleController() },
                onDoubleTap = { offset ->
                    val w = size.width
                    when {
                        offset.x <= w * config.leftRatio -> {
                            val sec = config.leftSeconds
                            val dur = player.duration.coerceAtLeast(0L)
                            val target = when (config.leftAction) {
                                SeekAction.SEEK_REWIND ->
                                    (player.currentPosition - sec * 1000L).coerceAtLeast(0L)
                                SeekAction.SEEK_FORWARD ->
                                    (player.currentPosition + sec * 1000L).coerceAtMost(dur)
                            }
                            player.seekTo(target)
                            onDoubleTapFx(
                                -1,
                                if (config.leftAction == SeekAction.SEEK_REWIND) "-${sec}s" else "+${sec}s",
                            )
                        }
                        offset.x >= w * config.rightRatio -> {
                            val sec = config.rightSeconds
                            val dur = player.duration.coerceAtLeast(0L)
                            val target = when (config.rightAction) {
                                SeekAction.SEEK_REWIND ->
                                    (player.currentPosition - sec * 1000L).coerceAtLeast(0L)
                                SeekAction.SEEK_FORWARD ->
                                    (player.currentPosition + sec * 1000L).coerceAtMost(dur)
                            }
                            player.seekTo(target)
                            onDoubleTapFx(
                                1,
                                if (config.rightAction == SeekAction.SEEK_REWIND) "-${sec}s" else "+${sec}s",
                            )
                        }
                        else -> {
                            if (player.isPlaying) player.pause() else player.play()
                        }
                    }
                },
                onLongPress = {
                    speedMode = true
                    speedAcc.value = 0f
                    player.setPlaybackSpeed(longPressSpeed)
                    onSpeedHud(String.format(java.util.Locale.US, "x%.1f 快速播放中", longPressSpeed))
                },
            )
        }
    }
    Box(layer)
}

/** 竖直 HUD（亮度/音量反向显示）：图标 + 竖向进度条 + 百分比 */
@Composable
internal fun GestureHudSide(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    progress: Float,
    modifier: Modifier,
) {
    val barHeight = 150.dp
    Column(
        modifier.padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .width(5.dp)
                .height(barHeight)
                .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .width(5.dp)
                    .height(barHeight * progress.coerceIn(0f, 1f))
                    .background(Color.White, RoundedCornerShape(3.dp)),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 双击水波纹 + 步长反馈（在对应侧居中显示） */
@Composable
internal fun DoubleTapFxOverlay(fx: DoubleTapFx, modifier: Modifier) {
    val alphaAnim = remember(fx.id) { Animatable(1f) }
    LaunchedEffect(fx.id) {
        alphaAnim.animateTo(0f, tween(450))
    }
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(if (fx.side < 0) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 48.dp)
                .size(120.dp)
                .graphicsLayer { alpha = alphaAnim.value }
                .background(Color.White.copy(alpha = 0.22f * alphaAnim.value), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                fx.label,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** 进度预览浮层（屏幕居中）：目标时间 / 总时长 + 自适应增量；加速区高亮提示"快速搜寻" */
@Composable
internal fun SeekPreviewOverlay(
    targetMs: Long,
    durMs: Long,
    deltaSec: Int,
    boosting: Boolean,
    modifier: Modifier,
) {
    Surface(
        modifier,
        color = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "${Format.duration(targetMs / 1000)} / ${Format.duration(durMs / 1000)}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (boosting) {
                    Icon(
                        Icons.Outlined.FastForward,
                        contentDescription = null,
                        tint = SeekBoostColor,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    formatSeekDelta(deltaSec) + if (boosting) " · 快速搜寻" else "",
                    color = if (boosting) SeekBoostColor else Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** seek 增量自适应格式：<60s 显示 +Ns，跨分钟显示 +M:SS */
internal fun formatSeekDelta(deltaSec: Int): String {
    val s = abs(deltaSec)
    val body = if (s < 60) "${s}s" else "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    return (if (deltaSec >= 0) "+" else "-") + body
}

/** 加速区高亮色（琥珀色，黑底画面上醒目且不刺眼） */
private val SeekBoostColor = Color(0xFFFFC107)

/** 长按倍速胶囊（顶部居中） */
@Composable
internal fun SpeedCapsuleHud(text: String, modifier: Modifier) {
    Surface(
        modifier,
        color = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

