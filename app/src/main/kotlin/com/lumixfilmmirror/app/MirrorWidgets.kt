package com.lumixfilmmirror.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.text.StaticLayout
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import org.json.JSONObject
import java.util.UUID

internal enum class WidgetStyle(val title: String) { SOFT("柔光胶卷"), BANDS("分层胶卷"), CAMERA("相机连接") }
internal data class WidgetPalette(val title: String, val hex: String)
// Keep saved hex keys stable. Colours are designed as a complete set, not hue rotations.
internal val widgetPalettes = listOf(WidgetPalette("海蓝", "4EADD8"), WidgetPalette("日落", "F2AB54"),
    WidgetPalette("樱粉", "C37F88"), WidgetPalette("麦苔", "A6AB68"), WidgetPalette("雾蓝", "8C87BE"), WidgetPalette("燕麦", "C36E4D"))

internal object WidgetArtwork {
    private fun colors(vararg hex: String) = hex.map { Color.parseColor("#$it") }.toIntArray()
    // Source links and original Color Hunt swatches are recorded in docs/design/widgets.md.
    fun ramp(hex: String): IntArray = when(hex.uppercase()) {
        "F2AB54" -> colors("FFD36D", "FF971E", "F34E26", "304D61", "5BA9BC")
        "C37F88" -> colors("FFCED8", "FFDDDD", "FFF4DE", "FFE8C4")
        "A6AB68" -> colors("D9D28B", "B2BD7E", "8E9162", "6A795D")
        "8C87BE" -> colors("CADCEC", "D8DDED", "DCD6E7", "C7BBD7")
        "C36E4D" -> colors("FDF6ED", "EEE5D9", "DCCFC0", "C9BBAA")
        else -> {
            val base=Color.parseColor("#$hex")
            intArrayOf(androidx.core.graphics.ColorUtils.blendARGB(base,Color.WHITE,.45f),
                androidx.core.graphics.ColorUtils.blendARGB(base,Color.WHITE,.18f),
                androidx.core.graphics.ColorUtils.blendARGB(base,Color.BLACK,.12f))
        }
    }
    fun stops(size: Int): FloatArray? = when(size) {
        5 -> floatArrayOf(0f,.24f,.43f,.64f,1f)
        4 -> floatArrayOf(0f,.34f,.66f,1f)
        else -> null
    }
    private fun ink(background: Int): Int = listOf(0xFFFFF9F0.toInt(),0xFF15212A.toInt())
        .maxBy { androidx.core.graphics.ColorUtils.calculateContrast(it,background) }
    fun titleInk(config: MirrorWidgetConfig) = if(config.style==WidgetStyle.CAMERA && config.hex.equals("F2AB54",true))
        0xFFFFF59A.toInt() else if(config.hex.equals("4EADD8",true))Color.WHITE else ink(ramp(config.hex).first())
    fun footerInk(config: MirrorWidgetConfig) = when {
        config.style==WidgetStyle.CAMERA && config.hex.equals("F2AB54",true) -> 0xFF184D65.toInt()
        config.style==WidgetStyle.BANDS -> 0xFF49392F.toInt()
        config.hex.equals("4EADD8",true) -> 0xFFDDEEF6.toInt()
        else -> ink(ramp(config.hex).last())
    }
    /** Same bitmap lettering in configuration, pin preview and launcher; no layout drift. */
    fun lettering(canvas: Canvas,config: MirrorWidgetConfig,width: Int,height: Int,fontScale: Float) {
        val camera=config.style==WidgetStyle.CAMERA
        val edge=width*.11f
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface=Typeface.create("sans-serif",if(camera)Typeface.NORMAL else Typeface.BOLD)
            color=titleInk(config)
            textSize=minOf(width*.088f,height*.13f)*fontScale.coerceIn(1f,1.3f)
        }
        // Film widgets retain a separate 48dp edit target; camera uses launcher/app reconfiguration.
        val titleWidth=width-edge*2-if(camera)0f else width*.10f
        val title=StaticLayout.Builder.obtain(config.title,0,config.title.length,paint,titleWidth.toInt().coerceAtLeast(1))
            .setIncludePad(false).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
        canvas.save();canvas.translate(edge,height*.12f);title.draw(canvas);canvas.restore()
        paint.typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        paint.color=footerInk(config)
        paint.textSize=minOf(width*.066f,height*.10f)*fontScale.coerceIn(1f,1.3f)
        val footer=TextUtils.ellipsize(config.footer,paint,width-edge*2,TextUtils.TruncateAt.END).toString()
        canvas.drawText(footer,width-edge-paint.measureText(footer),height*.88f-paint.fontMetrics.bottom,paint)
    }
}

