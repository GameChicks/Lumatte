package com.lumixfilmmirror.media.frame

import java.util.Locale

/** Display-only formatting of the camera Make/Model imported from EXIF. */
object FrameCameraNames {
    private val whitespace = Regex("\\s+")
    private val lumixModel = Regex(
        "^(?:(?:Panasonic(?: Corporation)?|Lumix)\\s+)*(?:DC-|DMC-|Lumix\\s+)([A-Z0-9][A-Z0-9._-]*)$",
        RegexOption.IGNORE_CASE,
    )

    /** Unknown or absent metadata is preserved; no model is inferred from a filename or connection. */
    fun format(exifCamera: String): String {
        val camera = exifCamera.trim().replace(whitespace, " ")
        val model = lumixModel.matchEntire(camera)?.groupValues?.get(1) ?: return camera
        return "Lumix ${model.uppercase(Locale.ROOT)}"
    }
}
