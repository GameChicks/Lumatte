package app.filmcompanion.media

import android.content.Context
import android.graphics.*
import app.filmcompanion.R
import kotlin.math.*

data class PrintLayout(val width: Int,val height: Int,val photo: RectF,val text: RectF,val cartridge: RectF?)
object PrintLayouts {
    fun captionArea(layout: PrintLayout,border: PhotoBorder): RectF {
        val unit=min(layout.width,layout.height)/1000f
        return if(border==PhotoBorder.FILM)RectF(layout.photo.right+unit*18,layout.photo.top+layout.photo.height()*.46f,
            layout.photo.right+layout.photo.width()*.6f-unit*18,layout.photo.bottom-unit*12)
        else RectF(layout.text).apply {inset(unit*12,unit*10)}
    }
    fun plan(pw: Int,ph: Int,recipe: PhotoRecipe,roll120: Boolean=recipe.cartridge.roll120==true,targetLongEdge: Int?=null): PrintLayout {
        val short=min(pw,ph).toFloat()
        val pad=short*maxOf(recipe.borderWidth.coerceIn(.02f,.12f),when(recipe.border) {
            PhotoBorder.MUSEUM -> .07f
            PhotoBorder.SLIDE -> .12f
            else -> 0f
        })
        val ticket=recipe.border==PhotoBorder.TICKET;val film=recipe.border==PhotoBorder.FILM
        val header=if(ticket) short*.23f else if(film)ph*.18f else when(recipe.border) {
            PhotoBorder.MUSEUM -> short*.105f
            PhotoBorder.SLIDE -> short*.13f
            else -> pad
        }
        val footer=when(recipe.border) {
            PhotoBorder.TICKET->short*.4f;PhotoBorder.INSTANT->short*.23f
            PhotoBorder.FILM->ph*.18f;PhotoBorder.PALETTE->short*.25f
            PhotoBorder.MUSEUM->short*(if(recipe.showMetadata || recipe.signature.isNotBlank()) .24f else .105f)
            PhotoBorder.SLIDE->short*(if(recipe.showMetadata || recipe.signature.isNotBlank()) .21f else .13f)
            else->short*(if(recipe.showMetadata || recipe.signature.isNotBlank()) .17f else recipe.borderWidth)
        }
        val sidebar=ph*(if(roll120).38f else .74f)
        var w=if(film) pw*1.6f+sidebar+pad*2 else pw+pad*2
        var h=ph+header+footer
        recipe.ratio.value?.let {v ->
            val ratio=if(recipe.portrait) 1/v else v
            if(w/h<ratio)w=h*ratio else h=w/ratio
        }
        // Proportions are stable at every requested output resolution.
        val scale=if(targetLongEdge==null)minOf(1f,4400f/max(w,h),sqrt(88f*1024*1024/(w*h*4)))
            else {require(targetLongEdge in 1..8192);targetLongEdge.toFloat()/max(w,h)}
        w*=scale;h*=scale
        val margin=pad*scale;val top=header*scale;val bottom=footer*scale
        val available=RectF(margin+(if(film) sidebar*scale else 0f),top,w-margin-(if(film)pw*.6f*scale else 0f),h-bottom)
        val fit=min(available.width()/pw,available.height()/ph)
        val photo=RectF(available.centerX()-pw*fit/2,available.centerY()-ph*fit/2,available.centerX()+pw*fit/2,available.centerY()+ph*fit/2)
        val textBottom=if(ticket) h-min(w,h)*.025f-w*.014f-margin*.2f else h-margin*.35f
        val text=RectF(margin,photo.bottom,w-margin,textBottom)
        val cartridge=if(film) FilmStripPainter.attachedBox(photo.left,max(0f,photo.top-photo.height()*.16f),photo.bottom+photo.height()*.14f,roll120) else null
        return PrintLayout(max(1,w.roundToInt()),max(1,h.roundToInt()),photo,text,cartridge)
    }
}

