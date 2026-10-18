package com.lumixfilmmirror.media

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.coroutineContext

enum class LutSubject(val label: String) {
    DAILY("日常"), NATURE("自然"), STREET("街头"), PORTRAIT("人像"),
    ARCHITECTURE("建筑"), NIGHT("夜景"), OTHER("其他")
}
enum class LutGallerySource { ALL, BUNDLED, LOCAL, OFFICIAL }

data class LutGalleryEntry(val id: String, val name: String, val builtin: Boolean, val photoStyle: String?,
    val subjects: Set<LutSubject>, val exampleColours: LutExampleColours? = null,
    val lumixLabSource: LumixLabLutSource? = null)
data class LutGallerySnapshot(val entries: List<LutGalleryEntry>, val problems: List<String>)
/** Returned card image is caller-owned; recycle it on replacement/disposal. */
data class LutCardPreview(val lutId: String, val image: Bitmap, val colours: LutExampleColours)
data class LutGalleryColourEntry(val lutId: String, val colours: LutExampleColours)
data class LutGalleryColourSnapshot(val entries: List<LutGalleryColourEntry>, val problems: List<String>)

data class LutGalleryQuery(val text: String = "", val source: LutGallerySource = LutGallerySource.ALL,
    val subject: LutSubject? = null, val family: LutColourFamily? = null,
    val exampleMode: LutExampleColourMode? = null, val minimumFamilyWeight: Float = .08f) {
    init { require(minimumFamilyWeight.isFinite() && minimumFamilyWeight in 0f..1f) }
}

/** Pure filtering. Missing examples/tags stay unknown instead of being guessed from the LUT name. */
object LutGalleryFilter {
    fun select(entries: List<LutGalleryEntry>, query: LutGalleryQuery): List<LutGalleryEntry> {
        val text = query.text.trim()
        return entries.filter { entry ->
            (text.isEmpty() || entry.name.contains(text, ignoreCase = true) || entry.photoStyle?.contains(text, ignoreCase = true) == true) &&
                (when (query.source) {
                    LutGallerySource.ALL -> true
                    LutGallerySource.BUNDLED -> entry.builtin
                    LutGallerySource.LOCAL -> !entry.builtin && entry.lumixLabSource == null
                    LutGallerySource.OFFICIAL -> entry.lumixLabSource != null
                }) &&
                (query.subject == null || query.subject in entry.subjects) &&
                (query.exampleMode == null || entry.exampleColours?.mode == query.exampleMode) &&
                (query.family == null || (entry.exampleColours?.familyWeights?.get(query.family) ?: 0f).let {
                    it > 0f && it >= query.minimumFamilyWeight
                })
        }
    }
}

/** Small persistent user labels, separate from LUT numeric data and disposable image caches. */
internal class LutGalleryLibrary(context: Context) {
    private val root = File(context.applicationContext.filesDir, "lut-gallery")

    suspend fun load(assets: List<LutAsset>): LutGallerySnapshot = withContext(Dispatchers.IO) {
        gate.withLock {
            val problems = mutableListOf<String>()
            val entries = assets.map { asset ->
                coroutineContext.ensureActive()
                val subjects = try { readSubjects(asset.id) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { problems += "${asset.name}：题材标签读取失败"; emptySet() }
                LutGalleryEntry(asset.id, asset.name, asset.builtin, asset.photoStyle, subjects, lumixLabSource = asset.lumixLabSource)
            }
            LutGallerySnapshot(entries, problems)
        }
    }

    suspend fun setSubjects(asset: LutAsset, subjects: Set<LutSubject>): LutGalleryEntry {
        val snapshot = subjects.toSet()
        return withContext(Dispatchers.IO) {
            gate.withLock {
                coroutineContext.ensureActive()
                check(root.isDirectory || root.mkdirs()) { "无法创建 LUT 标签目录" }
                val atomic = AtomicFile(file(asset.id))
                val json = JSONObject().put("schema", 1).put("lutId", asset.id)
                    .put("subjects", JSONArray(snapshot.sortedBy { it.ordinal }.map { it.name }))
                val output = atomic.startWrite()
                try {
                    output.write(json.toString().toByteArray(Charsets.UTF_8))
                    coroutineContext.ensureActive()
                    atomic.finishWrite(output)
                } catch (error: Throwable) { atomic.failWrite(output); throw error }
                LutGalleryEntry(asset.id, asset.name, asset.builtin, asset.photoStyle, snapshot, lumixLabSource = asset.lumixLabSource)
            }
        }
    }

    private fun file(id: String): File {
        require(ID.matches(id)) { "LUT 标识无效" }
        return File(root, "$id.json")
    }

    private suspend fun readSubjects(id: String): Set<LutSubject> {
        val file = file(id)
        if (!file.exists() && !File(root, "$id.json.bak").exists()) return emptySet()
        val bytes = AtomicFile(file).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val block = ByteArray(4096)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(block)
                if (count < 0) break
                require(output.size() + count <= 32 * 1024) { "LUT 标签文件过大" }
                output.write(block, 0, count)
            }
            output.toByteArray()
        }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getInt("schema") == 1 && json.getString("lutId") == id) { "LUT 标签来源不符" }
        val array = json.getJSONArray("subjects")
        require(array.length() <= LutSubject.entries.size) { "LUT 标签数量无效" }
        return (0 until array.length()).map { LutSubject.valueOf(array.getString(it)) }.toSet()
    }

    companion object {
        private val gate = Mutex() // AtomicFile is not a cross-instance locking primitive.
        private val ID = Regex("(?:bundled-[a-z0-9-]{1,80}|(?:local|lab)-[0-9a-f]{64})")
    }
}
