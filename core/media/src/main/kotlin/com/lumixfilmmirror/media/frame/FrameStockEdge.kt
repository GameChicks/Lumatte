package com.lumixfilmmirror.media.frame

import android.content.Context
import android.graphics.*
import com.lumixfilmmirror.media.FilmPreset
import com.lumixfilmmirror.media.FilmCategory
import com.lumixfilmmirror.model.FrameFont
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.min

/** Stock records: Fotufilm/MUAStudio, CC BY-SA 4.0. DX equations: PrintFrame.swift, Apache-2.0.
 * Original sources, hashes and licences are retained with the bundled stock-edge catalogue.
 * Developed-film colours and lettering are presentation approximations; the photograph is untouched.
 */
internal class FrameStockEdge(context: Context, private val fonts: FrameTypography) {
    private val assets = context.applicationContext.assets
    private val records by lazy {
        val root = assets.open("frames/stock-edges/catalogue.json").bufferedReader().use { JSONObject(it.readText()) }
        require(root.getInt("schema") == 1 && root.getString("sourceCommit") == "e15b09815a83b0e00ba9381b65d31d253667a1b1")
        val list = root.getJSONArray("stocks")
        (0 until list.length()).associate { list.getJSONObject(it).let { entry -> entry.getString("stockId") to entry } }
    }

    fun draw(canvas: Canvas, photo: RectF, stock: FilmPreset, number: Int, transparent: Boolean,
        checkActive: () -> Unit) {
        // Retired project silver recipes remain usable, with an unlettered neutral rebate.
        val record = records[stock.id] ?: JSONObject().put("monochrome", stock.category == FilmCategory.MONOCHROME)
            .put("positive", stock.positive).put("motionPicture", stock.category == FilmCategory.CINEMA)
        val mono = record.getBoolean("monochrome")
        val positive = record.getBoolean("positive")
        val motion = record.getBoolean("motionPicture")
        if (!transparent) canvas.drawColor(0xFFF0EEE7.toInt())
        canvas.save()
        if (photo.width() < photo.height()) {
            canvas.translate(photo.left, photo.bottom)
            canvas.rotate(-90f)
        } else canvas.translate(photo.left, photo.top)
        try {
            val long = maxOf(photo.width(), photo.height())
            val short = min(photo.width(), photo.height())
            val mm = short / 24f
            val strip = RectF(-mm, -5.5f * mm, long + mm, short + 5.5f * mm)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = when { positive -> 0xFF060606.toInt(); mono -> 0xFF141514.toInt(); else -> 0xFF291B13.toInt() }
            }
            canvas.drawRect(strip, paint)
            canvas.clipRect(strip)
            val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                if (transparent) blendMode = BlendMode.CLEAR else color = 0xFFF0EEE7.toInt()
            }
            val pitch = if (motion) 4.74f else 4.75f
            val holeWidth = if (motion) 1.854f else 1.981f
            val corner = if (motion) .927f else .510f
            var x = -1f + (pitch - holeWidth) / 2
            while (x < long / mm + 1) {
                checkActive()
                for (y in listOf(-3.5f, 24f + .706f))
                    canvas.drawRoundRect(x * mm, y * mm, (x + holeWidth) * mm, (y + 2.794f) * mm,
                        corner * mm, corner * mm, cut)
                x += pitch
            }
            val edge = record.optJSONObject("edge") ?: return
            val marks = edge.getJSONArray("marks")
            val ink = when { positive -> 0xFFE6DCC6.toInt(); mono -> 0xFFCECBBD.toInt(); else -> 0xFFD9A665.toInt() }
            paint.color = ink
            val repeats = ceil((long / mm + 2) / 38).toInt().coerceAtLeast(1)
            for (part in 0 until repeats) {
                checkActive()
                val frame = ((number - 1L + part) % 38 + 1).toInt()
                val offset = part * 38f
                fun text(mark: JSONObject): String = when (val value = mark.getString("text")) {
                    "1" -> frame.toString(); "1A" -> "${frame}A"; else -> value
                }
                fun width(mark: JSONObject) = mark.getDouble("widthMM").toFloat() * text(mark).length / mark.getString("text").length
                for (i in 0 until marks.length()) {
                    val mark = marks.getJSONObject(i)
                    val mx = (mark.getDouble("xMM").toFloat() + offset - 1f) * mm
                    val bottom = (29.5f - mark.getDouble("yMM").toFloat()) * mm
                    val height = mark.getDouble("heightMM").toFloat() * mm
                    fonts.text(canvas, text(mark), RectF(mx, bottom - height, mx + width(mark) * mm, bottom),
                        height, FrameFont.TYPEWRITER, ink, Paint.Align.LEFT)
                }
                edge.optJSONObject("dx")?.let { dx ->
                    for ((start, half) in listOf(10.2f to false, 29.7f to true)) {
                        var after = start
                        for (i in 0 until marks.length()) {
                            val mark = marks.getJSONObject(i)
                            val mx = mark.getDouble("xMM").toFloat()
                            if (mark.getDouble("yMM") < 1.8 && mx < start)
                                after = maxOf(after, mx + width(mark) + .25f)
                        }
                        val first = dx.getInt("part1"); val second = dx.getInt("part2")
                        require(first in 1..127 && second in 0..15)
                        fun bits(value: Int, count: Int) = (count - 1 downTo 0).map { value shr it and 1 == 1 }
                        val payload = bits(first, 7) + bits(second, 4) + bits(frame, 6) + half
                        val data = listOf(true, false, true, false, true, false) + bits(first, 7) + false +
                            bits(second, 4) + bits(frame, 6) + listOf(half, false, payload.count { it } % 2 == 1, false, true, false, true)
                        val clock = "1111101010101010101010101010111".map { it == '1' }
                        for ((cells, track) in listOf(data to (.25f to .76f), clock to (1.30f to 1.80f))) {
                            cells.forEachIndexed { cell, exposed -> if (exposed) {
                                val left = (offset + after - 1 + cell * .381f) * mm
                                canvas.drawRect(left, (29.5f - track.second) * mm,
                                    left + .381f * mm, (29.5f - track.first) * mm, paint)
                            } }
                        }
                    }
                }
            }
        } finally { canvas.restore() }
    }
}
