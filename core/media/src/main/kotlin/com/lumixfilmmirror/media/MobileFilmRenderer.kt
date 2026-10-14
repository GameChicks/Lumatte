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
    external fun render(handle: Long, bitmap: Bitmap, tables: Array<FloatArray>, sizes: IntArray,
        bounds: FloatArray, controls: FloatArray, sourceWidth: Int, sourceHeight: Int, seed: Long,
        preferGpu: Boolean, modelKey: String)
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

    suspend fun render(bitmap: Bitmap, plan: FilmRenderPlan, effectiveInput: PixelSize, preferGpu: Boolean): FilmExecutionBackend {
        check(NativeMobileFilmBridge.available) { "移动胶片原生库不可用" }
        val model = models.load(plan.preset)
        coroutineContext.ensureActive()
        require(bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) { "移动胶片输入需为可写 sRGB 位图" }
        val stages = listOf(model.response.nativeData, model.development.nativeData, model.scan.nativeData)
        val tables = stages.map { it.values }.toTypedArray()
        val sizes = IntArray(3) { stages[it].size }
        val bounds = FloatArray(18) { index ->
            val stage = stages[index / 6]
            if (index % 6 < 3) stage.minimum[index % 6] else stage.maximum[index % 6 - 3]
        }
        val effect = model.effects
        // Explicit artistic red-record option, not the ordinary stock baseline or an imported 800T.
        // Default stock mode retains channel balance at unit gain; no global saturation adjustment.
        val redLayer = plan.spatialOptions.halationAppearance == FilmHalationAppearance.RED_LAYER_RETURN &&
            plan.preset.category in setOf(FilmCategory.NEGATIVE, FilmCategory.CINEMA)
        val redReturn = effect.halationStrength[0] * 2f
        val halationReturns = if (redLayer) floatArrayOf(redReturn, redReturn * .04f, 0f)
            else effect.halationStrength
        val controls = floatArrayOf(plan.parameters.diffusion.strength,
            if (plan.parameters.halationEnabled) 1f else 0f, plan.parameters.exposureEv,
            if (effect.grainActive) 1f else 0f, effect.particleAreaUm2,
            *effect.particleScale, *effect.densityFloor, *effect.grainUniformity,
            *effect.densityMaximum, *halationReturns, *effect.halationSigmaUm, effect.filmFormatMm)
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
                        val useGpu = preferGpu && FilmEngine.canUseGpu()
                        val result = runCatching { NativeMobileFilmBridge.render(task, bitmap, tables, sizes, bounds,
                            controls, effectiveInput.width, effectiveInput.height, plan.grainSeed,
                            useGpu, modelKey)
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
