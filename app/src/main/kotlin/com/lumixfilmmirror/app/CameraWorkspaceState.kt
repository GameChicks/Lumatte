package com.lumixfilmmirror.app

import com.lumixfilmmirror.camera.CameraDevice
import com.lumixfilmmirror.camera.CameraMedia
import com.lumixfilmmirror.camera.ConnectionStep
import com.lumixfilmmirror.camera.DownloadProgress
import com.lumixfilmmirror.camera.DiscoveredCamera

data class CameraFolder(val id: String, val title: String)

data class CameraWorkspaceState(
    val device: CameraDevice? = null,
    val connected: Boolean = false,
    val disconnected: Boolean = false,
    val discovered: List<DiscoveredCamera> = emptyList(),
    val step: ConnectionStep? = null,
    val folders: List<CameraFolder> = listOf(CameraFolder("0", "相机照片")),
    val entries: List<CameraMedia> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val nextOffset: Int? = null,
    val updateId: String? = null,
    val total: Int = 0,
    val progress: DownloadProgress? = null,
    val transferred: Int = 0,
    val transferTotal: Int = 0,
    val problem: String? = null,
    val operationRunning: Boolean = false,
    val activeTransferBatch: String? = null,
    val notice: String? = null,
) {
    val busy: Boolean get() = operationRunning || activeTransferBatch != null
}
