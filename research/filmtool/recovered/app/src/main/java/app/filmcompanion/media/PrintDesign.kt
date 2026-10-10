package app.filmcompanion.media



import android.content.Context

import android.graphics.*

import androidx.core.content.res.ResourcesCompat

import androidx.palette.graphics.Palette

import app.filmcompanion.R

import app.filmcompanion.data.FilmStockEntity

import org.json.JSONObject

import kotlin.math.*



enum class PaletteMood(val title: String, val english: String) {

    VIBRANT("炽烈","Vibrant"), LIGHT_VIBRANT("清曜","Light Vibrant"),

    DARK_VIBRANT("棠梨","Dark Vibrant"), MUTED("尘霭","Muted"), LIGHT_MUTED("霜月","Light Muted"), DARK_MUTED("侘寂","Dark Muted")

}

enum class PrintFont(val title: String, val resource: Int) {

    SANS("清晰无衬线",R.font.film_sans_regular), TYPEWRITER("Cutive Mono",R.font.print_typewriter),

    SCRIPT("Dancing Script",R.font.print_script), SERIF("Playfair",R.font.print_serif),

    DISPLAY("Abril",R.font.print_display), SPACED("Julius",R.font.print_spaced), GREAT_VIBES("Great Vibes",R.font.print_great_vibes),

    KUNSTLER("Kunstler Script",R.font.print_kunstler), ROSETHELLA("Rosethella",R.font.print_rosethella), HIGHWIND("Highwind",R.font.print_highwind)

}

enum class PrintRatio(val title: String, val value: Float?) {

    ORIGINAL("原比例",null), SQUARE("1:1",1f), THREE_TWO("3:2",1.5f), FOUR_THREE("4:3",4f/3),

    SEVEN_SIX("7:6",7f/6), FIVE_FOUR("5:4",1.25f), FIFTEEN_NINE("15:9",15f/9), TWO_ONE("2:1",2f)

}

data class CartridgeSettings(

    val brand: String="", val film: String="", val notes: String="NOTES", val blackWhite: Boolean?=null,

    val mood: PaletteMood=PaletteMood.VIBRANT, val color: Int?=null, val roll120: Boolean?=null

) {

    fun json()=JSONObject().put("brand",brand).put("film",film).put("notes",notes).put("bw",blackWhite ?: JSONObject.NULL)

        .put("mood",mood.name).put("color",color ?: JSONObject.NULL).put("roll120",roll120 ?: JSONObject.NULL)

    companion object {

        fun from(j: JSONObject?)=if(j==null) CartridgeSettings() else CartridgeSettings(

            j.optString("brand").take(60),j.optString("film").take(80),j.optString("notes","NOTES").take(60),

            if(j.isNull("bw")) null else j.optBoolean("bw"),enumOr(j.optString("mood"),PaletteMood.VIBRANT),

            if(j.isNull("color")) null else j.optInt("color"),if(j.isNull("roll120")) null else j.optBoolean("roll120"))

    }

}

inline fun <reified T: Enum<T>> enumOr(value: String, fallback: T): T=runCatching {enumValueOf<T>(value)}.getOrDefault(fallback)



/** Each scan contributes one vote, independent of dimensions and aspect ratio. */

object PrintPalette {

    fun extract(bitmap: Bitmap): Map<PaletteMood,Int> {

        val p=Palette.from(bitmap).generate()

        return PaletteMood.entries.associateWith { mood -> color(p,mood) }

    }

    private fun color(p: Palette,mood: PaletteMood): Int = (when(mood) {

            PaletteMood.VIBRANT -> p.vibrantSwatch

            PaletteMood.LIGHT_VIBRANT -> p.lightVibrantSwatch

            PaletteMood.DARK_VIBRANT -> p.darkVibrantSwatch

            PaletteMood.MUTED -> p.mutedSwatch

            PaletteMood.LIGHT_MUTED -> p.lightMutedSwatch

            PaletteMood.DARK_MUTED -> p.darkMutedSwatch

        })?.rgb ?: p.dominantSwatch?.rgb ?: Color.rgb(99,103,96)

