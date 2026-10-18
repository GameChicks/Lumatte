package com.lumixfilmmirror.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp

private val homeGrain by lazy {
    val random = java.util.Random(721)
    Bitmap.createBitmap(IntArray(128 * 128) {
        val light = random.nextBoolean()
        val alpha = random.nextInt(15)
        (alpha shl 24) or if (light) 0x00ffffff else 0
    }, 128, 128, Bitmap.Config.ARGB_8888)
}

/** A fixed display layer: glass lighting and grain never subscribe to the fluid clock. */
@Composable
internal fun HomeMaterialLighting(modifier: Modifier, colours: List<Color>) {
    val grainPaint = remember { android.graphics.Paint().apply {
        shader = BitmapShader(homeGrain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    } }
    Canvas(modifier.graphicsLayer()) {
        drawRect(Brush.radialGradient(listOf(Color.White.copy(.28f), Color.Transparent),
            center = Offset(size.width * .18f, -size.height * .12f), radius = size.width * .82f))
        drawRect(Brush.radialGradient(listOf(colours.first().copy(.36f), Color.Transparent),
            center = Offset(-size.width * .12f, size.height * .65f), radius = size.width * .58f))
        drawRect(Brush.radialGradient(listOf(Color.White.copy(.23f), Color.Transparent),
            center = Offset(size.width * 1.05f, size.height * 1.1f), radius = size.width * .47f))
        drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(.05f), Color.Transparent, Color.Black.copy(.09f))))
        drawContext.canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, grainPaint)
        val spacing = 12.dp.toPx()
        for (x in 0 until (size.width / spacing).toInt()) for (y in 0 until (size.height / spacing).toInt()) {
            val alpha = if ((x * 17 + y * 13) % 7 == 0) .12f else .045f
            drawCircle(Color.White.copy(alpha), .34.dp.toPx(), Offset((x + .5f) * spacing, (y + .5f) * spacing))
        }
    }
}
