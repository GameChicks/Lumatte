package com.lumixfilmmirror.app

import android.app.Application
import android.net.Uri
import android.net.Network
import android.location.LocationManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumixfilmmirror.media.ExportDestination
import com.lumixfilmmirror.media.ImageImporter
import com.lumixfilmmirror.media.JpegOutputWriter
import com.lumixfilmmirror.media.SourceImage
import com.lumixfilmmirror.model.JpegOutput
import com.lumixfilmmirror.model.PhotoId
import com.lumixfilmmirror.camera.CameraMedia
import com.lumixfilmmirror.camera.ConnectionStep
import com.lumixfilmmirror.camera.LumixCameraSession
import com.lumixfilmmirror.camera.CameraDiscovery
import com.lumixfilmmirror.camera.CameraWifiConnector
import com.lumixfilmmirror.camera.CameraWifiConnection
import com.lumixfilmmirror.camera.DiscoveredCamera
import com.lumixfilmmirror.storage.WorkspaceStore
import com.lumixfilmmirror.storage.WorkspacePreferences
import com.lumixfilmmirror.storage.TransferTask
import com.lumixfilmmirror.transfer.TransferRunner
import com.lumixfilmmirror.transfer.TransferStop
import java.io.Closeable
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
    private val transferRunner = TransferRunner(store, importer)
    private val mutable = MutableStateFlow(WorkspaceState())
    val state = mutable.asStateFlow()
    private val tasksMutable = MutableStateFlow<List<TransferTask>>(emptyList())
    val transferTasks = tasksMutable.asStateFlow()
    private var restoreJob: Job? = null
    private var observingStorage = false
    private val storageObservers = mutableListOf<Job>()
    private var exportJob: Job? = null
    private val cameraMutable = MutableStateFlow(CameraWorkspaceState())
    val cameraState = cameraMutable.asStateFlow()
    private var cameraSession: LumixCameraSession? = null
    private var cameraJob: Job? = null
    private val discovery = CameraDiscovery(application)
    private var wifiConnection: CameraWifiConnection? = null
    private var networkWatch: Closeable? = null
    private var watchedNetwork: Network? = null

    init { restoreWorkspace() }

    fun restoreWorkspace() {
        if (observingStorage || restoreJob?.isActive == true) return
        mutable.update { it.copy(recovering = true, storageFailure = null) }
        restoreJob = viewModelScope.launch {
            try {
                val problems = store.initialize(importer)
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
                    mutable.value.activeTransferBatch?.let { id ->
                        val batch = tasks.filter { it.batchId == id }
                        cameraMutable.update { it.copy(transferTotal = batch.size, transferred = batch.count { task -> task.state == "IMPORTED" }) }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { storageFailed(error.message ?: "无法读取传输记录") }
        }
    }

    private fun storageFailed(message: String) {
        observingStorage = false
        mutable.update { it.copy(storageFailure = message) }
        storageObservers.forEach { it.cancel() }
        cameraJob?.cancel(TransferStop(false, "记录存储中断，传输已暂停"))
        cameraSession?.interrupt()
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

    fun connectCamera() = cameraOperation {
        clearCameraSession()
        cameraMutable.value = CameraWorkspaceState(step = ConnectionStep.DISCOVERING)
        findCamera()
    }

    fun connectCameraWifi(ssid: String, password: String) = cameraOperation {
        clearCameraSession()
        wifiConnection?.close()
        wifiConnection = null
        cameraMutable.value = CameraWorkspaceState(step = ConnectionStep.REQUESTING_WIFI)
        if (Build.VERSION.SDK_INT < 33 &&
            getApplication<Application>().getSystemService(LocationManager::class.java)?.isLocationEnabled != true) {
            error("当前 Android 版本需要开启系统定位后才能发起 Wi-Fi 连接；也可以先在系统设置中连接")
        }
        wifiConnection = CameraWifiConnector.connect(getApplication<Application>(), ssid, password, ::cameraNetworkLost)
        cameraMutable.update { it.copy(step = ConnectionStep.DISCOVERING) }
        findCamera()
    }

    fun chooseCamera(key: String) = cameraOperation {
        val candidate = cameraMutable.value.discovered.firstOrNull { it.key == key } ?: error("请重新查找相机")
        connectTarget(candidate)
    }

    fun reconnectCamera() = cameraOperation {
        val expected = cameraMutable.value.device ?: error("请先查找相机")
        clearCameraSession()
        cameraMutable.update { it.copy(step = ConnectionStep.DISCOVERING, disconnected = false) }
        val candidates = discovery.discover(wifiConnection?.let { listOf(it.network) } ?: discovery.wifiNetworks())
        cameraMutable.update { it.copy(discovered = candidates) }
        val candidate = candidates.firstOrNull { target ->
            if (expected.udn.isNotBlank()) target.device.udn == expected.udn
            else target.device.descriptionUri == expected.descriptionUri && target.device.model == expected.model
        } ?: error("未找到原相机，请确认 Wi-Fi 连接，或使用连接入口重新连接")
        connectTarget(candidate, restoreSelection = true)
    }

    fun cameraPermissionDenied() { cameraMutable.update { it.copy(problem = "未获得 Wi-Fi 连接权限，可在系统设置连接后查找相机") } }

    private suspend fun findCamera() {
        val candidates = discovery.discover(wifiConnection?.let { listOf(it.network) } ?: discovery.wifiNetworks())
        cameraMutable.update { it.copy(discovered = candidates) }
        if (candidates.isEmpty()) error("未发现相机，请开启相机的 Wi-Fi 传输功能并确认连接")
        if (candidates.size == 1) connectTarget(candidates.single())
    }

    private suspend fun connectTarget(candidate: DiscoveredCamera, restoreSelection: Boolean = false) {
        val previous = cameraMutable.value
        var connected = false
        try {
            clearCameraSession()
            cameraMutable.update { it.copy(disconnected = false, step = ConnectionStep.READING_DEVICE) }
            val preferences = getApplication<Application>().getSharedPreferences("camera-client", 0)
            val clientId = preferences.getString("client-id", null) ?: LumixCameraSession.newClientId().also {
                preferences.edit().putString("client-id", it).apply()
            }
            watchedNetwork = candidate.network
            networkWatch = CameraWifiConnector.watch(getApplication<Application>(), candidate.network) { cameraNetworkLost(candidate.network) }
            val session = LumixCameraSession.connect(candidate.network, candidate.device.descriptionUri.host, clientId,
                descriptionUri = candidate.device.descriptionUri) { step ->
                cameraMutable.update { it.copy(step = step) }
            }
            cameraSession = session
            session.prepareBrowsing()
            cameraMutable.update { it.copy(device = session.device, entries = emptyList(), selectedIds = emptySet(),
                folders = if (restoreSelection) previous.folders else listOf(CameraFolder("0", "相机照片"))) }
            loadCameraFolder()
            if (restoreSelection && previous.selectedIds.isNotEmpty()) {
                // Reload the previously viewed pages before restoring IDs; never reuse stale resource URLs.
                while (cameraMutable.value.nextOffset != null &&
                    !cameraMutable.value.entries.map { it.id }.toSet().containsAll(previous.selectedIds)) {
                    loadCameraFolder(append = true)
                }
                val previousPhotos = previous.entries.filter { it.id in previous.selectedIds }.associateBy { it.id }
                val available = cameraMutable.value.entries.filter { entry ->
                    previousPhotos[entry.id]?.let { entry.matchesPreviousPhoto(it) } == true
                }.map { it.id }.toSet()
                cameraMutable.update { it.copy(selectedIds = previous.selectedIds.intersect(available),
                    problem = if (!available.containsAll(previous.selectedIds)) "部分待传照片缺失或信息已变化，请重新选择" else null) }
            }
            cameraMutable.update { it.copy(connected = true) }
            connected = true
        } finally {
            if (restoreSelection && !connected) cameraMutable.update { it.copy(device = previous.device,
                entries = previous.entries, selectedIds = previous.selectedIds, folders = previous.folders) }
        }
    }

    private fun cameraNetworkLost(network: Network) {
        if (network != watchedNetwork && network != wifiConnection?.network) return
        cameraMutable.update { it.copy(connected = false, disconnected = true,
            problem = "相机连接已断开，已传入的照片保留。重新连接后可继续传输。") }
        cameraJob?.cancel(TransferStop(false, "相机断开，传输已暂停"))
        clearCameraSession()
        wifiConnection?.close()
        wifiConnection = null
    }

    private fun clearCameraSession() {
        watchedNetwork = null
        networkWatch?.close()
        networkWatch = null
        cameraSession?.close()
        cameraSession = null
        cameraMutable.update { it.copy(connected = false) }
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
        val batchId = store.enqueue(session.device, photos)
        cameraMutable.update { it.copy(selectedIds = it.selectedIds - photos.map { photo -> photo.id }.toSet()) }
        runTransferBatch(batchId)
    }

    fun resumeTransferBatch(batchId: String) = cameraOperation { runTransferBatch(batchId) }
    fun cancelTransferBatch(batchId: String) = cameraOperation {
        store.stopBatch(batchId, cancel = true, reason = "已取消后续传输，已导入照片保留")
    }
    fun pauseTransfer() {
        cameraJob?.cancel(TransferStop(false, "传输已暂停，可继续未完成的任务"))
        cameraSession?.interrupt()
    }

    private suspend fun runTransferBatch(batchId: String) {
        val tasks = store.batch(batchId)
        cameraMutable.update { it.copy(transferTotal = tasks.size, transferred = tasks.count { task -> task.state == "IMPORTED" }) }
        mutable.update { it.copy(activeTransferBatch = batchId, notice = null) }
        try {
            var currentTask: String? = null
            val result = transferRunner.run(batchId, cameraSession) { task, progress ->
                if (currentTask != task.id) {
                    currentTask = task.id
                    cameraMutable.update { it.copy(progress = null) }
                }
                cameraMutable.update { it.copy(progress = progress) }
            }
            mutable.update { it.copy(notice = "本次传入 ${result.imported} 张，失败 ${result.failed} 张，待连接 ${result.waiting} 张") }
        } finally { mutable.update { it.copy(activeTransferBatch = null) } }
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
                cameraMutable.update { if (it.disconnected) it else it.copy(problem = error.message ?: "操作已取消，已传入的照片保留") }
                if (!cameraMutable.value.connected) { clearCameraSession(); wifiConnection?.close(); wifiConnection = null }
            } catch (error: Exception) {
                cameraMutable.update { it.copy(problem = error.message ?: "相机连接或传输失败") }
                if (!cameraMutable.value.connected) { clearCameraSession(); wifiConnection?.close(); wifiConnection = null }
            } finally { mutable.update { it.copy(cameraBusy = false) } }
        }
    }

    fun cancelCameraOperation() {
        cameraJob?.cancel(TransferStop(true, "已取消后续传输，已导入照片保留"))
        cameraSession?.interrupt()
    }
    fun disconnectCamera() {
        if (mutable.value.cameraBusy) return
        clearCameraSession()
        wifiConnection?.close()
        wifiConnection = null
        cameraMutable.value = CameraWorkspaceState()
    }

    override fun onCleared() {
        clearCameraSession()
        wifiConnection?.close()
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
