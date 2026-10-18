# 第三方与素材清单

本项目代码的 [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html) 不替代下列来源的原许可。本表记录仓库现有证据，不为第三方文件重新授权。

| 内容 | 来源与许可记录 |
| --- | --- |
| LibRaw 0.22.2 | [LibRaw 源码与原始许可](third_party/libraw/)；保留原始 COPYRIGHT、LGPL/CDDL 文本，本项目选择其 [LGPL 2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html) 选项 |
| spektrafilm 派生移动模型 | Andrea Volpato，固定 `3bb2c2d2801ff68b92019cf1dbcbb133d60832bc`；[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)，原许可见 [原许可文本](core/media/src/main/assets/film/mobile-v1/SPEKTRAFILM_LICENSE.txt) |
| Fotufilm 参数、公开 demo 表与派生表 | Fotufilm / MUAStudio Inc.；[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)，[Fotufilm 原始许可](research/fotufilm/licenses/) 和运行资产 NOTICE 保留署名、哈希与修改说明 |
| Fotufilm 光晕方程适配 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0)，固定原始源码与 NOTICE 位于 `research/fotufilm/`；不声明完整引擎逐像素一致 |
| OFL 字体 | Cormorant Garamond、Dancing Script、Great Vibes、Instrument Serif、Playfair Display、Source Han Serif，以及画框字体；原 OFL 保存在 [界面字体许可](core/design/src/main/assets/licenses/) / [画框字体许可](core/media/src/main/assets/frames/licenses/) |
| 栗壳坚坚体（LikeJianJianTi） | 栗壳字库；本机原始字体文件，name table 记录 `Version 2.001; July 4, 2025`；SHA-256 与来源见 [字体来源与哈希](third_party/provenance/font-sources.json)。作者来源页说明免费商用，猫啃网收录页列明 Android 等嵌入式应用；应用内附来源说明。维护者授权该副本随 GameChicks/Lumatte 项目和 APK 公开分发。字体不属于 [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html)。 |
| Teko | 当前欢迎标题使用 Bold 700，取自 Google Fonts；[SIL OFL 1.1](https://openfontlicense.org/open-font-license-official-text/)，原始可变字体未修改；`core/design/src/main/assets/licenses/teko-OFL.txt` |
| Bebas Neue | 原欢迎标题字体，保留原始资源与来源记录；[SIL OFL 1.1](https://openfontlicense.org/open-font-license-official-text/)；`core/design/src/main/assets/licenses/bebas-neue-OFL.txt` |
| 金字社统圆体 | 金留庆 / Roman Czyborra / Paul Hardy；[SIL OFL 1.1](https://openfontlicense.org/open-font-license-official-text/)。按界面字符做子集，改名为 Pineframe Tongyuan UI，轮廓与字宽未修改；保留原字体版权和 OFL。来源为[猫啃下载页](https://www.maoken.com/freefonts/20033.html)及[作者项目页](https://www.typeface.cn/index.php?title=Project:金字社统圆体) |
| OPPO Sans 4.0 | 来自 [OPPO 官方页面](https://www.coloros.com/article/A00000074/)所链接的原始 ZIP；原字体逐字节保留。按包内 OPPO Sans Fonts License Agreement 随应用捆绑，保留完整协议，并在设置与许可页标明使用。不是 OFL；不修改、不单独作为字体分发 |
| 原生 JPEG 预览、LUT 与移动后端适配 | 项目代码；依赖的原始版权说明随来源保留 |

## 用户提供素材的公开分发记录

维护者于 2026-10-18 明确授权将以下用户提供素材随项目源码及 1.0.0 APK 公开发布。项目仓库现名为 `GameChicks/Lumatte`（由 `GameChicks/-Lumatte` 重命名）。素材仍保留原作者权利和来源记录；此授权不把它们改为 [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html)，也不替代原作者许可。

- `core/design/src/main/res/font/likejianjianti.ttf`（栗壳坚坚体 / LikeJianJianTi）：复制自维护者电脑中的字体安装文件，原字节保留。字体 name table 标记为 `Version 2.001; July 4, 2025`；SHA-256 为 `1F3206853FB3B07B9415F819A8C1D47D982455F996FD642E953DEFEAA0094831`。作者来源页：[一颗栗壳](https://oneleekr.com/archives/passage1)；授权来源和用途整理：[猫啃网字体条目](https://www.maoken.com/freefonts/24987.html)。设置/许可页会注明作者、来源、版本及字体不属于 [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html)。
- `app/src/main/assets/luts/` 内用户提供的 10 款 LUT：Fresh Bright、Kitaura、Smoky Color-S、Teal Flat-S、VOL 23 TXD AgfaPhoto LeBox、千禧回声 cold / warm、Backlight-N、Clear-S、Color Harmony。保留原作者和文件注释。
- `core/media/src/main/assets/frames/` 中从维护者自有 Film Companion APK 恢复的画框/胶卷图片。来源与 SHA-256 见同目录 `manifest.json`；字体许可按各自 OFL 单独记录，不能自动扩展到图片。

应用暗盒和图标包含维护者授权生成/提供的视觉资源；原始私人参考、生成中间结果和恢复会话不进入本次源码快照。商标/产品名称用于标识相机和风格，项目不是松下官方软件。

新 UI 字体的下载地址、输入哈希、版本及子集字符表见 [字体来源与哈希](third_party/provenance/font-sources.json)。`tools/prepare_ui_fonts.py` 是手动再生产工具，不会在构建或测试时下载字体。OPPO 字体不属于 GPL 代码，也不因存放在本仓库而改变其许可。
