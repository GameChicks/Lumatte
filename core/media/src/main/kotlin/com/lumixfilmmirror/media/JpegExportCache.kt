package com.lumixfilmmirror.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Shared across writer instances, including a newly created ViewModel restoring the workspace. */
internal object JpegExportAccess { val mutex = Mutex() }

data class JpegExportCacheSnapshot(
    val temporaryBytes: Long,
    val temporaryFiles: Int,
    val pendingBytes: Long,
    val pendingImages: Int,
    val unknownPendingSizes: Int,
    val problems: List<String>,
) {
    val canClean: Boolean get() = temporaryFiles > 0 || pendingImages > 0
}

data class JpegExportCleanup(val temporaryFilesRemoved: Int, val pendingImagesRemoved: Int,
    val remaining: JpegExportCacheSnapshot, val problems: List<String>)

/** Only known encoder files and owned, still-pending JPEG rows. Never sweeps a chosen SAF folder. */
class JpegExportCache(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    internal val collection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    internal val galleryPath = "${Environment.DIRECTORY_PICTURES}/LumixFilmMirror/"
    private val rawDirectory = File(this.context.cacheDir, "jpeg-export")
    private val uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val temporaryName = Regex("output-(?:$uuid|-?[0-9]+)\\.(?:jpg|png)")
    private val outputName = Regex("LFM-[0-9]{8}-[0-9]{6}-[0-9]{3}-(?:$uuid|[0-9a-f]{8})\\.(?:jpg|png)")
    private val imageMime = setOf("image/jpeg", "image/png")
    private data class Pending(val uri: Uri, val name: String, val path: String, val bytes: Long?, val mimeType: String)

    internal fun newTemporaryFile(extension: String = "jpg"): File {
        require(extension in setOf("jpg", "png"))
        val directory = directory(create = true) ?: error("无法创建导出中间目录")
        return File(directory, "output-${UUID.randomUUID()}.$extension").also {
            check(it.createNewFile()) { "无法创建导出中间文件" }
        }
    }

    suspend fun inspect(): JpegExportCacheSnapshot = withContext(Dispatchers.IO) {
        JpegExportAccess.mutex.withLock { inspectUnlocked() }
    }

    suspend fun clean(): JpegExportCleanup = withContext(Dispatchers.IO) {
        JpegExportAccess.mutex.withLock {
            val problems = mutableListOf<String>()
            var filesRemoved = 0
            var imagesRemoved = 0
            try {
                for (file in managedFiles(problems)) {
                    coroutineContext.ensureActive()
                    try { if (Files.deleteIfExists(file.toPath())) filesRemoved++ }
                    catch (error: Exception) { problems += "JPEG 中间文件未清理：${error.message.orEmpty()}" }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { problems += "JPEG 中间目录未清理：${error.message.orEmpty()}" }
            try {
                for (row in pendingRows(problems)) {
                    coroutineContext.ensureActive()
                    try { imagesRemoved += discardPending(row.uri, row.name, row.path, row.mimeType) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { problems += "未发布图片未清理：${error.message.orEmpty()}" }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { problems += "无法整理未发布 JPG：${error.message.orEmpty()}" }
            JpegExportCleanup(filesRemoved, imagesRemoved, inspectUnlocked(), problems)
        }
    }

    /** Also used on live publication failure; predicates prevent deleting a row already made visible. */
    internal fun discardPending(uri: Uri, name: String, path: String = galleryPath,
        mimeType: String = "image/jpeg"): Int {
        require(outputName.matches(name) && path in setOf(galleryPath, galleryPath.dropLast(1)))
        require(mimeType in imageMime)
        require(uri.authority == collection.authority && uri.scheme == collection.scheme &&
            uri.pathSegments.dropLast(1) == collection.pathSegments && uri.lastPathSegment?.toLongOrNull()?.let { it > 0 } == true &&
            uri.query == null && uri.fragment == null)
        val removed = resolver.delete(uri,
            "${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                "${MediaStore.MediaColumns.MIME_TYPE} = ?",
            arrayOf(context.packageName, name, path, mimeType))
        check(removed in 0..1) { "未发布图片清理结果异常" }
        return removed
    }

    private suspend fun inspectUnlocked(): JpegExportCacheSnapshot {
        val problems = mutableListOf<String>()
        var temporaryBytes = 0L
        var temporaryFiles = 0
        var pendingBytes = 0L
        var pendingImages = 0
        var unknownSizes = 0
        try {
            for (file in managedFiles(problems)) {
                coroutineContext.ensureActive()
                temporaryBytes += Files.size(file.toPath())
                temporaryFiles++
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { problems += "无法读取 JPEG 中间目录：${error.message.orEmpty()}" }
        try {
            for (row in pendingRows(problems)) {
                coroutineContext.ensureActive()
                pendingImages++
                if (row.bytes == null) unknownSizes++ else pendingBytes += row.bytes
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { problems += "无法读取未发布 JPG：${error.message.orEmpty()}" }
        return JpegExportCacheSnapshot(temporaryBytes, temporaryFiles, pendingBytes, pendingImages, unknownSizes, problems)
    }

    private fun directory(create: Boolean): File? {
        check(!Files.isSymbolicLink(rawDirectory.toPath())) { "JPEG 中间目录是链接，未整理" }
        if (!Files.exists(rawDirectory.toPath(), NOFOLLOW_LINKS)) {
            if (!create) return null
            check(rawDirectory.mkdirs() || rawDirectory.isDirectory) { "无法创建 JPEG 中间目录" }
        }
        check(Files.isDirectory(rawDirectory.toPath(), NOFOLLOW_LINKS)) { "JPEG 中间目录不可用" }
        val directory = rawDirectory.canonicalFile
        check(directory.parentFile == context.cacheDir.canonicalFile) { "JPEG 中间目录地址异常" }
        return directory
    }

    private fun managedFiles(problems: MutableList<String>): List<File> {
        val directory = directory(create = false) ?: return emptyList()
        return (directory.listFiles() ?: error("无法读取 JPEG 中间目录")).filter { file ->
            val known = temporaryName.matches(file.name) && Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) &&
                file.canonicalFile.parentFile == directory
            if (!known) problems += "JPEG 中间目录包含未识别项目，已保留"
            known
        }
    }

    private suspend fun pendingRows(problems: MutableList<String>): List<Pending> {
        val options = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
                    "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?) AND " +
                    "${MediaStore.MediaColumns.MIME_TYPE} IN (?, ?)")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(context.packageName, galleryPath, galleryPath.dropLast(1), "image/jpeg", "image/png"))
        }
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.MIME_TYPE)
        return resolver.query(collection, projection, options, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    val name = cursor.getString(1).orEmpty()
                    val path = cursor.getString(2).orEmpty()
                    val mime = cursor.getString(5).orEmpty()
                    check(cursor.getString(3) == context.packageName && cursor.getInt(4) == 1 &&
                        mime in imageMime && path in setOf(galleryPath, galleryPath.dropLast(1))) {
                        "未发布图片范围不匹配，未清理"
                    }
                    if (!outputName.matches(name)) { problems += "相册中存在未识别的未发布图片，已保留"; continue }
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                    val bytes = try { resolver.openFileDescriptor(uri, "r")?.use { it.statSize }?.takeIf { it >= 0 } }
                        catch (_: Exception) { null }
                    add(Pending(uri, name, path, bytes, mime))
                }
            }
        } ?: error("无法读取相册 JPG 记录")
    }
}
