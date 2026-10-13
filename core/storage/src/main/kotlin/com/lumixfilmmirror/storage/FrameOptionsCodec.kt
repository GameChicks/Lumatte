package com.lumixfilmmirror.storage

import com.lumixfilmmirror.model.*
import org.json.JSONObject

/** One versioned export frame per batch. A missing row preserves the unframed default. */
internal object FrameOptionsCodec {
    fun write(frame: FrameOptions): String = JSONObject().apply {
        put("version", 1); put("preset", frame.preset.name)
        put("border", frame.borderFraction.toDouble()); put("signature", frame.signature)
        put("metadata", frame.showPhotoMetadata); put("ratio", frame.ratio.name)
        put("showCamera", frame.showCamera); put("showLens", frame.showLens)
        put("showFocalLength", frame.showFocalLength); put("showAperture", frame.showAperture)
        put("showShutter", frame.showShutter); put("showIso", frame.showIso); put("showCapturedAt", frame.showCapturedAt)
        put("mood", frame.mood.name); put("roll120", frame.roll120)
        put("brand", frame.filmBrand); put("film", frame.filmName); put("notes", frame.filmNotes)
        fun optional(key: String, value: Any?) { put(key, value ?: JSONObject.NULL) }
        optional("color", frame.fixedColorArgb); optional("portrait", frame.portrait)
        optional("bw", frame.filmBlackWhite); optional("title", frame.title)
        optional("subtitle", frame.subtitle); optional("footer", frame.footer)
        optional("titleFont", frame.titleFont?.name); optional("subtitleFont", frame.subtitleFont?.name)
        optional("footerFont", frame.footerFont?.name); put("signatureFont", frame.signatureFont.name)
        optional("imageKey", frame.imageKey); optional("titleFontKey", frame.titleFontKey)
        optional("subtitleFontKey", frame.subtitleFontKey); optional("footerFontKey", frame.footerFontKey)
        optional("signatureFontKey", frame.signatureFontKey)
    }.toString()

    fun read(text: String): FrameOptions {
        val j = JSONObject(text)
        require(j.getInt("version") == 1) { "画框配置版本不支持" }
        fun optional(key: String): String? = if (j.isNull(key)) null else j.getString(key)
        return FrameOptions(preset = FramePreset.valueOf(j.getString("preset")),
            borderFraction = j.getDouble("border").toFloat(), signature = j.getString("signature"),
            showPhotoMetadata = j.getBoolean("metadata"),
            fixedColorArgb = if (j.isNull("color")) null else j.getInt("color"),
            ratio = FrameRatio.valueOf(j.getString("ratio")),
            title = optional("title"), subtitle = optional("subtitle"), footer = optional("footer"),
            titleFont = optional("titleFont")?.let { FrameFont.valueOf(it) },
            subtitleFont = optional("subtitleFont")?.let { FrameFont.valueOf(it) },
            footerFont = optional("footerFont")?.let { FrameFont.valueOf(it) },
            signatureFont = FrameFont.valueOf(j.getString("signatureFont")),
            filmBrand = j.getString("brand"), filmName = j.getString("film"), filmNotes = j.getString("notes"),
            roll120 = j.getBoolean("roll120"), mood = FrameMood.valueOf(j.getString("mood")),
            filmBlackWhite = if (j.isNull("bw")) null else j.getBoolean("bw"),
            portrait = if (j.isNull("portrait")) null else j.getBoolean("portrait"),
            imageKey = optional("imageKey"), titleFontKey = optional("titleFontKey"),
            subtitleFontKey = optional("subtitleFontKey"), footerFontKey = optional("footerFontKey"),
            signatureFontKey = optional("signatureFontKey"),
            showCamera = j.optBoolean("showCamera", true), showLens = j.optBoolean("showLens", true),
            showFocalLength = j.optBoolean("showFocalLength", true), showAperture = j.optBoolean("showAperture", true),
            showShutter = j.optBoolean("showShutter", true), showIso = j.optBoolean("showIso", true),
            showCapturedAt = j.optBoolean("showCapturedAt", false))
    }
}
