package com.lumixfilmmirror.app

import android.graphics.Bitmap
import android.util.LruCache
import com.lumixfilmmirror.camera.CameraResource
import com.lumixfilmmirror.camera.CameraMedia
import com.lumixfilmmirror.camera.LumixCameraSession
import com.lumixfilmmirror.camera.CameraProtocolException
import com.lumixfilmmirror.media.JpegThumbnailDecoder
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

    suspend fun load(session: LumixCameraSession, resources: List<CameraResource>, key: Key): Bitmap? = coroutineScope {
        val request = currentCoroutineContext().job
        val started = generation
        requests += request
        try {
            lane.withPermit {
                currentCoroutineContext().ensureActive()
                cache.get(key)?.let { return@withPermit it }
                if (key in failures) return@withPermit null
                for (resource in resources.take(2)) {
                    try {
                        val bytes = withTimeoutOrNull(6_000) { session.thumbnail(resource) }
                            ?: error("缩略图请求超时")
                        val bitmap = withContext(Dispatchers.IO) { JpegThumbnailDecoder.decode(bytes) }
                        currentCoroutineContext().ensureActive()
                        if (generation != started) return@withPermit null
                        cache.put(key, bitmap)
                        return@withPermit bitmap
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        if (error is CameraProtocolException && error.requiresReconnect) throw error
                        // Try only the other advertised thumbnail, never an original resource.
                        currentCoroutineContext().ensureActive()
                    }
                }
                currentCoroutineContext().ensureActive()
                if (generation == started) {
                    if (failures.size >= 128) failures.remove(failures.first())
                    failures += key
                }
                null
            }
        } finally { requests -= request }
    }
}
