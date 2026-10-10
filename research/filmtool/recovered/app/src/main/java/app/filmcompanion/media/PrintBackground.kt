package app.filmcompanion.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.roundToInt

/** Bounded CPU blur, shared by export and preview and available on Android 9. */
object PrintBackground {
    fun blurred(source: Bitmap): Bitmap {
        val factor=160f/max(source.width,source.height)
        val w=max(1,(source.width*factor).roundToInt());val h=max(1,(source.height*factor).roundToInt())
        val scaled=Bitmap.createScaledBitmap(source,w,h,true)
        val b=scaled.copy(Bitmap.Config.ARGB_8888,true)
        if(scaled!==source)scaled.recycle()
        var input=IntArray(w*h);var output=IntArray(w*h)
        b.getPixels(input,0,w,0,0,w,h)
        val radius=12;val count=radius*2+1
        repeat(3) {
            for(horizontal in listOf(true,false)) {
                val lines=if(horizontal)h else w;val length=if(horizontal)w else h
                fun index(line: Int,p: Int)=if(horizontal)line*w+p else p*w+line
                for(line in 0 until lines) {
                    var r=0;var g=0;var blue=0
                    for(k in -radius..radius) {
                        val c=input[index(line,k.coerceIn(0,length-1))];r+=(c ushr 16)and255;g+=(c ushr 8)and255;blue+=c and255
                    }
                    for(p in 0 until length) {
                        output[index(line,p)]=(-0x1000000) or ((r/count)shl16) or ((g/count)shl8) or (blue/count)
                        val old=input[index(line,(p-radius).coerceIn(0,length-1))]
                        val next=input[index(line,(p+radius+1).coerceIn(0,length-1))]
                        r+=((next ushr16)and255)-((old ushr16)and255)
                        g+=((next ushr8)and255)-((old ushr8)and255)
                        blue+=(next and255)-(old and255)
                    }
                }
                val swap=input;input=output;output=swap
            }
        }
        b.setPixels(input,0,w,0,0,w,h);return b
    }
    fun fill(canvas: Canvas,source: Bitmap,width: Int,height: Int) {
        val b=blurred(source)
        try {
            val scale=max(width.toFloat()/b.width,height.toFloat()/b.height)
            val rect=RectF((width-b.width*scale)/2,(height-b.height*scale)/2,(width+b.width*scale)/2,(height+b.height*scale)/2)
            canvas.drawBitmap(b,null,rect,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } finally {b.recycle()}
    }
}
