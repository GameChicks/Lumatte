package com.lumixfilmmirror.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.ExperimentalTextApi

@OptIn(ExperimentalTextApi::class)
object MirrorFonts {
    // Use the original variable font; weight instances are runtime settings, not edited files.
    val Body = FontFamily(listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium,
        FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(R.font.oppo_sans_4, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    })
    val Welcome = FontFamily(Font(R.font.teko, FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))))
    val Dots = FontFamily(Font(R.font.jinzi_tongyuan_ui))
    // Variable font resources carry real normal/italic faces and 300–700 weights.
    val Editorial = FontFamily(listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium,
        FontWeight.SemiBold, FontWeight.Bold).flatMap { weight -> listOf(
            Font(R.font.cormorant_garamond, weight),
            Font(R.font.cormorant_garamond_italic, weight, FontStyle.Italic)) })
    val ChineseEditorial = FontFamily(Font(R.font.editorial_chinese, FontWeight.Black))
    val Handwriting = FontFamily(Font(R.font.handwritten))
    val ChineseHandwriting = FontFamily(Font(R.font.likejianjianti))
    val ScriptAccent = FontFamily(Font(R.font.great_vibes))
    val NarrowItalic = FontFamily(Font(R.font.instrument_serif_italic, style = FontStyle.Italic))
    val FilmDisplay = FontFamily(Font(R.font.instrument_serif_regular),
        Font(R.font.instrument_serif_italic, style = FontStyle.Italic))
    val FilmPoster = FontFamily(Font(R.font.cormorant_garamond, FontWeight.Bold))
}

internal val MirrorTypography = Typography().let { t ->
    t.copy(displayLarge = t.displayLarge.copy(fontFamily = MirrorFonts.Editorial),
        displayMedium = t.displayMedium.copy(fontFamily = MirrorFonts.Editorial),
        displaySmall = t.displaySmall.copy(fontFamily = MirrorFonts.Editorial),
        headlineLarge = t.headlineLarge.copy(fontFamily = MirrorFonts.ChineseEditorial),
        headlineMedium = t.headlineMedium.copy(fontFamily = MirrorFonts.ChineseEditorial),
        headlineSmall = t.headlineSmall.copy(fontFamily = MirrorFonts.ChineseEditorial),
        titleLarge = t.titleLarge.copy(fontFamily = MirrorFonts.ChineseEditorial),
        titleMedium = t.titleMedium.copy(fontFamily = MirrorFonts.ChineseEditorial),
        titleSmall = t.titleSmall.copy(fontFamily = MirrorFonts.Body),
        bodyLarge = t.bodyLarge.copy(fontFamily = MirrorFonts.Body),
        bodyMedium = t.bodyMedium.copy(fontFamily = MirrorFonts.Body),
        bodySmall = t.bodySmall.copy(fontFamily = MirrorFonts.Body),
        labelLarge = t.labelLarge.copy(fontFamily = MirrorFonts.Body),
        labelMedium = t.labelMedium.copy(fontFamily = MirrorFonts.Body),
        labelSmall = t.labelSmall.copy(fontFamily = MirrorFonts.Body))
}
