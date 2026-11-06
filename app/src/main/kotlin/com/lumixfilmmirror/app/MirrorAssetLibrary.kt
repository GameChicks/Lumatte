package com.lumixfilmmirror.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.media.SourceImage
import com.lumixfilmmirror.media.ProcessingCatalogue
import com.lumixfilmmirror.model.PhotoRecipe
import com.lumixfilmmirror.model.PhotoTreatment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation

@Composable
internal fun MirrorAssetLibrary(state: WorkspaceState, workspace: WorkspaceViewModel, onBack: () -> Unit,
    onImport: () -> Unit, onEdit: () -> Unit) {
    val tones = LocalFilmMaterials.current
    var search by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val photos = remember(state.libraryPhotos, search) { state.libraryPhotos.asReversed().filter {
        search.isBlank() || it.name.contains(search, ignoreCase = true)
    } }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundAction("返回主页", DotGlyph.LEFT, onClick = onBack)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("资产库", style = MaterialTheme.typography.headlineMedium)
                Text("${state.libraryPhotos.size} 张已导入照片", style = MaterialTheme.typography.bodySmall, color = tones.secondaryInk)
            }
            RoundAction("导入照片", DotGlyph.PLUS, enabled = !state.busy, onClick = onImport)
        }
        MirrorTextField(search, { search = it }, "搜索文件名")
        if (photos.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(if (search.isBlank()) "导入相册或相机照片后，会保留在这里。" else "没有找到匹配的照片", color = tones.secondaryInk)
        } else LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(photos, key = { it.id.value }) { photo ->
                Column(Modifier.clip(RoundedCornerShape(20.dp)).background(tones.paperHighlight)
                    .clickable(enabled = !state.busy) { selected = photo.id.value }.padding(8.dp)) {
                    LibraryImage(photo, workspace, 320, Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(photo.name, Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium)
                    Text("${photo.size.width} × ${photo.size.height}", fontSize = 11.sp, color = tones.secondaryInk)
                }
            }
        }
    }
    state.libraryPhotos.firstOrNull { it.id.value == selected }?.let { photo ->
        MirrorDialog(photo.name, onDismiss = { selected = null }) {
            LibraryImage(photo, workspace, 960, Modifier.fillMaxWidth().height(270.dp))
            Text("选择一种方式继续编辑", color = tones.secondaryInk)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MirrorButton(onClick = { workspace.editLibraryPhoto(photo.id, PhotoTreatment.FILM) {
                    selected = null; onEdit()
                } }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("胶片模拟") }
                MirrorButton(onClick = { workspace.editLibraryPhoto(photo.id, PhotoTreatment.LUT) {
                    selected = null; onEdit()
                } }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("LUT 调色") }
            }
        }
    }
}

@Composable
private fun LibraryImage(photo: SourceImage, workspace: WorkspaceViewModel, longEdge: Int, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(null, photo.id, longEdge) {
        var owned: Bitmap? = null
        try {
            owned = workspace.processor.preview(photo, PhotoRecipe.Original, ProcessingCatalogue(emptyList(), emptyList(), emptyList()), longEdge)
            value = owned
            awaitCancellation()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { awaitCancellation() }
        finally { owned?.recycle() }
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(LocalFilmMaterials.current.paper), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), photo.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: Text("预览加载中", style = MaterialTheme.typography.labelSmall)
    }
}
