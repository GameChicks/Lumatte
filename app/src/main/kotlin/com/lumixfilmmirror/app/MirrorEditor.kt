package com.lumixfilmmirror.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.design.MirrorFonts
import com.lumixfilmmirror.media.ExportDestination
import com.lumixfilmmirror.media.ProcessingCatalogue
import com.lumixfilmmirror.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun MirrorEditor(state: WorkspaceState, workspace: WorkspaceViewModel, onBack: () -> Unit,
    onImport: () -> Unit, onChooseLut: (Boolean) -> Unit, onChooseFilm: () -> Unit, onNext: () -> Unit) {
    val tones = LocalFilmMaterials.current
    var original by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundAction("返回选型", DotGlyph.LEFT, onClick = onBack)
            Text(if (state.treatment == PhotoTreatment.FILM) "Film / 预览" else "LUT / 预览", Modifier.weight(1f).padding(start = 12.dp),
                style = MaterialTheme.typography.titleLarge)
            TextButton(enabled = !state.busy, onClick = onImport) { Text("导入") }
        }
        if (state.current == null) EmptyImport("一张照片，一种新的可能。", "支持多选 JPEG 与 RAW 内嵌 JPEG。", onImport)
        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { EditedPreview(state, workspace, original = original) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (original) "原图" else "处理效果", Modifier.weight(1f), color = tones.secondaryInk)
                    Switch(original, { original = it })
                }
                PhotoStrip(state, workspace)
                state.current?.rawNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tones.secondaryInk) }
            }
            if (state.failures.any { it.id == null }) item {
                Text("部分照片未导入", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                state.failures.filter { it.id == null }.forEach { failure ->
                    Text("${failure.name}：${failure.reason}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.treatment == PhotoTreatment.FILM) item { FilmControls(state, workspace, onChooseFilm) }
            else if (state.treatment == PhotoTreatment.LUT) item { LutControls(state, workspace, onChooseLut) }
            item { OutputControls(state, workspace) }
        }
        MirrorButton(enabled = state.current != null && !state.busy, onClick = onNext,
            modifier = Modifier.fillMaxWidth().padding(20.dp).height(56.dp), shape = RoundedCornerShape(28.dp)) {
            Text(if (state.treatment == PhotoTreatment.FILM) "显影与反转 →" else "选择画框与导出 →")
        }
    }
}

@Composable
internal fun EditedPreview(state: WorkspaceState, workspace: WorkspaceViewModel, frame: Boolean = false,
    original: Boolean = false, colourFilter: ColorFilter? = null, onReady: ((Bitmap) -> Unit)? = null, compactHeight: Dp? = null) {
    val source = state.current ?: return
    val recipe = if (original) PhotoRecipe.Original else runCatching { workspace.photoRecipe(state, source.id) }.getOrNull()
    val options = state.exportOptions
    val frameNumber = state.photos.indexOfFirst { it.id == source.id } + 1
    val previewKey = listOf("editor", source.id, recipe, if (frame) options else null, if (frame) frameNumber else null)
    var bitmap by remember(source.id, frame) { mutableStateOf(workspace.displayPreviews.peek(previewKey)?.image) }
    var problem by remember(source.id, frame) { mutableStateOf<String?>(null) }
    var loading by remember(source.id, frame) { mutableStateOf(true) }
    var retry by rememberSaveable(source.id.value, frame) { mutableIntStateOf(0) }
    LaunchedEffect(source.id, recipe, if (frame) state.frame else null, if (frame) state.output else null, state.luts, retry) {
        loading = workspace.displayPreviews.peek(previewKey) == null; problem = null
        try {
            if (loading) delay(160)
            val frozenRecipe = recipe ?: error("照片配方不可用")
            val result = workspace.displayPreviews.load(previewKey, keepLoading = false) {
                val catalogue = ProcessingCatalogue(state.luts, state.filmPresets, emptyList())
                MirrorPreview(if (frame) workspace.processor.previewExport(source, frozenRecipe, catalogue, options, longEdge = 960, frameNumber = frameNumber)
                    else workspace.processor.preview(source, frozenRecipe, catalogue, longEdge = 960))
            }.image
            bitmap = result; onReady?.invoke(result)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { bitmap = null; problem = error.message ?: "预览暂不可用" }
        catch (_: OutOfMemoryError) { bitmap = null; problem = "预览内存不足，可稍后重试" }
        finally { loading = false }
    }
    val ratio = if (frame) runCatching { workspace.exportLayout(source.id).canvas.let { it.width.toFloat() / it.height } }.getOrDefault(1f)
        else source.size.width.toFloat() / source.size.height
    val previewModifier = Modifier.fillMaxWidth().let { if(compactHeight != null) it.height(compactHeight) else it.aspectRatio(ratio.coerceIn(.65f,1.8f)) }
    Box(previewModifier.background(LocalFilmMaterials.current.paperHighlight),
        contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), source.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, colorFilter = colourFilter) }
        if (loading) CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
        if (problem != null) Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(problem.orEmpty(), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { retry++ }) { Text("重新加载预览") }
        }
    }
}

