package com.lumixfilmmirror.storage

import com.lumixfilmmirror.model.*
import org.json.JSONObject

/** Effective per-photo parameters are frozen; recovery never re-reads a changed batch draft. */
internal object PhotoRecipeCodec {
    fun write(recipe: PhotoRecipe): String = JSONObject().apply {
        put("version", 1)
        when (recipe) {
            PhotoRecipe.Original -> put("type", "ORIGINAL")
            is PhotoRecipe.Lut -> {
                put("type", "LUT")
                fun slot(name: String, slot: LutSlot) { put(name, JSONObject().apply {
                    put("id", slot.id ?: JSONObject.NULL); put("strength", slot.strength.toDouble())
                }) }
                slot("first", recipe.parameters.first); slot("second", recipe.parameters.second)
                put("grain", recipe.parameters.grain.toDouble())
                put("grainEnabled", recipe.parameters.grainEnabled); put("ditheringEnabled", recipe.parameters.ditheringEnabled)
            }
            is PhotoRecipe.Film -> {
                put("type", "FILM"); put("preset", recipe.parameters.presetId)
                put("exposure", recipe.parameters.exposureEv.toDouble())
                put("halation", recipe.parameters.halationEnabled)
                put("diffusion", recipe.parameters.diffusion.name); put("seed", recipe.grainSeed)
            }
        }
    }.toString()

    fun read(text: String): PhotoRecipe {
        val json = JSONObject(text)
        require(json.getInt("version") == 1) { "导出配方版本不支持" }
        return when (json.getString("type")) {
            "ORIGINAL" -> PhotoRecipe.Original
            "LUT" -> {
                fun slot(name: String): LutSlot {
                    val value = json.getJSONObject(name)
                    return LutSlot(if (value.isNull("id")) null else value.getString("id"), value.getDouble("strength").toFloat())
                }
                PhotoRecipe.Lut(LutRecipe(slot("first"), slot("second"), json.getDouble("grain").toFloat(),
                    json.optBoolean("grainEnabled", true), json.optBoolean("ditheringEnabled", false)))
            }
            "FILM" -> PhotoRecipe.Film(FilmParameters(FilmDefaults.currentPresetId(json.getString("preset")), json.getDouble("exposure").toFloat(),
                DiffusionGrade.valueOf(json.getString("diffusion")), json.getBoolean("halation")), json.getLong("seed"))
            else -> error("导出配方类型不支持")
        }
    }
}
