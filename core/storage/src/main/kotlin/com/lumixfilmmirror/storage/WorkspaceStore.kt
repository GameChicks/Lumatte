package com.lumixfilmmirror.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.lumixfilmmirror.camera.CameraDevice
import com.lumixfilmmirror.camera.CameraMedia
import com.lumixfilmmirror.camera.CameraResource
import com.lumixfilmmirror.camera.CameraIncomingDirectory
import com.lumixfilmmirror.media.*
import com.lumixfilmmirror.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.UUID

private val Context.workspacePreferences by preferencesDataStore("workspace-settings")

data class WorkspacePreferences(
    val output: JpegOutput = JpegOutput(),
    val folderUri: String? = null,
    val selectedId: String? = null,
    val processingBatchId: String? = null,
)

class WorkspaceStore(context: Context) {
    private val context = context.applicationContext
    private val dao = WorkspaceDatabase.get(this.context).workspace()
    private val preferences = this.context.workspacePreferences
    val photos = dao.photos().map { rows -> rows.map(::source) }
    val processingBatches = dao.processingBatches().map { groups -> groups.map { group ->
        ProcessingBatch(group.batch.id, group.batch.title, group.batch.createdAt,
            group.entries.filterNot { it.entry.removed }.sortedWith(compareBy({ it.entry.ordinal }, { it.entry.photoId }))
                .mapNotNull { it.photos.singleOrNull()?.let(::source) },
            group.lutRecipes.singleOrNull()?.let { LutRecipe(LutSlot(it.firstId, it.firstStrength),
                LutSlot(it.secondId, it.secondStrength), it.grain, it.grainEnabled, it.ditheringEnabled) } ?: LutRecipe(),
            group.editRecipes.singleOrNull()?.let { PhotoTreatment.valueOf(it.treatment) } ?: PhotoTreatment.LUT,
            group.editRecipes.singleOrNull()?.filmJson?.let(FilmBatchCodec::read),
            group.frameRecipes.singleOrNull()?.frameJson?.let(FrameOptionsCodec::read) ?: FrameOptions())
    } }
    val transfers = dao.transfers()
    val incomingTransfers = dao.incomingTransfers()

    suspend fun initialize(importer: ImageImporter, recoverTransfers: Boolean = true, recoverPhotos: Boolean = true): List<String> {
        if (recoverTransfers) { dao.recoverInterrupted(); dao.recoverReceivers() }
        if (!recoverPhotos) return emptyList()
        val recovery = importer.recoverImports()
        recovery.photos.forEach { dao.saveRecoveredPhoto(row(it)) }
        return recovery.problems
    }

    suspend fun source(id: String): SourceImage? = dao.photo(id)?.let(::source)
    suspend fun save(photo: SourceImage) = dao.saveRecoveredPhoto(row(photo))
    suspend fun createProcessingBatch(): ProcessingBatch {
        val id = UUID.randomUUID().toString()
        val created = System.currentTimeMillis()
        val title = "照片批次 · " + java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        dao.insertProcessingBatch(ProcessingBatchRow(id, title, created))
        return ProcessingBatch(id, title, created)
    }
    suspend fun reserveLocalPhoto(batchId: String, id: PhotoId) = dao.reserveProcessingPhoto(batchId, id.value)
    /** Hide only this processing entry; originals, recovered copies and export history are retained. */
    suspend fun removeProcessingPhotos(batchId: String, ids: Set<PhotoId>, treatment: PhotoTreatment, film: FilmBatch?) =
        dao.removeProcessingPhotos(batchId, ids.map { it.value },
            BatchEditRecipeRow(batchId, treatment.name, film?.let(FilmBatchCodec::write)))
    suspend fun saveLutRecipe(batchId: String, recipe: LutRecipe) = dao.saveLutRecipe(BatchLutRecipeRow(batchId,
        recipe.first.id, recipe.first.strength, recipe.second.id, recipe.second.strength, recipe.grain,
        recipe.grainEnabled, recipe.ditheringEnabled))
    suspend fun saveEditRecipe(batchId: String, treatment: PhotoTreatment, film: FilmBatch?) =
        dao.saveEditRecipe(BatchEditRecipeRow(batchId, treatment.name, film?.let(FilmBatchCodec::write)))
    suspend fun saveFrameRecipe(batchId: String, frame: FrameOptions) =
        dao.saveFrameRecipe(BatchFrameRecipeRow(batchId, FrameOptionsCodec.write(frame)))
    suspend fun finish(task: TransferTask, photo: SourceImage, bytes: Long) = dao.finish(task.id, row(photo), bytes)
    suspend fun reserveVideo(task: TransferTask, uri: String, bytes: Long) = dao.reserveVideo(task.id, uri, bytes)
    suspend fun finishVideo(task: TransferTask, uri: String, bytes: Long) = dao.finishVideo(task.id, uri, bytes, System.currentTimeMillis())
    suspend fun batch(id: String): List<TransferTask> = dao.batch(id)
    suspend fun transferSnapshot(): List<TransferTask> = dao.transferSnapshot()
    suspend fun task(id: String): TransferTask? = dao.task(id)
    suspend fun incoming(id: String): IncomingTransfer? = dao.incoming(id)
    suspend fun saveIncoming(run: IncomingTransfer) = dao.saveIncomingState(run)
    suspend fun incomingSavedTasks(run: IncomingTransfer): List<TransferTask> =
        dao.savedIncomingTasks(run.cameraKey, run.directoryId, run.mode)
    suspend fun startIncoming(device: CameraDevice, directory: CameraIncomingDirectory): IncomingTransfer =
        dao.getOrCreateIncoming(IncomingTransfer(UUID.randomUUID().toString(), device.incomingKey(),
            device.model, directory.mode.name, directory.objectId))
    suspend fun appendIncoming(run: IncomingTransfer, device: CameraDevice, media: CameraMedia): TransferTask {
        require(run.belongsTo(device))
        val resource = requireNotNull(media.transferResource) { "待发送文件没有支持的原始资源" }
        val task = TransferTask(UUID.randomUUID().toString(), run.id, 0, device.udn, device.model,
            device.descriptionUri.toString(), media.id, media.parentId, media.title, resource.mimeType,
            resource.profile, resource.originalName, resource.bytes, resource.uri.toString())
        return dao.appendIncomingTask(run.id, task)
    }
    suspend fun checkpoint(task: TransferTask, state: TransferState, bytes: Long, error: String? = null) =
        dao.checkpoint(task.id, state.name, bytes, error)
    suspend fun stopBatch(id: String, cancel: Boolean, reason: String) =
        dao.stopTransfer(id, if (cancel) "CANCELLED" else "PAUSED", reason)

