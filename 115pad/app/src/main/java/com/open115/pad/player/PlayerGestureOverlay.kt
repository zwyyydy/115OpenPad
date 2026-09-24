package com.open115.pad.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.open115.pad.util.Format
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

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

/**
 * 双击判定窗口（第一击抬手 → 第二击按下）。
 *
 * 比系统 doubleTapTimeout(300ms) 宽 50ms：手速稍慢的"两次轻点"会被系统值拆成两次单击
 * （用户感知就是"双击快进偶有失效"），代价只是单击唤出控制排多等 50ms。
 * 普通手势层与 VR 手势层共用这一个窗口，两种模式的双击手感保持一致。
 */
internal const val DOUBLE_TAP_WINDOW_MS = 350L

/** 刚双击过之后这段时间里的单击不再触发控制排显隐（连点快进时别把控制排又弹出来） */
private const val SINGLE_TAP_SUPPRESS_MS = 700L

/** 长按倍速后横移多少像素 = 1 倍速（沿用旧实现的手感） */
private const val SPEED_DRAG_PX = 400f

/**
 * 双击三区动作：左区快退 / 右区快进 / 中间播放暂停（左右的动作与步长由 [config] 决定）。
 *
 * 普通手势层与 VR 手势层**共用这一份实现**。VR 里横拖是转视角、捏合是调视场角，
 * 只有"轻点两下"这种零位移操作能安全复用；而两种模式下的双击行为必须完全一致，
 * 各写一份迟早会走偏。
 *
 * @param xRatio 双击点的横向比例（0~1）。各手势层用自己 `PointerInputScope` 的宽度算好传进来，
 *               这一层不必关心视图尺寸。
 * @param onFx 水波纹反馈（side -1 左 / 1 右 + 步长文案）；中间区是播放/暂停，不触发
 */
internal fun applyDoubleTapZone(
    player: Player,
    config: DoubleTapConfig,
    xRatio: Float,
    onFx: (Int, String) -> Unit,
) {
    fun seekZone(side: Int, action: SeekAction, seconds: Int) {
        val dur = player.duration
        val target = when (action) {
            SeekAction.SEEK_REWIND ->
                (player.currentPosition - seconds * 1000L).coerceAtLeast(0L)

            // 时长还没解析出来（dur<=0）时不做上界夹逼，否则"快进"会被夹到 0 直接跳回片头
            SeekAction.SEEK_FORWARD ->
                (player.currentPosition + seconds * 1000L)
                    .coerceAtMost(if (dur > 0) dur else Long.MAX_VALUE)
        }
        player.seekTo(target)
        onFx(side, (if (action == SeekAction.SEEK_REWIND) "-" else "+") + "${seconds}s")
    }
    when {
        xRatio <= config.leftRatio -> seekZone(-1, config.leftAction, config.leftSeconds)
        xRatio >= config.rightRatio -> seekZone(1, config.rightAction, config.rightSeconds)
        else -> if (player.isPlaying) player.pause() else player.play()
    }
}

internal enum class DragMode { NONE, SEEK, BRIGHTNESS, VOLUME }

/** 一次手势第一段的判定结果 */
private enum class TapPhase {
    /** 抬手（且位移没超 slop）：轻点 */
    TAP,

    /** 位移超过 slop：拖动（横滑快进 / 亮度 / 音量） */
    DRAG,

    /** 按住到长按时间：倍速 */
    LONG_PRESS,

    /** 指针被系统抢走（返回手势 / 窗口失焦）：这一下作废 */
    CANCEL,
}

/** 一次手势周期内的状态（方向锁定后不再改变） */
internal class GestureDragState {
    var locked: DragMode = DragMode.NONE

    /** 位移是否曾超过 touchSlop：轻点与拖动的分界（锁定方向后保持 true） */
    var movedBeyondSlop = false
    var accX = 0f
    var accY = 0f
    var width = 1f
    var height = 1f
    var startX = 0f
    var baseBrightness = 0.5f
    var baseVolume = 0
    var seekDeltaSec = 0f

