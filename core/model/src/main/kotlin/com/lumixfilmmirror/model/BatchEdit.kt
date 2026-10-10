package com.lumixfilmmirror.model

@JvmInline
value class PhotoId(val value: String)

enum class FilmBatchMode { INDIVIDUAL, FIRST_PHOTO }

/** Values use normalized effect strength; algorithm-specific mappings belong to the engine. */
data class FilmParameters(
    val presetId: String,
    val exposureEv: Float = 0f,
    val halation: Float = 0f,
    val bloom: Float = 0f,
    val grain: Float = 0f,
)

data class BatchPhoto(
    val id: PhotoId,
    val parameters: FilmParameters,
    val grainSeed: Long,
)

/** Independent drafts survive mode changes. The reference identity never follows display sorting. */
data class FilmBatch(
    val photos: List<BatchPhoto>,
    val referenceId: PhotoId,
    val mode: FilmBatchMode = FilmBatchMode.FIRST_PHOTO,
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
}
