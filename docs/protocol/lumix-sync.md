# 相机连接与传输协议记录

分析样本与哈希见 `research/reference-apks.json`。官方 Sync 样本 2.0.15；第三方样本 0.0.7。以下源文件路径位于忽略 Git 的 JADX 输出，不将完整第三方源码加入构建。

## 已定位的协议事实

| 协议 | 官方样本依据（相对 research/lumix-sync/decompiled/sources） | 第三方交叉依据 |
|---|---|---|
| `http://<host>:60606/Lumix/Server0/ddd` 设备描述 | `p335x4/RunnableC5294a.java`，m28706T | AOT 对应字符串及原生 Wi-Fi 路由 |
| `cam.cgi?mode=accctrl&type=req_acc`，value=客户端 ID，value2=客户端名称 | `com/panasonic/p189jp/service/C3472a.java:417`、`p335x4/C5307n.java` | AOT req_acc 常量 |
| X_CamCapability 末位为 1 时使用挑战握手 | `p258m4/C4665c.java`，m25814i；`C3472a.java:427` | AOT req_acc_g／req_acc_e 常量，只能证明存在 |
| req_acc_g 回复第二个 CSV 字段为十六进制挑战值 | `RunnableC5294a.java:923` | AOT `?mode=accctrl&type=req_acc_g` |
| req_acc_e 编码：补齐 4 字符、与挑战值异或、转换字节序、十六进制表示 | `p068J4/C0944s.java:53`，m4425b | 尚未恢复第三方算法；依据官方样本独立实现 |
| 等待相机确认时回复 ok_under_research 或 ok_under_research_no_msg | `C3472a.java:452` | 尚未恢复第三方状态机 |
| 会话字段为恢复 friendlyName 后的 CSV 第五项，后续 HTTP 带 X-SESSION_ID | `C3472a.java:623`、`p265n4/AbstractC4693b.java:98`；名字含逗号的解析见 `RunnableC5294a.java:951` | AOT X-SESSION_ID |
| 播放模式和 RAW 传输开关 | `RunnableC5294a.java`，m28713n 与 m28698L；`C3472a.java:467` | AOT raw_img_send |
| SOAP ContentDirectory Browse：分页、BrowseDirectChildren、X_FromCP LumixLink2.0 | `p321v4/C5116a.java:225`、`p335x4/C5306m.java` | AOT SOAPAction、BrowseFlag、X_FromCP、CDS_control |
| JPEG 原图 CAM_ORG；缩略 CAM_TN／CAM_LRGTN；RAW CAM_RAW；RAW JPEG CAM_RAW_JPG | `p258m4/C4667e.java` | AOT CAM_ORG／CAM_RAW／CAM_RAW_JPG |

## 当前源码实现范围

`core:camera` 独立实现设备描述、正常相机确认握手、挑战编码、内存会话头、SOAP 分页和 DIDL 资源分类。公开 API 仅包含连接、浏览初始化、浏览和下载；没有遥控拍摄、实时取景、录像或参数控制接口。

`app` 接入已连接相机 Wi-Fi 的基础入口，当前使用默认地址 `192.168.54.1`。优先选择地址为 192.168.54.x 的 Wi-Fi Network；否则仅在只有一个 Wi-Fi Network 时选择。多网络歧义不自动猜测。设备描述中的 ContentDirectory controlURL 优先于固定路径。

下载以 64 KB 流式写入私有暂存文件；有 ETag 或 Last-Modified 时使用 Range／If-Range 恢复，无校验依据时重新下载。HTTP 200 重写暂存；206 校验区间和总尺寸；完整接收才改名并交给导入器。取消保留未完成暂存与已导入照片，失败项仍选中可重试。RAW JPEG 资源保留“RAW 内嵌 JPEG”来源说明，不当作传感器数据处理。

相机 HTTP 使用 [Network.openConnection](https://developer.android.com/reference/android/net/Network)，不改变整个应用的网络路由。当前网络安全配置仅允许默认相机地址明文 HTTP，其他互联网请求仍要求 HTTPS。未来加入非默认地址时需一起扩展发现、连接和网络安全配置。

## 尚未证明或尚未实现

- 没有编译、运行、测试或真机验证；上述是源码实现及参考分析证据。
- 当前入口依赖用户已连接相机 Wi-Fi；SSDP 发现、BLE 配对和系统 Wi-Fi 连接请求尚未接入。
- 断线后需用户重新连接；后台前台服务、Room 持久队列、暂停与进程死亡恢复尚未接入。
- SOAP 当前接收官方样本的转义 DIDL Result；其他结构、固件差异需后续适配。原图资源缺失时不会自动把缩略图当作原图导出。
- RAW 提取仍只有 TIFF／RW2 标签快速路径，规划的 LibRaw 缩略图接口尚待加入。
- 视频资源分类已保留，但当前工作台只导入可编辑照片；视频保存仍待实现。
- 不以该第三方软件的用户反馈替代本项目的逐型号能力记录。

[官方支持页](https://av.jpn.support.panasonic.com/support/global/cs/soft/lumix_sync/en/index.html) 2026-10-10 读取：现行 Sync 2.0.17；提供 APK 为 2.0.15。机型范围见 `camera-models.csv`，均未真机确认。[Android 系统 Wi-Fi 连接请求说明](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap) 作为下一步系统连接入口依据。
