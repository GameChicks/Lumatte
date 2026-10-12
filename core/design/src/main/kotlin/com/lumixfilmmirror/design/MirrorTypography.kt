package com.lumixfilmmirror.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

object MirrorFonts {
    val Body = FontFamily(Font(R.font.oppo_sans_regular), Font(R.font.oppo_sans_medium, FontWeight.Medium),
        Font(R.font.oppo_sans_bold, FontWeight.Bold))
    val Welcome = FontFamily(Font(R.font.nhl_minnesota))
    val Dots = FontFamily(Font(R.font.dotmatrix_chinese))
    val Editorial = FontFamily(Font(R.font.editorial_serif))
    val ChineseEditorial = FontFamily(Font(R.font.editorial_chinese, FontWeight.Black))
    val Handwriting = FontFamily(Font(R.font.handwritten))
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
