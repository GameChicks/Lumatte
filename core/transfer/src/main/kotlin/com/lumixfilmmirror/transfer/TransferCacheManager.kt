package com.lumixfilmmirror.transfer

import com.lumixfilmmirror.media.VideoPublisher
import com.lumixfilmmirror.storage.TransferTask
import com.lumixfilmmirror.storage.WorkspaceStore
import com.lumixfilmmirror.storage.expectedMedia
import com.lumixfilmmirror.storage.terminal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlin.coroutines.coroutineContext

data class TransferCacheSnapshot(
    val privateBytes: Long,
    val reclaimablePrivateBytes: Long,
    val reclaimablePrivateFiles: Int,
    val pendingVideoBytes: Long,
    val reclaimablePendingVideoBytes: Long,
    val reclaimablePendingVideos: Int,
    val retainedTasks: Int,
    val problems: List<String>,
) {
    val canClean: Boolean get() = reclaimablePrivateFiles > 0 || reclaimablePendingVideos > 0
}

data class TransferCacheCleanup(
    val filesRemoved: Int,
    val privateBytesReleased: Long,
    val pendingVideosRemoved: Int,
    val pendingVideoBytesReleased: Long,
    val remaining: TransferCacheSnapshot,
    val problems: List<String>,
)

/** Caller must reserve the process controller before inspection/cleanup; no transfer runs concurrently. */
class TransferCacheManager(private val store: WorkspaceStore, private val videos: VideoPublisher) {
    private val partialName = Regex("[0-9a-f]{64}\\.(part|validator)")

    suspend fun inspect(): TransferCacheSnapshot = withContext(Dispatchers.IO) {
        var privateBytes = 0L
        var reclaimablePrivateBytes = 0L
        var reclaimablePrivateFiles = 0
        var pendingBytes = 0L
        var reclaimablePendingBytes = 0L
        var reclaimablePendingVideos = 0
        var retainedTasks = 0
        val problems = mutableListOf<String>()
        for (task in store.transferSnapshot()) {
            coroutineContext.ensureActive()
            try {
                val files = managedFiles(task)
                val bytes = files.sumOf { Files.size(it.toPath()) }
                privateBytes += bytes
                val pending = task.expectedMedia().transferResource?.videoContainer?.let { format ->
                    videos.pending(task.id, format, task.savedUri)
                }
                pendingBytes += pending?.bytes ?: 0
                if (files.isEmpty() && pending == null) continue
                val eligible = mayDiscard(task)
                if (eligible) {
                    reclaimablePrivateBytes += bytes
                    reclaimablePrivateFiles += files.size
                    if (pending != null) {
                        reclaimablePendingBytes += pending.bytes
                        reclaimablePendingVideos++
                    }
                } else if (bytes > 0 || pending != null) retainedTasks++
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { retainedTasks++; problems += problem(task, error) }
        }
        TransferCacheSnapshot(privateBytes, reclaimablePrivateBytes, reclaimablePrivateFiles, pendingBytes,
            reclaimablePendingBytes, reclaimablePendingVideos, retainedTasks, problems)
    }

    suspend fun clean(): TransferCacheCleanup = withContext(Dispatchers.IO) {
        var filesRemoved = 0
        var privateBytesReleased = 0L
        var pendingVideosRemoved = 0
        var pendingVideoBytesReleased = 0L
        val problems = mutableListOf<String>()
        // Fresh persisted states, never a stale UI snapshot. Only terminal tasks are candidates.
        for (task in store.transferSnapshot().filter { it.terminal }) {
            coroutineContext.ensureActive()
            try {
                val files = managedFiles(task)
                val format = task.expectedMedia().transferResource?.videoContainer
                if (files.isEmpty() && (format == null || videos.pending(task.id, format, task.savedUri) == null)) continue
                if (!mayDiscard(task)) continue
                format?.let { videoFormat ->
                    videos.discardPending(task.id, videoFormat, task.savedUri)?.let {
                        pendingVideosRemoved++
                        pendingVideoBytesReleased += it.bytes
                    }
                }
                for (file in files) {
                    coroutineContext.ensureActive()
                    val bytes = Files.size(file.toPath())
                    if (Files.deleteIfExists(file.toPath())) {
                        filesRemoved++
                        privateBytesReleased += bytes
                    }
                }
                // Delete only this known empty directory. Unexpected entries are never traversed/deleted.
                val directory = store.taskDirectory(task)
                if (directory.isDirectory && directory.list()?.isEmpty() == true) directory.delete()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { problems += problem(task, error) }
        }
        TransferCacheCleanup(filesRemoved, privateBytesReleased, pendingVideosRemoved,
            pendingVideoBytesReleased, inspect(), problems)
    }

    private suspend fun mayDiscard(task: TransferTask): Boolean = when (task.state) {
        "CANCELLED" -> true
        "IMPORTED" -> {
            val photo = store.source(task.id)
            check(photo != null && photo.jpeg.isFile && photo.jpeg.length() > 0) {
                "导入副本缺失，保留最后的下载文件"
            }
            true
        }
        "SAVED" -> {
            val format = requireNotNull(task.expectedMedia().transferResource?.videoContainer)
            check(videos.published(task.id, format, task.savedUri, task.bytesReceived) != null) {
                "已保存视频不可用，保留下载文件"
            }
            true
        }
        else -> false
    }

    private fun managedFiles(task: TransferTask): List<File> {
        val directory = store.taskDirectory(task)
        // No recursive delete, symlink following or sweeping unregistered task directories.
        check(directory.canonicalFile == directory.absoluteFile && !Files.isSymbolicLink(directory.toPath())) {
            "传输目录地址异常，未清理"
        }
        if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) return emptyList()
        check(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) { "传输目录不可用" }
        val entries = directory.listFiles() ?: error("无法读取传输目录")
        check(entries.all { (it.name == "complete.download" || partialName.matches(it.name)) &&
            Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) && it.canonicalFile.parentFile == directory.canonicalFile }) {
            "传输目录包含未识别文件，未清理此任务"
        }
        return entries.toList()
    }

    private fun problem(task: TransferTask, error: Exception): String =
        "${task.originalName ?: task.title}：${error.message ?: "无法整理传输缓存"}"
}
