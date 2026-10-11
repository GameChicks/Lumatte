package com.lumixfilmmirror.media

import android.graphics.Bitmap
import com.lumixfilmmirror.model.PixelSize

/** Configured source route. Does not claim native compilation, loading or measured performance. */
enum class FilmBackend { MOBILE_GLES3_WITH_CPU_FALLBACK, MOBILE_NATIVE_CPU }

/** Product facade; the full spectral Vulkan implementation remains a separate developer reference. */
internal class FilmEngine(models: MobileFilmModelLibrary) {
    private val renderer = MobileFilmRenderer(models)
    suspend fun render(bitmap: Bitmap, plan: FilmRenderPlan, effectiveInput: PixelSize) =
        renderer.render(bitmap, plan, effectiveInput, preferGpu = !gpuDisabled)
    suspend fun renderCpu(bitmap: Bitmap, plan: FilmRenderPlan, effectiveInput: PixelSize) =
        renderer.render(bitmap, plan, effectiveInput, preferGpu = false)
    fun disableGpu() = disableGpuForProcess()
    suspend fun releaseIdleEngine() = renderer.awaitIdle()
    companion object {
        /** One driver failure disables retries across all processors until the process restarts. */
        @Volatile private var gpuDisabled = false
        internal fun canUseGpu(): Boolean = !gpuDisabled
        internal fun disableGpuForProcess() { gpuDisabled = true }
    }
}
