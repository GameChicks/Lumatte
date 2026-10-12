package com.lumixfilmmirror.app

import android.graphics.Bitmap
import android.util.LruCache
import com.lumixfilmmirror.media.LutExampleColours
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

internal data class MirrorPreview(val image: Bitmap, val colours: LutExampleColours? = null)

/** Screen-independent, bounded display cache. Eviction drops ownership without recycling an image
 * still displayed by a card. Concurrent copies of the same card share one producer and palette. */
internal class MirrorPreviewCache(private val scope: CoroutineScope) {
    private val entries = object : LruCache<Any, MirrorPreview>(28 * 1024 * 1024) {
        override fun sizeOf(key: Any, value: MirrorPreview) = value.image.allocationByteCount
    }
    private val lock = Mutex()
    private val gate = Semaphore(2)
    private val pending = mutableMapOf<Any, Deferred<MirrorPreview>>()
    private val waiters = mutableMapOf<Any, Int>()
    fun peek(key: Any): MirrorPreview? = entries[key]
    suspend fun load(key: Any, keepLoading: Boolean = true, producer: suspend () -> MirrorPreview): MirrorPreview {
        entries[key]?.let { return it }
        val job = lock.withLock {
            entries[key]?.let { return it }
            waiters[key] = (waiters[key] ?: 0) + 1
            pending.getOrPut(key) {
                scope.async {
                    val producingJob = currentCoroutineContext()[Job]
                    try { gate.withPermit { producer().also { entries.put(key, it) } } }
                    finally { withContext(NonCancellable) { lock.withLock {
                        if (pending[key] === producingJob) pending.remove(key)
                    } } }
                }
            }
        }
        try { return job.await() }
        finally { withContext(NonCancellable) { lock.withLock {
            val remaining = (waiters[key] ?: 1) - 1
            if (remaining > 0) waiters[key] = remaining else {
                waiters.remove(key)
                if (pending[key] === job && (job.isCompleted || !keepLoading)) {
                    pending.remove(key)
                    if (!job.isCompleted) job.cancel()
                }
            }
        } } }
    }
}
