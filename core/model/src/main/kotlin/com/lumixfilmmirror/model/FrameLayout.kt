package com.lumixfilmmirror.model

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.round

enum class FramePreset(val title: String) {
    NONE("无边框"), PAPER("白色相纸"), BLACK("黑色画廊"), FILM("胶片暗盒"),
    TICKET("记忆票根"), INSTANT("拍立得"), DUSK("暮色"), MIST("雾面"),
    PALETTE("取色相框"), MUSEUM("典藏纸框"), SLIDE("幻灯片夹"),
}

enum class FrameRatio(val title: String, val landscape: Float?) {
    ORIGINAL("原比例", null), SQUARE("1:1", 1f), THREE_TWO("3:2", 1.5f),
    FOUR_THREE("4:3", 4f / 3f), SEVEN_SIX("7:6", 7f / 6f),
    FIVE_FOUR("5:4", 1.25f), FIFTEEN_NINE("15:9", 15f / 9f), TWO_ONE("2:1", 2f),
}

enum class FrameFont(val title: String) {
    SANS("清晰无衬线"), TYPEWRITER("Cutive Mono"), SCRIPT("Dancing Script"),
    SERIF("Playfair"), DISPLAY("Abril"), SPACED("Julius"), GREAT_VIBES("Great Vibes"),
}

data class FloatBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

data class FrameLayout(val canvas: PixelSize, val photo: FloatBox, val caption: FloatBox, val cartridge: FloatBox?)

/** Recovered Film Companion proportions. Percent affects the photo, never a hidden canvas cap. */
object FrameLayouts {
    fun plan(content: PixelSize, frame: FrameOptions): FrameLayout {
        val pw = content.width.toFloat()
        val ph = content.height.toFloat()
        if (frame.preset == FramePreset.NONE) {
            return FrameLayout(content, FloatBox(0f, 0f, pw, ph), FloatBox(0f, ph, pw, ph), null)
        }
        val short = min(pw, ph)
        val pad = short * maxOf(frame.borderFraction.coerceIn(.02f, .12f), when (frame.preset) {
            FramePreset.MUSEUM -> .07f
            FramePreset.SLIDE -> .12f
            else -> 0f
        })
        val film = frame.preset == FramePreset.FILM
        val header = when (frame.preset) {
            FramePreset.TICKET -> short * .23f
            FramePreset.FILM -> ph * .18f
            FramePreset.MUSEUM -> short * .105f
            FramePreset.SLIDE -> short * .13f
            else -> pad
        }
        val textVisible = frame.showPhotoMetadata || frame.signature.isNotBlank()
        val footer = short * when (frame.preset) {
            FramePreset.TICKET -> .4f
            FramePreset.INSTANT -> .23f
            FramePreset.FILM -> ph / short * .18f
            FramePreset.PALETTE -> .25f
            FramePreset.MUSEUM -> if (textVisible) .24f else .105f
            FramePreset.SLIDE -> if (textVisible) .21f else .13f
            else -> if (textVisible) .17f else frame.borderFraction.coerceIn(.02f, .12f)
        }
        val sidebar = if (film) ph * (if (frame.roll120) .38f else .74f) else 0f
        val tail = if (film) pw * .6f else 0f
        var width = pw + pad * 2 + sidebar + tail
        var height = ph + header + footer
        frame.ratio.landscape?.let {
            val ratio = if (frame.portrait ?: (pw < ph)) 1f / it else it
            if (width / height < ratio) width = height * ratio else height = width / ratio
        }
        val canvas = PixelSize(ceil(width.toDouble()).toInt(), ceil(height.toDouble()).toInt())
        // Any ratio expansion adds margins. Photo content retains exactly its requested dimensions.
        val x = round(pad + sidebar + (canvas.width - pad * 2 - sidebar - tail - pw) / 2)
        val y = round(header + (canvas.height - header - footer - ph) / 2)
        val photo = FloatBox(x, y, x + pw, y + ph)
        val unit = min(canvas.width, canvas.height) / 1000f
        val caption = if (film) FloatBox(photo.right + unit * 18, photo.top + ph * .46f,
            photo.right + tail - unit * 18, photo.bottom - unit * 12)
        else FloatBox(pad + unit * 12, photo.bottom + unit * 10, canvas.width - pad - unit * 12,
            canvas.height - pad * .35f - unit * 10 - if (frame.preset == FramePreset.TICKET)
                min(canvas.width, canvas.height) * .025f + canvas.width * .014f else 0f)
        val cartridge = if (film) {
            val top = maxOf(0f, y - ph * .16f)
            val bottom = photo.bottom + ph * .14f
            val scale = (bottom - top) / 430f
            val cw = if (frame.roll120) 120f else 240f
            val bodyRight = if (frame.roll120) 112f else 235f
            val right = x + (cw - bodyRight) * scale
            FloatBox(right - cw * scale, top, right, bottom)
        } else null
        return FrameLayout(canvas, photo, caption, cartridge)
    }

    /** Scale the complete export composition, rather than recomputing small-photo margins. */
    fun preview(layout: FrameLayout, longEdge: Int): FrameLayout {
        require(longEdge > 0)
        val scale = minOf(1.0, longEdge.toDouble() / maxOf(layout.canvas.width, layout.canvas.height))
        val canvas = PixelSize(maxOf(1, (layout.canvas.width * scale).toInt()),
            maxOf(1, (layout.canvas.height * scale).toInt()))
        val sx = canvas.width.toFloat() / layout.canvas.width
        val sy = canvas.height.toFloat() / layout.canvas.height
        fun FloatBox.scaled() = FloatBox(left * sx, top * sy, right * sx, bottom * sy)
        return FrameLayout(canvas, layout.photo.scaled(), layout.caption.scaled(), layout.cartridge?.scaled())
    }
}
