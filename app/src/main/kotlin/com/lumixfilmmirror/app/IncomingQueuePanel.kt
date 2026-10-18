package com.lumixfilmmirror.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumixfilmmirror.camera.CameraAccessMode
import com.lumixfilmmirror.design.FilmSpacing
import com.lumixfilmmirror.design.PaperPanel
import com.lumixfilmmirror.storage.IncomingTransfer
import com.lumixfilmmirror.storage.TransferTask
import com.lumixfilmmirror.storage.completed
import com.lumixfilmmirror.storage.terminal
import com.lumixfilmmirror.storage.confirmationUnknown

@Composable
fun IncomingQueuePanel(runs: List<IncomingTransfer>, tasks: List<TransferTask>, state: WorkspaceState,
    workspace: WorkspaceViewModel, onResume: (String) -> Unit) {
    var history by remember { mutableStateOf(false) }
    val displayed = if (history) runs else runs.filter { !it.terminal }
    PaperPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(FilmSpacing.panel), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("相机待发接收", style = MaterialTheme.typography.titleMedium)
            Text("文件保存与相机确认分别记录。停止接收只停止应用后续处理，已保存结果保留；相机上的发送模式需在相机结束。",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { history = !history }) { Text(if (history) "隐藏已结束接收" else "显示接收历史") }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(displayed, key = { it.id }) { run ->
                    val saved = tasks.count { it.batchId == run.id && it.completed }
                    val active = state.activeTransferBatch == run.id
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val mode = if (run.mode == CameraAccessMode.AUTO_UPLOAD.name) "拍后发送" else "相机选定发送"
                        Text("${run.cameraModel} · $mode · ${incomingLabel(run.state)}", style = MaterialTheme.typography.titleSmall)
                        Text("已保存 $saved 项 · ${confirmationLabel(run)}", style = MaterialTheme.typography.bodySmall)
                        run.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                        Row {
                            if (!run.terminal && !active) {
                                TextButton(enabled = !state.busy, onClick = { onResume(run.id) }) {
                                    Text(if (run.confirmationUnknown) "继续核对待发送目录" else "继续接收")
                                }
                                TextButton(enabled = !state.busy, onClick = { workspace.cancelTransferBatch(run.id) }) {
                                    Text("停止此接收")
                                }
                            }
                            if (active) TextButton(onClick = workspace::pauseTransfer) { Text("暂停接收") }
                        }
                        HorizontalDivider()
                    }
                }
            }
            if (displayed.isEmpty()) Text("暂无未结束接收", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun incomingLabel(state: String): String = when (state) {
    "RUNNING" -> "接收中"
    "WAITING" -> "等待后续拍摄"
    "PAUSED" -> "已暂停"
    "COMPLETE" -> "本轮目录已清空"
    "CANCELLED" -> "已停止"
    else -> state
}

private fun confirmationLabel(run: IncomingTransfer): String = when (run.acknowledgement) {
    "NONE" -> "等待文件保存"
    "READY" -> "文件已保存，待核对相机"
    "IN_FLIGHT" -> "等待相机确认响应"
    "UNKNOWN" -> "相机确认响应未知，不自动重发"
    "CONFIRMED" -> "最后一项相机确认成功"
    "ADVANCED" -> if (run.mode == CameraAccessMode.AUTO_UPLOAD.name) "此模式无需单独确认"
        else "原文件不再是待发送首项，未收到对应确认"
    else -> "确认状态待核对"
}
