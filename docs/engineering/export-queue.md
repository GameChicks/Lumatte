# 批量导出持久任务

2026-10-11，功能源码接入。没有运行 Room 迁移、编译、图像处理、进程中断、取消或设备测试，没有打包。本文描述实现语义，不证明任务恢复已经运行通过。UI 由用户的前端负责，本轮没有新增任务列表控件。

## 请求快照

`ExportQueueStore.enqueue` 为一次导出建立独立 UUID；Room 8 新增 `export_runs` 和 `export_items`，注册仅增表的 `7 → 8` 迁移。原照片、相机队列、批次及编辑草稿表保留，不执行破坏式迁移。当前 schema 已导出到 `core/storage/schemas/`（含 12.json），迁移只做增表 / 增列。

请求保存处理批次身份、照片顺序／原片号、每张实际生效的配方、颗粒种子、整批共享画框 JSON、输出尺寸百分比、压缩质量、导出格式（JPEG / PNG）、目标相册／文件夹 URI。首张继承在入队时解析成各项的有效胶片参数，后续照片保留自身种子、源图及 EXIF。每次恢复按这份快照运行，之后修改编辑草稿或工作台导出偏好不会悄悄改变旧任务。默认尺寸仍是有效输入 100%，质量默认 95，格式默认 JPEG，帧号在仅导出失败子集时不从 1 重新编号。

2026-10-18：`JpegOutput` 增加 `format`；Room `11 → 12` 仅给 `export_runs` 增列 `format TEXT NOT NULL DEFAULT 'JPEG'`，旧队列任务按 JPEG 恢复，已排队的 PNG 任务按快照继续 PNG。

任务只保存参数和导入照片 ID，不复制成片位图或原图。自定义素材、字体和 LUT 仍引用应用私有资源；资源缺失时记录失败，不换成原图或其他滤镜继续导出。LUT ID 由现有库管理，胶卷模型仍使用当前内置目录；本轮没有保存整个历史引擎版本。未来模型／算法升级的旧配方迁移仍需要版本策略。

## 执行与结果

`ExportQueueRunner` 不依赖 Compose 或 ViewModel，使用应用共享 PhotoProcessor。进程级运行门和数据库事务共同限制一次批量任务；实际导出仍使用已有 JPEG / PNG 编码／发布锁。每项阶段落盘后才进入下一步，执行阶段为 PREPARING、RENDERING、FRAMING、ENCODING、PUBLISHING。无框省略 FRAMING。

| 项目状态 | 含义 |
|---|---|
| QUEUED | 等待本次用户发起的执行 |
| RUNNING | 当前处理／保存；stage 提供具体阶段 |
| PAUSED | 尚未成功，可继续同一请求 |
| FAILED | 已知处理失败；用户明确重试时重新编码 |
| SAVED | 发布成功及完成 URI 已登记；继续任务时跳过 |
| REVIEW | 保存结果不明，需核对目标；继续／重试不会自动包含 |
| CANCELLED | 用户取消的余下项目；同一请求不再执行 |

`ExportRun.completed` 是已经停止等待本轮处理的项目数，包含 SAVED、FAILED、REVIEW、CANCELLED；不是“保存成功”数。`saved/failed/reviews` 分别统计真实记录。`items` 保留每项错误、阶段、候选 URI 和完成 URI。请求终态按项目汇总，REVIEW 优先成为 NEEDS_REVIEW；其余尚未处理时 PAUSED，全部成功时 COMPLETE，包含失败时 COMPLETE_WITH_FAILURES。

## 发布未知窗口

进入保存前先登记 PUBLISHING。媒体行／SAF 文档创建并得到 URI 后，`onTargetCreated` 在不可取消段登记候选 URI；候选文件可能尚未写完，绝不能计为成功。正常发布／完成记录的 `onPublished` 也在已有不可取消提交段内执行，Room SAVED 先于工作台成功显示。

进程每次启动仅恢复一次：此前 QUEUED／RUNNING 请求的未完成项成为 PAUSED，已处于 PUBLISHING 的 RUNNING 项成为 REVIEW，SAVED 保留。保存阶段抛错或取消也保留 REVIEW，不能只根据异常推断“目标没有成片”。外部创建和登记 URI 之间仍有窗口，因此候选 URI 可以为空。已保存与 REVIEW 项均不自动重试。

