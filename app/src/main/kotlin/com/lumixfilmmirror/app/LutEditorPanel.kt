package com.lumixfilmmirror.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lumixfilmmirror.design.FilmSpacing
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.design.PaperPanel
import com.lumixfilmmirror.media.LutAsset
import com.lumixfilmmirror.model.LutRecipe
import com.lumixfilmmirror.model.LutSlot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
internal fun LutEditorPanel(state: WorkspaceState, workspace: WorkspaceViewModel, onImport: () -> Unit) {
    val tones = LocalFilmMaterials.current
    var choosing by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.busy) { if (state.busy) choosing = null }
    val recipe = state.lutRecipe
    PaperPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(FilmSpacing.panel), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("LUT 调色", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(enabled = !state.busy, onClick = onImport) { Text(if (state.lutImporting) "正在导入…" else "导入 .cube") }
            }
            Text("LUT 1 → LUT 2 · 应用于本批全部照片", color = tones.secondaryInk, style = MaterialTheme.typography.bodySmall)
            for (number in 1..2) {
                val slot = if (number == 1) recipe.first else recipe.second
                val selected = state.luts.firstOrNull { it.id == slot.id }
                OutlinedButton(enabled = !state.busy, onClick = { choosing = number }, modifier = Modifier.fillMaxWidth()) {
                    Text("LUT $number · ${selected?.name ?: if (slot.id == null) "未选择" else "文件不可用，请重新选择"}")
                }
                selected?.photoStyle?.let {
                    Text("源照片建议使用松下 $it 照片风格", color = tones.secondaryInk, style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth()) {
                    Text("LUT $number 强度", Modifier.weight(1f))
                    Text("${(slot.strength * 100).roundToInt()}%", fontFamily = FontFamily.Monospace)
                }
                Slider(value = slot.strength, valueRange = 0f..1f, enabled = !state.busy && slot.id != null,
                    onValueChange = { strength -> workspace.updateLut(if (number == 1) recipe.copy(first = slot.copy(strength = strength))
                        else recipe.copy(second = slot.copy(strength = strength))) })
            }
            Row {
                TextButton(enabled = !state.busy, onClick = { workspace.updateLut(recipe.copy(first = recipe.second, second = recipe.first)) }) { Text("交换顺序") }
                TextButton(enabled = !state.busy, onClick = { workspace.updateLut(LutRecipe()) }) { Text("清除效果") }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("颗粒", Modifier.weight(1f))
                Switch(recipe.grainEnabled, { workspace.updateLut(recipe.copy(grainEnabled = it)) }, enabled = !state.busy)
                Text("${(recipe.grain * 100).roundToInt()}%", fontFamily = FontFamily.Monospace)
            }
            Slider(value = recipe.grain, valueRange = 0f..1f, enabled = !state.busy && recipe.grainEnabled,
                onValueChange = { workspace.updateLut(recipe.copy(grain = it)) })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Bayer 抖动 · 减少色彩断层", Modifier.weight(1f))
                Switch(recipe.ditheringEnabled, { workspace.updateLut(recipe.copy(ditheringEnabled = it)) }, enabled = !state.busy)
            }
            Text("使用 JPEG 色彩直接调色；导入的 LUT 需与源照片的输入色彩匹配。",
                color = tones.secondaryInk, style = MaterialTheme.typography.bodySmall)
        }
    }
    choosing?.let { number ->
        LutPicker(state, workspace, number, onClose = { choosing = null }, onSelect = { asset ->
            val current = state.lutRecipe
            val slot = if (number == 1) current.first else current.second
            workspace.updateLut(if (number == 1) current.copy(first = slot.copy(id = asset?.id))
                else current.copy(second = slot.copy(id = asset?.id)))
            choosing = null
        })
    }
}

@Composable
private fun LutPicker(state: WorkspaceState, workspace: WorkspaceViewModel, number: Int,
    onClose: () -> Unit, onSelect: (LutAsset?) -> Unit) {
    val tones = LocalFilmMaterials.current
    var category by remember { mutableStateOf("全部") }
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<LutAsset?>(null) }
    val assets = state.luts.filter { (category == "全部" || it.builtin == (category == "内置")) && it.name.contains(query.trim(), ignoreCase = true) }
    val back: () -> Unit = { if (detail != null) detail = null else onClose() }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = tones.background) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(FilmSpacing.screen),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = back) { Text("返回") }
                    Text("选择 LUT $number", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { onSelect(null) }) { Text("不使用") }
                }
                val selected = detail
                if (selected != null) {
                    Text(selected.name, style = MaterialTheme.typography.titleLarge)
                    selected.photoStyle?.let { Text("源照片建议使用松下 $it 照片风格", color = tones.secondaryInk) }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        LutImagePreview(selected, state, workspace, longEdge = 1280, fit = true)
                    }
                    Button(onClick = { onSelect(selected) }, modifier = Modifier.fillMaxWidth().heightIn(min = FilmSpacing.controlMinimum)) {
                        Text("选择该 LUT")
                    }
                    return@Column
                }
                Text("使用当前照片预览，每张卡片仅展示这一款 LUT 的效果。",
                    color = tones.secondaryInk, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("搜索 LUT 名称") }, modifier = Modifier.fillMaxWidth())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = category == "全部", onClick = { category = "全部" }, label = { Text("全部") }) }
                    item { FilterChip(selected = category == "内置", onClick = { category = "内置" }, label = { Text("内置") }) }
                    item { FilterChip(selected = category == "本地", onClick = { category = "本地" }, label = { Text("本地") }) }
                }
                if (assets.isEmpty()) Text("此分类暂无 LUT，可在工作台导入 .cube 文件。", color = tones.secondaryInk)
                LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(assets, key = { it.id }) { asset ->
                        LutPreviewCard(asset, state, workspace, onClick = { detail = asset })
                    }
                }
            }
        }
    }
}

@Composable
private fun LutPreviewCard(asset: LutAsset, state: WorkspaceState, workspace: WorkspaceViewModel, onClick: () -> Unit) {
    val tones = LocalFilmMaterials.current
    PaperPanel(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                LutImagePreview(asset, state, workspace, longEdge = 320, fit = false)
            }
            Text(asset.name, style = MaterialTheme.typography.titleSmall)
            Text(if (asset.builtin) "内置${asset.photoStyle?.let { " · $it" }.orEmpty()}" else "本地",
                color = tones.secondaryInk, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun LutImagePreview(asset: LutAsset, state: WorkspaceState, workspace: WorkspaceViewModel, longEdge: Int, fit: Boolean) {
    val source = state.current
    var image by remember(asset.id, source?.id, longEdge) { mutableStateOf<Bitmap?>(null) }
    var problem by remember(asset.id, source?.id, longEdge) { mutableStateOf<String?>(null) }
    LaunchedEffect(asset.id, source?.id, longEdge) {
        if (source == null) return@LaunchedEffect
        try {
            delay(60)
            image = workspace.previewPhoto(source, LutRecipe(first = LutSlot(asset.id)), listOf(asset), longEdge)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { problem = "无法读取效果预览" }
        catch (error: OutOfMemoryError) { problem = "预览内存不足" }
    }
    image?.let { Image(it.asImageBitmap(), contentDescription = "${asset.name} 效果预览",
        modifier = Modifier.fillMaxSize(), contentScale = if (fit) ContentScale.Fit else ContentScale.Crop) }
        ?: if (problem != null) Text(problem.orEmpty(), style = MaterialTheme.typography.labelSmall)
            else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
}
