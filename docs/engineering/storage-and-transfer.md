# 工作台持久化与传输恢复

当前为源码实现，尚未编译、测试或在设备上验证。用户从工作台启动传输后，由前台服务执行；进程结束后由用户明确继续队列。已实现后台生命周期代码，不以此声称各 Android 版本和厂商设备已验证持续传输。

## 数据归属

- Room `film-mirror-workspace.db`：照片目录、原始 EXIF 文本、处理批次与成员顺序、传输批次与各项状态，以及视频保存目标 URI。
- DataStore `workspace-settings`：JPG 尺寸百分比、压缩质量、导出目录 URI、当前照片 ID 和处理批次 ID。默认 100%／95／系统相册。
- 原图只读。仅应用私有目录保存输入副本、下载中间文件及内嵌 JPEG；不读取其他应用的数据库、密码、签名或身份。
- 相机 UDN、型号、目录对象 ID 和资源描述保存在队列中，用于重连核对。会话令牌、Wi-Fi 密码、系统 Network 对象不保存。

## 文件与提交顺序

```
files/source-jpegs/<photo UUID>/source.jpg
files/source-jpegs/<photo UUID>/source.json
files/camera-transfers/<task UUID>/<resource URI SHA-256>.part
files/camera-transfers/<task UUID>/<resource URI SHA-256>.validator
files/camera-transfers/<task UUID>/complete.download
```

相机任务 ID 同时作为导入照片 ID。完整下载验证长度、同步文件，再改名为固定的 `complete.download`。导入时先准备 JPEG，最后以 AtomicFile 写入带版本号的完成记录；Room 在同一事务中写入照片目录并标记 `IMPORTED`。下载完成文件仅在该事务成功后清理。

启动时只扫描本应用 `source-jpegs` 中的 UUID 目录，以有效完成记录补回尚未进入数据库的照片。缺失或损坏的完成记录显示说明；不删除已存在的数据库记录或原图。较早源码产生的无记录副本暂不自动接纳，不声称已完成旧版本数据迁移。

再次继续任务时，先检查已入库照片／独立导入记录，然后检查完成下载文件，最后才连接相机下载。这使进程在下载完成、导入完成、数据库提交等不同位置中断时都有明确恢复入口。取消与提交同时发生时，已经完成提交的照片仍保留。

## 队列状态与操作

| 状态 | 含义及下一步 |
|---|---|
| QUEUED | 待执行 |
| RUNNING | 正在下载，进度最多每 500 ms 写入一次 |
| DOWNLOADED | 完整文件已落盘，待导入照片或保存视频 |
| PUBLISHING | 视频正在写入系统相册，目标 URI 已保存 |
| PAUSED | 用户暂停、网络断开或上次进程中断，可继续 |
| FAILED | 保留原因和文件，继续／重试只处理未完成项 |
| IMPORTED | 照片目录已提交，后续不重复导入 |
| SAVED | 原视频已发布到系统相册，后续不重复复制 |
| CANCELLED | 用户取消余下任务，已导入照片和已保存视频不受影响 |

暂停保留部分下载和任务。取消结束本批尚未完成的任务，包括失败项；不撤销已导入照片或已保存视频，也不自动删除可恢复文件。启动把遗留 RUNNING／PUBLISHING 状态改成 PAUSED，不自动连接相机。

重新连接后按 UDN 核对相机，重新读取目录并核对对象 ID、名称、父目录、资源类型和已知文件长度。下载使用新会话及最新资源地址。HTTP Range 仅在服务器返回 ETag／Last-Modified 时启用；没有验证标记或资源地址变化时重新下载，不拼接不确定的旧片段。

界面可按批次继续、暂停、取消余下任务、查看逐项原因和已完成历史。明细使用 LazyColumn，避免整批明细同时创建。恢复失败时入口显示重试，不静默创建空数据库；导出目录访问失效时显示错误，不悄悄改到其他目录。

## 模块与迁移

`core:storage` 管理数据库与设置；`core:transfer` 管理顺序执行和文件提交；`core:camera` 只管理协议与网络；`core:media` 管理 JPEG 导入、完成记录及原视频保存。研究资料不加入构建。

