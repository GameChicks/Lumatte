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
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import org.json.JSONObject
import java.util.UUID

internal enum class WidgetStyle(val title: String) { SOFT("柔光胶卷"), BANDS("分层胶卷"), CAMERA("相机连接") }
internal data class WidgetPalette(val title: String, val hex: String)
internal val widgetPalettes = listOf(WidgetPalette("海蓝", "4EADD8"), WidgetPalette("日落", "F2AB54"),
    WidgetPalette("陶粉", "C37F88"), WidgetPalette("苔金", "A6AB68"), WidgetPalette("鸢尾", "8C87BE"), WidgetPalette("赤陶", "C36E4D"))

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
            setTextColor(R.id.widget_title,Color.WHITE)
            setTextColor(R.id.widget_footer,if(config.style==WidgetStyle.BANDS)Color.rgb(111,78,58) else Color.rgb(221,238,246))
            setContentDescription(R.id.widget_root,config.title+"，"+if(config.style==WidgetStyle.CAMERA)"打开相机连接" else "导入照片并使用此胶卷")
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
        val bitmap=background(config,(width*ratio).toInt().coerceAtLeast(100),(height*ratio).toInt().coerceAtLeast(100))
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
    fun background(config: MirrorWidgetConfig,width: Int=480,height: Int=480): Bitmap {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap); val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val color=Color.parseColor("#${config.hex}")
        val hsv=FloatArray(3);Color.colorToHSV(color,hsv)
        fun shade(h: Float,s: Float,v: Float)=Color.HSVToColor(floatArrayOf((h+360f)%360f,s.coerceIn(0f,1f),v.coerceIn(0f,1f)))
        val top=shade(hsv[0],hsv[1]*.70f,.94f)
        val pale=shade(hsv[0],hsv[1]*.23f,.91f)
        val deep=shade(hsv[0]+7f,.82f,.25f)
        val colors=if(config.style==WidgetStyle.CAMERA) intArrayOf(shade(hsv[0]-.5f,.60f,1f),color,shade(hsv[0]+160f,.65f,.38f),shade(hsv[0]+160f,.44f,.70f))
            else intArrayOf(color,top,pale,deep)
        paint.shader=LinearGradient(0f,0f,0f,height.toFloat(),colors,
            if(config.style==WidgetStyle.CAMERA)floatArrayOf(0f,.40f,.72f,1f) else floatArrayOf(0f,.26f,.49f,1f),Shader.TileMode.CLAMP)
        val round=RectF(0f,0f,width.toFloat(),height.toFloat())
        val radius=minOf(width,height)*.20f
        canvas.drawRoundRect(round,radius,radius,paint)
        val clip=android.graphics.Path().apply { addRoundRect(round,radius,radius,android.graphics.Path.Direction.CW) }
        canvas.save();canvas.clipPath(clip)
        if(config.style==WidgetStyle.BANDS) {
            paint.shader=LinearGradient(0f,height*.48f,0f,height*.74f,intArrayOf(deep,color),null,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,height*.49f,width.toFloat(),height*.75f,paint)
            paint.shader=LinearGradient(0f,height*.75f,0f,height.toFloat(),intArrayOf(0xFFF8EADF.toInt(),0xFFF4DFC0.toInt()),null,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,height*.75f,width.toFloat(),height.toFloat(),paint)
        } else {
            paint.shader=RadialGradient(width*.4f,height*.02f,width*.82f,intArrayOf(0x44FFFFFF,Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            canvas.drawRect(round,paint)
        }
        canvas.restore()
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
