package com.lumixfilmmirror.app

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumixfilmmirror.design.FilmSpacing
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.design.PaperPanel
import com.lumixfilmmirror.media.ExportDestination
import com.lumixfilmmirror.model.JpegOutput
import com.lumixfilmmirror.model.LutRecipe
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun WorkspaceScreen(workspace: WorkspaceViewModel) {
    val state by workspace.state.collectAsStateWithLifecycle()
    val camera by workspace.cameraState.collectAsStateWithLifecycle()
    val transfers by workspace.transferTasks.collectAsStateWithLifecycle()
    val incoming by workspace.incomingTransfers.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tones = LocalFilmMaterials.current
    var notificationPrompted by rememberSaveable { mutableStateOf(false) }
    var pendingTransfer by rememberSaveable { mutableStateOf<String?>(null) }
    val executeTransfer: (String) -> Unit = { target ->
        when (target) {
            "import" -> workspace.importCameraPhotos()
            "receive" -> workspace.receiveIncomingPhotos()
            "follow" -> workspace.receiveIncomingPhotos(follow = true)
            else -> workspace.resumeTransferBatch(target)
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) workspace.notice("通知权限关闭，可在工作台查看并暂停传输")
        val target = pendingTransfer
        pendingTransfer = null
        target?.let(executeTransfer)
    }
    val startTransfer: (String) -> Unit = { target ->
        if (Build.VERSION.SDK_INT >= 33 && !notificationPrompted &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPrompted = true
            pendingTransfer = target
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else executeTransfer(target)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { workspace.importImages(it) }
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { workspace.importLuts(it) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                workspace.destination(ExportDestination.Folder(uri))
            } catch (error: SecurityException) { workspace.notice("无法保留文件夹访问权限，请重新选择") }
        }
    }
    Column(Modifier.fillMaxSize().background(tones.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = FilmSpacing.screen, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LUMIX", style = MaterialTheme.typography.titleLarge, color = tones.ink)
                Text("FILM MIRROR", style = MaterialTheme.typography.labelMedium, color = tones.secondaryInk,
                    fontFamily = FontFamily.Monospace)
            }
            TextButton(enabled = !state.busy, onClick = { picker.launch(arrayOf("image/jpeg", "image/x-panasonic-rw2", "image/x-panasonic-raw", "application/octet-stream")) }) {
                Text(if (state.photos.isEmpty()) "导入照片" else "继续导入")
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = FilmSpacing.screen, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(FilmSpacing.gap)) {
            if (state.recovering) item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在打开素材库")
                }
            }
            state.storageFailure?.let { message -> item {
                Column {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    TextButton(enabled = !state.recovering, onClick = workspace::restoreWorkspace) { Text("重新打开") }
                }
            } }
            item { CameraTransferPanel(camera, state.busy, state.cameraBusy, state.activeTransferBatch != null,
                onImport = { startTransfer(if (camera.accessMode.incoming) "receive" else "import") },
                onFollow = { startTransfer("follow") }, workspace = workspace) }
            if (incoming.isNotEmpty()) item { IncomingQueuePanel(incoming, transfers, state, workspace, startTransfer) }
            if (transfers.isNotEmpty()) item { TransferQueuePanel(transfers, state, workspace,
                onResume = startTransfer) }
            if (transfers.isNotEmpty()) item { TransferCachePanel(camera, state.busy,
                onInspect = workspace::inspectTransferCache, onClean = workspace::clearTransferCache) }
            item { ProcessingBatchPanel(state, workspace) }
            item {
                PaperPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(FilmSpacing.panel), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.photos.isEmpty()) {
                            Spacer(Modifier.height(28.dp))
                            Text("把照片带进工作台", style = MaterialTheme.typography.headlineSmall)
                            Text("导入 JPEG，或读取松下 RAW 中的内嵌 JPEG。原始文件保留。",
                                color = tones.secondaryInk, style = MaterialTheme.typography.bodyMedium)
                            Button(enabled = !state.busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text("选择照片") }
                            Spacer(Modifier.height(28.dp))
                        } else {
                            PhotoPreview(state, workspace)
                            state.current?.let { photo ->
                                Text(photo.name, style = MaterialTheme.typography.titleSmall)
                                Text("${photo.size.width} × ${photo.size.height}", fontFamily = FontFamily.Monospace,
                                    color = tones.secondaryInk, style = MaterialTheme.typography.bodySmall)
                                photo.rawNotice?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = tones.secondaryInk) }
                            }
                            Text("${state.photos.indexOfFirst { it.id == state.selected } + 1} / ${state.photos.size}",
                                color = tones.secondaryInk, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            if (state.photos.size > 1) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.photos, key = { it.id.value }) { photo ->
                        FilterChip(selected = photo.id == state.selected, onClick = { workspace.select(photo.id) },
                            label = { Text("${state.photos.indexOf(photo) + 1} · ${photo.name.take(18)}") })
                    }
                }
            }
            if (state.photos.isNotEmpty()) item {
                LutEditorPanel(state, workspace, onImport = { lutPicker.launch(arrayOf("*/*")) })
            }
            if (state.photos.isNotEmpty()) item {
                ExportSettingsPanel(state, workspace::output, onFolder = { folder.launch(null) },
                    onGallery = { workspace.destination(ExportDestination.Gallery) })
            }
            item { JpegExportCachePanel(state, workspace) }
            item { FolderExportRecordsPanel(state, workspace) }
            if (state.importing) item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在导入 · 已读取 ${state.photos.size} 张", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.exporting) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { state.completed.toFloat() / state.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${state.completed} / ${state.total} 张", Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                        TextButton(onClick = workspace::cancelExport) { Text("取消后续导出") }
                    }
                }
            }
            state.notice?.let { notice -> item { Text(notice, color = tones.secondaryInk) } }
            items(state.failures) { failed ->
                Text("${failed.name}：${failed.reason}", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (!state.busy && state.failures.any { it.id != null }) item {
                TextButton(onClick = { workspace.export(retryOnly = true) }) { Text("仅重试导出失败的照片") }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
        if (state.photos.isNotEmpty()) {
            Surface(color = tones.paper, shadowElevation = 4.dp) {
                Button(onClick = { workspace.export() }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = FilmSpacing.screen, vertical = 12.dp)
                        .heightIn(min = FilmSpacing.controlMinimum), shape = RoundedCornerShape(16.dp)) {
                    Text("导出这一批 · ${state.photos.size} 张")
                }
            }
        }
    }
    camera.wifiSelection?.let { CameraWifiSelectionDialog(it, workspace) }
}

