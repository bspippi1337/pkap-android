package com.bspippi.pkap.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Blckswan / cyberpunk palette
val NeonGreen = Color(0xFF00FF9F)
val NeonPink = Color(0xFFFF2E63)
val NeonCyan = Color(0xFF00E5FF)
val DeepBlack = Color(0xFF0A0A0F)
val CardBg = Color(0xFF12121A)
val SurfaceVariant = Color(0xFF1A1A24)
val TextPrimary = Color(0xFFE0E0E0)
val TextMuted = Color(0xFF8A8A9A)

private val DarkColors = darkColorScheme(
    primary = NeonGreen,
    onPrimary = DeepBlack,
    secondary = NeonCyan,
    onSecondary = DeepBlack,
    tertiary = NeonPink,
    background = DeepBlack,
    onBackground = TextPrimary,
    surface = CardBg,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextMuted,
    error = NeonPink,
    outline = Color(0xFF2A2A3A)
)

@Composable
fun PKapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
