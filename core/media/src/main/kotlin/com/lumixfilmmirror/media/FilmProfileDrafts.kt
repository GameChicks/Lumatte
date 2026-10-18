package com.lumixfilmmirror.media

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

enum class FilmProfileEvidence { PUBLISHED_DATA, CONTROLLED_MEASUREMENTS, CREATIVE_APPROXIMATION }
enum class FilmRecord { RED, GREEN, BLUE, SILVER }
data class FilmDataSource(val title: String, val url: String, val revision: String, val licence: String)
data class FilmDensityPoint(val logExposure: Double, val density: Double)
data class FilmDensityCurve(val record: FilmRecord, val points: List<FilmDensityPoint>)
data class FilmSpectralPoint(val wavelengthNm: Double, val value: Double)
data class FilmRecordSpectrum(val record: FilmRecord, val points: List<FilmSpectralPoint>)
data class FilmDevelopmentCondition(val process: String, val temperatureCelsius: Double?, val durationSeconds: Int?,
    val exposureIndex: Int?, val pushStops: Double, val outputMethod: String)
data class FilmDevelopmentVariant(val id: String, val name: String, val condition: FilmDevelopmentCondition,
    val densityCurves: List<FilmDensityCurve>, val evidence: FilmProfileEvidence, val sources: List<FilmDataSource>)
data class FilmProfileDraft(val id: String, val name: String, val manufacturer: String, val category: FilmCategory,
    val revision: String, val sensitivity: List<FilmRecordSpectrum>, val dyeDensity: List<FilmRecordSpectrum>,
    val baseDensity: List<FilmSpectralPoint>, val variants: List<FilmDevelopmentVariant>)
data class FilmProfileAssessment(val profile: FilmProfileDraft, val missing: List<String>,
    val nextStep: String, val availableAsRenderingPreset: Boolean = false)

/** Data collection for offline modelling. Never passes an uncalibrated draft off as a camera film preset. */
class FilmProfileDraftLibrary(context: Context) {
    private val root = File(context.applicationContext.filesDir, "film-profile-drafts-v1")
    private val mutex = Mutex()

    suspend fun save(profile: FilmProfileDraft): FilmProfileAssessment = withContext(Dispatchers.IO) {
        mutex.withLock {
            validate(profile)
            val content = encode(profile).toString().toByteArray(Charsets.UTF_8)
            require(content.size <= MAX_BYTES) { "胶卷资料超出 2 MB 上限" }
            check(root.isDirectory || root.mkdirs()) { "无法创建胶卷资料目录" }
            require(File(root, "${profile.id}.json").exists() || root.listFiles().orEmpty().count { it.name.endsWith(".json") } < 128)
            coroutineContext.ensureActive()
            val file = AtomicFile(File(root, "${profile.id}.json"))
            val output = file.startWrite()
            try { output.write(content); coroutineContext.ensureActive(); file.finishWrite(output) }
            catch (error: Throwable) { file.failWrite(output); throw error }
            assess(profile)
        }
    }

    suspend fun list(): List<FilmProfileAssessment> = withContext(Dispatchers.IO) {
        mutex.withLock {
            root.listFiles().orEmpty().filter { it.name.matches(Regex("[a-z0-9_]{1,80}\\.json(?:\\.bak)?")) }
                .map { it.name.removeSuffix(".bak").removeSuffix(".json") }.distinct().sorted().map { id ->
                    coroutineContext.ensureActive()
                    assess(read(id))
                }
        }
    }

    /** Transferable source data for a future desktop baker; no executable commands or mobile spectral render. */
    suspend fun exportSourceJson(id: String): String = withContext(Dispatchers.IO) {
        mutex.withLock { encode(read(id)).toString(2) }
    }

    /** Inspect a measured curve numerically. Does not turn it into a colour or film-processing model. */
    suspend fun densityAt(id: String, variantId: String, logExposure: Double): Map<FilmRecord, Double> = withContext(Dispatchers.IO) {
        require(logExposure.isFinite())
        mutex.withLock {
            val variant = read(id).variants.firstOrNull { it.id == variantId } ?: error("冲洗条件不存在")
            variant.densityCurves.associate { curve ->
                val points = curve.points
                val value = when {
                    logExposure <= points.first().logExposure -> points.first().density
                    logExposure >= points.last().logExposure -> points.last().density
                    else -> {
                        val upper = points.indexOfFirst { it.logExposure >= logExposure }
                        val a = points[upper - 1]; val b = points[upper]
                        val fraction = (logExposure - a.logExposure) / (b.logExposure - a.logExposure)
                        a.density * (1 - fraction) + b.density * fraction
                    }
                }
                curve.record to value
            }
        }
    }

