package com.lumixfilmmirror.media.frame

import android.content.Context
import android.graphics.*
import com.lumixfilmmirror.media.FilmPreset
import com.lumixfilmmirror.media.FilmCategory
import org.json.JSONObject
import java.util.Locale
import kotlin.math.*

/** SpektraLab overscan.cpp / filmEdge.ts, d667dcf, GPL-3.0-or-later.
 * 135 edge layout, printer glyphs, DX bits and film dimensions adapted for post-render export.
 * Original bytes, measured/borrowed layout caveats and licences: research/spektralab/.
 * This compositor preserves the source aspect and does not chemically expose a new border.
 */
internal class FrameStockEdge(context: Context, @Suppress("UNUSED_PARAMETER") fonts: FrameTypography) {
    private val assets=context.applicationContext.assets
    private val data by lazy { assets.open("frames/spektralab/edge-faces.json").bufferedReader().use { JSONObject(it.readText()) } }
    private val bold by lazy { Typeface.createFromAsset(assets,"frames/spektralab/texgyreheros-bold.otf") }
    private val regular by lazy { Typeface.createFromAsset(assets,"frames/spektralab/texgyreheros-regular.otf") }
    private val mono by lazy { Typeface.createFromAsset(assets,"frames/spektralab/DejaVuSansMono-Bold.ttf") }

