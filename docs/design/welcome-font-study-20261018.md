# WELCOME 免费字体候选 · 2026-10-18

根据维护者提供的 NHL 与现用 Bebas Neue 两张截图，比较窄厚字身、C/O 内孔、W/M 结构，以及左高右低透视后的观感。先完成字体研究与静态排字；维护者随后明确选用推荐字体，已在应用源码接入 Teko Bold 700。没有编译、测试或打包。

## 建议

优先 **Teko Bold（700）**：在本次相同字面框的对照中，C/O 窄内孔和较方的肩部最接近参考图的厚重感。其次 **Big Shoulders Black（900，opsz=72）**，W/M 更有棱角。Antonio Bold 相对瘦，Anton 较厚但 C/O 更圆。以上相似度是视觉判断，不是同一字体或精确复刻的声明。

四款候选均从 Google Fonts 官方仓库取得原始 TTF、METADATA.pb 与 SIL OFL 1.1 原文。许可允许在符合其条款时用于商业作品与软件嵌入；分发字体时保留版权与许可，不单独销售字体。未下载或重新引入 NHL。

| 候选 | 轴设置 | 原始来源与许可 |
| --- | --- | --- |
| Teko Bold | wght=700 | [官方目录](https://github.com/google/fonts/tree/main/ofl/teko) · [OFL](https://github.com/google/fonts/blob/main/ofl/teko/OFL.txt) |
| Big Shoulders Black | opsz=72, wght=900 | [官方目录](https://github.com/google/fonts/tree/main/ofl/bigshoulders) · [OFL](https://github.com/google/fonts/blob/main/ofl/bigshoulders/OFL.txt) |
| Antonio Bold | wght=700 | [官方目录](https://github.com/google/fonts/tree/main/ofl/antonio) · [OFL](https://github.com/google/fonts/blob/main/ofl/antonio/OFL.txt) |
| Anton | 原始单字重 | [官方目录](https://github.com/google/fonts/tree/main/ofl/anton) · [OFL](https://github.com/google/fonts/blob/main/ofl/anton/OFL.txt) |

## 产物与可重复排字

- `dist/welcome-font-study-20261018/welcome-light.png`、`welcome-dark.png`：五行对照，第一行为现用 Bebas Neue。
- 同目录 `originals/`：原始字体、元数据和许可，未改写字体文件。
- 同目录 `sources.json`：每个下载文件的 URL、大小与 SHA-256。来源使用上游 main，保留这份哈希用于识别本次确切字节，不声称锁定了上游版本提交。
- 手动脚本 `tools/render_welcome_font_study.py`，需要已有的 Pillow 与 NumPy；不挂接构建或测试流程。

```powershell
python tools/render_welcome_font_study.py --output dist/welcome-font-study-20261018 --download
# 已有输入时离线重绘，省略 --download。
```

统一使用字形可见边界填满 850×255 字面框，包含横纵向独立缩放，再投影到右上角落差为高度 23% 的四边形；每行相同遮挡和颜色。标签另列未经缩放的原生词宽／大写字高。此图用于比较适配后的字形，不能把变换后比例当作字体原生比例，也不是 Android 屏幕截图。

复查原 `WelcomePerspective`：`naturalHeight` 使用 `fontMetrics.bottom - top`，包括非字面留白；它与所需的可见大写字面高度不同。接入 Teko 时已改为 `getTextBounds`，将实际字形边界投影到相同的四边形，保留右上角 23% 落差、深浅渐变与卡片遮挡布局。Compose 字体入口与 Canvas 标题都指定 700。原始字体及 OFL 的哈希已写入 `font-sources.json`，许可页可离线阅读原文。应用的品牌标题及卡片尺寸未因这次字体替换改变；先前生成的对照图保留“暂不替换”字样，表示选定之前的研究阶段。

同轮按维护者要求，取色相框图标也改用选卷页已有暗盒。共享资源清单见 `shared-cartridge-sources.json`；这是源码和文件完整性核对，没有 Android 实际预览/导出证据。
