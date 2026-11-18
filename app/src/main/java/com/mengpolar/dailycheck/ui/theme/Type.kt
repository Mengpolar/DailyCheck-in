package com.mengpolar.dailycheck.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

// 星图气质：标题类用衬线（宋体/思源宋），正文保持系统无衬线
private val Serif = FontFamily.Serif

private val base = Typography()

val Typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Serif),
    displayMedium = base.displayMedium.copy(fontFamily = Serif),
    displaySmall = base.displaySmall.copy(fontFamily = Serif),
    headlineLarge = base.headlineLarge.copy(fontFamily = Serif),
    headlineMedium = base.headlineMedium.copy(fontFamily = Serif),
    headlineSmall = base.headlineSmall.copy(fontFamily = Serif),
    titleLarge = base.titleLarge.copy(fontFamily = Serif),
    titleMedium = base.titleMedium.copy(fontFamily = Serif),
    titleSmall = base.titleSmall.copy(fontFamily = Serif),
)
