package com.team404bnf.app.ui.theme

import androidx.compose.ui.graphics.Color

object ArchiveColors {
    val Background = Color(0xFF121113)
    val Surface = Color(0xFF1D1B1C)
    val Control = Color(0xFF282426)
    val Red = Color(0xFFE85A54)
    val Gold = Color(0xFFD4AF75)
    val Text = Color(0xFFF1EBE6)
    val Muted = Color(0xFFB8B0AA)
    val Divider = Color(0xFF383335)
    val Active = Color(0xFF33292A)
    val Selected = Color(0xFF332B2B)
    val OnAccent = Color(0xFF141112)
    val Speakers = listOf(Gold, Color(0xFFB0C1D5), Color(0xFFA7C3B4),
        Color(0xFFD6ACCC), Color(0xFFDDB98E), Color(0xFF9DCBD0), Color(0xFFBCB0DE))
    fun speaker(index: Int): Color = when {
        index < 0 -> Muted
        index < Speakers.size -> Speakers[index]
        else -> Color.hsl(((index - Speakers.size) * 137.508f + 35f) % 360f, .32f, .74f)
    }
}
