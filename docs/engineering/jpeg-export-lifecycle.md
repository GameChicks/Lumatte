# JPEG / PNG 导出与中间文件生命周期

2026-10-11，源码接入。没有编译、测试、设备运行或打包，不能据此宣称数据恢复与取消操作已经通过验证。

## 编码与发布

输出尺寸默认有效输入尺寸的 100%，百分比和压缩质量仍为独立设置；格式可选 JPEG（默认）或 PNG 透明底。PNG 为无损编码、不使用压缩质量，也不写 EXIF。导出逐张读取本张 EXIF，旋转像素后写普通方向标记，RAW 说明只在界面出现。本轮只给画框输出增加格式与透明轮廓，没有调整影像算法、输入精度或尺寸语义。

每项先在本应用 `cache/jpeg-export/output-<UUID>.jpg` 或 `.png` 编码，再同步文件；JPEG 还写 EXIF。新导出文件名为 `LFM-yyyyMMdd-HHmmss-SSS-<完整随机 UUID>.jpg`（或 `.png`）；每次导出都具有独立身份，不覆盖已导出的同一输入。旧名称的成片保留。

默认相册采用 `Pictures/LumixFilmMirror/` 下的 `MediaStore.Images` pending 行：先创建 `IS_PENDING=1`，以 64 KB 缓冲复制、同步目标文件、重新读取长度核对，再清除 pending 并记录工作台的成功数量与 URI。[Android 相册发布流程](https://developer.android.com/training/data-storage/shared/media) 是平台依据；不是实际设备证据。

发布与成功回调置于同一 `NonCancellable` 提交段，避免文件已可见、从 IO 返回时协程取消导致工作台漏记成功项目。之后取消只终止后续导出；处理成功和失败项目各计一次，取消中未提交的项目不计为成功。[withContext 的返回取消语义](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html) 是此次调整的依据。

相册失败清理再次限定 owner、本轮文件名、固定位置、JPEG / PNG MIME 和 `IS_PENDING=1`。即使发布更新已生效而回调／响应抛错，已可见的文件也不会命中删除条件。此时发布结果可能需要用户核对，不能用异常本身断言没有成片。

自选 SAF 文件夹沿用 `createFile` 和流复制，核对写入字节数、flush 并关闭目标后才进入成功回调。创建前写私有 PREPARED 记录，取得并登记 URI 后写 CREATED；提交保存 COMMITTED 再调用成功回调。回调以后不会删除成功文件；复制失败或提交前取消只尝试删除本次创建且登记的目标。完成记录保存失败保留已写完的文档并提示核对，见 文件夹导出记录。SAF 提供者的实际落盘、远端同步与长度读取能力没有实测；不把字节计数当作云端持久化证明。

## 恢复与整理范围

JPEG 写入、缓存统计和清理共用进程级 Mutex，包括不同 ViewModel 的 writer 实例。新工作台恢复会在取得锁后整理遗留编码文件和可识别的相册 pending 占位；活动 writer 的编码、复制和发布期间不会被清理。普通成功、失败和取消路径在 finally 删除私有编码文件；删除失败的遗留文件可由恢复／手动整理再次处理。

| 对象 | 范围与处理 |
|---|---|
| 新编码文件 | 仅 `cache/jpeg-export/` 单层 `output-<UUID>.jpg` / `.png` 普通文件 |
| 较早编码文件 | 同目录的 `output-<数字>.jpg`／负数字变体，对应此前 `createTempFile` 名称 |
| 相册占位 | 本应用 owner、固定 Pictures 目录、JPEG 或 PNG MIME、pending=1，完整 UUID 新名称或时间戳加八位输入前缀的旧名称 |
| 已发布照片 | pending=0 不在查询／删除条件内，保留 |
| 工作台／相机文件 | `source-jpegs`、RAW 原始文件、传输目录、视频均不在此范围 |
| 未识别项目 | 保留并显示说明；不递归、不跟随符号链接 |
| 自选文件夹 | 不扫描、不按文件名认领或批量删除 |

相册统计通过 [QUERY_ARG_MATCH_PENDING](https://developer.android.com/reference/android/provider/MediaStore#QUERY_ARG_MATCH_PENDING) 包含 pending 行，再核对 owner／路径等字段。删除语句再次限定这些字段及 pending 状态，避免统计之后状态变化导致删到成片。能取得的目标尺寸求和；取不到的单独标记数量，不冒充全部占用已知。

工作台提供“导出中间文件”面板，显示编码缓存、未发布图片、不可读尺寸和保留原因，可手动刷新或清理。操作期间阻止本页面启动另一项导出／导入／相机操作。各项独立整理，结束按实际剩余资源重读，失败原因保留。

## 尚需继续

- 后续 Room 8 已接入持久导出请求、逐项阶段、候选 URI 与成功结果，详见 [导出任务](export-queue.md)。队列不从 pending 占位或残缺文档续写；未保存项重新编码，保存结果未知项先核对，已发布照片保留。照片导出前台服务及暂停／取消通知已接入源码，后台生命周期、系统超时、恢复及迁移未运行验证。
- SAF 已接入持久创建记录、恢复读取及打开核对；创建目标与保存 URI 之间仍可能留下未知文档，不扫描用户文件夹删除，不声称已恢复任意 SAF 中断。导出任务保存候选 URI 不能消除此外部窗口；实际提供者能力、后台执行和继续写入仍需继续。
- 发布响应未知、文件名被提供者调整、目录／媒体归属变化等情况仍需实际核对；未识别的记录会保留。
- 未登记的 EXIF 内部临时文件、其他应用缓存和未来 LUT／胶片引擎缓存不在此清理范围。
