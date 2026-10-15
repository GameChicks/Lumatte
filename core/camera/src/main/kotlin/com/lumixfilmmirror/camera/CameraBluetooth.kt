package com.lumixfilmmirror.camera

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select

data class BluetoothCamera(val address: String, val name: String, val signal: Int, val pairing: Boolean)
enum class BluetoothConnectionStep { SCANNING, CONNECTING, READING_SERVICES, REGISTERING_NOTIFICATIONS, IDENTIFYING, READING_WIFI, WAITING_FOR_SEND, STARTING_WIFI }

/** Deliberately not a data class: generated toString must never reveal a Wi-Fi password. */
class CameraWifiCredentials(val ssid: String, private var secret: String?, val canStartTransferWifi: Boolean,
    val canObserveIncoming: Boolean, val canStartRouterWifi: Boolean) : Closeable {
    fun password(): String = secret ?: error("连接资料已释放，请重新从相机读取")
    override fun close() { secret = null }
    override fun toString(): String = "CameraWifiCredentials(redacted)"
}

/** User-initiated BLE operations; scoped GATT closes when reads or foreground-service observation ends. */
class CameraBluetooth(private val context: Context) {
    private fun adapter(scanning: Boolean = false): BluetoothAdapter {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            if (scanning) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION) else listOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else if (scanning) listOf(Manifest.permission.ACCESS_FINE_LOCATION) else emptyList()
        CameraConnectionLog.info("LFMBle", "permissionCheck scanning=$scanning requiredGranted=" +
            permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } +
            " missing=" + permissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                .map { it.substringAfterLast('.') })
        check(permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            if (scanning) "查找蓝牙相机需要附近设备和精确定位权限；定位权限用于兼容扫描，不读取手机位置"
            else "连接蓝牙相机需要附近设备权限"
        }
        val locationEnabled = context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
        CameraConnectionLog.info("LFMBle", "systemLocationEnabled=$locationEnabled")
        if (scanning) check(locationEnabled) {
            "蓝牙兼容扫描需要开启系统定位后再查找相机；应用不会读取手机位置"
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("此设备没有蓝牙功能")
        CameraConnectionLog.info("LFMBle", "adapterEnabled=${adapter.isEnabled}")
        check(adapter.isEnabled) { "请先在系统设置中开启蓝牙" }
        return adapter
    }

    @SuppressLint("MissingPermission")
    suspend fun scan(onReport: (BluetoothScanReport) -> Unit = {}, stopRequested: Deferred<Unit>? = null,
        onFound: (List<BluetoothCamera>) -> Unit): List<BluetoothCamera> = coroutineScope {
        val scanner = adapter(scanning = true).bluetoothLeScanner ?: error("蓝牙暂时无法扫描，请重新开启蓝牙")
        val events = Channel<BluetoothCamera>(64)
        val scanning = AtomicBoolean(true)
        val advertisements = AtomicInteger()
        val panasonicAdvertisements = AtomicInteger()
        val emptyPanasonicPayloads = AtomicInteger()
        val observedStates = ConcurrentHashMap.newKeySet<Int>()
        fun report() = BluetoothScanReport(advertisements.get(), panasonicAdvertisements.get(),
            emptyPanasonicPayloads.get(), observedStates.toSet())
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { accept(result) }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::accept) }
            private fun accept(result: ScanResult) {
                if (!scanning.get()) return
                advertisements.incrementAndGet()
                val record = result.scanRecord ?: return
                // Android omits the two company-ID bytes from this manufacturer payload.
                val payload = record.getManufacturerSpecificData(0x003a) ?: return
                panasonicAdvertisements.incrementAndGet()
                // Discovery reads only the state; the camera identity is verified via GATT.
                // The reference scanner does not require the six identity bytes to be present.
                if (payload.isEmpty()) { emptyPanasonicPayloads.incrementAndGet(); return }
                val state = payload[0].toInt() and 255
                observedStates += state
                if (state != 0 && state != 3) return // Never wake or power on a sleeping camera.
                try {
                    events.trySend(BluetoothCamera(result.device.address,
                        record.deviceName?.takeIf { it.isNotBlank() } ?: "LUMIX 蓝牙相机", result.rssi, state == 3))
                } catch (_: SecurityException) {
                    events.close(IllegalStateException("蓝牙权限已被撤回，请重新授权后查找相机"))
                }
            }
            override fun onScanFailed(errorCode: Int) {
                CameraConnectionLog.warn("LFMBle", "scan=failed code=$errorCode")
                events.close(IllegalStateException("蓝牙扫描未能开始（$errorCode），请稍后重试"))
            }
        }
        val cameras = linkedMapOf<String, BluetoothCamera>()
        try {
            CameraConnectionLog.info("LFMBle", "scan=started timeoutMs=20000")
            // Match the reference's unfiltered foreground discovery; filter Panasonic in software.
            // Do not depend on chipset offload support for a zero-length manufacturer filter.
            scanner.startScan(null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            withTimeoutOrNull(20_000L) {
                var stopped = false
                while (!stopped) select<Unit> {
                    if (stopRequested != null) stopRequested.onAwait { stopped = true }
                    events.onReceive { camera ->
                        cameras[camera.address] = camera
                        onFound(cameras.values.sortedByDescending { it.signal })
                        onReport(report())
                    }
                }
            }
            cameras.values.sortedByDescending { it.signal }
        } finally {
            scanning.set(false)
            runCatching { scanner.stopScan(callback) }
            events.close()
            onReport(report())
            val summary = report()
            CameraConnectionLog.info("LFMBle", "scan=finished found=${cameras.size} advertisements=${summary.advertisements} " +
                "panasonic=${summary.panasonicAdvertisements} empty=${summary.emptyPanasonicPayloads} states=${summary.observedStates.sorted()}")
        }
    }

    suspend fun readWifi(camera: BluetoothCamera, clientId: String,
        onStep: (BluetoothConnectionStep) -> Unit): CameraWifiCredentials = withConnection(camera, onStep) { session ->
        identifyAndRead(session, clientId, onStep)
    }

    /** Existing router credentials belong to the camera. Only its SSID is sent. */
    suspend fun <T> withRouterWifi(camera: BluetoothCamera, clientId: String, routerSsid: CameraRouterSsid,
        onStep: (BluetoothConnectionStep) -> Unit, block: suspend () -> T): T {
        val bytes = LumixBleProtocol.routerSsid(routerSsid)
        try {
            return withConnection(camera, onStep) { session ->
                identifyAndRead(session, clientId, onStep).close()
                check(session.canStartRouterWifi()) { "此相机缺少路由器连接能力，请在相机上手动连接后查找" }
                onStep(BluetoothConnectionStep.STARTING_WIFI)
                session.startRouterWifi(bytes)
                session.handoff(block)
            }
        } finally { bytes.fill(0) }
    }

    /** Sending mode is obtained from a current camera event or its captured reconnect request. */
    suspend fun <T> withRouterSendingWifi(camera: BluetoothCamera, clientId: String, routerSsid: CameraRouterSsid,
        onStep: (BluetoothConnectionStep) -> Unit, reconnect: CameraSendingReconnectRequest? = null,
        block: suspend (CameraWifiCredentials, CameraAccessMode, CameraConnectionAddress?) -> T): T {
        val bytes = LumixBleProtocol.routerSsid(routerSsid)
        try {
            return withConnection(camera, onStep) { session ->
                val credentials = identifyAndRead(session, clientId, onStep)
                try {
                    check(credentials.canStartRouterWifi && credentials.canObserveIncoming) {
                        "此相机缺少路由器发送能力，请使用直连或手动连接入口"
                    }
                    credentials.close() // STA uses no camera access-point password.
                    val mode = session.startSendingWifi(onStep, reconnect, bytes)
                    val address = session.readSendingAddress()
                    session.sendingHandoff { block(credentials, mode, address) }
                } finally { credentials.close() }
            }
        } finally { bytes.fill(0) }
    }

    /** An already established sending Wi-Fi session only. No mode or Wi-Fi startup writes. */
    suspend fun <T> withIncomingEvents(camera: BluetoothCamera, clientId: String, expectedSsid: String,
        mode: CameraAccessMode, onStep: (BluetoothConnectionStep) -> Unit,
        block: suspend (IncomingCameraEvents) -> T): T = withConnection(camera, onStep) { session ->
        require(mode.incoming)
        val credentials = identifyAndRead(session, clientId, onStep)
        try {
            check(credentials.ssid == expectedSsid) { "蓝牙相机网络名称已变化，请重新连接" }
            check(credentials.canObserveIncoming) { "此相机缺少已识别的发送事件通知，请使用单轮接收" }
        } finally { credentials.close() }
        session.withIncomingEvents(mode, block)
    }

    /** Pair/connect is one operation. A first connection has no previously read SSID. */
    suspend fun <T> withTransferWifi(camera: BluetoothCamera, clientId: String, expectedSsid: String?,
        onStep: (BluetoothConnectionStep) -> Unit, block: suspend (CameraWifiCredentials) -> T): T =
        withConnection(camera, onStep) { session ->
            val credentials = identifyAndRead(session, clientId, onStep)
            try {
                check(expectedSsid == null || credentials.ssid == expectedSsid) { "相机网络名称已变化，请重新读取连接资料后选择" }
                check(credentials.canStartTransferWifi) { "此相机的蓝牙启动流程暂不支持，请在相机上开启 Wi-Fi 后连接" }
                onStep(BluetoothConnectionStep.STARTING_WIFI)
                session.startTransferWifi(initialPairing = camera.pairing)
                session.handoff { block(credentials) }
            } finally { credentials.close() }
        }

    /** The camera requests its sending mode. The caller cannot select an arbitrary mode byte. */
    suspend fun <T> withSendingWifi(camera: BluetoothCamera, clientId: String, expectedSsid: String,
        onStep: (BluetoothConnectionStep) -> Unit,
        block: suspend (CameraWifiCredentials, CameraAccessMode, CameraConnectionAddress?) -> T): T = withConnection(camera, onStep) { session ->
        val credentials = identifyAndRead(session, clientId, onStep)
        try {
            check(credentials.ssid == expectedSsid) { "相机网络名称已变化，请重新读取连接资料" }
            check(credentials.canStartTransferWifi && credentials.canObserveIncoming) {
                "此相机缺少已识别的发送启动能力，请在相机上开启 Wi-Fi 后连接"
            }
            val mode = session.startSendingWifi(onStep)
            val address = session.readSendingAddress()
            session.sendingHandoff { block(credentials, mode, address) }
        } finally { credentials.close() }
    }

    /** Explicit rehandshake after a recognized event. Re-read credentials; never reuse a password. */
    suspend fun <T> withSendingReconnectWifi(camera: BluetoothCamera, clientId: String,
        request: CameraSendingReconnectRequest, onStep: (BluetoothConnectionStep) -> Unit,
        block: suspend (CameraWifiCredentials, CameraAccessMode, CameraConnectionAddress?) -> T): T = withConnection(camera, onStep) { session ->
        val credentials = identifyAndRead(session, clientId, onStep)
        try {
            check(credentials.canStartTransferWifi && credentials.canObserveIncoming) {
                "此相机缺少发送启动能力，请使用手动 Wi-Fi 入口"
            }
            val mode = session.startSendingWifi(onStep, request)
            val address = session.readSendingAddress()
            session.sendingHandoff { block(credentials, mode, address) }
        } finally { credentials.close() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun <T> withConnection(camera: BluetoothCamera, onStep: (BluetoothConnectionStep) -> Unit,
        block: suspend (ConnectionGatt) -> T): T {
        val device = adapter().getRemoteDevice(camera.address)
        val session = ConnectionGatt(context, device)
        try {
            onStep(BluetoothConnectionStep.CONNECTING)
            session.connect()
            onStep(BluetoothConnectionStep.READING_SERVICES)
            session.discover()
            session.logConnectionCapabilities()
            return block(session)
        } finally { session.close() }
    }

    private suspend fun identifyAndRead(session: ConnectionGatt, clientId: String,
        onStep: (BluetoothConnectionStep) -> Unit): CameraWifiCredentials {
        val protocol = LumixBleProtocol
        onStep(BluetoothConnectionStep.REGISTERING_NOTIFICATIONS)
        session.prepareConnectionNotifications()
        val aps = session.has(protocol.apsClientId)
        CameraConnectionLog.info("LFMBle", "connectionFlow=s5-pair-v2 identityProtocol=${if (aps) "aps" else "legacy"}")
        onStep(BluetoothConnectionStep.IDENTIFYING)
        val identity = protocol.identity(clientId)
        var legacyChallenge = false
        val challenge = try {
            if (aps) {
                val challenge = protocol.challenge(session.read(protocol.challenge))
                session.write(protocol.apsClientId, protocol.encode(identity, challenge))
                session.write(protocol.apsFriendlyName, protocol.encode(protocol.appName, challenge))
                challenge
            } else {
                session.write(protocol.clientId, identity)
                session.write(protocol.friendlyName, protocol.appName)
                legacyChallenge = true
                0
            }
        } finally { identity.fill(0) }
        onStep(BluetoothConnectionStep.READING_WIFI)
        val ssid = protocol.decodeSsid(session.read(protocol.ssid))
        // Reference legacy order: identity -> name -> SSID -> challenge -> password.
        val passwordChallenge = if (legacyChallenge) protocol.challenge(session.read(protocol.challenge)) else challenge
        val passwordBytes = session.read(protocol.password)
        try { return CameraWifiCredentials(ssid, protocol.decodePassword(passwordBytes, passwordChallenge),
            session.canStartTransferWifi(), session.canObserveIncoming(), session.canStartRouterWifi()) }
        finally { passwordBytes.fill(0) }
    }
}

/** One request at a time; connection/sending notifications and two fixed ordinary startup values. */
@SuppressLint("MissingPermission")
private class ConnectionGatt(private val context: Context, private val device: BluetoothDevice) : Closeable {
    private enum class Kind { CONNECTED, SERVICES, READ, WRITE, DESCRIPTOR, MTU }
    private class Event(val kind: Kind, val status: Int, val uuid: UUID? = null, val bytes: ByteArray = byteArrayOf(),
        val mtu: Int = 0)
    private val events = Channel<Event>(16, onUndeliveredElement = { it.bytes.fill(0) })
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val startupActive = AtomicBoolean(false)
    private val failure = AtomicReference<IllegalStateException?>(null)
    private val startupRejected = CompletableDeferred<IllegalStateException>()
    private val routerStartup = AtomicBoolean(false)
    private val routerChoicePending = AtomicBoolean(false)
    private val routerAccepted = CompletableDeferred<Unit>()
    private val incomingMode = AtomicReference<CameraAccessMode?>(null)
    private val incomingEvents = IncomingCameraEvents()
    private val sendingStartupActive = AtomicBoolean(false)
    private val sendingStartupMode = AtomicReference<CameraAccessMode?>(null)
    private val sendingRequested = CompletableDeferred<CameraAccessMode>()
    private val sendingModeWriteStarted = AtomicBoolean(false)
    private val sendingNetworkReady = CompletableDeferred<Unit>()
    private var gatt: BluetoothGatt? = null
    private val subscriptions = mutableSetOf<Pair<UUID, UUID>>()
    private val callback = object : BluetoothGattCallback() {
        private fun fail(message: String) {
            CameraConnectionLog.warn("LFMBle", message)
            val problem = IllegalStateException(message)
            failure.compareAndSet(null, problem)
            if (routerStartup.get()) startupRejected.complete(problem)
            events.close(problem)
            incomingEvents.stop(problem)
        }
        private fun rejectStartup(message: String) {
            startupRejected.complete(IllegalStateException(message))
            fail(message)
        }
        private fun send(event: Event) {
            if (closed.get()) { event.bytes.fill(0); return }
            if (!events.trySend(event).isSuccess) {
                event.bytes.fill(0)
                fail("蓝牙响应过多，请重新连接")
            }
        }
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS)
                send(Event(Kind.CONNECTED, status))
            else if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED)
                fail("蓝牙相机已断开（$status），请在相机上开启配对后重试")
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) { send(Event(Kind.SERVICES, status)) }
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) { send(Event(Kind.MTU, status, mtu = mtu)) }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            send(Event(Kind.WRITE, status, characteristic.uuid))
        }
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            send(Event(Kind.DESCRIPTOR, status, descriptor.characteristic.uuid))
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            connectionReply(characteristic.uuid, value)
        }
        @Deprecated("Legacy Android callback")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) connectionReply(characteristic.uuid, characteristic.value ?: byteArrayOf())
        }
        private fun connectionReply(uuid: UUID, value: ByteArray) {
            if (closed.get()) return
            val protocol = LumixBleProtocol
            if (uuid in setOf(protocol.connectionStatus, protocol.wifiChoiceReply, protocol.imageTransferEvent, protocol.sendingModeEvent))
                CameraConnectionLog.info("LFMBle", "notification uuid=$uuid state=${value.firstOrNull()?.toInt()?.and(255)}")
            if (sendingStartupActive.get() && (uuid == protocol.imageTransferEvent || uuid == protocol.sendingModeEvent)) {
                if (value.isEmpty()) { incomingEvents.stop(IllegalStateException("相机发送事件不完整，请重新连接")); return }
                val state = value[0].toInt() and 255
                if (uuid == protocol.imageTransferEvent) {
                    if (state == 3) incomingEvents.stop(CameraSendingStopped("相机停止发送，连接已取消"))
                } else when (state) {
                    1, 4 -> {
                        val requested = if (state == 1) CameraAccessMode.AUTO_UPLOAD else CameraAccessMode.MARKED_TRANSFER
                        val previous = sendingStartupMode.get()
                        if (previous == null && sendingStartupMode.compareAndSet(null, requested)) sendingRequested.complete(requested)
                        else if (sendingStartupMode.get() != requested)
                            incomingEvents.stop(sendingReconnect(requested, CameraSendingReconnectReason.MODE_CHANGED))
                    }
                    2 -> incomingEvents.stop(CameraSendingStopped("相机结束发送，连接已取消"))
                    3 -> incomingEvents.stop(sendingStartupMode.get()?.let {
                        sendingReconnect(it, CameraSendingReconnectReason.WIFI_DETAILS_CHANGED)
                    } ?: IllegalStateException("相机请求重新读取网络资料，请重新连接"))
                }
                return
            }
            val mode = incomingMode.get()
            if (mode != null && (uuid == protocol.imageTransferEvent || uuid == protocol.sendingModeEvent)) {
                if (value.isEmpty()) { incomingEvents.stop(IllegalStateException("相机发送事件不完整，接收已暂停")); return }
                val state = value[0].toInt() and 255
                if (uuid == protocol.imageTransferEvent) when (state) {
                    1, 2 -> incomingEvents.filesReady()
                    3 -> incomingEvents.stop(CameraSendingStopped("相机停止发送，已保存结果保留"))
                } else when (state) {
                    1, 4 -> {
                        val requested = if (state == 1) CameraAccessMode.AUTO_UPLOAD else CameraAccessMode.MARKED_TRANSFER
                        if (requested == mode) incomingEvents.filesReady()
                        else incomingEvents.stop(sendingReconnect(requested, CameraSendingReconnectReason.MODE_CHANGED))
                    }
                    2 -> incomingEvents.stop(CameraSendingStopped("相机结束发送模式，已保存结果保留"))
                    3 -> incomingEvents.stop(sendingReconnect(mode, CameraSendingReconnectReason.WIFI_DETAILS_CHANGED))
                }
                return
            }
            if (!startupActive.get()) return
            if (uuid != protocol.wifiChoiceReply && uuid != protocol.connectionStatus) return
            if (value.isEmpty()) { rejectStartup("相机返回的连接状态不完整，请使用 Wi-Fi 入口"); return }
            val state = value[0].toInt() and 255
            if (uuid == protocol.connectionStatus && state == 1)
                rejectStartup("相机拒绝当前连接，请在相机上确认或使用 Wi-Fi 入口")
            if (uuid == protocol.connectionStatus && state == 0 && sendingStartupActive.get() && sendingModeWriteStarted.get())
                sendingNetworkReady.complete(Unit)
            if (uuid == protocol.wifiChoiceReply) {
                if (routerStartup.get()) {
                    if (state != 0) rejectStartup("相机未选择此路由器，请在相机上确认已保存该网络，再重新连接")
                    else if (routerChoicePending.compareAndSet(true, false)) routerAccepted.complete(Unit)
                } else if (state == 0)
                    rejectStartup("相机选择了其他 Wi-Fi 网络，请在相机上切换为直连后重试")
            }
        }
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            send(Event(Kind.READ, status, characteristic.uuid, value.copyOf()))
        }
        @Deprecated("Legacy Android callback")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) send(Event(Kind.READ, status, characteristic.uuid, characteristic.value?.copyOf() ?: byteArrayOf()))
        }
    }

    suspend fun connect() = mutex.withLock {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: error("蓝牙连接无法启动")
        await(Kind.CONNECTED, timeout = 30_000L)
        Unit
    }
    suspend fun discover() = mutex.withLock {
        check(requireGatt().discoverServices()) { "蓝牙服务读取无法启动" }
        await(Kind.SERVICES, timeout = 20_000L)
        Unit
    }
    fun has(uuid: UUID, service: UUID = LumixBleProtocol.service): Boolean = requireGatt().getService(service)?.getCharacteristic(uuid) != null
    private fun characteristic(uuid: UUID, service: UUID = LumixBleProtocol.service): BluetoothGattCharacteristic = requireGatt()
        .getService(service)?.getCharacteristic(uuid)
        ?: error("此相机缺少已识别的蓝牙连接资料，请使用 Wi-Fi 入口")
    suspend fun read(uuid: UUID, service: UUID = LumixBleProtocol.service): ByteArray = mutex.withLock {
        CameraConnectionLog.info("LFMBle", "operation=READ uuid=$uuid state=requested")
        check(requireGatt().readCharacteristic(characteristic(uuid, service))) { "蓝牙读取未被系统接受，请重试" }
        await(Kind.READ, uuid).bytes
    }
    @Suppress("DEPRECATION")
    suspend fun write(uuid: UUID, bytes: ByteArray, service: UUID = LumixBleProtocol.service) = mutex.withLock {
        val characteristic = characteristic(uuid, service)
        val writeType = when {
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> error("相机连接特征不可写，请重新读取连接资料")
        }
        CameraConnectionLog.info("LFMBle", "operation=WRITE uuid=$uuid state=requested writeType=$writeType")
        val accepted = if (Build.VERSION.SDK_INT >= 33)
            requireGatt().writeCharacteristic(characteristic, bytes, writeType).let { status ->
                CameraConnectionLog.info("LFMBle", "operation=WRITE uuid=$uuid androidStatus=$status")
                status == BluetoothStatusCodes.SUCCESS
            }
        else {
            characteristic.writeType = writeType
            characteristic.value = bytes.copyOf()
            requireGatt().writeCharacteristic(characteristic)
        }
        check(accepted) { "蓝牙连接资料未被系统接受，请重试" }
        await(Kind.WRITE, uuid)
        Unit
    }

    fun canStartTransferWifi(): Boolean {
        val protocol = LumixBleProtocol
        // Starting SoftAP requires the two writes. Optional status subscriptions
        // must not prevent those writes: Wi-Fi + HTTP approval confirm success.
        return hasWritable(protocol.wifiConnectionType, protocol.wifiService) &&
            hasWritable(protocol.connectionMode, protocol.service)
    }
    fun canObserveIncoming(): Boolean {
        val protocol = LumixBleProtocol
        return hasNotification(protocol.imageTransferEvent, protocol.sendingService) &&
            hasNotification(protocol.sendingModeEvent, protocol.service)
    }
    fun canStartRouterWifi(): Boolean = canStartTransferWifi() &&
        hasWritable(LumixBleProtocol.routerSsid, LumixBleProtocol.wifiService) &&
        hasNotification(LumixBleProtocol.wifiChoiceReply, LumixBleProtocol.wifiService)
    private fun hasWritable(uuid: UUID, service: UUID): Boolean = has(uuid, service) &&
        characteristic(uuid, service).properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    private fun hasNotification(uuid: UUID, service: UUID): Boolean {
        if (!has(uuid, service)) return false
        val characteristic = characteristic(uuid, service)
        // Some Panasonic revisions provide a writable notification descriptor
        // without the NOTIFY property bit. Match the reference's descriptor check.
        return descriptor(characteristic) != null
    }
    private fun descriptor(characteristic: BluetoothGattCharacteristic): BluetoothGattDescriptor? =
        characteristic.getDescriptor(characteristic.uuid) ?: characteristic.getDescriptor(LumixBleProtocol.cccd)
    fun logConnectionCapabilities() {
        val protocol = LumixBleProtocol
        listOf(protocol.service to protocol.connectionMode, protocol.service to protocol.connectionStatus,
            protocol.wifiService to protocol.wifiConnectionType, protocol.wifiService to protocol.wifiChoiceReply)
            .forEach { (service, uuid) ->
                val value = requireGatt().getService(service)?.getCharacteristic(uuid)
                CameraConnectionLog.info("LFMBle", "capability uuid=$uuid present=${value != null} " +
                    "properties=${value?.properties ?: 0} descriptor=${value?.let { descriptor(it) != null } ?: false}")
            }
        CameraConnectionLog.info("LFMBle", "capability transferStartup=${canStartTransferWifi()} " +
            "incoming=${canObserveIncoming()} router=${canStartRouterWifi()}")
    }
    suspend fun prepareConnectionNotifications() {
        val protocol = LumixBleProtocol
        if (hasNotification(protocol.connectionStatus, protocol.service)) subscribe(protocol.connectionStatus, protocol.service)
        if (hasNotification(protocol.wifiChoiceReply, protocol.wifiService)) subscribe(protocol.wifiChoiceReply, protocol.wifiService)
    }
    @Suppress("DEPRECATION")
    private suspend fun subscribe(uuid: UUID, service: UUID) = mutex.withLock {
        if ((service to uuid) in subscriptions) return@withLock
        val characteristic = characteristic(uuid, service)
        val descriptor = descriptor(characteristic) ?: error("相机连接通知暂不支持，请在相机上开启 Wi-Fi 后连接")
        check(requireGatt().setCharacteristicNotification(characteristic, true)) { "无法启用相机连接通知" }
        val indicateOnly = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 &&
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
        val value = if (indicateOnly) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        CameraConnectionLog.info("LFMBle", "operation=DESCRIPTOR uuid=$uuid state=requested")
        val accepted = if (Build.VERSION.SDK_INT >= 33)
            requireGatt().writeDescriptor(descriptor, value).let { status ->
                CameraConnectionLog.info("LFMBle", "operation=DESCRIPTOR uuid=$uuid androidStatus=$status")
                status == BluetoothStatusCodes.SUCCESS
            }
        else { descriptor.value = value.copyOf(); requireGatt().writeDescriptor(descriptor) }
        check(accepted) { "连接通知未被系统接受，请使用 Wi-Fi 入口" }
        await(Kind.DESCRIPTOR, uuid)
        subscriptions += service to uuid
        Unit
    }
    suspend fun startTransferWifi(initialPairing: Boolean) {
        val protocol = LumixBleProtocol
        prepareConnectionNotifications()
        startupActive.set(true)
        // The pairing advertisement selects registration mode 5; ordinary reconnect uses 3.
        // Official C0393g/C0272a and the user's successful S5 trace agree on this distinction.
        // Both start a photo-transfer network, not shutter/live-view or remote power commands.
        CameraConnectionLog.info("LFMBle", "wifiStartup=${if (initialPairing) "first-pairing" else "reconnect"} " +
            "transport=softap wifiType=1 mode=${if (initialPairing) 5 else 3}")
        write(protocol.wifiConnectionType, byteArrayOf(1), protocol.wifiService)
        write(protocol.connectionMode, byteArrayOf(if (initialPairing) 5 else 3))
    }
    suspend fun startRouterWifi(ssid: ByteArray) {
        check(canStartRouterWifi() && ssid.size in 32..64)
        prepareRouterMtu(ssid.size)
        val protocol = LumixBleProtocol
        routerStartup.set(true)
        startupActive.set(true)
        subscribe(protocol.wifiChoiceReply, protocol.wifiService)
        subscribe(protocol.connectionStatus, protocol.service)
        requestRouterChoice(ssid)
        write(protocol.wifiConnectionType, byteArrayOf(2), protocol.wifiService)
        write(protocol.connectionMode, byteArrayOf(3))
    }
    private suspend fun prepareRouterMtu(payloadSize: Int) {
        // The recovered character-padding rule can make UTF-8 payloads exceed 32 bytes.
        require(payloadSize in 32..64)
        mutex.withLock {
            check(requireGatt().requestMtu(maxOf(64, payloadSize + 3))) { "无法协商路由器连接资料长度，请使用手动连接入口" }
            check(await(Kind.MTU).mtu >= payloadSize + 3) { "相机蓝牙资料长度不足，请在相机上手动连接路由器" }
        }
    }
    private suspend fun requestRouterChoice(ssid: ByteArray) {
        val protocol = LumixBleProtocol
        routerChoicePending.set(true)
        write(protocol.routerSsid, ssid, protocol.wifiService)
        try {
            withTimeout(20_000L) {
                select<Unit> {
                    if (sendingStartupActive.get()) incomingEvents.ended.onAwait { throw it }
                    startupRejected.onAwait { throw it }
                    routerAccepted.onAwait { }
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            error("相机未确认路由器，请检查相机保存的网络名称和密码后重试")
        }
    }
    suspend fun startSendingWifi(onStep: (BluetoothConnectionStep) -> Unit,
        reconnect: CameraSendingReconnectRequest? = null, routerSsid: ByteArray? = null): CameraAccessMode {
        check(canStartTransferWifi() && canObserveIncoming()) { "相机发送启动暂不支持，请使用 Wi-Fi 入口" }
        if (routerSsid != null) {
            check(canStartRouterWifi() && routerSsid.size in 32..64) { "此相机的路由器发送启动暂不支持" }
            prepareRouterMtu(routerSsid.size)
            routerStartup.set(true)
        }
        val protocol = LumixBleProtocol
        startupActive.set(true)
        sendingStartupActive.set(true)
        subscribe(protocol.wifiChoiceReply, protocol.wifiService)
        subscribe(protocol.connectionStatus, protocol.service)
        subscribe(protocol.imageTransferEvent, protocol.sendingService)
        subscribe(protocol.sendingModeEvent, protocol.service)
        if (reconnect != null) {
            if (sendingStartupMode.compareAndSet(null, reconnect.mode)) sendingRequested.complete(reconnect.mode)
            else if (sendingStartupMode.get() != reconnect.mode) incomingEvents.stop(sendingReconnect(
                requireNotNull(sendingStartupMode.get()), CameraSendingReconnectReason.MODE_CHANGED))
        } else onStep(BluetoothConnectionStep.WAITING_FOR_SEND)
        val mode = try {
            withTimeout(90_000L) {
                select<CameraAccessMode> {
                    incomingEvents.ended.onAwait { throw it }
                    sendingRequested.onAwait { it }
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            error("未收到相机发送请求，请重新发起并在相机上开启拍后发送或选定文件发送")
        }
        ensureSendingActive()
        onStep(BluetoothConnectionStep.STARTING_WIFI)
        if (routerSsid != null) {
            requestRouterChoice(routerSsid)
            ensureSendingActive()
            write(protocol.wifiConnectionType, byteArrayOf(2), protocol.wifiService)
        } else write(protocol.wifiConnectionType, byteArrayOf(1), protocol.wifiService)
        ensureSendingActive()
        val command = when (mode) {
            CameraAccessMode.AUTO_UPLOAD -> byteArrayOf(1)
            CameraAccessMode.MARKED_TRANSFER -> byteArrayOf(6)
            CameraAccessMode.BROWSING -> error("相机没有请求发送模式")
        }
        sendingModeWriteStarted.set(true)
        write(protocol.connectionMode, command)
        ensureSendingActive()
        return mode
    }
    /** Optional enhancement: absent characteristics or absent positive state keep SSDP available. */
    suspend fun readSendingAddress(): CameraConnectionAddress? {
        val protocol = LumixBleProtocol
        ensureSendingActive()
        if (!has(protocol.connectionAddress, protocol.sendingService) ||
            characteristic(protocol.connectionAddress, protocol.sendingService).properties and BluetoothGattCharacteristic.PROPERTY_READ == 0)
            return null
        val ready = withTimeoutOrNull(8_000L) {
            select<Boolean> {
                incomingEvents.ended.onAwait { throw it }
                startupRejected.onAwait { throw it }
                sendingNetworkReady.onAwait { true }
            }
        }
        ensureSendingActive()
        if (ready != true) return null
        val bytes = read(protocol.connectionAddress, protocol.sendingService)
        try { return protocol.decodeConnectionAddress(bytes) }
        finally { bytes.fill(0) }
    }
    private suspend fun ensureSendingActive() {
        currentCoroutineContext().ensureActive()
        if (incomingEvents.ended.isCompleted) throw incomingEvents.ended.await()
    }
    suspend fun <T> sendingHandoff(block: suspend () -> T): T = coroutineScope {
        ensureSendingActive()
        val handoff = async { block() }
        select {
            incomingEvents.ended.onAwait { throw it }
            startupRejected.onAwait { throw it }
            handoff.onAwait { it }
        }
    }
    suspend fun <T> handoff(block: suspend () -> T): T = coroutineScope {
        val handoff = async { block() }
        select {
            startupRejected.onAwait { throw it }
            handoff.onAwait { it }
        }
    }
    suspend fun <T> withIncomingEvents(mode: CameraAccessMode, block: suspend (IncomingCameraEvents) -> T): T {
        check(canObserveIncoming()) { "相机发送事件暂不支持，请使用单轮接收" }
        incomingMode.set(mode)
        val protocol = LumixBleProtocol
        subscribe(protocol.imageTransferEvent, protocol.sendingService)
        subscribe(protocol.sendingModeEvent, protocol.service)
        return coroutineScope {
            val receiving = async { block(incomingEvents) }
            select {
                incomingEvents.ended.onAwait { throw it }
                receiving.onAwait { it }
            }
        }
    }

    private fun requireGatt(): BluetoothGatt {
        failure.get()?.let { throw it }
        return gatt ?: error("蓝牙会话已经结束")
    }
    private suspend fun await(kind: Kind, uuid: UUID? = null, timeout: Long = 20_000L): Event {
        val operation = when (uuid) {
            LumixBleProtocol.clientId, LumixBleProtocol.apsClientId -> "登记手机标识"
            LumixBleProtocol.friendlyName, LumixBleProtocol.apsFriendlyName -> "登记手机名称"
            LumixBleProtocol.ssid -> "读取相机网络名称"
            LumixBleProtocol.challenge -> "读取相机认证资料"
            LumixBleProtocol.password -> "读取相机网络资料"
            else -> when (kind) {
                Kind.CONNECTED -> "建立蓝牙连接"
                Kind.SERVICES -> "读取蓝牙服务"
                Kind.DESCRIPTOR -> "启用相机连接通知"
                else -> "蓝牙连接资料操作"
            }
        }
        try {
            return withTimeout(timeout) {
                while (true) {
                    failure.get()?.let { throw it }
                    val event = if (incomingMode.get() != null || sendingStartupActive.get()) select<Event> {
                        incomingEvents.ended.onAwait { throw it }
                        events.onReceive { it }
                    } else events.receive()
                    failure.get()?.let { event.bytes.fill(0); throw it }
                    if (event.kind != kind || event.uuid != uuid) { event.bytes.fill(0); continue }
                    if (event.status != BluetoothGatt.GATT_SUCCESS) {
                        event.bytes.fill(0)
                        CameraConnectionLog.warn("LFMBle", "operation=$kind uuid=$uuid status=${event.status}")
                        error("$operation 失败（状态 ${event.status}），请重新进入相机配对页面后重试")
                    }
                    // Protocol UUIDs only: never log characteristic values, device IDs or addresses.
                    CameraConnectionLog.info("LFMBle", "operation=$kind uuid=$uuid status=success" +
                        if (kind == Kind.READ) " bytes=${event.bytes.size}" else "")
                    return@withTimeout event
                }
                @Suppress("UNREACHABLE_CODE") error("蓝牙响应未到达")
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            CameraConnectionLog.warn("LFMBle", "operation=$kind uuid=$uuid timeoutMs=$timeout")
            error("$operation 超时，请重新进入相机配对页面后重试")
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        incomingEvents.close()
        events.close()
        while (true) (events.tryReceive().getOrNull() ?: break).bytes.fill(0)
    }
}
