package com.lumixfilmmirror.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.design.MirrorFonts
import kotlin.math.round

@Composable
internal fun MirrorControlSection(title: String, caption: String? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val tones = LocalFilmMaterials.current
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(tones.paperHighlight)
        .border(1.dp, tones.rim.copy(.45f), shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontFamily = MirrorFonts.ChineseEditorial, fontSize = 21.sp, lineHeight = 28.sp)
        caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tones.secondaryInk) }
        content()
    }
}

/** One gesture handler: vertical movement stays with the surrounding list. */
@Composable
internal fun MirrorParameterSlider(title: String, valueLabel: String, value: Float,
    range: ClosedFloatingPointRange<Float>, enabled: Boolean, steps: Int = 0, onValue: (Float) -> Unit) {
    val tones = LocalFilmMaterials.current
    val deliver by rememberUpdatedState(onValue)
    val safe = (value.takeIf { it.isFinite() } ?: range.start).coerceIn(range)
    val span = (range.endInclusive - range.start).coerceAtLeast(.000001f)
    val fraction = ((safe - range.start) / span).coerceIn(0f, 1f)
    var dragging by remember { mutableStateOf(false) }
    fun snap(requested: Float): Float {
        val amount = ((requested - range.start) / span).coerceIn(0f, 1f)
        val rounded = if (steps > 0) round(amount * (steps + 1)) / (steps + 1) else amount
        return (range.start + rounded * span).coerceIn(range)
    }
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = tones.amber)
        }
        Canvas(Modifier.fillMaxWidth().height(48.dp).semantics {
            contentDescription = title
            stateDescription = valueLabel
            progressBarRangeInfo = ProgressBarRangeInfo(safe, range, steps)
            if (!enabled) disabled()
            setProgress { requested ->
                if (enabled && requested.isFinite()) { deliver(snap(requested)); true } else false
            }
        }.onKeyEvent { event ->
            if (!enabled || event.type != KeyEventType.KeyDown) false
            else when (event.key) {
                Key.DirectionLeft, Key.DirectionRight -> {
                    val increment = span / if (steps > 0) steps + 1 else 100
                    deliver(snap(safe + increment * if (event.key == Key.DirectionRight) 1 else -1)); true
                }
                else -> false
            }
        }.focusable(enabled).pointerInput(enabled, range.start, range.endInclusive, steps) {
            if (!enabled) return@pointerInput
            val inset = 12.dp.toPx()
            fun emit(x: Float) {
                val width = (size.width - inset * 2).coerceAtLeast(1f)
                deliver(snap(range.start + ((x - inset) / width).coerceIn(0f, 1f) * span))
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                try {
                    val horizontal = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                        dragging = true; change.consume(); emit(change.position.x)
                    }
                    if (horizontal != null) horizontalDrag(horizontal.id) { change ->
                        change.consume(); emit(change.position.x)
                    } else currentEvent.changes.firstOrNull { it.id == down.id }?.let { end ->
                        if (!end.pressed && end.previousPressed && !end.isConsumed) emit(end.position.x)
                    }
                } finally { dragging = false }
            }
        }) {
            val inset = 12.dp.toPx(); val y = size.height / 2
            val start = Offset(inset, y); val end = Offset(size.width - inset, y)
            val thumb = Offset(inset + (size.width - inset * 2) * fraction, y)
            val opacity = if (enabled) 1f else .4f
            drawLine(tones.rim.copy(.65f * opacity), start, end, 3.dp.toPx(), StrokeCap.Round)
            if (fraction > 0f) drawLine(tones.amber.copy(opacity), start, thumb, 3.dp.toPx(), StrokeCap.Round)
            if (dragging) drawCircle(Brush.radialGradient(listOf(tones.amber.copy(.22f), Color.Transparent),
                center = thumb, radius = 19.dp.toPx()), 19.dp.toPx(), thumb)
            drawCircle(tones.ink.copy(opacity), 9.dp.toPx(), thumb)
            drawCircle(tones.paperHighlight.copy(opacity), 3.dp.toPx(), thumb)
        }
    }
}
