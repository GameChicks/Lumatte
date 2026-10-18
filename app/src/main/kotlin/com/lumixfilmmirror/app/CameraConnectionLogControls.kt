package com.lumixfilmmirror.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumixfilmmirror.camera.CameraConnectionLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Independent of the connection busy flag, so a failed or pending connection is still diagnosable. */
@Composable
internal fun CameraConnectionLogControls() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val log by CameraConnectionLog.state.collectAsStateWithLifecycle()
    var viewing by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var contents by remember { mutableStateOf("") }
    LaunchedEffect(context) { CameraConnectionLog.initialize(context) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val name = pendingExport
        pendingExport = null
        if (uri != null && name != null) scope.launch {
            saving = true
            try {
                val size = CameraConnectionLog.export(context, name, uri)
                notice = "已导出日志（$size 字节），可将 .log 文件发回排查。"
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { notice = "日志未能保存，请重新选择保存位置；原记录仍在应用内。" }
            finally { saving = false }
        }
    }
    fun export(name: String?) {
        if (name == null || saving || pendingExport != null) return
        pendingExport = name
        exporter.launch(name)
    }
    LaunchedEffect(viewing, log.files.map { it.name }) {
        if (viewing && log.files.none { it.name == selected }) selected = log.activeFile ?: log.files.firstOrNull()?.name
    }
    LaunchedEffect(viewing, selected, log.revision) {
        if (viewing) {
            val name = selected
            contents = if (name == null) "还没有连接日志。开始查找或连接相机后将自动记录。" else {
                delay(120)
                try { CameraConnectionLog.read(name).takeLast(60_000) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { "该日志已被清除或轮换，请选择其他记录。" }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("连接日志", style = MaterialTheme.typography.titleSmall)
                Text(if (log.enabled) "自动记录已开启" else "记录已暂停", style = MaterialTheme.typography.labelSmall)
            }
            Switch(checked = log.enabled, enabled = log.ready, onCheckedChange = CameraConnectionLog::setEnabled)
        }
        Text("记录连接步骤和错误，不记录密码、网络名称或身份数据。最多保留 8 份，重启后可查看。",
            style = MaterialTheme.typography.labelSmall)
        FlowRow {
            TextButton(enabled = log.ready, onClick = { viewing = true }) { Text("查看日志") }
            TextButton(enabled = log.files.isNotEmpty() && !saving && pendingExport == null,
                onClick = { export(log.activeFile ?: log.files.firstOrNull()?.name) }) { Text(if (saving) "正在导出" else "导出最新日志") }
            TextButton(enabled = log.files.isNotEmpty() && !saving && pendingExport == null,
                onClick = { scope.launch {
                    try {
                        CameraConnectionLog.clear()
                        notice = "已清除日志；自动记录开启时，后续连接事件会重新保存。"
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { notice = "部分日志未能清除，请稍后重试。" }
                } }) { Text("清除") }
        }
        log.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (viewing) AlertDialog(
        onDismissRequest = { viewing = false },
        title = { Text("连接日志") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                LazyColumn(Modifier.heightIn(max = 130.dp)) {
                    items(log.files, key = { it.name }) { file ->
                        TextButton(onClick = { selected = file.name }) {
                            Text("${if (file.name == selected) "● " else ""}${file.name} · ${file.bytes / 1024 + 1} KB",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                HorizontalDivider()
                SelectionContainer {
                    Text(contents, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp)
                        .verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("窗口显示所选日志末尾；导出保存该份完整记录。", style = MaterialTheme.typography.labelSmall)
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(enabled = selected != null && !saving && pendingExport == null,
            onClick = { export(selected) }) { Text("导出所选日志") } },
        dismissButton = { TextButton(onClick = { viewing = false }) { Text("关闭") } },
    )
}
