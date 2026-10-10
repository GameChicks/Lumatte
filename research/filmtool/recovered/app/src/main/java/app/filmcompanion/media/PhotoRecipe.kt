package app.filmcompanion.media



import android.content.Context

import org.json.JSONObject

import java.io.File



enum class PhotoBorder(val title: String) {

    NONE("无边框"), PAPER("白色相纸"), BLACK("黑色画廊"), FILM("胶片暗盒"),

    TICKET("记忆票根"), INSTANT("拍立得"), DUSK("暮色"), MIST("雾面"), PALETTE("取色相框"),

    MUSEUM("典藏纸框"), SLIDE("幻灯片夹");



    companion object {

        // Retired style keeps existing text, font, colour, ratio and decoration choices.

        fun fromName(name: String): PhotoBorder = if(name == "EDITORIAL") PAPER

            else entries.firstOrNull { it.name == name } ?: NONE

    }

}

data class PhotoRecipe(val border: PhotoBorder = PhotoBorder.NONE, val borderWidth: Float = 0.045f,

    val signature: String = "", val showMetadata: Boolean = true,

    val ratio: PrintRatio=PrintRatio.ORIGINAL, val portrait: Boolean=true,

    val titleText: String?=null, val subtitleText: String?=null, val footerText: String?=null,

    val font: PrintFont?=null, val customFont: String?=null, val customFontName: String?=null,

    val mood: PaletteMood=PaletteMood.VIBRANT, val frameColor: Int?=null,

    val imageKey: String?=null, val cartridge: CartridgeSettings=CartridgeSettings(),

    val paletteColors: Map<PaletteMood,Int> = emptyMap(), val signatureFont: PrintFont=PrintFont.SCRIPT,

    val subtitleFont: PrintFont?=null,val footerFont: PrintFont?=null)



/** Reversible edits are stored separately from original files and the Room schema. */

class PhotoRecipeStore(private val context: Context) {

    private val preferences = context.getSharedPreferences("photo-recipes-v1", Context.MODE_PRIVATE)

    fun contains(id: String): Boolean = preferences.contains(id)

    fun load(id: String): PhotoRecipe = runCatching {

        val root = JSONObject(preferences.getString(id, null) ?: return PhotoRecipe())

        PhotoRecipe(

            PhotoBorder.fromName(root.optString("border","NONE")), root.optDouble("borderWidth",0.045).toFloat(),

            root.optString("signature"), root.optBoolean("metadata",true),

            enumOr(root.optString("ratio"),PrintRatio.ORIGINAL),root.optBoolean("portrait",true),

            root.stringOrNull("title"),root.stringOrNull("subtitle"),root.stringOrNull("footer"),

            root.stringOrNull("font")?.let {enumOr(it,PrintFont.SPACED)},root.stringOrNull("customFont"),root.stringOrNull("customFontName"),

            enumOr(root.optString("mood"),PaletteMood.VIBRANT),if(root.isNull("frameColor")) null else root.optInt("frameColor"),

            root.stringOrNull("imageKey"),CartridgeSettings.from(root.optJSONObject("cartridge")),

            root.optJSONObject("palette")?.let {j -> PaletteMood.entries.mapNotNull {m -> if(j.has(m.name)) m to j.getInt(m.name) else null}.toMap()} ?: emptyMap(),

            enumOr(root.optString("signatureFont"),PrintFont.SCRIPT),

            root.stringOrNull("subtitleFont")?.let {enumOr(it,PrintFont.SPACED)},root.stringOrNull("footerFont")?.let {enumOr(it,PrintFont.SANS)})

    }.getOrDefault(PhotoRecipe())

    fun save(id: String, recipe: PhotoRecipe) {

        val root = JSONObject()

            .put("border",recipe.border.name).put("borderWidth",recipe.borderWidth)

            .put("signature",recipe.signature.take(100)).put("metadata",recipe.showMetadata)

            .put("signatureFont",recipe.signatureFont.name)

            .put("subtitleFont",recipe.subtitleFont?.name ?: JSONObject.NULL).put("footerFont",recipe.footerFont?.name ?: JSONObject.NULL)

            .put("ratio",recipe.ratio.name).put("portrait",recipe.portrait)

            .put("title",recipe.titleText?.take(240) ?: JSONObject.NULL).put("subtitle",recipe.subtitleText?.take(240) ?: JSONObject.NULL)

            .put("footer",recipe.footerText?.take(240) ?: JSONObject.NULL).put("font",recipe.font?.name ?: JSONObject.NULL)

            .put("customFont",recipe.customFont ?: JSONObject.NULL).put("customFontName",recipe.customFontName ?: JSONObject.NULL)

            .put("mood",recipe.mood.name).put("frameColor",recipe.frameColor ?: JSONObject.NULL)

            .put("imageKey",recipe.imageKey ?: JSONObject.NULL).put("cartridge",recipe.cartridge.json())

            .put("palette",JSONObject().apply {recipe.paletteColors.forEach {(m,c)->put(m.name,c)}})

        check(preferences.edit().putString(id,root.toString()).commit()) { "无法保存编辑参数" }

    }

    fun remove(ids: List<String>) {

        if(ids.isEmpty()) return

        val editor = preferences.edit()

        ids.forEach { editor.remove(it) }

        check(editor.commit()) { "无法清理编辑参数" }

        ids.forEach { id ->

            val preview = previewFile(id)

            listOf(preview, File(context.filesDir,"photo-previews-v2/${preview.name}")).forEach { cached ->

                check(!cached.exists() || cached.delete()) { "无法清理成片预览" }

            }

        }

    }

    // A new cache namespace prevents old converted thumbnails from resurfacing.

    fun previewFile(id: String): File {

        val key=java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }

        return File(context.filesDir,"photo-previews-v3").apply { mkdirs() }.let { File(it,"$key.jpg") }

    }

    private fun withoutRetiredFields(root: JSONObject): JSONObject = root.apply {

        remove("negative")

        if(optString("border") == "EDITORIAL") put("border", PhotoBorder.PAPER.name)

    }

    fun backup(ids: Set<String>): JSONObject = JSONObject().apply {

        preferences.all.forEach { (id,value) -> if(id in ids && value is String) put(id,withoutRetiredFields(JSONObject(value))) }

    }

    fun restore(payload: JSONObject, ids: Set<String>) {

        val editor=preferences.edit().clear()

        payload.keys().forEach { id -> if(id in ids) editor.putString(id,withoutRetiredFields(JSONObject(payload.getJSONObject(id).toString())).toString()) }

        check(editor.commit()) { "无法恢复照片编辑参数" }

        File(context.filesDir,"photo-previews-v3").listFiles()?.filter { it.isFile }?.forEach { it.delete() }

    }

}

private fun JSONObject.stringOrNull(key: String)=if(isNull(key)) null else optString(key)
