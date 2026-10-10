package com.lumixfilmmirror.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.lumixfilmmirror.camera.CameraResource
import com.lumixfilmmirror.camera.CameraMedia
import com.lumixfilmmirror.camera.LumixCameraSession
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Main-thread owner; IO/decoding is bounded and never written to disk. */
internal class CameraThumbnailLoader {
    data class Key(val version: Long, val entry: CameraMedia)
    private val cache = object : LruCache<Key, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
        // Compose may still display an evicted bitmap: do not recycle it here.
    }
    private val failures = linkedSetOf<Key>()
    private val requests = mutableSetOf<Job>()
    private val lane = Semaphore(1)
    private var generation = 0L

    fun cancelRequests() { requests.toList().forEach { it.cancel() } }
    fun invalidate() { generation++; cancelRequests(); cache.evictAll(); failures.clear() }

    suspend fun load(session: LumixCameraSession, resource: CameraResource, key: Key): Bitmap? = coroutineScope {
        val request = currentCoroutineContext().job
        val started = generation
        requests += request
        try {
            lane.withPermit {
                currentCoroutineContext().ensureActive()
                cache.get(key)?.let { return@withPermit it }
                if (key in failures) return@withPermit null
                try {
                    val bytes = withTimeoutOrNull(6_000) { session.thumbnail(resource) }
                        ?: error("缩略图请求超时")
                    val bitmap = withContext(Dispatchers.IO) { decode(bytes) }
                    currentCoroutineContext().ensureActive()
                    if (generation != started) return@withPermit null
                    cache.put(key, bitmap)
                    bitmap
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) {
                    if (generation == started) {
                        if (failures.size >= 128) failures.remove(failures.first())
                        failures += key
                    }
                    null
                }
            }
        } finally { requests -= request }
    }

    private fun decode(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 64_000_000) { "无效的相机 JPEG 缩略图" }
        var sample = 1
        while ((maxOf(bounds.outWidth, bounds.outHeight) + sample - 1) / sample > 384) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("缩略图无法解码")
    }
}