    fun draw(canvas:Canvas, photo:RectF, stock:FilmPreset, number:Int, transparent:Boolean, checkActive:()->Unit) {
        if(!transparent)canvas.drawColor(0xFFF0EEE7.toInt())
        canvas.save()
        if(photo.width()<photo.height()){canvas.translate(photo.left,photo.bottom);canvas.rotate(-90f)}
        else canvas.translate(photo.left,photo.top)
        try {
            val short=min(photo.width(),photo.height());val along=max(photo.width(),photo.height())
            val mm=short/24.3f;val length=along/mm;val rail=(35f-24.3f)/2
            canvas.scale(mm,mm);canvas.translate(0f,-rail)
            val strip=RectF(-.85f,0f,length+.85f,35f)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=when {
                stock.positive->0xFF080708.toInt();stock.category==FilmCategory.MONOCHROME->0xFF171916.toInt()
                else->0xFF311B12.toInt() } }
            canvas.drawRect(strip,paint);canvas.clipRect(strip)
            val cut=Paint(Paint.ANTI_ALIAS_FLAG).apply { if(transparent)blendMode=BlendMode.CLEAR else color=0xFFF0EEE7.toInt() }
            // Reference 135 perforations: 4.75 pitch, 1.98 x 2.80, 2mm from film edges.
            val phase=-3.3f
            var hole=phase
            while(hole<length+4.75f){checkActive();for(t in listOf(2f,30.2f))canvas.drawRoundRect(hole-.99f,t,hole+.99f,t+2.8f,.5f,.5f,cut);hole+=4.75f}
            val id=when(stock.id){"fujifilm_superia_xtra_400"->"fujifilm_xtra_400";"fujifilm_acros_100_ii"->"fujifilm_neopan_acros_100_ii";else->stock.id}
            val family=when {
                id.startsWith("cinestill_")->"cinestill"
                id.startsWith("kodak_vision3_")->"cine"
                id=="ilford_hp5_plus_400"->"ilford"
                id in setOf("fujifilm_xtra_400","fujifilm_pro_400h")->"fujiNeg"
                id in setOf("fujifilm_provia_100f","fujifilm_velvia_100","fujifilm_neopan_acros_100_ii")->"fujiSlide"
                else->"kodak"
            }
            val label=data.getJSONObject("labels").optString(id,stock.name.substringBefore('(').trim().uppercase(Locale.ROOT))
            paint.color=when {
                stock.category==FilmCategory.MONOCHROME->0xFFD2D1C8.toInt()
                stock.positive && !id.startsWith("fujifilm")->0xFFE4DBD5.toInt()
                stock.positive->0xFFE9BB59.toInt();family=="cine"->0xFFCCD2E9.toInt()
                family=="cinestill"->0xFFF3B850.toInt();family.startsWith("fuji")->0xFFE7C66A.toInt()
                else->0xFFE5B98A.toInt()
            }
            fun rect(l:Float,t:Float,r:Float,b:Float){canvas.drawRect(l,t,r,b,paint)}
            fun text(tx:String,x:Float,base:Float,cap:Float,face:Typeface=bold,scale:Float=1f,tracking:Float=.06f) {
                paint.typeface=face;paint.textSize=cap/(if(face===mono).729f else .714f)
                var sx=x
                tx.forEach { c ->
                    canvas.save();canvas.translate(sx,base);canvas.scale(scale,1f);canvas.drawText(c.toString(),0f,0f,paint);canvas.restore()
                    sx+=paint.measureText(c.toString())*scale+tracking
                }
            }
            fun matrix(tx:String,x0:Float,base:Float,cap:Float,xscale:Float=1f,heavy:Boolean=false) {
                val face=data.getJSONObject("faces").getJSONObject("matrix");val pitch=cap/7;val cw=pitch*xscale;var x=x0
                tx.forEach { c ->
                    val rows=face.optJSONArray(c.toString())
                    if(c==' ' || rows==null)x+=4*cw else {
                        for(row in 0..6){val bits=if(c=='0')intArrayOf(14,17,17,17,17,17,14)[row]else rows.getInt(row)
                            val expanded=if(heavy)(bits shl 1) or bits else bits;val n=if(heavy)6 else 5
                            for(col in 0 until n)if(expanded and (1 shl(n-1-col))!=0)
                                rect(x+col*cw-.02f*pitch,base-(7-row)*pitch-.02f*pitch,x+(col+1)*cw+.02f*pitch,base-(6-row)*pitch+.02f*pitch)
                        };x+=(if(heavy)7 else 6)*cw
                    }
                }
            }
            fun dots(tx:String,x0:Float,base:Float,faceName:String="names",ps:Float=.1155f,space:Float=4f) {
                val face=data.getJSONObject("faces").getJSONObject(faceName);var x=x0;var i=0
                while(i<tx.length){
                    var key=tx[i].toString();var count=1
                    if(faceName=="names")for(roman in listOf("III","II"))if(tx.startsWith(roman,i) && (i+roman.length==tx.length || tx[i+roman.length]==' ')){key=roman;count=roman.length;break}
                    val rows=face.optJSONArray(key)
                    if(rows!=null){for(row in 0 until rows.length()){val cells=rows.getString(row);for(col in cells.indices)if(cells[col]=='#')canvas.drawCircle(x+col*ps,base-(rows.length()-1-row)*.0984f-.057f,.057f,paint)}
                        x+=(rows.getString(0).length+2)*ps
                    }else x+=space*ps
                    i+=count
                }
            }
            fun arrow(x0:Float,x1:Float,t:Float){rect(x0,t-.09f,x1-.45f,t+.09f);val path=Path().apply{moveTo(x1-.6f,t-.26f);lineTo(x1,t);lineTo(x1-.6f,t+.26f);close()};canvas.drawPath(path,paint)}
            fun code(s:Float,num:Int,half:Boolean,ilford:Boolean=false) {
                val dx=data.getJSONObject("dx").optInt(id,-1);if(dx<0)return
                val bits=IntArray(23);val p1=(dx shr 4) and 127;val p2=dx and 15
                for(i in 0..6)bits[1+i]=(p1 shr(6-i)) and 1
                for(i in 0..3)bits[9+i]=(p2 shr(3-i)) and 1
                for(i in 0..5)bits[13+i]=(num.coerceIn(0,63) shr(5-i)) and 1
                bits[19]=if(half)1 else 0;bits[21]=bits.take(21).sum()%2
                val mod=(if(family=="fujiNeg")14.1f else if(ilford)13.06f else 12.7f)/31
                for(track in 0..1)for(i in 0..30){
                    val bit=if(track==0){if(i<5 || i>=28)1 else(i-5)%2}else if(i<5){if(i%2==0)1 else 0}else if(i>=28){if((i-28)%2==0)1 else 0}else bits[i-5]
                    if(bit==1)rect(s+i*mod,if(track==0)32.83f else 34.13f,s+(i+1)*mod,if(track==0)34.14f else 35.10f)
                }
            }
            // Half-frame grid stays tied to the same perforation phase even on panoramic gates.
            val minM=floor((-5f-phase-.62f)/19).toInt()-1;val maxM=ceil((length+5-phase-.62f)/19).toInt()+1
            for(m in minM..maxM){
                checkActive();val half=2*number+m;val num=floor(half/2.0).toInt();val a=half%2!=0
                val s=phase+.62f+19*m+if(family=="kodak" || family=="fujiNeg")19f else 0f
                when(family){
                    "kodak"->{
                        val light=id in setOf("fujifilm_c200","kodak_tri_x_400")
                        val cap=if(light)1.07f else 1.22f;val base=if(light)1.69f else 1.53f
                        if(!a)text(num.toString(),s+16.9f,base,cap,if(light)regular else bold,if(light)1f else 1.25f,.25f)
                        else text(label,s+if(light)5.1f else 6.4f,base,cap)
                        code(s,num,a)
                        if(a){text("${num}A",s+15.3f,34.16f,.90f,if(light)regular else bold,if(light)1f else 1.25f,.25f);arrow(s+14.47f,s+18.07f,34.72f)}
                        else text(num.toString(),s+16f,34.86f,1.4f,if(light)regular else bold,if(light)1f else 1.25f,.25f)
                    }
                    "fujiNeg"->{
                        val n=num.toString()+if(a)"A"else""
                        dots(n,s+if(a)16.38f else 15.75f,1.60f,"small",.09f)
                        if(a)dots(label,s+5.5f,1.5f)
                        code(s,num,a);dots(n,s+if(a)16.1f else 15.25f,if(a)34.59f else 34.69f,"small",.09f)
                        if(a)arrow(s+14.6f,s+15.85f,34f)
                    }
                    "fujiSlide"->{
                        val sn=phase+.56f+3.5f+19*m
                        if(!a){dots(num.toString(),sn,1.8f,"numbers");dots(num.toString(),sn+.1f,34.73f,"numbers");dots(label,sn+16,1.77f,space=10f)}
                        else{val sa=sn-19;matrix("36",sa+14.75f,34.6f,.675f);arrow(sa+16.05f,sa+18.59f,34.1f);dots("${num}A",sa+18.88f,34.6f,"small")}
                    }
                    "ilford"->{
                        val pivot=phase+1.89f+19*(m+1)
                        canvas.save();canvas.translate(pivot,35f);canvas.rotate(180f)
                        code(0f,num,a,true)
                        if(a){matrix("${num}A",14.9f,34.65f,1.2f);matrix(label,8.7f,1.85f,1.5f)}else matrix(num.toString(),14.6f,34.65f,1.6f,heavy=true)
                        canvas.restore()
                    }
                    "cinestill"->{
                        val f=floor(m/2.0).toInt();val numbered=m-2*f==0;val sn=phase+6.34f+38*f;val n=number+f
                        if(numbered){text(n.toString(),sn,34.98f,1.68f,mono,1.28f,0f);matrix(label,sn-6.21f,1.64f,1.38f,1.16f)}
                        else{val sa=sn+18.59f;val tc=34.085f;var previous=0f
                            for((end,hh)in listOf(.37f to .455f,.80f to .365f,1.08f to .235f,1.50f to .094f)){rect(sa+previous,tc-hh,sa+end+.01f,tc+hh);previous=end}
                            text("${n}A",sa+1.75f,34.5f,1f,mono,1.25f,0f)
                        }
                    }
                    "cine"->if(half%2==0){val sd=s+2.06f;rect(sd,34.09f,sd+1.16f,34.31f);if(Math.floorMod(half/2,2)==0)matrix(label,sd+10.1f,34.53f,.88f,.93f)}
                }
            }
        } finally {canvas.restore()}
    }
}
