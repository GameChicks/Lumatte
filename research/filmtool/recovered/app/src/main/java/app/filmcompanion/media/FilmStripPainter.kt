package app.filmcompanion.media



import android.content.Context

import android.graphics.*

import app.filmcompanion.R



/** Shared physical measurements for contact sheets and the single-frame film template. */

class FilmStripPainter(private val context: Context) {

    private val fonts=PrintFonts(context)

    private val asset=BitmapFactory.decodeResource(context.resources,R.drawable.cartridge_blank_v1)

    private val spool=BitmapFactory.decodeResource(context.resources,R.drawable.spool_120_blank_v1)

    val inkColor: (CartridgeSettings)->Int = { if(it.blackWhite==true) Color.rgb(235,234,223) else Color.rgb(237,144,57) }

    fun cartridge(c: Canvas, box: RectF, settings: CartridgeSettings, castShadow: Boolean=false) {

        val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        if(settings.roll120==true) {spool(c,box,settings,p);return}

        val scale=minOf(box.width()/240,box.height()/430)

        c.save();c.translate(box.centerX()-120*scale,box.centerY()-215*scale);c.scale(scale,scale)

        if(castShadow) shadow(c)

        c.drawBitmap(requireNotNull(asset),Rect(197,102,890,1344),RectF(0f,0f,240f,430f),p)

        // A label wrapped around a cylinder follows the projected top/bottom arcs, not a flat rectangle.

        val label=Path().apply {

            moveTo(6f,71f);cubicTo(24f,75f,54f,80f,83f,81f)

            lineTo(83f,409f);cubicTo(55f,408f,23f,402f,6f,393f)

            cubicTo(5.5f,282f,5.5f,182f,6f,71f);close()

        }

        val accent=settings.color ?: Color.rgb(99,103,96)

        p.color=accent;c.drawPath(label,p)

        texturePanel(c,requireNotNull(asset),Rect(197,102,890,1344),RectF(0f,0f,240f,430f),label,accent,p)

        c.save();c.clipPath(label)

        p.shader=LinearGradient(6f,0f,83f,0f,intArrayOf(0x70000000,0x00FFFFFF,0x20FFFFFF,0x08000000),floatArrayOf(0f,.3f,.55f,1f),Shader.TileMode.CLAMP)

        c.drawPath(label,p);p.shader=null;c.restore()

        vertical(c,settings.notes,49f,92f,294f,12f,PrintPalette.contrasting(accent))

        vertical(c,if(settings.blackWhite==true) "Black & White Film" else "Color Negative Film",106f,97f,285f,12.5f,0xFFEAE8DD.toInt())

        vertical(c,settings.film,150f,95f,289f,31f,0xFFF0EEE4.toInt())

        vertical(c,settings.brand.uppercase(),208f,96f,282f,18f,0xFFF0EEE4.toInt())

        c.restore()

    }

    private fun shadow(c: Canvas) {

        // The reference is a close contact shadow: a dense core, only a tiny soft edge, no broad halo.

        val shade=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=0xB0000000.toInt();maskFilter=BlurMaskFilter(1.5f,BlurMaskFilter.Blur.NORMAL)}

