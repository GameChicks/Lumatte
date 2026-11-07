package com.lumixfilmmirror.app

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.model.FilmParameters
import com.lumixfilmmirror.model.FilmGrainIntensity
import kotlin.math.roundToInt

private enum class FilmAdjustment(val title: String) {
    EXPOSURE("曝光"), BRIGHTNESS("亮度"), CONTRAST("对比度"), HIGHLIGHTS("高光"),
    SHADOWS("阴影"), SATURATION("饱和度"), VIBRANCE("自然饱和度"), TEMPERATURE("色温");

    fun value(p: FilmParameters): Float = when (this) {
        EXPOSURE -> p.exposureEv; BRIGHTNESS -> p.brightness; CONTRAST -> p.contrast
        HIGHLIGHTS -> p.highlights; SHADOWS -> p.shadows; SATURATION -> p.saturation
        VIBRANCE -> p.vibrance; TEMPERATURE -> p.temperature
    }
    fun label(p: FilmParameters): String = if (this == EXPOSURE) "%+.1f EV".format(value(p))
        else "%+d".format((value(p)*100).roundToInt())
    fun change(p: FilmParameters, requested: Float): FilmParameters {
        val value = if (this == EXPOSURE) (requested*10).roundToInt()/10f else (requested*100).roundToInt()/100f
        return when (this) {
            EXPOSURE -> p.copy(exposureEv = value); BRIGHTNESS -> p.copy(brightness = value)
            CONTRAST -> p.copy(contrast = value); HIGHLIGHTS -> p.copy(highlights = value)
            SHADOWS -> p.copy(shadows = value); SATURATION -> p.copy(saturation = value)
            VIBRANCE -> p.copy(vibrance = value); TEMPERATURE -> p.copy(temperature = value)
        }
    }
}

/** All basic tools share one slider so the editor does not grow by eight rows of controls. */
@Composable
internal fun MirrorFilmFineTuning(parameters: FilmParameters, enabled: Boolean, onChange: (FilmParameters) -> Unit,
    compact: Boolean = false, showGrain: Boolean = true) {
    var tool by rememberSaveable { mutableStateOf(FilmAdjustment.EXPOSURE.name) }
    val current = FilmAdjustment.valueOf(tool)
    val tones = LocalFilmMaterials.current
    if(compact) {
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            MirrorParameterSlider(current.title,current.label(parameters),current.value(parameters),
                if(current==FilmAdjustment.EXPOSURE)-3f..3f else -1f..1f,enabled,
                steps=if(current==FilmAdjustment.EXPOSURE)59 else 199){onChange(current.change(parameters,it))}
            LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                items(FilmAdjustment.entries,key={it.name}) { item ->
                    EditorTextTab(item.title,item==current,{tool=item.name},enabled=enabled)
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                MirrorTextAction("重置此项",{onChange(current.change(parameters,0f))},enabled=enabled && current.value(parameters)!=0f)
                MirrorTextAction("全部归零",{onChange(parameters.copy(exposureEv=0f,brightness=0f,contrast=0f,highlights=0f,
                    shadows=0f,saturation=0f,vibrance=0f,temperature=0f))},enabled=enabled && FilmAdjustment.entries.any{it.value(parameters)!=0f})
            }
        }
        return
    }
    val content: @Composable () -> Unit = {
        MirrorParameterSlider(current.title, current.label(parameters), current.value(parameters),
            if (current == FilmAdjustment.EXPOSURE) -3f..3f else -1f..1f, enabled,
            steps = if (current == FilmAdjustment.EXPOSURE) 59 else 199) { onChange(current.change(parameters, it)) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(FilmAdjustment.entries, key = { it.name }) { adjustment ->
                MirrorChoice(current == adjustment, { tool = adjustment.name }, enabled = enabled, label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(adjustment.title, fontSize = 13.sp)
                        Text(adjustment.label(parameters), fontSize = 10.sp)
                    }
                })
            }
        }
        if (current == FilmAdjustment.TEMPERATURE) Text("负值偏冷，正值偏暖。", fontSize = 12.sp, color = tones.secondaryInk)
        if (current == FilmAdjustment.VIBRANCE) Text("优先增强较淡的色彩。", fontSize = 12.sp, color = tones.secondaryInk)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MirrorActionButton(onClick = { onChange(current.change(parameters, 0f)) },
                enabled = enabled && current.value(parameters) != 0f) { Text("重置此项") }
            MirrorActionButton(onClick = { onChange(parameters.copy(exposureEv = 0f, brightness = 0f, contrast = 0f,
                highlights = 0f, shadows = 0f, saturation = 0f, vibrance = 0f, temperature = 0f)) },
                enabled = enabled && FilmAdjustment.entries.any { it.value(parameters) != 0f }) { Text("微调归零") }
        }
        if(showGrain && !compact) {
        Text("胶片颗粒", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilmGrainIntensity.entries.forEach { intensity ->
                MirrorChoice(parameters.grainIntensity == intensity,
                    { onChange(parameters.copy(grainIntensity = intensity)) }, Modifier.weight(1f), enabled = enabled,
                    label = { Text(when (intensity) {
                        FilmGrainIntensity.LOW -> "低"; FilmGrainIntensity.MEDIUM -> "中"; FilmGrainIntensity.HIGH -> "高"
                    }) })
            }
        }
        Text("中档保留原有颗粒强度。", style = MaterialTheme.typography.bodySmall, color = tones.secondaryInk)
        }
    }
    if(compact) Column(Modifier.padding(horizontal=20.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) { content() }
    else MirrorControlSection("基础微调") { content() }
}
