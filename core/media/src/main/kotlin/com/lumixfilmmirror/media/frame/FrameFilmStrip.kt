package com.lumixfilmmirror.media.frame

import android.graphics.*
import com.lumixfilmmirror.model.FrameFont
import java.io.Closeable
import java.util.Locale
import kotlin.math.min

/** Recovered cylinder label paths, 135/120 physical proportions, perforations and DX-like decoration. */
internal class FrameFilmStrip(assets: FrameAssets, private val fonts: FrameTypography, roll120: Boolean,
    stockId: String? = null, artLongEdge: Int = 2048) : Closeable {
    private val blankAsset = lazy { assets.bundled(if (roll120) "spool_120_blank_v1.png" else "cartridge_blank_v1.png") }
    private val asset by blankAsset
    private val stockAsset = assets.cartridge(stockId, artLongEdge)

    fun cartridge(canvas: Canvas, box: RectF, settings: FrameIdentity) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        stockAsset?.let { stock ->
            val row = IntArray(stock.width)
            var left = stock.width; var top = stock.height; var right = -1; var bottom = -1
            for (y in 0 until stock.height) {
                stock.getPixels(row, 0, stock.width, 0, y, stock.width, 1)
                row.forEachIndexed { x, pixel -> if (Color.alpha(pixel) >= 160) {
                    left = minOf(left,x); right = maxOf(right,x); top = minOf(top,y); bottom = maxOf(bottom,y)
                } }
            }
            require(right >= left && bottom >= top) { "暗盒素材没有可见图形" }
            val src = Rect(left,top,right+1,bottom+1)
            val scale = min(box.width()/src.width(), box.height()/src.height())
            val height = src.height()*scale
            canvas.drawBitmap(stock, src, RectF(box.right-src.width()*scale,
                box.centerY()-height/2,box.right,box.centerY()+height/2), paint)
            return
        }
        if (settings.roll120) { spool(canvas, box, settings, paint); return }
        val scale = min(box.width() / 240, box.height() / 430)
        canvas.save(); canvas.translate(box.centerX() - 120 * scale, box.centerY() - 215 * scale); canvas.scale(scale, scale)
        val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xB0000000.toInt(); maskFilter = BlurMaskFilter(1.5f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(16f, 72f, 245f, 411f, 3f, 3f, shade)
        val src = Rect(197, 102, 890, 1344)
        val dest = RectF(0f, 0f, 240f, 430f)
        canvas.drawBitmap(asset, src, dest, paint)
        val label = Path().apply {
            moveTo(6f, 71f); cubicTo(24f, 75f, 54f, 80f, 83f, 81f)
            lineTo(83f, 409f); cubicTo(55f, 408f, 23f, 402f, 6f, 393f)
            cubicTo(5.5f, 282f, 5.5f, 182f, 6f, 71f); close()
        }
        paint.color = settings.color; canvas.drawPath(label, paint)
        texturePanel(canvas, src, dest, label, settings.color, paint)
        canvas.save(); canvas.clipPath(label)
        paint.shader = LinearGradient(6f, 0f, 83f, 0f,
            intArrayOf(0x70000000, 0x00FFFFFF, 0x20FFFFFF, 0x08000000), floatArrayOf(0f, .3f, .55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(label, paint); paint.shader = null; canvas.restore()
        vertical(canvas, settings.notes, 49f, 92f, 294f, 12f, FramePalette.contrasting(settings.color))
        vertical(canvas, if (settings.blackWhite) "Black & White Film" else "Color Negative Film", 106f, 97f, 285f, 12.5f, 0xFFEAE8DD.toInt())
        vertical(canvas, settings.film, 150f, 95f, 289f, 31f, 0xFFF0EEE4.toInt())
        vertical(canvas, settings.brand.uppercase(Locale.ROOT), 208f, 96f, 282f, 18f, 0xFFF0EEE4.toInt())
        canvas.restore()
    }

    private fun spool(canvas: Canvas, box: RectF, settings: FrameIdentity, paint: Paint) {
        val scale = min(box.width() / 120, box.height() / 430)
        canvas.save(); canvas.translate(box.centerX() - 60 * scale, box.centerY() - 215 * scale); canvas.scale(scale, scale)
        val src = Rect(321, 40, 703, 1490)
        val dest = RectF(3.36f, 0f, 116.64f, 430f)
        canvas.drawBitmap(asset, src, dest, paint)
        paint.color = settings.color; canvas.drawRect(9f, 141f, 111f, 295f, paint)
        texturePanel(canvas, src, dest, Path().apply { addRect(RectF(9f, 141f, 111f, 295f), Path.Direction.CW) }, settings.color, paint)
        val ink = FramePalette.contrasting(settings.color)
        vertical(canvas, settings.film, 76f, 151f, 134f, 24f, ink)
        vertical(canvas, settings.brand.uppercase(Locale.ROOT), 37f, 151f, 133f, 13f, ink)
        fonts.text(canvas, "120", RectF(12f, 24f, 108f, 42f), 12f, FrameFont.SPACED, Color.WHITE)
        fonts.text(canvas, "120", RectF(12f, 392f, 108f, 408f), 12f, FrameFont.SPACED, Color.WHITE)
        fonts.text(canvas, if (settings.blackWhite) "Black & White Film" else "Color Negative Film",
            RectF(10f, 320f, 110f, 335f), 8f, FrameFont.SPACED, 0xFFEAE8DD.toInt())
        fonts.text(canvas, settings.notes, RectF(10f, 349f, 110f, 365f), 8f, FrameFont.SPACED, 0xFFEAE8DD.toInt())
        canvas.restore()
    }

    private fun texturePanel(canvas: Canvas, src: Rect, dest: RectF, panel: Path, color: Int, paint: Paint) {
        fun channel(value: Int) = value / 255f * .7f / 3
        val r = Color.red(color); val g = Color.green(color); val blue = Color.blue(color)
        paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            channel(r), channel(r), channel(r), 0f, r * .65f,
            channel(g), channel(g), channel(g), 0f, g * .65f,
            channel(blue), channel(blue), channel(blue), 0f, blue * .65f,
            0f, 0f, 0f, 1f, 0f)))
        canvas.save(); canvas.clipPath(panel); canvas.drawBitmap(asset, src, dest, paint); canvas.restore()
        paint.colorFilter = null
    }

    private fun vertical(canvas: Canvas, text: String, x: Float, y: Float, length: Float, size: Float, color: Int) {
        canvas.save(); canvas.translate(x, y); canvas.rotate(90f)
        fonts.text(canvas, text, RectF(0f, -size * .6f, length, size * .6f), size, FrameFont.SPACED, color, Paint.Align.LEFT)
        canvas.restore()
    }

    fun strip(canvas: Canvas, box: RectF, frameWidth: Float, number: Int, settings: FrameIdentity,
        transparent: Boolean = false) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF060606.toInt() }
        val tailStart = box.left + frameWidth
        val tailWidth = box.right-tailStart
        val h = box.height()
        val silhouette = Path().apply {
            moveTo(box.left,box.top)
            lineTo(tailStart+tailWidth*.22f,box.top)
            cubicTo(tailStart+tailWidth*.36f,box.top,tailStart+tailWidth*.26f,box.top+h*.36f,
                tailStart+tailWidth*.50f,box.top+h*.36f)
            lineTo(box.right-h*.06f,box.top+h*.36f)
            quadTo(box.right,box.top+h*.36f,box.right,box.top+h*.43f)
            lineTo(box.right-h*.012f,box.top+h*.61f)
            lineTo(box.right-h*.004f,box.top+h*.78f)
            lineTo(box.right-h*.018f,box.bottom-h*.05f)
            quadTo(box.right-h*.025f,box.bottom,box.right-h*.08f,box.bottom)
            lineTo(box.left,box.bottom); close()
        }
        canvas.drawPath(silhouette, paint)
        canvas.save(); canvas.clipPath(silhouette)
        val rail = box.height() * (.14f / 1.28f)
        // Recovered method call mentions frameWidth/tailStart but its body predates them.
        // Keep pitch tied to the photo width, with uninterrupted perforations across the tail.
        val pitch = frameWidth / 8
        if ((!settings.roll120 || settings.stockId != null) && pitch > 0f) {
            paint.color = Color.WHITE
            if (transparent) paint.blendMode = BlendMode.CLEAR
            var x = box.left + pitch * .18f
            while (x + pitch * .52f <= box.right) {
                for (y in listOf(box.top + rail * .34f, box.bottom - rail * .69f)) {
                    canvas.drawRoundRect(x, y, x + pitch * .52f, y + rail * .42f, pitch * .12f, pitch * .12f, paint)
                }
                x += pitch
            }
            paint.blendMode = BlendMode.SRC_OVER
        }
        val ink = if (settings.blackWhite) Color.rgb(235, 234, 223) else Color.rgb(237, 144, 57)
        val label = number.toString()
        val x = box.left
        fonts.text(canvas, label, RectF(x + frameWidth * .003f, box.top, x + frameWidth * .18f, box.top + rail * .28f),
            rail * .26f, FrameFont.TYPEWRITER, ink, Paint.Align.LEFT)
        fonts.text(canvas, settings.film.uppercase(Locale.ROOT), RectF(x + frameWidth * .22f, box.top,
            x + frameWidth - frameWidth * .005f, box.top + rail * .28f), rail * .26f, FrameFont.TYPEWRITER, ink, Paint.Align.LEFT)
        fonts.text(canvas, label, RectF(x + frameWidth * .34f, box.bottom - rail * .27f, x + frameWidth * .45f, box.bottom),
            rail * .24f, FrameFont.TYPEWRITER, ink)
        fonts.text(canvas, label + "A", RectF(x + frameWidth * .82f, box.bottom - rail * .27f, x + frameWidth, box.bottom),
            rail * .24f, FrameFont.TYPEWRITER, ink)
        paint.color = ink
        barcode(canvas, RectF(x + frameWidth * .01f, box.bottom - rail * .24f, x + frameWidth * .29f, box.bottom - rail * .035f), paint, number)
        barcode(canvas, RectF(x + frameWidth * .51f, box.bottom - rail * .24f, x + frameWidth * .79f, box.bottom - rail * .035f), paint, number + 7)
        canvas.restore()
    }

    private fun barcode(canvas: Canvas, box: RectF, paint: Paint, seed: Int) {
        val unit = box.width() / 36
        var x = box.left; var i = 0
        while (x < box.right && unit > 0f) {
            val width = unit * if ((i + seed) % 5 == 0) 2 else 1
            canvas.drawRect(x, box.top + if ((i + seed) % 4 == 0) box.height() * .3f else 0f, min(x + width, box.right), box.bottom, paint)
            x += width + unit; i++
        }
    }

    override fun close() { if (blankAsset.isInitialized()) blankAsset.value.recycle(); stockAsset?.recycle() }
}