@Composable
internal fun PhotoStrip(state: WorkspaceState, workspace: WorkspaceViewModel) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.photos, key = { it.id.value }) { photo ->
            MirrorChoice(state.selected == photo.id, { workspace.select(photo.id) }, label = { Text(photo.name, maxLines = 1) })
        }
    }
}

@Composable
private fun FilmControls(state: WorkspaceState, workspace: WorkspaceViewModel, onChooseFilm: () -> Unit) {
    val batch = state.filmBatch ?: return
    val parameters = state.selected?.let { id -> runCatching { batch.effectiveParameters(id) }.getOrNull() } ?: return
    val film = state.filmPresets.firstOrNull { it.id == parameters.presetId }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = !state.busy, onClick = onChooseFilm) { Text("${film?.name ?: "选择胶卷"} · 更换") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MirrorChoice(batch.mode == FilmBatchMode.INDIVIDUAL, { workspace.filmMode(FilmBatchMode.INDIVIDUAL) }, label = { Text("逐张独立") })
            MirrorChoice(batch.mode == FilmBatchMode.FIRST_PHOTO, { workspace.filmMode(FilmBatchMode.FIRST_PHOTO) }, label = { Text("继承首张") })
        }
        if (batch.mode == FilmBatchMode.FIRST_PHOTO) Text("整批继承基准照片：${state.photos.firstOrNull { it.id == batch.referenceId }?.name ?: "照片缺失"}",
            style = MaterialTheme.typography.bodySmall, color = LocalFilmMaterials.current.secondaryInk)
        ParameterSlider("曝光", "${"%.1f".format(parameters.exposureEv)} EV", parameters.exposureEv, -3f..3f,
            !state.busy) { workspace.updateFilm(parameters.copy(exposureEv = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Halation 光晕", Modifier.weight(1f))
            Switch(parameters.halationEnabled, { workspace.updateFilm(parameters.copy(halationEnabled = it)) }, enabled = !state.busy)
        }
        Text("黑柔")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(DiffusionGrade.entries) { grade -> MirrorChoice(parameters.diffusion == grade,
                { workspace.updateFilm(parameters.copy(diffusion = grade)) }, enabled = !state.busy,
                label = { Text(when (grade) { DiffusionGrade.OFF -> "关闭"; DiffusionGrade.EIGHTH -> "1/8";
                    DiffusionGrade.QUARTER -> "1/4"; DiffusionGrade.HALF -> "1/2" }) }) }
        }
    }
}

@Composable
private fun LutControls(state: WorkspaceState, workspace: WorkspaceViewModel, onChooseLut: (Boolean) -> Unit) {
    val recipe = state.lutRecipe
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (second in listOf(false, true)) {
            val slot = if (second) recipe.second else recipe.first
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LUT ${if (second) 2 else 1}", Modifier.weight(1f))
                TextButton(onClick = { onChooseLut(second) }, enabled = !state.busy) {
                    Text(state.luts.firstOrNull { it.id == slot.id }?.name ?: "添加 LUT")
                }
                if (slot.id != null) IconButton(enabled = !state.busy, onClick = {
                    workspace.updateLut(if (second) recipe.copy(second = LutSlot()) else recipe.copy(first = LutSlot()))
                }) { DotIcon(DotGlyph.CLOSE) }
            }
            ParameterSlider("强度", "${(slot.strength * 100).roundToInt()}%", slot.strength, 0f..1f,
                !state.busy && slot.id != null) { strength -> workspace.updateLut(if (second)
                    recipe.copy(second = slot.copy(strength = strength)) else recipe.copy(first = slot.copy(strength = strength))) }
        }
        TextButton(enabled = !state.busy, onClick = { workspace.updateLut(recipe.copy(first = recipe.second, second = recipe.first)) }) {
            Text("交换 LUT 1 → LUT 2 的顺序")
        }
        ParameterSlider("颗粒", "${(recipe.grain * 100).roundToInt()}%", recipe.grain, 0f..1f, !state.busy) {
            workspace.updateLut(recipe.copy(grain = it))
        }
    }
}