    fun aggregate(images: List<Map<PaletteMood,Int>>): Map<PaletteMood,Int> = PaletteMood.entries.associateWith { mood ->

        val colors=images.mapNotNull {it[mood]}

        if(colors.isEmpty()) Color.rgb(99,103,96) else {

            val votes=Bitmap.createBitmap(colors.size,1,Bitmap.Config.ARGB_8888)

            try {

                votes.setPixels(colors.toIntArray(),0,colors.size,0,0,colors.size,1)

                color(Palette.from(votes).generate(),mood)

            } finally {votes.recycle()}

        }

    }

    fun contrasting(color: Int): Int=if(androidx.core.graphics.ColorUtils.calculateLuminance(color)>0.35) Color.rgb(20,22,21) else Color.rgb(250,248,238)

}



data class PrintIdentity(val camera: String, val brand: String, val film: String, val stockId: String,

    val blackWhite: Boolean, val colors: Map<PaletteMood,Int> = emptyMap(),val cameraFormat: String="135") {

    val subtitle get()=camera+"  /  "+brand+" "+film

    fun cartridge(settings: CartridgeSettings)=settings.copy(

        brand=settings.brand.ifBlank {brand},film=settings.film.ifBlank {film},

        blackWhite=settings.blackWhite ?: blackWhite,

        color=settings.color ?: colors[settings.mood] ?: Color.rgb(99,103,96),

        roll120=settings.roll120 ?: (cameraFormat.uppercase().replace("X","").replace("×","") in setOf("120","645","66","67","68","69")))

    companion object {

        fun stock(camera: String, stock: FilmStockEntity?,format: String="135")=PrintIdentity(camera,stock?.brand ?: "FILM",

            stock?.model ?: "Untitled",stock?.id.orEmpty(),

            stock?.filmType?.uppercase() in setOf("BLACK_WHITE","BLACK_AND_WHITE","BW","B&W") ||

                stock?.process?.uppercase()=="B&W",cameraFormat=format)

    }

}



class PrintFonts(private val context: Context) {

    fun face(font: PrintFont)=ResourcesCompat.getFont(context,font.resource) ?: Typeface.DEFAULT

    fun text(c: Canvas,value: String,box: RectF,size: Float,font: PrintFont,color: Int,align: Paint.Align=Paint.Align.CENTER,spacing: Float=0f,custom: Typeface?=null,weight: Float=0f) {

        if(value.isEmpty()) return

        val ink=color

        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {typeface=custom ?: face(font);textSize=size;this.color=ink;textAlign=Paint.Align.LEFT}

        if(weight>0f) {p.style=Paint.Style.FILL_AND_STROKE;p.strokeWidth=weight}

        val lines=value.take(240).split('\n').take(3)

        p.textSize=minOf(p.textSize,box.height().coerceAtLeast(1f)/(lines.size*1.35f))

        var gap=spacing

        val widest=lines.maxOf {p.measureText(it)+(it.length-1).coerceAtLeast(0)*gap}

        if(widest>box.width()) {val factor=box.width()/widest;p.textSize*=factor;gap*=factor}

        val lineHeight=p.textSize*1.35f

        val y=box.centerY()-(lines.size-1)*lineHeight/2-(p.ascent()+p.descent())/2

        lines.forEachIndexed {index,line ->

            val w=p.measureText(line)+(line.length-1).coerceAtLeast(0)*gap

            var x=when(align) {Paint.Align.LEFT->box.left;Paint.Align.RIGHT->box.right-w;else->box.centerX()-w/2}

            if(gap==0f) c.drawText(line,x,y+index*lineHeight,p) else line.forEach {ch ->

                val s=ch.toString();c.drawText(s,x,y+index*lineHeight,p);x+=p.measureText(s)+gap

            }

        }

    }

}



class ContactDesignStore(context: Context) {

    private val prefs=context.getSharedPreferences("contact-design-v1",Context.MODE_PRIVATE)

    fun load(id: String)=runCatching {ContactSheetOptions.from(JSONObject(prefs.getString(id,null) ?: return ContactSheetOptions()))}.getOrDefault(ContactSheetOptions())

    fun save(id: String, options: ContactSheetOptions) {check(prefs.edit().putString(id,options.json().toString()).commit())}

    fun backup(ids: Set<String>)=JSONObject().apply {prefs.all.forEach {(k,v)->if(k in ids && v is String) put(k,JSONObject(v))}}

    fun restore(j: JSONObject, ids: Set<String>) {val e=prefs.edit().clear();j.keys().forEach {if(it in ids)e.putString(it,j.getJSONObject(it).toString())};check(e.commit())}

}
