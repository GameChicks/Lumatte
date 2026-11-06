package com.lumixfilmmirror.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.media.FilmCategory
import com.lumixfilmmirror.media.FilmPreset
import com.lumixfilmmirror.media.ProcessingCatalogue
import com.lumixfilmmirror.model.*
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/** Compact display labels only. Persisted stock IDs and full catalogue names never change. */
internal fun FilmPreset.editorName(): String = when(id) {
    "kodak_ektachrome_100" -> "E100"
    "cinestill_800t" -> "800T"
    "cinestill_400d" -> "400D"
    "kodak_ultramax_400" -> "UltraMax 400"
    "fujifilm_provia_100f" -> "Provia 100F"
    "fujifilm_acros_100_ii" -> "Acros II"
    "fujifilm_xtra_400" -> "X-Tra 400"
    "ilford_hp5_plus_400" -> "HP5 Plus"
    else -> name.removePrefix("Kodak ").removePrefix("Fujifilm ").removePrefix("Ilford ")
        .replace("Vision3 ", "V3 ")
}

@Composable
internal fun EditorTextTab(label: String, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    val tones=LocalFilmMaterials.current
    Column(modifier.height(40.dp).selectable(selected,enabled=enabled,role=Role.Tab,onClick=onClick)
        .padding(horizontal=10.dp),horizontalAlignment=Alignment.CenterHorizontally,
        verticalArrangement=Arrangement.Center) {
        Text(label,maxLines=1,softWrap=false,fontSize=12.sp,color=if(selected)tones.ink else tones.secondaryInk)
        Spacer(Modifier.height(5.dp))
        Box(Modifier.size(if(selected)18.dp else 0.dp,2.dp).background(tones.amber,RoundedCornerShape(1.dp)))
    }
}

@Composable
private fun EditorSample(label:String, fullName:String, selected:Boolean, enabled:Boolean, onClick:()->Unit,
    content:@Composable BoxScope.()->Unit) {
    val tones=LocalFilmMaterials.current
    val shape=RoundedCornerShape(12.dp)
    Column(Modifier.width(78.dp).height(104.dp).semantics { contentDescription=fullName }
        .selectable(selected,enabled=enabled,role=Role.RadioButton,onClick=onClick),
        horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(84.dp).clip(shape).background(tones.paper)
            .border(if(selected)2.dp else 1.dp,if(selected)tones.amber else tones.rim.copy(.35f),shape)
            .padding(4.dp),contentAlignment=Alignment.Center,content=content)
        Text(label,Modifier.fillMaxWidth().padding(top=6.dp),maxLines=1,softWrap=false,
            overflow=TextOverflow.Ellipsis,fontSize=11.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,
            color=if(selected)tones.ink else tones.secondaryInk)
    }
}

@Composable
internal fun MirrorEditorFilms(state:WorkspaceState,workspace:WorkspaceViewModel) {
    val current=state.selected?.let { state.filmBatch?.effectiveParameters(it)?.presetId }
    val selected=state.filmPresets.firstOrNull { it.id==current }
    var category by rememberSaveable { mutableStateOf(selected?.category?.name ?: "all") }
    val films=state.filmPresets.filter { category=="all" || it.category.name==category }
    val row=rememberLazyListState()
    LaunchedEffect(category) {
        row.scrollToItem(films.indexOfFirst { it.id==current }.coerceAtLeast(0))
    }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("胶卷",fontSize=13.sp)
            Text(selected?.name.orEmpty(),Modifier.weight(1f).padding(start=12.dp),fontSize=11.sp,
                maxLines=1,overflow=TextOverflow.Ellipsis,color=LocalFilmMaterials.current.secondaryInk)
        }
        LazyRow(contentPadding=PaddingValues(horizontal=10.dp)) {
            item { EditorTextTab("全部",category=="all",{category="all"}) }
            items(FilmCategory.entries,key={it.name}) { c ->
                EditorTextTab(when(c){FilmCategory.NEGATIVE->"负片";FilmCategory.CINEMA->"电影";
                    FilmCategory.REVERSAL->"反转";FilmCategory.MONOCHROME->"黑白"},category==c.name,{category=c.name})
            }
        }
        LazyRow(state=row,contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            items(films,key={it.id}) { film ->
                EditorSample(film.editorName(),film.name,state.treatment==PhotoTreatment.FILM && current==film.id,
                    !state.busy,{workspace.selectPreviewFilm(film.id)}) { FilmCartridge(film,Modifier.size(44.dp,68.dp)) }
            }
        }
    }
}

@Composable
internal fun MirrorEditorLuts(state:WorkspaceState,workspace:WorkspaceViewModel,onLibrary:(Boolean)->Unit) {
    var second by rememberSaveable { mutableStateOf(false) }
    val recipe=state.lutRecipe
    val slot=if(second)recipe.second else recipe.first
    fun choose(id:String?) {
        workspace.treatment(PhotoTreatment.LUT)
        workspace.updateLut(if(second)recipe.copy(second=slot.copy(id=id))else recipe.copy(first=slot.copy(id=id)))
    }
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically) {
            EditorTextTab("LUT 1",!second,{second=false})
            EditorTextTab("LUT 2",second,{second=true})
            Spacer(Modifier.weight(1f));MirrorTextAction("色彩库 ↗",{onLibrary(second)},enabled=!state.busy)
        }
        LazyRow(contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            item { EditorSample("无 LUT","不使用此槽 LUT",state.treatment==PhotoTreatment.LUT && slot.id==null,
                !state.busy,{choose(null)}) { DotIcon(DotGlyph.CLOSE) } }
            items(state.luts,key={it.id}) { lut ->
                EditorSample(lut.name,lut.name,state.treatment==PhotoTreatment.LUT && slot.id==lut.id,!state.busy,{choose(lut.id)}) {
                    EditorLutSample(state,workspace,if(second)recipe.copy(second=slot.copy(id=lut.id))else recipe.copy(first=slot.copy(id=lut.id)))
                }
            }
        }
        Box(Modifier.padding(horizontal=20.dp)) {
            MirrorParameterSlider("强度","${(slot.strength*100).roundToInt()}%",slot.strength,0f..1f,!state.busy && slot.id!=null){v->
                workspace.updateLut(if(second)recipe.copy(second=slot.copy(strength=v))else recipe.copy(first=slot.copy(strength=v)))
            }
        }
    }
}

@Composable
private fun EditorLutSample(state:WorkspaceState,workspace:WorkspaceViewModel,recipe:LutRecipe) {
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
    bitmap?.let { Image(it.asImageBitmap(),null,Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),contentScale=ContentScale.Crop) }
        ?: Text("LUT",fontSize=12.sp)
}
