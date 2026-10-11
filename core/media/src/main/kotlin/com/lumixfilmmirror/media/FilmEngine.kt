package com.lumixfilmmirror.media

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resumeWithException

internal object NativeFilmBridge {
    init { System.loadLibrary("lfm_film") }
    external fun shaderNames(): Array<String>
    external fun shaderBytes(name: String): ByteArray
    external fun createTask(): Long
    external fun cancelTask(handle: Long)
    external fun releaseTask(handle: Long)
    external fun releaseIdleEngine()
    external fun render(handle: Long, directory: String, bitmap: Bitmap, parameters: String, diffusion: Float, seed: Long)
}

/** Native work is drained before cancellation returns, so the caller can safely recycle its bitmap. */
internal class FilmEngine(context: Context) {
    private val context = context.applicationContext
    private val resourceMutex = Mutex()
    private var ready: File? = null
    @Volatile private var nativeUsed = false

    /** Enqueued behind active renders; never loads the native library solely to clean up. */
    suspend fun releaseIdleEngine() {
        if (!nativeUsed) return
        withContext(NonCancellable) {
            val finished = CompletableDeferred<Result<Unit>>()
            executor.execute {
                finished.complete(runCatching { NativeFilmBridge.releaseIdleEngine() })
            }
            finished.await().getOrThrow()
        }
    }

    private suspend fun resources(): File = withContext(Dispatchers.IO) {
        resourceMutex.withLock {
            ready?.let { return@withLock it }
            val root = File(context.filesDir, "film-engine-6a347d5756f8-v1")
            check(root.isDirectory || root.mkdirs()) { "无法创建胶片引擎目录" }
            val files = listOf("spektrafilm_constants.bin", "neutral_print_filters.json", "print_luts.json") +
                context.assets.list("profiles").orEmpty().filter { it.matches(Regex("[a-z0-9_]+\\.json")) }.map { "profiles/$it" }
            for (name in files) {
                coroutineContext.ensureActive()
                writeAtomic(File(root, name)) { output -> context.assets.open(name).use { it.copyTo(output) } }
            }
            for (name in NativeFilmBridge.shaderNames()) {
                coroutineContext.ensureActive()
                require(name.matches(Regex("spk_[a-z0-9_]+\\.spv")))
                writeAtomic(File(root, "vulkan/$name")) { it.write(NativeFilmBridge.shaderBytes(name)) }
            }
            ready = root
            root
        }
    }

    private fun writeAtomic(file: File, write: (java.io.OutputStream) -> Unit) {
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { write(output); output.flush(); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }

    suspend fun render(bitmap: Bitmap, plan: FilmRenderPlan) {
        val root = resources()
        coroutineContext.ensureActive()
        val values = plan.parameters
        val parameters = JSONObject().apply {
            put("film_stock", plan.preset.id)
            put("print_stock", plan.preset.printId)
            put("input_color_space", "sRGB")
            put("input_cctf_decoding", false) // JNI supplies linear RGB after the lens effect.
            put("output_color_space", "sRGB")
            put("output_cctf_encoding", true)
            put("auto_exposure", false) // Respect the camera JPEG's existing exposure.
            put("product_defaults", true) // Disable print auto compensation: one exposure control.
            put("exposure_compensation_ev", values.exposureEv.toDouble())
            put("print_exposure", 1.0) // Internal paper calibration, never a second exposure gain.
            put("halation_active", values.halationEnabled)
            put("scan_film", plan.preset.positive) // A reversal film is scanned, not printed through a negative.
            // The upstream strip budget is informational. Calculate actual rows here;
            // this bounds band-local intermediates, not whole-frame spatial buffers.
            val bandBudget = 48L * 1024 * 1024
            val bytesPerRow = bitmap.width.toLong() * 16 * 12
            put("striped", bitmap.width.toLong() * bitmap.height > 2_000_000L)
            put("strip_rows", (bandBudget / bytesPerRow).coerceIn(32, 512).coerceAtMost(bitmap.height.toLong()).toInt())
            put("strip_budget_bytes", bandBudget)
        }.toString()
        val task = NativeFilmBridge.createTask()
        check(task != 0L) { "无法建立胶片处理任务" }
        nativeUsed = true
        val taskLock = Any()
        var released = false
        val finished = CompletableDeferred<Unit>()
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation {
                    synchronized(taskLock) { if (!released) NativeFilmBridge.cancelTask(task) }
                }
                try {
                    executor.execute {
                        val outcome = runCatching { NativeFilmBridge.render(task, root.absolutePath, bitmap,
                            parameters, values.diffusion.strength, plan.grainSeed) }
                        synchronized(taskLock) { NativeFilmBridge.releaseTask(task); released = true }
                        finished.complete(Unit)
                        continuation.resumeWith(outcome)
                    }
                } catch (error: Exception) {
                    synchronized(taskLock) { NativeFilmBridge.releaseTask(task); released = true }
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
        private val executor = Executors.newSingleThreadExecutor { job -> Thread(job, "lfm-film-render").apply { isDaemon = true } }
    }
}
