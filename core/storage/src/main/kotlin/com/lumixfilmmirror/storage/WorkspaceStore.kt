package com.lumixfilmmirror.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.lumixfilmmirror.camera.CameraDevice
import com.lumixfilmmirror.camera.CameraMedia
import com.lumixfilmmirror.camera.CameraResource
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
)

class WorkspaceStore(context: Context) {
    private val context = context.applicationContext
    private val dao = WorkspaceDatabase.get(this.context).workspace()
    private val preferences = this.context.workspacePreferences
    val photos = dao.photos().map { rows -> rows.map(::source) }
    val transfers = dao.transfers()

    suspend fun initialize(importer: ImageImporter, recoverTransfers: Boolean = true): List<String> {
        if (recoverTransfers) dao.recoverInterrupted()
        val recovery = importer.recoverImports()
        recovery.photos.forEach { dao.savePhoto(row(it)) }
        return recovery.problems
    }

    suspend fun source(id: String): SourceImage? = dao.photo(id)?.let(::source)
    suspend fun save(photo: SourceImage) = dao.savePhoto(row(photo))
    suspend fun finish(task: TransferTask, photo: SourceImage, bytes: Long) = dao.finish(task.id, row(photo), bytes)
    suspend fun batch(id: String): List<TransferTask> = dao.batch(id)
    suspend fun checkpoint(task: TransferTask, state: TransferState, bytes: Long, error: String? = null) =
        dao.checkpoint(task.id, state.name, bytes, error)
    suspend fun stopBatch(id: String, cancel: Boolean, reason: String) =
        dao.stopBatch(id, if (cancel) "CANCELLED" else "PAUSED", reason)

    suspend fun enqueue(device: CameraDevice, photos: List<CameraMedia>): String {
        require(photos.isNotEmpty())
        val batchId = UUID.randomUUID().toString()
        val created = System.currentTimeMillis()
        val tasks = photos.mapIndexed { index, photo ->
            val resource = requireNotNull(photo.editableResource)
            TransferTask(UUID.randomUUID().toString(), batchId, index, device.udn, device.model,
                device.descriptionUri.toString(), photo.id, photo.parentId, photo.title,
                resource.mimeType, resource.profile, resource.originalName, resource.bytes,
                resource.uri.toString(), createdAt = created)
        }
        dao.enqueue(tasks)
        return batchId
    }

    suspend fun readPreferences(): WorkspacePreferences {
        val values = preferences.data.first()
        return WorkspacePreferences(JpegOutput((values[percent] ?: 100).coerceIn(1, 100),
            (values[quality] ?: 95).coerceIn(1, 100)), values[folder], values[selected])
    }
    suspend fun savePreferences(value: WorkspacePreferences) {
        preferences.edit { values ->
            values[percent] = value.output.dimensionPercent
            values[quality] = value.output.compressionQuality
            if (value.folderUri == null) values.remove(folder) else values[folder] = value.folderUri
            if (value.selectedId == null) values.remove(selected) else values[selected] = value.selectedId
        }
    }

    fun taskDirectory(task: TransferTask): File {
        require(UUID.fromString(task.id).toString() == task.id)
        return File(File(context.filesDir, "camera-transfers"), task.id)
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
        private val folder = stringPreferencesKey("export-folder-uri")
        private val selected = stringPreferencesKey("selected-photo-id")
    }
}

fun TransferTask.expectedMedia(): CameraMedia = CameraMedia(itemId, parentId, title, false,
    listOf(CameraResource(URI(resourceUri), mimeType, profile, originalName, expectedBytes)))

fun TransferTask.belongsTo(device: CameraDevice): Boolean =
    if (cameraUdn.isNotBlank()) cameraUdn == device.udn
    else cameraModel == device.model && descriptionUri == device.descriptionUri.toString()