    fun reset(offsetX: Float, offsetY: Float, w: Float, h: Float) {
        accX = 0f
        accY = 0f
        width = w
        height = h
        startX = offsetX
        seekDeltaSec = 0f
        locked = DragMode.NONE
        movedBeyondSlop = false
    }

    /**
     * 位移累计；超过 touchSlop 后按 |Δx| vs |Δy| 一次性锁定方向，抬手前严格保持。
     *
     * 锁定瞬间扣掉 slop 本身（与 Compose 的 post-slop offset 语义一致）：拖动的前 touchSlop
     * 像素只用来"确认这是拖动"，不参与位移映射，否则一越过阈值进度/亮度就会跳一下。
     * 方向判定用扣减**之前**的累计量，避免扣减把主方向换掉（25px/24px 这种临界情况）。
     */
    fun accumulate(dx: Float, dy: Float, touchSlop: Float) {
        accX += dx
        accY += dy
        if (movedBeyondSlop) return
        if (abs(accX) <= touchSlop && abs(accY) <= touchSlop) return
        movedBeyondSlop = true
        val horizontal = abs(accX) > abs(accY)
        if (horizontal) accX -= touchSlop * sign(accX) else accY -= touchSlop * sign(accY)
        locked = when {
            horizontal -> DragMode.SEEK
            startX < width / 3f -> DragMode.BRIGHTNESS
            startX > width * 2f / 3f -> DragMode.VOLUME
            else -> DragMode.NONE // 中部竖直滑动不响应
        }
    }
}

/** 长按倍速的提示文案（顶部胶囊） */
private fun speedHudText(speed: Float) =
    String.format(java.util.Locale.US, "x%.1f 快速播放中", speed)

/**
 * 播放手势覆盖层（覆盖在 PlayerView 之上的独立响应层）：
 * - 左侧竖直滑动：亮度 0.01~1.0（改 Window.screenBrightness）
 * - 右侧竖直滑动：媒体音量（STREAM_MUSIC，flag=0 不弹系统音量框）
 * - 全屏横滑：非线性动态灵敏度快进/快退（幂函数曲线，微调区极缓/加速区幂次放大），抬手才 seekTo
 * - 单击：切换控制器（3 秒无操作自动淡出，由调用方实现）
 * - 双击：左/右按 DoubleTapConfig 执行快退/快进（带水波纹反馈），中间播放/暂停
 * - 长按：倍速播放，长按后左右滑动微调倍速，松手恢复
 * 所有步长按屏幕宽/高百分比计算，防抖用系统 scaledTouchSlop。
 *
 * 单击 / 双击 / 长按 / 拖动**由同一个 pointerInput 自己判定**，不再用
 * `detectTapGestures` + `detectDragGestures` + `detectDragGesturesAfterLongPress` 叠三层：
 * 三层各自等自己的触发条件、各自消费事件，边界操作上会互相取消 —— 第二下轻点只要抖出
 * touchSlop，拖动层就把这次抬手吃掉，双击静默丢失（用户感知就是"双击快进偶有失效"）；
 * 长按倍速同理（轻点层的 consumeUntilUp 会把倍速微调的拖动取消掉）。
 * 自己维护状态机后，"一次手势到底是什么"只有一个判定者。VR 手势层（VrControls）同一思路。
 */
