package com.lumixfilmmirror.app

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.model.*
import kotlin.math.roundToInt

private val editorTools=listOf("adjust" to "调节","lut" to "LUT","film" to "胶卷","texture" to "质感","output" to "批次")

/** All panels have the same viewport; changing a tool or a long name never moves the photo. */
@Composable
internal fun MirrorEditorDock(state:WorkspaceState,workspace:WorkspaceViewModel,tool:String,
    onTool:(String)->Unit,onLibrary:(Boolean)->Unit,onOutput:()->Unit) {
    val tones=LocalFilmMaterials.current
    val saved=rememberSaveableStateHolder()
    val duration=if(LocalMirrorReducedMotion.current)0 else 200
    val panelHeight=(LocalConfiguration.current.screenHeightDp*.27f).coerceIn(184f,216f).dp
    Column(Modifier.fillMaxWidth().background(tones.paperHighlight)) {
        HorizontalDivider(color=tones.rim.copy(.3f))
        AnimatedContent(targetState=tool,modifier=Modifier.fillMaxWidth().height(panelHeight),
            contentAlignment=Alignment.TopStart,transitionSpec={
                val direction=if(editorTools.indexOfFirst{it.first==targetState}>editorTools.indexOfFirst{it.first==initialState})1 else -1
                ((fadeIn(tween(duration))+slideInHorizontally(tween(duration,easing=MirrorEase)){it*direction/12}) togetherWith
                    (fadeOut(tween(duration/2))+slideOutHorizontally(tween(duration,easing=MirrorEase)){-it*direction/12})).using(null)
            },label="editor-tools") { shown -> saved.SaveableStateProvider(shown) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top=8.dp)) {
                    when(shown) {
                        "adjust"->state.selected?.let { state.filmBatch?.effectiveParameters(it) }?.let { p ->
                            MirrorFilmFineTuning(p,!state.busy,workspace::updateFilm,compact=true,showGrain=false)
                        }
                        "lut"->MirrorEditorLuts(state,workspace,onLibrary)
                        "film"->MirrorEditorFilms(state,workspace)
                        "texture"->EditorTexture(state,workspace)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically) {
            editorTools.forEach { (key,title) ->
                EditorTextTab(title,tool==key,{if(key=="output")onOutput()else onTool(key)},Modifier.weight(1f),!state.busy)
            }
        }
    }
}

@Composable
private fun EditorTexture(state:WorkspaceState,workspace:WorkspaceViewModel) {
    Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(state.treatment==PhotoTreatment.FILM) {
            val p=state.selected?.let { state.filmBatch?.effectiveParameters(it) } ?: return@Column
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("光晕",Modifier.weight(1f),fontSize=13.sp)
                MirrorToggle(p.halationEnabled,"胶片光晕",!state.busy){workspace.updateFilm(p.copy(halationEnabled=it))}
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("颗粒",Modifier.weight(1f),fontSize=13.sp)
                FilmGrainIntensity.entries.forEach { g -> EditorTextTab(when(g){FilmGrainIntensity.LOW->"低";FilmGrainIntensity.MEDIUM->"中";FilmGrainIntensity.HIGH->"高"},
                    p.grainIntensity==g,{workspace.updateFilm(p.copy(grainIntensity=g))},enabled=!state.busy) }
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("黑柔",Modifier.weight(1f),fontSize=13.sp)
                DiffusionGrade.entries.forEach { g -> EditorTextTab(g.editorLabel(),p.diffusion==g,
                    {workspace.updateFilm(p.copy(diffusion=g))},enabled=!state.busy) }
            }
            Text("黑柔：柔化高光，降低强光反差。",fontSize=11.sp,color=LocalFilmMaterials.current.secondaryInk)
        } else {
            val r=state.lutRecipe
            Row(verticalAlignment=Alignment.CenterVertically){Text("颗粒效果",Modifier.weight(1f));MirrorToggle(r.grainEnabled,"颗粒效果",!state.busy){workspace.updateLut(r.copy(grainEnabled=it))}}
            MirrorParameterSlider("颗粒强度","${(r.grain*100).roundToInt()}%",r.grain,0f..1f,!state.busy && r.grainEnabled){workspace.updateLut(r.copy(grain=it))}
            Row(verticalAlignment=Alignment.CenterVertically){Text("减少色彩断层",Modifier.weight(1f));MirrorToggle(r.ditheringEnabled,"减少色彩断层",!state.busy){workspace.updateLut(r.copy(ditheringEnabled=it))}}
        }
    }
}

internal fun DiffusionGrade.editorLabel()=when(this) {
    DiffusionGrade.OFF->"关闭";DiffusionGrade.EIGHTH->"1/8";DiffusionGrade.QUARTER->"1/4";DiffusionGrade.HALF->"1/2"
}
