package com.lumixfilmmirror.media.frame

import android.graphics.*
import kotlin.math.max
import kotlin.math.roundToInt

/** Recovered bounded background blur: no full-resolution convolution or UI dependency. */
internal object FrameBackground {
    fun fill(canvas: Canvas, source: Bitmap, width: Int, height: Int, checkActive: () -> Unit) {
        val factor = minOf(1f, 160f / max(source.width, source.height))
        val w = max(1, (source.width * factor).roundToInt())
        val h = max(1, (source.height * factor).roundToInt())
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        var input = IntArray(w * h)
        var output = IntArray(w * h)
        try { scaled.getPixels(input, 0, w, 0, 0, w, h) }
        finally { if (scaled !== source) scaled.recycle() }
        val radius = 12
        val count = radius * 2 + 1
        repeat(3) {
            for (horizontal in listOf(true, false)) {
                checkActive()
                val lines = if (horizontal) h else w
                val length = if (horizontal) w else h
                fun index(line: Int, position: Int) = if (horizontal) line * w + position else position * w + line
                for (line in 0 until lines) {
                    var r = 0; var g = 0; var blue = 0
                    for (k in -radius..radius) {
                        val c = input[index(line, k.coerceIn(0, length - 1))]
                        r += (c ushr 16) and 255; g += (c ushr 8) and 255; blue += c and 255
                    }
                    for (position in 0 until length) {
                        output[index(line, position)] = (-0x1000000) or ((r / count) shl 16) or ((g / count) shl 8) or (blue / count)
                        val old = input[index(line, (position - radius).coerceIn(0, length - 1))]
                        val next = input[index(line, (position + radius + 1).coerceIn(0, length - 1))]
                        r += ((next ushr 16) and 255) - ((old ushr 16) and 255)
                        g += ((next ushr 8) and 255) - ((old ushr 8) and 255)
                        blue += (next and 255) - (old and 255)
                    }
                }
                val swap = input; input = output; output = swap
            }
        }
        val background = Bitmap.createBitmap(input, w, h, Bitmap.Config.ARGB_8888)
        try {
            checkActive()
            val scale = max(width.toFloat() / w, height.toFloat() / h)
            val box = RectF((width - w * scale) / 2, (height - h * scale) / 2,
                (width + w * scale) / 2, (height + h * scale) / 2)
            canvas.drawBitmap(background, null, box, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } finally { background.recycle() }
    }
}