@Composable
internal fun PlayerGestureOverlay(
    modifier: Modifier,
    enabled: Boolean,
    player: Player,
    config: DoubleTapConfig,
    longPressSpeed: Float,
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
    val touchSlop = remember { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }
    // 死区取系统 scaledTouchSlop：方向锁定的抖动阈值与微调死区保持一致
    val curve = remember(seekCurve, touchSlop) { seekCurve.copy(deadzonePx = touchSlop) }
    val state = remember { GestureDragState() }
    val scope = rememberCoroutineScope()
    // 双击要跨"两次手势"记状态。这些值不能用普通 var 存：pointerInput 块只在键变化时重启，
    // 块里捕获的普通 var 会一直停在块启动那一刻的值（数组/State 对象的身份稳定、内容可变）。
    val lastTapUpAt = remember { longArrayOf(0L) }
    val pendingTap = remember { arrayOfNulls<Job>(1) }
    val suppressTapUntil = remember { longArrayOf(0L) }

    var layer = modifier
    if (enabled) {
        // player / config / longPressSpeed / curve 全部进键：pointerInput 块在键不变时不会重启，
        // 闭包会把"重建播放器（切软/硬解）之前的旧实例"一直带下去 —— 那时双击水波纹照旧出现、
        // seekTo 却落在一个已经释放的播放器上，用户看到的就是"手势偶有失效"。
        // 进键后重建播放器会连手势层一起重启，闭包与播放器始终同步。
        layer = layer.pointerInput(player, config, longPressSpeed, curve) {
            awaitEachGesture {
                // 只认第一根手指：多指（双拇指连点、手掌误触）不影响判定
                val down = awaitFirstDown(requireUnconsumed = false)
                val downAt = down.uptimeMillis
                val w = size.width.toFloat().coerceAtLeast(1f)
                val h = size.height.toFloat().coerceAtLeast(1f)
                // 上层 UI（控制排按钮 / 进度条）已经消费掉的按下不参与轻点/双击/长按判定，
                // 但仍允许拖动（保持既有行为：压在控制条上横滑一样能快进）
                val claimedByUi = down.isConsumed

                // ---- 双击：第二击"按下"就出结果 ----
                // 刻意不等第二击抬手：抬手前手指抖出 touchSlop、抬手被别的层吃掉、或两下点得
                // 太快（系统的 DOUBLE_TAP_MIN_TIME 会把它算成"下一次按下"）——任何一条都会让
                // "等抬手"这条链路丢掉这次双击，正是"偶有失效"的来源。判据只有两条：
                // 上一击是干净的轻点（lastTapUpAt）、这一击按下还在窗口内。
                if (!claimedByUi && lastTapUpAt[0] != 0L &&
                    downAt - lastTapUpAt[0] <= DOUBLE_TAP_WINDOW_MS
                ) {
                    lastTapUpAt[0] = 0L
                    pendingTap[0]?.cancel()
                    pendingTap[0] = null
                    suppressTapUntil[0] = downAt + SINGLE_TAP_SUPPRESS_MS
                    // 用第二击的落点判区（与 Compose 的 onDoubleTap 语义一致）
                    applyDoubleTapZone(player, config, down.position.x / w, onDoubleTapFx)
                    // 这一击的后续（抬手/抖动）不再当拖动或长按处理
                    while (true) {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { it.consume() }
                        if (ev.changes.none { it.pressed }) break
                    }
                    return@awaitEachGesture
                }

                // 一次手势的拖动状态；亮度/音量基准在起手时取
                // 轻点/长按由本层独占：先吃掉这次按下（上层 UI 在 Main 阶段更靠前，
                // 已经先拿到并消费过它自己的那份，这里再消费不会影响按钮点击）
                if (!claimedByUi) down.consume()
                state.reset(down.position.x, down.position.y, w, h)
                val baseBrightness = (context as? Activity)?.window?.attributes?.screenBrightness ?: -1f
                state.baseBrightness = if (baseBrightness < 0f) 0.5f else baseBrightness
                state.baseVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

                // 第一段：等出"抬手（轻点）/ 位移超 slop（拖动）/ 长按时间到（倍速）"
                var upAt = 0L
                val phase = if (claimedByUi) {
                    TapPhase.DRAG
                } else {
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var result: TapPhase? = null
                        while (result == null) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id }
                            if (ch == null) {
                                result = TapPhase.CANCEL
                            } else if (ch.changedToUpIgnoreConsumed()) {
                                upAt = ch.uptimeMillis
                                result = TapPhase.TAP
                            } else {
                                val d = ch.positionChange()
                                state.accumulate(d.x, d.y, touchSlop)
                                if (state.movedBeyondSlop) result = TapPhase.DRAG
                            }
                        }
                        result
                    } ?: TapPhase.LONG_PRESS
                }

                when (phase) {
                    TapPhase.TAP -> {
                        // 轻点：先记下抬手时刻，等一个双击窗口再定性（这一等就是单击唤出控制排的延迟）
                        lastTapUpAt[0] = upAt
                        pendingTap[0]?.cancel()
                        pendingTap[0] = scope.launch {
                            delay(DOUBLE_TAP_WINDOW_MS)
                            // 刚双击过就先吞掉这一下：连点快进时别把控制排又弹出来
                            if (SystemClock.uptimeMillis() >= suppressTapUntil[0]) onToggleController()
                        }
                    }

                    TapPhase.LONG_PRESS -> {
                        // 长按倍速：按住即 longPressSpeed，横移微调，松手恢复 1x
                        player.setPlaybackSpeed(longPressSpeed)
                        onSpeedHud(speedHudText(longPressSpeed))
                        var acc = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (ch.changedToUpIgnoreConsumed()) break
                            ch.consume()
                            acc += ch.positionChange().x
                            val s = (longPressSpeed + acc / SPEED_DRAG_PX).coerceIn(0.5f, 4f)
                            player.setPlaybackSpeed(s)
                            onSpeedHud(speedHudText(s))
                        }
                        player.setPlaybackSpeed(1f)
                        onSpeedHud(null)
                    }

                    TapPhase.DRAG -> {
                        // 方向锁定后逐事件映射（拖动中只更新预览，抬手才真正 seek）
                        var endedWithUp = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (ch.changedToUpIgnoreConsumed()) {
                                endedWithUp = true
                                break
                            }
                            val d = ch.positionChange()
                            state.accumulate(d.x, d.y, touchSlop)
                            // 越过 slop 之后才消费：slop 以内的抖动留给上层 UI（与旧实现一致，
                            // 压在按钮上轻微移动不该把按钮的点击吃掉）
                            if (!state.movedBeyondSlop) continue
                            ch.consume()
                            when (state.locked) {
                                DragMode.SEEK -> {
                                    // 非线性动态灵敏度（幂函数曲线）：死区内 0，微调区极缓，
                                    // 加速区按 |ratio|^p 放大
                                    val dur = player.duration.coerceAtLeast(0L)
                                    val delta = curve.calculateSeekOffset(state.accX, state.width, dur)
                                    state.seekDeltaSec = delta
                                    val target = (player.currentPosition + (delta * 1000).toLong())
                                        .coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
                                    onSeekPreview(
                                        target,
                                        delta.roundToInt(),
                                        curve.isBoosting(state.accX, state.width),
                                    )
                                }
                                DragMode.BRIGHTNESS -> {
                                    val win = (context as? Activity)?.window
                                    if (win != null) {
                                        val v = (state.baseBrightness - state.accY / state.height)
                                            .coerceIn(0.01f, 1f)
                                        val lp = win.attributes
                                        lp.screenBrightness = v
                                        win.attributes = lp
                                        onBrightness(v)
                                    }
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
                        }
                        if (endedWithUp && state.locked == DragMode.SEEK && state.seekDeltaSec != 0f) {
                            val dur = player.duration
                            val target = (player.currentPosition + (state.seekDeltaSec * 1000).toLong())
                                .coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
                            onSeekCommit(target)
                        } else if (state.locked == DragMode.SEEK) {
                            // 锁定过 SEEK 但没提交（指针被系统抢走，或增量恰好为 0）：
                            // 撤掉进度预览，别把它留在画面上
                            onSeekPreviewCancel()
                        }
                        state.seekDeltaSec = 0f
                        state.locked = DragMode.NONE
                    }

                    TapPhase.CANCEL -> {}
                }
            }
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
