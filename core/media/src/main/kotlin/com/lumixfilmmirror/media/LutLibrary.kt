package com.lumixfilmmirror.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.lumixfilmmirror.model.LutRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class LutAsset internal constructor(val id: String, val name: String, val builtin: Boolean,
    internal val table: CubeLut, val photoStyle: String? = null, val lumixLabSource: LumixLabLutSource? = null)
data class LutLibrarySnapshot(val assets: List<LutAsset>, val problems: List<String>)

/** Copies user LUTs into application-owned storage; originals and creator files are never modified. */
class LutLibrary(context: Context) {
    private val context = context.applicationContext
    private val root = File(this.context.filesDir, "lut-library")
    private val official = LumixLabLutStore(root)
    private data class Bundled(val id: String, val name: String, val style: String? = null)
    private val bundled = listOf(
        Bundled("fresh-bright", "Fresh Bright", "STD"),
        Bundled("kitaura", "Kitaura", "STD"),
        Bundled("smoky-color-s", "Smoky Color-S", "STD"),
        Bundled("teal-flat-s", "Teal Flat-S", "STD"),
        Bundled("agfaphoto-lebox", "VOL 23 TXD AgfaPhoto LeBox"),
        Bundled("millennium-cold", "千禧回声 cold"),
        Bundled("millennium-warm", "千禧回声 warm"),
        Bundled("backlight-n", "Backlight-N", "NAT"),
        Bundled("clear-s", "Clear-S", "STD"),
        Bundled("color-harmony", "Color Harmony", "STD"),
    )

    suspend fun load(): LutLibrarySnapshot = withContext(Dispatchers.IO) {
        check(root.isDirectory || root.mkdirs()) { "无法创建 LUT 目录" }
        val assets = mutableListOf<LutAsset>()
        val problems = mutableListOf<String>()
        val parsingContext = coroutineContext
        for (entry in bundled) {
            coroutineContext.ensureActive()
            try {
                val text = context.assets.open("luts/${entry.name}.cube").bufferedReader(Charsets.UTF_8).use { it.readText() }
                val table = CubeLut.parse(text, entry.name) { parsingContext.ensureActive() }.named(entry.name)
                assets += LutAsset("bundled-${entry.id}", entry.name, true, table, entry.style)
            } catch (error: java.io.IOException) { problems += "${entry.name}：内置 LUT 读取失败" }
            catch (error: IllegalArgumentException) { problems += "${entry.name}：内置 LUT 数据无效" }
            catch (error: IllegalStateException) { problems += "${entry.name}：内置 LUT 格式不支持" }
        }
        root.listFiles().orEmpty().filter { it.name.matches(Regex("local-[0-9a-f]{64}\\.lfmlut(?:\\.bak)?")) }
            .map { it.name.removeSuffix(".bak").removeSuffix(".lfmlut") }.distinct().sorted().forEach { id ->
                coroutineContext.ensureActive()
                try {
                    val table = DataInputStream(AtomicFile(File(root, "$id.lfmlut")).openRead().buffered()).use { CubeLut.read(it) }
                    assets += LutAsset(id, table.title, false, table)
                } catch (error: java.io.IOException) { problems += "$id：LUT 读取失败" }
                catch (error: IllegalArgumentException) { problems += "$id：LUT 数据无效" }
            }
        val downloaded = official.load()
        LutLibrarySnapshot(assets + downloaded.assets, problems + downloaded.problems)
    }

    internal suspend fun importOfficial(detail: LumixLabLutDetail, cube: ByteArray): LutAsset = official.save(detail, cube)

    suspend fun import(uri: Uri): LutAsset = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }.orEmpty().ifBlank { "本地 LUT" }
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= 32L * 1024 * 1024) { "LUT 文件超过 32 MiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("无法读取 LUT 文件")
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val parsingContext = coroutineContext
        val isVlt = name.endsWith(".vlt", ignoreCase = true) || text.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
            .startsWith("# panasonic vlt file version", ignoreCase = true)
        val table = (if (isVlt) CubeLut.parseVlt(text, name) { parsingContext.ensureActive() }
            else CubeLut.parse(text, name) { parsingContext.ensureActive() })
            .named(name.replace(Regex("(?i)\\.(cube|vlt)$"), ""))
        coroutineContext.ensureActive()
        val id = "local-" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(root.isDirectory || root.mkdirs())
        val atomic = AtomicFile(File(root, "$id.lfmlut"))
        val output = atomic.startWrite()
        try {
            val data = DataOutputStream(output.buffered(64 * 1024))
            table.write(data); data.flush()
            coroutineContext.ensureActive()
            atomic.finishWrite(output)
        } catch (error: Throwable) { atomic.failWrite(output); throw error }
        LutAsset(id, table.title, false, table)
    }

    fun plan(recipe: LutRecipe, assets: List<LutAsset>): LutRenderPlan {
        fun table(id: String?): CubeLut? = id?.let { value ->
            requireNotNull(assets.firstOrNull { it.id == value }) { "所选 LUT 不可用，请重新选择；没有改用其他 LUT" }.table
        }
        return LutRenderPlan(table(recipe.first.id), recipe.first.strength, table(recipe.second.id), recipe.second.strength,
            if (recipe.grainEnabled) recipe.grain else 0f, recipe.ditheringEnabled)
    }
}
