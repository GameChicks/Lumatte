package com.lumixfilmmirror.app

import com.lumixfilmmirror.camera.CameraDevice
import com.lumixfilmmirror.camera.CameraMedia
import com.lumixfilmmirror.camera.ConnectionStep
import com.lumixfilmmirror.camera.DownloadProgress
import com.lumixfilmmirror.camera.DiscoveredCamera
import com.lumixfilmmirror.camera.BluetoothCamera
import com.lumixfilmmirror.camera.BluetoothConnectionStep
import com.lumixfilmmirror.transfer.TransferPhase
import com.lumixfilmmirror.transfer.TransferCacheSnapshot

data class CameraFolder(val id: String, val title: String)

data class CameraWorkspaceState(
    val device: CameraDevice? = null,
    val connected: Boolean = false,
    val disconnected: Boolean = false,
    val discovered: List<DiscoveredCamera> = emptyList(),
    val bluetoothCameras: List<BluetoothCamera> = emptyList(),
    val bluetoothStep: BluetoothConnectionStep? = null,
    val bluetoothWifiSsid: String? = null,
    val step: ConnectionStep? = null,
    val folders: List<CameraFolder> = listOf(CameraFolder("0", "相机媒体")),
    val entries: List<CameraMedia> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val nextOffset: Int? = null,
    val updateId: String? = null,
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
    val notice: String? = null,
) {
    val busy: Boolean get() = operationRunning || activeTransferBatch != null
}
