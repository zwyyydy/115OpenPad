package com.open115.pad.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 左滑显露删除按钮的行组件（播放列表抽屉用）。
 *
 * 防误触设计：
 * - 只有真实水平拖动才会露出删除层（detectHorizontalDragGestures 自带方向判定，
 *   抽屉列表的竖向滚动不会误触发）
 * - 删除按钮在滑出不足 60% 时【不可点击】（enabled 随滑出比例动态变化），
 *   点到也只会把行弹回，不会误删
 * - 松手时滑出过半自动吸附展开，不足半程自动弹回
 *
 * 用法：[content] 是行内容（条目信息），[onDelete] 在用户点击红色删除按钮时回调。
 */
@Composable
internal fun SwipeToDeleteRow(
    modifier: Modifier = Modifier,
    deleteWidth: Dp = 76.dp,
    enabled: Boolean = true,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    var offsetX by remember { mutableFloatStateOf(0f) } // ≤0，向左滑出的像素
    var rowHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val maxSwipePx = with(density) { deleteWidth.toPx() }

    Box(modifier.onSizeChanged { rowHeightPx = it.height }) {
        // 底层：红色删除层（固定在右侧，被内容行遮住，左滑才露出）
        Surface(
            color = Color(0xFFD32F2F),
            modifier = Modifier.matchParentSize(),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                IconButton(
                    onClick = onDelete,
                    enabled = enabled && offsetX <= -maxSwipePx * 0.6f,
                ) {
                    Icon(Icons.Outlined.Delete, "删除源文件", tint = Color.White)
                }
            }
        }

        // 上层：行内容，随手指水平移动
        Row(
            Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(maxSwipePx, enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            offsetX = (offsetX + amount).coerceIn(-maxSwipePx, 0f)
                        },
                        onDragEnd = {
                            // 过半吸附展开，不足半程弹回
                            offsetX = if (offsetX <= -maxSwipePx * 0.5f) -maxSwipePx else 0f
                        },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}
