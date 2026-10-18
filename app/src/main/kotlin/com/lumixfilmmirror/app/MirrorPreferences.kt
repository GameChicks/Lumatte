package com.lumixfilmmirror.app

import android.content.Context
import com.lumixfilmmirror.camera.CameraDevice
import com.lumixfilmmirror.media.LumixLabLut
import com.lumixfilmmirror.media.LumixLabCreator
import com.lumixfilmmirror.media.LumixLabImageRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class RememberedCamera(val id: String, val model: String, val name: String)
enum class MirrorAppearance(val title: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }
data class MirrorPersonalState(val cameras: List<RememberedCamera> = emptyList(), val favourites: Set<String> = emptySet(),
    val journal: String = "", val reducedMotion: Boolean = false, val savedLuts: List<LumixLabLut> = emptyList(),
    val revealedBatches: Set<String> = emptySet(), val appearance: MirrorAppearance = MirrorAppearance.SYSTEM,
    val haptics: Boolean = true, val automaticFilmDetails: Boolean = true, val revealAnimation: Boolean = true,
    val soundEffects: Boolean = true)

/** App-owned UI preferences only. No camera passwords, addresses or recovered app data. */
class MirrorPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("mirror-personal-v1", Context.MODE_PRIVATE)
    suspend fun read(): MirrorPersonalState = withContext(Dispatchers.IO) {
        val array = JSONArray(preferences.getString("cameras", "[]"))
        MirrorPersonalState((0 until array.length()).map { i -> array.getJSONObject(i).let {
            RememberedCamera(it.getString("id"), it.getString("model"), it.getString("name")) } },
            preferences.getStringSet("favourites", emptySet()).orEmpty().toSet(),
            preferences.getString("journal", "").orEmpty(), preferences.getBoolean("reduced-motion", false),
            JSONArray(preferences.getString("saved-luts", "[]")).let { luts -> (0 until luts.length()).map { index ->
                val item = luts.getJSONObject(index)
                fun image(key: String) = item.optString(key).takeIf { it.isNotBlank() }?.let { LumixLabImageRef(it, null, null, null) }
                LumixLabLut(item.getString("code"), item.getString("title"),
                    LumixLabCreator(item.optString("creator-code").takeIf { it.isNotBlank() },
                        item.optString("creator").takeIf { it.isNotBlank() }, image("avatar")), image("thumbnail"), null)
            } }, preferences.getStringSet("revealed-batches", emptySet()).orEmpty().toSet(),
            appearance = runCatching { MirrorAppearance.valueOf(preferences.getString("appearance", "SYSTEM").orEmpty()) }.getOrDefault(MirrorAppearance.SYSTEM),
            haptics = preferences.getBoolean("haptics", true),
            automaticFilmDetails = preferences.getBoolean("automatic-film-details", true),
            revealAnimation = preferences.getBoolean("reveal-animation", true),
            soundEffects = preferences.getBoolean("sound-effects", true))
    }
    suspend fun save(value: MirrorPersonalState) = withContext(Dispatchers.IO) {
        val cameras = JSONArray(value.cameras.map { JSONObject().put("id", it.id).put("model", it.model).put("name", it.name) })
        check(preferences.edit().putString("cameras", cameras.toString()).putStringSet("favourites", value.favourites)
            .putString("journal", value.journal).putBoolean("reduced-motion", value.reducedMotion)
            .putStringSet("revealed-batches", value.revealedBatches)
            .putString("appearance", value.appearance.name).putBoolean("haptics", value.haptics)
            .putBoolean("automatic-film-details", value.automaticFilmDetails).putBoolean("reveal-animation", value.revealAnimation)
            .putBoolean("sound-effects",value.soundEffects)
            .putString("saved-luts", JSONArray(value.savedLuts.map { item -> JSONObject().put("code", item.code)
                .put("title", item.title).put("creator", item.creator?.name.orEmpty())
                .put("creator-code", item.creator?.code.orEmpty()).put("avatar", item.creator?.icon?.path.orEmpty())
                .put("thumbnail", item.thumbnail?.path.orEmpty()) }).toString()).commit()) { "个人设置保存失败" }
    }
}

internal fun CameraDevice.rememberedIdentity(): String = udn.ifBlank { "model:$model:$friendlyName" }

/** Small metadata snapshot for restoring a detail page; the client still validates all image URLs. */
internal fun LumixLabLut.detailSnapshot(): String = JSONObject().put("code", code).put("title", title)
    .put("creator-code", creator?.code.orEmpty()).put("creator", creator?.name.orEmpty())
    .put("avatar", creator?.icon?.path.orEmpty()).put("thumbnail", thumbnail?.path.orEmpty()).toString()

internal fun restoreLutDetailSnapshot(value: String): LumixLabLut = JSONObject(value).let { item ->
    fun image(key: String) = item.optString(key).takeIf { it.isNotBlank() }?.let { LumixLabImageRef(it, null, null, null) }
    LumixLabLut(item.getString("code"), item.getString("title"),
        LumixLabCreator(item.optString("creator-code").takeIf { it.isNotBlank() },
            item.optString("creator").takeIf { it.isNotBlank() }, image("avatar")), image("thumbnail"), null)
}
