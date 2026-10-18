package com.lumixfilmmirror.app

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lumixfilmmirror.design.FilmMirrorTheme
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.media.FilmCatalogue
import com.lumixfilmmirror.media.FilmPreset
import kotlinx.coroutines.CancellationException

private data class WidgetChoices(val films: List<FilmPreset>,val personal: MirrorPersonalState)

class MirrorWidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        val pinStyle=intent.getStringExtra(MirrorWidgets.EXTRA_STYLE)?.let { raw -> WidgetStyle.entries.firstOrNull { it.name==raw } }
        val pinning=id==AppWidgetManager.INVALID_APPWIDGET_ID && pinStyle!=null
        if(!pinning && AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider?.packageName!=packageName) { finish();return }
        val config=if(pinning)MirrorWidgets.defaultConfig(requireNotNull(pinStyle)) else MirrorWidgets.read(this,id)
        setContent {
            var problem by remember { mutableStateOf<String?>(null) }
            val choices by produceState<WidgetChoices?>(null) {
                try { value=WidgetChoices(FilmCatalogue(this@MirrorWidgetConfigurationActivity).load(),MirrorPreferences(this@MirrorWidgetConfigurationActivity).read()) }
                catch(error: CancellationException) { throw error }
                catch(_: Exception) { problem="无法读取设置，请打开应用后重新添加小组件。" }
            }
            val dark=when(choices?.personal?.appearance) { MirrorAppearance.LIGHT -> false;MirrorAppearance.DARK -> true;else -> isSystemInDarkTheme() }
            FilmMirrorTheme(dark) {
                Surface(Modifier.fillMaxSize()) {
                    val data=choices
                    if(data==null)Column(Modifier.fillMaxSize().padding(32.dp),verticalArrangement=Arrangement.Center) {
                        Text(problem ?: "正在准备小组件…")
                        if(problem!=null)TextButton(onClick={finish()}) { Text("返回") }
                    } else WidgetConfiguration(config,data,onCancel={finish()}) { selected ->
                        if(pinning) {
                            val accepted=runCatching { MirrorWidgets.requestPin(this,selected) }.getOrDefault(false)
                            if(accepted)finish() else problem="请从桌面的小组件列表添加，再选择胶卷与颜色。"
                        } else if(MirrorWidgets.save(this,id,selected)) {
                            MirrorWidgets.update(this,id)
                            setResult(RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id));finish()
                        } else problem="小组件设置未能保存，请重试。"
                    }
                    if(data!=null && problem!=null)Text(problem.orEmpty(),Modifier.padding(24.dp),color=MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun WidgetConfiguration(initial: MirrorWidgetConfig,choices: WidgetChoices,onCancel: () -> Unit,onSave: (MirrorWidgetConfig) -> Unit) {
    var filmId by rememberSaveable { mutableStateOf(initial.filmId) }
    var cameraId by rememberSaveable { mutableStateOf(initial.cameraId) }
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var hex by rememberSaveable { mutableStateOf(initial.hex) }
    var customHex by rememberSaveable { mutableStateOf(initial.hex) }
    var expanded by remember { mutableStateOf(false) }
    val camera=initial.style==WidgetStyle.CAMERA
    val film=choices.films.firstOrNull { it.id==filmId }
    val footer=if(camera)"连接相机 →" else film?.let { preset ->
        val speed=preset.editorialName().speed?.takeWhile { it.isDigit() }
        if(speed.isNullOrBlank())"BLACK & WHITE" else "35mm ISO $speed"
    } ?: "导入照片 →"
    val config=MirrorWidgetConfig(initial.style,filmId,cameraId,title.trim().ifBlank { if(camera)"松下相机" else film?.name ?: "胶片模拟" },footer,hex)
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically) {
            RoundAction("取消",DotGlyph.LEFT,onClick=onCancel)
            Text(initial.style.title,Modifier.padding(start=16.dp),style=MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            WidgetCardPreview(config,Modifier.align(Alignment.CenterHorizontally).size(240.dp))
            if(camera) {
                Text("选择机型",style=MaterialTheme.typography.titleMedium)
                choices.personal.cameras.forEach { saved ->
                    MirrorChoice(cameraId==saved.id,{cameraId=saved.id;title=saved.model},Modifier.fillMaxWidth(),label={Text(saved.name.ifBlank { saved.model })})
                }
                if(choices.personal.cameras.isEmpty())Text("可以先填写机型，点按小组件后在应用中连接。",color=LocalFilmMaterials.current.secondaryInk)
            } else {
                Box {
                    MirrorChoice(false,{expanded=true},Modifier.fillMaxWidth(),label={Text((film?.name ?: "选择胶卷")+" ▾")})
                    DropdownMenu(expanded,onDismissRequest={expanded=false},modifier=Modifier.heightIn(max=340.dp)) {
                        choices.films.forEach { stock -> DropdownMenuItem(text={Text(stock.name)},onClick={filmId=stock.id;title=stock.name;expanded=false}) }
                    }
                }
            }
            MirrorTextField(title,{title=it.take(60)},if(camera)"机型或显示名称" else "胶卷显示名称")
            Text("背景颜色",style=MaterialTheme.typography.titleMedium)
            widgetPalettes.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    row.forEach { palette ->
                        val swatch=remember(initial.style,palette.hex) { MirrorWidgets.background(config.copy(hex=palette.hex,title="",footer=""),120,64) }
                        DisposableEffect(swatch) { onDispose { swatch.recycle() } }
                        MirrorChoice(hex.equals(palette.hex,true),{hex=palette.hex;customHex=hex},Modifier.weight(1f),label={
                            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Image(swatch.asImageBitmap(),null,Modifier.fillMaxWidth().height(24.dp))
                                Text(palette.title)
                            }
                        })
                    }
                }
            }
            if(!hex.equals("4EADD8",true) && !hex.equals("F2AB54",true))Text("配色参考 Color Hunt；渐变与文字颜色已针对小组件调整。",style=MaterialTheme.typography.bodySmall,color=LocalFilmMaterials.current.secondaryInk)
            MirrorTextField(customHex,{ value ->
                customHex=value.removePrefix("#").take(6)
                if(customHex.matches(Regex("[0-9a-fA-F]{6}")))hex=customHex.uppercase()
            },"自定义颜色 · 六位 HEX")
            Text(if(camera)"点按后打开相机连接。" else "点按后为这卷胶片导入照片。",color=LocalFilmMaterials.current.secondaryInk)
            Spacer(Modifier.height(8.dp))
        }
        MirrorButton(onClick={onSave(config)},Modifier.fillMaxWidth().padding(20.dp),
            enabled=(camera || film!=null) && customHex.matches(Regex("[0-9a-fA-F]{6}"))) { Text("保存小组件") }
    }
}

