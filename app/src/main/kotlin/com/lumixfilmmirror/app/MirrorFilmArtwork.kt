package com.lumixfilmmirror.app

import com.lumixfilmmirror.media.FilmPreset

/** Reuses the recovered label originals, never the reference screen/mockup as a UI bitmap. */
private val labelAssets = mapOf(
    "kodak_gold_200" to "film_gold_200_image2.png", "kodak_ektar_100" to "film_ektar_100.png",
    "kodak_ultramax_400" to "film_ultramax_400.png", "fujifilm_c200" to "film_pkg_fuji_c200.png",
    "fujifilm_provia_100f" to "film_pkg_fuji_provia100f.png", "fujifilm_velvia_100" to "film_pkg_fuji_velvia100.png",
    "fujifilm_xtra_400" to "film_pkg_fuji_superia400.png", "kodak_ektachrome_100" to "film_pkg_kodak_e100.png",
    "kodak_vision3_50d" to "film_pkg_kodak_5203.png", "kodak_vision3_200t" to "film_pkg_kodak_5213.png",
    "kodak_vision3_500t" to "film_pkg_kodak_5219.png")
internal fun FilmPreset.shortTitle() = name.removePrefix("Kodak ").removePrefix("Fujifilm ")
    .removePrefix("Ilford ")

internal data class FilmEditorialName(val title: String, val speed: String?, val variant: String?)

/** Processing variants are not a different physical cartridge or an extra ISO number. */
internal fun FilmPreset.editorialName(): FilmEditorialName {
    val raw = shortTitle()
    if (id == "fujifilm_acros_100_ii") return FilmEditorialName("Acros", "100", "II")
    if (id.startsWith("lfm_silver_")) return FilmEditorialName("Silver", null, raw.substringAfter(" · ", "").ifBlank { null })
    val push = Regex("\\s*\\(Push ([12])\\)$").find(raw)
    val stock = if (push == null) raw else raw.substring(0, push.range.first)
    val speed = Regex("(?:^| )([0-9]{2,4}[A-Za-z]*)$").find(stock)?.groupValues?.get(1)
    return FilmEditorialName(if (speed == null) stock else stock.removeSuffix(speed).trim(), speed,
        push?.groupValues?.get(1)?.let { "推片 +$it 档" })
}


internal fun recoveredFilmLabel(id: String): String? = labelAssets[id]
