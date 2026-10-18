package com.lumixfilmmirror.media.frame

import android.graphics.*
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.lumixfilmmirror.model.FrameFont
import com.lumixfilmmirror.model.FrameMood
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.min

internal object FramePalette {
    fun extract(bitmap: Bitmap): Map<FrameMood, Int> {
        val palette = Palette.from(bitmap).resizeBitmapArea(128 * 128).generate()
        return FrameMood.entries.associateWith { mood ->
            (when (mood) {
                FrameMood.VIBRANT -> palette.vibrantSwatch
                FrameMood.LIGHT_VIBRANT -> palette.lightVibrantSwatch
                FrameMood.DARK_VIBRANT -> palette.darkVibrantSwatch
                FrameMood.MUTED -> palette.mutedSwatch
                FrameMood.LIGHT_MUTED -> palette.lightMutedSwatch
                FrameMood.DARK_MUTED -> palette.darkMutedSwatch
            })?.rgb ?: palette.dominantSwatch?.rgb ?: Color.rgb(99, 103, 96)
        }
    }
    fun contrasting(color: Int) = if (ColorUtils.calculateLuminance(color) > .35) Color.rgb(20, 22, 21)
        else Color.rgb(250, 248, 238)
}

/** Recovered font metrics and line fitting. Shared by exported text and frame previews. */
internal class FrameTypography(private val assets: FrameAssets) {
    private val faces = mutableMapOf<FrameFont, Typeface>()
    @Synchronized private fun face(font: FrameFont): Typeface = faces.getOrPut(font) {
        assets.bundledFont(when (font) {
            FrameFont.SANS -> "film_sans_regular.otf"
            FrameFont.TYPEWRITER -> "print_typewriter.ttf"
            FrameFont.SCRIPT -> "print_script.ttf"
            FrameFont.SERIF -> "print_serif.ttf"
            FrameFont.DISPLAY -> "print_display.ttf"
            FrameFont.SPACED -> "print_spaced.ttf"
            FrameFont.GREAT_VIBES -> "print_great_vibes.ttf"
        })
    }

    fun text(canvas: Canvas, value: String, box: RectF, size: Float, font: FrameFont, color: Int,
        align: Paint.Align = Paint.Align.CENTER, spacing: Float = 0f, custom: String? = null) {
        if (value.isEmpty() || box.width() <= 0f || box.height() <= 0f || size <= 0f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = custom?.let(assets::font) ?: face(font)
            textSize = size; this.color = color; textAlign = Paint.Align.LEFT
        }
        val lines = value.take(240).split('\n').take(3)
        // Spacing operates on character clusters, so surrogate pairs and marks stay together.
        fun clusters(line: String): List<String> {
            val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(line) }
            val result = mutableListOf<String>()
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) { result += line.substring(start, end); start = end; end = iterator.next() }
            return result
        }
        val glyphs = lines.map(::clusters)
        paint.textSize = min(paint.textSize, box.height() / (lines.size * 1.35f))
        var gap = spacing
        fun width(index: Int) = if (gap == 0f) paint.measureText(lines[index])
            else glyphs[index].sumOf { paint.measureText(it).toDouble() }.toFloat() + maxOf(0, glyphs[index].size - 1) * gap
        val widest = lines.indices.maxOf(::width)
        if (widest > box.width()) { val factor = box.width() / widest; paint.textSize *= factor; gap *= factor }
        val lineHeight = paint.textSize * 1.35f
        val y = box.centerY() - (lines.size - 1) * lineHeight / 2 - (paint.ascent() + paint.descent()) / 2
        lines.forEachIndexed { index, line ->
            val w = width(index)
            var x = when (align) { Paint.Align.LEFT -> box.left; Paint.Align.RIGHT -> box.right - w; else -> box.centerX() - w / 2 }
            if (gap == 0f) canvas.drawText(line, x, y + index * lineHeight, paint)
            else glyphs[index].forEach { part ->
                canvas.drawText(part, x, y + index * lineHeight, paint); x += paint.measureText(part) + gap
            }
        }
    }
}