@Composable
internal fun OutputControls(state: WorkspaceState, workspace: WorkspaceViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("JPEG 输出", style = MaterialTheme.typography.titleMedium)
        ParameterSlider("照片尺寸", "${state.output.dimensionPercent}%", state.output.dimensionPercent.toFloat(), 1f..100f,
            !state.busy, steps = 98) { workspace.output(state.output.copy(dimensionPercent = it.roundToInt())) }
        ParameterSlider("JPEG 压缩质量", "${state.output.compressionQuality}", state.output.compressionQuality.toFloat(), 1f..100f,
            !state.busy, steps = 98) { workspace.output(state.output.copy(compressionQuality = it.roundToInt())) }
        state.current?.let { photo -> Text(state.output.contentSize(photo.size).let { "照片有效尺寸 ${it.width} × ${it.height}，画框会扩展画布。" },
            style = MaterialTheme.typography.bodySmall, color = LocalFilmMaterials.current.secondaryInk) }
        state.current?.let { photo ->
            val canvas = workspace.exportLayout(photo.id).canvas
            Text("最终画布 ${canvas.width} × ${canvas.height}", style = MaterialTheme.typography.bodySmall,
                color = LocalFilmMaterials.current.secondaryInk)
        }
    }
}

@Composable
private fun ParameterSlider(title: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>,
    enabled: Boolean, steps: Int = 0, onValue: (Float) -> Unit) {
    Row { Text(title, Modifier.weight(1f)); Text(valueLabel) }
    Slider(value, onValue, enabled = enabled, valueRange = range, steps = steps)
}

