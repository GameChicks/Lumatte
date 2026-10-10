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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select

data class BluetoothCamera(val address: String, val name: String, val signal: Int, val pairing: Boolean)
enum class BluetoothConnectionStep { SCANNING, CONNECTING, READING_SERVICES, IDENTIFYING, READING_WIFI, WAITING_FOR_SEND, STARTING_WIFI }

/** Deliberately not a data class: generated toString must never reveal a Wi-Fi password. */
class CameraWifiCredentials(val ssid: String, private var secret: String?, val canStartTransferWifi: Boolean,
    val canObserveIncoming: Boolean) : Closeable {
    fun password(): String = secret ?: error("连接资料已释放，请重新从相机读取")
    override fun close() { secret = null }
    override fun toString(): String = "CameraWifiCredentials(redacted)"
}

/** User-initiated BLE operations; scoped GATT closes when reads or foreground-service observation ends. */
class CameraBluetooth(private val context: Context) {
    private fun adapter(): BluetoothAdapter {
        val permissions = if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        check(permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            "需要附近设备权限才能查找蓝牙相机"
        }
        if (Build.VERSION.SDK_INT < 31) check(context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true) {
            "当前 Android 版本需要开启系统定位后才能查找蓝牙相机"
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("此设备没有蓝牙功能")
        check(adapter.isEnabled) { "请先在系统设置中开启蓝牙" }
        return adapter
    }

    @SuppressLint("MissingPermission")
    suspend fun scan(onFound: (List<BluetoothCamera>) -> Unit): List<BluetoothCamera> = coroutineScope {
        val scanner = adapter().bluetoothLeScanner ?: error("蓝牙暂时无法扫描，请重新开启蓝牙")
        val events = Channel<BluetoothCamera>(64)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { accept(result) }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::accept) }
            private fun accept(result: ScanResult) {
                val record = result.scanRecord ?: return
                // Android omits the two company-ID bytes from this manufacturer payload.
                val payload = record.getManufacturerSpecificData(0x003a) ?: return
                if (payload.size < 7) return
                val state = payload[0].toInt() and 255
                if (state != 0 && state != 3) return // Never wake or power on a sleeping camera.
                try {
                    events.trySend(BluetoothCamera(result.device.address,
                        record.deviceName?.takeIf { it.isNotBlank() } ?: "LUMIX 蓝牙相机", result.rssi, state == 3))
                } catch (_: SecurityException) {
                    events.close(IllegalStateException("蓝牙权限已被撤回，请重新授权后查找相机"))
                }
            }
            override fun onScanFailed(errorCode: Int) { events.close(IllegalStateException("蓝牙扫描未能开始（$errorCode），请稍后重试")) }
        }
        val cameras = linkedMapOf<String, BluetoothCamera>()
        try {
            scanner.startScan(listOf(ScanFilter.Builder().setManufacturerData(0x003a, byteArrayOf()).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            withTimeoutOrNull(12_000L) {
                for (camera in events) {
                    cameras[camera.address] = camera
                    onFound(cameras.values.sortedByDescending { it.signal })
                }
            }
            cameras.values.sortedByDescending { it.signal }
        } finally {
            runCatching { scanner.stopScan(callback) }
            events.close()
        }
    }

    suspend fun readWifi(camera: BluetoothCamera, clientId: String,
        onStep: (BluetoothConnectionStep) -> Unit): CameraWifiCredentials = withConnection(camera, onStep) { session ->
        identifyAndRead(session, clientId, onStep)
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

    /** User chooses this action separately from reading credentials. Keep BLE until Wi-Fi handoff finishes. */
    suspend fun <T> withTransferWifi(camera: BluetoothCamera, clientId: String, expectedSsid: String,
        onStep: (BluetoothConnectionStep) -> Unit, block: suspend (CameraWifiCredentials) -> T): T =
        withConnection(camera, onStep) { session ->
            val credentials = identifyAndRead(session, clientId, onStep)
            try {
                check(credentials.ssid == expectedSsid) { "相机网络名称已变化，请重新读取连接资料后选择" }
                check(credentials.canStartTransferWifi) { "此相机的蓝牙启动流程暂不支持，请在相机上开启 Wi-Fi 后连接" }
                onStep(BluetoothConnectionStep.STARTING_WIFI)
                session.startTransferWifi()
                session.handoff { block(credentials) }
            } finally { credentials.close() }
        }

    /** The camera requests its sending mode. The caller cannot select an arbitrary mode byte. */
    suspend fun <T> withSendingWifi(camera: BluetoothCamera, clientId: String, expectedSsid: String,
        onStep: (BluetoothConnectionStep) -> Unit,
        block: suspend (CameraWifiCredentials, CameraAccessMode) -> T): T = withConnection(camera, onStep) { session ->
        val credentials = identifyAndRead(session, clientId, onStep)
        try {
            check(credentials.ssid == expectedSsid) { "相机网络名称已变化，请重新读取连接资料" }
            check(credentials.canStartTransferWifi && credentials.canObserveIncoming) {
                "此相机缺少已识别的发送启动能力，请在相机上开启 Wi-Fi 后连接"
            }
            val mode = session.startSendingWifi(onStep)
            session.sendingHandoff { block(credentials, mode) }
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
            return block(session)
        } finally { session.close() }
    }

    private suspend fun identifyAndRead(session: ConnectionGatt, clientId: String,
        onStep: (BluetoothConnectionStep) -> Unit): CameraWifiCredentials {
        val protocol = LumixBleProtocol
        val aps = session.has(protocol.apsClientId)
        onStep(BluetoothConnectionStep.IDENTIFYING)
        val identity = protocol.identity(clientId)
        val challenge = try {
            if (aps) {
                val challenge = protocol.challenge(session.read(protocol.challenge))
                session.write(protocol.apsClientId, protocol.encode(identity, challenge))
                session.write(protocol.apsFriendlyName, protocol.encode(protocol.appName, challenge))
                challenge
            } else {
                session.write(protocol.clientId, identity)
                session.write(protocol.friendlyName, protocol.appName)
                protocol.challenge(session.read(protocol.challenge))
            }
        } finally { identity.fill(0) }
        onStep(BluetoothConnectionStep.READING_WIFI)
        val ssid = protocol.decodeSsid(session.read(protocol.ssid))
        val passwordBytes = session.read(protocol.password)
        try { return CameraWifiCredentials(ssid, protocol.decodePassword(passwordBytes, challenge),
            session.canStartTransferWifi(), session.canObserveIncoming()) }
        finally { passwordBytes.fill(0) }
    }
}

/** One request at a time; connection/sending notifications and two fixed ordinary startup values. */
@SuppressLint("MissingPermission")
private class ConnectionGatt(private val context: Context, private val device: BluetoothDevice) : Closeable {
    private enum class Kind { CONNECTED, SERVICES, READ, WRITE, DESCRIPTOR }
    private class Event(val kind: Kind, val status: Int, val uuid: UUID? = null, val bytes: ByteArray = byteArrayOf())
    private val events = Channel<Event>(16, onUndeliveredElement = { it.bytes.fill(0) })
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val startupActive = AtomicBoolean(false)
    private val failure = AtomicReference<IllegalStateException?>(null)
    private val startupRejected = CompletableDeferred<IllegalStateException>()
    private val incomingMode = AtomicReference<CameraAccessMode?>(null)
    private val incomingEvents = IncomingCameraEvents()
    private val sendingStartupActive = AtomicBoolean(false)
    private val sendingStartupMode = AtomicReference<CameraAccessMode?>(null)
    private val sendingRequested = CompletableDeferred<CameraAccessMode>()
    private var gatt: BluetoothGatt? = null
    private val callback = object : BluetoothGattCallback() {
        private fun fail(message: String) {
            val problem = IllegalStateException(message)
            failure.compareAndSet(null, problem)
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
                            incomingEvents.stop(IllegalStateException("相机发送模式已切换，请重新发起连接"))
                    }
                    2 -> incomingEvents.stop(CameraSendingStopped("相机结束发送，连接已取消"))
                    3 -> incomingEvents.stop(IllegalStateException("相机请求重新读取网络资料，请重新连接"))
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
                        else incomingEvents.stop(IllegalStateException("相机发送模式已切换，请重新连接后继续"))
                    }
                    2 -> incomingEvents.stop(CameraSendingStopped("相机结束发送模式，已保存结果保留"))
                    3 -> incomingEvents.stop(IllegalStateException("相机请求重新读取网络资料，请重新连接"))
                }
                return
            }
            if (!startupActive.get()) return
            if (uuid != protocol.wifiChoiceReply && uuid != protocol.connectionStatus) return
            if (value.isEmpty()) { rejectStartup("相机返回的连接状态不完整，请使用 Wi-Fi 入口"); return }
            val state = value[0].toInt() and 255
            if (uuid == protocol.connectionStatus && state == 1)
                rejectStartup("相机拒绝当前连接，请在相机上确认或使用 Wi-Fi 入口")
            if (uuid == protocol.wifiChoiceReply && state == 0)
                rejectStartup("相机选择了其他 Wi-Fi 网络，请在相机上切换为直连后重试")
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
    suspend fun read(uuid: UUID): ByteArray = mutex.withLock {
        check(requireGatt().readCharacteristic(characteristic(uuid))) { "蓝牙读取未被系统接受，请重试" }
        await(Kind.READ, uuid).bytes
    }
    @Suppress("DEPRECATION")
    suspend fun write(uuid: UUID, bytes: ByteArray, service: UUID = LumixBleProtocol.service) = mutex.withLock {
        val characteristic = characteristic(uuid, service)
        val accepted = if (Build.VERSION.SDK_INT >= 33)
            requireGatt().writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = bytes.copyOf()
            requireGatt().writeCharacteristic(characteristic)
        }
        check(accepted) { "蓝牙连接资料未被系统接受，请重试" }
        await(Kind.WRITE, uuid)
        Unit
    }

    fun canStartTransferWifi(): Boolean {
        val protocol = LumixBleProtocol
        return hasWritable(protocol.wifiConnectionType, protocol.wifiService) && hasWritable(protocol.connectionMode, protocol.service) &&
            hasNotification(protocol.wifiChoiceReply, protocol.wifiService) && hasNotification(protocol.connectionStatus, protocol.service)
    }
    fun canObserveIncoming(): Boolean {
        val protocol = LumixBleProtocol
        return hasNotification(protocol.imageTransferEvent, protocol.sendingService) &&
            hasNotification(protocol.sendingModeEvent, protocol.service)
    }
    private fun hasWritable(uuid: UUID, service: UUID): Boolean = has(uuid, service) &&
        characteristic(uuid, service).properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
    private fun hasNotification(uuid: UUID, service: UUID): Boolean {
        if (!has(uuid, service)) return false
        val characteristic = characteristic(uuid, service)
        return characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 &&
            descriptor(characteristic) != null
    }
    private fun descriptor(characteristic: BluetoothGattCharacteristic): BluetoothGattDescriptor? =
        characteristic.getDescriptor(characteristic.uuid) ?: characteristic.getDescriptor(LumixBleProtocol.cccd)
    @Suppress("DEPRECATION")
    private suspend fun subscribe(uuid: UUID, service: UUID) = mutex.withLock {
        val characteristic = characteristic(uuid, service)
        val descriptor = descriptor(characteristic) ?: error("相机连接通知暂不支持，请在相机上开启 Wi-Fi 后连接")
        check(requireGatt().setCharacteristicNotification(characteristic, true)) { "无法启用相机连接通知" }
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val accepted = if (Build.VERSION.SDK_INT >= 33)
            requireGatt().writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        else { descriptor.value = value.copyOf(); requireGatt().writeDescriptor(descriptor) }
        check(accepted) { "连接通知未被系统接受，请使用 Wi-Fi 入口" }
        await(Kind.DESCRIPTOR, uuid)
        Unit
    }
    suspend fun startTransferWifi() {
        val protocol = LumixBleProtocol
        subscribe(protocol.wifiChoiceReply, protocol.wifiService)
        subscribe(protocol.connectionStatus, protocol.service)
        startupActive.set(true)
        // SoftAP type 1, ordinary connection mode 3. Never expose a caller-supplied mode value.
        write(protocol.wifiConnectionType, byteArrayOf(1), protocol.wifiService)
        write(protocol.connectionMode, byteArrayOf(3))
    }
    suspend fun startSendingWifi(onStep: (BluetoothConnectionStep) -> Unit): CameraAccessMode {
        check(canStartTransferWifi() && canObserveIncoming()) { "相机发送启动暂不支持，请使用 Wi-Fi 入口" }
        val protocol = LumixBleProtocol
        startupActive.set(true)
        sendingStartupActive.set(true)
        subscribe(protocol.wifiChoiceReply, protocol.wifiService)
        subscribe(protocol.connectionStatus, protocol.service)
        subscribe(protocol.imageTransferEvent, protocol.sendingService)
        subscribe(protocol.sendingModeEvent, protocol.service)
        onStep(BluetoothConnectionStep.WAITING_FOR_SEND)
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
        write(protocol.wifiConnectionType, byteArrayOf(1), protocol.wifiService)
        ensureSendingActive()
        val command = when (mode) {
            CameraAccessMode.AUTO_UPLOAD -> byteArrayOf(1)
            CameraAccessMode.MARKED_TRANSFER -> byteArrayOf(6)
            CameraAccessMode.BROWSING -> error("相机没有请求发送模式")
        }
        write(protocol.connectionMode, command)
        ensureSendingActive()
        return mode
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
                        error("相机拒绝蓝牙连接资料操作（${event.status}），请在相机上确认配对或使用 Wi-Fi 入口")
                    }
                    return@withTimeout event
                }
                @Suppress("UNREACHABLE_CODE") error("蓝牙响应未到达")
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            error("蓝牙相机响应超时，请在相机上确认配对后重试，或使用 Wi-Fi 入口")
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
