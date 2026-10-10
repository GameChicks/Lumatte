package com.lumixfilmmirror.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumixfilmmirror.media.ExportDestination
import com.lumixfilmmirror.media.ImageImporter
import com.lumixfilmmirror.media.JpegOutputWriter
import com.lumixfilmmirror.media.SourceImage
import com.lumixfilmmirror.model.JpegOutput
import com.lumixfilmmirror.model.PhotoId
import com.lumixfilmmirror.camera.CameraMedia
import com.lumixfilmmirror.storage.WorkspaceStore
import com.lumixfilmmirror.storage.WorkspacePreferences
import com.lumixfilmmirror.storage.TransferTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
    val recovering: Boolean = true,
    val storageFailure: String? = null,
    val activeTransferBatch: String? = null,
    val completed: Int = 0,
    val total: Int = 0,
    val exported: List<ExportedPhoto> = emptyList(),
    val failures: List<FailedPhoto> = emptyList(),
    val notice: String? = null,
) {
    val current: SourceImage? get() = photos.firstOrNull { it.id == selected }
    val busy: Boolean get() = importing || exporting || cameraBusy || recovering || storageFailure != null
}

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    val writer = JpegOutputWriter(application)
    private val importer = ImageImporter(application)
    private val store = WorkspaceStore(application)
    private val camera = (application as FilmMirrorApplication).camera
    private val mutable = MutableStateFlow(WorkspaceState())
    val state = mutable.asStateFlow()
    private val tasksMutable = MutableStateFlow<List<TransferTask>>(emptyList())
    val transferTasks = tasksMutable.asStateFlow()
    private var restoreJob: Job? = null
    private var observingStorage = false
    private val storageObservers = mutableListOf<Job>()
    private var exportJob: Job? = null
    val cameraState = camera.cameraState

    init {
        viewModelScope.launch {
            cameraState.collect { value -> mutable.update { it.copy(cameraBusy = value.busy,
                activeTransferBatch = value.activeTransferBatch) } }
        }
        restoreWorkspace()
    }

    fun restoreWorkspace() {
        if (observingStorage || restoreJob?.isActive == true) return
        mutable.update { it.copy(recovering = true, storageFailure = null) }
        restoreJob = viewModelScope.launch {
            try {
                val problems = store.initialize(importer, recoverTransfers = !camera.isTransferring)
                val settings = store.readPreferences()
                mutable.update { it.copy(output = settings.output, selected = settings.selectedId?.let(::PhotoId),
                    destination = settings.folderUri?.let { uri -> ExportDestination.Folder(Uri.parse(uri)) } ?: ExportDestination.Gallery,
                    notice = problems.takeIf { it.isNotEmpty() }?.joinToString("\n")) }
                observingStorage = true
                observeStorage()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutable.update { it.copy(storageFailure = error.message ?: "工作台无法恢复，请重试") }
            } finally { mutable.update { it.copy(recovering = false) } }
        }
    }

    private fun observeStorage() {
        storageObservers.forEach { it.cancel() }
        storageObservers.clear()
        storageObservers += viewModelScope.launch {
            try {
                store.photos.collect { photos ->
                    val (available, missing) = store.available(photos)
                    mutable.update { previous -> previous.copy(photos = available,
                        selected = previous.selected?.takeIf { id -> available.any { it.id == id } } ?: available.firstOrNull()?.id,
                        notice = if (missing > 0) "$missing 张导入副本缺失，原有记录保留" else previous.notice) }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { storageFailed(error.message ?: "无法读取照片记录") }
        }
        storageObservers += viewModelScope.launch {
            try {
                store.transfers.collect { tasks ->
                    tasksMutable.value = tasks
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { storageFailed(error.message ?: "无法读取传输记录") }
        }
    }

    private fun storageFailed(message: String) {
        observingStorage = false
        mutable.update { it.copy(storageFailure = message) }
        storageObservers.forEach { it.cancel() }
        camera.stopTransfer(false, "记录存储中断，传输已暂停")
    }

    private fun persistPreferences() {
        val snapshot = mutable.value
        viewModelScope.launch {
            try {
                store.savePreferences(WorkspacePreferences(snapshot.output,
                    (snapshot.destination as? ExportDestination.Folder)?.treeUri?.toString(), snapshot.selected?.value))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(notice = error.message ?: "设置未能保存") } }
        }
    }

    fun notice(message: String) { mutable.update { it.copy(notice = message) } }

    private fun withCamera(action: () -> Unit) { if (!mutable.value.busy) action() }
    fun connectCamera() = withCamera(camera::connectCamera)
    fun connectCameraWifi(ssid: String, password: String) = withCamera { camera.connectCameraWifi(ssid, password) }
    fun scanBluetoothCameras() = withCamera(camera::scanBluetoothCameras)
    fun readBluetoothCamera(address: String) = withCamera { camera.readBluetoothCamera(address) }
    fun connectBluetoothWifi(startOnCamera: Boolean = false) = withCamera { camera.connectBluetoothWifi(startOnCamera) }
    fun bluetoothPermissionDenied() = camera.bluetoothPermissionDenied()
    fun chooseCamera(key: String) = withCamera { camera.chooseCamera(key) }
    fun reconnectCamera() = withCamera(camera::reconnectCamera)
    fun cameraPermissionDenied() = camera.cameraPermissionDenied()
    fun openCameraFolder(entry: CameraMedia) = withCamera { camera.openCameraFolder(entry) }
    fun cameraFolderBack() = withCamera(camera::cameraFolderBack)
    fun loadMoreCameraPhotos() = withCamera(camera::loadMoreCameraPhotos)
    fun refreshCameraPhotos() = withCamera(camera::refreshCameraPhotos)
    fun selectCameraPhoto(id: String) = withCamera { camera.selectCameraPhoto(id) }
    fun importCameraPhotos() = withCamera(camera::importCameraPhotos)
    fun resumeTransferBatch(id: String) = withCamera { camera.resumeTransferBatch(id) }
    fun cancelTransferBatch(id: String) = withCamera { camera.cancelTransferBatch(id) }
    fun pauseTransfer() = camera.stopTransfer(false, "传输已暂停，可继续未完成的任务")
    fun cancelCameraOperation() = camera.cancelCameraOperation()
    fun disconnectCamera() = withCamera(camera::disconnectCamera)
    fun inspectTransferCache() = withCamera(camera::inspectTransferCache)
    fun clearTransferCache() = withCamera(camera::clearTransferCache)

    fun importImages(uris: List<Uri>) {
        if (mutable.value.busy || camera.cameraState.value.busy || uris.isEmpty()) return
        mutable.update { it.copy(importing = true, failures = emptyList(), notice = null) }
        viewModelScope.launch {
            try {
                for (uri in uris) {
                    coroutineContext.ensureActive()
                    try {
                        val photo = importer.importImage(uri)
                        withContext(NonCancellable) { store.save(photo) }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        mutable.update { it.copy(failures = it.failures + FailedPhoto(null, uri.lastPathSegment.orEmpty(),
                            error.message ?: "导入失败")) }
                    }
                }
            } finally { mutable.update { it.copy(importing = false) } }
        }
    }

    fun select(id: PhotoId) {
        if (mutable.value.recovering) return
        mutable.update { it.copy(selected = id) }
        persistPreferences()
    }
    fun output(settings: JpegOutput) {
        if (!mutable.value.busy) { mutable.update { it.copy(output = settings) }; persistPreferences() }
    }
    fun destination(destination: ExportDestination) {
        if (!mutable.value.busy) { mutable.update { it.copy(destination = destination) }; persistPreferences() }
    }

    fun export(retryOnly: Boolean = false) {
        val snapshot = mutable.value
        if (snapshot.busy || camera.cameraState.value.busy || snapshot.photos.isEmpty()) return
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
