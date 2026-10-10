package com.lumixfilmmirror.app

import android.app.Application
import android.net.Network
import android.location.LocationManager
import android.os.Build
import com.lumixfilmmirror.camera.*
import com.lumixfilmmirror.media.ImageImporter
import com.lumixfilmmirror.storage.WorkspaceStore
import com.lumixfilmmirror.transfer.TransferRunner
import com.lumixfilmmirror.transfer.TransferStop
import java.io.Closeable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Process-owned camera connection; Activity/ViewModel recreation does not end a service transfer. */
class CameraController(private val application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = WorkspaceStore(application)
    private val transferRunner = TransferRunner(store, ImageImporter(application))
    private val cameraMutable = MutableStateFlow(CameraWorkspaceState())
    val cameraState = cameraMutable.asStateFlow()
    val isTransferring: Boolean get() = cameraMutable.value.activeTransferBatch != null
    private var cameraSession: LumixCameraSession? = null
    private var cameraJob: Job? = null
    private var transferJob: Job? = null
    private var pendingStop: TransferStop? = null
    private val discovery = CameraDiscovery(application)
    private var wifiConnection: CameraWifiConnection? = null
    private var networkWatch: Closeable? = null
    private var watchedNetwork: Network? = null
    private var visibleClients = 0
    private var idleRelease: Job? = null

    fun updateTransferCounts(batchId: String, total: Int, imported: Int) {
        cameraMutable.update { if (it.activeTransferBatch == batchId)
            it.copy(transferTotal = total, transferred = imported) else it }
    }

    fun attachUi() { idleRelease?.cancel(); visibleClients++ }
    fun detachUi() { visibleClients = (visibleClients - 1).coerceAtLeast(0); releaseIfIdle() }
    private fun releaseIfIdle() {
        if (visibleClients == 0 && !cameraMutable.value.busy) {
            idleRelease?.cancel()
            idleRelease = scope.launch {
                delay(500) // Allow an Activity recreation to attach before releasing an idle connection.
                if (visibleClients == 0 && !cameraMutable.value.busy) {
                    clearCameraSession()
                    wifiConnection?.close()
                    wifiConnection = null
                }
            }
        }
    }

    fun connectCamera() = cameraOperation {
        clearCameraSession()
        cameraMutable.value = CameraWorkspaceState(step = ConnectionStep.DISCOVERING, operationRunning = true)
        findCamera()
    }

    fun connectCameraWifi(ssid: String, password: String) = cameraOperation {
        clearCameraSession()
        wifiConnection?.close()
        wifiConnection = null
        cameraMutable.value = CameraWorkspaceState(step = ConnectionStep.REQUESTING_WIFI, operationRunning = true)
        if (Build.VERSION.SDK_INT < 33 &&
            application.getSystemService(LocationManager::class.java)?.isLocationEnabled != true) {
            error("当前 Android 版本需要开启系统定位后才能发起 Wi-Fi 连接；也可以先在系统设置中连接")
        }
        wifiConnection = CameraWifiConnector.connect(application, ssid, password, ::cameraNetworkLost)
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
            val preferences = application.getSharedPreferences("camera-client", 0)
            val clientId = preferences.getString("client-id", null) ?: LumixCameraSession.newClientId().also {
                preferences.edit().putString("client-id", it).apply()
            }
            watchedNetwork = candidate.network
            networkWatch = CameraWifiConnector.watch(application, candidate.network) { cameraNetworkLost(candidate.network) }
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
        cameraJob?.cancel(TransferStop(false, "相机断开，操作已暂停"))
        stopTransfer(false, "相机断开，传输已暂停")
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
        if (cameraMutable.value.busy) return
        cameraMutable.update { it.copy(selectedIds = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id) }
    }

    fun importCameraPhotos() = cameraOperation {
        val session = cameraSession ?: error("请先连接相机")
        val photos = cameraMutable.value.entries.filter { it.id in cameraMutable.value.selectedIds && it.editableResource != null }
        val batchId = store.enqueue(session.device, photos)
        cameraMutable.update { it.copy(selectedIds = it.selectedIds - photos.map { photo -> photo.id }.toSet()) }
        requestTransfer(batchId)
    }

    fun resumeTransferBatch(batchId: String) = cameraOperation { requestTransfer(batchId) }
    fun cancelTransferBatch(batchId: String) = cameraOperation {
        store.stopBatch(batchId, cancel = true, reason = "已取消后续传输，已导入照片保留")
    }
    fun stopTransfer(cancel: Boolean, message: String) {
        val stop = TransferStop(cancel, message)
        if (transferJob != null) transferJob?.cancel(stop)
        else if (cameraMutable.value.activeTransferBatch != null) pendingStop = stop
        cameraSession?.interrupt()
    }

    private suspend fun requestTransfer(batchId: String) {
        check(cameraMutable.value.activeTransferBatch == null) { "已有正在执行的传输" }
        val tasks = store.batch(batchId)
        if (tasks.none { it.state !in setOf("IMPORTED", "CANCELLED") }) {
            cameraMutable.update { it.copy(notice = "此批没有未完成的任务") }
            return
        }
        pendingStop = null
        cameraMutable.update { it.copy(activeTransferBatch = batchId, notice = null,
            transferTotal = tasks.size, transferred = tasks.count { task -> task.state == "IMPORTED" }) }
        try {
            application.startForegroundService(CameraTransferService.startIntent(application, batchId))
        } catch (error: Exception) {
            cameraMutable.update { it.copy(activeTransferBatch = null,
                problem = "无法启动后台传输：${error.message ?: "请在工作台重试"}") }
        }
    }

    /** Only the foreground service supplies the execution scope. Network objects stay in this process. */
    fun runInService(batchId: String, serviceScope: CoroutineScope): Job? {
        if (cameraMutable.value.activeTransferBatch != batchId || transferJob != null) return null
        val session = cameraSession
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                pendingStop?.let { throw it }
                var currentTask: String? = null
                var lastProgressAt = 0L
                val result = transferRunner.run(batchId, session) { task, progress ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (currentTask != task.id || now - lastProgressAt >= 100 || progress.bytes == progress.totalBytes) {
                        currentTask = task.id
                        lastProgressAt = now
                        cameraMutable.update { it.copy(progress = progress) }
                    }
                }
                cameraMutable.update { it.copy(notice = "本次传入 ${result.imported} 张，失败 ${result.failed} 张，待连接 ${result.waiting} 张") }
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    store.stopBatch(batchId, (error as? TransferStop)?.cancelRemaining == true,
                        error.message ?: "服务结束，传输已暂停")
                }
                cameraMutable.update { it.copy(notice = error.message ?: "传输已暂停") }
                throw error
            } catch (error: Exception) {
                cameraMutable.update { it.copy(problem = error.message ?: "传输失败，请重试队列") }
            } finally {
                transferJob = null
                pendingStop = null
                cameraMutable.update { it.copy(activeTransferBatch = null, progress = null) }
                releaseIfIdle()
            }
        }
        transferJob = job
        job.start()
        return job
    }

    fun serviceUnavailable(message: String) {
        cameraMutable.update { it.copy(problem = message) }
        if (transferJob != null) stopTransfer(false, message)
        else {
            cameraMutable.update { it.copy(activeTransferBatch = null, problem = message) }
            pendingStop = null
            releaseIfIdle()
        }
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
        if (cameraMutable.value.busy) return
        cameraMutable.update { it.copy(operationRunning = true) }
        cameraMutable.update { it.copy(problem = null, notice = null, transferTotal = 0, transferred = 0, progress = null) }
        cameraJob = scope.launch {
            try { block() }
            catch (error: CancellationException) {
                cameraMutable.update { if (it.disconnected) it else it.copy(problem = error.message ?: "操作已取消，已传入的照片保留") }
                if (!cameraMutable.value.connected) { clearCameraSession(); wifiConnection?.close(); wifiConnection = null }
            } catch (error: Exception) {
                cameraMutable.update { it.copy(problem = error.message ?: "相机连接或传输失败") }
                if (!cameraMutable.value.connected) { clearCameraSession(); wifiConnection?.close(); wifiConnection = null }
            } finally { cameraMutable.update { it.copy(operationRunning = false) }; releaseIfIdle() }
        }
    }

    fun cancelCameraOperation() {
        if (cameraMutable.value.activeTransferBatch != null)
            stopTransfer(true, "已取消后续传输，已导入照片保留")
        else { cameraJob?.cancel(); cameraSession?.interrupt() }
    }
    fun disconnectCamera() {
        if (cameraMutable.value.busy) return
        clearCameraSession()
        wifiConnection?.close()
        wifiConnection = null
        cameraMutable.value = CameraWorkspaceState()
    }

}
