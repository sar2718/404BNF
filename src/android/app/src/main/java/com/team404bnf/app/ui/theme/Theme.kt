package com.team404bnf.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

object ArchiveDimens {
    val PagePadding = 20.dp
    val Corner = 12.dp
    val ButtonHeight = 56.dp
    val ContentMaxWidth = 720.dp
}

private val DarkColors = darkColorScheme(
    primary = ArchiveColors.Gold, onPrimary = ArchiveColors.OnAccent,
    primaryContainer = ArchiveColors.Selected, onPrimaryContainer = ArchiveColors.Gold,
    secondary = ArchiveColors.Red, onSecondary = ArchiveColors.OnAccent,
    secondaryContainer = ArchiveColors.Active, onSecondaryContainer = ArchiveColors.Text,
    tertiary = ArchiveColors.Speakers[2],
    background = ArchiveColors.Background, onBackground = ArchiveColors.Text,
    surface = ArchiveColors.Surface, onSurface = ArchiveColors.Text,
    surfaceVariant = ArchiveColors.Control, onSurfaceVariant = ArchiveColors.Muted,
    surfaceContainer = ArchiveColors.Surface,
    surfaceContainerLow = ArchiveColors.Surface,
    surfaceContainerHigh = ArchiveColors.Control,
    surfaceContainerHighest = ArchiveColors.Control,
    surfaceTint = ArchiveColors.Gold,
    outline = ArchiveColors.Divider, outlineVariant = ArchiveColors.Divider,
    error = ArchiveColors.Red, onError = ArchiveColors.OnAccent
)

@Composable
fun BnfTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = ArchiveTypography,
        shapes = Shapes(
            small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(22.dp)
        ),
        content = content
    )
}
