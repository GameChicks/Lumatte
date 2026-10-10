package com.lumixfilmmirror.transfer

import android.net.Uri
import android.os.SystemClock
import com.lumixfilmmirror.camera.*
import com.lumixfilmmirror.media.ImageImporter
import com.lumixfilmmirror.model.PhotoId
import com.lumixfilmmirror.storage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

class TransferStop(val cancelRemaining: Boolean, message: String) : CancellationException(message)
data class TransferResult(val imported: Int, val failed: Int, val waiting: Int)

/** One runner owns a batch. Files and queue state survive its process; network/session objects do not. */
class TransferRunner(private val store: WorkspaceStore, private val importer: ImageImporter) {
    suspend fun run(batchId: String, session: LumixCameraSession?,
        onProgress: (TransferTask, DownloadProgress) -> Unit = {}): TransferResult {
        val tasks = store.batch(batchId).filter { it.state !in setOf("IMPORTED", "CANCELLED") }
        val resolved = mutableMapOf<String, Map<String, CameraMedia>>()
        var imported = 0
        var failed = 0
        var waiting = 0
        try {
            for (task in tasks) {
                currentCoroutineContext().ensureActive()
                var received = task.bytesReceived
                val directory = store.taskDirectory(task)
                var finished = File(directory, "complete.download")
                try {
                    val recovered = store.source(task.id) ?: importer.recoverImport(PhotoId(task.id))
                    if (recovered != null) {
                        require(withContext(Dispatchers.IO) { recovered.jpeg.isFile }) { "已导入照片文件缺失" }
                        received = withContext(Dispatchers.IO) { if (finished.isFile) finished.length() else received }
                        withContext(NonCancellable) { store.finish(task, recovered, received) }
                        withContext(Dispatchers.IO) { finished.delete() }
                        imported++
                        continue
                    }
                    if (!withContext(Dispatchers.IO) { finished.isFile && finished.length() > 0 }) {
                        if (session == null || !task.belongsTo(session.device)) {
                            store.checkpoint(task, TransferState.PAUSED, received, "请连接此批任务对应的相机后继续")
                            waiting++
                            continue
                        }
                        val candidates = resolved[task.parentId] ?: run {
                            resolveFolder(session, tasks.filter { it.parentId == task.parentId }).also { resolved[task.parentId] = it }
                        }
                        val media = candidates[task.itemId]
                        require(media != null && media.matchesPreviousPhoto(task.expectedMedia())) { "相机中的照片缺失或已变化，请重新选择" }
                        val resource = requireNotNull(media.editableResource)
                        store.checkpoint(task, TransferState.RUNNING, received)
                        var checkpointAt = 0L
                        finished = session.download(resource, directory, durable = true) { progress ->
                            received = progress.bytes
                            onProgress(task, progress)
                            val now = SystemClock.elapsedRealtime()
                            if (now - checkpointAt >= 500) {
                                store.checkpoint(task, TransferState.RUNNING, received)
                                checkpointAt = now
                            }
                        }
                        received = withContext(Dispatchers.IO) { finished.length() }
                        store.checkpoint(task, TransferState.DOWNLOADED, received)
                    } else received = withContext(Dispatchers.IO) { finished.length() }
                    val photo = importer.importImage(Uri.fromFile(finished), task.originalName ?: task.title,
                        rawEmbeddedPreview = task.expectedMedia().editableResource?.kind == CameraResourceKind.RAW_PREVIEW_JPEG,
                        photoId = PhotoId(task.id))
                    withContext(NonCancellable) { store.finish(task, photo, received) }
                    withContext(Dispatchers.IO) { finished.delete() }
                    imported++
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    store.checkpoint(task, TransferState.FAILED, received, error.message ?: "传输或导入失败")
                    failed++
                }
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                store.stopBatch(batchId, (error as? TransferStop)?.cancelRemaining == true,
                    error.message ?: "传输已暂停，可继续未完成的任务")
            }
            throw error
        }
        return TransferResult(imported, failed, waiting)
    }

    private suspend fun resolveFolder(session: LumixCameraSession, tasks: List<TransferTask>): Map<String, CameraMedia> {
        val wanted = tasks.map { it.itemId }.toSet()
        val found = mutableMapOf<String, CameraMedia>()
        var offset = 0
        var updateId: String? = null
        while (true) {
            val page = session.browse(tasks.first().parentId, offset)
            if (offset != 0) require(page.updateId == updateId) { "相机列表在读取时发生变化，请刷新后重试" }
            updateId = page.updateId
            page.entries.filter { it.id in wanted }.forEach { found[it.id] = it }
            if (found.keys.containsAll(wanted)) break
            offset = page.nextOffset ?: break
        }
        return found
    }
}