`confirmReview(runId, photoId, savedUri)` 只接收用户核对后的决定。提供实际照片 content URI 记录为 SAVED；传 null 显式允许后续按 FAILED 重试。这不是本应用自动验证过文件完整性的声明，也不删除或覆盖任何目标。活动请求不能在处理中核对。前端可优先打开 `candidateUri`，其缺失／不可读也不能自动宣称没有其他已创建文件，尤其是 SAF 提供者响应未知时。

现有缓存整理继续只清理本应用未发布相册占位和编码缓存；本队列不保留 pending 文件作为续写输入，不从残缺文档续写，PAUSED 重做编码，REVIEW 先核对。已发布相册照片及用户文件夹不参与批量删除。SAF 创建日志与导出任务是两个不同层次的记录，两者可能同时要求核对，见 [JPEG 生命周期](jpeg-export-lifecycle.md)。

## 工作台与前端入口

| 入口 | 语义 |
|---|---|
| `WorkspaceState.exportRuns` / `exportRunId` | 持久任务历史与当前显示任务，按处理批次筛选；不会把历史成片自动并入新请求的数量 |
| `export(retryOnly=false)` | 用当前草稿与输出设置建立新的请求 |
| `export(retryOnly=true)` | 只为所选旧请求的 FAILED 项建立新请求，可先降低尺寸或改配方；旧成功记录／文件保留，REVIEW 不包含 |
| `resumeExport(id, retryFailed=false)` | 继续旧快照的 QUEUED／PAUSED 项；明确 true 才包含 FAILED，SAVED／REVIEW 始终跳过 |
| `pauseExport()` | 取消当前执行协程，等待原生退出后暂停未完成项 |
| `cancelExport()` | 取消余下项目，保存成功项保留，保存不明项保留 REVIEW |
| `cancelPendingExport(id)` | 取消未运行请求的余下项目，运行中必须用执行取消入口 |
| `confirmExportReview(runId, photoId, savedUri)` | 前端展示目标并得到用户决定后登记核对结果 |

独立前端复用 `FilmMirrorApplication.exports`，其 `store` 提供 `runs`、`snapshot`、`enqueue`、`stopIdle`、`confirmReview`。先调用 `initialize`，这不会自动执行历史任务；用 `requestStart(id, retry)` 将明确请求交给照片导出服务，用 `stop(cancel, reason)` 暂停或取消。不要同时从页面另建 runner 执行同一任务。已经取消的请求不能复用，重新导出需建立新请求。

## 服务接管

`ExportController` 由 Application 共享持有；`PhotoExportService` 持有实际运行协程。工作台仅创建请求、发起接管并观察状态，页面生命周期结束不再取消已接管任务。`ExportExecutionState` 的 `active/preparing/stopping` 包含启动和收尾占用；`run` 提供实际结果，`notice` 提供结束／失败说明。原生取消排空及 Room 收尾结束之前不接受新请求。

服务通知显示阶段、已保存／失败／待核对数量，并提供暂停、取消余下和返回应用入口。通知携带请求 UUID 加独立会话 UUID，旧通知不能停止后来恢复的同一请求。启动 15 秒仍未接管时暂停请求；启动异常也保留任务。启动失败或接管前暂停／取消只由一个收尾协程写记录，暂停期间升级取消不会留下另一条延迟写入影响新请求。

API 35 及以上选择 `mediaProcessing`，较早系统选择 `dataSync`；清单声明对应类型与权限。服务只接受应用当前的明确启动请求，`START_NOT_STICKY`，不开机启动或自动恢复。持有带过期时间、定期续期的 CPU 唤醒锁，结束时释放。系统超时回调先取消任务并及时停止前台服务，原生排空和记录收尾另行完成；进程未被杀死时控制器仍保持占用。另有单次 330 分钟应用时段限制；它不是后台运行保证，也不替代系统的累计限制。

平台依据：[Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types#media-processing)、[超时处理](https://developer.android.com/develop/background-work/services/fgs/timeout)。当前只有源码；没有启动服务、运行迁移、编译或设备验证。通知权限／系统后台限制、锁屏、原生取消、服务销毁、进程被杀及各系统版本行为都尚无实际证据。
