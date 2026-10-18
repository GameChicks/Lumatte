package com.lumixfilmmirror.model

/** UI-independent requests. Original comparison never alters the saved edit draft. */
sealed interface PhotoRecipe {
    data object Original : PhotoRecipe
    data class Lut(val parameters: LutRecipe) : PhotoRecipe
    data class Film(val parameters: FilmParameters, val grainSeed: Long) : PhotoRecipe
}

enum class PhotoProcessingStage { PREPARING, RENDERING, FRAMING, ENCODING, PUBLISHING }
