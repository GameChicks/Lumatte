# 功能交接给前端

2026-10-11：用户明确由另一个前端负责 UI。本项目继续完善功能与接口，本轮新写的未提交界面撤回，既有 LUT 工作台保留。此文档说明源码接口；没有编译或设备验证。正式三栏、暗盒、动画、点阵、渐进模糊尚未实现。

## 独立的影像入口

`core/media/PhotoProcessor.kt` 不依赖 Compose、屏幕、动画或 `WorkspaceViewModel`。正式前端复用 `FilmMirrorApplication.processor` 共享实例，避免多套缓存与处理队列，传递以下模型即可：

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
| `lutBackend()` | 查询原生库加载对应的 `NATIVE_CPU`／`KOTLIN_CPU`；不证明速度或胶片 GPU 可用 |
| `filmBackend()` | 返回配置源码路径 `MOBILE_GLES3_WITH_CPU_FALLBACK`，不初始化 GPU、不表示本次实际后端或性能；不增加 UI 引擎选择 |
| `previewCacheInfo()` / `clearPreviewCache()` | 读取实际效果缓存统计，或等待当前预览安全结束后清理；预算 8 MiB／2 项 |
| `clearProcessingCaches()` | 等待预览和已排队原生任务，清理效果与模型缓存；不删除配方、素材或成片 |
| `requestMemoryTrim()` | 合并式异步处理缓存清理，用于应用内存／后台回调，不取消导出 |
| `export(source, recipe, catalogue, BatchExportOptions, destination, frameNumber, onStage, onPublished)` | 同配方及共享画框导出 JPEG；默认尺寸 100%，质量独立；返回已保存 URI |

`PhotoProcessingStage` 提供真实阶段入口：`PREPARING → RENDERING → FRAMING → ENCODING → PUBLISHING`（无框省略 FRAMING）。这是阶段信息，没有内核完成百分比，不让前端伪造精确进度。`onPublished` 位于不可取消发布提交段，用于成功记录。取消使用协程取消，已发布照片保留。旧 `JpegOutput` 导出重载保留，默认无框。

导出另提供可选的 `onTargetCreated(uri)` 回调：它仅表示已取得目标 URI，文件未必完整，不计成功。持久队列用它登记候选 URI，用 `onPublished` 登记 SAVED；正式批量前端应复用下面的队列入口，避免绕过任务记录。

预览返回的位图由前端持有；移出 Compose 绘制后再释放，不能在旧 Image 尚绘制时回收。前端滑杆可用 150～250 ms 合并与 `collectLatest`，避免每一个中间值都计算。调用协程取消完成后，原生任务已结束，处理器内部的输入位图才会回收。预览尺寸与最终尺寸不同，颗粒、空间效果在不同采样网格上不保证逐像素相同。

预览缓存只保留照片效果，画框／文字在其后绘制；原图、配方、表对象及实际照片处理尺寸共同决定失效。仅换签名等且处理尺寸不变时可复用光谱／LUT 结果。缓存位图不借给前端，所有返回图独立持有；8 MiB 不含前端图、临时图和 GPU。正式导出不读取小图缓存。后端、清理及数值证据边界见 [手机优化源码](mobile-processing.md)。

`clearPreviewCache()` 仅清理位图 LRU。`clearProcessingCaches()` 另排队等待移动原生工作结束，在所属 worker 释放 GLES 上下文／纹理，再丢弃模型缓存引用，不取消导出或破坏当前借用模型。下一次胶片请求按需加载模型／分配资源，私有数值资产保留。该操作可能等待正在渲染的大图，不在主线程阻塞等待，不在每次调整参数后调用。应用后台回调通过 `requestMemoryTrim()` 请求同一路径；后续排入的导出仍会按需重新分配资源，不能将清理当作强制内存上限。

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

Room 8 已接入不可变导出快照和每项结果，`state.exportRuns/exportRunId` 可供任务页使用。新增 `resumeExport(id, retryFailed)`、`pauseExport()`、`cancelPendingExport(id)`、`confirmExportReview(runId, photoId, savedUri)`；继续旧请求保留旧配方与输出设置，普通“仅重试失败”用当前设置创建新请求。SAVED 跳过，REVIEW 需用户先核对目标，不自动重试。没有新增任务列表控件。

