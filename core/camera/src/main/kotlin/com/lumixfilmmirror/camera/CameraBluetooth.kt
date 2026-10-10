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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BluetoothCamera(val address: String, val name: String, val signal: Int, val pairing: Boolean)
enum class BluetoothConnectionStep { SCANNING, CONNECTING, READING_SERVICES, IDENTIFYING, READING_WIFI }

/** Deliberately not a data class: generated toString must never reveal a Wi-Fi password. */
class CameraWifiCredentials(val ssid: String, private var secret: String?) : Closeable {
    fun password(): String = secret ?: error("连接资料已释放，请重新从相机读取")
    override fun close() { secret = null }
    override fun toString(): String = "CameraWifiCredentials(redacted)"
}

/** Foreground, user-initiated BLE operations. Every scan/GATT session closes on return or cancellation. */
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

    @SuppressLint("MissingPermission")
    suspend fun readWifi(camera: BluetoothCamera, clientId: String,
        onStep: (BluetoothConnectionStep) -> Unit): CameraWifiCredentials {
        val device = adapter().getRemoteDevice(camera.address)
        val session = ConnectionGatt(context, device)
        try {
            onStep(BluetoothConnectionStep.CONNECTING)
            session.connect()
            onStep(BluetoothConnectionStep.READING_SERVICES)
            session.discover()
            val protocol = LumixBleProtocol
            val aps = session.has(protocol.apsClientId)
            onStep(BluetoothConnectionStep.IDENTIFYING)
            var challenge = 0
            val identity = protocol.identity(clientId)
            try {
                if (aps) {
                    challenge = protocol.challenge(session.read(protocol.challenge))
                    session.write(protocol.apsClientId, protocol.encode(identity, challenge))
                    session.write(protocol.apsFriendlyName, protocol.encode(protocol.appName, challenge))
                } else {
                    session.write(protocol.clientId, identity)
                    session.write(protocol.friendlyName, protocol.appName)
                    challenge = protocol.challenge(session.read(protocol.challenge))
                }
            } finally { identity.fill(0) }
            onStep(BluetoothConnectionStep.READING_WIFI)
            val ssid = protocol.decodeSsid(session.read(protocol.ssid))
            val passwordBytes = session.read(protocol.password)
            try { return CameraWifiCredentials(ssid, protocol.decodePassword(passwordBytes, challenge)) }
            finally { passwordBytes.fill(0) }
        } catch (_: TimeoutCancellationException) {
            error("蓝牙相机响应超时，请在相机上确认配对后重试，或使用 Wi-Fi 入口")
        } finally { session.close() }
    }
}

/** One request at a time, callbacks copied at receipt, no notifications or remote-control UUIDs. */
@SuppressLint("MissingPermission")
private class ConnectionGatt(private val context: Context, private val device: BluetoothDevice) : Closeable {
    private enum class Kind { CONNECTED, SERVICES, READ, WRITE }
    private class Event(val kind: Kind, val status: Int, val uuid: UUID? = null, val bytes: ByteArray = byteArrayOf())
    private val events = Channel<Event>(16)
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private var gatt: BluetoothGatt? = null
    private val callback = object : BluetoothGattCallback() {
        private fun send(event: Event) {
            if (closed.get()) { event.bytes.fill(0); return }
            if (!events.trySend(event).isSuccess) {
                event.bytes.fill(0)
                events.close(IllegalStateException("蓝牙响应过多，请重新连接"))
            }
        }
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS)
                send(Event(Kind.CONNECTED, status))
            else if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED)
                events.close(IllegalStateException("蓝牙相机已断开（$status），请在相机上开启配对后重试"))
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) { send(Event(Kind.SERVICES, status)) }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            send(Event(Kind.WRITE, status, characteristic.uuid))
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
    fun has(uuid: UUID): Boolean = requireGatt().getService(LumixBleProtocol.service)?.getCharacteristic(uuid) != null
    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic = requireGatt()
        .getService(LumixBleProtocol.service)?.getCharacteristic(uuid)
        ?: error("此相机缺少已识别的蓝牙连接资料，请使用 Wi-Fi 入口")
    suspend fun read(uuid: UUID): ByteArray = mutex.withLock {
        check(requireGatt().readCharacteristic(characteristic(uuid))) { "蓝牙读取未被系统接受，请重试" }
        await(Kind.READ, uuid).bytes
    }
    @Suppress("DEPRECATION")
    suspend fun write(uuid: UUID, bytes: ByteArray) = mutex.withLock {
        val characteristic = characteristic(uuid)
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
    private fun requireGatt(): BluetoothGatt = gatt ?: error("蓝牙会话已经结束")
    private suspend fun await(kind: Kind, uuid: UUID? = null, timeout: Long = 20_000L): Event = withTimeout(timeout) {
        while (true) {
            val event = events.receive()
            if (event.kind != kind || event.uuid != uuid) { event.bytes.fill(0); continue }
            if (event.status != BluetoothGatt.GATT_SUCCESS) {
                event.bytes.fill(0)
                error("相机拒绝蓝牙连接资料操作（${event.status}），请在相机上确认配对或使用 Wi-Fi 入口")
            }
            return@withTimeout event
        }
        @Suppress("UNREACHABLE_CODE") error("蓝牙响应未到达")
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        events.close()
        while (true) (events.tryReceive().getOrNull() ?: break).bytes.fill(0)
    }
}
