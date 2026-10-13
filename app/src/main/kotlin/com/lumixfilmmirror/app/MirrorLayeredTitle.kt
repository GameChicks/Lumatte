package com.lumixfilmmirror.app

import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat

/** Knock out a narrow gap at intersections so both real letterforms remain legible. */
@Composable
internal fun MirrorLayeredTitle(first: String, second: String, ink: Color, accent: Color) {
    val context = LocalContext.current
    val faces = remember(context) {
        ResourcesCompat.getFont(context, com.lumixfilmmirror.design.R.font.cormorant_garamond) to
            ResourcesCompat.getFont(context, com.lumixfilmmirror.design.R.font.instrument_serif_italic)
    }
    val density = LocalDensity.current
    Canvas(Modifier.fillMaxWidth().height(with(density) { 100.sp.toDp() })
        .semantics { contentDescription = "$first $second" }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val top = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = faces.first; textSize = 48.sp.toPx(); color = ink.toArgb() }
        val lower = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = faces.second; textSize = 54.sp.toPx(); color = accent.toArgb() }
        val inset = 32.sp.toPx()
        val fit = minOf(1f, (size.width - 8.sp.toPx()) / top.measureText(first),
            (size.width - inset - 8.sp.toPx()) / lower.measureText(second))
        top.textSize *= fit; lower.textSize *= fit
        val a = Rect(); val b = Rect()
        top.getTextBounds(first, 0, first.length, a); lower.getTextBounds(second, 0, second.length, b)
        val firstBaseline = 6.sp.toPx() - a.top
        val secondBaseline = firstBaseline + a.bottom - 5.sp.toPx() - b.top
        val x = inset - b.left
        drawContext.canvas.nativeCanvas.apply {
            drawText(first, 3.sp.toPx() - a.left, firstBaseline, top)
            val gap = Paint(lower).apply {
                style = Paint.Style.STROKE; strokeWidth = 3.sp.toPx()
                strokeJoin = Paint.Join.ROUND; xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            drawText(second, x, secondBaseline, gap)
            drawText(second, x, secondBaseline, lower)
        }
    }
}
