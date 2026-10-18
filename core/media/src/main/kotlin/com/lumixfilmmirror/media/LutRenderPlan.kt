package com.lumixfilmmirror.media

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** Shared CPU path for preview and export; bounded scanline tiles, encoded sRGB input/output. */
class LutRenderPlan internal constructor(private val first: CubeLut?, private val firstStrength: Float,
    private val second: CubeLut?, private val secondStrength: Float, private val grain: Float, private val dithering: Boolean) {
    internal suspend fun render(bitmap: Bitmap, source: SourceImage, checkActive: () -> Unit) {
        if ((first == null || firstStrength == 0f) && (second == null || secondStrength == 0f) && grain == 0f && !dithering) return
        checkActive()
        if (NativeLutBridge.available) {
            NativeLutRenderer.render(bitmap, source, first.takeIf { firstStrength > 0f }, firstStrength,
                second.takeIf { secondStrength > 0f }, secondStrength, grain, dithering)
            checkActive()
            return
        }
        renderKotlin(bitmap, source, checkActive)
    }

    private fun renderKotlin(bitmap: Bitmap, source: SourceImage, checkActive: () -> Unit) {
        val tileHeight = minOf(32, bitmap.height)
        val pixels = IntArray(bitmap.width * tileHeight)
        val input = FloatArray(3); val output = FloatArray(3)
        val seed = source.id.value.hashCode()
        val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
        fun apply(table: CubeLut?, strength: Float) {
            if (table == null || strength == 0f) return
            table.apply(input, output)
            for (channel in 0..2) input[channel] += (output[channel] - input[channel]) * strength
        }
        for (top in 0 until bitmap.height step tileHeight) {
            checkActive()
            val rows = minOf(tileHeight, bitmap.height - top)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, top, bitmap.width, rows)
            for (row in 0 until rows) {
                checkActive()
                for (x in 0 until bitmap.width) {
                    val index = row * bitmap.width + x
                    val pixel = pixels[index]
                    input[0] = ((pixel ushr 16) and 255) / 255f
                    input[1] = ((pixel ushr 8) and 255) / 255f
                    input[2] = (pixel and 255) / 255f
                    apply(first, firstStrength); apply(second, secondStrength)
                    if (grain > 0f) {
                        val sx = (x.toLong() * source.size.width / bitmap.width / 3).toInt()
                        val sy = ((top + row).toLong() * source.size.height / bitmap.height / 3).toInt()
                        var hash = seed xor (sx * 73856093) xor (sy * 19349663)
                        hash = (hash xor (hash ushr 16)) * -2048144789
                        hash = (hash xor (hash ushr 13)) * -1028477387
                        hash = hash xor (hash ushr 16)
                        val light = (.2126f * input[0] + .7152f * input[1] + .0722f * input[2]).coerceIn(0f, 1f)
                        val noise = (((hash ushr 8) and 65535) / 65535f - .5f) * grain * .12f * (.35f + .65f * (1 - light))
                        for (channel in 0..2) input[channel] += noise
                    }
                    if (dithering) {
                        // Centred thresholds have zero mean; grain and dithering stay independent.
                        val noise = ((bayer[((top + row) and 3) * 4 + (x and 3)] + .5f) / 16f - .5f) / 255f
                        for (channel in 0..2) input[channel] += noise
                    }
                    fun channel(i: Int) = (input[i].coerceIn(0f, 1f) * 255).roundToInt()
                    pixels[index] = (pixel and -0x1000000) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
                }
            }
            bitmap.setPixels(pixels, 0, bitmap.width, 0, top, bitmap.width, rows)
        }
        checkActive()
    }
}
