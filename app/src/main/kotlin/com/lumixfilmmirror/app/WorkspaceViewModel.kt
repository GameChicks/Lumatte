package com.lumixfilmmirror.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumixfilmmirror.media.ExportDestination
import com.lumixfilmmirror.media.ImageImporter
import com.lumixfilmmirror.media.JpegOutputWriter
import com.lumixfilmmirror.media.JpegExportCacheSnapshot
import com.lumixfilmmirror.media.FolderExportSnapshot
import com.lumixfilmmirror.media.SourceImage
import com.lumixfilmmirror.model.JpegOutput
import com.lumixfilmmirror.model.PhotoId
import com.lumixfilmmirror.camera.CameraMedia
import com.lumixfilmmirror.storage.WorkspaceStore
import com.lumixfilmmirror.storage.WorkspacePreferences
import com.lumixfilmmirror.storage.TransferTask
import com.lumixfilmmirror.storage.IncomingTransfer
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
    val exportCacheBusy: Boolean = false,
    val exportCache: JpegExportCacheSnapshot? = null,
    val folderExports: FolderExportSnapshot? = null,
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
    val busy: Boolean get() = importing || exporting || exportCacheBusy || cameraBusy || recovering || storageFailure != null
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
    private val incomingMutable = MutableStateFlow<List<IncomingTransfer>>(emptyList())
    val incomingTransfers = incomingMutable.asStateFlow()
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
                val cleanup = writer.cleanCache()
                val folderExports = writer.inspectFolderExports()
                val cleanupProblems = (cleanup.problems + cleanup.remaining.problems).distinct()
                val cleanupNotice = if (cleanup.temporaryFilesRemoved + cleanup.pendingImagesRemoved > 0)
                    "已整理上次导出的 ${cleanup.temporaryFilesRemoved} 个中间文件、${cleanup.pendingImagesRemoved} 个未发布 JPG" else null
                mutable.update { it.copy(output = settings.output, selected = settings.selectedId?.let(::PhotoId),
                    destination = settings.folderUri?.let { uri -> ExportDestination.Folder(Uri.parse(uri)) } ?: ExportDestination.Gallery,
                    exportCache = cleanup.remaining.copy(problems = cleanupProblems),
                    folderExports = folderExports,
                    notice = (problems + listOfNotNull(cleanupNotice)).takeIf { it.isNotEmpty() }?.joinToString("\n")) }
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
        storageObservers += viewModelScope.launch {
            try { store.incomingTransfers.collect { incomingMutable.value = it } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { storageFailed(error.message ?: "无法读取接收确认记录") }
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
    fun connectBluetoothSendingWifi() = withCamera(camera::connectBluetoothSendingWifi)
    fun connectBluetoothRouterWifi(ssid: String) = withCamera { camera.connectBluetoothRouterWifi(ssid) }
    fun connectBluetoothRouterSendingWifi(ssid: String) = withCamera { camera.connectBluetoothRouterSendingWifi(ssid) }
    fun reconnectSendingCamera() = withCamera(camera::reconnectSendingCamera)
    // These actions answer the current operation; its busy flag must not block its own dialog.
    fun chooseWifiNetwork(requestId: String, key: Long) = camera.chooseWifiNetwork(requestId, key)
    fun refreshWifiSelection(requestId: String) = camera.refreshWifiSelection(requestId)
    fun cancelWifiSelection(requestId: String) = camera.cancelWifiSelection(requestId)
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
    fun receiveIncomingPhotos(follow: Boolean = false) = withCamera { camera.receiveIncomingPhotos(follow) }
    fun resumeTransferBatch(id: String) = withCamera { camera.resumeTransferBatch(id) }
    fun cancelTransferBatch(id: String) = withCamera { camera.cancelTransferBatch(id) }
    fun pauseTransfer() = camera.stopTransfer(false, "传输已暂停，可继续未完成的任务")
    fun cancelCameraOperation() = camera.cancelCameraOperation()
    fun disconnectCamera() = withCamera(camera::disconnectCamera)
    fun inspectTransferCache() = withCamera(camera::inspectTransferCache)
    fun clearTransferCache() = withCamera(camera::clearTransferCache)

    fun inspectExportCache() = exportCacheOperation(clean = false)
    fun clearExportCache() = exportCacheOperation(clean = true)
    fun inspectFolderExports() = folderExportOperation(null)
    fun forgetFolderExport(id: String) = folderExportOperation(id)
    private fun folderExportOperation(id: String?) {
        if (mutable.value.busy || camera.cameraState.value.busy) return
        mutable.update { it.copy(exportCacheBusy = true) }
        viewModelScope.launch {
            try {
                val result = if (id == null) writer.inspectFolderExports() else writer.forgetFolderExport(id)
                mutable.update { it.copy(folderExports = result,
                    notice = if (id == null) "已读取文件夹导出记录；文件需打开核对" else "已移除应用内记录，文件夹中的照片未删除") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(notice = error.message ?: "无法读取文件夹导出记录") } }
            finally { mutable.update { it.copy(exportCacheBusy = false) } }
        }
    }
    private fun exportCacheOperation(clean: Boolean) {
        if (mutable.value.busy || camera.cameraState.value.busy) return
        mutable.update { it.copy(exportCacheBusy = true) }
        viewModelScope.launch {
            try {
                if (clean) {
                    val result = writer.cleanCache()
                    mutable.update { it.copy(exportCache = result.remaining.copy(
                        problems = (result.problems + result.remaining.problems).distinct()),
                        notice = "已整理 ${result.temporaryFilesRemoved} 个导出中间文件、${result.pendingImagesRemoved} 个未发布 JPG") }
                } else {
                    val result = writer.inspectCache()
                    mutable.update { it.copy(exportCache = result) }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(notice = error.message ?: "无法整理导出中间文件") } }
            finally { mutable.update { it.copy(exportCacheBusy = false) } }
        }
    }

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
            exported = if (retryOnly) it.exported else emptyList(), failures = emptyList(), notice = null, exportCache = null) }
        exportJob = viewModelScope.launch {
            try {
                for (photo in photos) {
                    coroutineContext.ensureActive()
                    try {
                        writer.write(photo, snapshot.output, snapshot.destination) { uri ->
                            mutable.update { it.copy(exported = it.exported + ExportedPhoto(photo.id, photo.name, uri),
                                completed = it.completed + 1) }
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (error: OutOfMemoryError) {
                        mutable.update { it.copy(failures = it.failures + FailedPhoto(photo.id, photo.name,
                            "当前尺寸所需内存不足，请降低 JPG 尺寸后重试"), completed = it.completed + 1) }
                    } catch (error: Exception) {
                        mutable.update { it.copy(failures = it.failures + FailedPhoto(photo.id, photo.name, error.message ?: "导出失败"),
                            completed = it.completed + 1) }
                    }
                }
                mutable.update { it.copy(notice = "已保存 ${it.exported.size} 张照片") }
            } catch (error: CancellationException) {
                mutable.update { it.copy(notice = "已取消后续导出，已保存的照片会保留") }
            } finally {
                withContext(NonCancellable) {
                    try {
                        val records = writer.inspectFolderExports()
                        mutable.update { it.copy(folderExports = records) }
                    } catch (error: Exception) {
                        mutable.update { it.copy(notice = "导出已结束，但文件夹记录暂不可读：${error.message.orEmpty()}") }
                    }
                }
                mutable.update { it.copy(exporting = false) }
            }
        }
    }

    fun cancelExport() { exportJob?.cancel() }
}
