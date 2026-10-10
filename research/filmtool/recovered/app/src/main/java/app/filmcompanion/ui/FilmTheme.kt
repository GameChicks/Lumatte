package app.filmcompanion.ui



import android.graphics.Bitmap

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.Brush

import androidx.compose.ui.graphics.ImageShader

import androidx.compose.ui.graphics.Paint

import androidx.compose.ui.graphics.TileMode

import androidx.compose.ui.graphics.asImageBitmap

import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.clip

import androidx.compose.ui.draw.drawWithCache

import androidx.compose.ui.draw.shadow

import androidx.compose.foundation.background

import androidx.compose.foundation.border

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.ui.unit.Dp

import androidx.compose.ui.unit.dp

import kotlin.random.Random

import androidx.compose.runtime.Composable

import androidx.compose.runtime.CompositionLocalProvider

import androidx.compose.runtime.staticCompositionLocalOf

import androidx.compose.foundation.isSystemInDarkTheme



val LocalFilmDark = staticCompositionLocalOf { false }



private val cameraMaterialTones = listOf(
    LibraryMaterialTone.SAGE, LibraryMaterialTone.MIST_BLUE, LibraryMaterialTone.SAND,
    LibraryMaterialTone.DUSTY_ROSE, LibraryMaterialTone.LAVENDER, LibraryMaterialTone.SEA_GLASS
)

fun cameraMaterialTone(brand: String): LibraryMaterialTone {
    val stableIndex = brand.trim().lowercase().hashCode() and Int.MAX_VALUE
    return cameraMaterialTones[stableIndex % cameraMaterialTones.size]
}

object FilmColors {

    val Library: Color @Composable get() = if (LocalFilmDark.current) Color(0xFF181A19) else Color(0xFFE4E4E2)

    val LibraryCard: Color @Composable get() = if (LocalFilmDark.current) Color(0xFF242725) else Color(0xFFF0F0EC)

    val LibraryRaised: Color @Composable get() = if (LocalFilmDark.current) Color(0xFF2C302D) else Color(0xFFF6F6F2)

    val Ink: Color @Composable get() = if (LocalFilmDark.current) Color(0xFFECEEE8) else Color(0xFF101111)

    val Muted: Color @Composable get() = if (LocalFilmDark.current) Color(0xFFAAB2A9) else Color(0xFF60615D)

    val Instrument = Color(0xFF090A0A)

    val Panel = Color(0xFF141514)

    val PanelRaised = Color(0xFF1B1C1A)

    val Ivory = Color(0xFFF1F1EC)

    val DarkMuted = Color(0xFFA0A19B)

    val FilmYellow = Color(0xFFF0D51B)

    val Warm = Color(0xFFD2A56E)

    val Danger = Color(0xFFB44431)

    val ArchiveBlue = Color(0xFF4B86FF)

    val Confetti = listOf(Color(0xFF287FD0), Color(0xFFEFBA20), Color(0xFFE66558),

        Color(0xFF35A984), Color(0xFF8582D6), Color(0xFFF08B43))

}



object FilmType {

    val Sans = androidx.compose.ui.text.font.FontFamily(

        androidx.compose.ui.text.font.Font(app.filmcompanion.R.font.film_sans_regular, androidx.compose.ui.text.font.FontWeight.Normal),

        androidx.compose.ui.text.font.Font(app.filmcompanion.R.font.film_sans_bold, androidx.compose.ui.text.font.FontWeight.Bold))

    val Display = androidx.compose.ui.text.font.FontFamily(

        androidx.compose.ui.text.font.Font(app.filmcompanion.R.font.barlow_condensed_extrabold, androidx.compose.ui.text.font.FontWeight.ExtraBold),

        androidx.compose.ui.text.font.Font(app.filmcompanion.R.font.barlow_condensed_black, androidx.compose.ui.text.font.FontWeight.Black))

    val Mono = androidx.compose.ui.text.font.FontFamily(

        androidx.compose.ui.text.font.Font(app.filmcompanion.R.font.film_mono_regular, androidx.compose.ui.text.font.FontWeight.Normal),

        androidx.compose.ui.text.font.Font(app.filmcompanion.R.font.film_mono_semibold, androidx.compose.ui.text.font.FontWeight.SemiBold))

    val Title = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp)

    val Section = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp)

    val Card = androidx.compose.ui.unit.TextUnit(17f, androidx.compose.ui.unit.TextUnitType.Sp)

    val Body = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp)

    val Secondary = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp)

    val Label = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp)

}



@androidx.compose.runtime.Composable

