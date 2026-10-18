package com.lumixfilmmirror.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import androidx.exifinterface.media.ExifInterface
import com.lumixfilmmirror.model.PixelSize
import java.io.IOException

/** Bounded software example images. Network content type alone is never sufficient. */
internal object LutExampleDecoder {
    fun decode(bytes: ByteArray, longEdge: Int): Bitmap {
        require(bytes.size in 1..(4 * 1024 * 1024) && longEdge in 64..1280)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outMimeType !in setOf("image/jpeg", "image/png", "image/webp") ||
            bounds.outWidth !in 1..16384 || bounds.outHeight !in 1..16384 ||
            bounds.outWidth.toLong() * bounds.outHeight > 64_000_000) {
            throw LumixLabProtocolException("创作者例图格式或尺寸不受支持")
        }
        val orientation = try {
            bytes.inputStream().use { input ->
                ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1).takeIf { it in 1..8 } ?: 1
            }
        } catch (_: IOException) { 1 }
        var sample = 1
        while ((maxOf(bounds.outWidth, bounds.outHeight) + sample - 1) / sample > longEdge) sample *= 2
        val input = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            inScaled = false
        }) ?: throw LumixLabProtocolException("创作者例图无法解码")
        try {
            if (maxOf(input.width, input.height) > longEdge) throw LumixLabProtocolException("创作者例图解码尺寸超出限制")
            val size = if (orientation in 5..8) PixelSize(input.height, input.width)
                else PixelSize(input.width, input.height)
            return BitmapOrientation.render(input, size, orientation)
        } finally { input.recycle() }
    }
}