@Composable
internal fun FilmReveal(state: WorkspaceState, workspace: WorkspaceViewModel, onFinish: () -> Unit) {
    var ready by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("准备显影预览") }
    val progress = remember { Animatable(0f) }
    val reduced = LocalMirrorReducedMotion.current
    val film = state.selected?.let { id -> runCatching { workspace.filmPlan(state, id).preset }.getOrNull() }
    val negative = film?.positive == false
    LaunchedEffect(ready) {
        if (ready) {
            label = "显影"; delay(if (reduced) 0 else 650)
            label = if (negative) "反转 · 让色彩显现" else "色彩显现"
            progress.animateTo(1f, tween(if (reduced) 0 else 1600, easing = MirrorEase))
            delay(if (reduced) 0 else 180); onFinish()
        }
    }
    val p = if (!negative) 1f else progress.value
    val a = 2f * p - 1f
    val b = (1f - p) * 255f
    val matrix = ColorMatrix(floatArrayOf(a, 0f, 0f, 0f, b, 0f, a, 0f, 0f, b, 0f, 0f, a, 0f, b, 0f, 0f, 0f, 1f, 0f))
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Film / $label", Modifier.weight(1f), fontFamily = MirrorFonts.Editorial, fontSize = 26.sp)
            TextButton(onClick = onFinish) { Text("跳过") }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            EditedPreview(state, workspace, colourFilter = ColorFilter.colorMatrix(matrix), onReady = { ready = true })
        }
        Text("${state.photos.size} 张照片 · 一次显影", color = LocalFilmMaterials.current.secondaryInk)
        Text("效果预览完成后选择画框，确认导出才会保存成片。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun MirrorFrames(state: WorkspaceState, workspace: WorkspaceViewModel, onImport: () -> Unit,
    onBack: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try {
            context.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            workspace.destination(ExportDestination.Folder(uri))
        } catch (_: SecurityException) { workspace.notice("文件夹权限未保留，请重新选择") }
    }
    val art = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try { val key = workspace.processor.importFrameImage(uri); workspace.updateFrame(state.frame.copy(imageKey = key)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { workspace.notice(e.message ?: "装饰图未导入") }
        }
    }
    var fontTarget by rememberSaveable { mutableStateOf("signature") }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val key = workspace.processor.importFrameFont(uri)
                val frame = state.frame
                workspace.updateFrame(when (fontTarget) { "title" -> frame.copy(titleFontKey = key)
                    "subtitle" -> frame.copy(subtitleFontKey = key); "footer" -> frame.copy(footerFontKey = key)
                    else -> frame.copy(signatureFontKey = key) })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { workspace.notice(e.message ?: "字体未导入") }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            onBack?.let { RoundAction("返回预览", DotGlyph.LEFT, onClick = it) }
            Text("水印 / Frame", Modifier.weight(1f).padding(start = if (onBack != null) 12.dp else 0.dp),
                fontFamily = MirrorFonts.ChineseEditorial, fontSize = 30.sp)
            TextButton(enabled = !state.busy, onClick = onImport) { Text("导入") }
        }
        if (state.current == null) Box(Modifier.weight(1f).fillMaxWidth()) {
            EmptyImport("给照片一个归属。", "导入照片后选择画框与签名。", onImport)
        }
        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { EditedPreview(state, workspace, frame = true, compactHeight = 218.dp) }
            item {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("选择画框",Modifier.weight(1f),fontFamily=MirrorFonts.ChineseEditorial,fontSize=24.sp)
                    Text("左右滑动 · ${FramePreset.entries.size} 款",style=MaterialTheme.typography.bodySmall,color=LocalFilmMaterials.current.secondaryInk)
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(FramePreset.entries) { preset -> FrameSample(preset, state, workspace) }
                }
                Text("本批全部照片使用同一款画框", color = LocalFilmMaterials.current.secondaryInk, style = MaterialTheme.typography.bodySmall)
            }
            item { PhotoStrip(state, workspace) }
            if (state.frame.preset != FramePreset.NONE) {
                item { OutlinedTextField(state.frame.signature, { workspace.updateFrame(state.frame.copy(signature = it.take(240))) },
                    label = { Text("签名 / 水印文字") }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) }
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显示拍摄参数", Modifier.weight(1f)); Switch(state.frame.showPhotoMetadata,
                        { workspace.updateFrame(state.frame.copy(showPhotoMetadata = it)) }, enabled = !state.busy)
                } }
                item {
                    Text("画布比例")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(FrameRatio.entries) { ratio -> MirrorChoice(state.frame.ratio == ratio,
                            { workspace.updateFrame(state.frame.copy(ratio = ratio)) }, label = { Text(ratio.title) }, enabled = !state.busy) }
                    }
                    ParameterSlider("纸边", "${(state.frame.borderFraction * 100).roundToInt()}%", state.frame.borderFraction, .02f.. .12f,
                        !state.busy) { workspace.updateFrame(state.frame.copy(borderFraction = it)) }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(FrameFont.entries) { font -> MirrorChoice(state.frame.signatureFont == font && state.frame.signatureFontKey == null,
                            { workspace.updateFrame(state.frame.copy(signatureFont = font, signatureFontKey = null)) }, enabled = !state.busy,
                            label = { Text(font.title) }) }
                    }
                }
                item { TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "收起画框细节" else "画框细节与字体") } }
                if (showAdvanced) item {
                    val frame = state.frame
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (target in listOf("title", "subtitle", "footer")) {
                            val value = when (target) { "title" -> frame.title; "subtitle" -> frame.subtitle; else -> frame.footer }
                            OutlinedTextField(value.orEmpty(), { text -> workspace.updateFrame(when (target) {
                                "title" -> frame.copy(title = text.take(240).ifBlank { null })
                                "subtitle" -> frame.copy(subtitle = text.take(240).ifBlank { null })
                                else -> frame.copy(footer = text.take(240).ifBlank { null }) }) }, enabled = !state.busy,
                                label = { Text(when (target) { "title" -> "标题 · 留空使用照片信息"; "subtitle" -> "副标题"; else -> "页脚" }) })
                            TextButton(enabled = !state.busy, onClick = { fontTarget = target; fontPicker.launch(arrayOf("*/*")) }) { Text("导入该区域字体") }
                        }
                        TextButton(enabled = !state.busy, onClick = { fontTarget = "signature"; fontPicker.launch(arrayOf("*/*")) }) { Text("导入签名字体") }
                        TextButton(enabled = !state.busy, onClick = { art.launch(arrayOf("image/*")) }) { Text("导入装饰图") }
                        if (frame.imageKey != null) TextButton(onClick = { workspace.updateFrame(frame.copy(imageKey = null)) }) { Text("移除装饰图") }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(FrameMood.entries) { mood -> MirrorChoice(frame.mood == mood,
                                { workspace.updateFrame(frame.copy(mood = mood)) }, label = { Text(mood.title) }) }
                        }
                        Row { for ((label, argb) in listOf("自动" to null, "暖白" to 0xFFF7F4EC.toInt(), "黑" to 0xFF171A18.toInt()))
                            TextButton(onClick = { workspace.updateFrame(frame.copy(fixedColorArgb = argb)) }) { Text(label) } }
                        Text("画布方向")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((label, portrait) in listOf("跟随照片" to null, "横向" to false, "竖向" to true))
                                MirrorChoice(frame.portrait == portrait,
                                    { workspace.updateFrame(frame.copy(portrait = portrait)) }, enabled = !state.busy,
                                    label = { Text(label) })
                        }
                        if (frame.preset == FramePreset.FILM) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Text("120 暗盒", Modifier.weight(1f)); Switch(frame.roll120,
                                { workspace.updateFrame(frame.copy(roll120 = it)) }) }
                            OutlinedTextField(frame.filmBrand, { workspace.updateFrame(frame.copy(filmBrand = it.take(60))) }, label = { Text("胶卷品牌") })
                            OutlinedTextField(frame.filmName, { workspace.updateFrame(frame.copy(filmName = it.take(80))) }, label = { Text("胶卷名称") })
                            OutlinedTextField(frame.filmNotes, { workspace.updateFrame(frame.copy(filmNotes = it.take(60))) },
                                enabled = !state.busy, label = { Text("片尾文字") })
                            Text("暗盒标签风格")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for ((label, blackWhite) in listOf("跟随胶卷" to null, "彩色" to false, "黑白" to true))
                                    MirrorChoice(frame.filmBlackWhite == blackWhite,
                                        { workspace.updateFrame(frame.copy(filmBlackWhite = blackWhite)) }, enabled = !state.busy,
                                        label = { Text(label) })
                            }
                        }
                    }
                }
            }
            item { OutputControls(state, workspace) }
            item {
                Text("保存位置：${if (state.destination is ExportDestination.Gallery) "相册 / Pictures/LumixFilmMirror" else "所选文件夹"}")
                Row {
                    TextButton(enabled = !state.busy, onClick = { workspace.destination(ExportDestination.Gallery) }) { Text("相册") }
                    TextButton(enabled = !state.busy, onClick = { folder.launch(null) }) { Text("选择文件夹") }
                }
            }
            if (state.exported.isNotEmpty()) item { Text("已保存 ${state.exported.size} 张照片", color = MaterialTheme.colorScheme.primary) }
            state.failures.forEach { failure -> item { Text("${failure.name}：${failure.reason}", color = MaterialTheme.colorScheme.error) } }
            if (!state.busy && state.failures.any { it.id != null }) item {
                TextButton(onClick = { workspace.export(retryOnly = true) }) { Text("重试失败照片") }
            }
        }
        if (state.current != null) MirrorButton(enabled = !state.busy, onClick = { workspace.export() },
            modifier = Modifier.fillMaxWidth().padding(20.dp).height(56.dp), shape = RoundedCornerShape(28.dp)) {
            Text(if (state.exporting) "保存中…" else "导出本批 · ${state.photos.size} 张")
        }
    }
}

