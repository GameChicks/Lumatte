package com.lumixfilmmirror.media

import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Source information is retained with the LUT. No redistribution license is inferred from availability. */
data class LumixLabLutSource(val code: String, val title: String, val creatorCode: String?, val creatorName: String?,
    val overview: String?, val locale: String?, val photoStyle: String?, val grid17File: String,
    val cubeSha256: String, val thumbnailPath: String? = null, val creatorAvatarPath: String? = null)

/** One atomic container: metadata and parsed table cannot be committed separately. */
internal class LumixLabLutStore(private val root: File) {
    suspend fun load(): LutLibrarySnapshot = withContext(Dispatchers.IO) {
        gate.withLock {
            val assets = mutableListOf<LutAsset>()
            val problems = mutableListOf<String>()
            root.listFiles().orEmpty().filter { FILE.matches(it.name) }
                .map { it.name.removeSuffix(".bak").removeSuffix(".lfmlab") }.distinct().sorted().forEach { id ->
                    coroutineContext.ensureActive()
                    try {
                        val asset = DataInputStream(AtomicFile(File(root, "$id.lfmlab")).openRead().buffered()).use { input ->
                            require(input.readInt() == 1) { "官方 LUT 缓存版本不支持" }
                            val count = input.readInt()
                            require(count in 1..MAX_METADATA) { "官方 LUT 来源记录大小无效" }
                            val bytes = ByteArray(count).also(input::readFully)
                            val source = source(JSONObject(utf8(bytes)))
                            require(id == assetId(source)) { "官方 LUT 来源与标识不一致" }
                            val table = CubeLut.read(input)
                            require(table.nativeData.oneSize == 0 && table.nativeData.threeSize == 17) {
                                "官方 LUT 缓存规格不符"
                            }
                            LutAsset(id, table.title, false, table, source.photoStyle, source)
                        }
                        assets += asset
                    } catch (error: java.io.IOException) { problems += "$id：下载 LUT 读取失败" }
                    catch (error: IllegalArgumentException) { problems += "$id：下载 LUT 数据无效" }
                    catch (error: org.json.JSONException) { problems += "$id：下载 LUT 来源记录无效" }
                }
            LutLibrarySnapshot(assets, problems)
        }
    }

    suspend fun save(detail: LumixLabLutDetail, cube: ByteArray): LutAsset = withContext(Dispatchers.IO) {
        require(cube.size in 1..(4 * 1024 * 1024))
        val active = coroutineContext
        val table = CubeLut.parse(utf8(cube), detail.lut.title) { active.ensureActive() }.named(detail.lut.title)
        require(table.nativeData.oneSize == 0 && table.nativeData.threeSize == 17) { "下载文件并非已确认的 grid17 三维 LUT" }
        val source = LumixLabLutSource(detail.lut.code, detail.lut.title,
            detail.lut.creator?.code, detail.lut.creator?.name, detail.overview, detail.locale, detail.photoStyle,
            requireNotNull(detail.grid17), hash(cube), detail.lut.thumbnail?.path, detail.lut.creator?.icon?.path)
        val json = JSONObject().put("code", source.code).put("title", source.title)
            .put("creatorCode", source.creatorCode).put("creatorName", source.creatorName)
            .put("overview", source.overview).put("locale", source.locale).put("photoStyle", source.photoStyle)
            .put("grid17File", source.grid17File).put("cubeSha256", source.cubeSha256)
            .put("thumbnailPath", source.thumbnailPath).put("creatorAvatarPath", source.creatorAvatarPath)
        val metadata = json.toString().toByteArray(Charsets.UTF_8)
        require(metadata.size in 1..MAX_METADATA) { "官方 LUT 来源记录过大" }
        val id = assetId(source)
        gate.withLock {
            active.ensureActive()
            check(root.isDirectory || root.mkdirs()) { "无法创建 LUT 目录" }
            val atomic = AtomicFile(File(root, "$id.lfmlab"))
            val output = atomic.startWrite()
            try {
                val data = DataOutputStream(output.buffered(64 * 1024))
                data.writeInt(1); data.writeInt(metadata.size); data.write(metadata)
                table.write(data); data.flush()
                active.ensureActive()
                atomic.finishWrite(output)
            } catch (error: Throwable) { atomic.failWrite(output); throw error }
            LutAsset(id, table.title, false, table, source.photoStyle, source)
        }
    }

    private fun source(json: JSONObject): LumixLabLutSource {
        fun field(key: String, maximum: Int, required: Boolean = false): String? {
            val value = if (!json.has(key) || json.isNull(key)) null else {
                json.opt(key) as? String ?: throw IllegalArgumentException("来源字段格式无效")
            }
            require(value == null || value.length <= maximum)
            require(!required || !value.isNullOrBlank())
            return value
        }
        val hash = requireNotNull(field("cubeSha256", 64, true))
        require(HASH.matches(hash))
        return LumixLabLutSource(requireNotNull(field("code", 256, true)), requireNotNull(field("title", 1024, true)),
            field("creatorCode", 256), field("creatorName", 1024), field("overview", 32 * 1024),
            field("locale", 256), field("photoStyle", 256), requireNotNull(field("grid17File", 4096, true)), hash,
            field("thumbnailPath", 4096), field("creatorAvatarPath", 4096))
    }

    private fun assetId(source: LumixLabLutSource) = "lab-" + hash((source.code + "\u0000grid17\u0000" + source.cubeSha256).toByteArray(Charsets.UTF_8))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun utf8(bytes: ByteArray) = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()

    companion object {
        private const val MAX_METADATA = 128 * 1024
        private val FILE = Regex("lab-[0-9a-f]{64}\\.lfmlab(?:\\.bak)?")
        private val HASH = Regex("[0-9a-f]{64}")
        private val gate = Mutex()
    }
}
