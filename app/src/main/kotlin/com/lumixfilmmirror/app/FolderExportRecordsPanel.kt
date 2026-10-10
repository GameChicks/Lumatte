package com.lumixfilmmirror.app

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumixfilmmirror.design.FilmSpacing
import com.lumixfilmmirror.design.PaperPanel
import com.lumixfilmmirror.media.FolderExportRecord
import com.lumixfilmmirror.media.FolderExportStage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun FolderExportRecordsPanel(state: WorkspaceState, workspace: WorkspaceViewModel) {
    val context = LocalContext.current
    var pageIndex by remember { mutableIntStateOf(0) }
    var forgetting by remember { mutableStateOf<FolderExportRecord?>(null) }
    val records = state.folderExports?.records.orEmpty()
    val lastPage = ((records.size - 1).coerceAtLeast(0)) / 20
    val page = pageIndex.coerceAtMost(lastPage)
    val dateFormat = remember { DateTimeFormatter.ofPattern("MM-dd HH:mm") }
    PaperPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(FilmSpacing.panel), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("自选文件夹导出记录", style = MaterialTheme.typography.titleMedium)
            Text("保留每次创建与写入的记录。中断结果需打开核对，恢复记录时不会自动删除文件夹中的照片。",
                style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !state.busy, onClick = workspace::inspectFolderExports) { Text("刷新记录") }
            if (state.folderExports != null && records.isEmpty()) Text("暂无已登记的文件夹导出",
                style = MaterialTheme.typography.bodySmall)
            state.folderExports?.problems?.forEach { problem ->
                Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
            for (record in records.drop(page * 20).take(20)) {
                HorizontalDivider()
                Text(record.sourceName, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val status = when (record.stage) {
                    FolderExportStage.PREPARED -> "创建结果待核对 · 文档地址尚未保存"
                    FolderExportStage.CREATED -> "写入结果待核对 · 可能尚未完整"
                    FolderExportStage.COMMITTED -> "已记录完成写入"
                }
                Text("${dateFormat.format(Instant.ofEpochMilli(record.startedAt).atZone(ZoneId.systemDefault()))} · $status",
                    style = MaterialTheme.typography.labelSmall)
                Text("请求文件名 · ${record.requestedName}", style = MaterialTheme.typography.labelSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row {
                    if (record.documentUri != null) TextButton(enabled = !state.busy, onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(record.documentUri, "image/jpeg")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (_: SecurityException) {
                            workspace.notice("文件夹访问权限已失效，请重新选择文件夹或用系统文件应用核对")
                        } catch (_: Exception) {
                            workspace.notice("无法打开该文档，可能未完整、已移除或没有可用的图片查看应用")
                        }
                    }) { Text("打开核对") }
                    TextButton(enabled = !state.busy, onClick = { forgetting = record }) { Text("移除记录") }
                }
            }
            if (records.isNotEmpty()) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(enabled = !state.busy && page > 0, onClick = { pageIndex = page - 1 }) { Text("上一页") }
                Text("${page + 1} / ${lastPage + 1} · ${records.size} 条", style = MaterialTheme.typography.labelSmall)
                TextButton(enabled = !state.busy && page < lastPage, onClick = { pageIndex = page + 1 }) { Text("下一页") }
            }
            Text("提供者可能调整文件名；没有文档地址的记录，请在文件夹中按请求名称与时间核对。",
                style = MaterialTheme.typography.labelSmall)
        }
    }
    forgetting?.let { record ->
        AlertDialog(onDismissRequest = { forgetting = null }, title = { Text("移除导出记录？") },
            text = { Text("仅移除这条应用内记录，不删除文件夹中的照片，也不将待核对结果标为成功。\n${record.requestedName}") },
            confirmButton = {
                TextButton(enabled = !state.busy, onClick = {
                    forgetting = null
                    workspace.forgetFolderExport(record.id)
                }) { Text("仅移除记录") }
            }, dismissButton = { TextButton(onClick = { forgetting = null }) { Text("保留") } })
    }
}
