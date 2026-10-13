package com.lumixfilmmirror.media

enum class FilmHalationAppearance { RED_LAYER_RETURN, STOCK_RETURN }

/** Backend look selection. The ordinary UI retains its single Halation switch. */
data class FilmSpatialOptions(val halationAppearance: FilmHalationAppearance = FilmHalationAppearance.RED_LAYER_RETURN)

/** Algorithm identification for frontends/diagnostics; not a measured quality or speed claim. */
data class FilmSpatialEffectsInfo(
    val version: String = VERSION,
    val halationModel: String = "normalised-three-scale-film-return",
    val diffusionModel: String = "absorbing-three-scale-black-mist",
    val analysisLongEdge: Int = 512,
    val calibration: String = "project-approximation",
    val defaultHalationAppearance: FilmHalationAppearance = FilmHalationAppearance.RED_LAYER_RETURN,
) {
    companion object { const val VERSION = "lfm-optical-spatial-v2" }
}
