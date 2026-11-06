package com.lumixfilmmirror.media.frame

import android.graphics.*
import com.lumixfilmmirror.media.SourceImage
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlin.math.min

/** Seven segment geometry adapted from SpektraLab overscan.cpp (GPL-3.0-or-later).
 * An export overlay, after film rendering; no RAW reconstruction or emulsion exposure is implied.
 */
internal object FrameDateBack {
    private val segments = listOf("abcdef", "bc", "abged", "abgcd", "fgbc", "afgcd", "afgedc", "abc", "abcdefg", "abcdfg")
    fun label(source: SourceImage): String {
        val date = runCatching { LocalDate.parse(source.metadata.capturedAt.take(10).replace(':','-')) }.getOrNull()
            ?: source.jpeg.lastModified().takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
            ?: return ""
        return "'%02d %d %d".format(java.util.Locale.ROOT, date.year % 100, date.monthValue, date.dayOfMonth)
    }
    fun draw(canvas: Canvas, box: RectF, source: SourceImage) {
        val text=label(source);if(text.isEmpty())return
        val h=min(box.width(),box.height())*.027f
        val w=.52f*h;val th=.14f*h;val gap=.6f*th
        fun advance(c:Char)=when(c){ ' '->.45f*h; '\''->.35f*h;else->.8f*h }
        val width=text.sumOf { advance(it).toDouble() }.toFloat()-.28f*h
        val left=box.right-min(box.width(),box.height())*.055f-width
        val bottom=box.bottom-min(box.width(),box.height())*.055f
        val path=Path();var x=0f
        fun rect(rx:Float,ry:Float,rw:Float,rh:Float) {
            val points=floatArrayOf(rx,ry,rx+rw,ry,rx+rw,ry+rh,rx,ry+rh)
            for(i in 0..3){val px=left+points[2*i]+.12f*points[2*i+1];val py=bottom-points[2*i+1]
                if(i==0)path.moveTo(px,py)else path.lineTo(px,py)};path.close()
        }
        text.forEach { c ->
            if(c=='\'')rect(x,.72f*h,th,.28f*h)
            if(c in '0'..'9')segments[c-'0'].forEach { when(it){
                'a'->rect(x+gap,h-th,w-2*gap,th);'g'->rect(x+gap,.5f*h-.5f*th,w-2*gap,th)
                'd'->rect(x+gap,0f,w-2*gap,th);'f'->rect(x,.5f*h+.3f*th,th,.5f*h-.9f*th)
                'b'->rect(x+w-th,.5f*h+.3f*th,th,.5f*h-.9f*th)
                'e'->rect(x,.6f*th,th,.5f*h-.9f*th);'c'->rect(x+w-th,.6f*th,th,.5f*h-.9f*th)
            } }
            x+=advance(c)
        }
        canvas.save();canvas.clipRect(box)
        try {
            val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xBBEF511C.toInt();maskFilter=BlurMaskFilter((h*.19f).coerceAtLeast(.1f),BlurMaskFilter.Blur.NORMAL) }
            canvas.drawPath(path,p);p.maskFilter=null;p.color=0xFFF8AB5B.toInt();canvas.drawPath(path,p)
        } finally {canvas.restore()}
    }
}