@Composable
private fun PhotoPreview(state: WorkspaceState, workspace: WorkspaceViewModel) {
    if(state.current==null)return
    SwipePhotoPreview(state,workspace,compactHeight=220.dp)
    Text("长按查看原图 · 松手恢复效果",style=MaterialTheme.typography.bodySmall)
    PhotoManagementActions(state,workspace)
}
@Composable
private fun ExportSettingsPanel(state: WorkspaceState, onChange: (JpegOutput) -> Unit, onFolder: () -> Unit, onGallery: () -> Unit) {
    val tones = LocalFilmMaterials.current
    PaperPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(FilmSpacing.panel), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("JPG 导出", style = MaterialTheme.typography.titleMedium)
            Text("设置应用到这一批的全部照片", color = tones.secondaryInk, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth()) {
                Text("照片尺寸", Modifier.weight(1f))
                Text("${state.output.dimensionPercent}%", fontFamily = FontFamily.Monospace)
            }
            Slider(value = state.output.dimensionPercent.toFloat(), valueRange = 1f..100f, steps = 98,
                enabled = !state.busy, onValueChange = { onChange(state.output.copy(dimensionPercent = it.roundToInt())) })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (percent in listOf(25, 50, 75, 100)) FilterChip(selected = state.output.dimensionPercent == percent,
                    enabled = !state.busy, onClick = { onChange(state.output.copy(dimensionPercent = percent)) }, label = { Text("$percent%") })
            }
            state.current?.let { photo ->
                val size = state.output.contentSize(photo.size)
                Text("照片内容 ${size.width} × ${size.height} · 无画框导出 ${size.width} × ${size.height}",
                    color = tones.secondaryInk, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = tones.rim)
            Row(Modifier.fillMaxWidth()) {
                Text("JPG 压缩质量", Modifier.weight(1f))
                Text("${state.output.compressionQuality}", fontFamily = FontFamily.Monospace)
            }
            Slider(value = state.output.compressionQuality.toFloat(), valueRange = 1f..100f, steps = 98,
                enabled = !state.busy, onValueChange = { onChange(state.output.copy(compressionQuality = it.roundToInt())) })
            Text("压缩质量不改变像素尺寸", color = tones.secondaryInk, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = tones.rim)
            Text(if (state.destination is ExportDestination.Gallery) "保存到 Pictures/LumixFilmMirror" else "保存到所选文件夹",
                color = tones.secondaryInk, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(enabled = !state.busy, onClick = onFolder) { Text("选择文件夹") }
                if (state.destination !is ExportDestination.Gallery)
                    TextButton(enabled = !state.busy, onClick = onGallery) { Text("恢复默认相册") }
            }
        }
    }
}
