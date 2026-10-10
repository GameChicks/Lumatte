package com.lumixfilmmirror.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumixfilmmirror.camera.ConnectionStep
import com.lumixfilmmirror.camera.CameraResourceKind
import com.lumixfilmmirror.design.FilmSpacing
import com.lumixfilmmirror.design.PaperPanel

@Composable
fun CameraTransferPanel(camera: CameraWorkspaceState, busy: Boolean, cameraBusy: Boolean,
    transferring: Boolean, onImport: () -> Unit, workspace: WorkspaceViewModel) {
    PaperPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(FilmSpacing.panel), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("从相机传入", style = MaterialTheme.typography.titleMedium)
            if (!camera.connected) {
                CameraConnectControls(busy, workspace)
                if (camera.device != null) TextButton(enabled = !busy, onClick = workspace::reconnectCamera) {
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
                Text("${camera.device?.model.orEmpty()} · ${camera.device?.friendlyName.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(enabled = !busy, onClick = workspace::refreshCameraPhotos) { Text("刷新列表") }
                    TextButton(enabled = !busy, onClick = workspace::disconnectCamera) { Text("断开") }
                }
                Text(camera.folders.last().title, style = MaterialTheme.typography.titleSmall)
                if (camera.folders.size > 1) TextButton(enabled = !busy, onClick = workspace::cameraFolderBack) { Text("返回上级") }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    items(camera.entries, key = { it.id }) { entry ->
                        if (entry.isFolder) {
                            TextButton(enabled = !busy, onClick = { workspace.openCameraFolder(entry) }) { Text("文件夹 · ${entry.title}") }
                        } else {
                            val resource = entry.transferResource
                            Row {
                                Checkbox(checked = entry.id in camera.selectedIds, enabled = !busy && resource != null,
                                    onCheckedChange = { workspace.selectCameraPhoto(entry.id) })
                                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                                    Text(resource?.originalName ?: entry.title, style = MaterialTheme.typography.bodySmall)
                                    val label = when (resource?.kind) {
                                        CameraResourceKind.RAW -> "RAW · 传入后仅提取内嵌 JPEG"
                                        CameraResourceKind.RAW_PREVIEW_JPEG -> "RAW 内嵌 JPEG"
                                        CameraResourceKind.ORIGINAL_JPEG -> "原尺寸 JPEG"
                                        CameraResourceKind.VIDEO -> "${resource?.videoContainer?.label.orEmpty()} · 保存原视频到相册"
                                        else -> if (entry.resources.any { it.kind == CameraResourceKind.VIDEO })
                                            "此视频格式暂不支持传输" else "当前没有可传输的原图资源"
                                    }
                                    Text(label, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                Text("已载入 ${camera.entries.size} / ${camera.total} 项", style = MaterialTheme.typography.labelSmall)
                if (camera.nextOffset != null) TextButton(enabled = !busy, onClick = workspace::loadMoreCameraPhotos) { Text("加载更多") }
                Button(enabled = !busy && camera.selectedIds.isNotEmpty(), onClick = onImport) {
                    Text("传入选中文件 · ${camera.selectedIds.size} 项")
                }
                Text("照片进入工作台；视频直接保存到 Movies/LumixFilmMirror，保持原文件内容。",
                    style = MaterialTheme.typography.labelSmall)
            }
            if (cameraBusy) {
                val caption = when {
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
                    TextButton(onClick = workspace::cancelCameraOperation) { Text(if (transferring) "取消后续传输" else "取消操作") }
                }
            }
            camera.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            camera.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
