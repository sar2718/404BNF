package com.team404bnf.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.team404bnf.app.R

private val ArchiveFont = FontFamily(
    Font(R.font.golos_text_regular, FontWeight.Normal),
    Font(R.font.golos_text_medium, FontWeight.Medium),
    Font(R.font.golos_text_semibold, FontWeight.SemiBold),
    Font(R.font.golos_text_bold, FontWeight.Bold)
)
private fun text(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = ArchiveFont, fontSize = size.sp, lineHeight = line.sp,
    fontWeight = weight, letterSpacing = 0.sp
)
val ArchiveTypography = Typography(
    displayLarge = text(48, 56, FontWeight.Medium),
    displayMedium = text(44, 52, FontWeight.Bold),
    displaySmall = text(36, 44, FontWeight.Bold),
    headlineLarge = text(32, 38, FontWeight.Bold),
    headlineMedium = text(28, 34, FontWeight.Bold),
    headlineSmall = text(24, 30, FontWeight.SemiBold),
    titleLarge = text(22, 28, FontWeight.SemiBold),
    titleMedium = text(16, 22, FontWeight.Medium),
    titleSmall = text(14, 20, FontWeight.Medium),
    bodyLarge = text(16, 24), bodyMedium = text(14, 21), bodySmall = text(12, 18),
    labelLarge = text(14, 20, FontWeight.Medium),
    labelMedium = text(12, 16, FontWeight.Medium),
    labelSmall = text(10, 14, FontWeight.Medium)
)