实际执行已交给 `PhotoExportService`；共享 `FilmMirrorApplication.exports` 的 `state`（也可通过 ViewModel 的 `exportExecution`）提供 `active/preparing/stopping/run/notice`，包含接管与收尾，页面关闭后已接管任务不由页面取消。独立前端用其 `store` 创建／读取快照，显式 `requestStart(runId, retry)` 启动，`stop(cancel, reason)` 暂停或取消；从可见应用用户操作发起，不在订阅、重进页面或启动恢复时自动运行。字段、通知、会话标识与系统时限边界见 [导出任务](export-queue.md)。这些生命周期行为尚未运行验证。

输入 `SourceImage` 中的 `rawNotice` 已包含内嵌 JPEG 说明和真实尺寸，前端以小字显示，不能写入画框。源图、EXIF、文件名、尺寸和随机种子不通过首张继承复制。

画框机型文字使用 `source.metadata.frameCameraName`；也可调用 `FrameCameraNames.format(exifCamera)`。它将 EXIF 的 `Panasonic DC-S5`／`DC-S5` 显示为 `Lumix S5`，`DC-S1RM2` 显示为 `Lumix S1RM2`，并兼容旧款 `DMC-` 前缀。预览与导出已共用此入口。原始 `metadata.camera` 保留不变，未识别名称保留，缺失名称不推测；每张使用自身元数据，自定义画框文字仍优先。

## 动效与画框的扩展边界

开发／反转视觉只属于前端：一批开启一次，用户跳过仅改变动效状态，不调用 `cancelExport()`；反转是最终正片的呈现，不把导出结果再次反相。建议前端按一次导出会话维护动效状态，结合真实阶段及数量呈现。

`core/model` 的 `FramePreset/FrameOptions/BatchExportOptions` 已接入画框绘制与 Room 7 批次保存。11 项含无框使用恢复比例和素材，在效果后、JPEG 编码前绘制；整批一个预设和设置，每张使用自身方向、EXIF 和取色。设置说明、各行字体／自定义素材键及源代码证据边界见 [画框功能](frame-export.md)。这是功能源码，尚未渲染、编译或与旧包对照。

正式选卷暗盒滚动、400 ms 介绍及返回位置保留由前端管理，不进入影像引擎。LUT 创作者目录、例图 Palette、官方服务器下载尚未具备可用接口；不要把当前本地目录标为官方云端。

`PhotoProcessor.mobileFilmModelsInfo()` 可读取已打入资源目录的移动胶片模型 ID、相纸、正负片、版本、来源提交和缓存信息，不加载全部表或初始化 GPU。缺失／不支持的目录返回 problems，取消正常传播。这个资料入口不是实际渲染后端状态，不展示“已加速”或技术引擎选择；预览／导出已接 GLES 图块和从原图重做的 CPU 后备源码，共用模型与共享画框，尚未运行验证。实现与空间／颗粒近似见 [图像后端](mobile-film-rendering.md)。

黑白目录新增 `lfm_silver_neutral`／`lfm_silver_contrast` 自有风格，`FilmPreset.description` 可供介绍页显示来源说明。模型资料新增 `origin` 区分 spektrafilm 与本项目；自有模型 `sourceCommit` 为 null，前端需按来源处理可空提交。这两项不是品牌胶卷的实测复现。批量、曝光、柔光、Halation、画框和导出接口不变，详见 [黑白功能](monochrome-film.md)。

## 设计资料

三栏顺序为连接／Film／Lut，默认进入连接；悬浮胶囊底栏、系统浅深色、混排复古标题、简洁几何及少量 Neo-Dotmatrix／弥散按用户提供 `reference/` 原件执行。字体副本及资产记录在 `research/ui/fonts/`，不加入应用构建。指定正文 OPPO Sans、欢迎 NHL Minnesota、点阵字体管家波点；可用 Playfair Display 做混排，附 OFL。

这些设计要求为正式前端的交接约束，当前源代码界面不是最终设计，也不声称已经实现渐进模糊或指定字体。
