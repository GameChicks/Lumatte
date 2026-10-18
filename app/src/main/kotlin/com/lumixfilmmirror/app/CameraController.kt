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
import java.util.UUID
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
    private var browsingHeartbeat: Job? = null
    private val thumbnails = CameraThumbnailLoader()
    private var thumbnailVersion = 0L
    private var cameraJob: Job? = null
    private var transferJob: Job? = null
    private var pendingStop: TransferStop? = null
    private val discovery = CameraDiscovery(application)
    private val bluetooth = CameraBluetooth(application)
    private var bluetoothCredentials: CameraWifiCredentials? = null
    private var bluetoothTarget: BluetoothCamera? = null
    private class PendingBluetoothSelection {
        var startTransfer = true
        val stop = CompletableDeferred<Unit>()
        var camera: BluetoothCamera? = null
    }
    private var pendingBluetoothSelection: PendingBluetoothSelection? = null
    private data class BluetoothWifiBinding(val network: Network, val camera: BluetoothCamera,
        val ssid: String, val canObserve: Boolean, val expectedMode: CameraAccessMode? = null,
        val expectedDevice: CameraDevice? = null, val routerSsid: String? = null)
    private data class PendingSendingReconnect(val camera: BluetoothCamera, val device: CameraDevice?,
        val request: CameraSendingReconnectRequest, val routerSsid: String? = null)
    private var pendingSendingReconnect: PendingSendingReconnect? = null
    private var bluetoothWifiBinding: BluetoothWifiBinding? = null
    private var followBinding: BluetoothWifiBinding? = null
    private var wifiConnection: CameraWifiConnection? = null
    private var networkWatch: Closeable? = null
    private var watchedNetwork: Network? = null
    private var visibleClients = 0
    private var idleRelease: Job? = null
    // A user-started handshake survives Android's Bluetooth/Wi-Fi confirmation Activity.
    // This is bounded below; it does not enable background scanning or automatic reconnect.
    private var bluetoothConnectionInProgress = false
    private data class PendingWifiSelection(val id: String, val title: String,
        var options: List<CameraWifiNetwork>, val reply: CompletableDeferred<Network?>)
    private var pendingWifiSelection: PendingWifiSelection? = null

    init {
        CameraConnectionLog.initialize(application)
        scope.launch {
            cameraState.map { listOf(it.bluetoothStep, it.step, it.connected, it.bluetoothPairingCompleted) }
                .distinctUntilChanged().collect { state ->
                    if (state[0] != null || state[1] != null || state[2] == true || state[3] == true)
                        CameraConnectionLog.info("LFMCamera", "phase bt=${state[0]} wifiHttp=${state[1]} " +
                            "mediaReady=${state[2]} pairingApproved=${state[3]}")
                }
        }
    }

    fun updateTransferCounts(batchId: String, total: Int, imported: Int) {
        cameraMutable.update { if (it.activeTransferBatch == batchId)
            it.copy(transferTotal = total, transferred = imported) else it }
    }

    fun attachUi() {
        idleRelease?.cancel(); visibleClients++
        CameraConnectionLog.info("LFMActivity", "ui=visible")
    }
    fun detachUi() {
        visibleClients = (visibleClients - 1).coerceAtLeast(0)
        CameraConnectionLog.info("LFMActivity", "ui=${if (visibleClients == 0) "background" else "visible"} " +
            "connectionInProgress=$bluetoothConnectionInProgress")
        if (visibleClients == 0) thumbnails.cancelRequests()
        releaseIfIdle()
    }
    private fun releaseIfIdle() {
        if (bluetoothConnectionInProgress) return
        if (visibleClients == 0 && (!cameraMutable.value.busy ||
            ((cameraMutable.value.bluetoothStep != null || cameraMutable.value.wifiSelection != null) &&
                cameraMutable.value.activeTransferBatch == null))) {
            idleRelease?.cancel()
            idleRelease = scope.launch {
                delay(30_000) // Allow system pickers, sharing and brief app switches without dropping Wi-Fi.
                if (visibleClients == 0 && !bluetoothConnectionInProgress) {
                    if (cameraMutable.value.bluetoothStep != null || cameraMutable.value.wifiSelection != null)
                        cameraJob?.cancel(CancellationException("已离开连接界面，连接操作已取消"))
                    if (!cameraMutable.value.busy) {
                        CameraConnectionLog.info("LFMCamera", "sessionRelease=background-grace-expired")
                        bluetoothWifiBinding = null
                        clearBluetoothCredentials()
                        clearCameraSession()
                        wifiConnection?.close()
                        wifiConnection = null
                        cameraMutable.update { it.copy(step = null,
                            notice = if (it.device != null) "后台空闲连接已释放，点击重新连接可继续。" else it.notice) }
                    }
                }
            }
        }
    }

    fun connectCamera() = cameraOperation {
        val network = selectExistingWifi("选择查找相机的 Wi-Fi")
        clearSendingReconnect()
        prepareExistingWifi(network)
        cameraMutable.value = CameraWorkspaceState(step = ConnectionStep.DISCOVERING, operationRunning = true)
        findCamera()
    }

    fun connectCameraWifi(ssid: String, password: String, security: CameraWifiSecurity) = cameraOperation {
        clearSendingReconnect()
        clearBluetoothCredentials()
        connectWifi(ssid, password, security = security)
    }

    fun scanBluetoothCameras() = cameraOperation {
        clearBluetoothCredentials()
        cameraMutable.update { it.copy(bluetoothCameras = emptyList(), bluetoothScanReport = null,
            bluetoothSelectedCamera = null,
            bluetoothStep = BluetoothConnectionStep.SCANNING) }
        var report: BluetoothScanReport? = null
        val selection = PendingBluetoothSelection()
        pendingBluetoothSelection = selection
        try {
            val cameras = bluetooth.scan(onReport = { evidence ->
                report = evidence
                cameraMutable.update { it.copy(bluetoothScanReport = evidence) }
            }, stopRequested = selection.stop) { found -> cameraMutable.update { it.copy(bluetoothCameras = found) } }
            currentCoroutineContext().ensureActive()
            // One operation owns both scan shutdown and GATT startup; cancellation closes both.
            val chosen = selection.camera
            if (chosen != null) readBluetoothCandidate(chosen, selection.startTransfer)
            else if (cameras.isEmpty()) error(report?.noCameraMessage() ?: "未收到可连接的相机广播，请重新查找")
        } finally { if (pendingBluetoothSelection === selection) pendingBluetoothSelection = null }
    }

    fun readBluetoothCamera(address: String, startTransfer: Boolean = true) {
        val selection = pendingBluetoothSelection
        if (selection != null && cameraMutable.value.bluetoothStep == BluetoothConnectionStep.SCANNING) {
            val candidate = cameraMutable.value.bluetoothCameras.firstOrNull { it.address == address } ?: return
            if (!selection.stop.isCompleted) {
                selection.camera = candidate
                selection.startTransfer = startTransfer
                cameraMutable.update { it.copy(bluetoothStep = BluetoothConnectionStep.CONNECTING,
                    bluetoothSelectedCamera = candidate, notice = "已选择 ${candidate.name}，正在停止查找并连接…") }
                CameraConnectionLog.info("LFMCamera", "scanSelection=accepted action=stop-scan-and-connect")
                selection.stop.complete(Unit)
            }
            return
        }
        cameraOperation {
            val candidate = cameraMutable.value.bluetoothCameras.firstOrNull { it.address == address }
                ?: error("请重新查找蓝牙相机")
            readBluetoothCandidate(candidate, startTransfer)
        }
    }

    private suspend fun <T> withBluetoothConnectionLifetime(block: suspend () -> T): T {
        check(!bluetoothConnectionInProgress)
        idleRelease?.cancel()
        bluetoothConnectionInProgress = true
        try {
            return withTimeout(300_000L) { block() }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            error("连接相机等待超时，请确认手机系统弹窗和相机上的连接请求后重试")
        } finally {
            bluetoothConnectionInProgress = false
            releaseIfIdle()
        }
    }

    private suspend fun readBluetoothCandidate(candidate: BluetoothCamera, startTransfer: Boolean) = withBluetoothConnectionLifetime {
        clearSendingReconnect()
        clearBluetoothCredentials()
        cameraMutable.update { it.copy(bluetoothStep = BluetoothConnectionStep.CONNECTING, bluetoothSelectedCamera = candidate,
            bluetoothPairingCompleted = false) }
        // The reference stops scanning before connecting and leaves a 500 ms handoff interval.
        delay(500L)
        if (!startTransfer) {
            // Explicit advanced action for an already paired camera. Does not claim a new pairing.
            val credentials = bluetooth.readWifi(candidate, ownClientId()) { step ->
                cameraMutable.update { it.copy(bluetoothStep = step) }
            }
            bluetoothCredentials = credentials
            bluetoothTarget = candidate
            cameraMutable.update { it.copy(bluetoothWifiSsid = credentials.ssid,
                bluetoothCanStartWifi = credentials.canStartTransferWifi,
                bluetoothCanStartRouterWifi = credentials.canStartRouterWifi,
                bluetoothCanStartSendingWifi = credentials.canStartTransferWifi && credentials.canObserveIncoming,
                notice = "已读取网络资料，请选择传输或路由器连接方式；这不代表新配对已完成。") }
            return@withBluetoothConnectionLifetime
        }
        connectBluetoothTransfer(candidate)
    }

    private suspend fun connectBluetoothTransfer(candidate: BluetoothCamera, expectedSsid: String? = null) {
        CameraConnectionLog.info("LFMCamera", "connectionFlow=sync-trace-v3 phase=${if (candidate.pairing) "registration" else "media-connect"}")
        var target = candidate
        var pairedDevice: CameraDevice? = null
        if (candidate.pairing) {
            bluetooth.withTransferWifi(candidate, ownClientId(), expectedSsid,
                onStep = { step -> cameraMutable.update { it.copy(bluetoothStep = step) } }) { credentials ->
                // First-pairing mode 5 ends at HTTP approval, before playmode or directory setup.
                connectWifi(credentials.ssid, credentials.password(), expectedMode = CameraAccessMode.BROWSING,
                    registrationOnly = true)
                pairedDevice = requireNotNull(cameraMutable.value.device) { "配对确认缺少相机身份，请重新查找" }
            }
            // Registration has released its transport. Re-read credentials in a fresh mode-3 session.
            target = candidate.copy(pairing = false)
            cameraMutable.update { it.copy(bluetoothSelectedCamera = target,
                bluetoothPairingCompleted = true, notice = "首次配对已完成，正在建立照片浏览连接。") }
            CameraConnectionLog.info("LFMCamera", "connectionFlow=sync-trace-v3 registration=approved phase=media-connect")
            delay(500L)
        }
        bluetooth.withTransferWifi(target, ownClientId(), expectedSsid,
            onStep = { step -> cameraMutable.update { it.copy(bluetoothStep = step) } }) { credentials ->
            connectWifi(credentials.ssid, credentials.password(), expectedMode = CameraAccessMode.BROWSING,
                expectedDevice = pairedDevice)
            bindBluetoothWifi(target, credentials, expectedDevice = pairedDevice)
        }
    }

    fun connectBluetoothWifi(startOnCamera: Boolean = false) = cameraOperation {
        connectBluetoothWifiInternal(startOnCamera)
    }

    fun connectBluetoothSendingWifi() = cameraOperation {
        connectBluetoothWifiInternal(startOnCamera = true, waitForSending = true)
    }

    fun connectBluetoothRouterWifi(routerSsid: String) = cameraOperation {
        val target = bluetoothTarget ?: error("请重新查找蓝牙相机")
        check(bluetoothCredentials?.canStartRouterWifi == true) { "请重新读取相机连接资料" }
        val router = CameraRouterSsid.parse(routerSsid)
        val network = selectExistingWifi("选择相机将加入的路由器网络")
        clearSendingReconnect()
        prepareExistingWifi(network)
        try {
            bluetooth.withRouterWifi(target, ownClientId(), router,
                onStep = { step -> cameraMutable.update { it.copy(bluetoothStep = step) } }) {
                discoverRouterCamera(network, target, routerSsid, routerSsid, canObserve = false,
                    expectedMode = CameraAccessMode.BROWSING)
            }
        } catch (error: Exception) {
            releaseSendingConnection()
            throw error
        }
    }

    fun connectBluetoothRouterSendingWifi(routerSsid: String) = cameraOperation {
        val target = bluetoothTarget ?: error("请重新查找蓝牙相机")
        check(bluetoothCredentials?.let { it.canStartRouterWifi && it.canObserveIncoming } == true) {
            "请重新读取相机连接资料，或使用直连发送入口"
        }
        val router = CameraRouterSsid.parse(routerSsid)
        val network = selectExistingWifi("选择用于接收相机发送的路由器网络")
        clearSendingReconnect()
        prepareExistingWifi(network)
        try {
            bluetooth.withRouterSendingWifi(target, ownClientId(), router,
                onStep = { step -> cameraMutable.update { it.copy(bluetoothStep = step) } }) { fresh, mode, address ->
                discoverRouterCamera(network, target, routerSsid, fresh.ssid, fresh.canObserveIncoming, mode,
                    address = address)
            }
        } catch (error: Exception) {
            if (error is CameraSendingReconnectionRequired)
                rememberSendingReconnect(target, null, error.request, routerSsid)
            else releaseSendingConnection()
            throw error
        }
    }

    private suspend fun selectExistingWifi(title: String): Network {
        val options = discovery.wifiOptions()
        check(options.isNotEmpty()) { "请先在系统设置连接相机或路由器 Wi-Fi" }
        if (options.size == 1) return options.single().network
        val pending = PendingWifiSelection(UUID.randomUUID().toString(), title, options, CompletableDeferred())
        check(pendingWifiSelection == null)
        pendingWifiSelection = pending
        cameraMutable.update { it.copy(wifiSelection = CameraWifiSelection(pending.id, title, options)) }
        try {
            val selected = pending.reply.await() ?: throw CancellationException("已取消网络选择，未启动本次连接")
            check(selected in discovery.wifiNetworks()) { "所选 Wi-Fi 已断开，请重新连接" }
            return selected
        } finally {
            if (pendingWifiSelection === pending) {
                pendingWifiSelection = null
                cameraMutable.update { if (it.wifiSelection?.id == pending.id) it.copy(wifiSelection = null) else it }
            }
            pending.reply.cancel()
        }
    }

    fun chooseWifiNetwork(requestId: String, key: Long) {
        val pending = pendingWifiSelection?.takeIf { it.id == requestId && !it.reply.isCompleted } ?: return
        val network = pending.options.firstOrNull { it.key == key }?.network ?: return
        try {
            if (network !in discovery.wifiNetworks()) {
                refreshWifiSelection(requestId, "此 Wi-Fi 已断开，请刷新后重新选择")
                return
            }
            pending.reply.complete(network)
        } catch (error: Exception) {
            cameraMutable.update { if (it.wifiSelection?.id == requestId)
                it.copy(wifiSelection = it.wifiSelection.copy(problem = error.message ?: "无法读取当前网络")) else it }
        }
    }

    fun refreshWifiSelection(requestId: String, problem: String? = null) {
        val pending = pendingWifiSelection?.takeIf { it.id == requestId && !it.reply.isCompleted } ?: return
        try {
            pending.options = discovery.wifiOptions()
            cameraMutable.update { if (it.wifiSelection?.id == requestId)
                it.copy(wifiSelection = CameraWifiSelection(requestId, pending.title, pending.options, problem)) else it }
        } catch (error: Exception) {
            cameraMutable.update { if (it.wifiSelection?.id == requestId)
                it.copy(wifiSelection = it.wifiSelection.copy(problem = error.message ?: "无法刷新网络")) else it }
        }
    }

    fun cancelWifiSelection(requestId: String) {
        pendingWifiSelection?.takeIf { it.id == requestId }?.reply?.complete(null)
    }

    private fun prepareExistingWifi(network: Network) {
        bluetoothWifiBinding = null
        clearCameraSession()
        clearBluetoothCredentials()
        val previous = wifiConnection
        if (previous?.network == network) {
            // Retain a request-owned Wi-Fi too; closing it here could drop the selected network.
            check(network in discovery.wifiNetworks()) { "所选 Wi-Fi 已断开，请重新连接" }
        } else {
            val replacement = CameraWifiConnector.useExisting(application, network, ::cameraNetworkLost)
            wifiConnection = replacement
            previous?.close()
        }
    }

    private suspend fun discoverRouterCamera(network: Network, target: BluetoothCamera, routerSsid: String,
        bleSsid: String, canObserve: Boolean, expectedMode: CameraAccessMode, expectedDevice: CameraDevice? = null,
        address: CameraConnectionAddress? = null) {
        cameraMutable.update { it.copy(step = ConnectionStep.DISCOVERING) }
        // Association takes time. Keep discovery on this network and preserve the
        // original camera identity when this is a sending reconnect.
        val candidates = if (address != null) discoverCameraAddress(network, address, expectedDevice) else try {
            withTimeout(45_000L) {
                var found = emptyList<DiscoveredCamera>()
                while (found.isEmpty()) {
                    found = discovery.discover(listOf(network)).filter {
                        expectedDevice == null || matchesExpected(it.device, expectedDevice)
                    }
                    if (found.isEmpty()) delay(1_000L)
                }
                found
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            error(if (expectedDevice == null)
                "未在当前路由器发现相机，请核对 Wi-Fi 名称、相机保存的密码，以及路由器是否允许设备互访"
                else "当前路由器未发现原相机，旧接收记录保留，请核对网络后更新连接")
        }
        cameraMutable.update { it.copy(discovered = candidates) }
        // Camera-returned SSID is for the existing BLE identity check. User-entered
        // router SSID is only a transport choice; do not replace one with the other.
        bluetoothWifiBinding = BluetoothWifiBinding(network, target, bleSsid, canObserve,
            expectedMode, expectedDevice, routerSsid)
        if (candidates.size == 1) connectTarget(candidates.single(), expectedMode = expectedMode, expectedDevice = expectedDevice)
    }

    private suspend fun discoverCameraAddress(network: Network, address: CameraConnectionAddress,
        expectedDevice: CameraDevice?): List<DiscoveredCamera> {
        try {
            return withTimeout(45_000L) {
                var found = emptyList<DiscoveredCamera>()
                while (found.isEmpty()) {
                    found = listOfNotNull(discovery.discoverAt(network, address)).filter {
                        expectedDevice == null || matchesExpected(it.device, expectedDevice)
                    }
                    if (found.isEmpty()) delay(1_000L)
                }
                found
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            error(if (expectedDevice == null) "相机返回的地址未响应，请检查相机连接和路由器互访设置后重试"
                else "相机返回的地址未找到原相机，旧接收记录保留，请重新更新连接")
        }
    }

    fun reconnectSendingCamera() = cameraOperation {
        val pending = requireNotNull(pendingSendingReconnect) { "发送更新请求已释放，请重新连接相机" }
        try {
            val routerSsid = pending.routerSsid
            if (routerSsid != null) {
                val router = CameraRouterSsid.parse(routerSsid)
                val network = selectExistingWifi("选择更新发送连接的路由器网络")
                prepareExistingWifi(network)
                bluetooth.withRouterSendingWifi(pending.camera, ownClientId(), router,
                    onStep = { step -> cameraMutable.update { it.copy(bluetoothStep = step) } },
                    reconnect = pending.request) { fresh, mode, address ->
                    discoverRouterCamera(network, pending.camera, routerSsid, fresh.ssid, fresh.canObserveIncoming,
                        mode, pending.device, address)
                }
            } else bluetooth.withSendingReconnectWifi(pending.camera, ownClientId(), pending.request,
                onStep = { step -> cameraMutable.update { it.copy(bluetoothStep = step) } }) { fresh, mode, address ->
                connectWifi(fresh.ssid, fresh.password(), expectedMode = mode, expectedDevice = pending.device,
                    address = address)
                bindBluetoothWifi(pending.camera, fresh, expectedMode = mode, expectedDevice = pending.device)
            }
            clearSendingReconnect()
            cameraMutable.update { it.copy(notice = "发送连接已更新。旧接收记录保留，可在当前模式发起接收。") }
        } catch (error: Exception) {
            rememberSendingReconnect(pending.camera, pending.device,
                (error as? CameraSendingReconnectionRequired)?.request ?: pending.request, pending.routerSsid)
            throw error
        }
    }

    private fun clearSendingReconnect() {
        pendingSendingReconnect = null
        cameraMutable.update { it.copy(sendingReconnect = null) }
    }

    private fun rememberSendingReconnect(target: BluetoothCamera, device: CameraDevice?, request: CameraSendingReconnectRequest,
        routerSsid: String? = null) {
        pendingSendingReconnect = PendingSendingReconnect(target, device, request, routerSsid)
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

    private suspend fun connectBluetoothWifiInternal(startOnCamera: Boolean, waitForSending: Boolean = false) = withBluetoothConnectionLifetime {
        val credentials = bluetoothCredentials ?: error("连接资料已释放，请重新从蓝牙相机读取")
        val target = bluetoothTarget ?: error("请重新查找蓝牙相机")
        clearSendingReconnect()
        try {
            if (waitForSending) {
                bluetooth.withSendingWifi(target, ownClientId(), credentials.ssid,
                    onStep = { step -> cameraMutable.update { it.copy(bluetoothStep = step) } }) { fresh, mode, address ->
                    connectWifi(fresh.ssid, fresh.password(), expectedMode = mode, address = address)
                    bindBluetoothWifi(target, fresh, expectedMode = mode)
                }
            } else if (startOnCamera) {
                connectBluetoothTransfer(target, credentials.ssid)
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
        CameraConnectionLog.warn("LFMBle", "permissionResult=denied")
        cameraMutable.update { it.copy(problem = "所需权限未完整授予。请允许附近设备权限；查找相机还需精确定位权限。也可以使用 Wi-Fi 入口。") }
    }

    private fun clearBluetoothCredentials() {
        bluetoothCredentials?.close()
        bluetoothCredentials = null
        bluetoothTarget = null
        cameraMutable.update { it.copy(bluetoothWifiSsid = null, bluetoothCanStartWifi = false,
            bluetoothCanStartRouterWifi = false,
            bluetoothCanStartSendingWifi = false) }
    }

    private fun ownClientId(): String {
        val preferences = application.getSharedPreferences("camera-client", 0)
        return preferences.getString("client-id", null) ?: LumixCameraSession.newClientId().also {
            preferences.edit().putString("client-id", it).apply()
        }
    }

    private suspend fun connectWifi(ssid: String, password: String, expectedMode: CameraAccessMode? = null,
        expectedDevice: CameraDevice? = null, address: CameraConnectionAddress? = null,
        security: CameraWifiSecurity = CameraWifiSecurity.legacy(password), registrationOnly: Boolean = false) {
        CameraWifiConnector.validateRequest(application, ssid, password, security)
        bluetoothWifiBinding = null
        clearCameraSession()
        wifiConnection?.close()
        wifiConnection = null
        val pairingCompleted = bluetoothConnectionInProgress && cameraMutable.value.bluetoothPairingCompleted
        cameraMutable.value = CameraWorkspaceState(step = ConnectionStep.REQUESTING_WIFI, operationRunning = true,
            bluetoothSelectedCamera = cameraMutable.value.bluetoothSelectedCamera,
            bluetoothStep = cameraMutable.value.bluetoothStep,
            bluetoothPairingCompleted = pairingCompleted,
            notice = cameraMutable.value.notice.takeIf { pairingCompleted })
        if (Build.VERSION.SDK_INT < 33 &&
            application.getSystemService(LocationManager::class.java)?.isLocationEnabled != true) {
            error("当前 Android 版本需要开启系统定位后才能发起 Wi-Fi 连接；也可以先在系统设置中连接")
        }
        wifiConnection = CameraWifiConnector.connect(application, ssid, password, security, ::cameraNetworkLost)
        cameraMutable.update { it.copy(step = ConnectionStep.DISCOVERING) }
        findCamera(expectedMode, expectedDevice, address, registrationOnly)
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
        val network = wifiConnection?.network ?: selectExistingWifi("选择重新查找原相机的 Wi-Fi")
        val previousBinding = bluetoothWifiBinding?.takeIf { it.network == network }
        prepareExistingWifi(network)
        bluetoothWifiBinding = previousBinding
        cameraMutable.update { it.copy(step = ConnectionStep.DISCOVERING, disconnected = false) }
        val candidates = discovery.discover(listOf(network))
        cameraMutable.update { it.copy(discovered = candidates) }
        val candidate = candidates.firstOrNull { target ->
            if (expected.udn.isNotBlank()) target.device.udn == expected.udn
            else target.device.descriptionUri == expected.descriptionUri && target.device.model == expected.model
        } ?: error("未找到原相机，请确认 Wi-Fi 连接，或使用连接入口重新连接")
        connectTarget(candidate, restoreSelection = true, expectedMode = previousBinding?.expectedMode,
            expectedDevice = expected)
    }

    fun cameraPermissionDenied() {
        CameraConnectionLog.warn("LFMWifi", "permissionResult=denied")
        cameraMutable.update { it.copy(problem = "未获得 Wi-Fi 连接权限，可在系统设置连接后查找相机") }
    }

    private fun matchesExpected(device: CameraDevice, expected: CameraDevice): Boolean =
        if (expected.udn.isNotBlank()) device.udn == expected.udn
        else device.model == expected.model && device.descriptionUri == expected.descriptionUri

    private suspend fun findCamera(expectedMode: CameraAccessMode? = null, expectedDevice: CameraDevice? = null,
        address: CameraConnectionAddress? = null, registrationOnly: Boolean = false) {
        val network = requireNotNull(wifiConnection?.network) { "请先选择并连接相机网络" }
        check(network in discovery.wifiNetworks()) { "相机网络已断开，请重新连接" }
        val candidates = if (address != null) discoverCameraAddress(network, address, expectedDevice)
        else discoverReadyCamera(network, expectedDevice)
        cameraMutable.update { it.copy(discovered = candidates) }
        if (candidates.isEmpty()) error(if (expectedDevice == null) "未发现相机，请开启相机的 Wi-Fi 传输功能并确认连接"
            else "新网络未发现原相机，未将旧队列绑定到其他相机")
        check(!registrationOnly || candidates.size == 1) { "首次配对网络发现多个相机，未自动登记，请确认相机直连网络后重试" }
        if (candidates.size == 1) connectTarget(candidates.single(), expectedMode = expectedMode, expectedDevice = expectedDevice,
            registrationOnly = registrationOnly)
    }

    private suspend fun discoverReadyCamera(network: Network, expectedDevice: CameraDevice?): List<DiscoveredCamera> {
        try {
            return withTimeout(25_000L) {
                while (true) {
                    check(network in discovery.wifiNetworks()) { "相机 Wi-Fi 已断开，请重新连接" }
                    val found = discovery.discover(listOf(network))
                        .filter { expectedDevice == null || matchesExpected(it.device, expectedDevice) }
                    if (found.isNotEmpty()) return@withTimeout found
                    delay(1_000L)
                }
                @Suppress("UNREACHABLE_CODE") emptyList<DiscoveredCamera>()
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            error(if (expectedDevice == null) "已连接 Wi-Fi，但相机传输服务尚未响应。请保持相机传输页面打开后重试。"
                else "当前 Wi-Fi 未发现原相机，旧队列保留，请确认原相机网络后重试。")
        }
    }

    private suspend fun connectTarget(candidate: DiscoveredCamera, restoreSelection: Boolean = false,
        expectedMode: CameraAccessMode? = bluetoothWifiBinding?.takeIf { it.network == candidate.network }?.expectedMode,
        expectedDevice: CameraDevice? = bluetoothWifiBinding?.takeIf { it.network == candidate.network }?.expectedDevice,
        registrationOnly: Boolean = false) {
        check(wifiConnection?.network == candidate.network && candidate.network in discovery.wifiNetworks()) {
            "原查找网络已断开或变化，请重新查找后选择相机"
        }
        val previous = cameraMutable.value
        var connected = false
        try {
            clearCameraSession()
            cameraMutable.update { it.copy(disconnected = false, step = ConnectionStep.READING_DEVICE) }
            val clientId = ownClientId()
            watchedNetwork = candidate.network
            networkWatch = CameraWifiConnector.watch(application, candidate.network) { cameraNetworkLost(candidate.network) }
            val session = LumixCameraSession.connect(candidate.network, candidate.device.descriptionUri.host, clientId,
                descriptionUri = candidate.device.descriptionUri, networkInterfaceName = candidate.networkInterfaceName,
                compactLegacyIdentity = candidate.device.model.equals("DC-S5", ignoreCase = true)) { step ->
                cameraMutable.update { it.copy(step = step) }
            }
            cameraSession = session
            if (!registrationOnly && !session.accessMode.incoming) startBrowsingHeartbeat(session)
            check(expectedDevice == null || matchesExpected(session.device, expectedDevice)) { "相机身份已变化，未继续旧接收队列" }
            check(expectedMode == null || session.accessMode == expectedMode) {
                "相机 HTTP 发送模式与蓝牙请求不一致，请重新发起发送连接"
            }
            if (registrationOnly) {
                check(session.accessMode == CameraAccessMode.BROWSING) { "相机当前不是首次配对授权模式，请结束发送后重试" }
                cameraMutable.update { it.copy(device = session.device, bluetoothPairingCompleted = true,
                    connected = false, notice = "首次配对已完成，正在建立照片浏览连接。") }
                clearCameraSession() // No playmode, RAW setting, browse, thumbnails or file download in registration.
                val registrationNetwork = wifiConnection
                wifiConnection = null // Ignore loss callbacks from our intentionally released request.
                registrationNetwork?.close()
                return
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
                bluetoothWifiBinding?.let { binding -> binding.network == candidate.network && binding.canObserve } == true,
                notice = if (it.bluetoothPairingCompleted) "首次配对完成，照片相册已就绪。" else it.notice) }
            connected = true
        } finally {
            if (restoreSelection && !connected) cameraMutable.update { it.copy(device = previous.device,
                entries = previous.entries, selectedIds = previous.selectedIds, folders = previous.folders,
                accessMode = previous.accessMode, incomingCount = previous.incomingCount) }
        }
    }

    private fun cameraNetworkLost(network: Network) {
        if (network != watchedNetwork && network != wifiConnection?.network) return
        CameraConnectionLog.warn("LFMCamera", "selectedNetwork=lost")
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
        browsingHeartbeat?.cancel()
        browsingHeartbeat = null
        invalidateThumbnails()
        watchedNetwork = null
        networkWatch?.close()
        networkWatch = null
        cameraSession?.close()
        cameraSession = null
        cameraMutable.update { it.copy(connected = false, step = null, bluetoothCanFollowIncoming = false) }
    }

    private fun startBrowsingHeartbeat(session: LumixCameraSession) {
        browsingHeartbeat?.cancel()
        browsingHeartbeat = scope.launch {
            CameraConnectionLog.info("LFMCamera", "browseKeepAlive=started intervalMs=1000")
            var failures = 0
            var successes = 0
            while (isActive && cameraSession === session) {
                try {
                    // This lane runs beside thumbnails, SOAP and streaming downloads on the selected Network.
                    val responded = withTimeoutOrNull(5_000) { session.keepBrowsingAlive(); true } == true
                    if (!responded) throw java.io.IOException("相机保活响应超时")
                    failures = 0
                    successes++
                    if (successes == 1 || successes % 30 == 0)
                        CameraConnectionLog.info("LFMCamera", "browseKeepAlive=ok count=$successes")
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    failures++
                    logOperationFailure("keep-alive-failed", error)
                    if ((error as? CameraProtocolException)?.requiresReconnect == true || failures >= 3) {
                        expireCameraSession(session, "相机会话已失效，请重新连接后继续；已选照片和传输记录保留。")
                        return@launch
                    }
                }
                delay(1_000)
            }
        }
    }

    private fun expireCameraSession(session: LumixCameraSession, message: String) {
        if (cameraSession !== session) return
        CameraConnectionLog.warn("LFMCamera", "session=expired action=reconnect-required")
        stopTransfer(false, message)
        clearCameraSession()
        cameraMutable.update { it.copy(connected = false, disconnected = true, step = null,
            problem = message, notice = null) }
    }

    private fun invalidateThumbnails() {
        thumbnails.invalidate()
        thumbnailVersion++
        cameraMutable.update { it.copy(thumbnailVersion = thumbnailVersion) }
    }

    suspend fun cameraThumbnail(entry: CameraMedia, version: Long): android.graphics.Bitmap? {
        val snapshot = cameraMutable.value
        val session = cameraSession ?: throw CancellationException("缩略图会话已结束")
        if (!snapshot.connected || snapshot.busy || snapshot.accessMode.incoming || visibleClients == 0 ||
            snapshot.thumbnailVersion != version || entry !in snapshot.entries)
            throw CancellationException("缩略图加载暂不可用")
        val resources = entry.thumbnailResources
        if (resources.isEmpty()) return null
        val bitmap = try { thumbnails.load(session, resources, CameraThumbnailLoader.Key(version, entry)) }
        catch (error: CameraProtocolException) {
            if (error.requiresReconnect) expireCameraSession(session, "相机授权已失效，请重新连接后继续浏览和传入。")
            return null
        }
        val current = cameraMutable.value
        if (cameraSession !== session || !current.connected || current.busy || visibleClients == 0 ||
            current.thumbnailVersion != version || entry !in current.entries)
            throw CancellationException("缩略图所属目录已变化")
        return bitmap
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
        if (!cameraMutable.value.connected || cameraMutable.value.busy || cameraMutable.value.accessMode.incoming ||
            cameraMutable.value.entries.none { it.id == id && !it.isFolder && it.transferResource != null }) return
        cameraMutable.update { it.copy(selectedIds = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id) }
    }

    fun selectLoadedCameraPhotos(select: Boolean) {
        val state = cameraMutable.value
        if (!state.connected || state.busy || state.accessMode.incoming) return
        val ids = state.entries.filter { !it.isFolder && it.transferResource != null }.map { it.id }.toSet()
        cameraMutable.update { it.copy(selectedIds = if (select) it.selectedIds + ids else emptySet()) }
    }

    fun importCameraPhotos() = cameraOperation {
        check(cameraMutable.value.connected) { "连接已断开，请重新连接后传入" }
        val session = cameraSession ?: error("请先连接相机")
        check(!session.accessMode.incoming) { "此模式请使用待发送接收入口" }
        val photos = cameraMutable.value.entries.filter { it.id in cameraMutable.value.selectedIds && it.transferResource != null }
        check(photos.isNotEmpty()) { "请先选择要传入的照片或视频" }
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
        thumbnails.cancelRequests()
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
                logOperationFailure("transfer-failed", error)
                withContext(NonCancellable) { store.stopBatch(batchId, false, error.message ?: "接收已暂停") }
                if (error is CameraSendingReconnectionRequired && binding != null && session != null)
                    rememberSendingReconnect(binding.camera, session.device, error.request, binding.routerSsid)
                if (error is CameraProtocolException && error.requiresReconnect && session != null)
                    expireCameraSession(session, "相机授权已失效，传输已暂停。重新连接后可继续。")
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
            invalidateThumbnails()
            cameraMutable.update { it.copy(folders = folders, entries = emptyList(), selectedIds = emptySet(),
                nextOffset = null, total = 0, updateId = null, incomingCount = 0) }
            return
        }
        val page = if (session.accessMode.incoming) session.browse(folders.last().id, offset)
            else session.browseNewest(folders.last().id, if (append) offset else null)
        if (append && (snapshot.updateId != page.updateId || snapshot.total != page.totalMatches))
            error("相机照片列表已更新，请刷新后重新选择")
        if (!append) invalidateThumbnails()
        cameraMutable.update { it.copy(folders = folders,
            entries = (if (append) it.entries + page.entries else page.entries).distinctBy { entry -> entry.id },
            selectedIds = if (append) it.selectedIds else emptySet(), nextOffset = page.nextOffset,
            total = page.totalMatches, updateId = page.updateId, incomingCount = incomingCount) }
    }

    private fun cameraOperation(cache: Boolean = false, block: suspend () -> Unit) {
        if (cameraMutable.value.busy) return
        CameraConnectionLog.info("LFMCamera", "operation=started cache=$cache")
        thumbnails.cancelRequests()
        cameraMutable.update { it.copy(operationRunning = true, cacheBusy = cache, bluetoothStep = null) }
        cameraMutable.update { it.copy(problem = null, notice = null, transferTotal = 0, transferred = 0, progress = null, transferPhase = null) }
        cameraJob = scope.launch {
            try {
                block()
                CameraConnectionLog.info("LFMCamera", "operation=completed mediaReady=${cameraMutable.value.connected} " +
                    "pairingApproved=${cameraMutable.value.bluetoothPairingCompleted}")
            }
            catch (error: CancellationException) {
                logOperationFailure("cancelled", error)
                cameraMutable.update { if (it.disconnected) it else it.copy(problem = error.message ?: "操作已取消，已传入的照片保留") }
                if (!cameraMutable.value.connected) { clearCameraSession(); wifiConnection?.close(); wifiConnection = null }
            } catch (error: Exception) {
                logOperationFailure("failed", error)
                if (error is CameraProtocolException && error.requiresReconnect) cameraSession?.let {
                    expireCameraSession(it, "相机授权已失效，请重新连接后继续；已选照片和传输记录保留。")
                }
                cameraMutable.update { if (it.disconnected) it else it.copy(problem = error.message ?: "相机连接或传输失败") }
                if (!cameraMutable.value.connected) { clearCameraSession(); wifiConnection?.close(); wifiConnection = null }
            } finally {
                cameraMutable.update { it.copy(operationRunning = false, cacheBusy = false, bluetoothStep = null,
                    thumbnailLoadVersion = it.thumbnailLoadVersion + 1) }
                releaseIfIdle()
            }
        }
    }

    private fun logOperationFailure(result: String, error: Exception) {
        val state = cameraMutable.value
        val status = (error as? CameraProtocolException)?.status?.removePrefix("下载 ")?.takeIf {
            it.matches(Regex("(?:err_[a-z0-9_]{1,48}|HTTP [0-9]{3})"))
        }.orEmpty()
        val origin = error.stackTrace.firstOrNull { it.className.startsWith("com.lumixfilmmirror.") }
            ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }.orEmpty()
        // Exception text can contain URLs, query tokens, local file paths or camera names.
        CameraConnectionLog.warn("LFMCamera", "operation=$result type=${error.javaClass.simpleName} " +
            "status=$status at=$origin bt=${state.bluetoothStep} wifiHttp=${state.step} pairingApproved=${state.bluetoothPairingCompleted}")
    }

    fun cancelCameraOperation() {
        CameraConnectionLog.info("LFMCamera", "userAction=cancel")
        if (cameraMutable.value.activeTransferBatch != null)
            stopTransfer(true, "已取消后续传输，已导入照片保留")
        else { cameraJob?.cancel(); cameraSession?.interrupt() }
    }
    fun disconnectCamera() {
        if (cameraMutable.value.busy) return
        CameraConnectionLog.info("LFMCamera", "userAction=disconnect")
        clearSendingReconnect()
        clearBluetoothCredentials()
        bluetoothWifiBinding = null
        clearCameraSession()
        wifiConnection?.close()
        wifiConnection = null
        cameraMutable.value = CameraWorkspaceState()
    }

}
