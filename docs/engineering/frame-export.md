# 共享画框导出功能

2026-10-11：按用户要求只完善功能，UI 交给另一个前端。下述是源码接入状态；没有编译、渲染、执行迁移、测试或打包，不是已经与旧 APK 逐像素比对的结论。

## 处理顺序和批量范围

`PhotoProcessor → JpegOutputWriter` 的顺序为有效 JPEG 解码与方向校正 → 胶片或双 LUT → 画框 → JPEG 编码 → EXIF → 发布。RAW 只使用实际提取的内嵌 JPEG；其小字说明通过 `SourceImage.rawNotice` 交给前端，不画到成片上。

`BatchExportOptions(jpeg, frame)` 是导出配置，`PhotoRecipe` 仍只描述照片效果。`WorkspaceViewModel.updateFrame` 对当前整批保存一份 `FrameOptions`。逐张调整和首张继承都使用该共享画框；相机名、曝光信息、方向、取色和胶卷身份按每张实际生效配方解析。固定颜色、文字、字体、比例、135/120 选择及暗盒覆盖标签属于整批设置。

导出捕获照片列表、效果配方和画框快照，中途不读取新的界面参数。暗盒片号按该快照中的照片位置从 1 起，失败重试沿用当前批次位置，不能把重试子集全部重新编号。未实现照片删除/排序入口；以后加入时需要明确片号规则。

阶段为 `PREPARING → RENDERING → FRAMING → ENCODING → PUBLISHING`；无框省略 `FRAMING`。阶段回调不提供内核百分比。前端的显影／反转动画仍只播一次，跳过只改变前端状态，不能取消处理任务。

## 样式和恢复依据

枚举保持旧包的 11 项（包括无框）。恢复来源见 `research/filmtool/README.md`、`source-recovery.json` 和 `recovered/.../media/`，没有执行其中的历史命令。

| 样式 | 已接入的绘制内容 |
|---|---|
| NONE | 保持照片内容，省略画框位图与取色 |
| PAPER / BLACK | 暖白相纸或黑色画廊，底部文字及签名 |
| FILM | 原 135/120 暗盒素材、曲面标签、片尾、齿孔、片号及装饰条码 |
| TICKET | 灰底纸票、齿边、孔洞及原默认题字／页脚 |
| INSTANT | 原下边比例、轮廓及确定性纸纤维 |
| DUSK / MIST | 从当前处理图生成的模糊背景、暗／浅蒙层；雾面保留白色照片边 |
| PALETTE | 六种 Palette 色调或固定背景色，胶卷装饰图／用户装饰图及文字 |
| MUSEUM / SLIDE | 原棉纸纹理、窗口斜边；幻灯片夹保留外圈和固定点 |

恢复的 `FilmStripPainter` 方法调用含 `frameWidth/tailStart`，但捕获的方法体不含这两个参数；没有直接把不一致的历史文件当作可编译源码。新 `FrameFilmStrip` 按照片宽度保持齿孔节距，并延续至片尾；这部分仍待与旧包实画核对。全部材料 alpha 与滤镜在绘制照片前隔离，FILM 的新增比例空白明确填黑，避免透明画布编码 JPEG。

默认标题取相机名（缺失时用照片名称），副标题取该张胶卷／中性应用标签。纸框等普通样式默认页脚补入该照片的镜头、焦距、光圈、快门和 ISO；票根保留原引文。与恢复版本空页脚相比，这是新应用的元数据接入。`title/subtitle/footer = null` 使用自动值，空字符串隐藏相应一行。关闭 `showPhotoMetadata` 隐藏这三组文字，但独立签名仍可保留。FILM 黑白颜色默认取胶卷分类，亦可整批覆盖。当前内置光谱目录还没有黑白胶卷数值模型。

机型显示统一读取该张照片的 EXIF Make／Model：`Panasonic DC-S5`／`DC-S5` → `Lumix S5`，`Panasonic DC-S1RM2` → `Lumix S1RM2`。旧款 `DMC-` 型号采用相同后缀规则，已带 Lumix 品牌的名称归一化为 `Lumix`。`PhotoMetadata.frameCameraName` 和 `FrameCameraNames.format` 提供同一显示规则，预览、导出及前端共用；不改写保存的 `camera` 或照片 EXIF。无法识别的名称原样保留，缺失时不推测型号。批量设置只共享画框，自定义标题／副标题仍覆盖自动文字。这是源码接入，尚未编译或渲染核对。

