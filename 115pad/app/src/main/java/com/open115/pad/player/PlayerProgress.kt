package com.open115.pad.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.open115.pad.util.Format

// 固定配色：不跟随动态取色，保证在纯黑视频画面上任何机型都清晰
private val ProgressAccent = Color(0xFF6FA8FF)
private val TrackColor = Color.White.copy(alpha = 0.24f)
private val BufferedInBar = Color.White.copy(alpha = 0.40f)
private val PanelScrim = Color.Black.copy(alpha = 0.45f)

// 形态 A：视觉条高 4dp、Thumb 半径 7dp
private val SeekTrackHeight = 4.dp
private val SeekThumbRadius = 7.dp

/** 形态 A 的触摸热区高度。与视觉条高解耦，保证手指命中率（>= 36dp） */
internal val SeekTouchHeight = 36.dp

/** 时间行 / 拖拽预览共用的固定槽高，切换内容时面板高度不跳动 */
private val TimeSlotHeight = 22.dp

// 形态 B：2~3dp 极细条
internal val MiniBarHeight = 3.dp

/**
 * 形态 A：交互式进度条（Interactive SeekBar）。
 *
 * 四层结构：总时长背景轨 → 缓冲轨 → 已播轨 → 圆形 Thumb。
 * 手势契约：
 * - **按下即预览**该位置，拖动实时跟随，但**不**调用 seekTo；
 * - **抬手才回调一次** [onSeekFinished]，由调用方执行唯一一次 seekTo，
 *   从根本上避免"手指还在拖、播放器进度又往回跑"的左右拉扯；
 * - 拖拽期间时间槽切换为高亮的「位置 / 总时长」预览提示。
 *
 * 尺寸全部用 dp，1080p 手机与 4K 平板上厚度、热区一致。
 */
@Composable
internal fun InteractiveSeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onSeekStart: () -> Unit,
    onSeekFinished: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = durationMs.coerceAtLeast(0L)
    val interactive = total > 0L

    var dragging by remember { mutableStateOf(false) }
    var dragMs by remember { mutableLongStateOf(0L) }

    // 拖拽中以手指位置为准，其余时间跟随播放器进度
    val shownMs =
        if (dragging) dragMs else positionMs.coerceIn(0L, if (interactive) total else Long.MAX_VALUE)
    val fraction = if (interactive) (shownMs.toFloat() / total).coerceIn(0f, 1f) else 0f
    val bufferFraction = if (interactive) (bufferedMs.toFloat() / total).coerceIn(0f, 1f) else 0f

    Column(
        modifier
            .background(PanelScrim, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        // 时间槽：常态「当前位置 …… 总时长」，拖拽时整体换成高亮的预览提示
        Box(
            Modifier.fillMaxWidth().height(TimeSlotHeight),
            contentAlignment = Alignment.Center,
        ) {
            if (dragging) {
                Surface(
                    color = Color.Black.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        Format.playTime(dragMs, "00:00") + " / " + Format.playTime(total),
                        color = ProgressAccent,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.dp),
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Format.playTime(shownMs, "00:00"),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        Format.playTime(total),
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(SeekTouchHeight)
                .pointerInput(interactive, total) {
                    if (!interactive) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // 独占手势，避免透传到下层全屏手势层（否则单击会同时切换控制栏）
                        down.consume()
                        val width = size.width.toFloat()
                        fun msAt(x: Float): Long =
                            (total * (x / width).coerceIn(0f, 1f)).toLong()

                        var target = msAt(down.position.x)
                        dragging = true
                        dragMs = target
                        onSeekStart()
                        try {
                            var lastX = down.position.x
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                if (change.position.x != lastX) {
                                    lastX = change.position.x
                                    target = msAt(change.position.x)
                                    dragMs = target
                                }
                            }
                        } finally {
                            // 抬手、或被系统打断，都收敛成一次提交，状态不残留
                            dragging = false
                            onSeekFinished(target)
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cy = size.height / 2f
                val trackH = SeekTrackHeight.toPx()
                val radius = trackH / 2f
                val corner = CornerRadius(radius, radius)
                val w = size.width

                drawRoundRect(TrackColor, Offset(0f, cy - radius), Size(w, trackH), corner)
                if (bufferFraction > 0f) {
                    drawRoundRect(
                        BufferedInBar,
                        Offset(0f, cy - radius),
                        Size(w * bufferFraction, trackH),
                        corner,
                    )
                }
                if (fraction > 0f) {
                    drawRoundRect(
                        ProgressAccent,
                        Offset(0f, cy - radius),
                        Size(w * fraction, trackH),
                        corner,
                    )
                }
                // Thumb：白环 + 强调色内芯；拖拽时放大，给出"抓住了"的反馈
                val cx = w * fraction
                val thumbR = SeekThumbRadius.toPx() * if (dragging) 1.25f else 1f
                drawCircle(Color.White, radius = thumbR, center = Offset(cx, cy))
                drawCircle(ProgressAccent, radius = thumbR * 0.55f, center = Offset(cx, cy))
            }
        }
    }
}

/**
 * 形态 B：常驻底部迷你进度条（Mini Progress Bar）。
 *
 * 极简半透明细条，紧贴播放器最底部，**没有 Thumb、也不挂任何 pointerInput**，
 * 因此不会拦截全屏手势，也不影响观看。仅由缓冲轨与已播轨两层构成。
 */
@Composable
internal fun MiniProgressBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    modifier: Modifier = Modifier,
) {
    val total = durationMs.coerceAtLeast(0L)
    val played = if (total > 0L) (positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f
    val buffered = if (total > 0L) (bufferedMs.toFloat() / total).coerceIn(0f, 1f) else 0f

    Canvas(modifier.fillMaxWidth().height(MiniBarHeight)) {
        val w = size.width
        val h = size.height
        drawRect(Color.White.copy(alpha = 0.16f), size = Size(w, h))
        if (buffered > played) {
            drawRect(Color.White.copy(alpha = 0.34f), size = Size(w * buffered, h))
        }
        if (played > 0f) {
            drawRect(ProgressAccent.copy(alpha = 0.9f), size = Size(w * played, h))
        }
    }
}