Room 当前数据库版本为 4。1 → 2 新增可为空的 `savedUri` 列；2 → 3 新增 `incoming_transfers` 接收记录表及 `transfers.cameraAcknowledgement` 可空列；3 → 4 新增处理批次和预登记成员表，按原队列归组，其他旧照片进入历史组，见 [照片处理批次](processing-batches.md)。照片、原队列和已保存 URI 保留；未启用破坏性迁移。已配置 schema 导出目录 `core/storage/schemas`，但各版本都未运行编译，因此尚无生成的 schema JSON；首次获准编译后保留真实生成的当前版本文件，不伪造历史 schema。[迁移 API 依据](https://developer.android.com/training/data-storage/room/migrating-db-versions)

## 待发送接收记录

待发送接收有独立的轮次记录，UUID 同时作为文件任务的 batchId。每次登记目录首项，并在同一事务里绑定当前任务 ID；单项保存仍使用已有的照片／视频提交流程。相机确认状态同事务写入轮次及当前文件，旧文件的确认状态不随下一项登记而丢失。文件完成不等于相机确认完成；即使本批文件全部标记 IMPORTED／SAVED，接收记录仍可显示待确认、响应未知或暂停。用户开启的后续接收在目录清空时记为 WAITING，同一批次等待 BLE 事件再追加文件；进程恢复把 WAITING 改为 PAUSED，不自动重建 GATT 或启动服务。

恢复将 IN_FLIGHT 确认改为 UNKNOWN，不自动重发目录级确认。继续核对只对尚在首项的同一文件保留歧义；原文件已不再为首项时记录目录前进，但不虚构确认成功。停止接收保留已保存文件及确认历史，不自动退出相机发送模式。完整流程及限制见 [待发送接收](automatic-transfer.md)。

模式切换后旧接收保留 PAUSED；按相机、模式和目录选择对应未结束记录，另一模式可建立独立记录，不要求删除或取消旧记录。Room 事务在创建／恢复和写入 RUNNING／WAITING 时检查同相机其他活跃接收，限制同时只有一个；暂停记录的文件、序号、UNKNOWN 与确认历史不迁移到新模式。该接收状态逻辑未新增列或表；当前版本 4 的新增表用于照片处理批次。

## 原视频保存与恢复

视频沿用同一持久队列和前台服务，但不创建照片目录记录。相机广告资源按 MIME 与 Panasonic profile 共同识别 MP4、MOV 和 AVCHD／TS，不从文件名猜测陌生格式，也不下载 IFRAME 独立资源替代完整视频。

64 KB 流式复制到系统相册 `Movies/LumixFilmMirror`，不解码、不改尺寸、不转码，保持下载文件的字节内容。JPG 尺寸、胶片配方和画框均不作用于视频。手机是否能播放具体编码由播放器和设备决定，保存成功不等于播放兼容。

发布先创建 `IS_PENDING=1` 的本应用视频记录，再将目标 URI 与完整字节数写入任务。复制并同步文件后，核对文件长度，再设置 `IS_PENDING=0`；随后在不可取消的提交段中标记 SAVED，避免刚发布就被取消操作标成未完成。下载副本仅在确认提交后清理。

MediaStore 与 Room 无法共用事务。为覆盖“相册已创建，但 URI 还未写入 Room”的中断位置，文件名使用任务完整 UUID：`LFM-<task UUID>.mp4/mov/mts`。继续时按已存 URI，或本应用拥有的同名、同目录记录查找。未完成记录可重写；已发布文件长度匹配则补记完成，不再复制，已发布但不匹配的文件不覆盖。原相机文件名仍保存在队列并显示在界面。

查询只针对本应用记录，可包含本应用 pending 项；不申请读取整库视频权限，也不枚举其他应用的视频。[Android 媒体存储说明](https://developer.android.com/training/data-storage/shared/media) 与 [MediaColumns 字段](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns) 提供相应 API 依据。文件长度和元数据匹配不构成内容哈希或视频解码验证。

取消或失败后的未发布相册记录和私有下载副本暂保留；取消项可由用户清理，失败项继续保留供重试。系统 pending 过期回收可能移除未发布记录。已完成视频可从队列明细交给外部播放器打开。用户手动删除、编辑、移动或还原相册、系统供应商差异及存储空间不足的实际行为仍待验证。

## 传输缓存清理

工作台提供“查看占用／刷新占用”和“清理可释放缓存”。统计读取已登记任务的私有传输目录，以及本应用创建、尚未发布的相册视频记录；不是整个应用或手机的存储占用。统计按需执行，不随每次下载进度扫描磁盘。

查看与清理沿用应用级相机操作占用，执行期间不启动另一个连接、传输或缓存操作；已经预留或正在执行的前台传输也会阻止清理。操作归应用协程所有，Activity 重建不创建第二个清理过程。UI 不允许在照片导入、导出或工作台恢复期间发起清理。

执行时重新读取 Room 状态，只考虑 IMPORTED、SAVED、CANCELLED。QUEUED、RUNNING、DOWNLOADED、PUBLISHING、PAUSED、FAILED 都保留中间文件与未发布视频。清理不删除任务历史，不修改任务状态、原相机文件、`source-jpegs` 工作台副本、已导出 JPG 或任何已发布视频。IMPORTED 的工作台 JPEG 缺失，或 SAVED 的相册视频不可读／长度不符时，保留其剩余下载文件并报告原因。

文件删除只针对 `camera-transfers/<已登记任务 UUID>/` 的 `complete.download` 和 SHA-256 命名的 `.part`／`.validator` 文件，不递归删除、不遍历未登记目录、不跟随符号链接。路径或目录内容异常时保留整项；成功后只尝试移除对应空目录。空占位文件也可清理。

取消任务可能留下 MediaStore pending 行，包含“插入行后、Room 尚未记录 URI”的中断位置。清理按任务 UUID 及已存 URI 查找，核对本应用归属、原位置和文件名；删除语句再次限定 `IS_PENDING=1`、owner、文件名和路径，已发布视频不进入删除条件。[本应用媒体归属规则](https://developer.android.com/training/data-storage/shared/media) 与 [ContentResolver 删除 API](https://developer.android.com/reference/android/content/ContentResolver#delete(android.net.Uri,%20java.lang.String,%20java.lang.String[])) 是 API 依据，不是设备验证结果。

各项独立处理，某项失败后继续其他项；结束重新统计，界面显示保留原因。取消或系统结束清理可能留下部分已释放、部分未清理的缓存，再次刷新按实际文件重新统计。缓存统计失败时显示不完整提示，不把无法读取的项目当成零占用。默认不自动按时间删除、不给恢复任务设期限。

传输清理只覆盖已登记任务。JPEG 编码临时文件和本应用未发布 JPG 占位已接入独立的恢复／手动整理入口，见 [JPEG 生命周期](jpeg-export-lifecycle.md)。SAF 另有持久创建记录、恢复读取及打开核对，不自动清理外部文档，见 文件夹记录。未登记传输目录、SAF 提供者差异及未来 LUT／引擎缓存仍需各自处理，不能将任一按钮作为全应用清理已经完成的证据。

## 前台服务与页面生命周期

`FilmMirrorApplication` 持有进程内 `CameraController`；传输期间的连接、会话和系统网络请求不会因工作台 ViewModel 重建而丢失。`CameraTransferService` 提供实际执行的 CoroutineScope，工作台只观察它及 Room 的状态。界面重新创建时，不把仍在运行的任务当成上次中断任务暂停；无页面、无操作、无传输时释放应用自己的网络请求。

用户的“传入”或“继续”操作先建立持久队列，再请求启动服务。服务立即发布低优先级进度通知；服务未能接管时保留队列并显示原因。每次只有一个活跃批次。较早服务任务的完成回调不会停止新批次；通知操作包含批次身份，过期动作不会暂停另一批任务。

服务采用 `connectedDevice` 类型，清单声明相应前台服务权限及网络权限。依据 [Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types) 中外部设备网络交互的定义，以及 [启动服务说明](https://developer.android.com/develop/background-work/services/fgs/launch) 的前台启动流程。服务不在开机广播或进程恢复时自动启动，不自动连接相机。

通知显示已传入数量，提供暂停、取消余下和返回工作台。Android 13+ 首次发起传输时请求通知权限；拒绝后仍保留工作台操作，不以授权作为照片传入的条件，依据 [Android 通知权限说明](https://developer.android.com/develop/ui/compose/notifications/notification-permission)。权限弹窗中的待启动动作保存为操作／批次标识，不保存页面回调。

实际传输期间持有带超时续期的 partial wake lock 和 Wi-Fi lock。结束、取消、启动失败或服务销毁时释放；通知刷新每秒一次，UI 字节进度最多每 100 ms 一次，Room 字节检查点最多每 500 ms 一次。

服务使用 `START_NOT_STICKY`。任务从最近列表移走时允许当前前台服务继续；系统终止进程、强制停止或网络断开时保留未完成任务，不能保证系统限制下无限后台运行。服务销毁与超时入口暂停任务；若进程没有机会执行清理，下次启动恢复遗留 RUNNING／PUBLISHING 状态。已导入照片和已保存视频继续保留。

后续 Room 8 已新增 [导出请求与逐项结果](export-queue.md)，注册 `7 → 8` 增表迁移；照片导出另由应用共享控制器及前台服务接管，通知提供暂停／取消余下，系统超时入口及时结束服务后继续原生取消收尾。恢复、继续、取消、核对及服务仍只是源码，没有执行迁移或进程验证。剩余工作包括 STA／扩展 BLE 通知与更多配对差异、特殊视频资源适配、SAF 创建未知窗口／提供者差异及其他缓存生命周期、导出服务系统兼容性与时限行为验证，以及逐机型兼容性。当前实现不证明缓存清理、视频保存、待发送接收、后台传输或导出、进程恢复、断点续传或任何相机型号已通过实际验证。
