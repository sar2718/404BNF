package com.team404bnf.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),

    background = Color(0xFF101318),
    onBackground = Color(0xFFE6EAF0),

    surface = Color(0xFF1B2027),
    onSurface = Color(0xFFE6EAF0),

    surfaceVariant = Color(0xFF252B35),
    onSurfaceVariant = Color(0xFFACB6C6)
)

@Composable
fun BnfTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}