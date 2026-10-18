package com.lumixfilmmirror.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Camera
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.res.ResourcesCompat
import com.lumixfilmmirror.media.FilmPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private data class CartridgeTexture(val objectImage: Bitmap, val shadow: Bitmap, val shadowOffset: IntArray)
private val cartridgeTextures = object : LruCache<String, CartridgeTexture>(10 * 1024 * 1024) {
    override fun sizeOf(key: String, value: CartridgeTexture) = value.objectImage.allocationByteCount + value.shadow.allocationByteCount
}
private val cartridgeLoadGate = Mutex()

private fun FilmPreset.artIdentity() = id.removeSuffix("_push1").removeSuffix("_push2")


/** One bounded raster per stock; draw-time perspective and shadows share the same geometry. */
@Composable
internal fun FilmCartridge(film: FilmPreset, modifier: Modifier = Modifier, roll: Float = 0f,
    yaw: Float = 0f, pitch: Float = 0f, illumination: Float = 1f, grounded: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val identity = film.artIdentity()
    var texture by remember(identity) { mutableStateOf(cartridgeTextures[identity]) }
    var unavailable by remember(identity) { mutableStateOf(false) }
    LaunchedEffect(identity) {
        try { texture = withContext(Dispatchers.IO) { cartridgeLoadGate.withLock {
            cartridgeTextures[identity] ?: loadCartridge(context, film).also { cartridgeTextures.put(identity, it) }
        } } }
        catch(error: CancellationException) { throw error }
        catch(_: Exception) { unavailable = true }
        catch(_: OutOfMemoryError) { unavailable = true }
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val shadowPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = AndroidColor.BLACK } }
    val camera = remember { Camera() }
    val perspective = remember { Matrix() }
    Box(modifier.semantics { contentDescription = film.name }, contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize()) {
        val art = texture ?: return@Canvas
        val objectImage = art.objectImage
        val angle = Math.toRadians(roll.toDouble())
        val width = objectImage.width.toFloat(); val height = objectImage.height.toFloat()
        val boxWidth = abs(width * cos(angle)).toFloat() + abs(height * sin(angle)).toFloat()
        val boxHeight = abs(width * sin(angle)).toFloat() + abs(height * cos(angle)).toFloat()
        val scale = minOf(size.width * .89f / boxWidth, size.height * .88f / boxHeight)
        val centerX = size.width * .49f; val centerY = size.height * .47f
        val destination = RectF(-width * scale / 2, -height * scale / 2, width * scale / 2, height * scale / 2)
        camera.save(); camera.setLocation(0f, 0f, -24f)
        camera.rotateX(pitch); camera.rotateY(yaw); camera.getMatrix(perspective); camera.restore()
        val canvas = drawContext.canvas.nativeCanvas
        if (grounded && abs(roll) < 30f) {
            val bottom = centerY + boxHeight * scale / 2
            // Fixed scene-space contact patch. It stays on the shelf when the product tilts.
            shadowPaint.alpha = 160
            shadowPaint.shader = android.graphics.RadialGradient(centerX + width * scale * .08f, bottom,
                width * scale * .53f, intArrayOf(0x88000000.toInt(), AndroidColor.TRANSPARENT), null,
                android.graphics.Shader.TileMode.CLAMP)
            canvas.save(); canvas.scale(1f, .19f, centerX, bottom)
            canvas.drawOval(RectF(centerX - width * scale * .57f, bottom - width * scale * .5f,
                centerX + width * scale * .68f, bottom + width * scale * .5f), shadowPaint)
            canvas.restore(); shadowPaint.shader = null
        }
        fun transform(dx: Float, dy: Float, draw: () -> Unit) {
            canvas.save(); canvas.translate(centerX + dx, centerY + dy)
            canvas.rotate(roll); canvas.concat(perspective); draw(); canvas.restore()
        }
        shadowPaint.alpha = if (grounded) 116 else 80
        val mask = art.shadow
        val shadowBox = RectF(destination.left + art.shadowOffset[0] * scale,
            destination.top + art.shadowOffset[1] * scale,
            destination.left + (art.shadowOffset[0] + mask.width) * scale,
            destination.top + (art.shadowOffset[1] + mask.height) * scale)
        transform(size.width * .027f, size.height * .026f) { canvas.drawBitmap(mask, null, shadowBox, shadowPaint) }
        paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            illumination,0f,0f,0f,0f, 0f,illumination,0f,0f,0f, 0f,0f,illumination,0f,0f, 0f,0f,0f,1f,0f)))
        transform(0f, 0f) { canvas.drawBitmap(objectImage, null, destination, paint) }
        paint.colorFilter = null
    }
    if (unavailable) Text("暗盒暂不可用", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
    }
}

