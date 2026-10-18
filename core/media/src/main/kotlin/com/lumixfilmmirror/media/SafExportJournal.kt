package com.lumixfilmmirror.media

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

enum class FolderExportStage { PREPARED, CREATED, COMMITTED }

data class FolderExportRecord(
    val id: String,
    val sourceName: String,
    val requestedName: String,
    val documentUri: Uri?,
    val stage: FolderExportStage,
    val expectedBytes: Long,
    val startedAt: Long,
)

data class FolderExportSnapshot(val records: List<FolderExportRecord>, val problems: List<String>)

/** Caller holds JpegExportAccess.mutex. Contains local records only; never scans or removes SAF documents. */
internal class SafExportJournal(context: Context) {
    private val context = context.applicationContext
    private val rawDirectory = File(this.context.filesDir, "folder-export-records")
    private val uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val knownName = Regex("($uuid)\\.json(?:\\.bak|\\.new)?")
    private data class Entry(val record: FolderExportRecord, val tree: Uri, val sourceId: String)

    fun prepare(source: SourceImage, name: String, tree: Uri, bytes: Long): FolderExportRecord {
        require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree) &&
            tree.query == null && tree.fragment == null && bytes > 0)
        val record = FolderExportRecord(UUID.randomUUID().toString(), source.name, name, null,
            FolderExportStage.PREPARED, bytes, System.currentTimeMillis())
        write(Entry(record, tree, source.id.value))
        return record
    }

    fun created(record: FolderExportRecord, uri: Uri): FolderExportRecord {
        val entry = read(record.id)
        check(entry.record == record && record.stage == FolderExportStage.PREPARED)
        validateDocument(uri, entry.tree, requireProvider = true)
        return record.copy(documentUri = uri, stage = FolderExportStage.CREATED).also {
            write(entry.copy(record = it))
        }
    }

    fun committed(record: FolderExportRecord) {
        val entry = read(record.id)
        check(entry.record == record && record.stage == FolderExportStage.CREATED && record.documentUri != null)
        write(entry.copy(record = record.copy(stage = FolderExportStage.COMMITTED)))
    }

    /** Removes this app's explanation only. Never interpreted as success or document deletion. */
    fun forget(id: String) {
        read(id) // A malformed or future-version record is retained, not silently removed.
        val file = atomic(id, create = false)
        val base = file.baseFile
        file.delete()
        check(listOf(base, File(base.path + ".bak"), File(base.path + ".new")).none { it.exists() }) {
            "应用内导出记录未完全移除，文件夹中的照片未删除"
        }
    }

    fun inspect(): FolderExportSnapshot {
        val problems = mutableListOf<String>()
        val records = mutableListOf<FolderExportRecord>()
        try {
            val folder = directory(create = false) ?: return FolderExportSnapshot(emptyList(), emptyList())
            val ids = linkedSetOf<String>()
            for (file in folder.listFiles() ?: error("无法读取文件夹导出记录")) {
                val match = knownName.matchEntire(file.name)
                if (match == null || !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.canonicalFile.parentFile != folder) {
                    problems += "文件夹导出记录中有未识别项目，已保留"
                    continue
                }
                ids += match.groupValues[1]
            }
            for (id in ids) {
                val base = File(folder, "$id.json")
                if (!base.exists() && !File(folder, "$id.json.bak").exists()) {
                    problems += "有尚未完成写入的本地记录，已保留"
                    continue
                }
                try { records += read(id).record }
                catch (_: Exception) { problems += "有文件夹导出记录无法读取，已保留；请自行核对所选文件夹" }
            }
        } catch (error: Exception) { problems += error.message ?: "无法读取文件夹导出记录" }
        return FolderExportSnapshot(records.sortedByDescending { it.startedAt }, problems.distinct())
    }

    private fun validateDocument(uri: Uri, tree: Uri, requireProvider: Boolean = false) {
        require(uri.scheme == "content" && uri.authority == tree.authority && uri.query == null && uri.fragment == null &&
            DocumentsContract.getDocumentId(uri).isNotBlank()) { "提供者返回的导出文档地址异常" }
        if (DocumentsContract.isTreeUri(uri)) require(DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getTreeDocumentId(tree))
        if (requireProvider) require(DocumentsContract.isDocumentUri(context, uri))
    }

    private fun write(entry: Entry) {
        val record = entry.record
        val json = JSONObject().put("version", 1).put("id", record.id).put("sourceId", entry.sourceId)
            .put("sourceName", record.sourceName).put("requestedName", record.requestedName)
            .put("treeUri", entry.tree.toString()).put("documentUri", record.documentUri?.toString() ?: JSONObject.NULL)
            .put("stage", record.stage.name).put("expectedBytes", record.expectedBytes).put("startedAt", record.startedAt)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 32 * 1024) { "文件夹导出记录过大" }
        val file = atomic(record.id, create = true)
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Throwable) { file.failWrite(output); throw error }
    }

    private fun read(id: String): Entry {
        val bytes = atomic(id, create = false).openRead().use { input ->
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(result.size() + count <= 32 * 1024)
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getInt("version") == 1 && json.getString("id") == id)
        val tree = Uri.parse(json.getString("treeUri"))
        require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree) && tree.query == null && tree.fragment == null)
        val uri = if (json.isNull("documentUri")) null else Uri.parse(json.getString("documentUri"))
        if (uri != null) validateDocument(uri, tree)
        val stage = FolderExportStage.valueOf(json.getString("stage"))
        require((stage == FolderExportStage.PREPARED) == (uri == null))
        val record = FolderExportRecord(id, json.getString("sourceName"), json.getString("requestedName"), uri, stage,
            json.getLong("expectedBytes"), json.getLong("startedAt"))
        require(record.expectedBytes > 0 && record.startedAt > 0)
        return Entry(record, tree, json.getString("sourceId"))
    }

    private fun atomic(id: String, create: Boolean): AtomicFile {
        require(Regex(uuid).matches(id))
        val folder = directory(create) ?: error("文件夹导出记录不存在")
        val base = File(folder, "$id.json")
        for (file in listOf(base, File(folder, "$id.json.bak"), File(folder, "$id.json.new"))) {
            check(!Files.isSymbolicLink(file.toPath())) { "文件夹导出记录是链接，已保留" }
            check(!file.exists() || Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "文件夹导出记录地址异常" }
        }
        return AtomicFile(base)
    }

    private fun directory(create: Boolean): File? {
        check(!Files.isSymbolicLink(rawDirectory.toPath())) { "文件夹导出记录目录是链接，已保留" }
        if (!rawDirectory.exists()) {
            if (!create) return null
            check(rawDirectory.mkdirs() || rawDirectory.isDirectory) { "无法创建文件夹导出记录目录" }
        }
        check(Files.isDirectory(rawDirectory.toPath(), NOFOLLOW_LINKS))
        val folder = rawDirectory.canonicalFile
        check(folder.parentFile == context.filesDir.canonicalFile)
        return folder
    }
}
