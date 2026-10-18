# 运行素材

本文件只说明当前应用所需资源。参考照片/视频、重复字体、恢复会话和生成中间结果已移到项目外，不作为公开源码或构建依赖。

| 位置 | 用途 |
| --- | --- |
| `core/design/src/main/res/font/` | UI 正文、标题、点阵和艺术字字体 |
| `app/src/main/assets/licenses/` | 应用内可读的第三方来源与许可说明 |
| `core/design/src/main/assets/licenses/` | OFL 与 OPPO 各自的完整字体许可 |
| `core/media/src/main/assets/frames/` | 画框、胶卷装饰、导出字体和原始来源/哈希清单 |
| `app/src/main/res/drawable-nodpi/` | 暗盒、图标及维护者提供的设置资源 |
| `core/media/src/main/res/drawable-nodpi/` | 26 张共享暗盒原图，选卷页与取色相框共用 |
| `app/src/main/assets/luts/` | 用户提供的 10 款内置 LUT，保留原作者注释 |

Cormorant Garamond、Dancing Script、Great Vibes、Instrument Serif、Playfair Display、Source Han Serif 及画框 OFL 字体随各自许可保留。暗盒中包含维护者授权生成的视觉资源和其自有旧 APK 中恢复的图像，不能把参考照片或第三方软件图标的授权自动归入本项目。

欢迎标题使用 Teko Bold 700（OFL 1.1），来自 Google Fonts 的原始可变字体，文件未修改；许可随应用捆绑，Bebas Neue 原始来源记录保留。正文使用官方 OPPO Sans 4.0 原始可变字体。点阵使用金字社统圆体的 OFL 字符子集，派生家族改名 Pineframe Tongyuan UI；只保留当前主页及机型字母/数字所需的 181 个码点，轮廓和字宽不变，约 70 KiB。旧 NHL、AaSpot 和旧 OPPO 三字重已移出源码树。完整来源、哈希与字符表见 [font-sources.json](font-sources.json)。原正文/点阵手动更新命令见 `tools/prepare_ui_fonts.py --help`；Teko 的原始下载记录和排字脚本见 [欢迎字体研究](welcome-font-study-20261018.md)。

选卷页与取色相框的胶卷图标共用 `FilmCartridgeArtwork`。26 张原图移到媒体模块，未复制为另一套资源；与移动前 Git HEAD 的原始字节一致，来源路径和 SHA-256 见 [共享暗盒清单](shared-cartridge-sources.json)。当前 24 款目录全部有映射，旧 Silver 配方也保留对应造型；推片后缀归到基础胶卷。取色相框仍优先尊重用户自行导入的装饰图，无选择胶卷时不虚构品牌外观。

中文 LUT 名称使用维护者提供的栗壳坚坚体副本，内部字体版本标记为 2.001（2025-07-04），原字节与 SHA-256 记录见 [字体来源清单](font-sources.json) 及应用内的许可说明。维护者于 2026-10-18 授权将该字体、10 款用户 LUT 和恢复画框图片随项目与 APK 在 GameChicks/-Lumatte 公开分发；各素材保留原作者权利与来源说明，不由 GPLv3 统一授权。详见[第三方清单](../../THIRD_PARTY_NOTICES.md)。

宣传用原始透明图标为 `app/src/main/res/drawable-nodpi/launcher_film_mirror_v1.png`：1254×1254 RGBA PNG，包含全透明像素。Android 自适应图标通过 `launcher_foreground.xml` 引用它；宣传页面直接使用 PNG 即可。

Palette 色彩和 LUT 预览使用用户导入照片。官方 LUT 作者图片/头像只保留官方元数据对应的 HTTPS 引用，运行时由用户明确发起下载，不将参考演示视频中的照片打入应用。