    fun assess(profile: FilmProfileDraft): FilmProfileAssessment {
        validate(profile)
        val missing = mutableListOf<String>()
        if (profile.variants.isEmpty()) missing += "缺少冲洗条件与密度曲线"
        val records = if (profile.category == FilmCategory.MONOCHROME) setOf(FilmRecord.SILVER)
            else setOf(FilmRecord.RED, FilmRecord.GREEN, FilmRecord.BLUE)
        if (!profile.sensitivity.map { it.record }.containsAll(records)) missing += "缺少完整感光响应资料"
        if (profile.category != FilmCategory.MONOCHROME && !profile.dyeDensity.map { it.record }.containsAll(records))
            missing += "缺少各记录的染料密度光谱"
        if (profile.baseDensity.isEmpty()) missing += "缺少基底密度资料"
        if (profile.variants.any { !it.densityCurves.map { curve -> curve.record }.containsAll(records) })
            missing += "部分冲洗条件的密度曲线不完整"
        if (profile.variants.any { it.evidence != FilmProfileEvidence.CONTROLLED_MEASUREMENTS })
            missing += "部分条件尚未经过受控测量校准"
        missing += "尚未生成并核对移动三阶段模型及空间/颗粒参数"
        return FilmProfileAssessment(profile, missing.distinct(), "电脑端拟合、明确冲扫目标并生成移动数据包")
    }

