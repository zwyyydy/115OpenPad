package com.open115.pad.ui.theme

import androidx.compose.material3.MaterialTheme
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
