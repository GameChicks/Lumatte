package com.lumixfilmmirror.app

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.design.MirrorFonts

internal val MirrorEase = CubicBezierEasing(.22f, .8f, .22f, 1f)
internal val LocalMirrorReducedMotion = compositionLocalOf { false }

@Composable
internal fun systemMotionReduced(): Boolean {
    val context = LocalContext.current
    return Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}

internal enum class DotGlyph(val rows: List<String>) {
    LEFT(listOf("00100", "01000", "11111", "01000", "00100")),
    RIGHT(listOf("00100", "00010", "11111", "00010", "00100")),
    PLUS(listOf("00100", "00100", "11111", "00100", "00100")),
    CLOSE(listOf("10001", "01010", "00100", "01010", "10001")),
    STAR(listOf("0001000", "0001000", "1111111", "0111110", "0011100", "0100010", "1000001")),
    CAMERA(listOf("0011100", "1111111", "1000001", "1011101", "1010101", "1011101", "1111111")),
    FILM(listOf("1111111", "1010101", "1000001", "1010101", "1000001", "1010101", "1111111")),
    LUT(listOf("0001000", "0110110", "1101011", "1001001", "1101011", "0110110", "0001000")),
    FRAME(listOf("1111111", "1000001", "1011101", "1010101", "1011101", "1000001", "1111111")),
}

@Composable
internal fun DotIcon(glyph: DotGlyph, modifier: Modifier = Modifier, tint: Color = LocalFilmMaterials.current.ink) {
    Canvas(modifier.size(24.dp)) {
        val step = size.minDimension / (glyph.rows.size + 1)
        val shiftY = if (glyph == DotGlyph.STAR) -step * .18f else 0f
        glyph.rows.forEachIndexed { y, row -> row.forEachIndexed { x, c -> if (c == '1')
            drawCircle(tint, step * .29f, Offset((x + 1) * step, (y + 1) * step + shiftY)) } }
    }
}

@Composable
internal fun RoundAction(label: String, glyph: DotGlyph, modifier: Modifier = Modifier,
    active: Boolean = false, onClick: () -> Unit) {
    val tones = LocalFilmMaterials.current
    Box(modifier.size(52.dp).clip(CircleShape).background(if (active) tones.ink else tones.paperHighlight)
        .border(1.dp, tones.rim, CircleShape).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label; if (glyph == DotGlyph.STAR) selected = active }, contentAlignment = Alignment.Center) {
        DotIcon(glyph, tint = if (active) tones.paper else tones.ink)
    }
}

@Composable
internal fun PageHeading(kicker: String, title: String, onImport: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(kicker, style = MaterialTheme.typography.labelSmall, color = LocalFilmMaterials.current.secondaryInk)
            Text(title, fontFamily = MirrorFonts.Editorial, style = MaterialTheme.typography.displayMedium)
        }
        onImport?.let { RoundAction("导入照片", DotGlyph.PLUS, onClick = it) }
    }
}

@Composable
internal fun EmptyImport(title: String, caption: String, onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DotIcon(DotGlyph.FRAME, Modifier.size(52.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(caption, color = LocalFilmMaterials.current.secondaryInk)
        Button(onClick = onImport) { Text("导入照片") }
    }
}

/** Capture content once, then blur only the bottom strip in three masked bands.
 * Navigation is composed outside this capture and remains sharp and independently clickable. */
@Composable
internal fun ProgressiveBackdrop(modifier: Modifier = Modifier, enabled: Boolean, content: @Composable () -> Unit) {
    val source = rememberGraphicsLayer()
    val bands = listOf(rememberGraphicsLayer(), rememberGraphicsLayer(), rememberGraphicsLayer())
    Box(modifier.drawWithContent {
        if (!enabled) drawContent()
        else {
            source.record { this@drawWithContent.drawContent() }
            drawLayer(source)
            val height = minOf(144.dp.toPx(), size.height)
            val top = size.height - height
            bands.forEachIndexed { index, layer ->
                layer.renderEffect = BlurEffect((index + 1) * 5.dp.toPx(), (index + 1) * 5.dp.toPx(), TileMode.Clamp)
                layer.record(size = androidx.compose.ui.unit.IntSize(size.width.toInt(), height.toInt())) {
                    withTransform({ translate(0f, -top) }) { drawLayer(source) }
                }
                val native = drawContext.canvas
                native.saveLayer(androidx.compose.ui.geometry.Rect(0f, top, size.width, size.height), Paint())
                withTransform({ translate(0f, top) }) { drawLayer(layer) }
                val start = top + height * index / 3f
                val end = top + height * (index + 1) / 3f
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = start, endY = end),
                    blendMode = BlendMode.DstIn)
                native.restore()
            }
        }
    }) { content() }
}
