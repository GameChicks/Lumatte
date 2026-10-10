package app.filmcompanion.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.filmcompanion.data.FilmDao
import app.filmcompanion.data.RollEntity
import app.filmcompanion.data.ScanEntity
import app.filmcompanion.media.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class EditStep(val title: String) { INPUT("原片"), NEGATIVE("去色罩"), BORDER("边框水印"), EXPORT("导出") }

@Composable
fun PhotoEditorDialog(roll: RollEntity,scan: ScanEntity,dao: FilmDao,onSaved: () -> Unit,onClose: () -> Unit) {
    val context=LocalContext.current; val scope=rememberCoroutineScope()
    val service=remember { SinglePhotoExportService(context,dao) }
    var recipe by remember(scan.id) { mutableStateOf(PhotoRecipeStore(context).load(scan.id)) }
    var step by remember { mutableStateOf(EditStep.INPUT) }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var labels by remember { mutableStateOf("" to "") }
    var status by remember { mutableStateOf("") }
    var rendering by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }
    var png by remember { mutableStateOf(false) }
    var longEdge by remember { mutableIntStateOf(3072) }
    var advanced by remember { mutableStateOf(false) }
    LaunchedEffect(scan.id) {
        try { source=ScanDecoder(context).decode(Uri.parse(scan.sourceUri),scan.fileName,1200); labels=service.labels(roll,scan) }
        catch(error: kotlinx.coroutines.CancellationException) { throw error }
        catch(error: Exception) { status=error.message ?: "原片读取失败，请重新关联" }
    }
    LaunchedEffect(source,recipe,step,labels) {
        val original=source ?: return@LaunchedEffect
        rendering=true
        try {
            delay(90)
            val shown=if(step==EditStep.NEGATIVE || step==EditStep.INPUT) recipe.copy(border=PhotoBorder.NONE) else recipe
            preview=withContext(Dispatchers.Default) { PhotoRenderer(context).render(original,shown,labels.first,labels.second) }
        } catch(error: kotlinx.coroutines.CancellationException) { throw error }
        catch(error: Exception) { status=error.message ?: "预览失败" }
        finally { rendering=false }
    }
    fun autoNegative() {
        source?.let { bitmap ->
            val pixels=IntArray(bitmap.width*bitmap.height); bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            recipe=recipe.copy(negative=NegativeMath.auto(pixels,recipe.negative))
        }
    }
    fun export(tree: Uri?=null) {
        val snapshot=recipe; val edge=longEdge; val lossless=png
        busy=true; status="正在处理照片并写入 EXIF…"
        scope.launch {
            try {
                service.saveEdit(scan,snapshot,source ?: error("原片尚未载入")); onSaved()
                val result=service.export(roll,scan,snapshot,edge,lossless,tree)
                status="已导出 ${result.width} × ${result.height}\n${result.location}\nEXIF 已同步，可在系统相册或文件管理器查看。"
                exported=true
            } catch(error: kotlinx.coroutines.CancellationException) { throw error }
            catch(_: OutOfMemoryError) { status="内存不足，请选择较小尺寸后重试" }
            catch(error: Exception) { status="导出失败：${error.message}" }
            finally { busy=false }
        }
    }
    val directoryPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree -> if(tree!=null) export(tree) }
    fun updateNegative(value: NegativeSettings) { exported=false; recipe=recipe.copy(negative=value) }
    Dialog(onDismissRequest={if(!busy) onClose()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Column(Modifier.fillMaxSize().background(FilmColors.Library).systemBarsPadding().padding(18.dp).testTag("photo-editor")) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(step.title,color=FilmColors.Ink,fontSize=23.sp,fontWeight=FontWeight.Bold)
                    Text("FRAME ${scan.frameNumber ?: "—"}  /  ${scan.fileName}",color=FilmColors.Muted,fontSize=10.sp,maxLines=1)
                }
                SoftAction("关闭",enabled=!busy,onClick=onClose)
            }
            Row(Modifier.fillMaxWidth().padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf(EditStep.NEGATIVE,EditStep.BORDER,EditStep.EXPORT).forEach { stage ->
                    SoftAction(stage.title,Modifier.weight(1f),selected=step==stage,enabled=source!=null && !busy,onClick={step=stage;picking=false;exported=false})
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF131513))
                .pointerInput(picking,source,busy,step) {
                    if(picking && !busy && step==EditStep.NEGATIVE) detectTapGestures { point ->
                        source?.let { bitmap ->
                            val scale=minOf(size.width.toFloat()/bitmap.width,size.height.toFloat()/bitmap.height)
                            val x=((point.x-(size.width-bitmap.width*scale)/2)/scale).toInt()
                            val y=((point.y-(size.height-bitmap.height*scale)/2)/scale).toInt()
                            if(x in 0 until bitmap.width && y in 0 until bitmap.height) {
                                var r=0;var g=0;var b=0;var count=0
                                for(py in maxOf(0,y-2)..minOf(bitmap.height-1,y+2)) for(px in maxOf(0,x-2)..minOf(bitmap.width-1,x+2)) {
                                    val c=bitmap.getPixel(px,py);r+=(c ushr 16) and 255;g+=(c ushr 8) and 255;b+=c and 255;count++
                                }
                                updateNegative(recipe.negative.copy(enabled=true,maskR=maxOf(1,r/count),maskG=maxOf(1,g/count),maskB=maxOf(1,b/count)))
                                picking=false
                            }
                        }
                    }
                },contentAlignment=Alignment.Center) {
                val shown=if(picking || comparing) source else preview
                shown?.let { Image(it.asImageBitmap(),if(picking || comparing) "原扫描" else "编辑后的成片",Modifier.fillMaxSize(),contentScale=ContentScale.Fit) }
                    ?: Text(if(status.isBlank()) "正在读取原片…" else "无法读取原片",color=Color.LightGray)
                if(picking) Text("点选未曝光的片基区域",Modifier.align(Alignment.TopCenter).padding(12.dp).background(Color(0xCC101310)).padding(8.dp),color=Color.White,fontSize=12.sp)
            }
            if(step!=EditStep.INPUT && source!=null) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(if(rendering) "正在更新预览…" else "预览与导出使用相同参数",color=FilmColors.Muted,fontSize=10.sp,modifier=Modifier.weight(1f))
                TextButton(onClick={comparing=!comparing},enabled=!busy) { Text(if(comparing) "查看成片" else "对比原片",fontSize=11.sp,color=FilmColors.Ink) }
            }
            Column(Modifier.fillMaxWidth().heightIn(max=300.dp).verticalScroll(rememberScrollState()).padding(top=12.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                when(step) {
                    EditStep.INPUT -> {
                        Text("这张照片需要去色罩吗？",color=FilmColors.Ink,fontSize=19.sp,fontWeight=FontWeight.SemiBold)
                        Text("橙色／棕色的扫描负片先转正；已冲扫好的照片可直接加边框。",color=FilmColors.Muted,fontSize=12.sp)
                        SoftAction("这是负片，开始去色罩",Modifier.fillMaxWidth(),selected=true,enabled=source!=null,onClick={if(!recipe.negative.enabled) autoNegative();step=EditStep.NEGATIVE})
                        SoftAction("已是正片，编辑边框",Modifier.fillMaxWidth(),enabled=source!=null,onClick={recipe=recipe.copy(negative=recipe.negative.copy(enabled=false));step=EditStep.BORDER})
                    }
                    EditStep.NEGATIVE -> {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("负片转正",color=FilmColors.Ink,modifier=Modifier.weight(1f),fontSize=14.sp)
                            Switch(recipe.negative.enabled,{ updateNegative(recipe.negative.copy(enabled=it)); if(it && recipe.negative.maskR==255 && recipe.negative.maskG==255) autoNegative() },enabled=!busy)
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            SoftAction("自动去色罩",Modifier.weight(1f),enabled=!busy && source!=null,onClick=::autoNegative)
                            SoftAction(if(picking) "取消取色" else "点选片基",Modifier.weight(1f),selected=picking,enabled=!busy,onClick={picking=!picking})
                            SoftAction("重置",enabled=!busy,onClick={recipe=recipe.copy(negative=NegativeSettings());autoNegative()})
                        }
                        EditSlider("曝光",recipe.negative.exposure,-3f..3f,"%.1f EV".format(recipe.negative.exposure),!busy) { updateNegative(recipe.negative.copy(exposure=it)) }
                        EditSlider("中间调",recipe.negative.gamma,0.3f..2.5f,"%.2f".format(recipe.negative.gamma),!busy) { updateNegative(recipe.negative.copy(gamma=it)) }
                        EditSlider("对比度",recipe.negative.contrast,0.5f..2f,"%.2f".format(recipe.negative.contrast),!busy) { updateNegative(recipe.negative.copy(contrast=it)) }
                        TextButton(onClick={advanced=!advanced}) { Text(if(advanced) "收起色彩微调" else "色温、色调与 RGB 片基",color=FilmColors.Ink,fontSize=12.sp) }
                        if(advanced) {
                            EditSlider("色温",recipe.negative.temperature,-1f..1f,(recipe.negative.temperature*100).roundToInt().toString(),!busy) { updateNegative(recipe.negative.copy(temperature=it)) }
                            EditSlider("色调",recipe.negative.tint,-1f..1f,(recipe.negative.tint*100).roundToInt().toString(),!busy) { updateNegative(recipe.negative.copy(tint=it)) }
                            EditSlider("片基 R",recipe.negative.maskR.toFloat(),1f..255f,recipe.negative.maskR.toString(),!busy) {updateNegative(recipe.negative.copy(maskR=it.roundToInt()))}
                            EditSlider("片基 G",recipe.negative.maskG.toFloat(),1f..255f,recipe.negative.maskG.toString(),!busy) {updateNegative(recipe.negative.copy(maskG=it.roundToInt()))}
                            EditSlider("片基 B",recipe.negative.maskB.toFloat(),1f..255f,recipe.negative.maskB.toString(),!busy) {updateNegative(recipe.negative.copy(maskB=it.roundToInt()))}
                        }
                    }
                    EditStep.BORDER -> {
                        Text("选择边框，或保留纯照片",color=FilmColors.Ink,fontSize=14.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                            PhotoBorder.entries.forEach { border -> SoftAction(border.title,selected=recipe.border==border,enabled=!busy,onClick={recipe=recipe.copy(border=border);exported=false}) }
                        }
                        if(recipe.border!=PhotoBorder.NONE) {
                            EditSlider("留白宽度",recipe.borderWidth,0.02f..0.12f,"${(recipe.borderWidth*100).roundToInt()}%",!busy) {recipe=recipe.copy(borderWidth=it)}
                            OutlinedTextField(recipe.signature,{recipe=recipe.copy(signature=it.take(100))},label={Text("作者签名，可留空")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text("显示相机、胶卷与拍摄信息",color=FilmColors.Ink,fontSize=12.sp,modifier=Modifier.weight(1f))
                                Switch(recipe.showMetadata,{recipe=recipe.copy(showMetadata=it)},enabled=!busy)
                            }
                        }
                    }
                    EditStep.EXPORT -> {
                        Text("准备导出",color=FilmColors.Ink,fontSize=18.sp,fontWeight=FontWeight.SemiBold)
                        if(recipe.border==PhotoBorder.FILM) {
                            Text("透明 WebP · 成片长边8192 px · 小于20 MB",color=FilmColors.Ink,fontSize=13.sp)
                            Text("保留完整照片与透明底，写入 EXIF。8K提升边框和印字的精细度；照片细节取决于原扫描。",color=FilmColors.Muted,fontSize=11.sp)
                        } else {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            SoftAction("JPEG · 95%",Modifier.weight(1f),selected=!png,enabled=!busy,onClick={png=false;exported=false})
                            SoftAction("PNG · 无损",Modifier.weight(1f),selected=png,enabled=!busy,onClick={png=true;exported=false})
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf(2048,3072,4096).forEach { edge -> SoftAction("$edge px",Modifier.weight(1f),selected=longEdge==edge,enabled=!busy,onClick={longEdge=edge;exported=false}) }
                        }
                        Text("照片长边上限，不放大小图；边框额外计入尺寸。新建导出文件夹，并同步 EXIF。",color=FilmColors.Muted,fontSize=11.sp)
                        }
                        Text("${if(recipe.negative.enabled) "已去色罩" else "保持原色"}  ·  ${recipe.border.title}",color=FilmColors.Ink,fontSize=12.sp)
                    }
                }
                if(status.isNotBlank()) Text(status,color=FilmColors.Muted,fontSize=11.sp)
            }
            if(step!=EditStep.INPUT) {
                Spacer(Modifier.height(12.dp))
                if(step==EditStep.NEGATIVE) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    SoftAction("直接导出正片",Modifier.weight(1f),enabled=!busy && !rendering,onClick={recipe=recipe.copy(border=PhotoBorder.NONE);picking=false;step=EditStep.EXPORT})
                    SoftAction("加边框水印 →",Modifier.weight(1f),selected=true,enabled=!busy && !rendering,onClick={step=EditStep.BORDER;picking=false})
                } else SoftAction(when {busy -> "正在导出…"; exported -> "完成";step==EditStep.BORDER -> "下一步：导出";else -> "导出照片"},
                    Modifier.fillMaxWidth().height(52.dp),selected=true,enabled=!busy && !rendering && preview!=null,onClick={
                        when {exported -> onClose();step==EditStep.BORDER -> step=EditStep.EXPORT;Build.VERSION.SDK_INT>=29 -> export();else -> directoryPicker.launch(null)}
                    })
            }
        }
    }
}

@Composable
private fun EditSlider(label: String,value: Float,range: ClosedFloatingPointRange<Float>,caption: String,enabled: Boolean,onChange: (Float) -> Unit) {
    Column {
        Row {Text(label,color=FilmColors.Ink,fontSize=12.sp,modifier=Modifier.weight(1f));Text(caption,color=FilmColors.Muted,fontSize=11.sp)}
        Slider(value.coerceIn(range),onChange,valueRange=range,enabled=enabled,modifier=Modifier.fillMaxWidth().height(32.dp),
            colors=SliderDefaults.colors(thumbColor=FilmColors.Ink,activeTrackColor=FilmColors.Ink,inactiveTrackColor=FilmColors.Muted.copy(alpha=0.2f)))
    }
}
