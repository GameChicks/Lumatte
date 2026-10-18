package com.lumixfilmmirror.storage

import com.lumixfilmmirror.model.*
import org.json.JSONArray
import org.json.JSONObject

internal object FilmBatchCodec {
    fun write(batch: FilmBatch): String = JSONObject().apply {
        put("version", 1)
        put("reference", batch.referenceId.value)
        put("mode", batch.mode.name)
        put("photos", JSONArray().apply {
            batch.photos.forEach { photo -> put(JSONObject().apply {
                put("id", photo.id.value)
                put("seed", photo.grainSeed)
                put("preset", photo.parameters.presetId)
                put("exposure", photo.parameters.exposureEv.toDouble())
                put("halation", photo.parameters.halationEnabled)
                put("diffusion", photo.parameters.diffusion.name)
            }) }
        })
    }.toString()

    fun read(text: String): FilmBatch {
        val json = JSONObject(text)
        require(json.getInt("version") == 1) { "胶片配方版本不支持" }
        val array = json.getJSONArray("photos")
        val photos = (0 until array.length()).map { index ->
            val value = array.getJSONObject(index)
            BatchPhoto(PhotoId(value.getString("id")), FilmParameters(
                presetId = FilmDefaults.currentPresetId(value.getString("preset")), exposureEv = value.getDouble("exposure").toFloat(),
                halationEnabled = value.getBoolean("halation"), diffusion = DiffusionGrade.valueOf(value.getString("diffusion"))),
                value.getLong("seed"))
        }
        return FilmBatch(photos, PhotoId(json.getString("reference")), FilmBatchMode.valueOf(json.getString("mode")))
    }
}
