package com.open115.pad.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 全局浅色方案：柔和微冷浅灰底（#F8FAFC）+ 纯白卡片 + 高饱和现代蓝（#2563EB）。
 * 刻意不跟随系统深色与动态取色——客户端以浅色轻量质感为基准（播放器内部自成暗色空间）。
 */
private val LightColors = lightColorScheme(
    primary = AppColors.Accent,
    onPrimary = Color.White,
    primaryContainer = AppColors.AccentSoft,
    onPrimaryContainer = AppColors.AccentDeep,
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    tertiary = AppColors.AccentDeep,
    background = AppColors.Bg,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Card,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.GraySoft,
    onSurfaceVariant = AppColors.TextSecondary,
    surfaceContainer = AppColors.Card,
    surfaceContainerLow = AppColors.Bg,
    surfaceContainerHigh = AppColors.Card,
    outline = Color(0xFFE2E8F0),
    outlineVariant = AppColors.CardBorder,
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = AppColors.RedBg,
    onErrorContainer = AppColors.RedFg,
)

@Composable
fun Open115Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}

/**
 * 媒体库专用的深色方案。
 *
 * **不全局改深色**：客户端整体是浅色轻量质感（见上面 LightColors 的注释）。只有媒体库这一条线
 * 是"沉浸式看片"的场景 —— 列表 / 海报墙 / 详情页三屏连成一体，而详情页是 fanart 铺满 +
 * 取色底色，天然是暗的；列表和墙跟着暗才不割裂（用户原话：「和详情页协调一些」）。
 *
 * 三个页面只用了 `MaterialTheme.colorScheme.*`，所以**在路由层套一层这个就够了，不用逐个控件改**。
 *
 * ★ 底色是**深灰**（#1C1C23）不是近黑：第一版用了 #0E0E12，整页压得太重、像块黑板
 *   （用户：「这个颜色又太深了」）。现在底色比卡片低一档、卡片又比底色亮一档，
 *   靠这层明度差把层次做出来，而不是靠描边。
 *
 * `primary` 比浅色方案浅一档：`#2563EB` 在深灰底上太闷，搜索框聚焦线、可点文本都看不清。
 * `surfaceVariant` 也不能太亮 —— 透明 LOGO 海报（示例影片那几张）会直接露出它，亮了就是一块白方块。
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FB4FF),
    onPrimary = Color(0xFF0E1524),
    primaryContainer = Color(0xFF2A3A5C),
    onPrimaryContainer = Color(0xFFD3E0FF),
    secondary = Color(0xFF8AD8F8),
    onSecondary = Color(0xFF0A2634),
    tertiary = Color(0xFFA9C8FF),
    background = Color(0xFF1C1C23),
    onBackground = Color(0xFFE9E9EF),
    surface = Color(0xFF26262E),
    onSurface = Color(0xFFE9E9EF),
    surfaceVariant = Color(0xFF34343E),
    onSurfaceVariant = Color(0xFFABABB8),
    surfaceContainer = Color(0xFF26262E),
    surfaceContainerLow = Color(0xFF202027),
    surfaceContainerHigh = Color(0xFF31313A),
    // ★ 这一档必须显式设：Material3 的 `Card` 容器色用的是 surfaceContainerHighest，
    //   不设就回落到 M3 基线的默认值（#36343B，**带紫调**），和这套冷灰调色板不是一家人。
    surfaceContainerHighest = Color(0xFF33333D),
    outline = Color(0xFF3F3F4A),
    outlineVariant = Color(0xFF2E2E38),
    error = Color(0xFFFF7B7B),
    onError = Color(0xFF2E0C0C),
    errorContainer = Color(0xFF43201F),
    onErrorContainer = Color(0xFFFFD2CF),
)

/** 媒体库这条线专用：把内容套进深色方案（路由层包一次，三个页面都跟着变） */
@Composable
fun Open115DarkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
