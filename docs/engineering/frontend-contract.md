# 功能交接给前端

2026-10-11：用户明确由另一个前端负责 UI。本项目继续完善功能与接口，本轮新写的未提交界面撤回，既有 LUT 工作台保留。此文档说明源码接口；没有编译或设备验证。正式三栏、暗盒、动画、点阵、渐进模糊尚未实现。

## 独立的影像入口

`core/media/PhotoProcessor.kt` 不依赖 Compose、屏幕、动画或 `WorkspaceViewModel`。一个应用持有一个处理器，前端传递以下模型即可：

| 接口／模型 | 用法 |
|---|---|
| `loadCatalogue()` | 返回内置／本地 LUT、胶卷目录和 LUT 读取问题；显示资源读取不初始化 GPU |
| `importLut(uri)` | 导入本地 .cube，原件不改写；随后更新目录 |
| `PhotoRecipe.Original` | 原图比较，与当前草稿分离 |
| `PhotoRecipe.Lut(LutRecipe)` | 两个槽、各自强度及颗粒；顺序为 1 → 2 |
| `PhotoRecipe.Film(FilmParameters, grainSeed)` | 胶卷 ID、统一曝光 EV、Halation、黑柔档位；专业参数内部处理 |
| `preview(source, recipe, catalogue, longEdge)` | 挂起函数返回位图，默认 960 长边，串行处理；调用前端协程可取消 |
| `exportLayout(source, BatchExportOptions)` | 不解码计算照片区域和含画框最终画布尺寸 |
| `previewExport(source, recipe, catalogue, options, longEdge, frameNumber)` | 处理效果＋画框预览，长边约束完整画布，默认 960 |
| `importFrameImage(uri)` / `importFrameFont(uri)` | 导入私有素材副本并返回键，原件不改写 |
| `export(source, recipe, catalogue, BatchExportOptions, destination, frameNumber, onStage, onPublished)` | 同配方及共享画框导出 JPEG；默认尺寸 100%，质量独立；返回已保存 URI |

`PhotoProcessingStage` 提供真实阶段入口：`PREPARING → RENDERING → FRAMING → ENCODING → PUBLISHING`（无框省略 FRAMING）。这是阶段信息，没有内核完成百分比，不让前端伪造精确进度。`onPublished` 位于不可取消发布提交段，用于成功记录。取消使用协程取消，已发布照片保留。旧 `JpegOutput` 导出重载保留，默认无框。

预览返回的位图由前端持有；移出 Compose 绘制后再释放，不能在旧 Image 尚绘制时回收。前端滑杆可用 150～250 ms 合并与 `collectLatest`，避免每一个中间值都计算。调用协程取消完成后，原生任务已结束，处理器内部的输入位图才会回收。预览尺寸与最终尺寸不同，颗粒、空间效果在不同采样网格上不保证逐像素相同。

## 复用工作台状态与批量逻辑

`WorkspaceViewModel.state` 是 `StateFlow<WorkspaceState>`，无需从控件反读参数。已有如下入口：

- `treatment(LUT/FILM)`：切换本批工作流，各自配方保留；前端三栏“Film／Lut”选择时调用。“连接”不改变草稿。
- `filmMode(INDIVIDUAL/FIRST_PHOTO)`：逐张调整或首张继承；切换不会覆盖原来的逐张草稿。
- `updateFilm(FilmParameters)`：逐张模式修改选中照片，继承模式修改稳定的基准照片。
- `useCurrentFilmReference()`：原基准缺失时由用户显式指定新基准，不能在排序、重进页面或导入新照片时自动换人。
- `copyFilmReferenceToAll()`：显式将统一参数复制到每张草稿；不要在模式切换时自动调用。
- `updateLut(LutRecipe)`、`importLuts(uris)`：当前批次 LUT 配方和导入。
- `previewEditedPhoto(id, longEdge, original)`：自动解析该张实际生效配方；正式前端使用此入口。既有 LUT 原型的 `previewPhoto(...)` 仍保留，但不负责读取胶片批量配方。
- `updateFrame(FrameOptions)`：保存当前整批共享画框。`state.frame` / `state.exportOptions` 提供设置；切换处理模式不会清空画框。
- `previewExportPhoto(id, longEdge)`、`exportLayout(id)`：导出页的含框预览和实际成片尺寸，不通过编辑预览塞入画框。
- `export(retryOnly)`、`cancelExport()`：整批导出、仅重试失败及取消后续任务。`processingStage`／`processingPhotoId` 提供当前阶段和照片，`completed/total/exported/failures` 提供实际数量与结果。

输入 `SourceImage` 中的 `rawNotice` 已包含内嵌 JPEG 说明和真实尺寸，前端以小字显示，不能写入画框。源图、EXIF、文件名、尺寸和随机种子不通过首张继承复制。

## 动效与画框的扩展边界

开发／反转视觉只属于前端：一批开启一次，用户跳过仅改变动效状态，不调用 `cancelExport()`；反转是最终正片的呈现，不把导出结果再次反相。建议前端按一次导出会话维护动效状态，结合真实阶段及数量呈现。

`core/model` 的 `FramePreset/FrameOptions/BatchExportOptions` 已接入画框绘制与 Room 7 批次保存。11 项含无框使用恢复比例和素材，在效果后、JPEG 编码前绘制；整批一个预设和设置，每张使用自身方向、EXIF 和取色。设置说明、各行字体／自定义素材键及源代码证据边界见 [画框功能](frame-export.md)。这是功能源码，尚未渲染、编译或与旧包对照。

正式选卷暗盒滚动、400 ms 介绍及返回位置保留由前端管理，不进入影像引擎。LUT 创作者目录、例图 Palette、官方服务器下载尚未具备可用接口；不要把当前本地目录标为官方云端。

## 设计资料

三栏顺序为连接／Film／Lut，默认进入连接；悬浮胶囊底栏、系统浅深色、混排复古标题、简洁几何及少量 Neo-Dotmatrix／弥散按用户提供 `reference/` 原件执行。字体副本及资产记录在 `research/ui/fonts/`，不加入应用构建。指定正文 OPPO Sans、欢迎 NHL Minnesota、点阵字体管家波点；可用 Playfair Display 做混排，附 OFL。

这些设计要求为正式前端的交接约束，当前源代码界面不是最终设计，也不声称已经实现渐进模糊或指定字体。
