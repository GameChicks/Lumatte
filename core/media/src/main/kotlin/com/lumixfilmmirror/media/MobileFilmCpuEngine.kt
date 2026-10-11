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
    external fun render(handle: Long, bitmap: Bitmap, tables: Array<FloatArray>, sizes: IntArray,
        bounds: FloatArray, controls: FloatArray, sourceWidth: Int, sourceHeight: Int, seed: Long)
}

/** Independent image backend; no reference-engine substitution on failure or partial output reuse. */
internal class MobileFilmCpuEngine(private val models: MobileFilmModelLibrary) {
    /** No persistent native pool; drain current work before callers discard model caches. */
    suspend fun awaitIdle() = withContext(NonCancellable) {
        val finished = CompletableDeferred<Unit>()
        executor.execute { finished.complete(Unit) }
        finished.await()
    }

    suspend fun render(bitmap: Bitmap, plan: FilmRenderPlan, effectiveInput: PixelSize) {
        check(NativeMobileFilmBridge.available) { "移动胶片原生 CPU 库不可用" }
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
        val controls = floatArrayOf(plan.parameters.diffusion.strength,
            if (plan.parameters.halationEnabled) 1f else 0f, plan.parameters.exposureEv,
            if (effect.grainActive) 1f else 0f, effect.particleAreaUm2,
            *effect.particleScale, *effect.densityFloor, *effect.grainUniformity,
            *effect.densityMaximum, *effect.halationStrength, *effect.halationSigmaUm, effect.filmFormatMm)
        val task = NativeMobileFilmBridge.createTask()
        check(task != 0L) { "无法建立移动胶片任务" }
        val taskLock = Any()
        var released = false
        val finished = CompletableDeferred<Unit>()
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation {
                    synchronized(taskLock) { if (!released) NativeMobileFilmBridge.cancelTask(task) }
                }
                try {
                    executor.execute {
                        val result = runCatching { NativeMobileFilmBridge.render(task, bitmap, tables, sizes, bounds,
                            controls, effectiveInput.width, effectiveInput.height, plan.grainSeed) }
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
        private val executor = Executors.newSingleThreadExecutor { job -> Thread(job, "lfm-mobile-film").apply { isDaemon = true } }
    }
}
