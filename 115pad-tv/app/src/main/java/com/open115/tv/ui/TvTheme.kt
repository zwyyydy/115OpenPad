package com.open115.tv.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/** 电视端固定深色（客厅环境、远距离观看，浅色底在电视上很刺眼）。 */
private val TvColorScheme = darkColorScheme(
    primary = Color(0xFF9FC3FF),
    onPrimary = Color(0xFF0F2E52),
    background = Color(0xFF0B0B0D),
    onBackground = Color(0xFFEDEDF0),
    surface = Color(0xFF16171A),
    onSurface = Color(0xFFEDEDF0),
    surfaceVariant = Color(0xFF22242A),
    onSurfaceVariant = Color(0xFFB9BCC6),
    error = Color(0xFFFF8A80),
)

@Composable
internal fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TvColorScheme, content = content)
}

internal val TitleWeight = FontWeight.SemiBold
