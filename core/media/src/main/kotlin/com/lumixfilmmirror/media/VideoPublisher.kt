package com.lumixfilmmirror.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.lumixfilmmirror.model.VideoContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Copies original video bytes. Deterministic row identity closes the insert-before-DB-commit gap. */
class VideoPublisher(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val path = "${Environment.DIRECTORY_MOVIES}/LumixFilmMirror/"
    private data class Row(val uri: Uri, val pending: Boolean, val name: String, val path: String)
    data class PendingVideo(val bytes: Long)

    suspend fun pending(id: String, format: VideoContainer, knownUri: String?): PendingVideo? = withContext(Dispatchers.IO) {
        val row = find(id, format, knownUri) ?: return@withContext null
        if (!row.pending) return@withContext null
        require(row.name == name(id, format) && row.path == path) { "未发布视频的位置已变化，缓存清理未修改该文件" }
        PendingVideo(length(row.uri).also { require(it >= 0) { "无法读取未发布视频尺寸" } })
    }

    /** Only a terminal task may call this, while the process transfer controller is idle. */
    suspend fun discardPending(id: String, format: VideoContainer, knownUri: String?): PendingVideo? = withContext(Dispatchers.IO) {
        val row = find(id, format, knownUri) ?: return@withContext null
        if (!row.pending) return@withContext null
        require(row.name == name(id, format) && row.path == path) { "未发布视频的位置已变化，缓存清理未修改该文件" }
        val bytes = length(row.uri).also { require(it >= 0) { "无法读取未发布视频尺寸" } }
        coroutineContext.ensureActive()
        // Recheck ownership, identity and pending state in the deletion itself. Never delete visible media.
        val removed = resolver.delete(row.uri,
            "${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(context.packageName, name(id, format), path))
        require(removed in 0..1) { "未发布视频清理结果异常" }
        if (removed == 1) PendingVideo(bytes) else null
    }

    suspend fun published(id: String, format: VideoContainer, knownUri: String?, bytes: Long): Uri? =
        withContext(Dispatchers.IO) {
            val row = find(id, format, knownUri) ?: return@withContext null
            if (row.pending) return@withContext null
            require(bytes > 0 && length(row.uri) == bytes) { "已保存的视频尺寸与任务记录不一致，未覆盖该文件" }
            row.uri
        }

    suspend fun publish(id: String, format: VideoContainer, file: File, knownUri: String?,
        onReserved: suspend (Uri, Long) -> Unit, onPublished: suspend (Uri, Long) -> Unit): Uri = withContext(Dispatchers.IO) {
        val bytes = file.length()
        require(file.isFile && bytes > 0) { "完整视频下载文件缺失" }
        val existing = find(id, format, knownUri)
        if (existing != null && !existing.pending) {
            require(length(existing.uri) == bytes) { "已保存的视频尺寸发生变化，未覆盖该文件" }
            withContext(NonCancellable) { onPublished(existing.uri, bytes) }
            return@withContext existing.uri
        }
        val name = name(id, format)
        if (existing != null) require(existing.name == name && existing.path == path) { "保存中的视频位置已变化，未覆盖该文件" }
        val uri = existing?.uri ?: resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("无法创建相册视频")
        // Persist the URI before copying. On interruption the owned pending row is retained for retry.
        onReserved(uri, bytes)
        coroutineContext.ensureActive()
        val descriptor = resolver.openFileDescriptor(uri, "rwt") ?: error("无法写入相册视频")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    require(copied <= bytes) { "视频副本在保存时发生变化" }
                }
                require(copied == bytes) { "视频副本尚未完整" }
                output.fd.sync()
            }
        }
        coroutineContext.ensureActive()
        require(length(uri) == bytes) { "相册视频尚未完整" }
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
            "无法完成相册视频保存"
        }
        // Once visible, cancellation must not leave this item labelled as cancelled in the queue.
        withContext(NonCancellable) { onPublished(uri, bytes) }
        uri
    }

    private fun name(id: String, format: VideoContainer): String {
        require(UUID.fromString(id).toString() == id)
        return "LFM-$id.${format.extension}"
    }

    private fun find(id: String, format: VideoContainer, knownUri: String?): Row? {
        val name = name(id, format)
        if (knownUri != null) {
            val uri = Uri.parse(knownUri)
            require(uri.scheme == collection.scheme && uri.authority == collection.authority &&
                uri.pathSegments.dropLast(1) == collection.pathSegments &&
                uri.lastPathSegment?.toLongOrNull()?.let { it > 0 } == true && uri.query == null && uri.fragment == null) {
                "视频保存地址不匹配"
            }
            query(uri, null, null).singleOrNull()?.let { return it }
        }
        val rows = query(collection,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            arrayOf(name, path, context.packageName))
        require(rows.size <= 1) { "视频保存记录重复，请检查相册；没有创建新副本" }
        return rows.singleOrNull()
    }

    private fun query(uri: Uri, selection: String?, args: Array<String>?): List<Row> {
        val options = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            if (args != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        }
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        return resolver.query(uri, projection, options, null)?.use { cursor ->
            val rows = mutableListOf<Row>()
            while (cursor.moveToNext()) {
                require(cursor.getString(4) == context.packageName) { "视频保存记录不属于本应用，未修改该文件" }
                rows += Row(ContentUris.withAppendedId(collection, cursor.getLong(0)), cursor.getInt(1) != 0,
                    cursor.getString(2).orEmpty(), cursor.getString(3).orEmpty())
            }
            rows
        } ?: error("无法读取相册视频保存记录")
    }

    private fun length(uri: Uri): Long = resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        ?: error("无法读取已保存视频的尺寸")
}
