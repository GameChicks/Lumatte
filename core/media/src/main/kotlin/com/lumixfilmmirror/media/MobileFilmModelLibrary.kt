package com.lumixfilmmirror.media

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Bundled assets only. No camera/Internet transport, eager table loading or native initialization. */
internal class MobileFilmModelLibrary(context: Context) {
    private val assets = context.applicationContext.assets
    private val gate = Mutex()
    private val cache = LinkedHashMap<String, MobileFilmModel>(4, .75f, true)
    private var cachedBytes = 0L
    private var entries: Map<String, Entry>? = null
    private data class Entry(val info: MobileFilmModelInfo, val manifestSha256: String)

    suspend fun info(): MobileFilmModelsInfo = withContext(Dispatchers.IO) {
        gate.withLock {
            try {
                MobileFilmModelsInfo(index().values.map { it.info }, emptyList(), cache.size, cachedBytes, CACHE_BYTES)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                MobileFilmModelsInfo(emptyList(), listOf("移动胶片模型资料不可用：${error.message}"),
                    cache.size, cachedBytes, CACHE_BYTES)
            }
        }
    }

    suspend fun clear() = gate.withLock { cache.clear(); cachedBytes = 0L }

    suspend fun load(preset: FilmPreset): MobileFilmModel = withContext(Dispatchers.IO) {
        gate.withLock {
            val entry = requireNotNull(index()[preset.id]) { "所选胶卷尚无移动模型数据" }
            require(entry.info.printId == preset.printId && entry.info.positive == preset.positive) {
                "移动胶片模型与所选胶卷／相纸不符"
            }
            cache[preset.id]?.let { return@withLock it }
            coroutineContext.ensureActive()
            val bytes = readSmall("$ROOT/${preset.id}/model.json")
            require(hash(bytes) == entry.manifestSha256) { "胶片模型说明哈希不符" }
            val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
            val source = manifest.getJSONObject("source")
            require(manifest.getInt("schema") == 1 && manifest.getString("modelVersion") == entry.info.modelVersion &&
                manifest.getString("stockId") == preset.id && manifest.getString("printId") == preset.printId &&
                manifest.getBoolean("positive") == preset.positive && source.getString("commit") == SOURCE_COMMIT &&
                source.getString("repository") == "https://github.com/andreavolpato/spektrafilm" &&
                source.getString("license") == "CC-BY-SA-4.0" &&
                manifest.getString("storage") == "float32_le_rgb_x_fastest" &&
                manifest.getString("dir") == "baked_local_only" && !manifest.getBoolean("spatialEffectsBaked") &&
                !manifest.getBoolean("grainBaked")) { "不支持此胶片模型来源／阶段格式" }
            val tables = manifest.getJSONObject("tables")
            val model = MobileFilmModel(entry.info,
                readTable(preset.id, "response", tables.getJSONObject("response"), "srgb_encoded", "film_raw_linear"),
                readTable(preset.id, "development", tables.getJSONObject("development"), "film_log10_exposure", "film_cmy_density"),
                readTable(preset.id, "scan", tables.getJSONObject("scan"), "film_cmy_density", "srgb_encoded"))
            val count = model.response.bytes + model.development.bytes + model.scan.bytes
            require(count == entry.info.tableBytes) { "胶片模型总大小不符" }
            coroutineContext.ensureActive()
            if (count <= CACHE_BYTES) {
                while (cache.isNotEmpty() && (cache.size >= CACHE_MODELS || cachedBytes + count > CACHE_BYTES)) {
                    val iterator = cache.entries.iterator()
                    val old = iterator.next().value
                    cachedBytes -= old.info.tableBytes
                    iterator.remove()
                }
                cache[preset.id] = model
                cachedBytes += count
            }
            model
        }
    }

