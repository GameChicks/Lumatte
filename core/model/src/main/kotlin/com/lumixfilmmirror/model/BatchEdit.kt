package com.lumixfilmmirror.model

@JvmInline
value class PhotoId(val value: String)

enum class FilmBatchMode { INDIVIDUAL, FIRST_PHOTO }
enum class DiffusionGrade(val strength: Float) { OFF(0f), EIGHTH(.125f), QUARTER(.25f), HALF(.5f) }
enum class FilmGrainIntensity(val strength: Float) { LOW(.4f), MEDIUM(1f), HIGH(1.5f) }
enum class PhotoTreatment { LUT, FILM, ORIGINAL }

object FilmDefaults {
    /** Retired push models resolve to the normal stock without changing the user's exposure. */
    fun currentPresetId(id: String): String = when (id) {
        "kodak_portra_800_push1", "kodak_portra_800_push2" -> "kodak_portra_800"
        else -> id
    }
    fun halationEnabled(presetId: String): Boolean = presetId == "cinestill_800t" || presetId == "cinestill_400d"
}

/** Per-photo film and finishing controls; neutral defaults preserve previously saved recipes. */
data class FilmParameters(
    val presetId: String = "kodak_gold_200",
    val exposureEv: Float = 0f,
    val diffusion: DiffusionGrade = DiffusionGrade.OFF,
    val halationEnabled: Boolean = FilmDefaults.halationEnabled(presetId),
    val contrast: Float = 0f,
    val grainIntensity: FilmGrainIntensity = FilmGrainIntensity.MEDIUM,
    val brightness: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val temperature: Float = 0f,
) {
    init {
        require(exposureEv.isFinite() && exposureEv in -3f..3f)
        require(contrast.isFinite() && contrast in -1f..1f)
        require(listOf(brightness, highlights, shadows, saturation, vibrance, temperature).all {
            it.isFinite() && it in -1f..1f
        })
    }

    fun withPreset(id: String): FilmParameters = copy(presetId = id, halationEnabled = FilmDefaults.halationEnabled(id))
}

data class BatchPhoto(
    val id: PhotoId,
    val parameters: FilmParameters,
    val grainSeed: Long,
)

/** Independent drafts survive mode changes. The reference identity never follows display sorting. */
data class FilmBatch(
    val photos: List<BatchPhoto>,
    val referenceId: PhotoId,
    val mode: FilmBatchMode = FilmBatchMode.INDIVIDUAL,
) {
    init {
        require(photos.isNotEmpty())
        require(photos.map { it.id }.distinct().size == photos.size)
        require(photos.any { it.id == referenceId })
    }

    fun effectiveParameters(id: PhotoId): FilmParameters {
        val photo = photos.single { it.id == id }
        return when (mode) {
            FilmBatchMode.INDIVIDUAL -> photo.parameters
            FilmBatchMode.FIRST_PHOTO -> photos.single { it.id == referenceId }.parameters
        }
    }

    fun updateParameters(id: PhotoId, parameters: FilmParameters): FilmBatch {
        require(mode == FilmBatchMode.INDIVIDUAL || id == referenceId)
        require(photos.any { it.id == id })
        return copy(photos = photos.map { if (it.id == id) it.copy(parameters = parameters) else it })
    }

    fun copyReferenceToAll(): FilmBatch {
        val parameters = photos.single { it.id == referenceId }.parameters
        return copy(photos = photos.map { it.copy(parameters = parameters) })
    }

    /** Seed the chosen stock once without linking later edits or replacing independent drafts/seeds. */
    fun seedPreset(presetId:String,ids:Set<PhotoId>?=null):FilmBatch = copy(photos=photos.map { photo ->
        if(ids==null || photo.id in ids)photo.copy(parameters=photo.parameters.withPreset(presetId)) else photo
    })
}
