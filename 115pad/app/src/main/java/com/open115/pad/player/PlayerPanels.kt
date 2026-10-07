package com.open115.pad.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 播放器面板（弹幕 / 滤镜 / VR）共用的视觉件。
 *
 * 三块面板此前各自画容器、各自塞一份 Material 默认 Slider —— 粗轨道、紫灰剩余段、
 * 竖条 thumb、steps 刻度点，跟视频上的暗色氛围完全不搭，三块面板之间也各有各的糙法。
 * 这里统一成一套：[PlayerSheet] 容器（标题行 + 状态副标题 + 大关闭热区）、
 * [PlayerSliderCell] 轻量滑条（细轨、小圆点 thumb、无刻度点、双击回默认）。
 * 胶囊 chips 即 [VrChip]（在 PlayerActivity.kt），三块面板本来就共用。
 *
 * 只动视觉：参数语义（拖动实时上屏 / 抬手落盘 / steps 吸附）与原来完全一致。
 */

/** 播放器主色：与进度条同源（PlayerProgress.ProgressAccent），面板内填充、选中态都用它 */
internal val PlayerAccent = Color(0xFF6FA8FF)

/** 面板容器：深蓝黑半透明底 + 细描边 + 18dp 圆角；顶部一行标题/状态/关闭键 */
@Composable
internal fun PlayerSheet(
    title: String,
    /** 标题右侧的状态小字（弹幕匹配状态、当前滤镜名等）；null 则标题独占一行 */
    subtitle: String? = null,
    /** 收起面板（两侧圆钮列随之回来） */
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** 标题行是否撑满可用宽度。
     *  true（弹幕/滤镜）= 面板拉通，滑条两列吃满宽度；
     *  false（VR）= 面板按**内容**收窄成紧凑一块，靠调用方的 align 居中 ——
     *  全宽长框包着一行小胶囊，视觉上空得慌。false 时 subtitle 必须为 null。 */
    fillWidth: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = Color(0xD90B0D12),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
        modifier = modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 7.dp, bottom = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier,
            ) {
                Text(
                    title,
                    color = Color.White.copy(alpha = 0.92f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                when {
                    subtitle != null -> {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            subtitle,
                            color = Color.White.copy(alpha = 0.55f),
                            // 同 VrChip：16sp 行高会裁掉 12sp 汉字下缘，放到 18sp
                            style = MaterialTheme.typography.labelMedium.copy(lineHeight = 18.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    fillWidth -> Spacer(Modifier.weight(1f))
                    else -> Spacer(Modifier.width(10.dp))
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "收起面板",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

/**
 * 面板里的一格滑条：上方一行「标签 …… 数值」，下方细轨滑条。
 *
 * - 轨道 3dp：已填充 [PlayerAccent]、未填充白 20%，去掉 Material 默认的紫灰粗轨
 * - thumb：白底小圆点 + 主色描边，拖动中放大 —— 替代默认的大灰圆
 * - steps 只用于吸附（挡住画质/时效这类离散档位），**不画刻度点**：
 *   缓存时效 30 档那排密集小点是整个面板最乱的元素
 * - 双击整格回到 [defaultValue]（可空 = 不启用）：滤镜调参高频动作，不用拖回原位
 *
 * 事件语义不变：[onChange] 拖动中实时上屏、[onCommit] 抬手落盘。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSliderCell(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    /** 双击回到的默认值；null = 该滑条没有"回默认"动作 */
    defaultValue: Float? = null,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val dragging by interactionSource.collectIsDraggedAsState()
    Column(
        modifier.pointerInput(defaultValue) {
            detectTapGestures(onDoubleTap = {
                if (defaultValue != null) {
                    onChange(defaultValue)
                    onCommit()
                }
            })
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        ) {
            Text(
                label,
                color = Color.White.copy(alpha = 0.68f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            // 等宽数字：拖动时数值宽度不跳，右端保持稳定
            Text(
                valueText,
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
            )
        }
        PlayerSliderCore(
            value = value,
            range = range,
            steps = steps,
            interactionSource = interactionSource,
            onChange = onChange,
            onCommit = onCommit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 「标签 + 滑条 + 数值」单行居中排（原版 VR 面板的排版：滑条不拉满整行，
 * 整组在面板里居中，视觉重心不偏）—— VR 的边缘畸变抑制用它。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    /** 滑条宽度（原版 300dp，太宽会重新压向两边） */
    sliderWidth: androidx.compose.ui.unit.Dp = 300.dp,
    steps: Int = 0,
    defaultValue: Float? = null,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.pointerInput(defaultValue) {
            detectTapGestures(onDoubleTap = {
                if (defaultValue != null) {
                    onChange(defaultValue)
                    onCommit()
                }
            })
        },
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.68f),
            style = MaterialTheme.typography.labelMedium.copy(lineHeight = 18.sp),
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        PlayerSliderCore(
            value = value,
            range = range,
            steps = steps,
            interactionSource = interactionSource,
            onChange = onChange,
            onCommit = onCommit,
            modifier = Modifier.width(sliderWidth),
        )
        Spacer(Modifier.width(6.dp))
        // 固定宽度：数值从"关"到"1.0"跳动时不拽动整行
        Text(
            valueText,
            color = Color.White.copy(alpha = 0.92f),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
            modifier = Modifier.width(30.dp),
        )
    }
}

/** 细轨滑条本体（Cell 与 Row 共用）：3dp 轨 + 圆点 thumb + steps 吸附无刻度点 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSliderCore(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    interactionSource: MutableInteractionSource,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dragging by interactionSource.collectIsDraggedAsState()
    Slider(
        value = value,
        onValueChange = onChange,
        onValueChangeFinished = onCommit,
        valueRange = range,
        steps = steps,
        interactionSource = interactionSource,
        thumb = {
            val d = if (dragging) 16.dp else 12.dp
            Box(
                Modifier
                    .size(d)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(d - 5.dp)
                        .clip(CircleShape)
                        .border(2.dp, PlayerAccent, CircleShape),
                )
            }
        },
        track = {
            val span = range.endInclusive - range.start
            val frac = if (span > 0f) ((value - range.start) / span).coerceIn(0f, 1f) else 0f
            Canvas(Modifier.fillMaxWidth().height(26.dp)) {
                val trackH = 3.dp.toPx()
                val y = size.height / 2f
                val radius = CornerRadius(trackH / 2f)
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.20f),
                    topLeft = Offset(0f, y - trackH / 2f),
                    size = Size(size.width, trackH),
                    cornerRadius = radius,
                )
                val fillW = size.width * frac
                if (fillW > 0.5f) {
                    drawRoundRect(
                        color = PlayerAccent,
                        topLeft = Offset(0f, y - trackH / 2f),
                        size = Size(maxOf(fillW, trackH), trackH),
                        cornerRadius = radius,
                    )
                }
            }
        },
        modifier = modifier,
    )
}