    private suspend fun index(): Map<String, Entry> {
        entries?.let { return it }
        val json = JSONObject(readSmall("$ROOT/index.json").toString(Charsets.UTF_8))
        require(json.getInt("schema") == 1 && json.getString("sourceCommit") == SOURCE_COMMIT &&
            json.getString("modelVersion") == MODEL_VERSION) { "不支持此移动胶片目录版本" }
        val array = json.getJSONArray("models")
        require(array.length() in 1..128) { "移动胶片目录数量无效" }
        val result = LinkedHashMap<String, Entry>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val id = item.getString("stockId")
            val printId = item.getString("printId")
            val version = item.getString("modelVersion")
            val size = item.getLong("tableBytes")
            val digest = item.getString("manifestSha256")
            require(ID.matches(id) && ID.matches(printId) && version == MODEL_VERSION &&
                size in 1..MAX_MODEL_BYTES && SHA256.matches(digest) && !result.containsKey(id)) { "移动胶片目录条目无效" }
            result[id] = Entry(MobileFilmModelInfo(id, printId, item.getBoolean("positive"), version, SOURCE_COMMIT, size), digest)
        }
        return result.toMap().also { entries = it }
    }

    private suspend fun readSmall(path: String): ByteArray = assets.open(path).use { input ->
        val output = ByteArrayOutputStream()
        val block = ByteArray(8192)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(block)
            if (count < 0) break
            require(output.size() + count <= MAX_JSON_BYTES) { "胶片模型说明过大" }
            output.write(block, 0, count)
        }
        output.toByteArray()
    }

    private suspend fun readTable(stock: String, name: String, json: JSONObject, inputSpace: String, outputSpace: String): FilmStageTable {
        val size = json.getInt("size")
        require(size in 2..65 && json.getString("file") == "$name.f32" && json.getString("shaper") == "linear" &&
            json.getString("inputSpace") == inputSpace && json.getString("outputSpace") == outputSpace) { "胶片阶段表格式无效" }
        val count = size * size * size * 3
        require(json.getLong("bytes") == count.toLong() * Float.SIZE_BYTES) { "胶片阶段表大小无效" }
        val expectedHash = json.getString("sha256")
        require(SHA256.matches(expectedHash)) { "胶片阶段表缺少有效哈希" }
        fun axis(key: String): FloatArray {
            val array = json.getJSONArray(key)
            require(array.length() == 3) { "胶片采样轴数量无效" }
            return FloatArray(3) { array.getDouble(it).toFloat().also { value ->
                require(value.isFinite()) { "胶片采样轴不是有限值" }
            } }
        }
        val low = axis("minimum"); val high = axis("maximum")
        for (channel in 0..2) require(high[channel] > low[channel] && (high[channel] - low[channel]).isFinite()) {
            "胶片采样轴范围无效"
        }
        val values = FloatArray(count)
        val digest = MessageDigest.getInstance("SHA-256")
        assets.open("$ROOT/$stock/$name.f32").use { input ->
            val block = ByteArray(16 * 1024)
            val view = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
            var written = 0
            while (written < count) {
                coroutineContext.ensureActive()
                val needed = minOf(block.size, (count - written) * Float.SIZE_BYTES)
                var received = 0
                while (received < needed) {
                    coroutineContext.ensureActive()
                    val n = input.read(block, received, needed - received)
                    require(n > 0) { "胶片阶段表不完整" }
                    received += n
                }
                digest.update(block, 0, needed)
                view.position(0)
                repeat(needed / Float.SIZE_BYTES) {
                    val value = view.float
                    require(value.isFinite()) { "胶片阶段表包含无效数值" }
                    values[written++] = value
                }
            }
            require(input.read() == -1) { "胶片阶段表含多余数据" }
        }
        require(hex(digest.digest()) == expectedHash) { "胶片阶段表哈希不符" }
        return FilmStageTable(size, FilmTableDomain(low, high), values)
    }

    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        private const val ROOT = "film/mobile-v1"
        private const val SOURCE_COMMIT = "3bb2c2d2801ff68b92019cf1dbcbb133d60832bc"
        private const val MODEL_VERSION = "sf-3bb2c2d-three-stage-v1"
        private const val MAX_JSON_BYTES = 256 * 1024
        private const val MAX_MODEL_BYTES = 12L * 1024 * 1024
        private const val CACHE_BYTES = 4L * 1024 * 1024
        private const val CACHE_MODELS = 2
        private val ID = Regex("[a-z0-9_]{1,80}")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
