package com.lumixfilmmirror.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumixfilmmirror.camera.*
import com.lumixfilmmirror.design.PaperPanel
import kotlinx.coroutines.flow.distinctUntilChanged

/** A single scrolling gallery; connection status and the import action never scroll inside another list. */
@Composable
fun CameraTransferPanel(camera: CameraWorkspaceState, busy: Boolean, cameraBusy: Boolean,
    transferring: Boolean, onImport: () -> Unit, onFollow: () -> Unit, workspace: WorkspaceViewModel,
    initialMethod: String = "wifi", requestedCamera: RememberedCamera? = null,
    modifier: Modifier = Modifier.height(680.dp), trailingContent: @Composable () -> Unit = {}) {
    var method by rememberSaveable(initialMethod) { mutableStateOf(initialMethod) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var guideExpanded by rememberSaveable { mutableStateOf(false) }
    val differentCamera = camera.connected && requestedCamera != null && camera.device?.rememberedIdentity() != requestedCamera.id
    val gallery = rememberLazyGridState()
    val visibleIds by remember(gallery) {
        derivedStateOf { gallery.layoutInfo.visibleItemsInfo.map { it.key }.toSet() }
    }
    val latestCamera by rememberUpdatedState(camera)
    val latestBusy by rememberUpdatedState(busy)
    LaunchedEffect(camera.connected, camera.nextOffset, camera.thumbnailVersion) {
        snapshotFlow {
            val state = latestCamera
            state.connected && !state.accessMode.incoming && state.nextOffset != null &&
                state.entries.isNotEmpty() && state.problem == null && !latestBusy &&
                (gallery.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.entries.size - 9
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) workspace.loadMoreCameraPhotos() }
    }
    LaunchedEffect(camera.thumbnailVersion) { gallery.scrollToItem(0) }
    PaperPanel(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize()) {
            // An expired session must be visible even when the user has scrolled far down the gallery.
            camera.problem?.let {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (!camera.connected && camera.device != null && camera.sendingReconnect == null)
                        TextButton(enabled = !busy, onClick = workspace::reconnectCamera) { Text("重新连接") }
                }
                HorizontalDivider()
            }
            LazyVerticalGrid(columns = GridCells.Adaptive(96.dp), state = gallery,
                modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "connection", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CameraOperationStatus(camera, cameraBusy, transferring, workspace)
                        if (differentCamera) {
                            Text("切换相机", style = MaterialTheme.typography.titleLarge)
                            Text("当前连接 ${camera.device?.model.orEmpty()}。断开后可连接 ${requestedCamera?.model.orEmpty()}。")
                            MirrorButton(enabled = !busy, onClick = workspace::disconnectCamera) { Text("断开并切换") }
                        } else if (!camera.connected) {
                            camera.sendingReconnect?.let { CameraSendingReconnectControl(it, busy, workspace) }
                            if (requestedCamera != null) Text("连接 ${requestedCamera.model}", style = MaterialTheme.typography.titleLarge)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MirrorChoice(method == "bluetooth", { method = "bluetooth" }, Modifier.weight(1f), enabled = !busy,
                                    label = { Text("蓝牙连接") })
                                MirrorChoice(method != "bluetooth", { method = "wifi" }, Modifier.weight(1f), enabled = !busy,
                                    label = { Text("Wi-Fi 连接") })
                            }
                            if (method == "bluetooth") CameraBluetoothControls(camera, busy, workspace)
                            else CameraConnectControls(busy, workspace)
                            TextButton(onClick = { guideExpanded = !guideExpanded }) {
                                Text(if (guideExpanded) "收起连接帮助 ↑" else "首次连接？查看步骤 ↓")
                            }
                            if (guideExpanded) MirrorConnectionGuide(method == "bluetooth", requestedCamera?.model)
                            if (camera.problem == null && camera.device != null && camera.sendingReconnect == null &&
                                (requestedCamera == null || camera.device.rememberedIdentity() == requestedCamera.id))
                                TextButton(enabled = !busy, onClick = workspace::reconnectCamera) { Text("重新连接 ${camera.device.model}") }
                            if (camera.discovered.size > 1) {
                                Text("选择相机", style = MaterialTheme.typography.titleSmall)
                                camera.discovered.forEach { found -> TextButton(enabled = !busy,
                                    onClick = { workspace.chooseCamera(found.key) }) {
                                    Text("${found.device.model} · ${found.device.friendlyName}")
                                } }
                            }
                        } else {
                            Text(camera.device?.model.orEmpty(), style = MaterialTheme.typography.titleLarge)
                            Text(if (camera.accessMode.incoming) "相机发送目录" else "相机相册 · 最新在前",
                                style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(enabled = !busy, onClick = workspace::refreshCameraPhotos) { Text("刷新") }
                                if (!camera.accessMode.incoming) {
                                    TextButton(enabled = !busy && camera.entries.any { !it.isFolder && it.transferResource != null },
                                        onClick = { workspace.selectLoadedCameraPhotos(true) }) { Text("选择已载入") }
                                    if (camera.selectedIds.isNotEmpty()) TextButton(enabled = !busy,
                                        onClick = { workspace.selectLoadedCameraPhotos(false) }) { Text("取消选择") }
                                }
                                TextButton(enabled = !busy, onClick = workspace::disconnectCamera) { Text("断开连接") }
                            }
                            if (camera.folders.size > 1) TextButton(enabled = !busy, onClick = workspace::cameraFolderBack) {
                                Text("← 返回上级 · ${camera.folders.last().title}")
                            }
                            if (camera.accessMode.incoming) Text("按相机发送队列接收；RAW 文件仅提取内嵌 JPEG。",
                                style = MaterialTheme.typography.bodySmall)
                            else Text("点按照片可多选；RAW 仅使用内嵌 JPEG。视频保存原文件。", style = MaterialTheme.typography.bodySmall)
                        }
                        camera.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (camera.connected && !differentCamera) {
                    items(camera.entries, key = { it.id }) { entry ->
                        CameraGalleryTile(entry, entry.id in camera.selectedIds, camera,
                            enabled = !busy && !camera.accessMode.incoming,
                            loadPreview = !busy && !camera.accessMode.incoming && entry.id in visibleIds, workspace)
                    }
                    item(key = "pagination", span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (camera.entries.isEmpty() && !busy) Text("相机中没有可浏览的媒体")
                            Text("已载入 ${camera.entries.size} / ${camera.total} 项", style = MaterialTheme.typography.labelSmall)
                            if (camera.nextOffset != null) TextButton(enabled = !busy, onClick = workspace::loadMoreCameraPhotos) {
                                Text(if (camera.accessMode.incoming) "加载更多" else "加载更早的照片")
                            }
                        }
                    }
                }
                item(key = "diagnostics", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        TextButton(onClick = { diagnostics = !diagnostics }) { Text(if (diagnostics) "收起连接日志 ↑" else "连接日志与诊断 ↓") }
                        if (diagnostics) CameraConnectionLogControls()
                        trailingContent()
                    }
                }
            }
            if (camera.connected && !differentCamera) {
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!camera.accessMode.incoming) Button(modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && camera.selectedIds.isNotEmpty(), onClick = onImport) {
                        Text(if (camera.selectedIds.isEmpty()) "选择照片后传入" else "传入选中 · ${camera.selectedIds.size} 项")
                    } else {
                        Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = onImport) { Text("接收待发送文件") }
                        if (camera.bluetoothCanFollowIncoming) IncomingFollowControl(busy, onFollow, workspace)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraGalleryTile(entry: CameraMedia, selected: Boolean, camera: CameraWorkspaceState,
    enabled: Boolean, loadPreview: Boolean, workspace: WorkspaceViewModel) {
    val shape = RoundedCornerShape(8.dp)
    val action = if (entry.isFolder) Modifier.clickable(enabled = enabled, role = Role.Button) { workspace.openCameraFolder(entry) }
        else Modifier.selectable(selected, enabled = enabled && entry.transferResource != null, role = Role.Checkbox) {
            workspace.selectCameraPhoto(entry.id)
        }
    Column(Modifier.fillMaxWidth().clip(shape).then(action)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)) {
            if (entry.isFolder) Text("文件夹", Modifier.align(Alignment.Center), style = MaterialTheme.typography.labelLarge)
            else {
                CameraPhotoThumbnail(entry, camera.thumbnailVersion, camera.thumbnailLoadVersion,
                    loadPreview, workspace, Modifier.fillMaxSize().padding(if (selected) 3.dp else 0.dp))
                if (!camera.accessMode.incoming && entry.transferResource != null)
                    Surface(Modifier.align(Alignment.TopEnd).padding(5.dp), shape = RoundedCornerShape(12.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = .8f)) {
                        Text(if (selected) "✓" else "○", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium)
                    }
                val tag = when (entry.transferResource?.kind) {
                    CameraResourceKind.RAW, CameraResourceKind.RAW_PREVIEW_JPEG -> "RAW · JPEG"
                    CameraResourceKind.VIDEO -> "视频"
                    else -> null
                }
                tag?.let { Surface(Modifier.align(Alignment.BottomStart).padding(4.dp), shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .85f)) {
                    Text(it, Modifier.padding(horizontal = 4.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                } }
            }
        }
        Text(entry.title, Modifier.padding(vertical = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CameraOperationStatus(camera: CameraWorkspaceState, cameraBusy: Boolean, transferring: Boolean,
    workspace: WorkspaceViewModel) {
    if (cameraBusy) {
        if (!camera.connected && !transferring && !camera.cacheBusy && !camera.waitingForIncoming) {
            camera.bluetoothSelectedCamera?.let {
                Text("正在连接 ${it.name}", style = MaterialTheme.typography.titleSmall)
            }
            // Connection stages are observable; their durations have no reliable percentage.
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("① 选择相机  →  ② 蓝牙登记  →  ③ Wi-Fi 连接  →  ④ 读取照片",
                style = MaterialTheme.typography.labelSmall)
        }
        val caption = when {
            camera.wifiSelection != null -> "请选择用于本次相机连接的 Wi-Fi"
            camera.cacheBusy -> "正在整理传输缓存"
            camera.waitingForIncoming -> "等待后续拍摄 · 已保存 ${camera.transferred} 项"
            camera.bluetoothStep == BluetoothConnectionStep.SCANNING -> "正在查找蓝牙相机 · 最多 20 秒"
            camera.bluetoothStep == BluetoothConnectionStep.CONNECTING -> "正在连接蓝牙相机，请在相机上确认配对"
            camera.bluetoothStep == BluetoothConnectionStep.READING_SERVICES -> "正在读取蓝牙连接能力"
            camera.bluetoothStep == BluetoothConnectionStep.REGISTERING_NOTIFICATIONS -> "正在启用相机连接通知"
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
                Text(if (camera.followingIncoming) "停止后续接收" else if (transferring) "取消后续传输"
                    else if (camera.bluetoothStep == BluetoothConnectionStep.SCANNING) "停止查找"
                    else if (camera.connected) "取消操作" else "取消连接")
            }
        }
    }

}
