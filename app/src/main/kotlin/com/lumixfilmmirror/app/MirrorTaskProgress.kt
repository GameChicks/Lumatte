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
    val compact = collapsed || state.exporting
    Surface(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp), shape = RoundedCornerShape(20.dp),
        color = LocalFilmMaterials.current.paperHighlight, shadowElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (compact) listOfNotNull(
                    "保存 ${state.completed}/${state.total}".takeIf { state.exporting },
                    "传入 ${camera.transferred}/${camera.transferTotal}".takeIf { transfer }
                ).joinToString(" · ") else "任务进度", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if(state.exporting)MirrorTextAction("暂停保存",workspace::pauseExport)
                else MirrorTextAction(if(collapsed)"展开"else"收起",{onCollapse(!collapsed)})
            }
            if(state.exporting)LinearProgressIndicator(progress={if(state.total>0)(state.completed.toFloat()/state.total).coerceIn(0f,1f)else 0f},modifier=Modifier.fillMaxWidth())
            AnimatedVisibility(visible = !compact,
                enter = fadeIn(tween(duration)) + expandVertically(tween(duration, easing = MirrorEase)),
                exit = fadeOut(tween(duration)) + shrinkVertically(tween(duration, easing = MirrorEase))) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.exporting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("正在保存 ${state.completed} / ${state.total}", Modifier.weight(1f))
                        MirrorActionButton(onClick = workspace::pauseExport) { Text("暂停保存") }
                    }
                    if (state.total > 0) LinearProgressIndicator(progress = { (state.completed.toFloat() / state.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (transfer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("正在传入 ${camera.transferred} / ${camera.transferTotal}", Modifier.weight(1f))
                        MirrorActionButton(onClick = workspace::pauseTransfer) { Text("暂停传入") }
                    }
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                }
            }
        }
    }
    }
}
