package com.lumixfilmmirror.app

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import kotlin.math.sin
import kotlin.math.cos
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.design.MirrorFonts

internal val MirrorEase = CubicBezierEasing(.22f, .8f, .22f, 1f)
internal val LocalMirrorReducedMotion = compositionLocalOf { false }
internal val LocalMirrorDotArt = compositionLocalOf { false }
internal val LocalMirrorHaptics = compositionLocalOf { true }
internal val LocalMirrorAutomaticFilmDetails = compositionLocalOf { true }

@Composable
internal fun MirrorChoice(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, label: @Composable () -> Unit) {
    val tones = LocalFilmMaterials.current
    val shape = RoundedCornerShape(20.dp)
    val colour by androidx.compose.animation.animateColorAsState(
        if (selected) tones.ink else tones.paperHighlight, tween(if (LocalMirrorReducedMotion.current) 0 else 300), label = "choice-fill")
    Box(modifier.heightIn(min = 44.dp).clip(shape).background(colour)
        .border(1.dp, if (selected) tones.ink else tones.rim.copy(.55f), shape)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 15.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides (if (selected) tones.paper else tones.ink).copy(if (enabled) 1f else .45f)) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { label() }
        }
    }
}

@Composable
internal fun MirrorButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(28.dp), destructive: Boolean = false,
    content: @Composable RowScope.() -> Unit) {
    val tones = LocalFilmMaterials.current
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val buttonInk = if (destructive) Color(0xFF845960) else tones.ink
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) .975f else 1f,
        tween(if (LocalMirrorReducedMotion.current) 0 else 120), label = "button-pressure")
    Row(modifier.heightIn(min = 52.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .4f }
        .clip(shape).background(Brush.verticalGradient(listOf(buttonInk, buttonInk.copy(.90f))))
        .clickable(enabled = enabled, interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
        .padding(horizontal = 22.dp, vertical = 12.dp), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalContentColor provides if (destructive) Color.White else tones.paper) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp)) { content() }
        }
    }
}

@Composable
internal fun systemMotionReduced(): Boolean {
    val context = LocalContext.current
    return Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}

internal enum class DotGlyph(val rows: List<String>) {
    SETTINGS(listOf("0010100", "0111110", "1100011", "0101010", "1100011", "0111110", "0010100")),
    LEFT(listOf("00100", "01000", "11111", "01000", "00100")),
    RIGHT(listOf("00100", "00010", "11111", "00010", "00100")),
    PLUS(listOf("00100", "00100", "11111", "00100", "00100")),
    CLOSE(listOf("10001", "01010", "00100", "01010", "10001")),
    SEARCH(listOf("0111000", "1000100", "1000100", "1000100", "0111100", "0000010", "0000001")),
    STAR(listOf("0001000", "0001000", "1111111", "0111110", "0011100", "0100010", "1000001")),
    CAMERA(listOf("0011100", "1111111", "1000001", "1011101", "1010101", "1011101", "1111111")),
    BLUETOOTH(listOf("0001000", "0001100", "0101010", "0011100", "0001000", "0011100", "0101010", "0001100", "0001000")),
    ALBUM(listOf("1111111", "1000001", "1000101", "1000001", "1011001", "1101101", "1111111")),
    FILM(listOf("1111111", "1010101", "1000001", "1010101", "1000001", "1010101", "1111111")),
    LUT(listOf("0001000", "0110110", "1101011", "1001001", "1101011", "0110110", "0001000")),
    FRAME(listOf("1111111", "1000001", "1011101", "1010101", "1011101", "1000001", "1111111")),
}

