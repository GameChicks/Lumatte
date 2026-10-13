# UI 资产来源 · 2026-10-12

| 应用副本 | 本项目只读来源 | 角色 / 许可记录 |
|---|---|---|
| core/design/src/main/res/font/oppo_sans_regular.ttf | research/ui/fonts/oppo_sans_regular.ttf | OPPO Sans 正文，用户指定本机文件 |
| oppo_sans_medium.ttf | research/ui/fonts/oppo_sans_medium.ttf | 中等字重 |
| oppo_sans_bold.ttf | research/ui/fonts/oppo_sans_bold.ttf | 粗体 |
| nhl_minnesota.ttf | research/ui/fonts/nhl_minnesota.ttf | 指定 WELCOME，完整公开嵌入许可尚待补齐 |
| dotmatrix_chinese.ttf | research/ui/fonts/dotmatrix_chinese.ttf | 指定波点；名称表 AaSpot 与完整商用嵌入条款需继续核对 |
| editorial_serif.ttf | research/ui/fonts/editorial_serif.ttf | Playfair Display；附 OFL |
| handwritten.ttf | research/filmtool/assets/font/print_script.ttf | Dancing Script；附 OFL |
| app/src/main/res/drawable-nodpi/cartridge_blank_v1.png | research/filmtool/assets/drawable/cartridge_blank_v1.png | 自有 APK 恢复的暗盒，叠加新目录标签 |

这里的字体路径除首行外同属 `core/design/src/main/res/font/`。原文件均未修改。副本字节数与 SHA-256 见 `research/ui/fonts/app-font-manifest.json`；指定字体原清单仍为 `font-manifest.json`。10 月 12 日接入七份字体，10 月 13 日增加一份中文衬线标题字体，不将所有恢复字体无区别打入应用。

Playfair / Dancing Script 许可副本放入 `core/design/src/main/assets/licenses/`。OPPO / NHL / 波点的已有来源与许可证据见 `research/ui/README.md`；用户指定用于本机实现不替代公开分发授权记录。本轮没有发布字体或应用到外部服务。

圆点箭头、星形、相机、Film、LUT、画框与加号由 Canvas 绘制，不使用参考游戏图中的图标。没有把生成的设计图当作按钮贴图。旧画框资产继续由 `core/media/src/main/assets/frames/manifest.json` 管理，不改变来源、照片或原 APK。

官方 LUT 例图和头像仅使用对应官方元数据中的 HTTPS 同源引用；本地 LUT 预览来自当前真实导入照片。下载来源保存缩略图 / 头像引用的可选字段，旧缓存兼容读取，不自行伪造作者图或介绍。

## 2026-10-13 新增

中文艺术标题使用 C:/Windows/Fonts/Source Han Serif SC Heavy (TrueType).ttf 的完整副本 editorial_chinese.ttf（2,525,144 字节），原文件未修改。OFL 从 Adobe Source Han Serif 官方 release/LICENSE.txt 保存到 core/design/src/main/assets/licenses/source-han-serif-OFL.txt。正文仍为 OPPO Sans。三份 imagegen 暗盒及完整提示词见 [生成资产记录](generated-cartridges-2026-10-13.md)。

继续前端与设置时新增 [Instrument Serif](https://github.com/google/fonts/tree/main/ofl/instrumentserif) 的真实窄衬线斜体副本 `instrument_serif_italic.ttf`，OFL 保存到 design assets/licenses；原 Cormorant 与 Great Vibes 保留。设置内的支付宝 / 微信捐赠图由用户直接提供，从 Downloads 原字节复制，未更改原图；不作为界面风格参考混用。应用图标与原件记录见 [ImageGen 图标记录](generated-icon-2026-10-13.md)。