internal data class MirrorWidgetConfig(val style: WidgetStyle, val filmId: String = "kodak_gold_200",
    val cameraId: String? = null, val title: String = "Kodak Gold 200", val footer: String = "35mm ISO 200", val hex: String = "4EADD8") {
    fun json(): String = JSONObject().put("style", style.name).put("film", filmId).put("camera", cameraId)
        .put("title", title).put("footer", footer).put("hex", hex).toString()
}

internal object MirrorWidgets {
    private fun preferences(context: Context) = context.getSharedPreferences("pineframe-widgets-v1", Context.MODE_PRIVATE)
    fun defaultStyle(context: Context, id: Int) = when (AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider?.className) {
        FilmBandsWidget::class.java.name -> WidgetStyle.BANDS
        CameraConnectWidget::class.java.name -> WidgetStyle.CAMERA
        else -> WidgetStyle.SOFT
    }
    fun read(context: Context, id: Int): MirrorWidgetConfig {
        val fallback = defaultStyle(context,id)
        return runCatching {
            decode(requireNotNull(preferences(context).getString(id.toString(),null)))
        }.getOrElse { defaultConfig(fallback) }
    }
    fun defaultConfig(style: WidgetStyle) = if(style==WidgetStyle.CAMERA)
        MirrorWidgetConfig(style,title="松下相机",footer="连接相机 →",hex="F2AB54") else MirrorWidgetConfig(style)
    private fun decode(json: String): MirrorWidgetConfig {
            val item=JSONObject(json)
            return MirrorWidgetConfig(WidgetStyle.valueOf(item.getString("style")),com.lumixfilmmirror.model.FilmDefaults.currentPresetId(item.optString("film","kodak_gold_200")),
                item.optString("camera").takeIf { it.isNotBlank() && it != "null" },item.getString("title"),item.getString("footer"),
                item.getString("hex").takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) } ?: "4EADD8")
    }
    /** Pinning does not launch the configuration activity. Save a draft before asking the launcher. */
    fun requestPin(context: Context,config: MirrorWidgetConfig): Boolean {
        val manager=AppWidgetManager.getInstance(context)
        if(!manager.isRequestPinAppWidgetSupported)return false
        val token=UUID.randomUUID().toString()
        val prefs=preferences(context)
        val edit=prefs.edit()
        val now=System.currentTimeMillis()
        prefs.all.filterKeys { it.startsWith("pin:") }.forEach { (key,value) ->
            val created=runCatching { JSONObject(value as String).getLong("created") }.getOrDefault(0L)
            if(now-created>86_400_000L)edit.remove(key)
        }
        if(!edit.putString("pin:$token",JSONObject().put("created",now).put("config",config.json()).toString()).commit())return false
        val intent=Intent(context,MirrorWidgetPinnedReceiver::class.java).setAction(WIDGET_PINNED)
            .setData(Uri.parse("pineframe://widget-pin/$token"))
        // The launcher supplies EXTRA_APPWIDGET_ID after the user accepts this explicit callback.
        val mutable=if(android.os.Build.VERSION.SDK_INT>=31)PendingIntent.FLAG_MUTABLE else 0
        val callback=PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT or mutable)
        val preview=background(config)
        return try {
            val extras=Bundle().apply { putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW,contentViews(context,config,preview)) }
            manager.requestPinAppWidget(component(context,config.style),extras,callback).also { accepted ->
                if(!accepted) { prefs.edit().remove("pin:$token").apply();callback.cancel() }
            }
        } catch(error: RuntimeException) {
            prefs.edit().remove("pin:$token").apply();callback.cancel();throw error
        } finally { preview.recycle() }
    }
    fun completePin(context: Context,intent: Intent) {
        if(intent.action!=WIDGET_PINNED)return
        val token=intent.data?.takeIf { it.scheme=="pineframe" && it.host=="widget-pin" }?.lastPathSegment ?: return
        val prefs=preferences(context)
        val draft=runCatching { JSONObject(prefs.getString("pin:$token",null) ?: return) }.getOrNull() ?: return
        if(System.currentTimeMillis()-draft.optLong("created")>86_400_000L) { prefs.edit().remove("pin:$token").apply();return }
        val config=runCatching { decode(draft.getString("config")) }.getOrNull() ?: return
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        if(AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider!=component(context,config.style))return
        if(prefs.edit().putString(id.toString(),config.json()).remove("pin:$token").commit())update(context,id)
    }
    fun save(context: Context,id: Int,config: MirrorWidgetConfig): Boolean = preferences(context).edit().putString(id.toString(),config.json()).commit()
    fun delete(context: Context,id: Int) { preferences(context).edit().remove(id.toString()).apply() }
    fun restore(context: Context,oldId: Int,newId: Int) {
        preferences(context).getString(oldId.toString(),null)?.let { value ->
            preferences(context).edit().putString(newId.toString(),value).remove(oldId.toString()).apply()
        }
    }
    fun component(context: Context,style: WidgetStyle) = ComponentName(context,when(style) {
        WidgetStyle.SOFT -> FilmSoftWidget::class.java
        WidgetStyle.BANDS -> FilmBandsWidget::class.java
        WidgetStyle.CAMERA -> CameraConnectWidget::class.java
    })
    private fun contentViews(context: Context,config: MirrorWidgetConfig,bitmap: Bitmap): RemoteViews {
        val layout=when(config.style) { WidgetStyle.SOFT -> R.layout.widget_film_soft
            WidgetStyle.BANDS -> R.layout.widget_film_bands; WidgetStyle.CAMERA -> R.layout.widget_camera }
        return RemoteViews(context.packageName,layout).apply {
            setImageViewBitmap(R.id.widget_background,bitmap)
            setTextViewText(R.id.widget_title,config.title)
            setTextViewText(R.id.widget_footer,config.footer)
            setViewVisibility(R.id.widget_title,android.view.View.GONE)
            setViewVisibility(R.id.widget_footer,android.view.View.GONE)
            setViewVisibility(R.id.widget_edit,if(config.style==WidgetStyle.CAMERA)android.view.View.GONE else android.view.View.VISIBLE)
            setContentDescription(R.id.widget_root,config.title+"，"+config.footer+"，"+if(config.style==WidgetStyle.CAMERA)"打开相机连接" else "导入照片并使用此胶卷")
        }
    }
    fun update(context: Context,id: Int) {
        val manager=AppWidgetManager.getInstance(context)
        if(manager.getAppWidgetInfo(id)==null)return
        val config=read(context,id)
        val options=manager.getAppWidgetOptions(id)
        val width=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,180).coerceAtLeast(100)
        val height=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,180).coerceAtLeast(100)
        val ratio=512f/maxOf(width,height)
        val bitmap=background(config,(width*ratio).toInt().coerceAtLeast(100),(height*ratio).toInt().coerceAtLeast(100),context.resources.configuration.fontScale)
        val views=contentViews(context,config,bitmap)
        val intent=Intent(context,MainActivity::class.java).setAction(WIDGET_OPEN)
            .setData(Uri.parse("pineframe://widget/$id"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        views.setOnClickPendingIntent(R.id.widget_root,PendingIntent.getActivity(context,id,intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        val editIntent=Intent(context,MirrorWidgetConfigurationActivity::class.java)
            .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).setData(Uri.parse("pineframe://widget-edit/$id"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        views.setOnClickPendingIntent(R.id.widget_edit,PendingIntent.getActivity(context,id,editIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        manager.updateAppWidget(id,views)
        bitmap.recycle()
    }
    /** Code-native color fields; there is no photo, logo or periodic/background processing. */
    fun background(config: MirrorWidgetConfig,width: Int=480,height: Int=480,fontScale: Float=1f): Bitmap {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap); val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val color=Color.parseColor("#${config.hex}")
        val hsv=FloatArray(3);Color.colorToHSV(color,hsv)
        fun shade(h: Float,s: Float,v: Float)=Color.HSVToColor(floatArrayOf((h+360f)%360f,s.coerceIn(0f,1f),v.coerceIn(0f,1f)))
        val top=shade(hsv[0],hsv[1]*.70f,.94f)
        val pale=shade(hsv[0],hsv[1]*.23f,.91f)
        val deep=shade(hsv[0]+7f,.82f,.25f)
        val defaultBlue=config.hex.equals("4EADD8",true)
        val colors=if(defaultBlue && config.style!=WidgetStyle.CAMERA)intArrayOf(color,top,pale,deep) else WidgetArtwork.ramp(config.hex)
        paint.shader=LinearGradient(0f,0f,0f,height.toFloat(),colors,
            if(defaultBlue && config.style!=WidgetStyle.CAMERA)floatArrayOf(0f,.26f,.49f,1f) else WidgetArtwork.stops(colors.size),Shader.TileMode.CLAMP)
        val round=RectF(0f,0f,width.toFloat(),height.toFloat())
        val radius=minOf(width,height)*(if(config.style==WidgetStyle.CAMERA).17f else .20f)
        canvas.drawRoundRect(round,radius,radius,paint)
        val clip=android.graphics.Path().apply { addRoundRect(round,radius,radius,android.graphics.Path.Direction.CW) }
        canvas.save();canvas.clipPath(clip)
        if(config.style==WidgetStyle.BANDS) {
            val band=if(defaultBlue)intArrayOf(deep,color) else intArrayOf(colors[2],colors[0])
            paint.shader=LinearGradient(0f,height*.48f,0f,height*.74f,band,null,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,height*.49f,width.toFloat(),height*.75f,paint)
            paint.shader=LinearGradient(0f,height*.75f,0f,height.toFloat(),intArrayOf(0xFFF8EADF.toInt(),0xFFF4DFC0.toInt()),null,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,height*.75f,width.toFloat(),height.toFloat(),paint)
        } else {
            paint.shader=RadialGradient(width*(if(defaultBlue).4f else .5f),height*.02f,width*(if(defaultBlue).82f else .68f),intArrayOf(if(defaultBlue)0x44FFFFFF else 0x22FFFFFF,Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            canvas.drawRect(round,paint)
        }
        canvas.restore()
        WidgetArtwork.lettering(canvas,config,width,height,fontScale)
        return bitmap
    }
    const val WIDGET_OPEN="com.lumixfilmmirror.app.WIDGET_OPEN"
    const val WIDGET_PINNED="com.lumixfilmmirror.app.WIDGET_PINNED"
    const val EXTRA_STYLE="com.lumixfilmmirror.app.WIDGET_STYLE"
}

class MirrorWidgetPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) = MirrorWidgets.completePin(context,intent)
}

abstract class MirrorWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context,manager: AppWidgetManager,ids: IntArray) { ids.forEach { MirrorWidgets.update(context,it) } }
    override fun onAppWidgetOptionsChanged(context: Context,manager: AppWidgetManager,id: Int,newOptions: Bundle) { MirrorWidgets.update(context,id) }
    override fun onDeleted(context: Context,ids: IntArray) { ids.forEach { MirrorWidgets.delete(context,it) } }
    override fun onRestored(context: Context,oldIds: IntArray,newIds: IntArray) {
        oldIds.zip(newIds).forEach { (old,new) -> MirrorWidgets.restore(context,old,new);MirrorWidgets.update(context,new) }
    }
}
class FilmSoftWidget : MirrorWidgetProvider()
class FilmBandsWidget : MirrorWidgetProvider()
class CameraConnectWidget : MirrorWidgetProvider()

internal data class WidgetLaunch(val style: WidgetStyle,val filmId: String,val cameraId: String?)
internal fun widgetLaunch(context: Context,intent: Intent?): WidgetLaunch? {
    if(intent?.action!=MirrorWidgets.WIDGET_OPEN)return null
    val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
    if(AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider?.packageName!=context.packageName)return null
    val config=MirrorWidgets.read(context,id)
    return WidgetLaunch(config.style,config.filmId,config.cameraId)
}
