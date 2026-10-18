package com.lumixfilmmirror.app

import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lumixfilmmirror.design.FilmSpacing
import com.lumixfilmmirror.design.PaperPanel
import com.lumixfilmmirror.storage.TransferTask
import com.lumixfilmmirror.storage.completed
import com.lumixfilmmirror.storage.terminal
import com.lumixfilmmirror.storage.expectedMedia
import java.text.DateFormat
import java.util.Date

@Composable
fun TransferQueuePanel(tasks: List<TransferTask>, state: WorkspaceState, workspace: WorkspaceViewModel,
    onResume: (String) -> Unit) {
    val batches = tasks.groupBy { it.batchId }.entries.sortedByDescending { it.value.first().createdAt }
    val context = LocalContext.current
    var showHistory by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val displayed = if (showHistory) batches else batches.filter { (_, photos) -> photos.any { !it.terminal } }
    PaperPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(FilmSpacing.panel), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("传输队列", style = MaterialTheme.typography.titleMedium)
            Text("切到后台后由传输服务继续；暂停或进程结束后保留记录。已下载文件可直接完成导入或保存。",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "隐藏已完成记录" else "显示已完成记录") }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                displayed.forEach { (id, photos) ->
                  item(key = "batch:$id") {
                    val completed = photos.count { it.completed }
                    val imported = photos.count { it.state == "IMPORTED" }
                    val videos = photos.count { it.state == "SAVED" }
                    val failed = photos.count { it.state == "FAILED" }
                    val pending = photos.count { !it.terminal }
                    val active = state.activeTransferBatch == id
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${photos.first().cameraModel} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(photos.first().createdAt))}",
                            style = MaterialTheme.typography.titleSmall)
                        Text("完成 $completed / ${photos.size} 项 · 照片 $imported 张 · 视频 $videos 个 · 失败 $failed 项",
                            style = MaterialTheme.typography.bodySmall)
                        Row {
                            if (pending > 0 && !active) {
                                TextButton(enabled = !state.busy, onClick = { onResume(id) }) { Text("继续／重试") }
                                TextButton(enabled = !state.busy, onClick = { workspace.cancelTransferBatch(id) }) { Text("取消余下任务") }
                            }
                            if (active) TextButton(onClick = workspace::pauseTransfer) { Text("暂停") }
                            TextButton(onClick = { expanded[id] = expanded[id] != true }) { Text(if (expanded[id] == true) "收起明细" else "查看明细") }
                        }
                        HorizontalDivider()
                    }
                  }
                  if (expanded[id] == true) items(photos, key = { "task:${it.id}" }) { photo ->
                      Column {
                          Text("${photo.originalName ?: photo.title} · ${transferLabel(photo.state)}",
                              style = MaterialTheme.typography.bodySmall)
                          photo.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                          photo.cameraAcknowledgement?.let { ack ->
                              Text("相机：${acknowledgementLabel(ack)}", style = MaterialTheme.typography.labelSmall)
                          }
                          if (photo.state == "SAVED" && photo.savedUri != null) TextButton(onClick = {
                              try {
                                  context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(photo.savedUri),
                                      photo.expectedMedia().transferResource?.videoContainer?.mimeType ?: "video/*")
                                      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                              } catch (error: ActivityNotFoundException) { workspace.notice("视频已保存，请使用支持此格式的播放器或系统相册查看") }
                              catch (error: SecurityException) { workspace.notice("视频访问不可用，请在系统相册查看保存的文件") }
                          }) { Text("打开视频") }
                      }
                  }
                }
            }
            if (displayed.isEmpty()) Text("暂无待传任务", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun acknowledgementLabel(state: String): String = when (state) {
    "READY" -> "文件已保存，待核对"
    "IN_FLIGHT" -> "等待确认响应"
    "UNKNOWN" -> "确认响应未知，不自动重发"
    "CONFIRMED" -> "确认成功"
    "ADVANCED" -> "无需单独确认，或原文件已离开发送首项"
    else -> state
}

private fun transferLabel(state: String): String = when (state) {
    "QUEUED" -> "待传输"
    "RUNNING" -> "传输中"
    "DOWNLOADED" -> "已下载，待导入或保存"
    "PUBLISHING" -> "保存视频中"
    "PAUSED" -> "已暂停"
    "FAILED" -> "失败，可重试"
    "IMPORTED" -> "已传入"
    "SAVED" -> "视频已保存"
    "CANCELLED" -> "已取消"
    else -> state
}
