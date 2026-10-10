package app.filmcompanion.media



import android.content.Context

import android.graphics.*

import app.filmcompanion.data.ScanEntity

import kotlinx.coroutines.currentCoroutineContext

import kotlinx.coroutines.ensureActive



class ContactSheetRenderer(private val context: Context) {

    private val fonts=PrintFonts(context)

    suspend fun render(name: String, gear: String, film: String, ei: Int, scans: List<ScanEntity>, page: Int,

        total: Int, width: Int, options: ContactSheetOptions, decode: suspend (ScanEntity,Int)->Bitmap?): ContactSheetPreview {

        val rows=(scans.size+5)/6;val height=height(width,scans.size,options.style)

        require(width.toLong()*height*2<=32L*1024*1024) {"接触印样超过内存预算"}

        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.RGB_565)

        val painter=FilmStripPainter(context)

        try {

            val c=Canvas(bitmap);c.scale(width/2048f,width/2048f)

            val archive=options.style==ContactSheetStyle.ARCHIVE;val dark=options.style==ContactSheetStyle.FILM

            c.drawColor(if(dark) Color.BLACK else 0xFFFAF9F5.toInt())

            val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            val left=if(archive) 65f else 305f;val right=1990f;val cw=(right-left)/6

            val ink=if(dark) 0xFFE8E6DB.toInt() else 0xFF383832.toInt()

            val settings=options.cartridge.copy(film=options.cartridge.film.ifBlank {film},brand=options.cartridge.brand.ifBlank {"FILM"})

            fonts.text(c,gear+" / "+name+" / EI "+ei,RectF(left,32f,right,65f),20f,PrintFont.TYPEWRITER,ink,Paint.Align.LEFT)

            val missing=mutableListOf<String>()

            for(row in 0 until rows) {

                val top=103f+row*(if(archive) 276f else 322f)

                if(!archive) painter.strip(c,RectF(left,top,right,top+300f),6,settings,

                    scans.drop(row*6).take(6).map {it.frameNumber?.toString() ?: "—"},settings.film)

                for(col in 0..5) {

                    val scan=scans.getOrNull(row*6+col) ?: break

                    currentCoroutineContext().ensureActive()

                    val x=left+col*cw+4

                    val box=RectF(x,top+if(archive) 0f else 54f,x+cw-8,top+if(archive) 225f else 246f)

                    p.color=if(archive) 0xFFE9E7DE.toInt() else 0xFF111111.toInt();c.drawRect(box,p)

                    val photo=decode(scan,(width*cw/2048).toInt())

                    if(photo!=null) try {

                        val rotate=!archive && options.rotatePortraits && photo.height>photo.width

                        val pw=if(rotate)photo.height else photo.width;val ph=if(rotate)photo.width else photo.height

                        val scale=minOf(box.width()/pw,box.height()/ph)

                        c.save();c.translate(box.centerX(),box.centerY());if(rotate)c.rotate(90f)

                        c.drawBitmap(photo,null,RectF(-photo.width*scale/2,-photo.height*scale/2,photo.width*scale/2,photo.height*scale/2),p);c.restore()

                    } finally {photo.recycle()} else {

                        missing+=scan.fileName

                        fonts.text(c,"原件不可读取",box,17f,PrintFont.SANS,Color.GRAY)

                    }

                    if(archive) fonts.text(c,scan.frameNumber?.toString() ?: "—",RectF(x,top+234f,x+cw-8,top+260f),18f,PrintFont.TYPEWRITER,ink,Paint.Align.LEFT)

                }

            }

            val cartridgeBox=if(archive) null else FilmStripPainter.attachedBox(left+4,20f,421f,settings.roll120==true)

            cartridgeBox?.let {painter.cartridge(c,it,settings,castShadow=true)}

            fonts.text(c,total.toString()+" FRAMES  /  "+(page+1)+" : "+((total+23)/24),

                RectF(left,height*2048f/width-46,right,height*2048f/width-17),17f,PrintFont.TYPEWRITER,ink,Paint.Align.LEFT)

            return ContactSheetPreview(bitmap,(total+23)/24,missing,cartridgeBox?.let {RectF(it.left*width/2048,it.top*width/2048,it.right*width/2048,it.bottom*width/2048)})

        } catch(error: Throwable) {bitmap.recycle();throw error} finally {painter.close()}

    }

    companion object {

        fun height(width: Int,count: Int,style: ContactSheetStyle): Int {

            require(count in 1..24)

            return (width/2048f*(160+((count+5)/6)*(if(style==ContactSheetStyle.ARCHIVE)276 else 322))).toInt()

        }

    }

}
