package com.lumixfilmmirror.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.lumixfilmmirror.design.LocalFilmMaterials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class LicenceEntry(val title: String, val note: String, val files: List<String>)

/** Full bundled notices are readable offline; font provenance is not replaced by a blanket licence. */
@Composable
internal fun MirrorLicences(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val entries = remember {
        listOf(
            LicenceEntry("松映 · Pineframe", "本项目新应用代码 · GNU GPLv3；第三方组件与素材继续采用各自许可。", listOf("licenses/LumixFilmMirror-GPL-3.0.txt")),
            LicenceEntry("AndroidX / Jetpack Compose / Room", "Android Open Source Project · Apache 2.0", listOf("licenses/Apache-2.0.txt")),
            LicenceEntry("Kotlin / kotlinx.coroutines", "JetBrains · Apache 2.0", listOf("licenses/Apache-2.0.txt")),
            LicenceEntry("OkHttp / Okio", "Square · Apache 2.0", listOf("licenses/Apache-2.0.txt")),
            LicenceEntry("LibRaw 0.22.2", "LibRaw LLC · 选择 LGPL 2.1 许可；仅用于提取内嵌 JPEG。源码与修改记录保存在项目 third_party/libraw 与 native/raw-preview。",
                listOf("licenses/libraw-COPYRIGHT.txt", "licenses/libraw-LGPL.txt")),
            LicenceEntry("spektrafilm 胶片资料", "Andrea Volpato · CC BY-SA 4.0；移动模型为派生资料，保留来源和处理记录。",
                listOf("film/mobile-v1/SPEKTRAFILM_LICENSE.txt")),
            LicenceEntry("自有黑白模型", "Silver Neutral / Contrast · 本项目自有艺术风格 · GPL-3.0-only；没有品牌胶卷实测标定。",
                listOf("film/mobile-monochrome-v1/NOTICE.txt", "film/mobile-monochrome-v1/LICENSE.txt")),
            LicenceEntry("Fotufilm 六款移动胶片模型", "Fotufilm / MUAStudio Inc. · 胶片配置与派生色彩表 CC BY-SA 4.0；计算适配 Apache 2.0。移动近似，非完整上游引擎或实测胶片一致性声明。",
                listOf("film/mobile-fotufilm-v1/NOTICE.txt", "film/mobile-fotufilm-v1/UPSTREAM-NOTICE.txt",
                    "film/mobile-fotufilm-v1/CC-BY-SA-4.0.txt", "film/mobile-fotufilm-v1/Apache-2.0.txt")),
            LicenceEntry("Cormorant Garamond", "Christian Thalmann · SIL OFL 1.1", listOf("licenses/cormorant-garamond-OFL.txt")),
            LicenceEntry("Instrument Serif", "Instrument Serif Project Authors · SIL OFL 1.1 · 窄衬线斜体",
                listOf("licenses/instrument-serif-OFL.txt")),
            LicenceEntry("Great Vibes / Dancing Script", "SIL OFL 1.1", listOf("licenses/great-vibes-OFL.txt", "licenses/dancingscript-OFL.txt")),
            LicenceEntry("华康翩翩体 W5-A", "用户提供的本机字体副本；仅用于包含中文的 LUT 名称。未附公开分发许可文本。", emptyList()),
            LicenceEntry("Source Han Serif / Source Han Sans", "Adobe · SIL OFL 1.1", listOf("licenses/source-han-serif-OFL.txt", "frames/licenses/SourceHanSans-OFL.txt")),
            LicenceEntry("画框字体", "Playfair Display、Julius Sans One、Cutive Mono、Abril Fatface、Dancing Script、Great Vibes · SIL OFL 1.1",
                listOf("frames/licenses/playfairdisplay-OFL.txt", "frames/licenses/juliussansone-OFL.txt", "frames/licenses/cutivemono-OFL.txt",
                    "frames/licenses/abrilfatface-OFL.txt", "frames/licenses/dancingscript-OFL.txt", "frames/licenses/greatvibes-OFL.txt")),
            LicenceEntry("指定本机字体与参考资产", "OPPO Sans、NHL Minnesota、AaSpot 点阵来自用户指定本机字体。具体版本的公开嵌入条款仍需补齐，不能将其统称为 OFL。恢复资产来源见项目 docs/design/assets.md。", emptyList()),
            LicenceEntry("Lut2Photo 算法参考", "qh7574/Lut2Photo-Android（MPL 2.0）。参考其 Bayer 抖动及亮度颗粒思路；本应用实现独立编写，没有打包该项目源码或二进制。", emptyList()),
            LicenceEntry("LUT 与创作者例图", "内置和下载的 LUT、例图与头像保留各自创作者权利；不由以上软件许可统一授权。捐赠二维码由开发者提供。", emptyList())
        )
    }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val active = selected?.let { entries.getOrNull(it) }
    var text by remember(active) { mutableStateOf<String?>(null) }
    LaunchedEffect(active) {
        if (active != null) text = withContext(Dispatchers.IO) {
            active.files.joinToString("\n\n") { file ->
                try { context.assets.open(file).bufferedReader().use { it.readText() } }
                catch (_: java.io.IOException) { "许可文本读取失败：$file" }
            }
        }
    }
    MirrorDialog("开源组件与许可", onDismiss = onDismiss) {
        if (active != null) {
            MirrorTextAction("← 返回许可列表", { selected = null })
            Text(active.title, style = MaterialTheme.typography.titleMedium)
            Text(active.note, style = MaterialTheme.typography.bodySmall, color = LocalFilmMaterials.current.secondaryInk)
            androidx.compose.foundation.text.selection.SelectionContainer { Text(text ?: "正在读取…", style = MaterialTheme.typography.bodySmall) }
        } else {
            Text("组件及素材沿用各自许可，点击可离线阅读随应用附带的完整文本。", style = MaterialTheme.typography.bodySmall)
            entries.forEachIndexed { index, entry ->
                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                Text(entry.note, style = MaterialTheme.typography.bodySmall, color = LocalFilmMaterials.current.secondaryInk)
                if (entry.files.isNotEmpty()) MirrorTextAction("阅读许可正文 →", { selected = index })
            }
        }
    }
}
