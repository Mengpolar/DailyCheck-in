package com.mengpolar.dailycheck.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0E9F6E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8F5E7),
    onPrimaryContainer = Color(0xFF003920),
    secondary = Color(0xFF4A6358),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8D9),
    onSecondaryContainer = Color(0xFF072015),
    background = Color(0xFFF7F9F6),
    onBackground = Color(0xFF181D1A),
    surface = Color.White,
    onSurface = Color(0xFF181D1A),
    surfaceVariant = Color(0xFFDBE5DD),
    onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF707973),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF54D99F),
    onPrimary = Color(0xFF003821),
    primaryContainer = Color(0xFF005230),
    onPrimaryContainer = Color(0xFFD8F5E7),
    secondary = Color(0xFFB1CCBD),
    onSecondary = Color(0xFF1C352A),
    secondaryContainer = Color(0xFF334B40),
    onSecondaryContainer = Color(0xFFCCE8D9),
    background = Color(0xFF101412),
    onBackground = Color(0xFFDEE4DF),
    surface = Color(0xFF181C1A),
    onSurface = Color(0xFFDEE4DF),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFBFC9C1),
    outline = Color(0xFF89938C),
)

@Composable
fun DailyCheckTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}