@Composable
private fun FrameSample(preset: FramePreset, state: WorkspaceState, workspace: WorkspaceViewModel) {
    val source = state.current ?: return
    val options = state.exportOptions.copy(frame = state.frame.copy(preset = preset))
    // A preset thumbnail is independent of the currently selected preset and other photos' edits.
    val recipe = workspace.photoRecipe(state, source.id)
    val previewKey = listOf("frame", source.id, recipe, options)
    var thumbnail by remember(previewKey) { mutableStateOf(workspace.displayPreviews.peek(previewKey)?.image) }
    LaunchedEffect(previewKey) {
        try {
            thumbnail = workspace.displayPreviews.load(previewKey) { MirrorPreview(workspace.processor.previewExport(source, recipe,
                ProcessingCatalogue(state.luts, state.filmPresets, emptyList()),
                options, longEdge = 192)) }.image
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Keep labelled selectable placeholder; main preview shows the error. */ }
    }
    Column(Modifier.width(100.dp).clickable(enabled = !state.busy) { workspace.updateFrame(state.frame.copy(preset = preset)) },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(100.dp).background(LocalFilmMaterials.current.paperHighlight), contentAlignment = Alignment.Center) {
            thumbnail?.let { Image(it.asImageBitmap(), preset.title, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit) }
                ?: DotIcon(DotGlyph.FRAME)
        }
        MirrorChoice(state.frame.preset == preset, { workspace.updateFrame(state.frame.copy(preset = preset)) },
            enabled = !state.busy, label = { Text(preset.title, fontSize = 11.sp) })
    }
}
