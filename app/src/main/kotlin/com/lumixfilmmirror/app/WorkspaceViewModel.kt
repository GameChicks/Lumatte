package com.lumixfilmmirror.app

import android.app.Application
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumixfilmmirror.media.ExportDestination
import com.lumixfilmmirror.media.ImageImporter
import com.lumixfilmmirror.media.JpegOutputWriter
import com.lumixfilmmirror.media.SourceImage
import com.lumixfilmmirror.model.JpegOutput
import com.lumixfilmmirror.model.PhotoId
import com.lumixfilmmirror.camera.CameraResourceKind
import com.lumixfilmmirror.camera.CameraMedia
import com.lumixfilmmirror.camera.ConnectionStep
import com.lumixfilmmirror.camera.LumixCameraSession
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

data class ExportedPhoto(val id: PhotoId, val name: String, val uri: Uri)
data class FailedPhoto(val id: PhotoId?, val name: String, val reason: String)

data class WorkspaceState(
    val photos: List<SourceImage> = emptyList(),
    val selected: PhotoId? = null,
    val output: JpegOutput = JpegOutput(),
    val destination: ExportDestination = ExportDestination.Gallery,
    val importing: Boolean = false,
    val exporting: Boolean = false,
    val cameraBusy: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val exported: List<ExportedPhoto> = emptyList(),
    val failures: List<FailedPhoto> = emptyList(),
    val notice: String? = null,
) {
    val current: SourceImage? get() = photos.firstOrNull { it.id == selected }
    val busy: Boolean get() = importing || exporting || cameraBusy
}

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    val writer = JpegOutputWriter(application)
    private val importer = ImageImporter(application)
    private val mutable = MutableStateFlow(WorkspaceState())
    val state = mutable.asStateFlow()
    private var exportJob: Job? = null
    private val cameraMutable = MutableStateFlow(CameraWorkspaceState())
    val cameraState = cameraMutable.asStateFlow()
    private var cameraSession: LumixCameraSession? = null
    private var cameraJob: Job? = null

    fun connectCamera() = cameraOperation {
        cameraSession?.close()
        cameraSession = null
        cameraMutable.value = CameraWorkspaceState(step = ConnectionStep.READING_DEVICE)
        val manager = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val wifi = manager.allNetworks.filter {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val network = wifi.firstOrNull {
            manager.getLinkProperties(it)?.linkAddresses?.any { link ->
                link.address.hostAddress?.startsWith("192.168.54.") == true
            } == true
        } ?: wifi.singleOrNull() ?: error("请先在系统 Wi-Fi 中连接相机，再回来连接")
        val preferences = getApplication<Application>().getSharedPreferences("camera-client", 0)
        val clientId = preferences.getString("client-id", null) ?: LumixCameraSession.newClientId().also {
            preferences.edit().putString("client-id", it).apply()
        }
        val session = LumixCameraSession.connect(network, "192.168.54.1", clientId) { step ->
            cameraMutable.update { it.copy(step = step) }
        }
        cameraSession = session
        cameraMutable.update { it.copy(device = session.device) }
        session.prepareBrowsing()
        loadCameraFolder()
    }

    fun openCameraFolder(entry: CameraMedia) {
        if (!entry.isFolder) return
        cameraOperation {
            loadCameraFolder(folders = cameraMutable.value.folders + CameraFolder(entry.id, entry.title))
        }
    }

    fun cameraFolderBack() = cameraOperation {
        if (cameraMutable.value.folders.size > 1) {
            loadCameraFolder(folders = cameraMutable.value.folders.dropLast(1))
        }
    }

    fun loadMoreCameraPhotos() = cameraOperation { loadCameraFolder(append = true) }
    fun refreshCameraPhotos() = cameraOperation { loadCameraFolder() }

    fun selectCameraPhoto(id: String) {
        if (mutable.value.busy) return
        cameraMutable.update { it.copy(selectedIds = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id) }
    }

    fun importCameraPhotos() = cameraOperation {
        val session = cameraSession ?: error("请先连接相机")
        val photos = cameraMutable.value.entries.filter { it.id in cameraMutable.value.selectedIds && it.editableResource != null }
        cameraMutable.update { it.copy(transferTotal = photos.size, transferred = 0, progress = null) }
        mutable.update { it.copy(failures = emptyList(), notice = null) }
        val directory = File(getApplication<Application>().filesDir, "camera-transfers")
        val failedIds = mutableSetOf<String>()
        for (entry in photos) {
            coroutineContext.ensureActive()
            val resource = requireNotNull(entry.editableResource)
            val name = resource.originalName ?: entry.title
            var downloaded: File? = null
            try {
                cameraMutable.update { it.copy(progress = null) }
                val completedFile = session.download(resource, directory) { progress ->
                    cameraMutable.update { it.copy(progress = progress) }
                }
                downloaded = completedFile
                val photo = importer.importImage(Uri.fromFile(completedFile), name,
                    rawEmbeddedPreview = resource.kind == CameraResourceKind.RAW_PREVIEW_JPEG)
                mutable.update { it.copy(photos = it.photos + photo, selected = it.selected ?: photo.id) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                failedIds += entry.id
                mutable.update { it.copy(failures = it.failures + FailedPhoto(null, name, error.message ?: "传输失败")) }
            } finally { downloaded?.delete() }
            cameraMutable.update { it.copy(transferred = it.transferred + 1,
                selectedIds = if (entry.id !in failedIds) it.selectedIds - entry.id else it.selectedIds) }
        }
        mutable.update { it.copy(notice = "已传入 ${photos.size - failedIds.size} 张照片") }
    }

    private suspend fun loadCameraFolder(append: Boolean = false,
        folders: List<CameraFolder> = cameraMutable.value.folders) {
        val session = cameraSession ?: error("请先连接相机")
        val snapshot = cameraMutable.value
        val offset = if (append) snapshot.nextOffset ?: return else 0
        val page = session.browse(folders.last().id, offset)
        if (append && snapshot.updateId != page.updateId) error("相机照片列表已更新，请刷新后重新选择")
        cameraMutable.update { it.copy(folders = folders,
            entries = (if (append) it.entries + page.entries else page.entries).distinctBy { entry -> entry.id },
            selectedIds = if (append) it.selectedIds else emptySet(), nextOffset = page.nextOffset,
            total = page.totalMatches, updateId = page.updateId) }
    }

    private fun cameraOperation(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(cameraBusy = true) }
        cameraMutable.update { it.copy(problem = null, transferTotal = 0, transferred = 0, progress = null) }
        cameraJob = viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) {
                cameraMutable.update { it.copy(problem = "操作已取消，已传入的照片保留") }
            } catch (error: Exception) {
                cameraMutable.update { it.copy(problem = error.message ?: "相机连接或传输失败") }
            } finally { mutable.update { it.copy(cameraBusy = false) } }
        }
    }

    fun cancelCameraOperation() { cameraJob?.cancel() }
    fun disconnectCamera() {
        if (mutable.value.cameraBusy) return
        cameraSession?.close()
        cameraSession = null
        cameraMutable.value = CameraWorkspaceState()
    }

    override fun onCleared() {
        cameraSession?.close()
        super.onCleared()
    }

    fun importImages(uris: List<Uri>) {
        if (mutable.value.busy || uris.isEmpty()) return
        mutable.update { it.copy(importing = true, failures = emptyList(), notice = null) }
        viewModelScope.launch {
            try {
                for (uri in uris) {
                    coroutineContext.ensureActive()
                    try {
                        val photo = importer.importImage(uri)
                        mutable.update { it.copy(photos = it.photos + photo, selected = it.selected ?: photo.id) }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        mutable.update { it.copy(failures = it.failures + FailedPhoto(null, uri.lastPathSegment.orEmpty(),
                            error.message ?: "导入失败")) }
                    }
                }
            } finally { mutable.update { it.copy(importing = false) } }
        }
    }

    fun select(id: PhotoId) { mutable.update { it.copy(selected = id) } }
    fun output(settings: JpegOutput) {
        if (!mutable.value.busy) mutable.update { it.copy(output = settings) }
    }
    fun destination(destination: ExportDestination) {
        if (!mutable.value.busy) mutable.update { it.copy(destination = destination) }
    }

    fun export(retryOnly: Boolean = false) {
        val snapshot = mutable.value
        if (snapshot.busy || snapshot.photos.isEmpty()) return
        val retryIds = snapshot.failures.mapNotNull { it.id }.toSet()
        val photos = if (retryOnly) snapshot.photos.filter { it.id in retryIds } else snapshot.photos
        if (photos.isEmpty()) return
        mutable.update { it.copy(exporting = true, completed = 0, total = photos.size,
            exported = if (retryOnly) it.exported else emptyList(), failures = emptyList(), notice = null) }
        exportJob = viewModelScope.launch {
            try {
                for (photo in photos) {
                    coroutineContext.ensureActive()
                    try {
                        val uri = writer.write(photo, snapshot.output, snapshot.destination)
                        mutable.update { it.copy(exported = it.exported + ExportedPhoto(photo.id, photo.name, uri)) }
                    } catch (error: CancellationException) { throw error }
                    catch (error: OutOfMemoryError) {
                        mutable.update { it.copy(failures = it.failures + FailedPhoto(photo.id, photo.name,
                            "当前尺寸所需内存不足，请降低 JPG 尺寸后重试")) }
                    } catch (error: Exception) {
                        mutable.update { it.copy(failures = it.failures + FailedPhoto(photo.id, photo.name, error.message ?: "导出失败")) }
                    }
                    mutable.update { it.copy(completed = it.completed + 1) }
                }
                mutable.update { it.copy(notice = "已保存 ${it.exported.size} 张照片") }
            } catch (error: CancellationException) {
                mutable.update { it.copy(notice = "已取消后续导出，已保存的照片会保留") }
            } finally { mutable.update { it.copy(exporting = false) } }
        }
    }

    fun cancelExport() { exportJob?.cancel() }
}
