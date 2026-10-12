package com.lumixfilmmirror.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class FilmMaterials(
    val background: Color,
    val paper: Color,
    val paperHighlight: Color,
    val rim: Color,
    val shadow: Color,
    val ink: Color,
    val secondaryInk: Color,
    val amber: Color,
)

private val LightMaterials = FilmMaterials(
    background = Color(0xFFF0EEE7), paper = Color(0xFFFAF5EC),
    paperHighlight = Color(0xFFFFFDF6), rim = Color(0xFFD2CEC2),
    shadow = Color(0x33252017), ink = Color(0xFF262821),
    secondaryInk = Color(0xFF68685F), amber = Color(0xFF89591C),
)
private val DarkMaterials = FilmMaterials(
    background = Color(0xFF101214), paper = Color(0xFF222629),
    paperHighlight = Color(0xFF30363A), rim = Color(0xFF454A4D),
    shadow = Color(0x77000000), ink = Color(0xFFF0EEE4),
    secondaryInk = Color(0xFFB3B8AC), amber = Color(0xFFDBB278),
)

val LocalFilmMaterials = staticCompositionLocalOf { LightMaterials }

object FilmSpacing {
    val screen = 20.dp
    val panel = 16.dp
    val gap = 12.dp
    val compact = 8.dp
    val controlMinimum = 48.dp
    val cardRadius = 24.dp
}

@Composable
fun FilmMirrorTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val tones = if (dark) DarkMaterials else LightMaterials
    val scheme = if (dark) darkColorScheme(
        primary = tones.amber, onPrimary = Color(0xFF241B0F),
        background = tones.background, onBackground = tones.ink,
        surface = tones.paper, onSurface = tones.ink,
        surfaceVariant = tones.paperHighlight, onSurfaceVariant = tones.secondaryInk,
        outline = tones.rim,
    ) else lightColorScheme(
        primary = tones.amber, onPrimary = Color(0xFFFFFBF1),
        background = tones.background, onBackground = tones.ink,
        surface = tones.paper, onSurface = tones.ink,
        surfaceVariant = tones.paperHighlight, onSurfaceVariant = tones.secondaryInk,
        outline = tones.rim,
    )
    CompositionLocalProvider(LocalFilmMaterials provides tones, LocalContentColor provides tones.ink) {
        MaterialTheme(colorScheme = scheme, typography = MirrorTypography, content = content)
    }
}
