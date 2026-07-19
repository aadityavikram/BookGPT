package com.bookgpt.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BrandRed = Color(0xFFDC2626)
private val BrandRedLight = Color(0xFFEF4444)
private val NearBlack = Color(0xFF0A0A0A)
private val Panel = Color(0xFF141414)
private val TextPrimary = Color(0xFFE5E5E5)
private val TextMuted = Color(0xFFA3A3A3)

private val DarkColors = darkColorScheme(
    primary = BrandRed,
    onPrimary = Color.White,
    secondary = BrandRedLight,
    background = NearBlack,
    surface = Panel,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextMuted,
    outline = Color(0xFF2E2E2E),
)

private val LightColors = lightColorScheme(
    primary = BrandRed,
    onPrimary = Color.White,
    secondary = BrandRedLight,
    background = Color(0xFFFAFAFA),
    surface = Color.White,
    onBackground = Color(0xFF171717),
    onSurface = Color(0xFF171717),
)

@Composable
fun BookGptTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme || isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