@Composable
internal fun DotIcon(glyph: DotGlyph, modifier: Modifier = Modifier, tint: Color = LocalFilmMaterials.current.ink, filled: Boolean = false) {
    val dotted = LocalMirrorDotArt.current
    Canvas(modifier.size(24.dp)) {
        if (!dotted) {
            val w = size.width; val h = size.height
            val stroke = Stroke(size.minDimension * .075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, Offset(w*x1,h*y1), Offset(w*x2,h*y2), stroke.width, StrokeCap.Round)
            when (glyph) {
                DotGlyph.SETTINGS -> {
                    drawCircle(tint, w*.27f, Offset(w*.5f,h*.5f), style = stroke)
                    drawCircle(tint, w*.09f, Offset(w*.5f,h*.5f), style = stroke)
                    repeat(8) { i ->
                        val a = i * Math.PI / 4
                        line(.5f + cos(a).toFloat()*.27f, .5f + sin(a).toFloat()*.27f,
                            .5f + cos(a).toFloat()*.41f, .5f + sin(a).toFloat()*.41f)
                    }
                }
                DotGlyph.LEFT, DotGlyph.RIGHT -> {
                    val right = glyph == DotGlyph.RIGHT
                    val end = if (right) .76f else .24f; val from = if (right) .24f else .76f
                    line(from,.5f,end,.5f); line(.5f,.24f,end,.5f); line(.5f,.76f,end,.5f)
                }
                DotGlyph.PLUS -> { line(.2f,.5f,.8f,.5f); line(.5f,.2f,.5f,.8f) }
                DotGlyph.CLOSE -> { line(.25f,.25f,.75f,.75f); line(.75f,.25f,.25f,.75f) }
                DotGlyph.SEARCH -> {
                    drawCircle(tint, w*.27f, Offset(w*.41f,h*.41f), style = stroke)
                    line(.61f,.61f,.85f,.85f)
                }
                DotGlyph.STAR -> {
                    val path = Path()
                    repeat(10) { i ->
                        val angle = (-Math.PI/2 + i*Math.PI/5).toFloat()
                        val radius = if (i%2==0) .43f else .19f
                        val x = (.5f+cos(angle)*radius)*w; val y=(.48f+sin(angle)*radius)*h
                        if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
                    }
                    path.close(); if(filled) drawPath(path,tint) else drawPath(path,tint,style=stroke)
                }
                DotGlyph.CAMERA -> {
                    val path=Path().apply { moveTo(w*.12f,h*.32f);lineTo(w*.32f,h*.32f);lineTo(w*.39f,h*.21f);lineTo(w*.65f,h*.21f);lineTo(w*.72f,h*.32f);lineTo(w*.88f,h*.32f);lineTo(w*.88f,h*.8f);lineTo(w*.12f,h*.8f);close() }
                    drawPath(path,tint,style=stroke); drawCircle(tint,w*.16f,Offset(w*.51f,h*.56f),style=stroke)
                }
                DotGlyph.BLUETOOTH -> {
                    line(.47f,.08f,.47f,.92f); line(.47f,.08f,.77f,.31f)
                    line(.77f,.31f,.23f,.74f); line(.23f,.26f,.77f,.69f); line(.77f,.69f,.47f,.92f)
                }
                DotGlyph.ALBUM -> {
                    drawRoundRect(tint,Offset(w*.12f,h*.13f),androidx.compose.ui.geometry.Size(w*.76f,h*.74f),CornerRadius(w*.08f),style=stroke)
                    drawCircle(tint,w*.05f,Offset(w*.67f,h*.34f))
                    val path=Path().apply { moveTo(w*.16f,h*.74f);lineTo(w*.39f,h*.49f);lineTo(w*.61f,h*.72f);lineTo(w*.72f,h*.6f);lineTo(w*.85f,h*.74f) }
                    drawPath(path,tint,style=stroke)
                }
                DotGlyph.FILM -> {
                    drawRoundRect(tint,Offset(w*.19f,h*.1f),androidx.compose.ui.geometry.Size(w*.62f,h*.8f),CornerRadius(w*.07f),style=stroke)
                    line(.35f,.12f,.35f,.88f);line(.65f,.12f,.65f,.88f)
                    repeat(4) { n -> line(.23f,.23f+n*.18f,.29f,.23f+n*.18f);line(.71f,.23f+n*.18f,.77f,.23f+n*.18f) }
                }
                DotGlyph.LUT -> {
                    val path=Path().apply { moveTo(w*.5f,h*.08f);lineTo(w*.85f,h*.28f);lineTo(w*.85f,h*.72f);lineTo(w*.5f,h*.92f);lineTo(w*.15f,h*.72f);lineTo(w*.15f,h*.28f);close() }
                    drawPath(path,tint,style=stroke);line(.15f,.28f,.5f,.49f);line(.85f,.28f,.5f,.49f);line(.5f,.49f,.5f,.92f)
                }
                DotGlyph.FRAME -> {
                    drawRoundRect(tint,Offset(w*.13f,h*.13f),androidx.compose.ui.geometry.Size(w*.74f,h*.74f),CornerRadius(w*.07f),style=stroke)
                    drawRect(tint,Offset(w*.28f,h*.28f),androidx.compose.ui.geometry.Size(w*.44f,h*.44f),style=stroke)
                }
            }
            return@Canvas
        }
        val step = size.minDimension / (glyph.rows.size + 1)
        val shiftY = if (glyph == DotGlyph.STAR) -step * .18f else 0f
        glyph.rows.forEachIndexed { y, row -> row.forEachIndexed { x, c -> if (c == '1')
            drawCircle(tint, step * .29f, Offset((x + 1) * step, (y + 1) * step + shiftY)) } }
    }
}

