package com.lumixfilmmirror.app

import android.app.Application
import android.net.Network
import android.location.LocationManager
import android.os.Build
import com.lumixfilmmirror.camera.*
import com.lumixfilmmirror.media.ImageImporter
import com.lumixfilmmirror.media.VideoPublisher
import com.lumixfilmmirror.storage.WorkspaceStore
import com.lumixfilmmirror.storage.completed
import com.lumixfilmmirror.storage.terminal
import com.lumixfilmmirror.storage.accessMode
import com.lumixfilmmirror.storage.TransferTask
import com.lumixfilmmirror.transfer.TransferRunner
import com.lumixfilmmirror.transfer.IncomingTransferReceiver
import com.lumixfilmmirror.transfer.TransferPhase
import com.lumixfilmmirror.transfer.TransferStop
import com.lumixfilmmirror.transfer.TransferCacheManager
import java.io.Closeable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Process-owned camera connection; Activity/ViewModel recreation does not end a service transfer. */
class CameraController(private val application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = WorkspaceStore(application)
    private val videoPublisher = VideoPublisher(application)
    private val transferRunner = TransferRunner(store, ImageImporter(application), videoPublisher)
    private val incomingReceiver = IncomingTransferReceiver(store, transferRunner)
    private val transferCache = TransferCacheManager(store, videoPublisher)
    private val cameraMutable = MutableStateFlow(CameraWorkspaceState())
    val cameraState = cameraMutable.asStateFlow()
    val isTransferring: Boolean get() = cameraMutable.value.activeTransferBatch != null
    private var cameraSession: LumixCameraSession? = null
    private var cameraJob: Job? = null
    private var transferJob: Job? = null
    private var pendingStop: TransferStop? = null
    private val discovery = CameraDiscovery(application)
    private val bluetooth = CameraBluetooth(application)
    private var bluetoothCredentials: CameraWifiCredentials? = null
    private var bluetoothTarget: BluetoothCamera? = null
    private data class BluetoothWifiBinding(val network: Network, val camera: BluetoothCamera,
        val ssid: String, val canObserve: Boolean, val expectedMode: CameraAccessMode? = null,
        val expectedDevice: CameraDevice? = null)
    private data class PendingSendingReconnect(val camera: BluetoothCamera, val device: CameraDevice?,
        val request: CameraSendingReconnectRequest)
    private var pendingSendingReconnect: PendingSendingReconnect? = null
    private var bluetoothWifiBinding: BluetoothWifiBinding? = null
    private var followBinding: BluetoothWifiBinding? = null
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
        if (visibleClients == 0 && (!cameraMutable.value.busy ||
            (cameraMutable.value.bluetoothStep != null && cameraMutable.value.activeTransferBatch == null))) {
            idleRelease?.cancel()
            idleRelease = scope.launch {
                delay(500) // Allow an Activity recreation to attach before releasing an idle connection.
                if (visibleClients == 0) {
                    if (cameraMutable.value.bluetoothStep != null)
                        cameraJob?.cancel(CancellationException("已离开连接界面，蓝牙操作已取消"))
                    if (!cameraMutable.value.busy) {
                        bluetoothWifiBinding = null
                        clearBluetoothCredentials()
                        clearCameraSession()
                        wifiConnection?.close()
                        wifiConnection = null
                    }
                }
            }
        }
    }

    fun connectCamera() = cameraOperation {
        clearSendingReconnect()
        bluetoothWifiBinding = null
        clearBluetoothCredentials()
        clearCameraSession()
        cameraMutable.value = CameraWorkspaceState(step = ConnectionStep.DISCOVERING, operationRunning = true)
        findCamera()
    }

    fun connectCameraWifi(ssid: String, password: String) = cameraOperation {
        clearSendingReconnect()
        clearBluetoothCredentials()
        connectWifi(ssid, password)
    }

    fun scanBluetoothCameras() = cameraOperation {
        clearBluetoothCredentials()
        cameraMutable.update { it.copy(bluetoothCameras = emptyList(), bluetoothStep = BluetoothConnectionStep.SCANNING) }
        val cameras = bluetooth.scan { found -> cameraMutable.update { it.copy(bluetoothCameras = found) } }
        if (cameras.isEmpty()) error("未发现可连接的蓝牙相机，请在相机菜单开启蓝牙配对，或使用 Wi-Fi 入口")
    }

    fun readBluetoothCamera(address: String) = cameraOperation {
        val candidate = cameraMutable.value.bluetoothCameras.firstOrNull { it.address == address }
            ?: error("请重新查找蓝牙相机")
        clearSendingReconnect()
        clearBluetoothCredentials()
        val credentials = bluetooth.readWifi(candidate, ownClientId()) { step ->
            cameraMutable.update { it.copy(bluetoothStep = step) }
        }
        bluetoothCredentials = credentials
        bluetoothTarget = candidate
        cameraMutable.update { it.copy(bluetoothWifiSsid = credentials.ssid, bluetoothCanStartWifi = credentials.canStartTransferWifi,
            bluetoothCanStartSendingWifi = credentials.canStartTransferWifi && credentials.canObserveIncoming,
            notice = if (credentials.canStartTransferWifi) "已读取相机网络，可以启动传输 Wi-Fi 后连接。"
                else "已读取相机网络。请在相机上开启 Wi-Fi 传输，然后点击连接。") }
    }

    fun connectBluetoothWifi(startOnCamera: Boolean = false) = cameraOperation {
        connectBluetoothWifiInternal(startOnCamera)
    }

    fun connectBluetoothSendingWifi() = cameraOperation {
        connectBluetoothWifiInternal(startOnCamera = true, waitForSending = true)
    }

    fun reconnectSendingCamera() = cameraOperation {
        val pending = requireNotNull(pendingSendingReconnect) { "发送更新请求已释放，请重新连接相机" }
        try {
            bluetooth.withSendingReconnectWifi(pending.camera, ownClientId(), pending.request,
                onStep = { step -> cameraMutable.update { it.copy(bluetoothStep = step) } }) { fresh, mode ->
                connectWifi(fresh.ssid, fresh.password(), expectedMode = mode, expectedDevice = pending.device)
                bindBluetoothWifi(pending.camera, fresh, expectedMode = mode, expectedDevice = pending.device)
            }
            clearSendingReconnect()
            cameraMutable.update { it.copy(notice = "发送连接已更新。旧接收记录保留，可在当前模式发起接收。") }
        } catch (error: Exception) {
            rememberSendingReconnect(pending.camera, pending.device,
                (error as? CameraSendingReconnectionRequired)?.request ?: pending.request)
            throw error
        }
    }

    private fun clearSendingReconnect() {
        pendingSendingReconnect = null
        cameraMutable.update { it.copy(sendingReconnect = null) }
    }

    private fun rememberSendingReconnect(target: BluetoothCamera, device: CameraDevice?, request: CameraSendingReconnectRequest) {
        pendingSendingReconnect = PendingSendingReconnect(target, device, request)
        cameraMutable.update { it.copy(sendingReconnect = request) }
        releaseSendingConnection()
    }

    private fun releaseSendingConnection() {
        bluetoothWifiBinding = null
        clearBluetoothCredentials()
        clearCameraSession()
        val connection = wifiConnection
        wifiConnection = null
        connection?.close()
    }

    private suspend fun connectBluetoothWifiInternal(startOnCamera: Boolean, waitForSending: Boolean = false) {
        val credentials = bluetoothCredentials ?: error("连接资料已释放，请重新从蓝牙相机读取")
        val target = bluetoothTarget ?: error("请重新查找蓝牙相机")
        clearSendingReconnect()
        try {
            if (waitForSending) {
                bluetooth.withSendingWifi(target, ownClientId(), credentials.ssid,
                    onStep = { step -> cameraMutable.update { it.copy(bluetoothStep = step) } }) { fresh, mode ->
                    connectWifi(fresh.ssid, fresh.password(), expectedMode = mode)
                    bindBluetoothWifi(target, fresh, expectedMode = mode)
                }
            } else if (startOnCamera) {
                bluetooth.withTransferWifi(target, ownClientId(), credentials.ssid,
                    onStep = { step -> cameraMutable.update { it.copy(bluetoothStep = step) } }) { fresh ->
                    connectWifi(fresh.ssid, fresh.password())
                    bindBluetoothWifi(target, fresh)
                }
            } else {
                connectWifi(credentials.ssid, credentials.password())
                bindBluetoothWifi(target, credentials)
            }
        } catch (error: Exception) {
            if (error is CameraSendingReconnectionRequired) rememberSendingReconnect(target, null, error.request)
            if (startOnCamera) {
                bluetoothWifiBinding = null
                clearCameraSession()
                wifiConnection?.close()
                wifiConnection = null
            }
            throw error
        }
        finally { clearBluetoothCredentials() }
    }

    fun bluetoothPermissionDenied() {
        cameraMutable.update { it.copy(problem = "未获得蓝牙权限，可以使用 Wi-Fi 入口连接相机") }
    }

    private fun clearBluetoothCredentials() {
        bluetoothCredentials?.close()
        bluetoothCredentials = null
        bluetoothTarget = null
        cameraMutable.update { it.copy(bluetoothWifiSsid = null, bluetoothCanStartWifi = false,
            bluetoothCanStartSendingWifi = false) }
    }

    private fun ownClientId(): String {
        val preferences = application.getSharedPreferences("camera-client", 0)
        return preferences.getString("client-id", null) ?: LumixCameraSession.newClientId().also {
            preferences.edit().putString("client-id", it).apply()
        }
    }

    private suspend fun connectWifi(ssid: String, password: String, expectedMode: CameraAccessMode? = null,
        expectedDevice: CameraDevice? = null) {
        bluetoothWifiBinding = null
        clearCameraSession()
        wifiConnection?.close()
        wifiConnection = null
        cameraMutable.value = CameraWorkspaceState(step = ConnectionStep.REQUESTING_WIFI, operationRunning = true,
            bluetoothStep = cameraMutable.value.bluetoothStep)
        if (Build.VERSION.SDK_INT < 33 &&
            application.getSystemService(LocationManager::class.java)?.isLocationEnabled != true) {
            error("当前 Android 版本需要开启系统定位后才能发起 Wi-Fi 连接；也可以先在系统设置中连接")
        }
        wifiConnection = CameraWifiConnector.connect(application, ssid, password, ::cameraNetworkLost)
        cameraMutable.update { it.copy(step = ConnectionStep.DISCOVERING) }
        findCamera(expectedMode, expectedDevice)
    }

    private fun bindBluetoothWifi(target: BluetoothCamera, credentials: CameraWifiCredentials,
        expectedMode: CameraAccessMode? = null, expectedDevice: CameraDevice? = null) {
        val network = wifiConnection?.network ?: return
        bluetoothWifiBinding = BluetoothWifiBinding(network, target, credentials.ssid, credentials.canObserveIncoming,
            expectedMode, expectedDevice)
        cameraMutable.update { it.copy(bluetoothCanFollowIncoming = credentials.canObserveIncoming &&
            cameraSession?.accessMode?.incoming == true && watchedNetwork == network) }
    }

    fun chooseCamera(key: String) = cameraOperation {
        val candidate = cameraMutable.value.discovered.firstOrNull { it.key == key } ?: error("请重新查找相机")
        connectTarget(candidate)
    }

    fun reconnectCamera() = cameraOperation {
        check(pendingSendingReconnect == null) { "相机已请求更新发送连接，请使用发送更新入口" }
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

    private fun matchesExpected(device: CameraDevice, expected: CameraDevice): Boolean =
        if (expected.udn.isNotBlank()) device.udn == expected.udn
        else device.model == expected.model && device.descriptionUri == expected.descriptionUri

    private suspend fun findCamera(expectedMode: CameraAccessMode? = null, expectedDevice: CameraDevice? = null) {
        val candidates = discovery.discover(wifiConnection?.let { listOf(it.network) } ?: discovery.wifiNetworks())
            .filter { expectedDevice == null || matchesExpected(it.device, expectedDevice) }
        cameraMutable.update { it.copy(discovered = candidates) }
        if (candidates.isEmpty()) error(if (expectedDevice == null) "未发现相机，请开启相机的 Wi-Fi 传输功能并确认连接"
            else "新网络未发现原相机，未将旧队列绑定到其他相机")
        if (candidates.size == 1) connectTarget(candidates.single(), expectedMode = expectedMode, expectedDevice = expectedDevice)
    }

    private suspend fun connectTarget(candidate: DiscoveredCamera, restoreSelection: Boolean = false,
        expectedMode: CameraAccessMode? = bluetoothWifiBinding?.takeIf { it.network == candidate.network }?.expectedMode,
        expectedDevice: CameraDevice? = bluetoothWifiBinding?.takeIf { it.network == candidate.network }?.expectedDevice) {
        val previous = cameraMutable.value
        var connected = false
        try {
            clearCameraSession()
            cameraMutable.update { it.copy(disconnected = false, step = ConnectionStep.READING_DEVICE) }
            val clientId = ownClientId()
            watchedNetwork = candidate.network
            networkWatch = CameraWifiConnector.watch(application, candidate.network) { cameraNetworkLost(candidate.network) }
            val session = LumixCameraSession.connect(candidate.network, candidate.device.descriptionUri.host, clientId,
                descriptionUri = candidate.device.descriptionUri) { step ->
                cameraMutable.update { it.copy(step = step) }
            }
            cameraSession = session
            check(expectedDevice == null || matchesExpected(session.device, expectedDevice)) { "相机身份已变化，未继续旧接收队列" }
            check(expectedMode == null || session.accessMode == expectedMode) {
                "相机 HTTP 发送模式与蓝牙请求不一致，请重新发起发送连接"
            }
            val incoming = if (session.accessMode.incoming) session.prepareIncoming()
                else { session.prepareBrowsing(); null }
            val restoreBrowsing = restoreSelection && incoming == null && previous.accessMode == CameraAccessMode.BROWSING
            cameraMutable.update { it.copy(device = session.device, entries = emptyList(), selectedIds = emptySet(),
                accessMode = session.accessMode, incomingCount = null,
                folders = if (incoming != null) listOf(CameraFolder(incoming.objectId,
                    if (incoming.mode == CameraAccessMode.AUTO_UPLOAD) "拍后自动发送目录" else "相机选定的发送目录"))
                    else if (restoreBrowsing) previous.folders else listOf(CameraFolder("0", "相机媒体"))) }
            loadCameraFolder()
            if (restoreBrowsing && previous.selectedIds.isNotEmpty()) {
                // Reload the previously viewed pages before restoring IDs; never reuse stale resource URLs.
                while (cameraMutable.value.nextOffset != null &&
                    !cameraMutable.value.entries.map { it.id }.toSet().containsAll(previous.selectedIds)) {
                    loadCameraFolder(append = true)
                }
                val previousPhotos = previous.entries.filter { it.id in previous.selectedIds }.associateBy { it.id }
                val available = cameraMutable.value.entries.filter { entry ->
                    previousPhotos[entry.id]?.let { entry.matchesPreviousMedia(it) } == true
                }.map { it.id }.toSet()
                cameraMutable.update { it.copy(selectedIds = previous.selectedIds.intersect(available),
                    problem = if (!available.containsAll(previous.selectedIds)) "部分待传文件缺失或信息已变化，请重新选择" else null) }
            }
            cameraMutable.update { it.copy(connected = true, bluetoothCanFollowIncoming = session.accessMode.incoming &&
                bluetoothWifiBinding?.let { binding -> binding.network == candidate.network && binding.canObserve } == true) }
            connected = true
        } finally {
            if (restoreSelection && !connected) cameraMutable.update { it.copy(device = previous.device,
                entries = previous.entries, selectedIds = previous.selectedIds, folders = previous.folders,
                accessMode = previous.accessMode, incomingCount = previous.incomingCount) }
        }
    }

    private fun cameraNetworkLost(network: Network) {
        if (network != watchedNetwork && network != wifiConnection?.network) return
        cameraMutable.update { it.copy(connected = false, disconnected = true,
            problem = "相机连接已断开，已传入的照片保留。重新连接后可继续传输。") }
        cameraJob?.cancel(TransferStop(false, "相机断开，操作已暂停"))
        stopTransfer(false, "相机断开，传输已暂停")
        clearCameraSession()
        bluetoothWifiBinding = null
        wifiConnection?.close()
        wifiConnection = null
    }

    private fun clearCameraSession() {
        watchedNetwork = null
        networkWatch?.close()
        networkWatch = null
        cameraSession?.close()
        cameraSession = null
        cameraMutable.update { it.copy(connected = false, bluetoothCanFollowIncoming = false) }
    }

    fun openCameraFolder(entry: CameraMedia) {
        if (!entry.isFolder || cameraMutable.value.accessMode.incoming) return
        cameraOperation {
            loadCameraFolder(folders = cameraMutable.value.folders + CameraFolder(entry.id, entry.title))
        }
    }

    fun cameraFolderBack() = cameraOperation {
        check(!cameraMutable.value.accessMode.incoming) { "待发送模式没有普通浏览目录" }
        if (cameraMutable.value.folders.size > 1) {
            loadCameraFolder(folders = cameraMutable.value.folders.dropLast(1))
        }
    }

    fun loadMoreCameraPhotos() = cameraOperation { loadCameraFolder(append = true) }
    fun refreshCameraPhotos() = cameraOperation { loadCameraFolder() }

    fun selectCameraPhoto(id: String) {
        if (cameraMutable.value.busy || cameraMutable.value.accessMode.incoming) return
        cameraMutable.update { it.copy(selectedIds = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id) }
    }

    fun importCameraPhotos() = cameraOperation {
        val session = cameraSession ?: error("请先连接相机")
        check(!session.accessMode.incoming) { "此模式请使用待发送接收入口" }
        val photos = cameraMutable.value.entries.filter { it.id in cameraMutable.value.selectedIds && it.transferResource != null }
        val batchId = store.enqueue(session.device, photos)
        cameraMutable.update { it.copy(selectedIds = it.selectedIds - photos.map { photo -> photo.id }.toSet()) }
        requestTransfer(batchId)
    }

    fun receiveIncomingPhotos(follow: Boolean = false) = cameraOperation {
        val session = cameraSession ?: error("请先连接相机")
        check(session.accessMode.incoming) { "请先在相机上进入发送模式" }
        val receiving = store.startIncoming(session.device, session.preparedIncomingDirectory())
        requestTransfer(receiving.id, follow)
    }

    fun resumeTransferBatch(batchId: String) = cameraOperation { requestTransfer(batchId) }
    fun cancelTransferBatch(batchId: String) = cameraOperation {
        store.stopBatch(batchId, cancel = true, reason = "已取消后续传输，已导入照片和已保存视频保留")
        cameraMutable.update { it.copy(transferCache = null) }
    }

    fun inspectTransferCache() = cameraOperation(cache = true) {
        cameraMutable.update { it.copy(transferCache = null) }
        val snapshot = transferCache.inspect()
        cameraMutable.update { it.copy(transferCache = snapshot) }
    }

    fun clearTransferCache() = cameraOperation(cache = true) {
        cameraMutable.update { it.copy(transferCache = null) }
        val result = transferCache.clean()
        cameraMutable.update { it.copy(transferCache = result.remaining.copy(
            problems = (result.problems + result.remaining.problems).distinct()),
            notice = "已清理 ${storageBytes(result.privateBytesReleased + result.pendingVideoBytesReleased)} 缓存：${result.filesRemoved} 个中间文件、${result.pendingVideosRemoved} 个未发布视频占位记录") }
    }
    fun stopTransfer(cancel: Boolean, message: String) {
        val stop = TransferStop(cancel, message)
        if (transferJob != null) transferJob?.cancel(stop)
        else if (cameraMutable.value.activeTransferBatch != null) pendingStop = stop
        cameraSession?.interrupt()
    }

    private suspend fun requestTransfer(batchId: String, follow: Boolean = false) {
        check(pendingSendingReconnect == null) { "请先更新相机发送连接" }
        check(cameraMutable.value.activeTransferBatch == null) { "已有正在执行的传输" }
        val incoming = store.incoming(batchId)
        val session = cameraSession
        if (incoming != null) {
            check(!incoming.terminal) { "此接收记录已结束，已保存结果保留" }
            check(session != null && incoming.belongsTo(session.device) && incoming.accessMode == session.accessMode &&
                incoming.directoryId == session.preparedIncomingDirectory().objectId) {
                "请连接此记录对应的相机并进入相同发送模式后继续"
            }
        } else check(session?.accessMode?.incoming != true) { "普通队列请在结束相机发送模式后继续" }
        val binding = if (follow) requireNotNull(bluetoothWifiBinding?.takeIf {
            incoming != null && it.canObserve && it.network == watchedNetwork
        }) { "后续拍摄接收需要通过已识别的蓝牙相机 Wi-Fi 连接入口连接" } else null
        val tasks = store.batch(batchId)
        if (incoming == null && tasks.all { it.terminal }) {
            cameraMutable.update { it.copy(notice = "此批没有未完成的任务") }
            return
        }
        pendingStop = null
        followBinding = binding
        cameraMutable.update { it.copy(activeTransferBatch = batchId, notice = null, transferCache = null,
            receiving = incoming != null, transferPhase = if (incoming != null) TransferPhase.RESOLVING else null,
            followingIncoming = follow, waitingForIncoming = false,
            transferTotal = tasks.size, transferred = tasks.count { task -> task.completed }) }
        try {
            application.startForegroundService(CameraTransferService.startIntent(application, batchId))
        } catch (error: Exception) {
            followBinding = null
            cameraMutable.update { it.copy(activeTransferBatch = null, receiving = false, followingIncoming = false,
                problem = "无法启动后台传输：${error.message ?: "请在工作台重试"}") }
        }
    }

    /** Only the foreground service supplies the execution scope. Network objects stay in this process. */
    fun runInService(batchId: String, serviceScope: CoroutineScope): Job? {
        if (cameraMutable.value.activeTransferBatch != batchId || transferJob != null) return null
        val session = cameraSession
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            val binding = followBinding
            try {
                pendingStop?.let { throw it }
                var currentTask: String? = null
                var lastProgressAt = 0L
                val phase: (TransferTask, TransferPhase) -> Unit =
                    { _, value -> cameraMutable.update { it.copy(transferPhase = value, progress = null) } }
                val progress: (TransferTask, DownloadProgress) -> Unit = { task, progress ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (currentTask != task.id || now - lastProgressAt >= 100 || progress.bytes == progress.totalBytes) {
                        currentTask = task.id
                        lastProgressAt = now
                        cameraMutable.update { it.copy(progress = progress) }
                    }
                }
                val result = if (store.incoming(batchId) != null) {
                    val sendingSession = requireNotNull(session) { "请连接相机后继续接收" }
                    val count: (Int) -> Unit = { count -> cameraMutable.update { it.copy(incomingCount = count,
                            entries = if (count == 0) emptyList() else it.entries,
                            nextOffset = if (count == 0) null else it.nextOffset,
                            total = if (count == 0) 0 else it.total) } }
                    if (binding != null) bluetooth.withIncomingEvents(binding.camera, ownClientId(), binding.ssid,
                        sendingSession.accessMode,
                        onStep = { step -> cameraMutable.update { it.copy(bluetoothStep = step) } }) { events ->
                        cameraMutable.update { it.copy(bluetoothStep = null) }
                        incomingReceiver.run(batchId, sendingSession, onCount = count, onPhase = phase,
                            onProgress = progress, awaitNext = events::awaitFiles,
                            onWaiting = { waiting -> cameraMutable.update { it.copy(waitingForIncoming = waiting,
                                progress = null, transferPhase = if (waiting) null else TransferPhase.RESOLVING) } })
                    } else incomingReceiver.run(batchId, sendingSession, onCount = count, onPhase = phase, onProgress = progress)
                } else transferRunner.run(batchId, session, onPhase = phase, onProgress = progress)
                cameraMutable.update { it.copy(notice = "本次传入 ${result.imported} 张照片、保存 ${result.savedVideos} 个视频；失败 ${result.failed} 项、待连接 ${result.waiting} 项") }
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    store.stopBatch(batchId, (error as? TransferStop)?.cancelRemaining == true,
                        error.message ?: "服务结束，传输已暂停")
                }
                if (error is CameraSendingStopped) releaseSendingConnection()
                cameraMutable.update { it.copy(notice = error.message ?: "传输已暂停") }
                throw error
            } catch (error: Exception) {
                withContext(NonCancellable) { store.stopBatch(batchId, false, error.message ?: "接收已暂停") }
                if (error is CameraSendingReconnectionRequired && binding != null && session != null)
                    rememberSendingReconnect(binding.camera, session.device, error.request)
                cameraMutable.update { it.copy(problem = error.message ?: "传输失败，请重试队列") }
            } finally {
                transferJob = null
                pendingStop = null
                followBinding = null
                cameraMutable.update { it.copy(activeTransferBatch = null, receiving = false, followingIncoming = false,
                    waitingForIncoming = false, bluetoothStep = null, progress = null, transferPhase = null) }
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
            followBinding = null
            cameraMutable.update { it.copy(activeTransferBatch = null, receiving = false, followingIncoming = false,
                waitingForIncoming = false, problem = message) }
            pendingStop = null
            releaseIfIdle()
        }
    }

    private suspend fun loadCameraFolder(append: Boolean = false,
        folders: List<CameraFolder> = cameraMutable.value.folders) {
        val session = cameraSession ?: error("请先连接相机")
        val snapshot = cameraMutable.value
        val offset = if (append) snapshot.nextOffset ?: return else 0
        val incomingCount = if (!append && session.accessMode.incoming) {
            session.incomingContentCount()
        } else snapshot.incomingCount
        if (!append && incomingCount == 0 && session.accessMode.incoming) {
            cameraMutable.update { it.copy(folders = folders, entries = emptyList(), selectedIds = emptySet(),
                nextOffset = null, total = 0, updateId = null, incomingCount = 0) }
            return
        }
        val page = session.browse(folders.last().id, offset)
        if (append && snapshot.updateId != page.updateId) error("相机照片列表已更新，请刷新后重新选择")
        cameraMutable.update { it.copy(folders = folders,
            entries = (if (append) it.entries + page.entries else page.entries).distinctBy { entry -> entry.id },
            selectedIds = if (append) it.selectedIds else emptySet(), nextOffset = page.nextOffset,
            total = page.totalMatches, updateId = page.updateId, incomingCount = incomingCount) }
    }

    private fun cameraOperation(cache: Boolean = false, block: suspend () -> Unit) {
        if (cameraMutable.value.busy) return
        cameraMutable.update { it.copy(operationRunning = true, cacheBusy = cache, bluetoothStep = null) }
        cameraMutable.update { it.copy(problem = null, notice = null, transferTotal = 0, transferred = 0, progress = null, transferPhase = null) }
        cameraJob = scope.launch {
            try { block() }
            catch (error: CancellationException) {
                cameraMutable.update { if (it.disconnected) it else it.copy(problem = error.message ?: "操作已取消，已传入的照片保留") }
                if (!cameraMutable.value.connected) { clearCameraSession(); wifiConnection?.close(); wifiConnection = null }
            } catch (error: Exception) {
                cameraMutable.update { it.copy(problem = error.message ?: "相机连接或传输失败") }
                if (!cameraMutable.value.connected) { clearCameraSession(); wifiConnection?.close(); wifiConnection = null }
            } finally { cameraMutable.update { it.copy(operationRunning = false, cacheBusy = false, bluetoothStep = null) }; releaseIfIdle() }
        }
    }

    fun cancelCameraOperation() {
        if (cameraMutable.value.activeTransferBatch != null)
            stopTransfer(true, "已取消后续传输，已导入照片保留")
        else { cameraJob?.cancel(); cameraSession?.interrupt() }
    }
    fun disconnectCamera() {
        if (cameraMutable.value.busy) return
        clearSendingReconnect()
        clearBluetoothCredentials()
        bluetoothWifiBinding = null
        clearCameraSession()
        wifiConnection?.close()
        wifiConnection = null
        cameraMutable.value = CameraWorkspaceState()
    }

}
