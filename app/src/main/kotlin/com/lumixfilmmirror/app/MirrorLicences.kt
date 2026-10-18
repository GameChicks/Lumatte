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
            LicenceEntry("松映 · Lumatte", "本项目新应用代码 · GNU GPLv3；第三方组件与素材继续采用各自许可。", listOf("licenses/LumixFilmMirror-GPL-3.0.txt")),
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
            LicenceEntry("栗壳坚坚体（LikeJianJianTi）", "栗壳字库 · 用户提供的本机字体副本，用于中文 LUT 名称。作者页面说明免费商用；字体文件不属于 GPLv3。", listOf("licenses/likejianjianti-notice.txt")),
            LicenceEntry("Source Han Serif / Source Han Sans", "Adobe · SIL OFL 1.1", listOf("licenses/source-han-serif-OFL.txt", "frames/licenses/SourceHanSans-OFL.txt")),
            LicenceEntry("画框字体", "Playfair Display、Julius Sans One、Cutive Mono、Abril Fatface、Dancing Script、Great Vibes · SIL OFL 1.1",
                listOf("frames/licenses/playfairdisplay-OFL.txt", "frames/licenses/juliussansone-OFL.txt", "frames/licenses/cutivemono-OFL.txt",
                    "frames/licenses/abrilfatface-OFL.txt", "frames/licenses/dancingscript-OFL.txt", "frames/licenses/greatvibes-OFL.txt")),
            LicenceEntry("OPPO Sans 4.0", "本应用使用 OPPO Sans 4.0 字体。© 2024 Guangdong OPPO Mobile Telecommunications Corp., Ltd. 原始字体未修改，按随官方字体包附带的 OPPO Sans Fonts License Agreement 捆绑。", listOf("licenses/oppo-sans-4-license.txt")),
            LicenceEntry("Teko", "Teko Project Authors · SIL OFL 1.1 · 欢迎标题使用 Bold 700，原始可变字体未修改。", listOf("licenses/teko-OFL.txt")),
            LicenceEntry("Bebas Neue", "Bebas Neue Project Authors · SIL OFL 1.1 · 原欢迎标题字体，保留原始来源记录。", listOf("licenses/bebas-neue-OFL.txt")),
            LicenceEntry("金字社统圆体", "金留庆 / Roman Czyborra / Paul Hardy · SIL OFL 1.1；界面字符子集改名为 Pineframe Tongyuan UI，字形轮廓未修改。", listOf("licenses/jinzi-tongyuan-OFL.txt")),
            LicenceEntry("恢复画框与胶卷图片", "来自维护者自有旧 APK；维护者于 2026-10-18 授权随本项目公开再分发。来源与文件哈希见项目 docs/design/assets.md。", emptyList()),
            LicenceEntry("Lut2Photo 算法参考", "qh7574/Lut2Photo-Android（MPL 2.0）。参考其 Bayer 抖动及亮度颗粒思路；本应用实现独立编写，没有打包该项目源码或二进制。", emptyList()),
            LicenceEntry("LUT 与创作者例图", "内置 10 款用户提供 LUT 已获维护者授权，可随项目和 APK 发布到 GameChicks/Lumatte；下载的例图与头像保留各自创作者权利，不由 GPLv3 统一授权。", emptyList())
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