fun CompanionTheme(dark: Boolean = isSystemInDarkTheme(), content: @androidx.compose.runtime.Composable () -> Unit) {

    CompositionLocalProvider(LocalFilmDark provides dark) {

    val colors = if (dark) androidx.compose.material3.darkColorScheme(

        primary = FilmColors.FilmYellow, onPrimary = Color(0xFF101111),

        background = FilmColors.Instrument, surface = FilmColors.PanelRaised,

        onSurface = FilmColors.Ivory, onSurfaceVariant = FilmColors.DarkMuted,

        error = FilmColors.Danger, secondary = FilmColors.Warm)

    else androidx.compose.material3.lightColorScheme(

        primary = FilmColors.Ink, onPrimary = FilmColors.Ivory,

        background = FilmColors.Library, surface = FilmColors.LibraryRaised,

        onSurface = FilmColors.Ink, onSurfaceVariant = FilmColors.Muted,

        error = FilmColors.Danger, secondary = FilmColors.Warm)

    val base = androidx.compose.material3.Typography()

    val typography = base.copy(

        headlineLarge = base.headlineLarge.copy(fontFamily = FilmType.Sans, fontSize = FilmType.Title),

        titleLarge = base.titleLarge.copy(fontFamily = FilmType.Sans, fontSize = FilmType.Section),

        titleMedium = base.titleMedium.copy(fontFamily = FilmType.Sans, fontSize = FilmType.Card),

        bodyLarge = base.bodyLarge.copy(fontFamily = FilmType.Sans, fontSize = FilmType.Body),

        bodyMedium = base.bodyMedium.copy(fontFamily = FilmType.Sans, fontSize = FilmType.Body),

        bodySmall = base.bodySmall.copy(fontFamily = FilmType.Sans, fontSize = FilmType.Secondary),

        labelLarge = base.labelLarge.copy(fontFamily = FilmType.Sans, fontSize = FilmType.Body),

        labelMedium = base.labelMedium.copy(fontFamily = FilmType.Sans, fontSize = FilmType.Secondary),

        labelSmall = base.labelSmall.copy(fontFamily = FilmType.Sans, fontSize = FilmType.Label))

    androidx.compose.material3.MaterialTheme(colorScheme = colors, typography = typography) {

        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides colors.onSurface, content = content)

    }

    }

}



// A small repeating grain tile is created once. Materials do not allocate noise per frame.

private val materialGrain by lazy {

    val random = Random(741)

    Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {

        val pixels = IntArray(128 * 128) {

            val alpha = random.nextInt(3, 28)

            (alpha shl 24) or if (random.nextBoolean()) 0xFFFFFF else 0

        }

        setPixels(pixels, 0, 128, 0, 0, 128, 128)

    }.asImageBitmap()

}



private fun Modifier.grain(amount: Float) = drawWithCache {

    val paint = Paint().apply {

        shader = ImageShader(materialGrain, TileMode.Repeated, TileMode.Repeated)

        alpha = amount

    }

    onDrawBehind { drawIntoCanvas { it.drawRect(0f, 0f, size.width, size.height, paint) } }

}



/** Warm satin face, broad soft shadow and a fine lit lip. */

@Composable

fun Modifier.libraryMaterial(radius: Dp): Modifier {

    val dark = LocalFilmDark.current

    val shape = RoundedCornerShape(radius)

    return shadow(22.dp, shape, clip = false,

        ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.19f))

        .clip(shape)

        .background(Brush.linearGradient(if (dark) listOf(Color(0xFF353A35), Color(0xFF292D29), Color(0xFF222622))

            else listOf(Color(0xFFF8F8F3), Color(0xFFF0F0EA), Color(0xFFE9E9E3))))

        .grain(0.18f)

        .border(0.7.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.17f else 0.8f),

            Color.White.copy(alpha = 0.16f), Color(0xFFBABBB3).copy(alpha = 0.20f))), shape)

}



/** A matte instrument body below the viewfinder. */

fun Modifier.instrumentBody(radius: Dp): Modifier {

    val shape = RoundedCornerShape(radius)

    return shadow(20.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.32f),

        spotColor = Color.Black.copy(alpha = 0.45f)).clip(shape)

        .background(Brush.verticalGradient(listOf(Color(0xFF242521), Color(0xFF1C1D19), Color(0xFF151612))))

        .grain(0.24f)

        .border(0.7.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.Black.copy(alpha = 0.45f))), shape)

}



/** Dark wells have a shaded upper rim and a very faint lower reflection. */

fun Modifier.instrumentRecess(radius: Dp): Modifier {

    val shape = RoundedCornerShape(radius)

    return shadow(14.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.30f),

        spotColor = Color.Black.copy(alpha = 0.46f)).clip(shape)

        .background(Brush.verticalGradient(listOf(Color(0xFF020303), Color(0xFF080908), Color(0xFF11120F))))

        .grain(0.18f)

        .border(2.dp, Brush.verticalGradient(listOf(Color.Black, Color(0xFF10110F), Color(0xFF42453E).copy(alpha = 0.50f))), shape)

}



/** A raised switch, surrounded by a dark moat and two bevel highlights. */

fun Modifier.instrumentSwitch(radius: Dp): Modifier {

    val shape = RoundedCornerShape(radius)

    return shadow(16.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.33f),

        spotColor = Color.Black.copy(alpha = 0.48f)).clip(shape)

        .background(Brush.verticalGradient(listOf(Color(0xFF363832), Color(0xFF23251F), Color(0xFF141610))))

        .grain(0.22f)

        .border(1.2.dp, Brush.verticalGradient(listOf(Color(0xFF777B71).copy(alpha = 0.65f),

            Color(0xFF30332B), Color.Black)), shape)

}
