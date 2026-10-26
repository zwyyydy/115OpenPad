package com.open115.pad.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.open115.pad.ui.theme.AppColors
import com.open115.pad.ui.theme.AppRadius

/**
 * 空态：柔和的圆形图标底 + 标题 + 一句说明（+ 可选操作）。
 * 比一行居中的灰字更像"这一页本来就长这样"，而不是渲染失败。
 *
 * 颜色走 [AppColors]，所以媒体库的深色路由里会自动变成深色版本。
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(AppColors.GraySoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = AppColors.TextTertiary, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        hint?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
        action?.let {
            Spacer(Modifier.height(18.dp))
            it()
        }
    }
}

/** 一块会呼吸的骨架砖：底色 + 一道横扫的高光，明暗两套调色板都能用 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(AppRadius.sm),
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val x by transition.animateFloat(
        initialValue = -500f,
        targetValue = 1500f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "skeletonX",
    )
    // 三级文字色压到 16%：浅色底上是稍深的一道，深色底上是稍亮的一道
    val highlight = AppColors.TextTertiary.copy(alpha = 0.16f)
    Box(modifier.clip(shape).background(AppColors.GraySoft)) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    colors = listOf(Color.Transparent, highlight, Color.Transparent),
                    start = Offset(x, 0f),
                    end = Offset(x + 420f, 420f),
                ),
            ),
        )
    }
}

/**
 * 文件列表的加载骨架：和真实行同高同圆角，数据一到就"原地长出文字"，
 * 不会先从一张居中的转圈图跳到一屏卡片。
 */
@Composable
fun FileListSkeleton(rows: Int = 8, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(rows) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppRadius.md))
                    .background(AppColors.Card)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBox(Modifier.size(44.dp), RoundedCornerShape(AppRadius.sm))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    SkeletonBox(Modifier.fillMaxWidth(0.42f).height(14.dp))
                    Spacer(Modifier.height(9.dp))
                    SkeletonBox(Modifier.fillMaxWidth(0.24f).height(11.dp))
                }
            }
        }
    }
}

/** 海报网格的加载骨架：2:3 的砖 + 两行标题，和真实卡片同尺寸 */
@Composable
fun PosterGridSkeleton(
    compact: Boolean,
    modifier: Modifier = Modifier,
    count: Int = 12,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (compact) 110.dp else 130.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = false,
    ) {
        items(count) {
            Column {
                SkeletonBox(Modifier.fillMaxWidth().aspectRatio(2f / 3f), RoundedCornerShape(AppRadius.md))
                Spacer(Modifier.height(8.dp))
                SkeletonBox(Modifier.fillMaxWidth(0.85f).height(12.dp), RoundedCornerShape(4.dp))
                Spacer(Modifier.height(5.dp))
                SkeletonBox(Modifier.fillMaxWidth(0.45f).height(10.dp), RoundedCornerShape(4.dp))
            }
        }
    }
}
