package com.lumixfilmmirror.media

import org.json.JSONArray
import org.json.JSONObject

/** Only recovered response shapes; missing roots never masquerade as a successful empty page. */
internal object LumixLabJson {
    fun genres(root: JSONObject): List<LumixLabGenre> = root.array("genres", 256).objects().map { genre ->
        LumixLabGenre(genre.required("code", 256), genre.text("title", 1024)?.takeIf { it.isNotBlank() }
            ?: when (val code = genre.required("code", 256)) { "movie" -> "电影"; "still" -> "摄影"; else -> code },
            genre.image("thumbnail"), genre.optionalArray("top", 64)?.objects()?.map { top ->
                LumixLabGenreTop(top.text("region", 256), top.image("thumbnail"))
            }.orEmpty())
    }

    fun luts(root: JSONObject): List<LumixLabLut> = root.array("filter", 256).objects().map(::lut)

    fun detail(root: JSONObject): LumixLabLutDetail {
        val files = root.obj("file")
        return LumixLabLutDetail(lut(root), root.text("overview", 32 * 1024),
            root.text("photo_style", 256), root.text("_locale", 256),
            files?.text("grid17", 4096), files?.text("grid33", 4096))
    }

    private fun lut(value: JSONObject) = LumixLabLut(value.required("code", 256),
        value.required("title", 1024), value.obj("creator")?.let { creator ->
            LumixLabCreator(creator.text("code", 256), creator.text("name", 1024), creator.image("icon"))
        }, value.image("thumbnail"), value.number("download_number"))

    private fun JSONObject.image(key: String): LumixLabImageRef? = obj(key)?.let { image ->
        val path = image.text("path", 4096)
        if (path.isNullOrBlank()) null else LumixLabImageRef(path, image.text("path_original", 4096),
            image.number("width")?.takeIf { it > 0 }, image.number("height")?.takeIf { it > 0 })
    }

    private fun JSONObject.required(key: String, limit: Int): String =
        text(key, limit)?.takeIf { it.isNotBlank() } ?: invalid()

    private fun JSONObject.text(key: String, limit: Int): String? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key) as? String ?: invalid()
        if (value.length > limit) invalid()
        return value
    }

    // Recovered DTOs disagree between String and int for image dimensions. Preserve their integer meaning.
    private fun JSONObject.number(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val number = when (val raw = opt(key)) {
            is Int -> raw
            is Long -> raw.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: invalid()
            is String -> raw.toIntOrNull() ?: invalid()
            else -> invalid()
        }
        if (number < 0) invalid()
        return number
    }

    private fun JSONObject.obj(key: String): JSONObject? {
        if (!has(key) || isNull(key)) return null
        return opt(key) as? JSONObject ?: invalid()
    }

    private fun JSONObject.optionalArray(key: String, maximum: Int): JSONArray? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key) as? JSONArray ?: invalid()
        if (value.length() > maximum) invalid()
        return value
    }

    private fun JSONObject.array(key: String, maximum: Int): JSONArray = optionalArray(key, maximum) ?: invalid()
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { opt(it) as? JSONObject ?: invalid() }
    private fun invalid(): Nothing = throw LumixLabProtocolException("Lumix Lab 目录字段不符合已知格式")
}
