package com.lumixfilmmirror.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.sin
import kotlin.math.cos

/** Own Android light field: slow broad colour movement, without a diagonal stripe or touch rings.
 * AMLL is the visual reference, not a runtime dependency. Only this drawing layer reads the clock. */
@Composable
internal fun MirrorColorField(modifier: Modifier, colors: List<Color>, moving: Boolean = true) {
    val reduced = LocalMirrorReducedMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(moving, reduced, lifecycle) {
        if(moving && !reduced) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last=0L
            while(true) withFrameNanos { now ->
                if(last!=0L && now-last>=41_000_000L) { time.floatValue += minOf((now-last)/1_000_000_000f,.1f); last=now }
                else if(last==0L)last=now
            }
        }
    }
    Canvas(modifier) {
        val t=time.floatValue
        drawRect(colors.first())
        colors.drop(1).forEachIndexed { i,color ->
            val center=Offset(size.width*(.50f+.38f*sin(t*.065f+i*2.2f)),
                size.height*(.48f+.40f*cos(t*.055f+i*1.9f)))
            drawRect(Brush.radialGradient(listOf(color.copy(.92f),color.copy(.28f),Color.Transparent),
                center,size.maxDimension*(.68f+i*.07f)))
        }
    }
}
