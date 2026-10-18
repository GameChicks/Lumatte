package com.lumixfilmmirror.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumixfilmmirror.design.FilmSpacing
import com.lumixfilmmirror.design.PaperPanel
import java.util.Locale

@Composable
fun TransferCachePanel(camera: CameraWorkspaceState, busy: Boolean, onInspect: () -> Unit, onClean: () -> Unit) {
    PaperPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(FilmSpacing.panel), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("传输缓存", style = MaterialTheme.typography.titleMedium)
            Text("清理已完成或已取消任务的中间文件。保留工作台照片、已保存视频、历史记录，以及待执行、暂停和失败任务。",
                style = MaterialTheme.typography.bodySmall)
            camera.transferCache?.let { cache ->
                Text("中间文件 ${storageBytes(cache.privateBytes)} · 可清理 ${storageBytes(cache.reclaimablePrivateBytes)}",
                    style = MaterialTheme.typography.bodySmall)
                Text("未发布视频 ${storageBytes(cache.pendingVideoBytes)} · 可清理 ${storageBytes(cache.reclaimablePendingVideoBytes)}",
                    style = MaterialTheme.typography.bodySmall)
                if (cache.retainedTasks > 0) Text("${cache.retainedTasks} 项任务的缓存保留", style = MaterialTheme.typography.labelSmall)
                if (cache.problems.isNotEmpty()) {
                    Text("部分缓存无法统计或清理，已保留相关文件", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    cache.problems.take(3).forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
                    if (cache.problems.size > 3) Text("另有 ${cache.problems.size - 3} 项", style = MaterialTheme.typography.labelSmall)
                }
            }
            Row {
                TextButton(enabled = !busy, onClick = onInspect) {
                    Text(if (camera.transferCache == null) "查看占用" else "刷新占用")
                }
                TextButton(enabled = !busy && camera.transferCache?.canClean == true, onClick = onClean) { Text("清理可释放缓存") }
            }
            if (camera.cacheBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("只统计已登记任务的传输中间文件与未发布视频，不包含照片、导出成片或整个应用的存储占用。",
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

internal fun storageBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
