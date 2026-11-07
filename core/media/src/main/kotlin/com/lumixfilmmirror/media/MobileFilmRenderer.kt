package com.lumixfilmmirror.media

import android.graphics.Bitmap
import com.lumixfilmmirror.model.PixelSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resumeWithException

internal object NativeMobileFilmBridge {
    val available: Boolean by lazy {
        try { System.loadLibrary("lfm_mobile_film"); true }
        catch (_: UnsatisfiedLinkError) { false }
    }
    external fun createTask(): Long
    external fun cancelTask(handle: Long)
    external fun releaseTask(handle: Long)
    external fun releaseIdleGpu()
    external fun spatialMetrics(): LongArray
    external fun render(handle: Long, bitmap: Bitmap, tables: Array<FloatArray>, sizes: IntArray,
        bounds: FloatArray, controls: FloatArray, sourceWidth: Int, sourceHeight: Int, seed: Long,
        preferGpu: Boolean, modelKey: String, rawPath:String?,orientation:Int)
}

/** Native GPU errors require decoding a fresh input; cancellation and memory errors retain their type. */
internal class MobileFilmGpuFailure(message: String) : Exception(message)

/** CPU/GLES share one worker; EGL resources are only touched on their owning thread. */
internal class MobileFilmRenderer(private val models: MobileFilmModelLibrary) {
    /** Drain pending work before releasing GPU resources. Never load native code just to trim caches. */
    suspend fun awaitIdle() = withContext(NonCancellable) {
        val finished = CompletableDeferred<Result<Unit>>()
        executor.execute { finished.complete(runCatching { if (nativeUsed) NativeMobileFilmBridge.releaseIdleGpu() }) }
        finished.await().getOrThrow()
    }

    suspend fun render(bitmap: Bitmap, plan: FilmRenderPlan, effectiveInput: PixelSize, preferGpu: Boolean, source:SourceImage?=null): FilmExecutionBackend {
        check(NativeMobileFilmBridge.available) { "移动胶片原生库不可用" }
        val model = models.load(plan.preset)
        coroutineContext.ensureActive()
        require(bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) { "移动胶片输入需为可写 sRGB 位图" }
        require(bitmap.colorSpace == android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)) {
            "移动胶片输入必须转换到 sRGB，不能仅重标色彩空间"
        }
        val stages = listOf(model.response.nativeData, model.development.nativeData, model.scan.nativeData)
        val tables = stages.map { it.values }.toTypedArray()
        val sizes = IntArray(3) { stages[it].size }
        val bounds = FloatArray(18) { index ->
            val stage = stages[index / 6]
            if (index % 6 < 3) stage.minimum[index % 6] else stage.maximum[index % 6 - 3]
        }
        val effect = model.effects
        // Only CineStill uses the SpektraLab base-reflection/headroom path. Legacy Fotufilm
        // wire slots stay reserved for binary stability; ordinary stock effects are unchanged.
        val cineStill = plan.preset.id == "cinestill_800t" || plan.preset.id == "cinestill_400d"
        val physical = effect.fotufilmHalation
        require(!cineStill || physical != null) { "CineStill 缺少原始光晕求解参数" }
        val controls = floatArrayOf(plan.parameters.diffusion.strength,
            if (plan.parameters.halationEnabled) 1f else 0f, plan.parameters.exposureEv,
            if (effect.grainActive) 1f else 0f, effect.particleAreaUm2,
            *effect.particleScale, *effect.densityFloor, *effect.grainUniformity,
            *effect.densityMaximum, *effect.halationStrength, *effect.halationSigmaUm, effect.filmFormatMm,
            if (cineStill) 1f else 0f, effect.halationBounces.toFloat(), effect.halationDecay, effect.grainSpacingUm,
            effect.scatterAmount, *effect.scatterCoreUm, *effect.scatterTailUm, *effect.scatterTailWeight,
            *(physical?.sigmaMm ?: FloatArray(3)), *(physical?.weights ?: FloatArray(9)),
            *(physical?.matrix ?: FloatArray(9)),
            0f, // Retired post-scan red JPEG overlay; headroom now acts before development.
            plan.parameters.contrast, plan.parameters.grainIntensity.strength,
            plan.parameters.brightness, plan.parameters.highlights, plan.parameters.shadows,
            plan.parameters.saturation, plan.parameters.vibrance, plan.parameters.temperature)
        val modelKey = "${model.info.modelVersion}:${model.info.sourceCommit}:${model.info.stockId}:${model.info.printId}"
        val task = NativeMobileFilmBridge.createTask()
        check(task != 0L) { "无法建立移动胶片任务" }
        nativeUsed = true
        val taskLock = Any()
        var released = false
        val finished = CompletableDeferred<Unit>()
        try {
            return suspendCancellableCoroutine<FilmExecutionBackend> { continuation ->
                continuation.invokeOnCancellation {
                    synchronized(taskLock) { if (!released) NativeMobileFilmBridge.cancelTask(task) }
                }
                try {
                    executor.execute {
                        // RAW10 reads mmap samples in float; the ordinary 8-bit GPU upload is never used for it.
                        val useGpu = preferGpu && FilmEngine.canUseGpu() && source?.rawWorkingFile==null
                        val result = runCatching { NativeMobileFilmBridge.render(task, bitmap, tables, sizes, bounds,
                            controls, effectiveInput.width, effectiveInput.height, plan.grainSeed,
                            useGpu, modelKey,source?.rawWorkingFile?.absolutePath,source?.orientation ?: 1)
                            if(cineStill) {
                                val metrics=NativeMobileFilmBridge.spatialMetrics()
                                android.util.Log.i("LFMFilm","cinestillSpatialMs=${metrics[0]/1000.0} spatialCacheHit=${metrics[1]==1L} fieldBytes=${metrics[2]}")
                            }
                            if (useGpu) FilmExecutionBackend.GLES3 else FilmExecutionBackend.CPU
                        }
                        (result.exceptionOrNull() as? MobileFilmGpuFailure)?.let { FilmEngine.disableGpuForProcess(it.message) }
                        synchronized(taskLock) { NativeMobileFilmBridge.releaseTask(task); released = true }
                        finished.complete(Unit)
                        continuation.resumeWith(result)
                    }
                } catch (error: Throwable) {
                    synchronized(taskLock) { NativeMobileFilmBridge.releaseTask(task); released = true }
                    finished.complete(Unit)
                    continuation.resumeWithException(error)
                }
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) { finished.await() }
            throw error
        }
    }

    companion object {
        @Volatile private var nativeUsed = false
        private val executor = Executors.newSingleThreadExecutor { job -> Thread(job, "lfm-mobile-film").apply { isDaemon = true } }
    }
}
