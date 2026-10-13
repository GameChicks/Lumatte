package com.lumixfilmmirror.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumixfilmmirror.design.LocalFilmMaterials

/** Dedicated layout space above the page; collapse never covers the page's actions. */
@Composable
internal fun MirrorTaskProgress(state: WorkspaceState, camera: CameraWorkspaceState, workspace: WorkspaceViewModel,
    collapsed: Boolean, onCollapse: (Boolean) -> Unit) {
    val transfer = camera.activeTransferBatch != null
    if (!transfer && !state.exporting) return
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), shape = RoundedCornerShape(20.dp),
        color = LocalFilmMaterials.current.paperHighlight, shadowElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (collapsed) listOfNotNull(
                    "保存 ${state.completed}/${state.total}".takeIf { state.exporting },
                    "传入 ${camera.transferred}/${camera.transferTotal}".takeIf { transfer }
                ).joinToString(" · ") else "任务进度", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { onCollapse(!collapsed) }) { Text(if (collapsed) "展开进度" else "收起") }
            }
            if (!collapsed) {
                if (state.exporting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("正在保存 ${state.completed} / ${state.total}", Modifier.weight(1f))
                        TextButton(onClick = workspace::pauseExport) { Text("暂停保存") }
                    }
                    if (state.total > 0) LinearProgressIndicator(progress = { (state.completed.toFloat() / state.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (transfer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("正在传入 ${camera.transferred} / ${camera.transferTotal}", Modifier.weight(1f))
                        TextButton(onClick = workspace::pauseTransfer) { Text("暂停传入") }
                    }
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}
