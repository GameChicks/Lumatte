package com.lumixfilmmirror.storage

import android.content.Context
import android.net.Uri
import com.lumixfilmmirror.media.ExportDestination
import com.lumixfilmmirror.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Persisted immutable requests and publication outcomes, independent of any screen. */
class ExportQueueStore(context: Context) {
    private val dao = WorkspaceDatabase.get(context.applicationContext).exports()
    val runs = dao.observe().map { rows -> rows.map(::run) }.flowOn(Dispatchers.Default)

    suspend fun initialize() = recoveryGate.withLock {
        if (!recovered) {
            dao.recoverInterrupted()
            // Backfill confirmed receipts from versions that left saved photos pending.
            dao.retireSavedPhotos(null, null)
            recovered = true
        }
    }

    suspend fun enqueue(batchId: String, photos: List<ExportRequestPhoto>, options: BatchExportOptions,
        destination: ExportDestination): ExportRun = withContext(Dispatchers.Default) {
        initialize()
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val row = ExportRunRow(id, batchId, now, now, "QUEUED", options.jpeg.dimensionPercent,
            options.jpeg.compressionQuality, FrameOptionsCodec.write(options.frame),
            (destination as? ExportDestination.Folder)?.treeUri?.toString(), options.jpeg.format.name)
        dao.enqueue(row, photos.map { ExportItemRow(id, it.id.value, it.name, it.frameNumber, PhotoRecipeCodec.write(it.recipe)) })
        requireNotNull(snapshot(id))
    }

    suspend fun snapshot(id: String): ExportRun? = withContext(Dispatchers.Default) { dao.snapshot(id)?.let(::run) }
    suspend fun stopIdle(id: String, cancel: Boolean = false) = dao.stopIdle(id, cancel)
    internal suspend fun begin(id: String, retryFailed: Boolean, onClaim: () -> Unit): ExportRun = withContext(Dispatchers.Default) {
        initialize()
        val group = dao.begin(id, retryFailed)
        onClaim() // The runner must release the claim even if decoding its recipe then fails.
        run(group)
    }
    internal suspend fun checkpoint(id: String, photoId: PhotoId, stage: PhotoProcessingStage) {
        check(dao.checkpoint(id, photoId.value, stage.name) == 1) { "导出阶段未能保存" }
    }
    internal suspend fun targetCreated(id: String, photoId: PhotoId, uri: Uri) {
        check(dao.targetCreated(id, photoId.value, uri.toString()) == 1) { "导出目标未能登记" }
    }
    internal suspend fun saved(id: String, photoId: PhotoId, uri: Uri) {
        check(dao.saved(id, photoId.value, uri.toString()) == 1) { "照片已保存，但完成记录未能登记，请核对目标" }
    }
    internal suspend fun failed(id: String, photoId: PhotoId, error: String) { dao.failed(id, photoId.value, error) }
    internal suspend fun finish(id: String, stopped: Boolean? = null) = dao.finish(id,
        stopped?.let { if (it) "CANCELLED" else "PAUSED" },
        if (stopped == true) "已取消后续导出，已保存照片保留" else "导出已暂停，可继续未完成的项目")

    /** Call only after the user has inspected the target. Null explicitly permits a retry; it deletes nothing. */
    suspend fun confirmReview(id: String, photoId: PhotoId, savedUri: Uri?) {
        if (savedUri != null) require(savedUri.scheme == "content") { "请使用核对过的照片 URI" }
        dao.confirmReview(id, photoId.value, savedUri?.toString())
    }

    private fun run(group: ExportRunWithItems): ExportRun = with(group.run) {
        val exportFormat = runCatching { ExportFormat.valueOf(format) }.getOrDefault(ExportFormat.JPEG)
        ExportRun(id, batchId, createdAt, updatedAt, ExportRunState.valueOf(state),
            BatchExportOptions(JpegOutput(dimensionPercent, compressionQuality, exportFormat),
                FrameOptionsCodec.read(frameJson)),
            folderUri?.let { ExportDestination.Folder(Uri.parse(it)) } ?: ExportDestination.Gallery,
            group.items.sortedBy { it.frameNumber }.map { item -> ExportQueueItem(
                ExportRequestPhoto(PhotoId(item.photoId), item.name, item.frameNumber, PhotoRecipeCodec.read(item.recipeJson)),
                ExportItemState.valueOf(item.state), item.stage?.let { PhotoProcessingStage.valueOf(it) },
                item.candidateUri, item.savedUri, item.error) })
    }

    companion object {
        private val recoveryGate = Mutex()
        private var recovered = false
    }
}
