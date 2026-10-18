package com.lumixfilmmirror.media.frame

import android.content.Context
import android.graphics.*
import com.lumixfilmmirror.media.FilmCategory
import com.lumixfilmmirror.media.FilmPreset
import com.lumixfilmmirror.media.SourceImage
import com.lumixfilmmirror.model.*
import kotlin.math.max
import kotlin.math.min
import java.util.Locale

internal data class FrameIdentity(val camera: String, val brand: String, val film: String,
    val stockId: String?, val blackWhite: Boolean, val notes: String, val color: Int, val roll120: Boolean,
    val exposureMetadata: String)

/** Backend output composition, adapted from the user's recovered Film Companion renderer. */
internal class FrameRenderer(context: Context) {
    val assets = FrameAssets(context)
    private val fonts = FrameTypography(assets)

    /** NONE returns the borrowed input. Other presets return a new owned bitmap; input is never recycled. */
    fun render(photo: Bitmap, source: SourceImage, options: FrameOptions, layout: FrameLayout,
        stock: FilmPreset? = null, frameNumber: Int = 1, transparentBackground: Boolean = false,
        checkActive: () -> Unit = {}): Bitmap {
        require(frameNumber > 0)
        if (options.preset == FramePreset.NONE) return photo
        checkActive()
        val pixels = Bitmap.createBitmap(layout.canvas.width, layout.canvas.height, Bitmap.Config.ARGB_8888)
        // Only FILM and TICKET have silhouettes that read as cut-outs on a transparent canvas.
        val transparent = transparentBackground && options.preset in setOf(FramePreset.FILM, FramePreset.TICKET)
        try {
            val canvas = Canvas(pixels)
            // Opaque exports keep the full canvas filled; PNG cut-outs leave the margins empty.
            val colors = if (options.fixedColorArgb == null && options.preset in setOf(FramePreset.PALETTE, FramePreset.FILM))
                FramePalette.extract(photo) else emptyMap()
            val accent = (options.fixedColorArgb ?: colors[options.mood] ?: Color.rgb(99, 103, 96)) or (-0x1000000)
            val identity = FrameIdentity(source.metadata.frameCameraName.takeIf { options.showCamera }.orEmpty(),
                options.filmBrand.ifBlank { stock?.name?.substringBefore(' ') ?: "LUMIX" },
                options.filmName.ifBlank { stock?.name?.substringAfter(' ') ?: "FILM MIRROR" }, stock?.id,
                options.filmBlackWhite ?: (stock?.category == FilmCategory.MONOCHROME), options.filmNotes, accent, options.roll120,
                source.metadata.frameDetail(options))
            val background = when (options.preset) {
                FramePreset.BLACK, FramePreset.FILM -> Color.BLACK
                FramePreset.DUSK -> 0xFF3C3E40.toInt()
                FramePreset.MIST -> 0xFFCCCBC6.toInt()
                FramePreset.PALETTE -> accent
                else -> 0xFFFAF8F0.toInt()
            }
            if (!transparent) canvas.drawColor(background)
            val ink = FramePalette.contrasting(background)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val unit = min(layout.canvas.width, layout.canvas.height) / 1000f
            val imageBox = layout.photo.rect()
            if (options.preset in setOf(FramePreset.MUSEUM, FramePreset.SLIDE)) {
                val paper = assets.bundled("print_cotton_paper.png")
                try {
                    val shader = BitmapShader(paper, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                    shader.setLocalMatrix(Matrix().apply { setScale(unit * .75f, unit * .75f) })
                    paint.shader = shader; paint.alpha = 180
                    canvas.drawRect(0f, 0f, pixels.width.toFloat(), pixels.height.toFloat(), paint)
                    paint.shader = null; paint.alpha = 255
                } finally { paper.recycle() }
                val aperture = RectF(imageBox).apply { inset(-unit * 3, -unit * 3) }
                paint.color = 0xFFC5BFAF.toInt(); canvas.drawRect(aperture, paint)
                paint.color = Color.WHITE; paint.strokeWidth = unit * 1.4f
                canvas.drawLine(aperture.left, aperture.bottom, aperture.right, aperture.bottom, paint)
                canvas.drawLine(aperture.right, aperture.top, aperture.right, aperture.bottom, paint)
                if (options.preset == FramePreset.SLIDE) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = unit * 1.8f; paint.color = 0xFFB9B4A8.toInt()
                    canvas.drawRoundRect(RectF(unit * 9, unit * 9, pixels.width - unit * 9, pixels.height - unit * 9),
                        unit * 10, unit * 10, paint)
                    paint.style = Paint.Style.FILL; paint.color = 0xFFA59B88.toInt()
                    for (x in listOf(unit * 23, pixels.width - unit * 23)) canvas.drawCircle(x, unit * 23, unit * 2, paint)
                }
            }
            checkActive()
            if (options.preset == FramePreset.DUSK || options.preset == FramePreset.MIST) {
                FrameBackground.fill(canvas, photo, pixels.width, pixels.height, checkActive)
                paint.color = if (options.preset == FramePreset.DUSK) 0xA3181B1D.toInt() else 0x99F1F0EB.toInt()
                canvas.drawRect(0f, 0f, pixels.width.toFloat(), pixels.height.toFloat(), paint)
                if (options.preset == FramePreset.MIST) {
                    paint.color = Color.WHITE
                    val rim = max(1.5f, unit * 1.5f)
                    canvas.drawRect(RectF(imageBox).apply { inset(-rim, -rim) }, paint)
                }
            }
            if (options.preset == FramePreset.TICKET) {
                val edge = min(pixels.width, pixels.height) * .025f
                val radius = pixels.width * .014f
                if (transparent) {
                    // The paper silhouette floats on transparency; notches and punch hole are cut out.
                    val paper = RectF(edge, edge, pixels.width - edge, pixels.height - edge)
                    paint.color = 0xFFFAF8F0.toInt()
                    canvas.drawRect(paper, paint)
                    // Outer rim is painted before the cuts so the notch mouths stay open.
                    val rimWidth = max(1f, edge * .18f)
                    val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0xFF777A79.toInt(); style = Paint.Style.STROKE; strokeWidth = rimWidth
                    }
                    canvas.save(); canvas.clipRect(paper)
                    canvas.drawRect(RectF(paper.left + rimWidth / 2, paper.top + rimWidth / 2,
                        paper.right - rimWidth / 2, paper.bottom - rimWidth / 2), rim)
                    canvas.restore()
                    val holeRadius = radius * .8f
                    val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.CLEAR }
                    var x = edge + radius
                    while (x < pixels.width - edge) {
                        canvas.drawCircle(x, edge, radius, cut)
                        canvas.drawCircle(x, pixels.height - edge, radius, cut)
                        x += radius * 2.65f
                    }
                    canvas.drawCircle(pixels.width / 2f, imageBox.top * .36f, holeRadius, cut)
                    // Rim the open cut-outs afterwards so the silhouette stays legible on light backgrounds.
                    canvas.save(); canvas.clipRect(paper)
                    var nx = edge + radius
                    while (nx < pixels.width - edge) {
                        canvas.drawCircle(nx, edge, radius, rim)
                        canvas.drawCircle(nx, pixels.height - edge, radius, rim)
                        nx += radius * 2.65f
                    }
                    canvas.drawCircle(pixels.width / 2f, imageBox.top * .36f, holeRadius, rim)
                    canvas.restore()
                } else {
                    paint.color = 0xFF777A79.toInt(); canvas.drawRect(0f, 0f, pixels.width.toFloat(), pixels.height.toFloat(), paint)
                    paint.color = 0xFFFAF8F0.toInt(); canvas.drawRect(edge, edge, pixels.width - edge, pixels.height - edge, paint)
                    paint.color = 0xFF777A79.toInt()
                    var x = edge + radius
                    while (x < pixels.width - edge) {
                        canvas.drawCircle(x, edge, radius, paint); canvas.drawCircle(x, pixels.height - edge, radius, paint)
                        x += radius * 2.65f
                    }
                    canvas.drawCircle(pixels.width / 2f, imageBox.top * .36f, radius * .8f, paint)
                }
            }
            if (options.preset == FramePreset.INSTANT) {
                paint.color = 0xFFCFC9BB.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = unit
                canvas.drawRect(unit * 7, unit * 7, pixels.width - unit * 7, pixels.height - unit * 7, paint)
                paint.style = Paint.Style.FILL; paint.color = 0x16A99F83
                for (i in 0..42) {
                    val y = (i * 71 % 997) / 1000f * pixels.height
                    canvas.drawLine(unit * 5, y, pixels.width - unit * 5, y + unit * 1.3f, paint)
                }
            }
            checkActive()
            if (options.preset == FramePreset.FILM) {
                FrameFilmStrip(assets, fonts, identity.roll120).use { painter ->
                    val rail = imageBox.height() * .14f
                    painter.strip(canvas, RectF(imageBox.left, imageBox.top - rail,
                        imageBox.right + imageBox.width() * .6f, imageBox.bottom + rail), imageBox.width(), frameNumber, identity)
                    canvas.drawBitmap(photo, null, imageBox, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    layout.cartridge?.let { painter.cartridge(canvas, it.rect(), identity) }
                }
            } else {
                // Material alpha/filter must never affect the photo pixels.
                canvas.drawBitmap(photo, null, imageBox, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
            drawCaption(canvas, options, layout, identity, ink, unit, checkActive)
            checkActive()
            return pixels
        } catch (error: Throwable) { pixels.recycle(); throw error }
    }

    private fun drawCaption(canvas: Canvas, options: FrameOptions, layout: FrameLayout,
        identity: FrameIdentity, ink: Int, unit: Float, checkActive: () -> Unit) {
        if (!options.showPhotoMetadata && options.signature.isBlank() && options.preset != FramePreset.PALETTE) return
        val ticket = options.preset == FramePreset.TICKET
        val font = options.titleFont ?: when (options.preset) {
            FramePreset.FILM, FramePreset.SLIDE -> FrameFont.TYPEWRITER
            FramePreset.TICKET -> FrameFont.GREAT_VIBES
            FramePreset.MUSEUM -> FrameFont.SERIF
            else -> FrameFont.SPACED
        }
        val textUnit = if (options.preset == FramePreset.FILM) layout.canvas.width / 1250f else unit
        data class Line(val text: String, val size: Float, val face: FrameFont, val spacing: Float = 0f, val custom: String? = null) {
            val height get() = size * 1.5f * text.take(240).split('\n').take(3).size
        }
        val lines = mutableListOf<Line>()
        if (options.showPhotoMetadata) {
            lines += Line(options.title ?: if (ticket) "Where the light lingers" else identity.camera,
                textUnit * if (ticket) 35f else 24f, font, if (font == FrameFont.SPACED) textUnit * 1.2f else 0f, options.titleFontKey)
            val subtitleFont = options.subtitleFont ?: if (ticket) FrameFont.SPACED else font
            lines += Line(options.subtitle ?: if (ticket) listOf(identity.camera, identity.brand + " " + identity.film).filter { it.isNotBlank() }.joinToString("  /  ")
                else identity.brand + " " + identity.film, textUnit * 18, subtitleFont, textUnit * .6f, options.subtitleFontKey)
            lines += Line(options.footer ?: if (ticket) "A moment, kept in light." else identity.exposureMetadata, textUnit * 14,
                options.footerFont ?: if (ticket) FrameFont.TYPEWRITER else FrameFont.SANS, custom = options.footerFontKey)
        }
        if (options.signature.isNotBlank()) lines += Line(options.signature, textUnit * 20, options.signatureFont, custom = options.signatureFontKey)
        val visible = lines.filter { it.text.isNotBlank() }
        val badge = if (options.preset == FramePreset.PALETTE) options.imageKey?.let(assets::bitmap)
            ?: stockBadge(identity) else null
        try {
            val badgeSize = if (badge != null) unit * 52 else 0f
            val badgeGap = if (badge != null && visible.isNotEmpty()) unit * 12 else 0f
            val gap = textUnit * 8
            val naturalHeight = badgeSize + badgeGap + visible.sumOf { it.height.toDouble() }.toFloat() + gap * max(0, visible.size - 1)
            val area = layout.caption.rect()
            val scale = if (naturalHeight > 0f) min(1f, max(0f, area.height()) / naturalHeight) else 1f
            var y = area.centerY() - naturalHeight * scale / 2
            if (badge != null && scale > 0f && area.width() > 0f) {
                checkActive()
                val size = badgeSize * scale
                val bounds = visibleBounds(badge, checkActive)
                val fit = min(min(size, area.width()) / bounds.width(), size / bounds.height())
                canvas.drawBitmap(badge, bounds, RectF(area.centerX() - bounds.width() * fit / 2, y,
                    area.centerX() + bounds.width() * fit / 2, y + bounds.height() * fit), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                y += size + badgeGap * scale
            }
            for (line in visible) {
                checkActive()
                val height = line.height * scale
                fonts.text(canvas, line.text, RectF(area.left, y, area.right, y + height), line.size * scale,
                    line.face, ink, spacing = line.spacing * scale, custom = line.custom)
                y += height + gap * scale
            }
        } finally { badge?.recycle() }
    }

    private fun stockBadge(identity: FrameIdentity): Bitmap? {
        if (identity.stockId == null) return null
        assets.cover(identity.stockId)?.let { return it }
        val badge = Bitmap.createBitmap(200, 250, Bitmap.Config.ARGB_8888)
        try {
            badge.eraseColor(0xFF272A27.toInt())
            val canvas = Canvas(badge)
            fonts.text(canvas, identity.brand.uppercase(Locale.ROOT), RectF(30f, 12.5f, 180f, 40f), 16f, FrameFont.SANS, Color.WHITE)
            fonts.text(canvas, identity.film, RectF(24f, 62.5f, 180f, 162.5f), 34f, FrameFont.SANS, Color.WHITE)
            return badge
        } catch (error: Throwable) { badge.recycle(); throw error }
    }

    private fun visibleBounds(bitmap: Bitmap, checkActive: () -> Unit): Rect {
        val full = Rect(0, 0, bitmap.width, bitmap.height)
        if (!bitmap.hasAlpha()) return full
        val row = IntArray(bitmap.width)
        var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
        for (y in 0 until bitmap.height) {
            if (y % 32 == 0) checkActive()
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            row.forEachIndexed { x, color -> if (Color.alpha(color) > 16) {
                left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
            } }
        }
        return if (right < left) full else Rect(left, top, right + 1, bottom + 1)
    }
}

internal fun FloatBox.rect() = RectF(left, top, right, bottom)
