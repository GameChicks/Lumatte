# 胶卷编辑介绍资料 · 2026-10-14

2026-10-16 更新：下文为 10 月 14 日资料快照。当前运行时已移除 Portra 800 Push1 / Push2，Silver 两款只兼容历史配方；公开目录为 18 款基础卷与新增 6 款，共 24 款，全部映射独立暗盒。完整新提示词、原件及哈希见 `generated-assets-2026-10-16.json`、`generated-results-2026-10-16.json`；工程修正见 `docs/engineering/widget-preview-export-fixes-2026-10-16.md`。

运行时中文介绍在 `app/src/main/kotlin/com/lumixfilmmirror/app/FilmEditorialLibrary.kt`。`copy-snapshot.json` 是基础 20 条介绍的静态展示副本，供设计对照稿读取，应用不读取这个 JSON。Portra 800 两个推片介绍在原生代码中派生，合计 22 个目录预设。

此轮以厂商一手资料核对感光度、日光 / 灯光平衡、颗粒与用途；中文文案为重新撰写的编辑说明。色彩印象、相对比较和使用建议是编辑概括，不能等同于当前 LUT 或空间算法的实测表现。

| 卷种 | 核对资料 |
|---|---|
| Gold 200 | [Kodak Gold 官方页](https://www.kodak.com/en/still-film/product/consumer/gold-200-film/) |
| Ultramax 400 | [Kodak Ultramax 官方页](https://www.kodak.com/en/still-film/product/consumer/ultramax-400-film/) |
| Ektar 100 | [Kodak Ektar 官方页](https://www.kodak.com/en/still-film/product/professional/ektar-100-film/) |
| Portra 160 / 400 / 800 | [160](https://www.kodakprofessional.com/photographers/film/color/kodak-professional-portra-160-film/529)、[400](https://www.kodakprofessional.com/photographers/film/color/kodak-professional-portra-400-film/516)、[800](https://www.kodakprofessional.com/photographers/film/color/kodak-professional-portra-800-film/528) |
| Ektachrome E100 | [Kodak Alaris E100](https://www.kodakprofessional.com/photographers/film/color/kodak-professional-ektachrome-film-e100/531) |
| Kodachrome 64 | [Kodak E-88 技术资料，2002 年厂商文档归档副本](https://125px.com/docs/film/kodak/e88-2002_03.pdf) |
| C200、Superia X-tra 400、PRO 400H、Velvia 100、Provia 100F | [Fujifilm 官方技术资料入口](https://www.fujifilm.com/in/en/consumer/support/films/negative-and-reversal)，读取各卷数据表第一页 |
| Vision3 50D / 200T / 250D / 500T、Verita 200D | [Kodak Camera Films](https://www.kodak.com/en/motion/products/camera-films/) 和对应各卷产品页 |
| Silver Neutral / Contrast | 项目 `docs/engineering/monochrome-film.md` 与自有模拟模型说明 |

推片仍是原卷的处理方式。本项目两个 Portra 800 Push 变体不作为另一种厂商胶卷，不另造原片 ISO 或实测性能数据。电影卷保留原片 ECN-2 信息，与消费静态胶片的 C-41 分开。

## 字体与原图

Instrument Serif 正体新增自 [Google Fonts 官方目录](https://github.com/google/fonts/tree/main/ofl/instrumentserif)，与已有真斜体共用 `instrument-serif-OFL.txt`。Abril Fatface 从已有恢复的 `frames/fonts/print_display.ttf` 字节复制至 design 资源，许可沿用 `frames/licenses/abrilfatface-OFL.txt`。来源与 SHA-256 见 `font-provenance.json`。

对照稿读取原参考和已有暗盒资源，不复制参考中的场景照片作为真实样片。暗盒透视、标签包裹与阴影为运行时绘制，不改原件。
