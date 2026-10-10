package com.lumixfilmmirror.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import androidx.exifinterface.media.ExifInterface
import com.lumixfilmmirror.model.PixelSize
import java.io.ByteArrayInputStream
import java.io.IOException

/** Small JPEG-only previews. Caller runs decoding off the main thread and owns the returned bitmap. */
object JpegThumbnailDecoder {
    fun decode(bytes: ByteArray, longEdge: Int = 384): Bitmap {
        require(bytes.size in 1..(4 * 1024 * 1024) && longEdge in 1..384) { "缩略图超出限制" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 64_000_000) { "无效的相机 JPEG 缩略图" }
        val orientation = try {
            ByteArrayInputStream(bytes).use { input ->
                ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    .takeIf { it in 1..8 } ?: ExifInterface.ORIENTATION_NORMAL
            }
        } catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
        var sample = 1
        while ((maxOf(bounds.outWidth, bounds.outHeight) + sample - 1) / sample > longEdge) sample *= 2
        val input = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            inScaled = false
        }) ?: error("缩略图无法解码")
        try {
            require(maxOf(input.width, input.height) <= longEdge) { "解码后的缩略图超出限制" }
            val size = if (orientation in 5..8) PixelSize(input.height, input.width)
                else PixelSize(input.width, input.height)
            return BitmapOrientation.render(input, size, orientation)
        } finally { input.recycle() }
    }
}
