package com.lumixfilmmirror.media

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resumeWithException

enum class LutBackend { NATIVE_CPU, KOTLIN_CPU }

/** Arrays belong to an immutable parsed table. Native code takes per-task copies, never mutates them. */
internal data class NativeCubeData(val oneSize: Int, val threeSize: Int,
    val one: FloatArray, val three: FloatArray, val bounds: FloatArray)

internal object NativeLutBridge {
    val available: Boolean by lazy {
        try { System.loadLibrary("lfm_lut"); true }
        catch (_: UnsatisfiedLinkError) { false }
    }
    external fun createTask(): Long
    external fun cancelTask(handle: Long)
    external fun releaseTask(handle: Long)
    external fun render(handle: Long, bitmap: Bitmap,
        firstOneSize: Int, firstThreeSize: Int, firstOne: FloatArray?, firstThree: FloatArray?, firstBounds: FloatArray?, firstStrength: Float,
        secondOneSize: Int, secondThreeSize: Int, secondOne: FloatArray?, secondThree: FloatArray?, secondBounds: FloatArray?, secondStrength: Float,
        grain: Float, sourceWidth: Int, sourceHeight: Int, seed: Int, dithering: Boolean)
}

/** Cancellation drains native work before the caller may recycle or reuse the bitmap. */
internal object NativeLutRenderer {
    private val executor = Executors.newSingleThreadExecutor { job -> Thread(job, "lfm-lut-render").apply { isDaemon = true } }
    fun backend() = if (NativeLutBridge.available) LutBackend.NATIVE_CPU else LutBackend.KOTLIN_CPU

    suspend fun render(bitmap: Bitmap, source: SourceImage, first: CubeLut?, firstStrength: Float,
        second: CubeLut?, secondStrength: Float, grain: Float, dithering: Boolean) {
        val a = first?.nativeData
        val b = second?.nativeData
        val task = NativeLutBridge.createTask()
        check(task != 0L) { "无法建立 LUT 处理任务" }
        val taskLock = Any()
        var released = false
        val finished = CompletableDeferred<Unit>()
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation {
                    synchronized(taskLock) { if (!released) NativeLutBridge.cancelTask(task) }
                }
                try {
                    executor.execute {
                        val outcome = runCatching { NativeLutBridge.render(task, bitmap,
                            a?.oneSize ?: 0, a?.threeSize ?: 0, a?.one, a?.three, a?.bounds, firstStrength,
                            b?.oneSize ?: 0, b?.threeSize ?: 0, b?.one, b?.three, b?.bounds, secondStrength,
                            grain, source.size.width, source.size.height, source.id.value.hashCode(), dithering) }
                        synchronized(taskLock) { NativeLutBridge.releaseTask(task); released = true }
                        finished.complete(Unit)
                        continuation.resumeWith(outcome)
                    }
                } catch (error: Throwable) {
                    synchronized(taskLock) { NativeLutBridge.releaseTask(task); released = true }
                    finished.complete(Unit)
                    continuation.resumeWithException(error)
                }
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) { finished.await() }
            throw error
        }
    }
}
