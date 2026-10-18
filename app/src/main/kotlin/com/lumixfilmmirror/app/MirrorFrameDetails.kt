@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.lumixfilmmirror.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.model.*
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun MirrorFrameDetails(state: WorkspaceState, workspace: WorkspaceViewModel,
    onFont: (String) -> Unit, onArt: () -> Unit) {
    val frame = state.frame
    val enabled = !state.busy
    val tones = LocalFilmMaterials.current
    val assets = LocalContext.current.applicationContext.assets
    // Match the bundled typefaces used by FrameTypography; load once, not on selection.
    val signatureFaces = remember(assets) {
        FrameFont.entries.associateWith { font ->
            val file = when (font) {
                FrameFont.SANS -> "film_sans_regular.otf"
                FrameFont.TYPEWRITER -> "print_typewriter.ttf"
                FrameFont.SCRIPT -> "print_script.ttf"
                FrameFont.SERIF -> "print_serif.ttf"
                FrameFont.DISPLAY -> "print_display.ttf"
                FrameFont.SPACED -> "print_spaced.ttf"
                FrameFont.GREAT_VIBES -> "print_great_vibes.ttf"
            }
            FontFamily(android.graphics.Typeface.createFromAsset(assets, "frames/fonts/$file"))
        }
    }
    var extra by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (frame.preset == FramePreset.NONE) {
            MirrorControlSection("照片本身") { Text("选择画框后，可添加签名、拍摄信息与纸边。", color = tones.secondaryInk) }
        } else {
            MirrorControlSection("签名与拍摄信息") {
                MirrorTextField(frame.signature, { workspace.updateFrame(frame.copy(signature = it.take(240))) }, "签名 / 水印文字", enabled = enabled)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显示文字与拍摄信息", Modifier.weight(1f))
                    MirrorToggle(frame.showPhotoMetadata, "显示标题与拍摄信息", enabled) { workspace.updateFrame(frame.copy(showPhotoMetadata = it)) }
                }
                val metadata = state.current?.metadata
                data class MetadataChoice(val label: String, val value: String, val checked: Boolean,
                    val change: (Boolean) -> FrameOptions)
                listOf(
                    MetadataChoice("相机机型", metadata?.frameCameraName.orEmpty(), frame.showCamera) { frame.copy(showCamera = it) },
                    MetadataChoice("镜头", metadata?.lens.orEmpty(), frame.showLens) { frame.copy(showLens = it) },
                    MetadataChoice("焦距", metadata?.focalLength.orEmpty(), frame.showFocalLength) { frame.copy(showFocalLength = it) },
                    MetadataChoice("光圈", metadata?.aperture.orEmpty(), frame.showAperture) { frame.copy(showAperture = it) },
                    MetadataChoice("快门", metadata?.shutter.orEmpty(), frame.showShutter) { frame.copy(showShutter = it) },
                    MetadataChoice("ISO", metadata?.iso.orEmpty(), frame.showIso) { frame.copy(showIso = it) },
                    MetadataChoice("拍摄时间", metadata?.capturedAt.orEmpty(), frame.showCapturedAt) { frame.copy(showCapturedAt = it) }
                ).forEach { choice ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(choice.label)
                            Text(choice.value.ifBlank { "当前照片未检测到，成片自动隐藏" },
                                style = MaterialTheme.typography.bodySmall, color = tones.secondaryInk)
                        }
                        MirrorToggle(choice.checked, "显示${choice.label}", enabled && frame.showPhotoMetadata) {
                            workspace.updateFrame(choice.change(it))
                        }
                    }
                }
                Text("开关应用于整批；每张照片只显示实际检测到的数据。", style = MaterialTheme.typography.bodySmall, color = tones.secondaryInk)
                Text("签名字体", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FrameFont.entries.forEach { font ->
                        MirrorChoice(frame.signatureFont == font && frame.signatureFontKey == null,
                            { workspace.updateFrame(frame.copy(signatureFont = font, signatureFontKey = null)) }, enabled = enabled,
                            label = { Text(font.title, fontFamily = signatureFaces.getValue(font)) })
                    }
                }
                MirrorTextAction(if (frame.signatureFontKey == null) "导入签名字体 ↗" else "更换已导入的字体 ↗",
                    { onFont("signature") }, enabled = enabled)
            }
            MirrorControlSection("构图与纸边") {
                Text("画布比例", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FrameRatio.entries.forEach { ratio ->
                        MirrorChoice(frame.ratio == ratio, { workspace.updateFrame(frame.copy(ratio = ratio)) }, enabled = enabled,
                            label = { Text(ratio.title) })
                    }
                }
                MirrorParameterSlider("纸边", "${(frame.borderFraction * 100).roundToInt()}%", frame.borderFraction, .02f.. .12f, enabled) {
                    workspace.updateFrame(frame.copy(borderFraction = it))
                }
                Text("画布方向", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, portrait) in listOf("跟随照片" to null, "横向" to false, "竖向" to true))
                        MirrorChoice(frame.portrait == portrait, { workspace.updateFrame(frame.copy(portrait = portrait)) }, enabled = enabled,
                            label = { Text(label) })
                }
            }
            if (frame.preset in setOf(FramePreset.PALETTE, FramePreset.FILM)) MirrorControlSection(
                if (frame.preset == FramePreset.FILM) "暗盒配色" else "纸面色彩", "自动色彩从当前照片中提取。") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, colour) in listOf("自动取色" to null, "暖白" to 0xFFF7F4EC.toInt(), "黑色" to 0xFF171A18.toInt()))
                        MirrorChoice(frame.fixedColorArgb == colour, { workspace.updateFrame(frame.copy(fixedColorArgb = colour)) }, enabled = enabled,
                            label = { Text(label) })
                }
                if (frame.fixedColorArgb == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FrameMood.entries.forEach { mood ->
                        MirrorChoice(frame.mood == mood, { workspace.updateFrame(frame.copy(mood = mood)) }, enabled = enabled,
                            label = { Text(mood.title) })
                    }
                }
            }
            if (frame.preset == FramePreset.FILM) MirrorControlSection("暗盒标签", "标签风格决定暗盒的外观。") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("120 暗盒", Modifier.weight(1f))
                    MirrorToggle(frame.roll120, "120 暗盒", enabled) { workspace.updateFrame(frame.copy(roll120 = it)) }
                }
                MirrorTextField(frame.filmBrand, { workspace.updateFrame(frame.copy(filmBrand = it.take(60))) }, "胶卷品牌", enabled = enabled)
                MirrorTextField(frame.filmName, { workspace.updateFrame(frame.copy(filmName = it.take(80))) }, "胶卷名称", enabled = enabled)
                MirrorTextField(frame.filmNotes, { workspace.updateFrame(frame.copy(filmNotes = it.take(60))) }, "片尾文字", enabled = enabled)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, bw) in listOf("跟随胶卷" to null, "彩色" to false, "黑白" to true))
                        MirrorChoice(frame.filmBlackWhite == bw, { workspace.updateFrame(frame.copy(filmBlackWhite = bw)) }, enabled = enabled,
                            label = { Text(label) })
                }
            }
            MirrorChoice(extra, { extra = !extra }, Modifier.fillMaxWidth(), label = {
                Text(if (extra) "收起更多文字与字体 ↑" else "更多文字与字体 ↓")
            })
            if (extra) {
                MirrorControlSection("标题与注记", "不同画框使用各自的文字区域。") {
                    if (!frame.showPhotoMetadata) Text("打开上方“显示标题与拍摄信息”后，这些文字会出现在成片中。",
                        color = tones.secondaryInk, style = MaterialTheme.typography.bodySmall)
                    for (target in listOf("title", "subtitle", "footer")) {
                        val value = when (target) { "title" -> frame.title; "subtitle" -> frame.subtitle; else -> frame.footer }
                        Text(when (target) { "title" -> "标题"; "subtitle" -> "副标题"; else -> "页脚" }, style = MaterialTheme.typography.labelLarge)
                        MirrorTextField(value.orEmpty(), { text -> workspace.updateFrame(when (target) {
                            "title" -> frame.copy(title = text.take(240).ifBlank { null })
                            "subtitle" -> frame.copy(subtitle = text.take(240).ifBlank { null })
                            else -> frame.copy(footer = text.take(240).ifBlank { null }) }) },
                            if (target == "title") "留空使用照片信息" else "可选文字", enabled = enabled && frame.showPhotoMetadata)
                        MirrorTextAction("导入该区域字体 ↗", { onFont(target) }, enabled = enabled && frame.showPhotoMetadata)
                    }
                }
                if (frame.preset == FramePreset.PALETTE) MirrorControlSection("装饰图") {
                    MirrorChoice(false, onArt, Modifier.fillMaxWidth(), enabled = enabled, label = {
                        Text(if (frame.imageKey == null) "导入装饰图 ↗" else "更换装饰图 ↗")
                    })
                    if (frame.imageKey != null) MirrorTextAction("移除装饰图", { workspace.updateFrame(frame.copy(imageKey = null)) }, enabled = enabled)
                }
            }
        }
    }
}
