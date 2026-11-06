package com.lumixfilmmirror.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CancellationException
import com.lumixfilmmirror.media.ProcessingCatalogue
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixfilmmirror.model.*
import kotlin.math.roundToInt

@Composable
internal fun MirrorEditorFilms(state: WorkspaceState, workspace: WorkspaceViewModel) {
    val selected=state.selected?.let { state.filmBatch?.effectiveParameters(it)?.presetId }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("更换胶卷",Modifier.padding(horizontal=20.dp),fontSize=13.sp)
        LazyRow(contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            items(state.filmPresets,key={it.id}) { film ->
                MirrorChoice(state.treatment==PhotoTreatment.FILM && selected==film.id,{workspace.selectPreviewFilm(film.id)},
                    enabled=!state.busy,label={Column(Modifier.width(112.dp),horizontalAlignment=Alignment.CenterHorizontally){
                        FilmCartridge(film,Modifier.size(50.dp,76.dp));Spacer(Modifier.height(6.dp));Text(film.name,fontSize=11.sp,maxLines=2)
                    }})
            }
        }
    }
}

@Composable
internal fun MirrorEditorLuts(state: WorkspaceState, workspace: WorkspaceViewModel, onLibrary:(Boolean)->Unit) {
    var second by rememberSaveable { mutableStateOf(false) }
    val recipe=state.lutRecipe
    val slot=if(second)recipe.second else recipe.first
    fun choose(id:String?) {
        workspace.treatment(PhotoTreatment.LUT)
        workspace.updateLut(if(second)recipe.copy(second=slot.copy(id=id))else recipe.copy(first=slot.copy(id=id)))
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            MirrorChoice(!second,{second=false},label={Text("LUT 1")})
            MirrorChoice(second,{second=true},label={Text("LUT 2")})
            Spacer(Modifier.weight(1f));MirrorTextAction("色彩库 ↗",{onLibrary(second)},enabled=!state.busy)
        }
        LazyRow(contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            item { MirrorChoice(state.treatment==PhotoTreatment.LUT && slot.id==null,{choose(null)},enabled=!state.busy,label={Text("无 LUT")}) }
            items(state.luts,key={it.id}) { lut ->
                MirrorChoice(state.treatment==PhotoTreatment.LUT && slot.id==lut.id,{choose(lut.id)},enabled=!state.busy,
                    label={Column(Modifier.width(96.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        EditorLutSample(state,workspace,if(second)recipe.copy(second=slot.copy(id=lut.id)) else recipe.copy(first=slot.copy(id=lut.id)))
                        Spacer(Modifier.height(6.dp));Text(lut.name,maxLines=2,fontSize=11.sp)
                    }})
            }
        }
        Box(Modifier.padding(horizontal=20.dp)) {
            MirrorParameterSlider("LUT 强度","${(slot.strength*100).roundToInt()}%",slot.strength,0f..1f,!state.busy && slot.id!=null){v->
                workspace.updateLut(if(second)recipe.copy(second=slot.copy(strength=v))else recipe.copy(first=slot.copy(strength=v)))
            }
        }
    }
}

@Composable
private fun EditorLutSample(state: WorkspaceState, workspace: WorkspaceViewModel, recipe: LutRecipe) {
    val source=state.current ?: return
    val adjusted=recipe.copy(adjustments=state.filmBatch?.effectiveParameters(source.id)?.adjustments() ?: PhotoAdjustments())
    val key=listOf("editor-lut-choice",source.id,adjusted,state.luts)
    var bitmap by remember(key) { mutableStateOf(workspace.displayPreviews.peek(key)?.image) }
    LaunchedEffect(key) {
        try { bitmap=workspace.displayPreviews.load(key,keepLoading=false) {
            MirrorPreview(workspace.processor.preview(source,PhotoRecipe.Lut(adjusted),
                ProcessingCatalogue(state.luts,emptyList(),emptyList()),longEdge=160))
        }.image }
        catch(error:CancellationException){throw error}
        catch(_:Exception){} catch(_:OutOfMemoryError){}
    }
    Box(Modifier.fillMaxWidth().height(72.dp),contentAlignment=Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit) } ?: Text("LUT",fontSize=12.sp)
    }
}
