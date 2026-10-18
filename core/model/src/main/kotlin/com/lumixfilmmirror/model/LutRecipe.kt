package com.lumixfilmmirror.model

data class LutSlot(val id: String? = null, val strength: Float = 1f) {
    init { require(strength.isFinite() && strength in 0f..1f) }
}

/** Both slots and grain belong to the processing batch; JPEG dimensions/quality remain independent. */
data class LutRecipe(val first: LutSlot = LutSlot(), val second: LutSlot = LutSlot(), val grain: Float = 0f,
    val grainEnabled: Boolean = true, val ditheringEnabled: Boolean = true) {
    init { require(grain.isFinite() && grain in 0f..1f) }
}
