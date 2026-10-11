package com.lumixfilmmirror.media

import android.graphics.Bitmap
import com.lumixfilmmirror.model.PixelSize

/** Configured source route. Does not claim native compilation, loading or measured performance. */
enum class FilmBackend { MOBILE_NATIVE_CPU }

/** Product facade; the full spectral Vulkan implementation remains a separate developer reference. */
internal class FilmEngine(models: MobileFilmModelLibrary) {
    private val cpu = MobileFilmCpuEngine(models)
    suspend fun render(bitmap: Bitmap, plan: FilmRenderPlan, effectiveInput: PixelSize) =
        cpu.render(bitmap, plan, effectiveInput)
    suspend fun releaseIdleEngine() = cpu.awaitIdle()
}