class PhotoPrintRenderer(private val context: Context) {
    suspend fun render(source: Bitmap,recipe: PhotoRecipe,title: String,metadata: String,identity: PrintIdentity?,frame: Int?,targetLongEdge: Int?=null): Bitmap {
        if(recipe.border==PhotoBorder.NONE)return source.copy(Bitmap.Config.ARGB_8888,true) ?: error("无法创建导出位图")
        val photo=source
        val fonts=PrintFonts(context)
        val roll120=identity?.cartridge(recipe.cartridge)?.roll120 ?: (recipe.cartridge.roll120==true)
        val layout=PrintLayouts.plan(photo.width,photo.height,recipe,roll120,targetLongEdge)
        val b=Bitmap.createBitmap(layout.width,layout.height,Bitmap.Config.ARGB_8888)
        try {
            val c=Canvas(b);val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val colors=recipe.paletteColors.ifEmpty {PrintPalette.extract(photo)}
            val accent=recipe.frameColor ?: colors[recipe.mood] ?: Color.GRAY
            val bg=when(recipe.border) {
                PhotoBorder.BLACK,PhotoBorder.FILM->Color.BLACK
                PhotoBorder.DUSK->0xFF3C3E40.toInt()
                PhotoBorder.MIST->0xFFCCCBC6.toInt()
                PhotoBorder.PALETTE->accent
                else->0xFFFAF8F0.toInt()
            }
            if(recipe.border!=PhotoBorder.FILM)c.drawColor(bg)
            val ink=PrintPalette.contrasting(bg)
            val unit=min(layout.width,layout.height)/1000f
            if(recipe.border in listOf(PhotoBorder.MUSEUM,PhotoBorder.SLIDE)) {
                val paper=PaperMaterial.bitmap(context)
                val shader=BitmapShader(paper,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT)
                shader.setLocalMatrix(Matrix().apply {setScale(unit*.75f,unit*.75f)})
                p.shader=shader;p.alpha=180
                c.drawRect(0f,0f,layout.width.toFloat(),layout.height.toFloat(),p)
                p.shader=null;p.alpha=255
                // A bevel lies outside the image; the photo itself is never shaded.
                val aperture=RectF(layout.photo).apply {inset(-unit*3,-unit*3)}
                p.color=0xFFC5BFAF.toInt();c.drawRect(aperture,p)
                p.color=Color.WHITE;p.strokeWidth=unit*1.4f
                c.drawLine(aperture.left,aperture.bottom,aperture.right,aperture.bottom,p)
                c.drawLine(aperture.right,aperture.top,aperture.right,aperture.bottom,p)
                if(recipe.border==PhotoBorder.SLIDE) {
                    p.style=Paint.Style.STROKE;p.strokeWidth=unit*1.8f;p.color=0xFFB9B4A8.toInt()
                    c.drawRoundRect(RectF(unit*9,unit*9,layout.width-unit*9,layout.height-unit*9),unit*10,unit*10,p)
                    p.style=Paint.Style.FILL
                    p.color=0xFFA59B88.toInt()
                    for(x in listOf(unit*23,layout.width-unit*23))c.drawCircle(x,unit*23,unit*2,p)
                }
            }
            if(recipe.border==PhotoBorder.DUSK || recipe.border==PhotoBorder.MIST) {
                PrintBackground.fill(c,photo,layout.width,layout.height)
                p.color=if(recipe.border==PhotoBorder.DUSK)0xA3181B1D.toInt() else 0x99F1F0EB.toInt()
                c.drawRect(0f,0f,layout.width.toFloat(),layout.height.toFloat(),p)
                if(recipe.border==PhotoBorder.MIST) {
                    p.color=Color.WHITE
                    val rim=max(1.5f,unit*1.5f)
                    c.drawRect(RectF(layout.photo).apply {inset(-rim,-rim)},p)
                }
            }
            if(recipe.border==PhotoBorder.TICKET) {
                // Ticket is real paper on a restrained background; scallops and punched hole are cut out visually.
                val edge=min(layout.width*.025f,layout.height*.025f)
                p.color=0xFF777A79.toInt();c.drawRect(0f,0f,layout.width.toFloat(),layout.height.toFloat(),p)
                p.color=0xFFFAF8F0.toInt();c.drawRect(edge,edge,layout.width-edge,layout.height-edge,p)
                p.color=0xFF777A79.toInt()
                val radius=layout.width*.014f;var x=edge+radius
                while(x<layout.width-edge) {c.drawCircle(x,edge,radius,p);c.drawCircle(x,layout.height-edge,radius,p);x+=radius*2.65f}
                c.drawCircle(layout.width/2f,layout.photo.top*.36f,radius*.8f,p)
            }
            if(recipe.border==PhotoBorder.INSTANT) {
                p.color=0xFFCFC9BB.toInt();p.style=Paint.Style.STROKE;p.strokeWidth=unit
                c.drawRect(unit*7,unit*7,layout.width-unit*7,layout.height-unit*7,p)
                p.style=Paint.Style.FILL
                // Sparse paper fibres, deterministic in normalized coordinates.
                p.color=0x16A99F83
                for(i in 0..42) {val y=(i*71%997)/1000f*layout.height;c.drawLine(unit*5,y,layout.width-unit*5,y+unit*1.3f,p)}
            }
            if(recipe.border==PhotoBorder.FILM) {
                val settings=(identity ?: PrintIdentity("","FILM","Untitled","",false,colors)).copy(colors=colors).cartridge(recipe.cartridge)
                val painter=FilmStripPainter(context)
                try {
                    val rail=layout.photo.height()*.14f
                    painter.strip(c,RectF(layout.photo.left,layout.photo.top-rail,layout.photo.right+layout.photo.width()*.6f,layout.photo.bottom+rail),1,settings,listOf(frame?.toString() ?: "1"),settings.film,.14f/1.28f,
                        frameWidth=layout.photo.width(),tailStart=layout.photo.right)
                    c.drawBitmap(photo,null,layout.photo,p)
                    layout.cartridge?.let {painter.cartridge(c,it,settings,castShadow=true)}
                } finally {painter.close()}
            }
            // Paper pigment alpha must never become photo opacity.
            if(recipe.border!=PhotoBorder.FILM)c.drawBitmap(photo,null,layout.photo,Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            if(recipe.showMetadata || recipe.signature.isNotBlank() || recipe.border==PhotoBorder.PALETTE) {
                val ticket=recipe.border==PhotoBorder.TICKET
                val defaultTitle=if(ticket) "Where the light lingers" else identity?.camera ?: title.substringBefore(" · ")
                val defaultSubtitle=if(ticket) identity?.subtitle ?: title else identity?.let {it.brand+" "+it.film} ?: title.substringAfter(" · ",metadata)
                val font=recipe.font ?: when(recipe.border) {
                    PhotoBorder.FILM -> PrintFont.TYPEWRITER
                    PhotoBorder.TICKET -> PrintFont.GREAT_VIBES
                    PhotoBorder.MUSEUM -> PrintFont.SERIF
                    PhotoBorder.SLIDE -> PrintFont.TYPEWRITER
                    else -> PrintFont.SPACED
                }
                val custom=PrintAssets(context).font(recipe.customFont)
                val textUnit=if(recipe.border==PhotoBorder.FILM)layout.width/1250f else unit
                // Give each line its natural height; narrow percentage boxes used to shrink the glyphs twice.
                data class Line(val value: String,val size: Float,val face: PrintFont,val spacing: Float=0f,val useCustom: Boolean=false) {
                    val height get()=size*1.5f*value.take(240).split("\n").take(3).size
                }
                val lines=mutableListOf<Line>()
                if(recipe.showMetadata) {
                    lines+=Line(recipe.titleText ?: defaultTitle,textUnit*(if(ticket)35f else 24f),font,
                        if(font==PrintFont.SPACED)textUnit*1.2f else 0f,true)
                    val subtitleFont=recipe.subtitleFont ?: if(ticket)PrintFont.SPACED else font
                    lines+=Line(recipe.subtitleText ?: defaultSubtitle,textUnit*18,subtitleFont,
                        textUnit*.6f,!ticket && recipe.subtitleFont==null)
                    lines+=Line(recipe.footerText ?: if(ticket) "A moment, kept in light." else "",textUnit*14,
                        recipe.footerFont ?: if(ticket)PrintFont.TYPEWRITER else PrintFont.SANS)
                }
                if(recipe.signature.isNotBlank())lines+=Line(recipe.signature,textUnit*20,recipe.signatureFont)
                val visible=lines.filter {it.value.isNotBlank()}
                val badge=if(recipe.border==PhotoBorder.PALETTE)PrintAssets(context).bitmap(recipe.imageKey) ?: stockBitmap(identity) else null
                try {
                    val badgeSize=if(badge!=null)unit*52 else 0f
                    val gap=textUnit*8
                    val badgeGap=if(badge!=null && visible.isNotEmpty())unit*12 else 0f
                    val naturalHeight=badgeSize+badgeGap+visible.sumOf {it.height.toDouble()}.toFloat()+gap*max(0,visible.size-1)
                    val area=PrintLayouts.captionArea(layout,recipe.border)
                    val scale=if(naturalHeight>0)min(1f,max(0f,area.height())/naturalHeight) else 1f
                    var y=area.centerY()-naturalHeight*scale/2
                    if(badge!=null) {
                        val size=badgeSize*scale
                        fitBitmap(c,badge,RectF(area.centerX()-size/2,y,area.centerX()+size/2,y+size),p)
                        y+=size+badgeGap*scale
                    }
                    for(line in visible) {
                        val height=line.height*scale
                        fonts.text(c,line.value,RectF(area.left,y,area.right,y+height),line.size*scale,line.face,ink,
                            spacing=line.spacing*scale,custom=if(line.useCustom)custom else null)
                        y+=height+gap*scale
                    }
                } finally {badge?.recycle()}
            }
            return b
        } catch(error: Throwable) {b.recycle();throw error}
    }
    private fun stockBitmap(identity: PrintIdentity?): Bitmap? {
        val id=identity?.stockId ?: return null
        val cover=context.resources.getIdentifier("film_pkg_"+id.replace('-','_'),"drawable",context.packageName)
        if(cover!=0) return BitmapFactory.decodeResource(context.resources,cover)
        val known=mapOf("kodak-gold-200" to R.drawable.film_gold_200_image2,"kodak-5207" to R.drawable.film_kodak_5207,
            "kodak-ultramax-400" to R.drawable.film_ultramax_400,"kodak-colorplus-200" to R.drawable.film_colorplus_200,
            "kodak-ektar-100" to R.drawable.film_ektar_100,"fujifilm-velvia-50" to R.drawable.film_velvia_50,
            "cinestill-800t" to R.drawable.film_cinestill_800t,"cinestill-400d" to R.drawable.film_cinestill_400d,"cinestill-50d" to R.drawable.film_cinestill_50d)
        known[id]?.let {return BitmapFactory.decodeResource(context.resources,it)}
        val resource=context.resources.getIdentifier("film_bg_"+id.replace('-','_'),"drawable",context.packageName)
        val original=if(resource!=0) BitmapFactory.decodeResource(context.resources,resource) else null
        val result=original?.copy(Bitmap.Config.ARGB_8888,true) ?: Bitmap.createBitmap(200,250,Bitmap.Config.ARGB_8888).apply {eraseColor(0xFF272A27.toInt())}
        original?.recycle()
        val c=Canvas(result);val f=PrintFonts(context);val w=result.width.toFloat();val h=result.height.toFloat()
        val ink=if(resource!=0)Color.BLACK else Color.WHITE
        f.text(c,identity.brand.uppercase(),RectF(w*.15f,h*.05f,w*.9f,h*.16f),w*.08f,PrintFont.SANS,ink)
        f.text(c,identity.film,RectF(w*.12f,h*.25f,w*.9f,h*.65f),w*.17f,PrintFont.SANS,ink)
        return result
    }
    private fun fitBitmap(c: Canvas,b: Bitmap,r: RectF,p: Paint) {
        // PNG packages and uploaded logos can contain unequal transparent margins.
        val src=visibleBounds(b)
        val scale=min(r.width()/src.width(),r.height()/src.height())
        c.drawBitmap(b,src,RectF(r.centerX()-src.width()*scale/2,r.centerY()-src.height()*scale/2,r.centerX()+src.width()*scale/2,r.centerY()+src.height()*scale/2),p)
    }
}

private object PaperMaterial {
    @Volatile private var cached: Bitmap? = null
    fun bitmap(context: Context): Bitmap = cached ?: synchronized(this) {
        cached ?: BitmapFactory.decodeResource(context.resources,R.drawable.print_cotton_paper)
            .also {cached=it}
    }
}

internal fun visibleBounds(b: Bitmap): Rect {
    val full=Rect(0,0,b.width,b.height)
    if(!b.hasAlpha())return full
    val row=IntArray(b.width);var left=b.width;var top=b.height;var right=-1;var bottom=-1
    for(y in 0 until b.height) {
        b.getPixels(row,0,b.width,0,y,b.width,1)
        row.forEachIndexed {x,color->if(Color.alpha(color)>16) {left=min(left,x);right=max(right,x);top=min(top,y);bottom=max(bottom,y)}}
    }
    return if(right<left)full else Rect(left,top,right+1,bottom+1)
}
