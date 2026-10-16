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
    private data class Entry(val info: MobileFilmModelInfo, val manifestSha256: String, val root: String)

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
            require(entry.info.origin != FilmModelOrigin.PROJECT_MONOCHROME || preset.category == FilmCategory.MONOCHROME) {
                "自有黑白胶片分类不符"
            }
            cache[preset.id]?.let { return@withLock it }
            coroutineContext.ensureActive()
            val bytes = readSmall("${entry.root}/${preset.id}/model.json")
            require(hash(bytes) == entry.manifestSha256) { "胶片模型说明哈希不符" }
            val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
            val source = manifest.getJSONObject("source")
            require(manifest.getInt("schema") == 1 && manifest.getString("modelVersion") == entry.info.modelVersion &&
                manifest.getString("stockId") == preset.id && manifest.getString("printId") == preset.printId &&
                manifest.getBoolean("positive") == preset.positive &&
                manifest.getString("storage") == "float32_le_rgb_x_fastest" &&
                !manifest.getBoolean("spatialEffectsBaked") &&
                !manifest.getBoolean("grainBaked")) { "不支持此胶片模型来源／阶段格式" }
            when (entry.info.origin) {
                FilmModelOrigin.SPEKTRAFILM -> require(source.getString("commit") == SOURCE_COMMIT &&
                    source.getString("repository") == "https://github.com/andreavolpato/spektrafilm" &&
                    source.getString("license") == "CC-BY-SA-4.0" && manifest.getString("dir") == "baked_local_only") {
                    "不支持此光谱胶片模型来源"
                }
                FilmModelOrigin.PROJECT_MONOCHROME -> require(source.getString("origin") == "project_authored" &&
                    source.getString("author") == "Lumix Film Mirror" && source.getString("implementation") == "silver-negative-v1" &&
                    source.getString("license") == "GPL-3.0-only" && manifest.getString("dir") == "none_silver_density" &&
                    !manifest.getJSONObject("silverModel").getBoolean("commercialStockCalibration")) { "不支持此自有黑白模型来源" }
                FilmModelOrigin.FOTUFILM_MOBILE -> require(source.getString("origin") == "fotufilm_public_pack_adapter" &&
                    source.getString("commit") == FOTU_COMMIT && source.getString("license") == "CC-BY-SA-4.0" &&
                    source.getString("repository") == "https://github.com/DhaliwalX/fotufilm-engine" &&
                    manifest.getString("dir") == "baked_local_only" &&
                    manifest.getJSONObject("mobileAdaptation").getBoolean("monochrome") == (preset.category == FilmCategory.MONOCHROME)) {
                    "不支持此 Fotufilm 移动模型来源"
                }
            }
            val tables = manifest.getJSONObject("tables")
            val scanDescriptor = tables.getJSONObject("scan")
            val model = MobileFilmModel(entry.info,
                readTable(entry.root, preset.id, "response", tables.getJSONObject("response"), "srgb_encoded", "film_raw_linear"),
                readTable(entry.root, preset.id, "development", tables.getJSONObject("development"), "film_log10_exposure", "film_cmy_density"),
                readTable(entry.root, preset.id, "scan", scanDescriptor, "film_cmy_density", "srgb_encoded"),
                readEffects(manifest, scanDescriptor))
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
        val spectral = readIndex(ROOT, MODEL_VERSION, FilmModelOrigin.SPEKTRAFILM)
        val monochrome = readIndex(MONO_ROOT, MONO_VERSION, FilmModelOrigin.PROJECT_MONOCHROME)
        val fotufilm = readIndex(FOTU_ROOT, FOTU_VERSION, FilmModelOrigin.FOTUFILM_MOBILE)
        require((spectral.keys + monochrome.keys + fotufilm.keys).size == spectral.size + monochrome.size + fotufilm.size) {
            "移动胶片目录标识重复"
        }
        return (spectral + monochrome + fotufilm).also { entries = it }
    }

    private suspend fun readIndex(root: String, expectedVersion: String, origin: FilmModelOrigin): Map<String, Entry> {
        val json = JSONObject(readSmall("$root/index.json").toString(Charsets.UTF_8))
        require(json.getInt("schema") == 1 && json.getString("modelVersion") == expectedVersion &&
            when (origin) {
                FilmModelOrigin.SPEKTRAFILM -> json.getString("sourceCommit") == SOURCE_COMMIT
                FilmModelOrigin.PROJECT_MONOCHROME -> json.getString("origin") == "project_authored"
                FilmModelOrigin.FOTUFILM_MOBILE -> json.getString("origin") == "fotufilm_public_pack_adapter" &&
                    json.getString("sourceCommit") == FOTU_COMMIT
            }) { "不支持此移动胶片目录版本" }
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
            require(ID.matches(id) && ID.matches(printId) && version == expectedVersion &&
                size in 1..MAX_MODEL_BYTES && SHA256.matches(digest) && !result.containsKey(id)) { "移动胶片目录条目无效" }
            if (origin == FilmModelOrigin.PROJECT_MONOCHROME) require(id in MONO_STOCKS && printId == "lfm_silver_paper" &&
                !item.getBoolean("positive")) { "自有黑白目录条目无效" }
            if (origin == FilmModelOrigin.FOTUFILM_MOBILE) require(id in FOTU_STOCKS && printId == "fotu_fixed_print" &&
                !item.getBoolean("positive")) { "Fotufilm 移动目录条目无效" }
            result[id] = Entry(MobileFilmModelInfo(id, printId, item.getBoolean("positive"), version,
                when (origin) {
                    FilmModelOrigin.SPEKTRAFILM -> SOURCE_COMMIT
                    FilmModelOrigin.FOTUFILM_MOBILE -> FOTU_COMMIT
                    FilmModelOrigin.PROJECT_MONOCHROME -> null
                }, size, origin), digest, root)
        }
        return result.toMap()
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

    private suspend fun readTable(root: String, stock: String, name: String, json: JSONObject, inputSpace: String, outputSpace: String): FilmStageTable {
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
        assets.open("$root/$stock/$name.f32").use { input ->
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

    private fun readEffects(manifest: JSONObject, scan: JSONObject): MobileFilmEffects {
        val grain = manifest.getJSONObject("grainReference")
        val halation = manifest.getJSONObject("halationReference")
        fun scalar(json: JSONObject, key: String, low: Float, high: Float): Float =
            json.getDouble(key).toFloat().also { require(it.isFinite() && it in low..high) { "胶片效果参数无效：$key" } }
        fun triple(json: JSONObject, key: String, low: Float, high: Float): FloatArray {
            val array = json.getJSONArray(key)
            require(array.length() == 3) { "胶片效果通道数无效：$key" }
            return FloatArray(3) { array.getDouble(it).toFloat().also { value ->
                require(value.isFinite() && value in low..high) { "胶片效果参数无效：$key" }
            } }
        }
        // v1 generators reserve 0.2 above the declared density ceiling.
        val maximum = triple(scan, "maximum", .21f, 10f).also { for (c in 0..2) it[c] -= .2f }
        fun scatter(key: String, fallback: FloatArray, high: Float): FloatArray =
            if (halation.has(key)) triple(halation, key, 0f, high) else fallback
        require(halation.optBoolean("halation_renormalize", true)) { "移动模型需要归一化返光" }
        val cineStill = manifest.getString("stockId") in setOf("cinestill_800t", "cinestill_400d")
        val physical = if (cineStill) {
            val kernel = halation.getJSONObject("fotufilm_kernel")
            require(kernel.getString("algorithm") == "fotufilm-legacy-physical-v1" &&
                kernel.getInt("analysis_long_edge") == 1536) { "不支持的 CineStill 光晕求解" }
            fun rows(key: String, high: Float, unitSum: Boolean): FloatArray {
                val array = kernel.getJSONArray(key)
                require(array.length() == 3)
                val output = FloatArray(9)
                for (r in 0..2) {
                    val row = array.getJSONArray(r)
                    require(row.length() == 3)
                    for (c in 0..2) output[r * 3 + c] = row.getDouble(c).toFloat().also {
                        require(it.isFinite() && it >= -1e-6f && it <= high)
                    }
                    val sum = (0..2).sumOf { output[r * 3 + it].toDouble() }
                    require(if (unitSum) kotlin.math.abs(sum - 1) < 1e-5 else sum in 0.0..1.0)
                }
                return output
            }
            FotufilmHalationKernel(triple(kernel, "sigma_mm", .000001f, 2f),
                rows("weights", 1f, true), rows("mix_matrix", 1f, false))
        } else null
        return MobileFilmEffects(grain.getBoolean("active"), scalar(grain, "particle_area_um2", .0001f, 10f),
            triple(grain, "particle_scale", .0001f, 20f), triple(grain, "density_min", 0f, 1f),
            triple(grain, "uniformity", 0f, 1f), maximum,
            triple(halation, "halation_strength", 0f, 2f), triple(halation, "halation_first_sigma_um", 0f, 1000f),
            scalar(manifest.getJSONObject("settingsSnapshot").getJSONObject("camera"), "film_format_mm", 10f, 100f),
            halation.optInt("halation_n_bounces", 3).also { require(it in 1..8) },
            halation.optDouble("halation_bounce_decay", .5).toFloat().also { require(it.isFinite() && it in 0f..1f) },
            grain.optDouble("spacing_um", 0.0).toFloat().also { require(it.isFinite() && it in 0f..40f) },
            halation.optDouble("scatter_amount", 1.0).toFloat().also { require(it.isFinite() && it in 0f..1f) },
            scatter("scatter_core_um", floatArrayOf(2.2f, 2f, 1.6f), 1000f),
            scatter("scatter_tail_um", floatArrayOf(9.3f, 9.7f, 9.1f), 1000f),
            scatter("scatter_tail_weight", floatArrayOf(.78f, .65f, .67f), 1f), physical)
    }

    companion object {
        private const val ROOT = "film/mobile-v1"
        private const val SOURCE_COMMIT = "3bb2c2d2801ff68b92019cf1dbcbb133d60832bc"
        private const val MODEL_VERSION = "sf-3bb2c2d-three-stage-v1"
        private const val MONO_ROOT = "film/mobile-monochrome-v1"
        private const val MONO_VERSION = "lfm-silver-three-stage-v1"
        private val MONO_STOCKS = setOf("lfm_silver_neutral", "lfm_silver_contrast")
        private const val FOTU_ROOT = "film/mobile-fotufilm-v1"
        private const val FOTU_VERSION = "lfm-fotufilm-public-three-stage-v1"
        private const val FOTU_COMMIT = "12c8efea259e4cea70ec22166e504634d401a0cd"
        private val FOTU_STOCKS = setOf("cinestill_800t", "cinestill_400d", "fujifilm_acros_100_ii",
            "kodak_tri_x_400", "ilford_hp5_plus_400", "ilford_delta_3200")
        private const val MAX_JSON_BYTES = 256 * 1024
        private const val MAX_MODEL_BYTES = 12L * 1024 * 1024
        private const val CACHE_BYTES = 12L * 1024 * 1024
        private const val CACHE_MODELS = 2
        private val ID = Regex("[a-z0-9_]{1,80}")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
