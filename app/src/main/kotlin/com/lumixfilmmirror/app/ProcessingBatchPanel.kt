package com.lumixfilmmirror.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumixfilmmirror.design.FilmSpacing
import com.lumixfilmmirror.design.PaperPanel

@Composable
internal fun ProcessingBatchPanel(state: WorkspaceState, workspace: WorkspaceViewModel) {
    PaperPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(FilmSpacing.panel), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("当前批次", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(enabled = !state.busy, onClick = workspace::newProcessingBatch) { Text("新建批次") }
            }
            Text(state.processingBatch?.title ?: if (state.activeTransferBatch != null) "正在建立相机批次" else "导入时建立新批次",
                style = MaterialTheme.typography.bodyMedium)
            Text("继续导入会加入当前批次，导出仅包含本批照片。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.processingBatches.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.processingBatches, key = { it.id }) { batch ->
                    FilterChip(selected = batch.id == state.processingBatchId, enabled = !state.busy,
                        onClick = { workspace.selectProcessingBatch(batch.id) },
                        label = { Text("${batch.title} · ${batch.photos.size} 张") })
                }
            }
        }
    }
}
