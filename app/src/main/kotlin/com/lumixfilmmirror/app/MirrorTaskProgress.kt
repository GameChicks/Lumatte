package com.lumixfilmmirror.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    val active = transfer || state.exporting
    var lastState by remember { mutableStateOf(state) }
    var lastCamera by remember { mutableStateOf(camera) }
    SideEffect { if (active) { lastState = state; lastCamera = camera } }
    val duration = if (LocalMirrorReducedMotion.current) 0 else 300
    AnimatedVisibility(visible = active,
        enter = fadeIn(tween(duration)) + expandVertically(tween(duration, easing = MirrorEase)),
        exit = fadeOut(tween(duration)) + shrinkVertically(tween(duration, easing = MirrorEase))) {
    val state = if (active) state else lastState
    val camera = if (active) camera else lastCamera
    val transfer = camera.activeTransferBatch != null
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
            AnimatedVisibility(visible = !collapsed,
                enter = fadeIn(tween(duration)) + expandVertically(tween(duration, easing = MirrorEase)),
                exit = fadeOut(tween(duration)) + shrinkVertically(tween(duration, easing = MirrorEase))) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    }
}
