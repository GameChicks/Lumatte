package com.lumixfilmmirror.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.lumixfilmmirror.model.PixelSize

/** The caller owns input; this returns an independent, upright canvas. */
internal object BitmapOrientation {
    /** Downsample display proofs with a low-pass pyramid, rather than picking four
     * noisy full-resolution texels for each screen pixel. Input remains borrowed.
     * Export rendering/encoding and the film's physical grain parameters are unchanged.
     */
    fun previewDownsample(input: Bitmap, size: PixelSize, checkActive: () -> Unit): Bitmap {
        var owned: Bitmap? = null
        try {
            var current = input
            while(current.width > size.width*2L || current.height > size.height*2L) {
                checkActive()
                val nextSize=PixelSize(maxOf(size.width,current.width/2),maxOf(size.height,current.height/2))
                val next=render(current,nextSize,1)
                owned?.recycle();owned=next;current=next
            }
            checkActive()
            if(owned!=null && current.width==size.width && current.height==size.height) {
                owned=null
                return current
            }
            return render(current,size,1)
        } finally { owned?.recycle() }
    }

    fun render(input: Bitmap, size: PixelSize, orientation: Int): Bitmap {
        val output = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        try {
            val width = size.width.toFloat(); val height = size.height.toFloat()
            val destination = when (orientation) {
                2 -> floatArrayOf(width, 0f, 0f, 0f, width, height)
                3 -> floatArrayOf(width, height, 0f, height, width, 0f)
                4 -> floatArrayOf(0f, height, width, height, 0f, 0f)
                5 -> floatArrayOf(0f, 0f, 0f, height, width, 0f)
                6 -> floatArrayOf(width, 0f, width, height, 0f, 0f)
                7 -> floatArrayOf(width, height, width, 0f, 0f, height)
                8 -> floatArrayOf(0f, height, 0f, 0f, width, height)
                else -> floatArrayOf(0f, 0f, width, 0f, 0f, height)
            }
            val transform = Matrix()
            check(transform.setPolyToPoly(
                floatArrayOf(0f, 0f, input.width.toFloat(), 0f, 0f, input.height.toFloat()), 0,
                destination, 0, 3,
            )) { "无法应用照片方向" }
            Canvas(output).drawBitmap(input, transform, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return output
        } catch (error: Throwable) { output.recycle(); throw error }
    }
}
