package com.lumixfilmmirror.media

import android.content.Context
import com.lumixfilmmirror.model.FilmParameters
import com.lumixfilmmirror.model.FilmDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class FilmCategory(val label: String) { NEGATIVE("负片"), CINEMA("电影卷"), REVERSAL("反转片"), MONOCHROME("黑白卷") }
data class FilmPreset(val id: String, val name: String, val category: FilmCategory, val printId: String,
    val positive: Boolean, val description: String? = null)
data class FilmRenderPlan(val parameters: FilmParameters, val preset: FilmPreset, val grainSeed: Long,
    val spatialOptions: FilmSpatialOptions = FilmSpatialOptions())

class FilmCatalogue(context: Context) {
    private val assets = context.applicationContext.assets
    suspend fun load(): List<FilmPreset> = withContext(Dispatchers.IO) {
        // Display metadata stays lightweight. The mobile library loads only the selected
        // model's separately hashed, finite float tables; reference profiles are not parsed here.
        val presets = listOf("film/catalogue.json", "film/mobile-fotufilm-v1/catalogue.json").flatMap { path ->
            val stocks = assets.open(path).bufferedReader().use { JSONObject(it.readText()).getJSONArray("stocks") }
            (0 until stocks.length()).map { index ->
                val info = stocks.getJSONObject(index)
                FilmPreset(info.getString("id"), info.getString("name"), FilmCategory.valueOf(info.getString("category")),
                    info.getString("printId"), info.getBoolean("positive"),
                    info.optString("description").takeIf { it.isNotBlank() })
            }
        }
        require(presets.map { it.id }.distinct().size == presets.size) { "胶片目录含重复标识" }
        presets.sortedWith(compareBy({ it.id != "kodak_gold_200" }, { it.category.ordinal }, { it.name }))
    }

    fun plan(parameters: FilmParameters, presets: List<FilmPreset>, grainSeed: Long,
        spatialOptions: FilmSpatialOptions = FilmSpatialOptions()): FilmRenderPlan {
        val current = parameters.copy(presetId = FilmDefaults.currentPresetId(parameters.presetId))
        return FilmRenderPlan(current,
            requireNotNull(presets.firstOrNull { it.id == current.presetId }
                ?: legacySilver.firstOrNull { it.id == current.presetId }) { "所选胶卷模型不可用，请重新选卷" }, grainSeed, spatialOptions)
    }

    private val legacySilver = listOf(
        FilmPreset("lfm_silver_neutral", "Silver · Neutral", FilmCategory.MONOCHROME, "lfm_silver_paper", false),
        FilmPreset("lfm_silver_contrast", "Silver · Contrast", FilmCategory.MONOCHROME, "lfm_silver_paper", false),
    ) // Saved recipes remain renderable; new selection shows the four named films.
}
