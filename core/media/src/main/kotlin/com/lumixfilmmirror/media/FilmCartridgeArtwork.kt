package com.lumixfilmmirror.media

/** One artwork catalogue shared by the film picker and export frames.
 * Push variants keep the original physical stock appearance. Resource bytes are unchanged.
 */
object FilmCartridgeArtwork {
    fun resource(stockId: String): Int? = when (stockId.removeSuffix("_push1").removeSuffix("_push2")) {
        "kodak_gold_200" -> R.drawable.cartridge_gold_200_v2
        "fujifilm_c200" -> R.drawable.cartridge_c200_v1
        "fujifilm_velvia_100" -> R.drawable.cartridge_velvia_100_v2
        "kodak_vision3_500t" -> R.drawable.cartridge_vision3_500t_v2
        "lfm_silver_neutral" -> R.drawable.cartridge_silver_neutral_v1
        "lfm_silver_contrast" -> R.drawable.cartridge_silver_contrast_v1
        "fujifilm_pro_400h" -> R.drawable.cartridge_fujifilm_pro_400h_v1
        "fujifilm_provia_100f" -> R.drawable.cartridge_fujifilm_provia_100f_v1
        "fujifilm_xtra_400" -> R.drawable.cartridge_fujifilm_xtra_400_v1
        "kodak_ektachrome_100" -> R.drawable.cartridge_kodak_ektachrome_100_v1
        "kodak_ektar_100" -> R.drawable.cartridge_kodak_ektar_100_v1
        "kodak_kodachrome_64" -> R.drawable.cartridge_kodak_kodachrome_64_v1
        "kodak_portra_160" -> R.drawable.cartridge_kodak_portra_160_v1
        "kodak_portra_400" -> R.drawable.cartridge_kodak_portra_400_v1
        "kodak_portra_800" -> R.drawable.cartridge_kodak_portra_800_v1
        "kodak_ultramax_400" -> R.drawable.cartridge_kodak_ultramax_400_v1
        "kodak_verita_200d" -> R.drawable.cartridge_kodak_verita_200d_v1
        "kodak_vision3_200t" -> R.drawable.cartridge_kodak_vision3_200t_v1
        "kodak_vision3_250d" -> R.drawable.cartridge_kodak_vision3_250d_v1
        "kodak_vision3_50d" -> R.drawable.cartridge_kodak_vision3_50d_v1
        "cinestill_800t" -> R.drawable.cartridge_cinestill_800t_v1
        "cinestill_400d" -> R.drawable.cartridge_cinestill_400d_v1
        "fujifilm_acros_100_ii" -> R.drawable.cartridge_fujifilm_acros_100_ii_v1
        "kodak_tri_x_400" -> R.drawable.cartridge_kodak_tri_x_400_v1
        "ilford_hp5_plus_400" -> R.drawable.cartridge_ilford_hp5_plus_400_v1
        "ilford_delta_3200" -> R.drawable.cartridge_ilford_delta_3200_v1
        else -> null
    }
}
