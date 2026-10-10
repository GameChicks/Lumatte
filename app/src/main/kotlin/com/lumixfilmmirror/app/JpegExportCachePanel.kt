package com.lumixfilmmirror.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumixfilmmirror.design.FilmSpacing
import com.lumixfilmmirror.design.PaperPanel

@Composable
fun JpegExportCachePanel(state: WorkspaceState, workspace: WorkspaceViewModel) {
    PaperPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(FilmSpacing.panel), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("导出中间文件", style = MaterialTheme.typography.titleMedium)
            Text("整理 JPEG 编码缓存及本应用未发布的相册占位，保留工作台照片和已导出成片。",
                style = MaterialTheme.typography.bodySmall)
            state.exportCache?.let { cache ->
                Text("编码缓存 ${storageBytes(cache.temporaryBytes)} · ${cache.temporaryFiles} 个",
                    style = MaterialTheme.typography.bodySmall)
                Text("未发布 JPG 已知占用 ${storageBytes(cache.pendingBytes)} · ${cache.pendingImages} 个",
                    style = MaterialTheme.typography.bodySmall)
                if (cache.unknownPendingSizes > 0) Text("其中 ${cache.unknownPendingSizes} 个占位的尺寸暂不可读",
                    style = MaterialTheme.typography.labelSmall)
                cache.problems.take(3).forEach { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall) }
                if (cache.problems.size > 3) Text("另有 ${cache.problems.size - 3} 项说明", style = MaterialTheme.typography.labelSmall)
            }
            Row {
                TextButton(enabled = !state.busy, onClick = workspace::inspectExportCache) { Text("查看占用") }
                TextButton(enabled = !state.busy && state.exportCache?.canClean == true, onClick = workspace::clearExportCache) {
                    Text("清理导出中间文件")
                }
            }
            if (state.exportCacheBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("应用恢复时会整理可识别的导出残留；下方记录可协助核对自选文件夹，其中的照片不参与此清理。",
                style = MaterialTheme.typography.labelSmall)
        }
    }
}
