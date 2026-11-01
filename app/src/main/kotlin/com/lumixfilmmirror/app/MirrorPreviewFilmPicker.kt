@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.lumixfilmmirror.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.design.MirrorFonts
import com.lumixfilmmirror.media.FilmCategory

/** Selection stays over the live editor; neither the main carousel nor its details are opened. */
@Composable
internal fun MirrorPreviewFilmPicker(state: WorkspaceState, workspace: WorkspaceViewModel, onDismiss: () -> Unit) {
    val batch = state.filmBatch ?: return
    val id = state.selected ?: return
    val selected = batch.effectiveParameters(id).presetId
    val tones = LocalFilmMaterials.current
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val films = remember(state.filmPresets, category) {
        state.filmPresets.filter { category == null || it.category.name == category }
    }
    val grid = rememberLazyGridState()
    LaunchedEffect(category) {
        grid.scrollToItem(films.indexOfFirst { it.id == selected }.coerceAtLeast(0))
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = tones.paper, contentColor = tones.ink, scrimColor = Color.Transparent) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.52f).navigationBarsPadding()
            .padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("更换胶卷", fontFamily = MirrorFonts.ChineseEditorial, fontSize = 24.sp)
                    Text("点选即应用，收起后继续微调", style = MaterialTheme.typography.bodySmall,
                        color = tones.secondaryInk)
                }
                TextButton(onClick = onDismiss) { Text("完成") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { MirrorChoice(category == null, { category = null }, label = { Text("全部") }) }
                items(FilmCategory.entries) { entry ->
                    MirrorChoice(category == entry.name, { category = entry.name }, label = { Text(entry.label) })
                }
            }
            LazyVerticalGrid(columns = GridCells.Adaptive(152.dp), state = grid, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(films, key = { it.id }) { film ->
                    val active = film.id == selected
                    val shape = RoundedCornerShape(20.dp)
                    Row(Modifier.fillMaxWidth().heightIn(min = 100.dp).clip(shape)
                        .background(if (active) tones.amber.copy(alpha = .13f) else tones.paperHighlight)
                        .border(if (active) 2.dp else 1.dp, if (active) tones.amber else tones.rim.copy(alpha = .55f), shape)
                        .selectable(active, enabled = !state.busy, role = Role.RadioButton,
                            onClick = { workspace.selectPreviewFilm(film.id) })
                        .padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilmCartridge(film, Modifier.size(width = 35.dp, height = 58.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(film.shortTitle(), maxLines = 3, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall)
                            Text(if (active) "使用中" else film.category.label, style = MaterialTheme.typography.labelSmall,
                                color = if (active) tones.amber else tones.secondaryInk)
                        }
                    }
                }
            }
        }
    }
}