    private fun read(id: String): FilmProfileDraft {
        require(ID.matches(id))
        val bytes = AtomicFile(File(root, "$id.json")).openRead().use { input ->
            val output = ByteArrayOutputStream()
            val block = ByteArray(8192)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES)
                output.write(block, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size <= MAX_BYTES)
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getInt("schema") == 1 && json.getString("id") == id)
        fun points(array: JSONArray) = (0 until array.length()).map {
            val p = array.getJSONObject(it); FilmSpectralPoint(p.getDouble("nm"), p.getDouble("value"))
        }
        fun spectra(key: String): List<FilmRecordSpectrum> {
            val a = json.getJSONArray(key)
            return (0 until a.length()).map { val s = a.getJSONObject(it)
                FilmRecordSpectrum(FilmRecord.valueOf(s.getString("record")), points(s.getJSONArray("points"))) }
        }
        val variants = json.getJSONArray("variants")
        val profile = FilmProfileDraft(id, json.getString("name"), json.getString("manufacturer"),
            FilmCategory.valueOf(json.getString("category")), json.getString("revision"),
            spectra("sensitivity"), spectra("dyeDensity"), points(json.getJSONArray("baseDensity")),
            (0 until variants.length()).map { index ->
                val v = variants.getJSONObject(index); val c = v.getJSONObject("condition")
                val curves = v.getJSONArray("curves"); val sources = v.getJSONArray("sources")
                FilmDevelopmentVariant(v.getString("id"), v.getString("name"),
                    FilmDevelopmentCondition(c.getString("process"), if (c.isNull("temperatureCelsius")) null else c.getDouble("temperatureCelsius"),
                        if (c.isNull("durationSeconds")) null else c.getInt("durationSeconds"),
                        if (c.isNull("exposureIndex")) null else c.getInt("exposureIndex"), c.getDouble("pushStops"), c.getString("outputMethod")),
                    (0 until curves.length()).map { val curve = curves.getJSONObject(it); val ps = curve.getJSONArray("points")
                        FilmDensityCurve(FilmRecord.valueOf(curve.getString("record")), (0 until ps.length()).map { n ->
                            val p = ps.getJSONObject(n); FilmDensityPoint(p.getDouble("logExposure"), p.getDouble("density")) }) },
                    FilmProfileEvidence.valueOf(v.getString("evidence")), (0 until sources.length()).map {
                        val s = sources.getJSONObject(it); FilmDataSource(s.getString("title"), s.getString("url"), s.getString("revision"), s.getString("licence")) })
            })
        validate(profile)
        return profile
    }

    private fun encode(p: FilmProfileDraft): JSONObject {
        fun <T> array(values: List<T>, convert: (T) -> JSONObject) = JSONArray().apply { values.forEach { put(convert(it)) } }
        fun points(ps: List<FilmSpectralPoint>) = array(ps) { JSONObject().put("nm", it.wavelengthNm).put("value", it.value) }
        fun spectra(ss: List<FilmRecordSpectrum>) = array(ss) { JSONObject().put("record", it.record.name).put("points", points(it.points)) }
        return JSONObject().put("schema", 1).put("id", p.id).put("name", p.name).put("manufacturer", p.manufacturer)
            .put("category", p.category.name).put("revision", p.revision).put("sensitivity", spectra(p.sensitivity))
            .put("dyeDensity", spectra(p.dyeDensity)).put("baseDensity", points(p.baseDensity))
            .put("variants", array(p.variants) { v ->
                val c = v.condition
                JSONObject().put("id", v.id).put("name", v.name).put("evidence", v.evidence.name)
                    .put("condition", JSONObject().put("process", c.process).put("temperatureCelsius", c.temperatureCelsius ?: JSONObject.NULL)
                        .put("durationSeconds", c.durationSeconds ?: JSONObject.NULL).put("exposureIndex", c.exposureIndex ?: JSONObject.NULL)
                        .put("pushStops", c.pushStops).put("outputMethod", c.outputMethod))
                    .put("curves", array(v.densityCurves) { curve -> JSONObject().put("record", curve.record.name)
                        .put("points", array(curve.points) { JSONObject().put("logExposure", it.logExposure).put("density", it.density) }) })
                    .put("sources", array(v.sources) { JSONObject().put("title", it.title).put("url", it.url)
                        .put("revision", it.revision).put("licence", it.licence) })
            })
    }

    private fun validate(p: FilmProfileDraft) {
        require(ID.matches(p.id) && p.name.length in 1..200 && p.manufacturer.length in 1..200 && p.revision.length in 1..200)
        require(p.variants.size <= 16 && p.variants.map { it.id }.distinct().size == p.variants.size)
        val records = if (p.category == FilmCategory.MONOCHROME) setOf(FilmRecord.SILVER)
            else setOf(FilmRecord.RED, FilmRecord.GREEN, FilmRecord.BLUE)
        require(p.sensitivity.all { it.record in records } && p.dyeDensity.all { it.record in records })
        require(p.category != FilmCategory.MONOCHROME || p.dyeDensity.isEmpty())
        fun spectra(ss: List<FilmRecordSpectrum>) {
            require(ss.size <= 4 && ss.map { it.record }.distinct().size == ss.size)
            ss.forEach { spectrum -> spectralPoints(spectrum.points) }
        }
        spectra(p.sensitivity); spectra(p.dyeDensity)
        if (p.baseDensity.isNotEmpty()) spectralPoints(p.baseDensity)
        p.variants.forEach { v ->
            require(ID.matches(v.id) && v.name.length in 1..200 && v.sources.size in 1..32)
            v.sources.forEach { require(it.title.length in 1..400 && it.url.length <= 2048 &&
                (it.url.isBlank() || java.net.URI(it.url).scheme in setOf("https", "http")) &&
                it.revision.length in 1..200 && it.licence.length in 1..500) }
            val c = v.condition
            require(c.process.length in 1..200 && c.outputMethod.length in 1..400 && c.pushStops.isFinite() && c.pushStops in -5.0..5.0)
            require(c.temperatureCelsius == null || (c.temperatureCelsius.isFinite() && c.temperatureCelsius in 0.0..100.0))
            require(c.durationSeconds == null || c.durationSeconds in 1..86400)
            require(c.exposureIndex == null || c.exposureIndex in 1..102400)
            require(v.densityCurves.size in 1..4 && v.densityCurves.map { it.record }.distinct().size == v.densityCurves.size)
            require(v.densityCurves.all { it.record in records })
            v.densityCurves.forEach { curve ->
                require(curve.points.size in 2..512)
                require(curve.points.all { it.logExposure.isFinite() && it.logExposure in -20.0..20.0 && it.density.isFinite() && it.density in 0.0..10.0 })
                require(curve.points.zipWithNext().all { (a, b) -> b.logExposure > a.logExposure })
            }
        }
    }
    private fun spectralPoints(points: List<FilmSpectralPoint>) {
        require(points.size in 2..512 && points.all { it.wavelengthNm.isFinite() && it.wavelengthNm in 300.0..900.0 &&
            it.value.isFinite() && it.value >= 0 && it.value <= 100 })
        require(points.zipWithNext().all { (a, b) -> b.wavelengthNm > a.wavelengthNm })
    }
    companion object { private val ID = Regex("[a-z0-9_]{1,80}"); private const val MAX_BYTES = 2 * 1024 * 1024 }
}
