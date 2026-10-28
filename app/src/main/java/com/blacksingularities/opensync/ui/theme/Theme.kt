package com.blacksingularities.opensync.ui.theme

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

object Palette {
    // Czarno-biały interfejs jak w OpenPDF: akcent = biel, czerwień dla błędów i usuwania.
    val Accent = Color(0xFFFFFFFF)
    val Record = Color(0xFFFF3B47)
    val Ok = Color(0xFF4CD964)
    val Ink = Color(0xFF000000)
    val Panel = Color(0xFF000000)
    val PanelHigh = Color(0xFF1C1C1C)
    val PanelHigher = Color(0xFF2A2A2A)
    val TextDim = Color(0xB3FFFFFF)
    val TextFaint = Color(0x66FFFFFF)
    val Hairline = Color(0x1FFFFFFF)
    val Warn = Color(0xFFFFB020)
}

private val scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.Ink,
    secondary = Color(0xFFE0E0E0),
    onSecondary = Palette.Ink,
    background = Palette.Ink,
    onBackground = Color.White,
    surface = Palette.Panel,
    onSurface = Color.White,
    surfaceVariant = Palette.PanelHigh,
    onSurfaceVariant = Palette.TextDim,
    surfaceContainerLow = Palette.Panel,
    surfaceContainer = Palette.PanelHigh,
    surfaceContainerHigh = Palette.PanelHigh,
    surfaceContainerHighest = Palette.PanelHigher,
    outline = Color(0x33FFFFFF),
    error = Palette.Record,
)

@Composable
fun OpenSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = Typography()) {
        CompositionLocalProvider(
            LocalTextSelectionColors provides TextSelectionColors(handleColor = Color.White, backgroundColor = Color.White.copy(alpha = 0.3f)),
            content = content,
        )
    }
}

/** Cyfry o stałej szerokości (procenty, rozmiary) – jak w OpenPDF. */
val MonoDigits = androidx.compose.ui.text.TextStyle(
    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
    fontSize = 14.sp,
)
