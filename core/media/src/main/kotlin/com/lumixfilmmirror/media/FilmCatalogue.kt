package com.lumixfilmmirror.media

import android.content.Context
import com.lumixfilmmirror.model.FilmParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class FilmCategory(val label: String) { NEGATIVE("负片"), CINEMA("电影卷"), REVERSAL("反转片"), MONOCHROME("黑白卷") }
data class FilmPreset(val id: String, val name: String, val category: FilmCategory, val printId: String, val positive: Boolean)
data class FilmRenderPlan(val parameters: FilmParameters, val preset: FilmPreset, val grainSeed: Long)

class FilmCatalogue(context: Context) {
    private val assets = context.applicationContext.assets
    suspend fun load(): List<FilmPreset> = withContext(Dispatchers.IO) {
        // Display metadata is separate from large numerical tables containing NaN.
        // The native parser owns those tables; Android's JSONObject never parses them.
        val stocks = assets.open("film/catalogue.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONArray("stocks")
        }
        (0 until stocks.length()).map { index ->
            val info = stocks.getJSONObject(index)
            FilmPreset(info.getString("id"), info.getString("name"), FilmCategory.valueOf(info.getString("category")),
                info.getString("printId"), info.getBoolean("positive"))
        }.sortedWith(compareBy({ it.id != "kodak_gold_200" }, { it.category.ordinal }, { it.name }))
    }

    fun plan(parameters: FilmParameters, presets: List<FilmPreset>, grainSeed: Long): FilmRenderPlan = FilmRenderPlan(parameters,
        requireNotNull(presets.firstOrNull { it.id == parameters.presetId }) { "所选胶卷模型不可用，请重新选卷" }, grainSeed)
}