## 尺寸和位图生命周期

默认 `JpegOutput(100, 95)`：100 是照片内容尺寸百分比，95 是 JPEG 压缩质量。画框添加画布，不把照片缩小到固定长边；比例通过添加留白实现。方向默认逐张跟随照片，`portrait` 非空可固定画布方向。照片位置在正式导出时对齐整像素，避免仅加框时产生分数像素平移。

`exportLayout(source, options)` 返回最终画布及内容区域，不解码、不初始化 GPU。前端可同时显示“照片内容”和“含框成片”两个尺寸。EXIF 像素宽高写最终画布，方向已经归一化。

`previewExport` 先计算正式导出布局，再缩放完整构图到指定长边；不会将小图重新当作导出原件计算边框。编辑预览 `preview` 始终只展示照片效果，画框留到导出。两种预览共用串行门，返回位图由前端持有并适时回收。

正式加框时短暂同时持有处理图与画布；绘制结束立即释放处理图，再编码画布。无框直接复用处理图。暮色／雾面模糊分析限制在 160 像素长边，Palette 分析限制在 128×128 面积。装饰图导入限制 640 长边。其余输出不自动限尺寸；大照片的双位图与 FILM 加长画布内存仍待设备测量，内存不足沿用明确失败／降低尺寸重试，不悄悄减像素。预览和正式图不同采样的 Palette／颗粒并不保证数值完全一致；固定颜色能锁住整批画框底色。

## 素材和字体扩展

`importFrameImage(uri)` / `importFrameFont(uri)` 返回 SHA-256 键，保存到应用私有 `frame-assets/`，配方不保存外部 URI 或绝对路径。导入不覆盖原文件；支持透明 PNG 装饰副本、TTF/OTF/TTC 字体。输入上限 32 MiB；字体必须有 SFNT/TTC 标识且可被 Android Typeface 读取。写临时文件、关闭同步后原子改名。缺失素材明确报错，请用户重新导入，不默默换成另一个字体或图案。

设置 `imageKey` 使用 PALETTE 装饰图；设置 `titleFontKey/subtitleFontKey/footerFontKey/signatureFontKey` 独立覆盖相应字体。七种内置画框字体从恢复包复制，SFNT 名称／许可文字和附带 OFL 一致：恢复 Source Han Sans 子集、Cutive Mono、Dancing Script、Playfair Display、Abril Fatface、Julius Sans One、Great Vibes。没有将缺少附带许可的 Kunstler／Rosethella／Highwind 自动随应用再分发；用户自用字体导入接口保留。

`core/media/src/main/assets/frames/manifest.json` 记录 45 个素材副本的 APK 来源、字节数、SHA-256 和字体许可位置。生成工具 `tools/migrate_frame_assets.py` 只复制恢复资源并记录元数据，不运行旧代码、绘制或构建。暗盒、棉纸及胶卷封面是用户旧应用恢复资产，来源记录不等于它们都由新应用 GPL 许可覆盖；对外发布前仍需按来源清单确认分发条件。原 APK、参考副本、字体原件保持不变。

## 保存与前端调用

Room 7 声明 `6 → 7` 新增 `batch_frame_recipes(batchId, frameJson)`，仅增表。每批一行，JSON 版本 1，缺少旧记录使用无框，不改写已有照片、LUT、胶片或传输记录。没有破坏式迁移。当前迁移只是源码声明，未生成新 Room schema，也未执行迁移。

```kotlin
// 前端在导出页修改整批设置；这里不依赖任何具体控件。
workspace.updateFrame(workspace.state.value.frame.copy(
    preset = FramePreset.MUSEUM,
    ratio = FrameRatio.THREE_TWO,
    signature = "My signature",
))
val geometry = workspace.exportLayout(photoId)
val preview = workspace.previewExportPhoto(photoId, longEdge = 960)
// preview 的位图归前端管理。整批导出复用该配置。
workspace.export()
```

也可以只使用 `PhotoProcessor`，传入 `PhotoRecipe`、`ProcessingCatalogue` 和 `BatchExportOptions`，不用本项目 ViewModel。详细接口见 [前端交接](frontend-contract.md)。此代码片段未执行。
