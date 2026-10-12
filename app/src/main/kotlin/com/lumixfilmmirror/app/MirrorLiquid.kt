package com.lumixfilmmirror.app

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.cos
import kotlin.math.sin

private val liquidEpoch = android.os.SystemClock.elapsedRealtimeNanos()
private fun liquidSeconds() = (android.os.SystemClock.elapsedRealtimeNanos() - liquidEpoch) / 1_000_000_000f

/** Independent Android colour-field implementation, inspired by AMLL's flowing mesh material.
 * The clock is observed only by the isolated drawing layer, never by the card's composition. */
@Composable
internal fun LiquidMaterial(modifier: Modifier, colours: List<Color>, moving: Boolean) {
    val seconds = remember { mutableFloatStateOf(liquidSeconds()) }
    val touch = remember { mutableStateOf(Offset(.5f, .5f)) }
    val touchTime = remember { mutableFloatStateOf(-100f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(moving, lifecycle) {
        if (moving) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previous = 0L
            while (true) withFrameNanos { now ->
                if (previous == 0L) previous = now
                // A 30 Hz material clock; scrolling and controls retain their own display cadence.
                if (now - previous >= 30_000_000L) {
                    seconds.floatValue = liquidSeconds()
                    previous = now
                }
            }
        }
    }
    val renderer = remember(colours) { if (Build.VERSION.SDK_INT >= 33) LiquidShader(colours) else null }
    Canvas(modifier.graphicsLayer().pointerInput(moving) {
        if (moving) awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            touch.value = Offset(down.position.x / size.width, down.position.y / size.height)
            touchTime.floatValue = seconds.floatValue
            // Observe without consuming: the camera card's click, swipe and pager remain available.
        }
    }) {
        val t = seconds.floatValue
        if (Build.VERSION.SDK_INT >= 33 && renderer != null) {
            renderer.draw(size.width, size.height, t, moving, touch.value, t - touchTime.floatValue, drawContext.canvas)
        } else {
            drawRect(Brush.verticalGradient(colours))
            repeat(6) { i ->
                val p = Offset(size.width * (.5f + .47f * sin(t * (.11f + i * .013f) + i * 2.1f)),
                    size.height * (.5f + .5f * cos(t * (.13f + i * .017f) + i * 1.7f)))
                drawRect(Brush.radialGradient(listOf(colours[i % colours.size].copy(.8f), Color.Transparent),
                    p, size.width * (.6f + .05f * i)))
            }
            if (moving) repeat(3) { i ->
                val age = (t + i * 2.7f) % 8.1f
                val r = (.08f + age * .12f) * size.width
                val alpha = sin(age / 8.1f * Math.PI).toFloat() * .19f
                drawCircle(Brush.radialGradient(colorStops = arrayOf(0f to Color.Transparent, .84f to Color.Transparent,
                    .94f to Color.White.copy(alpha), 1f to Color.Transparent), center, r), r)
            }
        }
    }
}

@RequiresApi(33)
private class LiquidShader(colours: List<Color>) {
    private val shader = android.graphics.RuntimeShader(SOURCE)
    private val paint = android.graphics.Paint().apply { shader = this@LiquidShader.shader }
    init {
        colours.take(3).forEachIndexed { i, c -> shader.setFloatUniform("c$i", c.red, c.green, c.blue) }
    }
    fun draw(width: Float, height: Float, time: Float, moving: Boolean, touch: Offset, age: Float, canvas: Canvas) {
        shader.setFloatUniform("resolution", width, height)
        shader.setFloatUniform("time", time)
        shader.setFloatUniform("motion", if (moving) 1f else 0f)
        shader.setFloatUniform("touch", touch.x, touch.y)
        shader.setFloatUniform("touchAge", age)
        canvas.nativeCanvas.drawRect(0f, 0f, width, height, paint)
    }
    companion object {
        // Incommensurate velocities and domain warping avoid a tween endpoint or a visible reset.
        private const val SOURCE = """
            uniform float2 resolution;
            uniform float time;
            uniform float motion;
            uniform float3 c0;
            uniform float3 c1;
            uniform float3 c2;
            uniform float2 touch;
            uniform float touchAge;
            float field(float2 uv, float2 p, float spread) {
                float2 d = uv-p; return exp(-dot(d,d)*spread);
            }
            half4 main(float2 pixel) {
                float2 uv = pixel / resolution;
                float t = time*motion;
                float2 q = uv + .13*float2(sin(uv.y*5.3+t*.31)+cos(uv.x*4.1-t*.19),
                    cos(uv.x*4.7+t*.23)+sin(uv.y*3.9+t*.17));
                float2 a = .5+.47*float2(sin(t*.147+.3),cos(t*.193+1.4));
                float2 b = .5+.55*float2(cos(t*.213+2.3),sin(t*.161+.8));
                float2 c = .5+.53*float2(sin(t*.179+4.1),cos(t*.127+3.2));
                float wa=field(q,a,5.5)+.12;
                float wb=field(q,b,6.2)+.1;
                float wc=field(q,c,4.7)+.18;
                float3 colour=(c0*wa+c1*wb+c2*wc)/(wa+wb+wc);
                float d=length((uv-.5)*float2(1.,resolution.y/resolution.x));
                float rings=0.;
                for(int i=0;i<3;i++) {
                    float age=mod(t+float(i)*2.7,8.1);
                    float r=.06+age*.125;
                    float envelope=sin(age/8.1*3.14159);
                    rings+=exp(-pow((d-r)/.028,2.))*envelope*.15;
                }
                float td=length((uv-touch)*float2(1.,resolution.y/resolution.x));
                float ta=clamp(touchAge,0.,4.);
                float tap=exp(-pow((td-ta*.32)/.025,2.))*max(0.,1.-ta/3.)*.17;
                float sheen=.07*sin(q.x*3.+q.y*4.-t*.29);
                colour+=motion*(rings+tap)+sheen;
                float grain=fract(sin(dot(pixel,float2(12.9898,78.233)))*43758.5453)-.5;
                colour+=grain*.014;
                return half4(clamp(colour,0.,1.),1.);
            }
        """
    }
}