@Composable
internal fun RoundAction(label: String, glyph: DotGlyph, modifier: Modifier = Modifier,
    active: Boolean = false, glass: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tones = LocalFilmMaterials.current
    Box(modifier.size(52.dp).clip(CircleShape).background(if (active) tones.ink else tones.paperHighlight.copy(if (glass) .4f else 1f))
        .border(1.dp, tones.rim, CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label; if (glyph == DotGlyph.STAR) selected = active; if (!enabled) disabled() }, contentAlignment = Alignment.Center) {
        DotIcon(glyph, tint = (if (active) tones.paper else tones.ink).copy(alpha = if (enabled) 1f else .4f), filled = active)
    }
}

@Composable
internal fun PageHeading(kicker: String, title: String, onImport: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val pair = when(title) {
                "LUT" -> "Color" to "Library"
                "Film" -> "Film" to "Stories"
                "设置" -> "Personal" to "Settings"
                "水印" -> "Frame" to "Signature"
                else -> title to kicker.substringAfterLast(" / ").lowercase().replaceFirstChar { it.uppercase() }
            }
            MirrorLayeredTitle(pair.first, pair.second, LocalFilmMaterials.current.ink,
                LocalFilmMaterials.current.amber, compact = true)
            Text(kicker.replace("COLOUR", "COLOR").replace("Colour", "Color"),
                style = MaterialTheme.typography.labelSmall, color = LocalFilmMaterials.current.secondaryInk)
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
        MirrorButton(onClick = onImport) { Text("导入照片") }
    }
}

/** Capture page pixels once, then blur a guarded bottom strip in six masked bands.
 * Navigation is composed outside this capture and remains sharp and independently clickable. */
@Composable
internal fun ProgressiveBackdrop(modifier: Modifier = Modifier, enabled: Boolean,
    source: androidx.compose.ui.graphics.layer.GraphicsLayer = rememberGraphicsLayer(), content: @Composable () -> Unit) {
    val bands = listOf(rememberGraphicsLayer(), rememberGraphicsLayer(), rememberGraphicsLayer(),
        rememberGraphicsLayer(), rememberGraphicsLayer(), rememberGraphicsLayer())
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    SideEffect {
        bands.forEachIndexed { i, layer ->
            val radius = floatArrayOf(2f,5f,10f,18f,28f,40f)[i] * density
            layer.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
        }
    }
    Box(modifier.drawWithContent {
        if (!enabled) drawContent()
        else {
            source.record { this@drawWithContent.drawContent() }
            drawLayer(source)
            val height = minOf(128.dp.toPx(), size.height)
            val top = size.height - height
            val guard = minOf(80.dp.toPx(), top)
            bands.forEachIndexed { index, layer ->
                layer.record(size = androidx.compose.ui.unit.IntSize(size.width.toInt(), (height+guard).toInt())) {
                    withTransform({ translate(0f, -top + guard) }) { drawLayer(source) }
                }
                val native = drawContext.canvas
                native.saveLayer(androidx.compose.ui.geometry.Rect(0f, top, size.width, size.height), Paint())
                withTransform({ translate(0f, top-guard) }) { drawLayer(layer) }
                val start = top + height * index / bands.size
                val end = top + height * (index + 1) / bands.size
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = start, endY = end),
                    blendMode = BlendMode.DstIn)
                native.restore()
            }
        }
    }) { Box(Modifier.fillMaxSize().graphicsLayer()) { content() } }
}

/** Actual pixels behind the floating bar, with a blur guard outside its clipped bounds. */
@Composable
internal fun Modifier.frostedBackdrop(source: androidx.compose.ui.graphics.layer.GraphicsLayer, enabled: Boolean,
    sourceOrigin: State<Offset>? = null): Modifier {
    val glass = rememberGraphicsLayer()
    val position = remember { mutableStateOf(Offset.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    SideEffect { glass.renderEffect = if (enabled) BlurEffect(22f*density,22f*density,TileMode.Clamp) else null }
    return this.then(Modifier.onGloballyPositioned {
        position.value = if (sourceOrigin == null) it.positionInParent() else it.positionInRoot() - sourceOrigin.value
    }.drawWithContent {
        if (enabled && source.size.width > 0) {
            val guard = 48.dp.toPx()
            glass.record(size = androidx.compose.ui.unit.IntSize((size.width+guard*2).toInt(),(size.height+guard*2).toInt())) {
                withTransform({ translate(guard-position.value.x,guard-position.value.y) }) { drawLayer(source) }
            }
            withTransform({ translate(-guard,-guard) }) { drawLayer(glass) }
        }
        drawContent()
    })
}

private fun Modifier.onPlacedPosition(update: (Offset) -> Unit): Modifier = this.then(
    Modifier.onGloballyPositioned { update(it.positionInParent()) })
