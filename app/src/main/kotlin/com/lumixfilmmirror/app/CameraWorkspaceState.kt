package com.lumixfilmmirror.app

import com.lumixfilmmirror.camera.CameraDevice
import com.lumixfilmmirror.camera.CameraAccessMode
import com.lumixfilmmirror.camera.CameraMedia
import com.lumixfilmmirror.camera.ConnectionStep
import com.lumixfilmmirror.camera.DownloadProgress
import com.lumixfilmmirror.camera.DiscoveredCamera
import com.lumixfilmmirror.camera.BluetoothCamera
import com.lumixfilmmirror.camera.BluetoothScanReport
import com.lumixfilmmirror.camera.BluetoothConnectionStep
import com.lumixfilmmirror.camera.CameraSendingReconnectRequest
import com.lumixfilmmirror.camera.CameraWifiNetwork
import com.lumixfilmmirror.transfer.TransferPhase
import com.lumixfilmmirror.transfer.TransferCacheSnapshot

data class CameraFolder(val id: String, val title: String)

data class CameraWifiSelection(val id: String, val title: String, val options: List<CameraWifiNetwork>,
    val problem: String? = null)

data class CameraWorkspaceState(
    val device: CameraDevice? = null,
    val connected: Boolean = false,
    val disconnected: Boolean = false,
    val accessMode: CameraAccessMode = CameraAccessMode.BROWSING,
    val incomingCount: Int? = null,
    val discovered: List<DiscoveredCamera> = emptyList(),
    val bluetoothCameras: List<BluetoothCamera> = emptyList(),
    val bluetoothSelectedCamera: BluetoothCamera? = null,
    // HTTP approval completed in the registration phase; media availability is separate.
    val bluetoothPairingCompleted: Boolean = false,
    val bluetoothScanReport: BluetoothScanReport? = null,
    val bluetoothStep: BluetoothConnectionStep? = null,
    val bluetoothWifiSsid: String? = null,
    val bluetoothCanStartWifi: Boolean = false,
    val bluetoothCanStartRouterWifi: Boolean = false,
    val bluetoothCanStartSendingWifi: Boolean = false,
    val bluetoothCanFollowIncoming: Boolean = false,
    val sendingReconnect: CameraSendingReconnectRequest? = null,
    val wifiSelection: CameraWifiSelection? = null,
    val step: ConnectionStep? = null,
    val folders: List<CameraFolder> = listOf(CameraFolder("0", "相机媒体")),
    val entries: List<CameraMedia> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val nextOffset: Int? = null,
    val updateId: String? = null,
    val thumbnailVersion: Long = 0,
    val thumbnailLoadVersion: Long = 0,
    val total: Int = 0,
    val progress: DownloadProgress? = null,
    val transferred: Int = 0,
    val transferTotal: Int = 0,
    val transferPhase: TransferPhase? = null,
    val problem: String? = null,
    val operationRunning: Boolean = false,
    val cacheBusy: Boolean = false,
    val transferCache: TransferCacheSnapshot? = null,
    val activeTransferBatch: String? = null,
    val receiving: Boolean = false,
    val followingIncoming: Boolean = false,
    val waitingForIncoming: Boolean = false,
    val notice: String? = null,
) {
    val busy: Boolean get() = operationRunning || activeTransferBatch != null
}
