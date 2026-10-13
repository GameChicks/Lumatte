package com.lumixfilmmirror.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumixfilmmirror.camera.ConnectionStep
import com.lumixfilmmirror.camera.CameraResourceKind
import com.lumixfilmmirror.camera.CameraAccessMode
import com.lumixfilmmirror.camera.BluetoothConnectionStep
import com.lumixfilmmirror.design.FilmSpacing
import com.lumixfilmmirror.design.PaperPanel

@Composable
fun CameraTransferPanel(camera: CameraWorkspaceState, busy: Boolean, cameraBusy: Boolean,
    transferring: Boolean, onImport: () -> Unit, onFollow: () -> Unit, workspace: WorkspaceViewModel,
    initialMethod: String = "wifi", requestedCamera: RememberedCamera? = null) {
    var method by rememberSaveable { mutableStateOf(initialMethod) }
    val differentCamera = camera.connected && requestedCamera != null && camera.device?.rememberedIdentity() != requestedCamera.id
    val listState = rememberLazyListState()
    val visibleIds by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.map { it.key }.toSet() }
    }
    PaperPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(FilmSpacing.panel), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (differentCamera) {
                Text("切换相机", style = MaterialTheme.typography.headlineSmall)
                Text("当前连接：${camera.device?.model.orEmpty()}")
                Text("你选择了 ${requestedCamera?.model.orEmpty()}。先断开当前相机，再为这台相机建立连接。",
                    style = MaterialTheme.typography.bodyMedium)
                MirrorButton(enabled = !busy, onClick = workspace::disconnectCamera) { Text("断开并切换") }
                Text("已经传入的照片会保留；传输进行中时先暂停，再切换相机。", style = MaterialTheme.typography.bodySmall)
            } else if (!camera.connected) {
                camera.sendingReconnect?.let { CameraSendingReconnectControl(it, busy, workspace) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MirrorChoice(method == "bluetooth", { method = "bluetooth" }, Modifier.weight(1f), enabled = !busy,
                        label = { Text("蓝牙配对") })
                    MirrorChoice(method != "bluetooth", { method = "wifi" }, Modifier.weight(1f), enabled = !busy,
                        label = { Text("Wi-Fi 连接") })
                }
                MirrorConnectionGuide(method == "bluetooth", requestedCamera?.model)
                if (method == "bluetooth") CameraBluetoothControls(camera, busy, workspace)
                else CameraConnectControls(busy, workspace)
                if (camera.device != null && camera.sendingReconnect == null &&
                    (requestedCamera == null || camera.device.rememberedIdentity() == requestedCamera.id)) TextButton(enabled = !busy, onClick = workspace::reconnectCamera) {
                    Text("重新连接 ${camera.device.model}")
                }
                if (camera.discovered.size > 1) {
                    Text("选择要连接的相机", style = MaterialTheme.typography.titleSmall)
                    for (found in camera.discovered) TextButton(enabled = !busy,
                        onClick = { workspace.chooseCamera(found.key) }) {
                        Text("${found.device.model} · ${found.device.friendlyName}")
                    }
                }
            } else {
                Text("从相机传入", style = MaterialTheme.typography.titleMedium)
                Text("${camera.device?.model.orEmpty()} · ${camera.device?.friendlyName.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                if (camera.followingIncoming) Text("后续拍摄接收已开启", style = MaterialTheme.typography.labelSmall)
                if (camera.accessMode.incoming) {
                    Text(if (camera.accessMode == CameraAccessMode.AUTO_UPLOAD) "相机已进入拍后自动发送模式"
                        else "相机已进入选定文件发送模式", style = MaterialTheme.typography.titleSmall)
                    Text("单轮接收在目录清空后结束；若下方提供后续拍摄入口，可保持连接继续接收。",
                        style = MaterialTheme.typography.bodySmall)
                    camera.incomingCount?.let { Text("相机报告待发送 $it 项", style = MaterialTheme.typography.labelSmall) }
                }
                Row {
                    TextButton(enabled = !busy, onClick = workspace::refreshCameraPhotos) { Text("刷新列表") }
                    TextButton(enabled = !busy, onClick = workspace::disconnectCamera) { Text("断开") }
                }
                Text(camera.folders.last().title, style = MaterialTheme.typography.titleSmall)
                if (camera.folders.size > 1) TextButton(enabled = !busy, onClick = workspace::cameraFolderBack) { Text("返回上级") }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), state = listState) {
                    items(camera.entries, key = { it.id }) { entry ->
                        if (entry.isFolder) {
                            TextButton(enabled = !busy && !camera.accessMode.incoming,
                                onClick = { workspace.openCameraFolder(entry) }) { Text("文件夹 · ${entry.title}") }
                        } else {
                            val resource = entry.transferResource
                            Row {
                                if (!camera.accessMode.incoming) Checkbox(checked = entry.id in camera.selectedIds,
                                    enabled = !busy && resource != null, onCheckedChange = { workspace.selectCameraPhoto(entry.id) })
                                if (!camera.accessMode.incoming) CameraPhotoThumbnail(entry, camera.thumbnailVersion, camera.thumbnailLoadVersion,
                                    enabled = !busy && entry.id in visibleIds, workspace = workspace)
                                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                                    Text(resource?.originalName ?: entry.title, style = MaterialTheme.typography.bodySmall)
                                    val label = when (resource?.kind) {
                                        CameraResourceKind.RAW -> "RAW · 传入后仅提取内嵌 JPEG"
                                        CameraResourceKind.RAW_PREVIEW_JPEG -> "RAW 内嵌 JPEG"
                                        CameraResourceKind.ORIGINAL_JPEG -> "原尺寸 JPEG"
                                        CameraResourceKind.VIDEO -> "${resource?.videoContainer?.label.orEmpty()} · 保存原视频到相册"
                                        else -> if (entry.isVideo)
                                            "未提供可传输的完整视频资源" else "当前没有可传输的原图资源"
                                    }
                                    Text(label, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                Text("已载入 ${camera.entries.size} / ${camera.total} 项", style = MaterialTheme.typography.labelSmall)
                if (camera.nextOffset != null) TextButton(enabled = !busy, onClick = workspace::loadMoreCameraPhotos) { Text("加载更多") }
                if (!camera.accessMode.incoming) Button(enabled = !busy && camera.selectedIds.isNotEmpty(), onClick = onImport) {
                    Text("传入选中文件 · ${camera.selectedIds.size} 项")
                }
                if (camera.accessMode.incoming) Button(enabled = !busy, onClick = onImport) { Text("接收待发送文件") }
                if (camera.accessMode.incoming && camera.bluetoothCanFollowIncoming) {
                    IncomingFollowControl(busy, onFollow, workspace)
                }
                if (!camera.accessMode.incoming) Text("照片进入工作台；视频直接保存到 Movies/LumixFilmMirror，保持原文件内容。",
                    style = MaterialTheme.typography.labelSmall)
            }
            if (cameraBusy) {
                val caption = when {
                    camera.wifiSelection != null -> "请选择用于本次相机连接的 Wi-Fi"
                    camera.cacheBusy -> "正在整理传输缓存"
                    camera.waitingForIncoming -> "等待后续拍摄 · 已保存 ${camera.transferred} 项"
                    camera.bluetoothStep == BluetoothConnectionStep.SCANNING -> "正在查找蓝牙相机 · 最多 20 秒"
                    camera.bluetoothStep == BluetoothConnectionStep.CONNECTING -> "正在连接蓝牙相机，请在相机上确认配对"
                    camera.bluetoothStep == BluetoothConnectionStep.READING_SERVICES -> "正在读取蓝牙连接能力"
                    camera.bluetoothStep == BluetoothConnectionStep.IDENTIFYING -> "正在登记本应用，请在相机上确认配对"
                    camera.bluetoothStep == BluetoothConnectionStep.READING_WIFI -> "正在读取相机 Wi-Fi 资料"
                    camera.bluetoothStep == BluetoothConnectionStep.WAITING_FOR_SEND -> "等待相机发送请求 · 请在相机上开启拍后发送或选定文件发送"
                    camera.step == ConnectionStep.REQUESTING_WIFI && camera.bluetoothStep == BluetoothConnectionStep.STARTING_WIFI -> "请在系统弹窗中确认连接相机 Wi-Fi"
                    camera.bluetoothStep == BluetoothConnectionStep.STARTING_WIFI -> "正在启动相机的传输 Wi-Fi"
                    camera.receiving -> "${camera.transferPhase.caption()} · 已保存 ${camera.transferred} 项 · 待发送 ${camera.incomingCount ?: 0} 项"
                    camera.transferTotal > 0 -> "${camera.transferPhase.caption()} · 完成 ${camera.transferred} / ${camera.transferTotal} 项"
                    camera.step == ConnectionStep.REQUESTING_WIFI -> "请在系统弹窗中确认连接"
                    camera.step == ConnectionStep.DISCOVERING -> "正在查找相机"
                    camera.step == ConnectionStep.WAITING_FOR_CAMERA_APPROVAL -> "请在相机上确认连接"
                    camera.step == ConnectionStep.READING_DEVICE -> "正在读取相机信息"
                    camera.step == ConnectionStep.REQUESTING_ACCESS -> "正在建立连接"
                    else -> "正在读取照片列表"
                }
                Text(caption, style = MaterialTheme.typography.bodySmall)
                camera.progress?.let { progress ->
                    val total = progress.totalBytes
                    if (total != null && total > 0) LinearProgressIndicator(
                        progress = { (progress.bytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${progress.bytes / 1024} KB${total?.let { " / ${it / 1024} KB" }.orEmpty()}",
                        style = MaterialTheme.typography.labelSmall)
                }
                Row {
                    if (transferring) TextButton(onClick = workspace::pauseTransfer) { Text("暂停传输") }
                    TextButton(onClick = workspace::cancelCameraOperation) {
                        Text(if (camera.followingIncoming) "停止后续接收" else if (transferring) "取消后续传输" else "取消操作")
                    }
                }
            }
            camera.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            camera.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
