package com.open115.pad.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 把图**自己的 alpha** 从 [from]（纵向比例）处开始渐隐，到 [to] 处彻底透明 ——
 * 下沿"溶进"下面的底色。
 *
 * ★ 为什么不用「在图上盖一层 透明→底色 的渐变」：那会留下一块**比周围略深的矩形**。
 *   渐变是叠加在图上画的，图的边缘还在，所以底色的那一块和旁边的纯底色对不上。
 *   把图自己的 alpha 推到 0 才是真的溶进去 —— 这是这一处唯一的正解。
 *
 * ★ 必须 `CompositingStrategy.Offscreen`：`BlendMode.DstIn` 是拿 brush 的 alpha 去乘
 *   **目标层**的 alpha。不隔离成离屏图层的话，它会把下面已经画好的兄弟节点（底色、别的层）
 *   一起擦掉，表现为"整块区域变透明/发黑"。
 *
 * 用法（底色层在下、图在上）：
 * ```
 * Box(Modifier.background(base)) { Image(..., Modifier.fillMaxSize().dissolve(0.45f, 1f)) }
 * ```
 */
fun Modifier.dissolve(from: Float = 0.55f, to: Float = 1f): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                // Black 在 DstIn 里表示"保留"（alpha=1），Transparent 表示"擦掉"
                colors = listOf(Color.Black, Color.Transparent),
                startY = size.height * from,
                endY = size.height * to,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
