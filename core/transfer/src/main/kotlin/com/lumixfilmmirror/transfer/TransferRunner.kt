package com.lumixfilmmirror.transfer

import android.net.Uri
import android.os.SystemClock
import com.lumixfilmmirror.camera.*
import com.lumixfilmmirror.media.ImageImporter
import com.lumixfilmmirror.media.VideoPublisher
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
data class TransferResult(val imported: Int, val failed: Int, val waiting: Int, val savedVideos: Int)
enum class TransferPhase { RESOLVING, DOWNLOADING, IMPORTING_PHOTO, SAVING_VIDEO, CONFIRMING_CAMERA }

/** One runner owns a batch. Files and queue state survive its process; network/session objects do not. */
class TransferRunner(private val store: WorkspaceStore, private val importer: ImageImporter,
    private val videoPublisher: VideoPublisher) {
    suspend fun run(batchId: String, session: LumixCameraSession?,
        onPhase: (TransferTask, TransferPhase) -> Unit = { _, _ -> },
        onProgress: (TransferTask, DownloadProgress) -> Unit = { _, _ -> }): TransferResult {
        check(store.incoming(batchId) == null && session?.accessMode?.incoming != true) {
            "待发送任务必须通过接收器执行，不能跳过相机确认记录"
        }
        return execute(batchId, session, null, null, onPhase, onProgress)
    }

    suspend fun runIncomingItem(run: IncomingTransfer, task: TransferTask, session: LumixCameraSession,
        onPhase: (TransferTask, TransferPhase) -> Unit,
        onProgress: (TransferTask, DownloadProgress) -> Unit): TransferResult {
        val directory = session.preparedIncomingDirectory()
        require(run.belongsTo(session.device) && run.accessMode == session.accessMode &&
            run.directoryId == directory.objectId && task.batchId == run.id && run.currentTaskId == task.id)
        return execute(run.id, session, task.id, directory.objectId, onPhase, onProgress)
    }

    suspend fun requireSavedResult(task: TransferTask) {
        check(task.completed) { "文件尚未保存" }
        val format = task.expectedMedia().transferResource?.videoContainer
        if (format == null) {
            val photo = requireNotNull(store.source(task.id)) { "已保存照片记录缺失" }
            require(withContext(Dispatchers.IO) { photo.jpeg.isFile && photo.jpeg.length() > 0 }) { "已保存照片副本缺失" }
        } else require(videoPublisher.published(task.id, format, task.savedUri, task.bytesReceived) != null) {
            "已保存视频暂不可访问，未发送相机确认"
        }
    }

    private suspend fun execute(batchId: String, session: LumixCameraSession?, onlyTaskId: String?,
        receivingObjectId: String?, onPhase: (TransferTask, TransferPhase) -> Unit,
        onProgress: (TransferTask, DownloadProgress) -> Unit): TransferResult {
        val tasks = store.batch(batchId).filter { !it.terminal && (onlyTaskId == null || it.id == onlyTaskId) }
        val resolved = mutableMapOf<String, Map<String, CameraMedia>>()
        var imported = 0
        var failed = 0
        var waiting = 0
        var savedVideos = 0
        try {
            for (task in tasks) {
                currentCoroutineContext().ensureActive()
                var received = task.bytesReceived
                val directory = store.taskDirectory(task)
                var finished = File(directory, "complete.download")
                try {
                    val expected = requireNotNull(task.expectedMedia().transferResource)
                    val videoFormat = expected.videoContainer
                    if (videoFormat != null) {
                        onPhase(task, TransferPhase.SAVING_VIDEO)
                        val published = videoPublisher.published(task.id, videoFormat, task.savedUri, received)
                        if (published != null) {
                            withContext(NonCancellable) { store.finishVideo(task, published.toString(), received) }
                            withContext(Dispatchers.IO) { finished.delete() }
                            savedVideos++
                            continue // Published before process exit; never copy a second video.
                        }
                    } else {
                        onPhase(task, TransferPhase.IMPORTING_PHOTO)
                        val recovered = store.source(task.id) ?: importer.recoverImport(PhotoId(task.id))
                        if (recovered != null) {
                            require(withContext(Dispatchers.IO) { recovered.jpeg.isFile }) { "已导入照片文件缺失" }
                            received = withContext(Dispatchers.IO) { if (finished.isFile) finished.length() else received }
                            withContext(NonCancellable) { store.finish(task, recovered, received) }
                            withContext(Dispatchers.IO) { finished.delete() }
                            imported++
                            continue
                        }
                    }
                    if (!withContext(Dispatchers.IO) { finished.isFile && finished.length() > 0 }) {
                        if (session == null || !task.belongsTo(session.device)) {
                            store.checkpoint(task, TransferState.PAUSED, received, "请连接此批任务对应的相机后继续")
                            waiting++
                            continue
                        }
                        onPhase(task, TransferPhase.RESOLVING)
                        val candidates = resolved[task.parentId] ?: run {
                            resolveFolder(session, tasks.filter { it.parentId == task.parentId },
                                receivingObjectId ?: task.parentId).also { resolved[task.parentId] = it }
                        }
                        val media = candidates[task.itemId]
                        require(media != null && media.matchesPreviousMedia(task.expectedMedia())) { "相机中的媒体缺失或已变化，请重新选择" }
                        val resource = requireNotNull(media.transferResource)
                        store.checkpoint(task, TransferState.RUNNING, received)
                        onPhase(task, TransferPhase.DOWNLOADING)
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
                    if (videoFormat != null) {
                        onPhase(task, TransferPhase.SAVING_VIDEO)
                        videoPublisher.publish(task.id, videoFormat, finished, task.savedUri,
                            onReserved = { reserved, bytes ->
                                withContext(NonCancellable) { store.reserveVideo(task, reserved.toString(), bytes) }
                            }, onPublished = { uri, bytes -> store.finishVideo(task, uri.toString(), bytes) })
                        savedVideos++
                    } else {
                        onPhase(task, TransferPhase.IMPORTING_PHOTO)
                        val photo = importer.importImage(Uri.fromFile(finished), task.originalName ?: task.title,
                            rawEmbeddedPreview = expected.kind == CameraResourceKind.RAW_PREVIEW_JPEG,
                            photoId = PhotoId(task.id))
                        withContext(NonCancellable) { store.finish(task, photo, received) }
                        imported++
                    }
                    withContext(Dispatchers.IO) { finished.delete() }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    if (error is CameraProtocolException && error.requiresReconnect) {
                        store.checkpoint(task, TransferState.PAUSED, received, "相机授权已失效，重新连接后继续")
                        store.stopBatch(batchId, false, "相机授权已失效，重新连接后继续")
                        throw error // Stop the batch; do not mark every following photo as failed.
                    }
                    store.checkpoint(task, TransferState.FAILED, received, error.message ?: "传输或保存失败")
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
        return TransferResult(imported, failed, waiting, savedVideos)
    }

    private suspend fun resolveFolder(session: LumixCameraSession, tasks: List<TransferTask>, objectId: String): Map<String, CameraMedia> {
        val wanted = tasks.map { it.itemId }.toSet()
        val found = mutableMapOf<String, CameraMedia>()
        var offset = 0
        var updateId: String? = null
        while (true) {
            val page = session.browse(objectId, offset)
            if (offset != 0) require(page.updateId == updateId) { "相机列表在读取时发生变化，请刷新后重试" }
            updateId = page.updateId
            page.entries.filter { it.id in wanted }.forEach { found[it.id] = it }
            if (found.keys.containsAll(wanted)) break
            offset = page.nextOffset ?: break
        }
        return found
    }
}
