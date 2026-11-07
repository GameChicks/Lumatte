package com.lumixfilmmirror.media

enum class FilmHalationAppearance { RED_LAYER_RETURN, STOCK_RETURN }

/** Compatibility API. Artistic RED overrides no longer affect ordinary stock profiles. */
data class FilmSpatialOptions(val halationAppearance: FilmHalationAppearance = FilmHalationAppearance.STOCK_RETURN)

/** Algorithm identification for frontends/diagnostics; not a measured quality or speed claim. */
data class FilmSpatialEffectsInfo(
    val version: String = VERSION,
    val halationModel: String = "ordinary-stock-and-spektralab-base-reflection",
    val diffusionModel: String = "absorbing-three-scale-black-mist",
    val analysisLongEdge: Int = 512,
    /** Zero means the complete current render grid; no fixed CineStill analysis cap. */
    val cineStillAnalysisLongEdge: Int = 0,
    val calibration: String = "project-approximation",
    val defaultHalationAppearance: FilmHalationAppearance = FilmHalationAppearance.STOCK_RETURN,
) {
    companion object { const val VERSION = "lfm-optical-spatial-v5-spektralab-source-adjustments" }
}
