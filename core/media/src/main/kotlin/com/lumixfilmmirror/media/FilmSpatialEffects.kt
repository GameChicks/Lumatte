package com.lumixfilmmirror.media

enum class FilmHalationAppearance { RED_LAYER_RETURN, STOCK_RETURN }

/** Compatibility API. Artistic RED overrides no longer affect ordinary stock profiles. */
data class FilmSpatialOptions(val halationAppearance: FilmHalationAppearance = FilmHalationAppearance.STOCK_RETURN)

/** Algorithm identification for frontends/diagnostics; not a measured quality or speed claim. */
data class FilmSpatialEffectsInfo(
    val version: String = VERSION,
    val halationModel: String = "spektrafilm-default-bounces-and-cinestill-mobile-look",
    val diffusionModel: String = "absorbing-three-scale-black-mist",
    val analysisLongEdge: Int = 512,
    val cineStillAnalysisLongEdge: Int = 1024,
    val calibration: String = "project-approximation",
    val defaultHalationAppearance: FilmHalationAppearance = FilmHalationAppearance.STOCK_RETURN,
) {
    companion object { const val VERSION = "lfm-optical-spatial-v3-stock-and-cinestill" }
}