    suspend fun enqueue(device: CameraDevice, photos: List<CameraMedia>): String {
        require(photos.isNotEmpty())
        val batchId = UUID.randomUUID().toString()
        val created = System.currentTimeMillis()
        val tasks = photos.mapIndexed { index, photo ->
            val resource = requireNotNull(photo.transferResource)
            TransferTask(UUID.randomUUID().toString(), batchId, index, device.udn, device.model,
                device.descriptionUri.toString(), photo.id, photo.parentId, photo.title,
                resource.mimeType, resource.profile, resource.originalName, resource.bytes,
                resource.uri.toString(), createdAt = created)
        }
        dao.enqueueProcessingBatch(ProcessingBatchRow(batchId, "相机传入 · ${device.model}", created), tasks)
        return batchId
    }

    suspend fun readPreferences(): WorkspacePreferences {
        val values = preferences.data.first()
        return WorkspacePreferences(JpegOutput((values[percent] ?: 100).coerceIn(1, 100),
            (values[quality] ?: 95).coerceIn(1, 100),
            values[format]?.let { runCatching { ExportFormat.valueOf(it) }.getOrNull() } ?: ExportFormat.JPEG),
            values[folder], values[selected], values[processingBatch])
    }
    suspend fun savePreferences(value: WorkspacePreferences) {
        preferences.edit { values ->
            values[percent] = value.output.dimensionPercent
            values[quality] = value.output.compressionQuality
            values[format] = value.output.format.name
            if (value.folderUri == null) values.remove(folder) else values[folder] = value.folderUri
            if (value.selectedId == null) values.remove(selected) else values[selected] = value.selectedId
            if (value.processingBatchId == null) values.remove(processingBatch) else values[processingBatch] = value.processingBatchId
        }
    }

    fun taskDirectory(task: TransferTask): File {
        require(UUID.fromString(task.id).toString() == task.id)
        return File(File(context.filesDir.canonicalFile, "camera-transfers"), task.id)
    }

    /** Runs file existence checks off the UI thread; retains database records for missing photos. */
    suspend fun available(photos: List<SourceImage>): Pair<List<SourceImage>, Int> = withContext(Dispatchers.IO) {
        val available = photos.filter { it.jpeg.isFile }
        available to photos.size - available.size
    }

    private fun row(photo: SourceImage) = with(photo) {
        PhotoRow(id.value, name, size.width, size.height, orientation, origin is InputOrigin.RawEmbeddedJpeg,
            metadata.camera, metadata.lens, metadata.capturedAt, metadata.iso, metadata.aperture,
            metadata.shutter, metadata.focalLength)
    }
    private fun source(row: PhotoRow): SourceImage {
        require(UUID.fromString(row.id).toString() == row.id)
        val size = PixelSize(row.width, row.height)
        return SourceImage(PhotoId(row.id), row.name, File(context.filesDir, "source-jpegs/${row.id}/source.jpg"),
            size, row.orientation, if (row.rawPreview) InputOrigin.RawEmbeddedJpeg(size) else InputOrigin.Jpeg,
            PhotoMetadata(row.camera, row.lens, row.capturedAt, row.iso, row.aperture, row.shutter, row.focalLength))
    }

    companion object {
        private val percent = intPreferencesKey("jpeg-dimension-percent")
        private val quality = intPreferencesKey("jpeg-compression-quality")
        private val format = stringPreferencesKey("export-format")
        private val folder = stringPreferencesKey("export-folder-uri")
        private val selected = stringPreferencesKey("selected-photo-id")
        private val processingBatch = stringPreferencesKey("processing-batch-id")
    }
}

fun TransferTask.expectedMedia(): CameraMedia = CameraMedia(itemId, parentId, title, false,
    listOf(CameraResource(URI(resourceUri), mimeType, profile, originalName, expectedBytes)))

fun TransferTask.belongsTo(device: CameraDevice): Boolean =
    if (cameraUdn.isNotBlank()) cameraUdn == device.udn
    else cameraModel == device.model && descriptionUri == device.descriptionUri.toString()

val TransferTask.completed: Boolean get() = state in setOf("IMPORTED", "SAVED")
val TransferTask.terminal: Boolean get() = completed || state == "CANCELLED"
