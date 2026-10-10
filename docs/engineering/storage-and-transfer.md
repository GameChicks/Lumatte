# 工作台持久化与传输恢复

当前为源码实现，尚未编译、测试或在设备上验证。传输执行仍由工作台 ViewModel 持有，未实现前台服务；退到后台不保证持续传输，进程结束后由用户明确继续队列。

## 数据归属

- Room `film-mirror-workspace.db`：照片目录、原始 EXIF 文本、稳定照片顺序、传输批次与各项状态。
- DataStore `workspace-settings`：JPG 尺寸百分比、压缩质量、导出目录 URI、当前照片 ID。默认 100%／95／系统相册。
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
| DOWNLOADED | 完整文件已落盘，待导入 |
| PAUSED | 用户暂停、网络断开或上次进程中断，可继续 |
| FAILED | 保留原因和文件，继续／重试只处理未完成项 |
| IMPORTED | 照片目录已提交，后续不重复导入 |
| CANCELLED | 用户取消余下任务，已导入照片不受影响 |

暂停保留部分下载和任务。取消结束本批尚未完成的任务，包括失败项；不撤销已导入照片，也不自动删除可恢复文件。启动把遗留 RUNNING 状态改成 PAUSED，不自动连接相机。

重新连接后按 UDN 核对相机，重新读取目录并核对对象 ID、名称、父目录、资源类型和已知文件长度。下载使用新会话及最新资源地址。HTTP Range 仅在服务器返回 ETag／Last-Modified 时启用；没有验证标记或资源地址变化时重新下载，不拼接不确定的旧片段。

界面可按批次继续、暂停、取消余下任务、查看逐项原因和已完成历史。明细使用 LazyColumn，避免整批明细同时创建。恢复失败时入口显示重试，不静默创建空数据库；导出目录访问失效时显示错误，不悄悄改到其他目录。

## 模块与迁移

`core:storage` 管理数据库与设置；`core:transfer` 管理顺序执行和文件提交；`core:camera` 只管理协议与网络；`core:media` 管理 JPEG 导入与完成记录。研究资料不加入构建。

Room 初始数据库版本为 1，未启用破坏性迁移。已配置 schema 导出目录 `core/storage/schemas`，但未运行编译，因此尚无生成的 schema JSON；首次获准编译后应保留真实生成文件，后续结构升级必须提供明确迁移。

剩余工作包括前台服务、后台通知与恢复入口、视频与自动传输、缓存清理策略、持久导出／显影队列，以及逐机型兼容性。当前实现不证明进程恢复、断点续传或任何相机型号已通过实际验证。