        c.drawRoundRect(16f,72f,245f,411f,3f,3f,shade)

    }

    private fun spool(c: Canvas,box: RectF,s: CartridgeSettings,p: Paint) {

        val scale=minOf(box.width()/120,box.height()/430)

        c.save();c.translate(box.centerX()-60*scale,box.centerY()-215*scale);c.scale(scale,scale)

        val src=Rect(321,40,703,1490);val dest=RectF(3.36f,0f,116.64f,430f)

        spool?.let {c.drawBitmap(it,src,dest,p)}

        val color=s.color ?: Color.GRAY

        p.color=color;c.drawRect(9f,141f,111f,295f,p)

        spool?.let {texturePanel(c,it,src,dest,Path().apply {addRect(RectF(9f,141f,111f,295f),Path.Direction.CW)},color,p)}

        val ink=PrintPalette.contrasting(color)

        vertical(c,s.film,76f,151f,134f,24f,ink)

        vertical(c,s.brand.uppercase(),37f,151f,133f,13f,ink)

        fonts.text(c,"120",RectF(12f,24f,108f,42f),12f,PrintFont.SANS,Color.WHITE)

        fonts.text(c,"120",RectF(12f,392f,108f,408f),12f,PrintFont.SANS,Color.WHITE)

        fonts.text(c,if(s.blackWhite==true)"Black & White Film" else "Color Negative Film",RectF(10f,320f,110f,335f),8f,PrintFont.SANS,0xFFEAE8DD.toInt())

        fonts.text(c,s.notes,RectF(10f,349f,110f,365f),8f,PrintFont.SANS,0xFFEAE8DD.toInt())

        c.restore()

    }

    private fun texturePanel(c: Canvas,b: Bitmap,src: Rect,dest: RectF,panel: Path,color: Int,p: Paint) {

        fun channel(v: Int)=v/255f*.7f/3

        val r=Color.red(color);val g=Color.green(color);val blue=Color.blue(color)

        p.colorFilter=ColorMatrixColorFilter(ColorMatrix(floatArrayOf(

            channel(r),channel(r),channel(r),0f,r*.65f,

            channel(g),channel(g),channel(g),0f,g*.65f,

            channel(blue),channel(blue),channel(blue),0f,blue*.65f,

            0f,0f,0f,1f,0f)))

        c.save();c.clipPath(panel);c.drawBitmap(b,src,dest,p);c.restore();p.colorFilter=null

    }

    private fun vertical(c: Canvas,s: String,x: Float,y: Float,len: Float,size: Float,color: Int) {

        c.save();c.translate(x,y);c.rotate(90f)

        fonts.text(c,s,RectF(0f,-size*.6f,len,size*.6f),size,PrintFont.SANS,color,Paint.Align.LEFT)

        c.restore()

    }

    fun strip(c: Canvas, box: RectF, columns: Int, settings: CartridgeSettings, numbers: List<String>, film: String, railFraction: Float=.18f) {

        val p=Paint(Paint.ANTI_ALIAS_FLAG);p.color=0xFF060606.toInt();c.drawRect(box,p)

        val cw=box.width()/columns;val rail=box.height()*railFraction;val pitch=cw/8

        p.color=Color.WHITE

        for(i in 0 until (if(settings.roll120==true)0 else columns*8)) {

            val x=box.left+i*pitch+pitch*.18f

            for(y in listOf(box.top+rail*.34f,box.bottom-rail*.69f))

                c.drawRoundRect(x,y,x+pitch*.52f,y+rail*.42f,pitch*.12f,pitch*.12f,p)

        }

        val ink=inkColor(settings)

        for(i in 0 until columns) {

            val x=box.left+i*cw;val number=numbers.getOrNull(i) ?: ""

            fonts.text(c,number,RectF(x+3,box.top,x+cw*.18f,box.top+rail*.28f),rail*.26f,PrintFont.TYPEWRITER,ink,Paint.Align.LEFT)

            fonts.text(c,film.uppercase(),RectF(x+cw*.22f,box.top,x+cw-5,box.top+rail*.28f),rail*.26f,PrintFont.TYPEWRITER,ink,Paint.Align.LEFT)

            fonts.text(c,number,RectF(x+cw*.34f,box.bottom-rail*.27f,x+cw*.45f,box.bottom),rail*.24f,PrintFont.TYPEWRITER,ink)

            fonts.text(c,if(number.isEmpty()) "" else number+"A",RectF(x+cw*.82f,box.bottom-rail*.27f,x+cw,box.bottom),rail*.24f,PrintFont.TYPEWRITER,ink)

            p.color=ink

            barcode(c,RectF(x+cw*.01f,box.bottom-rail*.24f,x+cw*.29f,box.bottom-rail*.035f),p,i)

            barcode(c,RectF(x+cw*.51f,box.bottom-rail*.24f,x+cw*.79f,box.bottom-rail*.035f),p,i+7)

        }

    }

    private fun barcode(c: Canvas,b: RectF,p: Paint,seed: Int) {

        val units=b.width()/36;var x=b.left;var i=0

        while(x<b.right) {val w=units*(if((i+seed)%5==0) 2 else 1);c.drawRect(x,b.top+if((i+seed)%4==0) b.height()*.3f else 0f,minOf(x+w,b.right),b.bottom,p);x+=w+units;i++}

    }

    fun close() {asset?.recycle();spool?.recycle()}

    companion object {

        /** Align the visible cylinder edge to the first photo, rather than the transparent asset box. */

        fun attachedBox(photoLeft: Float,top: Float,bottom: Float,roll120: Boolean=false): RectF {

            val scale=(bottom-top)/430f

            val width=if(roll120)120f else 240f

            val bodyRight=if(roll120)112f else 235f

            val right=photoLeft+(width-bodyRight)*scale

            return RectF(right-width*scale,top,right,bottom)

        }

    }

}
