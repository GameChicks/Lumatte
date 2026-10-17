# 第三方与素材清单

本项目代码的 GPLv3 不替代下列来源的原许可。本表记录仓库现有证据，不为第三方文件重新授权。

| 内容 | 来源与许可记录 |
| --- | --- |
| LibRaw 0.22.2 | `third_party/libraw/`；保留原始 COPYRIGHT、LGPL/CDDL 文本，本项目选择其 LGPL 2.1 选项 |
| spektrafilm 派生移动模型 | Andrea Volpato，固定 `3bb2c2d2801ff68b92019cf1dbcbb133d60832bc`；CC BY-SA 4.0，原许可见 `core/media/src/main/assets/film/mobile-v1/SPEKTRAFILM_LICENSE.txt` |
| Fotufilm 参数、公开 demo 表与派生表 | Fotufilm / MUAStudio Inc.；CC BY-SA 4.0，`research/fotufilm/licenses/` 和运行资产 NOTICE 保留署名、哈希与修改说明 |
| Fotufilm 光晕方程适配 | Apache-2.0，固定原始源码与 NOTICE 位于 `research/fotufilm/`；不声明完整引擎逐像素一致 |
| OFL 字体 | Cormorant Garamond、Dancing Script、Great Vibes、Instrument Serif、Playfair Display、Source Han Serif，以及画框字体；原 OFL 保存在 `core/design/src/main/assets/licenses/` / `core/media/src/main/assets/frames/licenses/` |
| Bebas Neue | 欢迎标题，取自 Google Fonts；SIL OFL 1.1，原字体未修改；`core/design/src/main/assets/licenses/bebas-neue-OFL.txt` |
| 金字社统圆体 | 金留庆 / Roman Czyborra / Paul Hardy；SIL OFL 1.1。按界面字符做子集，改名为 Pineframe Tongyuan UI，轮廓与字宽未修改；保留原字体版权和 OFL。来源为[猫啃下载页](https://www.maoken.com/freefonts/20033.html)及[作者项目页](https://www.typeface.cn/index.php?title=Project:金字社统圆体) |
| OPPO Sans 4.0 | 来自 [OPPO 官方页面](https://www.coloros.com/article/A00000074/)所链接的原始 ZIP；原字体逐字节保留。按包内 OPPO Sans Fonts License Agreement 随应用捆绑，保留完整协议，并在设置与许可页标明使用。不是 OFL；不修改、不单独作为字体分发 |
| 原生 JPEG 预览、LUT 与移动后端适配 | 项目代码；依赖的原始版权说明随来源保留 |

## 待维护者补充公开再分发授权

维护者在 2026-10-17 明确要求先保留以下文件并标明待确认，稍后补充授权。它们参与本机原型，不应被误写为“已确认可随开源项目分发”。

- `core/design/src/main/res/font/chinese_handwriting.ttf`（华康中文手写字体）：当前本机副本缺少与具体版本匹配的公开嵌入、再分发许可。旧 NHL、AaSpot 点阵及旧 OPPO 三字重已按维护者要求替换，不在当前源码树中。
- `app/src/main/assets/luts/` 内用户提供的 10 款 LUT：Fresh Bright、Kitaura、Smoky Color-S、Teal Flat-S、VOL 23 TXD AgfaPhoto LeBox、千禧回声 cold / warm、Backlight-N、Clear-S、Color Harmony。保留原作者和文件注释；提供文件不等于已取得公开再分发授权。
- `core/media/src/main/assets/frames/` 中从用户自有 Film Companion APK 恢复的画框/胶卷图片。来源与 SHA-256 见同目录 `manifest.json`；字体许可按各自 OFL 单独记录，不能自动扩展到图片。

应用暗盒和图标包含维护者授权生成/提供的视觉资源；原始私人参考、生成中间结果和恢复会话不进入本次源码快照。商标/产品名称用于标识相机和风格，项目不是松下官方软件。

新 UI 字体的下载地址、输入哈希、版本及子集字符表见 `docs/design/font-sources.json`。`tools/prepare_ui_fonts.py` 是手动再生产工具，不会在构建或测试时下载字体。OPPO 字体不属于 GPL 代码，也不因存放在本仓库而改变其许可。
