@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.lumixfilmmirror.app

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixfilmmirror.design.LocalFilmMaterials
import com.lumixfilmmirror.design.MirrorFonts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MirrorSettings(personal: MirrorPersonalState, state: WorkspaceState, workspace: WorkspaceViewModel,
    onBack: () -> Unit, onTools: () -> Unit) {
    val tones = LocalFilmMaterials.current
    var licences by rememberSaveable { mutableStateOf(false) }
    var widgets by rememberSaveable { mutableStateOf(false) }
    var donation by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundAction("返回主页", DotGlyph.LEFT, onClick = onBack)
            Column(Modifier.padding(start = 12.dp).weight(1f)) { MirrorLayeredTitle("Personal","Settings",tones.ink,tones.amber,compact=true) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                MirrorControlSection("外观") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MirrorAppearance.entries.forEach { mode -> MirrorChoice(personal.appearance == mode,
                            { workspace.updatePersonal { it.copy(appearance = mode) } }, label = { Text(mode.title) }) }
                    }
                    Text("跟随系统时，页面随手机的浅色与深色设置切换。", style = MaterialTheme.typography.bodySmall, color = tones.secondaryInk)
                }
            }
            item {
                MirrorControlSection("动态与交互") {
                    SettingsToggle("减弱动画", "减少持续背景、模糊与转场，胶卷详情改为点击展开。", personal.reducedMotion) { value ->
                        workspace.updatePersonal { it.copy(reducedMotion = value) }
                    }
                    SettingsToggle("触感反馈", "胶卷滚动、收藏和拍立得切换时轻触振动。", personal.haptics) { value ->
                        workspace.updatePersonal { it.copy(haptics = value) }
                    }
                    SettingsToggle("交互音效", "切换胶卷、拍立得和收藏时播放轻提示音；独立于震动，遵循系统静音与提示音音量。", personal.soundEffects) { value ->
                        workspace.updatePersonal { it.copy(soundEffects = value) }
                    }
                    SettingsToggle("胶卷自动展开", "滚动停留后展开介绍；关闭后点按查看。", personal.automaticFilmDetails) { value ->
                        workspace.updatePersonal { it.copy(automaticFilmDetails = value) }
                    }
                    SettingsToggle("显影与反转动画", "每批播放一次，也可随时跳过；减弱动画时直接进入画框。", personal.revealAnimation) { value ->
                        workspace.updatePersonal { it.copy(revealAnimation = value) }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutputControls(state, workspace)
                    Text("编辑与水印页使用同一组选项，自动保留最近一次选择。", style = MaterialTheme.typography.bodySmall, color = tones.secondaryInk)
                }
            }
            item {
                MirrorControlSection("素材、任务与存储") {
                    Text("查看照片批次、传输和导出任务，管理可重新生成的缓存。", color = tones.secondaryInk)
                    MirrorChoice(false, onTools, Modifier.fillMaxWidth(), label = { Text("打开任务与缓存管理 →") })
                    Text("RAW 仅提取内嵌 JPEG；原始文件保留。照片默认按有效输入尺寸的 100% 输出。",
                        style = MaterialTheme.typography.bodySmall, color = tones.secondaryInk)
                }
            }
            item {
                MirrorControlSection("支持开发", "感谢你支持 松映 · Lumatte 的持续开发。") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MirrorChoice(false, { donation = "alipay" }, Modifier.weight(1f), label = { Text("支付宝") })
                        MirrorChoice(false, { donation = "wechat" }, Modifier.weight(1f), label = { Text("微信") })
                    }
                    Text("自愿捐赠，金额由你决定。", style = MaterialTheme.typography.bodySmall, color = tones.secondaryInk)
                }
            }
            item {
                MirrorControlSection("关于") {
                    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
                    Text("松映 · Lumatte · $version")
                    Text("本应用使用 OPPO Sans 4.0 字体", style = MaterialTheme.typography.bodySmall)
                    Text("为松下相机而作的独立第三方胶片暗房。",color=tones.secondaryInk)
                    MirrorTextAction("桌面小组件 →",{widgets=true},Modifier.fillMaxWidth())
                    MirrorTextAction("开源组件与许可 →", { licences = true }, Modifier.fillMaxWidth())
                    Text("连接、浏览与传输松下相机照片，提供胶片模拟、LUT 与画框。",
                        style = MaterialTheme.typography.bodySmall, color = tones.secondaryInk)
                }
            }
        }
    }
    if (licences) MirrorLicences { licences = false }
    if (widgets) DesktopWidgetsDialog { widgets=false }
    donation?.let { method -> DonationCode(method, workspace) { donation = null } }
}

@Composable
private fun SettingsToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall, color = LocalFilmMaterials.current.secondaryInk)
        }
        MirrorToggle(checked, title, onValueChange = onChange)
    }
}

@Composable
private fun DonationCode(method: String, workspace: WorkspaceViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val alipay = method == "alipay"
    val resource = if (alipay) R.drawable.donation_alipay else R.drawable.donation_wechat
    val height = (LocalConfiguration.current.screenHeightDp * .6f).dp
    MirrorDialog(if (alipay) "支付宝捐赠" else "微信捐赠", onDismiss = onDismiss) {
        Image(painterResource(resource), if (alipay) "支付宝收款二维码" else "微信收款二维码",
            Modifier.fillMaxWidth().height(height).background(Color.White), contentScale = ContentScale.Fit)
        Text("保存二维码后，在对应支付应用的扫一扫中选择相册。", style = MaterialTheme.typography.bodySmall)
        MirrorButton(onClick = {
            saving = true
            scope.launch {
                try { saveDonationCode(context, resource, method, alipay); workspace.notice("捐赠二维码已保存到相册") }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { workspace.notice(error.message ?: "二维码保存失败") }
                finally { saving = false }
            }
        }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "正在保存…" else "保存原始二维码") }
    }
}

/** Copy the supplied bytes exactly, publishing only after the write succeeds. */
private suspend fun saveDonationCode(context: Context, resource: Int, method: String, jpeg: Boolean) = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "LumixFilmMirror-donation-$method-${System.currentTimeMillis()}.${if (jpeg) "jpg" else "png"}")
        put(MediaStore.Images.Media.MIME_TYPE, if (jpeg) "image/jpeg" else "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LumixFilmMirror")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "无法创建相册文件" }
    var published = false
    try {
        context.resources.openRawResource(resource).use { input ->
            checkNotNull(resolver.openOutputStream(uri)).use { output -> input.copyTo(output) }
        }
        withContext(NonCancellable) {
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
            published = true
        }
    } catch (error: Throwable) { if (!published) withContext(NonCancellable) { resolver.delete(uri, null, null) }; throw error }
}
