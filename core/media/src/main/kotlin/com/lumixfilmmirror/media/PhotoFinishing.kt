package com.lumixfilmmirror.media

import com.lumixfilmmirror.model.PhotoAdjustments
import kotlin.math.*

/** Source adjustments before LUT lookup; matches native film/LUT equations without an 8-bit intermediate. */
internal object PhotoFinishing {
    fun apply(rgb: FloatArray, p: PhotoAdjustments) {
        if (p == PhotoAdjustments()) return
        fun decode(v: Float) = if (v <= .04045f) v/12.92f else ((v+.055f)/1.055f).pow(2.4f)
        fun encode(v: Float) = if (v <= .0031308f) 12.92f*v else 1.055f*v.pow(1f/2.4f)-.055f
        fun ease(t: Float): Float { val x=t.coerceIn(0f,1f); return x*x*(3-2*x) }
        val a=FloatArray(3) { decode(rgb[it].coerceIn(0f,1f))*2f.pow(p.exposure) }
        for(c in 0..2)rgb[c]=encode(a[c])
        if(p.copy(exposure=0f)==PhotoAdjustments())return
        fun light() = .2126f*a[0]+.7152f*a[1]+.0722f*a[2]
        val sourceY=light()
        if(sourceY<=1e-6f){for(c in 0..2)rgb[c]=0f;return}
        val y=sourceY.coerceIn(1e-6f,1f-1e-6f)
        a[0]*=2f.pow(p.temperature*.4f); a[2]*=2f.pow(-p.temperature*.4f)
        val balance=sourceY/max(light(),1e-6f)
        for(c in 0..2)a[c]*=balance
        val stops=log2(y/.18f)
        val shift=p.brightness*1.5f+2*(p.highlights*ease(stops/3)+p.shadows*ease(-stops/4))
        val pivot=.18f/.82f
        val odds=((y/(1-y))/pivot).pow(2f.pow(p.contrast))*pivot*2f.pow(shift)
        val outputY=odds/(1+odds)
        for(c in 0..2)a[c]*=outputY/sourceY
        val peak=a.max();val trough=a.min()
        val colourful=((peak-trough)/max(peak,1e-6f)).coerceIn(0f,1f)
        val chroma=(1+p.saturation)*(1+p.vibrance*(1-colourful))
        for(c in 0..2)a[c]=outputY+chroma*(a[c]-outputY)
        var reach=1f
        for(c in a){ if(c<0)reach=min(reach,outputY/(outputY-c));if(c>1)reach=min(reach,(1-outputY)/(c-outputY)) }
        for(c in 0..2)rgb[c]=encode((outputY+reach*(a[c]-outputY)).coerceIn(0f,1f))
    }
}
