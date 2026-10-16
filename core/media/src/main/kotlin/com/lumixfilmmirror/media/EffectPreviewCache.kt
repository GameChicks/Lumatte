package com.lumixfilmmirror.media

import android.graphics.Bitmap
import com.lumixfilmmirror.model.PhotoRecipe
import com.lumixfilmmirror.model.PixelSize

data class PreviewCacheInfo(val bytes: Long, val entries: Int, val hits: Long, val misses: Long,
    val budgetBytes: Long, val entryLimit: Int)

/** Source JPEGs are immutable private copies. Parsed LUT identity also participates in invalidation. */
internal data class EffectPreviewKey(val photoId: String, val path: String, val length: Long, val modified: Long,
    val sourceSize: PixelSize, val orientation: Int, val outputSize: PixelSize, val recipe: PhotoRecipe,
    val firstTable: CubeLut?, val secondTable: CubeLut?, val stock: FilmPreset?, val filmRenderSize: PixelSize?)

/** Access only while PhotoProcessor's preview gate is held. Returned bitmaps never borrow cache storage. */
internal class EffectPreviewCache(private val budget: Long = 8L * 1024 * 1024, private val entryLimit: Int = 2) {
    private val entries = LinkedHashMap<EffectPreviewKey, Bitmap>(4, .75f, true)
    private var bytes = 0L
    private var hits = 0L
    private var misses = 0L

    fun copy(key: EffectPreviewKey): Bitmap? {
        val cached = entries[key]
        if (cached == null) { misses++; return null }
        val result = try { cached.copy(Bitmap.Config.ARGB_8888, true) } catch (_: OutOfMemoryError) { null }
        if (result == null) { clear(); misses++; return null }
        hits++
        return result
    }

    fun remember(key: EffectPreviewKey, pixels: Bitmap) {
        val cost = pixels.allocationByteCount.toLong()
        if (cost > budget) return
        entries.remove(key)?.let { bytes -= it.allocationByteCount; it.recycle() }
        while (entries.isNotEmpty() && (entries.size >= entryLimit || bytes + cost > budget)) evictOldest()
        // Caching is optional. A successful render remains usable if the extra cache copy cannot fit.
        val cached = try { pixels.copy(Bitmap.Config.ARGB_8888, false) } catch (_: OutOfMemoryError) { null } ?: return
        val actual = cached.allocationByteCount.toLong()
        if (actual > budget) { cached.recycle(); return }
        while (entries.isNotEmpty() && bytes + actual > budget) evictOldest()
        entries[key] = cached; bytes += actual
    }

    private fun evictOldest() {
        val iterator = entries.entries.iterator()
        val oldest = iterator.next().value
        iterator.remove(); bytes -= oldest.allocationByteCount; oldest.recycle()
    }
    fun clear() {
        entries.values.forEach(Bitmap::recycle)
        entries.clear(); bytes = 0
    }
    fun info() = PreviewCacheInfo(bytes, entries.size, hits, misses, budget, entryLimit)
}
