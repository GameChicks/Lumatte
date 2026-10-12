package com.lumixfilmmirror.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.media.FilmPreset
import com.lumixfilmmirror.media.LutAsset
import com.lumixfilmmirror.media.ExportDestination
import com.lumixfilmmirror.model.*

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MirrorScreen(workspace: WorkspaceViewModel) {
    val state by workspace.state.collectAsStateWithLifecycle()
    val camera by workspace.cameraState.collectAsStateWithLifecycle()
    val personal by workspace.personal.collectAsStateWithLifecycle()
    val transfers by workspace.transferTasks.collectAsStateWithLifecycle()
    val incoming by workspace.incomingTransfers.collectAsStateWithLifecycle()
    val tones = LocalFilmMaterials.current
    val context = LocalContext.current
    val savedPages = rememberSaveableStateHolder()
    val backdrop = androidx.compose.ui.graphics.rememberGraphicsLayer()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pane by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraEntry by rememberSaveable { mutableStateOf("bluetooth") }
    var requestedCameraId by rememberSaveable { mutableStateOf<String?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    var transferHidden by rememberSaveable { mutableStateOf(false) }
    var transferChoice by rememberSaveable { mutableStateOf<String?>(null) }
    var transferDestination by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedImportTreatment by rememberSaveable { mutableStateOf<String?>(null) }
    var importAccepted by rememberSaveable { mutableStateOf(false) }
    var importRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var importCreatesBatch by rememberSaveable { mutableStateOf(true) }
    var importSources by rememberSaveable { mutableStateOf(false) }
    var pendingFilm by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingLut by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingLutSecond by rememberSaveable { mutableStateOf(false) }
    var editingLutSecond by rememberSaveable { mutableStateOf(false) }
    var transferBatch by rememberSaveable { mutableStateOf<String?>(null) }
    var noticedTransferStart by rememberSaveable { mutableStateOf(false) }
    var notificationAction by rememberSaveable { mutableStateOf<String?>(null) }
    val executeTransfer: (String) -> Unit = { target -> when (target) {
        "import" -> workspace.importCameraPhotos(); "receive" -> workspace.receiveIncomingPhotos()
        "follow" -> workspace.receiveIncomingPhotos(follow = true); else -> workspace.resumeTransferBatch(target)
    } }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationAction?.let(executeTransfer); notificationAction = null
    }
    val startTransfer: (String) -> Unit = { target ->
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationAction = target; notification.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else executeTransfer(target)
    }
    val receivePhotos: (List<android.net.Uri>) -> Unit = { uris ->
        val request = importRequest
        if (uris.isNotEmpty() && request != null) {
            importAccepted = workspace.importImages(uris, newBatch = importCreatesBatch, requestId = request)
            if (!importAccepted) {
                selectedImportTreatment = null; pendingFilm = null; pendingLut = null; importRequest = null
                workspace.notice("当前任务正在执行，待完成后重新导入照片")
            }
        } else { selectedImportTreatment = null; pendingFilm = null; pendingLut = null; importRequest = null; importAccepted = false }
    }
    val photos = rememberLauncherForActivityResult(MirrorPhotoPicker(), receivePhotos)
    val rawPhotos = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), receivePhotos)
    val luts = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> workspace.importLuts(uris) }
    val importPhotos: (PhotoTreatment) -> Unit = { treatment ->
        if (!state.busy) {
            importAccepted = false
            importRequest = java.util.UUID.randomUUID().toString()
            importCreatesBatch = pane != "editor" && pane != "export"
            selectedImportTreatment = treatment.name
            importSources = true
        }
    }
    val chooseFilm: (FilmPreset) -> Unit = { film ->
        if (!state.busy) {
            if (state.photos.isEmpty()) { pendingFilm = film.id; importPhotos(PhotoTreatment.FILM) }
            else {
                workspace.treatment(PhotoTreatment.FILM)
                val batch = workspace.state.value.filmBatch
                val current = workspace.state.value.selected?.let { batch?.effectiveParameters(it) } ?: FilmParameters()
                workspace.updateFilm(current.copy(presetId = film.id)); pane = "editor"
            }
        }
    }
    val chooseLut: (LutAsset, Boolean) -> Unit = { asset, second ->
        if (!state.busy) {
            if (state.photos.isEmpty()) {
                pendingLut = asset.id; pendingLutSecond = second; importPhotos(PhotoTreatment.LUT)
            } else {
                workspace.treatment(PhotoTreatment.LUT)
                workspace.selectLutSlot(asset.id, second); pane = "editor"
            }
        }
    }
    LaunchedEffect(state.photoImportResult, state.processingBatchId, state.photos, state.busy, selectedImportTreatment, importAccepted) {
        val result = state.photoImportResult
        if (importAccepted && !state.busy && result == null) {
            selectedImportTreatment = null; pendingFilm = null; pendingLut = null; importAccepted = false; importRequest = null
            workspace.notice("本次导入已结束，请重新选择照片")
            return@LaunchedEffect
        }
        if (importAccepted && !state.busy && selectedImportTreatment != null && result != null && result.requestId == importRequest) {
            if (result.imported.isEmpty()) {
                selectedImportTreatment = null; pendingFilm = null; pendingLut = null; importAccepted = false; importRequest = null
                workspace.notice((listOf("本次没有成功导入照片") + state.failures.take(2).map { "${it.name}：${it.reason}" }).joinToString("\n"))
                return@LaunchedEffect
            }
            // Room may publish the new batch after the import coroutine finishes.
            if (state.processingBatchId != result.batchId || result.imported.any { id -> state.photos.none { it.id == id } })
                return@LaunchedEffect
            val proceedToEditor = pendingFilm != null || pendingLut != null
            val treatment = PhotoTreatment.valueOf(requireNotNull(selectedImportTreatment))
            workspace.treatment(treatment)
            pendingFilm?.let { workspace.updateFilm(FilmParameters(presetId = it)) }
            pendingLut?.let { workspace.selectLutSlot(it, pendingLutSecond) }
            selectedImportTreatment = null; pendingFilm = null; pendingLut = null; importAccepted = false; importRequest = null
            // Import from a tab stays in selection. A chosen stock/LUT proceeds to its preview.
            if (proceedToEditor) pane = "editor"
        }
    }
    LaunchedEffect(camera.activeTransferBatch) {
        camera.activeTransferBatch?.let { id ->
            transferHidden = false
            if (transferDestination != null) { transferBatch = id; noticedTransferStart = true }
        }
    }
    LaunchedEffect(camera.problem, camera.operationRunning, camera.activeTransferBatch) {
        if (transferDestination != null && !noticedTransferStart && camera.activeTransferBatch == null &&
            !camera.operationRunning && camera.problem != null) transferDestination = null
    }
    LaunchedEffect(camera.activeTransferBatch, state.busy, state.processingBatchId, noticedTransferStart, transfers) {
        val id = transferBatch
        if (noticedTransferStart && camera.activeTransferBatch == null && !state.busy && id != null) {
            val tasks = transfers.filter { it.batchId == id }
            if (tasks.isEmpty()) return@LaunchedEffect
            if (tasks.any { it.state !in setOf("IMPORTED", "SAVED", "CANCELLED") }) return@LaunchedEffect
            val destination = transferDestination
            transferDestination = null; noticedTransferStart = false; transferBatch = null
            if (state.photos.isNotEmpty() && state.processingBatchId == id) {
                when (destination) {
                    "phone" -> {
                        workspace.treatment(PhotoTreatment.ORIGINAL)
                        workspace.updateFrame(FrameOptions())
                        workspace.output(JpegOutput(100, 100))
                        workspace.destination(ExportDestination.Gallery)
                        workspace.export()
                    }
                    "film" -> { workspace.treatment(PhotoTreatment.FILM); tab = 1; pane = null }
                    "lut" -> { workspace.treatment(PhotoTreatment.LUT); tab = 2; pane = null }
                }
            }
        }
    }
    val reduced = personal.reducedMotion || systemMotionReduced()
    val navigation = pane == null && !fullscreen
    BackHandler(pane != null) {
        pane = when (pane) { "export" -> "editor"; "reveal" -> "editor"; else -> null }
    }
    CompositionLocalProvider(LocalMirrorReducedMotion provides reduced) {
        Box(Modifier.fillMaxSize().background(tones.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            ProgressiveBackdrop(Modifier.fillMaxSize(), enabled = navigation && !reduced, source = backdrop) {
                AnimatedContent(targetState = pane, label = "workspace-card-entry", transitionSpec = {
                    (fadeIn(tween(if (reduced) 0 else 300)) + scaleIn(tween(if (reduced) 0 else 420, easing = MirrorEase), initialScale = .97f)) togetherWith
                        (fadeOut(tween(if (reduced) 0 else 160)) + scaleOut(tween(if (reduced) 0 else 300), targetScale = 1.015f))
                }) { scene -> when (scene) {
                    "camera" -> Column(Modifier.fillMaxSize()) {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            RoundAction("返回连接页", DotGlyph.LEFT, onClick = { pane = null })
                            Text("相机与相册", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
                        }
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            item { CameraTransferPanel(camera, state.busy, state.cameraBusy, state.activeTransferBatch != null,
                                onImport = { transferChoice = if (camera.accessMode.incoming) "receive" else "import" },
                                onFollow = { startTransfer("follow") }, workspace, initialMethod = cameraEntry,
                                requestedCamera = personal.cameras.firstOrNull { it.id == requestedCameraId }) }
                            if (incoming.isNotEmpty()) item { IncomingQueuePanel(incoming, transfers, state, workspace, startTransfer) }
                            if (transfers.isNotEmpty()) item { TransferQueuePanel(transfers, state, workspace, startTransfer) }
                        }
                    }
                    "editor" -> MirrorEditor(state, workspace, onBack = { pane = null },
                        onImport = { importPhotos(state.treatment) }, onChooseLut = { second -> editingLutSecond = second; tab = 2; pane = null },
                        onChooseFilm = { tab = 1; pane = null }, onNext = {
                            val id = state.processingBatchId
                            if (state.treatment == PhotoTreatment.FILM && id != null && id !in personal.revealedBatches) pane = "reveal"
                            else pane = "export"
                        })
                    "reveal" -> FilmReveal(state, workspace) {
                        state.processingBatchId?.let { id -> workspace.updatePersonal { it.copy(revealedBatches = it.revealedBatches + id) } }
                        pane = "export"
                    }
                    "export" -> MirrorFrames(state, workspace, { importPhotos(state.treatment) }, onBack = { pane = "editor" })
                    "tools" -> Column {
                        TextButton(onClick = { pane = null }) { Text("← 返回连接页") }
                        Box(Modifier.weight(1f)) { WorkspaceScreen(workspace) }
                    }
                    else -> savedPages.SaveableStateProvider(tab) {
                        when (tab) {
                            0 -> MirrorHome(state, camera, personal, transfers, workspace,
                                onCamera = { id -> requestedCameraId = id; cameraEntry = "bluetooth"; pane = "camera" },
                                onAlbum = { requestedCameraId = null; cameraEntry = "wifi"; pane = "camera" }, onTools = { pane = "tools" })
                            1 -> MirrorFilm(state, { importPhotos(PhotoTreatment.FILM) }, chooseFilm) { fullscreen = it }
                            2 -> MirrorLuts(state, workspace, personal, { importPhotos(PhotoTreatment.LUT) },
                                { luts.launch(arrayOf("*/*")) }, chooseLut, initialSecond = editingLutSecond) { fullscreen = it }
                            else -> Box(Modifier.padding(bottom = 100.dp)) {
                                MirrorFrames(state, workspace, { importPhotos(PhotoTreatment.ORIGINAL) })
                            }
                        }
                    }
                } }
            }
            if (navigation) MirrorNavigation(tab, Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 16.dp), backdrop) {
                tab = it; fullscreen = false
            }
            val active = camera.activeTransferBatch != null || state.exporting
            AnimatedVisibility(active && !transferHidden, modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 20.dp, vertical = 8.dp)) {
                Surface(shape = RoundedCornerShape(20.dp), color = tones.paperHighlight, tonalElevation = 4.dp, shadowElevation = 8.dp) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (state.exporting) "正在保存 ${state.completed} / ${state.total}" else
                                "正在传入 ${camera.transferred} / ${camera.transferTotal}", Modifier.weight(1f))
                            TextButton(onClick = { transferHidden = true }) { Text("隐藏") }
                        }
                        if (state.exporting && state.total > 0) LinearProgressIndicator(
                            progress = { state.completed.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = if (state.exporting) workspace::pauseExport else workspace::pauseTransfer) { Text("暂停") }
                    }
                }
            }
            if (active && transferHidden) TextButton(onClick = { transferHidden = false },
                modifier = Modifier.align(Alignment.TopEnd).background(tones.paperHighlight, RoundedCornerShape(24.dp))) { Text("显示进度") }
            if (state.recovering) Surface(Modifier.align(Alignment.TopCenter), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp)); Text("正在打开素材库…")
                }
            }
            state.storageFailure?.let { failure -> AlertDialog(onDismissRequest = {}, title = { Text("工作台暂不可用") },
                text = { Text(failure) }, confirmButton = { TextButton(onClick = workspace::restoreWorkspace) { Text("重试恢复") } }) }
            // Operational notices remain visible and dismissible in the formal interface.
            if (state.notice != null && !state.recovering) Snackbar(Modifier.align(Alignment.BottomCenter)
                .padding(start = 20.dp, end = 20.dp, bottom = if (navigation) 110.dp else 12.dp), action = {
                TextButton(onClick = { workspace.clearNotice() }) { Text("知道了") }
            }) { Text(state.notice.orEmpty(), maxLines = 3) }
            if (pane != "tools") camera.wifiSelection?.let { CameraWifiSelectionDialog(it, workspace) }
        }
    }
    transferChoice?.let { target -> AlertDialog(onDismissRequest = { transferChoice = null }, title = { Text("传入后做什么？") },
        text = { Column {
            TextButton(onClick = { transferDestination = "phone"; transferChoice = null; startTransfer(target) }) { Text("仅导入手机") }
            TextButton(onClick = { transferDestination = "film"; transferChoice = null; startTransfer(target) }) { Text("进入 Film 编辑") }
            TextButton(onClick = { transferDestination = "lut"; transferChoice = null; startTransfer(target) }) { Text("进入 LUT 编辑") }
            Text("视频保持原文件保存到相册。", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = { transferChoice = null }) { Text("取消") } }) }
    if(importSources) ModalBottomSheet(onDismissRequest = {
        importSources=false; importRequest=null; selectedImportTreatment=null; pendingFilm=null; pendingLut=null
    }, containerColor = tones.paper) {
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("从哪里导入？",fontFamily=androidx.compose.ui.text.font.FontFamily.Serif,style=MaterialTheme.typography.headlineSmall)
            MirrorButton(onClick={importSources=false;photos.launch(Unit)},Modifier.fillMaxWidth().height(56.dp)) { Text("系统相册 · 多选照片") }
            OutlinedButton(onClick={importSources=false;rawPhotos.launch(arrayOf("image/jpeg","image/x-panasonic-rw2","image/x-panasonic-raw","application/octet-stream"))},
                Modifier.fillMaxWidth().height(56.dp)) { Text("从文件导入 JPEG / RAW") }
            Text("RAW 仅提取内嵌 JPEG。",style=MaterialTheme.typography.bodySmall,color=tones.secondaryInk)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MirrorNavigation(selected: Int, modifier: Modifier,
    backdrop: androidx.compose.ui.graphics.layer.GraphicsLayer, onSelect: (Int) -> Unit) {
    val tones = LocalFilmMaterials.current
    val shape = RoundedCornerShape(38.dp)
    val dark = isSystemInDarkTheme()
    val position by animateFloatAsState(selected.toFloat(), tween(if (LocalMirrorReducedMotion.current) 0 else 380, easing = MirrorEase), label = "nav-position")
    BoxWithConstraints(modifier.fillMaxWidth().height(76.dp).clip(shape)
        .frostedBackdrop(backdrop, !LocalMirrorReducedMotion.current)
        .background(if (dark) Color(0xFF0F1920).copy(.45f) else Color.White.copy(.42f))
        .border(1.dp, if (dark) Color.White.copy(.24f) else Color.White.copy(.75f), shape)) {
        val cell = (maxWidth - 12.dp) / 4
        val accent = if (selected == 1 || selected == 3) tones.amber else Color(0xFF94D3ED)
        Box(Modifier.offset(x = 6.dp + cell * position, y = 6.dp).width(cell).height(64.dp).clip(RoundedCornerShape(32.dp))
            .background(Brush.verticalGradient(if (dark) listOf(accent.copy(.35f), Color(0xFF12232F).copy(.78f)) else listOf(tones.ink, tones.ink.copy(.86f))))
            .border(.8.dp, if (dark) accent.copy(.48f) else Color.Transparent, RoundedCornerShape(32.dp)))
    Row(Modifier.fillMaxSize().padding(6.dp)) {
        val names = listOf("连接", "Film", "LUT", "水印")
        val icons = listOf(DotGlyph.CAMERA, DotGlyph.FILM, DotGlyph.LUT, DotGlyph.FRAME)
        names.forEachIndexed { index, label ->
            val active = selected == index
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(32.dp))
                .clickable(role = Role.Tab) { onSelect(index) }
                .semantics { this.selected = active; contentDescription = label },
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                DotIcon(icons[index], tint = if (active) if (dark) Color.White else tones.paper else tones.secondaryInk)
                Text(label, color = if (active) if (dark) Color.White else tones.paper else tones.secondaryInk, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    }
}
