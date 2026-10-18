package com.lumixfilmmirror.model

import kotlin.math.roundToInt

data class PixelSize(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
}

/** Percentage scales image content. Frame layout then determines the final canvas dimensions. */
data class JpegOutput(
    val dimensionPercent: Int = 100,
    val compressionQuality: Int = 95,
) {
    init {
        require(dimensionPercent in 1..100)
        require(compressionQuality in 1..100)
    }

    fun contentSize(input: PixelSize): PixelSize = PixelSize(
        (input.width.toDouble() * dimensionPercent / 100).roundToInt().coerceAtLeast(1),
        (input.height.toDouble() * dimensionPercent / 100).roundToInt().coerceAtLeast(1),
    )
}

/** One frame configuration belongs to the export batch, never to an individual photo recipe. */
data class BatchExportOptions(
    val jpeg: JpegOutput = JpegOutput(),
    val frame: FrameOptions = FrameOptions(),
)

data class FrameOptions(
    val preset: FramePreset = FramePreset.NONE,
    val borderFraction: Float = .045f,
    val signature: String = "",
    val showPhotoMetadata: Boolean = true,
    val fixedColorArgb: Int? = null,
    val ratio: FrameRatio = FrameRatio.ORIGINAL,
    val title: String? = null,
    val subtitle: String? = null,
    val footer: String? = null,
    val titleFont: FrameFont? = null,
    val subtitleFont: FrameFont? = null,
    val footerFont: FrameFont? = null,
    val signatureFont: FrameFont = FrameFont.SCRIPT,
    /** Blank means use this photo's selected stock, then the neutral app label. */
    val filmBrand: String = "",
    val filmName: String = "",
    val filmNotes: String = "NOTES",
    val roll120: Boolean = false,
    val mood: FrameMood = FrameMood.VIBRANT,
    val filmBlackWhite: Boolean? = null,
    /** Null follows each photo's orientation; otherwise fixes the canvas direction. */
    val portrait: Boolean? = null,
    val imageKey: String? = null,
    val titleFontKey: String? = null,
    val subtitleFontKey: String? = null,
    val footerFontKey: String? = null,
    val signatureFontKey: String? = null,
    val showCamera: Boolean = true,
    val showLens: Boolean = true,
    val showFocalLength: Boolean = true,
    val showAperture: Boolean = true,
    val showShutter: Boolean = true,
    val showIso: Boolean = true,
    val showCapturedAt: Boolean = false,
) {
    init {
        require(borderFraction.isFinite() && borderFraction in 0.02f..0.12f)
        require(listOf(signature, title, subtitle, footer).all { it == null || it.length <= 240 })
        require(filmBrand.length <= 60 && filmName.length <= 80 && filmNotes.length <= 60)
        require(imageKey == null || imageKey.matches(Regex("[a-f0-9]{64}\\.png")))
        require(listOf(titleFontKey, subtitleFontKey, footerFontKey, signatureFontKey)
            .all { it == null || it.matches(Regex("[a-f0-9]{64}\\.font")) })
    }
}

enum class FrameMood(val title: String) {
    VIBRANT("炽烈"), LIGHT_VIBRANT("清曜"), DARK_VIBRANT("棠梨"),
    MUTED("尘霭"), LIGHT_MUTED("霜月"), DARK_MUTED("侘寂"),
}

sealed interface InputOrigin {
    data object Jpeg : InputOrigin
    data class RawEmbeddedJpeg(val dimensions: PixelSize) : InputOrigin
}
