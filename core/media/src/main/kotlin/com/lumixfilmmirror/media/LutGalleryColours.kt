package com.lumixfilmmirror.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

enum class LutColourOrigin { PHOTO_PREVIEW, USER_EXAMPLE, CREATOR_EXAMPLE }
enum class LutColourFamily { RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PURPLE, PINK, NEUTRAL }
enum class LutExampleColourMode { COLOUR, NEAR_MONOCHROME, UNKNOWN }

data class LutColourSwatch(val argb: Int, val weight: Float, val family: LutColourFamily,
    val titleTextArgb: Int, val bodyTextArgb: Int)

/** Describes this example, never the LUT's universal suitability or its mathematical output range. */
data class LutExampleColours(val origin: LutColourOrigin, val exampleIdentity: String,
    val dominantArgb: Int?, val swatches: List<LutColourSwatch>,
    val familyWeights: Map<LutColourFamily, Float>, val mode: LutExampleColourMode)

/** Owns only a small analysis bitmap; the caller keeps its borrowed input alive until return. */
object LutGalleryColours {
    suspend fun analyse(bitmap: Bitmap, origin: LutColourOrigin, exampleIdentity: String): LutExampleColours =
        withContext(Dispatchers.Default) {
            require(exampleIdentity.isNotBlank() && exampleIdentity.length <= 1024)
            require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) { "LUT 例图已不可用" }
            require(bitmap.config != Bitmap.Config.HARDWARE) { "LUT 取色需要已解码的软件缩略图" }
            coroutineContext.ensureActive()
            val scale = minOf(1.0, 128.0 / maxOf(bitmap.width, bitmap.height))
            val analysis = Bitmap.createBitmap(maxOf(1, (bitmap.width * scale).roundToInt()),
                maxOf(1, (bitmap.height * scale).roundToInt()), Bitmap.Config.ARGB_8888)
            try {
                // Use an owned software snapshot. Transparent examples
                // are composited on white photo paper; do not recycle or modify the caller's bitmap.
                analysis.eraseColor(Color.WHITE)
                Canvas(analysis).drawBitmap(bitmap, null, Rect(0, 0, analysis.width, analysis.height),
                    Paint(Paint.FILTER_BITMAP_FLAG))
                coroutineContext.ensureActive()
                // Keep black/white/neutral swatches so monochrome examples remain analyzable.
                val palette = Palette.from(analysis).resizeBitmapArea(128 * 128).maximumColorCount(16)
                    .clearFilters().generate()
                coroutineContext.ensureActive()
                val all = palette.swatches.filter { it.population > 0 }.sortedByDescending { it.population }
                val total = all.sumOf { it.population.toLong() }
                if (total == 0L) return@withContext LutExampleColours(origin, exampleIdentity, null,
                    emptyList(), emptyMap(), LutExampleColourMode.UNKNOWN)
                val hsv = FloatArray(3)
                val swatches = all.map { swatch ->
                    Color.colorToHSV(swatch.rgb, hsv)
                    val family = if (hsv[1] < .12f || hsv[2] < .08f) LutColourFamily.NEUTRAL else when {
                        hsv[0] < 15 || hsv[0] >= 345 -> LutColourFamily.RED
                        hsv[0] < 45 -> LutColourFamily.ORANGE
                        hsv[0] < 75 -> LutColourFamily.YELLOW
                        hsv[0] < 165 -> LutColourFamily.GREEN
                        hsv[0] < 200 -> LutColourFamily.CYAN
                        hsv[0] < 265 -> LutColourFamily.BLUE
                        hsv[0] < 315 -> LutColourFamily.PURPLE
                        else -> LutColourFamily.PINK
                    }
                    LutColourSwatch(swatch.rgb, swatch.population.toFloat() / total.toFloat(), family,
                        swatch.titleTextColor, swatch.bodyTextColor)
                }
                val families = swatches.groupBy { it.family }.mapValues { (_, values) -> values.sumOf { it.weight.toDouble() }.toFloat() }
                val mode = if ((families[LutColourFamily.NEUTRAL] ?: 0f) >= .93f)
                    LutExampleColourMode.NEAR_MONOCHROME else LutExampleColourMode.COLOUR
                LutExampleColours(origin, exampleIdentity, all.first().rgb, swatches.take(6), families, mode)
            } finally { analysis.recycle() }
        }
}