@Composable
internal fun WidgetCardPreview(config: MirrorWidgetConfig,modifier: Modifier=Modifier) {
    val fontScale=LocalDensity.current.fontScale
    val bitmap=remember(config,fontScale) { MirrorWidgets.background(config,fontScale=fontScale) }
    DisposableEffect(bitmap) { onDispose { bitmap.recycle() } }
    Box(modifier) {
        Image(bitmap.asImageBitmap(),config.title+"，"+config.footer,Modifier.fillMaxSize())
        if(config.style!=WidgetStyle.CAMERA)Image(painterResource(R.drawable.widget_edit),null,
            Modifier.align(Alignment.TopEnd).padding(8.dp).size(48.dp).padding(14.dp))
    }
}

@Composable
internal fun DesktopWidgetsDialog(onDismiss: () -> Unit) {
    val context=LocalContext.current
    val manager=remember { AppWidgetManager.getInstance(context) }
    var problem by remember { mutableStateOf<String?>(null) }
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event -> if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME)refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val installed=remember(refresh) { WidgetStyle.entries.flatMap { manager.getAppWidgetIds(MirrorWidgets.component(context,it)).toList() }.distinct() }
    MirrorDialog("桌面小组件",onDismiss=onDismiss) { close ->
        Text("先选择胶卷、机型与颜色，再添加到桌面。胶卷组件可点右上角编辑；相机组件在下方已添加列表中编辑。")
        WidgetStyle.entries.forEach { style ->
            val config=if(style==WidgetStyle.CAMERA)MirrorWidgetConfig(style,title="松下相机",footer="连接相机 →",hex="F2AB54") else MirrorWidgetConfig(style)
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                WidgetCardPreview(config,Modifier.size(112.dp))
                Column(Modifier.weight(1f)) {
                    Text(style.title)
                    MirrorTextAction("添加到桌面",{
                        if(manager.isRequestPinAppWidgetSupported) {
                            context.startActivity(Intent(context,MirrorWidgetConfigurationActivity::class.java)
                                .putExtra(MirrorWidgets.EXTRA_STYLE,style.name))
                        } else problem="请从桌面的小组件列表添加。"
                    })
                }
            }
        }
        if(installed.isNotEmpty()) {
            Text("已添加的小组件",style=MaterialTheme.typography.titleMedium)
            installed.forEach { id ->
                val config=MirrorWidgets.read(context,id)
                MirrorTextAction("${config.title} · 编辑",{
                    context.startActivity(Intent(context,MirrorWidgetConfigurationActivity::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id))
                },Modifier.fillMaxWidth())
            }
        }
        problem?.let { Text(it,color=LocalFilmMaterials.current.secondaryInk) }
        MirrorTextAction("完成",close)
    }
}