private fun loadCartridge(context: Context, film: FilmPreset): CartridgeTexture {
    val resource = com.lumixfilmmirror.media.FilmCartridgeArtwork.resource(film.id)
    val options = BitmapFactory.Options().apply { inScaled = false; inSampleSize = 2 }
    val raw = BitmapFactory.decodeResource(context.resources, resource ?: R.drawable.cartridge_blank_v1, options)
        ?: error("暗盒素材无法读取")
    val composed = if (resource != null) raw else {
        val result = raw.copy(Bitmap.Config.ARGB_8888, true)
        try { wrapLabel(context, film, AndroidCanvas(result), result.width, result.height) }
        catch (error: Throwable) { result.recycle(); throw error }
        finally { raw.recycle() }
        result
    }
    // Fit visible geometry, not each source file's different transparent canvas margins.
    val row = IntArray(composed.width)
    var left = composed.width; var right = -1; var top = composed.height; var bottom = -1
    for (y in 0 until composed.height) {
        composed.getPixels(row, 0, composed.width, 0, y, composed.width, 1)
        for (x in row.indices) if ((row[x] ushr 24) >= 160) {
            left = minOf(left,x); right = maxOf(right,x); top = minOf(top,y); bottom = maxOf(bottom,y)
        }
    }
    val cropped = if (right >= left) {
        val pad = 3
        val x = maxOf(0,left-pad); val y = maxOf(0,top-pad)
        Bitmap.createBitmap(composed,x,y,minOf(composed.width,right+pad+1)-x,minOf(composed.height,bottom+pad+1)-y)
    } else composed
    if (cropped !== composed) composed.recycle()
    val offset = IntArray(2)
    val mask = cropped.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(cropped.width * .055f, BlurMaskFilter.Blur.NORMAL) }, offset)
    return CartridgeTexture(cropped,mask,offset)
}

/** Texture wraps around a cylindrical front half, including curved upper/lower paper edges. */
private fun wrapLabel(context: Context, film: FilmPreset, canvas: AndroidCanvas, width: Int, height: Int) {
    val filename = recoveredFilmLabel(film.artIdentity())
    val label = filename?.let { context.assets.open("frames/art/$it").use { stream ->
        BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 2 }) } }
        ?: makeLabel(context, film)
    try {
        val columns = 32; val rows = 4
        val vertices = FloatArray((columns+1)*(rows+1)*2)
        val colors = IntArray((columns+1)*(rows+1))
        val left = width*.193f; val span = width*.621f
        val top = height*.244f; val labelHeight = height*.615f
        for (row in 0..rows) for (column in 0..columns) {
            val u = column.toFloat()/columns; val v = row.toFloat()/rows
            val theta = (u-.5f)*2.45f
            val curvedX = (sin(theta)/sin(1.225f)+1f)*.5f
            val bow = cos(theta)*height*.015f
            val index = row*(columns+1)+column
            vertices[index*2] = left+span*curvedX
            vertices[index*2+1] = top+labelHeight*v+bow
            val light = (.48f+.52f*cos(theta)-.12f*u).coerceIn(.36f,1f)
            val channel = (255f*light).toInt()
            colors[index] = AndroidColor.rgb(channel,channel,channel)
        }
        canvas.drawBitmapMesh(label,columns,rows,vertices,0,colors,0,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    } finally { label.recycle() }
}

private fun makeLabel(context: Context, film: FilmPreset): Bitmap {
    val image = Bitmap.createBitmap(420,560,Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(image)
    val editorial = film.editorialName()
    val info = FilmEditorialLibrary.forFilm(film)
    val background = if (film.id.contains("portra")) 0xFFE6DAC2.toInt() else info.accent.toArgb()
    canvas.drawColor(background)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=0xFF171714.toInt(); typeface=Typeface.create(ResourcesCompat.getFont(context, com.lumixfilmmirror.design.R.font.oppo_sans_4),700,false)
    }
    paint.textSize=27f; canvas.drawText(film.name.substringBefore(' ').uppercase(),28f,48f,paint)
    paint.textSize=76f
    paint.textSize*=minOf(1f,360f/paint.measureText(editorial.title).coerceAtLeast(1f))
    canvas.drawText(editorial.title,28f,270f,paint)
    editorial.speed?.let { paint.textSize=110f; canvas.drawText(it,28f,390f,paint) }
    paint.color=if(film.id.contains("portra")) 0xFF735875.toInt() else 0xFF252C29.toInt()
    canvas.drawRect(0f,460f,420f,560f,paint)
    paint.color=AndroidColor.WHITE; paint.textSize=23f
    canvas.drawText(if(film.category==com.lumixfilmmirror.media.FilmCategory.CINEMA) "MOTION PICTURE" else "COLOR FILM",28f,520f,paint)
    return image
}
