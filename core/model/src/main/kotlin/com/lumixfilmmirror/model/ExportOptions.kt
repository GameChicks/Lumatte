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
    val filmBrand: String = "LUMIX",
    val filmName: String = "FILM MIRROR",
    val filmNotes: String = "NOTES",
    val roll120: Boolean = false,
)

sealed interface InputOrigin {
    data object Jpeg : InputOrigin
    data class RawEmbeddedJpeg(val dimensions: PixelSize) : InputOrigin
}
