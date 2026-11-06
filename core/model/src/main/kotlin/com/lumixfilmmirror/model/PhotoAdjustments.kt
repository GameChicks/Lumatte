package com.lumixfilmmirror.model

/** Neutral defaults preserve existing LUT recipes; film drafts also store these per photo. */
data class PhotoAdjustments(val exposure: Float = 0f, val brightness: Float = 0f, val contrast: Float = 0f,
    val highlights: Float = 0f, val shadows: Float = 0f, val saturation: Float = 0f,
    val vibrance: Float = 0f, val temperature: Float = 0f) {
    init { require(exposure.isFinite() && exposure in -3f..3f)
        require(listOf(brightness, contrast, highlights, shadows, saturation, vibrance, temperature).all { it.isFinite() && it in -1f..1f }) }
    fun values() = floatArrayOf(exposure, brightness, contrast, highlights, shadows, saturation, vibrance, temperature)
}
fun FilmParameters.adjustments() = PhotoAdjustments(exposureEv, brightness, contrast, highlights, shadows, saturation, vibrance, temperature)
