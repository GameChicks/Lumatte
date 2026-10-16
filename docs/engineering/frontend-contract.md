# 功能交接给前端

2026-10-12：按本轮用户直接要求，正式四栏 UI 已由 MirrorScreen 接入这些接口。具体界面、设计与证据见 实现记录 和 [完整规范](../design/specification.md)。下文较早“三栏 / 字体不加入构建 / 另找前端”的交接描述仅保留历史上下文，以本轮四栏和已接入字体副本为准。接口接入与源码编译不代表设备或照片效果验证。

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

预览缓存只保留照片效果，画框／文字在其后绘制；原图、配方、表对象及实际照片处理尺寸共同决定失效。仅换签名等且处理尺寸不变时可复用光谱／LUT 结果。缓存位图不借给前端，所有返回图独立持有；8 MiB 不含前端图、临时图和 GPU。正式导出不读取小图缓存。后端、清理及数值证据边界见 手机优化源码。

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

LUT 图库后端新增 `loadLutGallery(catalogue)`、`setLutSubjects(asset, subjects)` 和 `previewLutCard(source, asset)`。卡片同时返回实际效果 Bitmap 与 Palette 颜色，默认长边 320，调用方负责回收已接收的图。全库 `lutGalleryColours(source, catalogue, onEntry=...)` 按项生成颜色数字、回收临时卡片，不返回图片；用于分析未滚动到的候选后筛选，不能把尚未分析条目当成已证明不匹配。用 `LutGalleryFilter.select` 做搜索、内置／本地、题材、颜色家族和例图近黑白组合筛选。卡片来源明确为用户当前照片，不是创作者上传图片；题材为用户可保存的标签，不从颜色自动判断。字段、来源、缓存与取消约定见 LUT 图库接口。正式前端可以使用这些入口，原型 UI 本轮未改。

开发／反转视觉只属于前端：一批开启一次，用户跳过仅改变动效状态，不调用 `cancelExport()`；反转是最终正片的呈现，不把导出结果再次反相。建议前端按一次导出会话维护动效状态，结合真实阶段及数量呈现。

`core/model` 的 `FramePreset/FrameOptions/BatchExportOptions` 已接入画框绘制与 Room 7 批次保存。11 项含无框使用恢复比例和素材，在效果后、JPEG 编码前绘制；整批一个预设和设置，每张使用自身方向、EXIF 和取色。设置说明、各行字体／自定义素材键及源代码证据边界见 [画框功能](frame-export.md)。这是功能源码，尚未渲染、编译或与旧包对照。

正式选卷暗盒滚动、400 ms 介绍及返回位置保留由前端管理，不进入影像引擎。`PhotoProcessor.officialLutCatalogue` 新增 `genres()`、`luts(query)`、`detail(code, language)`、`example(lut)` 的独立 HTTPS 源码接口；`example` 返回调用方持有的小图与 `CREATOR_EXAMPLE` 取色信息，须回收图片。`downloadOfficialLut(code, language)` 显式获取详情、下载 grid17 ZIP、提取／解析 cube 并原子保存，返回可放入两个槽任意一个的离线 `LutAsset`。完成后重新 `loadCatalogue()`，以稳定的 `lab-…` ID 保存配方；下载项有 `lumixLabSource`，图库可用新增 `OFFICIAL` 来源过滤。构造处理器或加载本地目录不会发送网络请求。

本轮已恢复 `app_lang` 11 个配置／值，并加入相同 qualifier 的自身服务语言资源。`LumixLabLanguages.forContext(frontendContext)` 可读取当前配置，`PhotoProcessor.officialLutLanguage()` 使用应用配置；目录和下载的便利入口默认取此值。`nextOffset` 只给继续请求的位置，无总数／下一页存在性保证。获取和下载失败应显示错误，保留内置／本地工作流；不要把网络 DTO 的 `code` 当成本地可渲染 LUT ID。源码尚未编译、请求官方服务器或运行下载，不显示“已连接官方目录”或成功下载。分页／图像限制、原子文件、来源和证据见 [官方目录接口](lumix-lab-catalogue.md)。

`WorkspaceViewModel.officialLuts.state` 新增独立浏览状态：分类、分页、详情、真实例图颜色／分析数量及按条目错误；没有 Bitmap 状态持有。`refreshOfficialLuts()`、浏览协调器的加载／关闭／取消、`downloadOfficialLut()`、`cancelOfficialLutDownload()`、`reloadLutLibrary()` 和 `selectLutSlot(assetId, second)` 已对接源码。下载独立，不自动覆盖槽；`officialLutDownload` 提供阶段和成功 ID／错误。前端可用 `LumixLabGalleryFilter` 对已加载候选做标题／作者／颜色筛选，未加载或未取色的项目保持未知。调用顺序、所有权、取消与证据见 浏览和工作台交接。

`PhotoProcessor.mobileFilmModelsInfo()` 可读取已打入资源目录的移动胶片模型 ID、相纸、正负片、版本、来源提交和缓存信息，不加载全部表或初始化 GPU。缺失／不支持的目录返回 problems，取消正常传播。这个资料入口不是实际渲染后端状态，不展示“已加速”或技术引擎选择；预览／导出已接 GLES 图块和从原图重做的 CPU 后备源码，共用模型与共享画框，尚未运行验证。实现与空间／颗粒近似见 [图像后端](mobile-film-rendering.md)。

黑白目录新增 `lfm_silver_neutral`／`lfm_silver_contrast` 自有风格，`FilmPreset.description` 可供介绍页显示来源说明。模型资料新增 `origin` 区分 spektrafilm 与本项目；自有模型 `sourceCommit` 为 null，前端需按来源处理可空提交。这两项不是品牌胶卷的实测复现。批量、曝光、柔光、Halation、画框和导出接口不变，详见 黑白功能。

## 设计资料

三栏顺序为连接／Film／Lut，默认进入连接；悬浮胶囊底栏、系统浅深色、混排复古标题、简洁几何及少量 Neo-Dotmatrix／弥散按用户提供 `reference/` 原件执行。字体副本及资产记录在 `research/ui/fonts/`，不加入应用构建。指定正文 OPPO Sans、欢迎 NHL Minnesota、点阵字体管家波点；可用 Playfair Display 做混排，附 OFL。

这些设计要求为正式前端的交接约束，当前源代码界面不是最终设计，也不声称已经实现渐进模糊或指定字体。
