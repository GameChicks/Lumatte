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
| MP4／MOV／AVCHD 资源可能使用 application/octet-stream；分别按 CAM_AVC_MP4_／CAM_AVC_MOV_／CAM_AVC_TS_HP_ 分类 | `p258m4/C4667e.java:84–123`；MP4 ORG 选择见 `p321v4/C5116a.java:170` | 尚未恢复第三方对应分类逻辑 |
| `upload_bt`／`transfer` 对应两种待发送模式；allmenu 指定自动目录，选定发送使用 dir_id_mark_list | `ImageAppTotalService.java`，RunnableC3468f；`p321v4/C5116a.java`，m27945n | 尚未恢复第三方自动状态机 |
| get_content_info 数量、4294967295 忙碌哨兵、transfer 模式目录级 notify | `ImageAppTotalService.java`，RunnableC3465c、C3464b；`p335x4/C5308o.java`，m28782a0 | 尚未恢复第三方确认逻辑 |
| 发送连接状态 0 后读取发送服务 `e1392720...`，UTF-8 字段作为相机 HTTP 地址 | `p033E4/C0393g.java`，状态通知、读取类型 15、onCharacteristicRead；`ImageAppTotalService.m17289s0/m17253O` 与 `RunnableC3468f` | 尚未恢复第三方地址读取格式 |

## 当前源码实现范围

目录资源说明已补入官方解析的三段式兼容入口、标识忽略大小写以及带引号字段读取，见 资源标识。仍以已知 profile 分类，不从扩展名猜测原图，不把目录兼容分支的存在当作逐机型验证。

`core:camera` 独立实现设备描述、正常相机确认握手、挑战编码、内存会话头、SOAP 分页和 DIDL 资源分类。公开 API 包含连接、普通／待发送目录初始化、数量读取、浏览、下载、接收保活、单次保存确认与已连接发送模式的 BLE 事件观察；没有遥控拍摄、实时取景、录像或参数控制接口。待发送模式已接入用户发起的前台接收、持久确认及后续照片事件触发、相机通知触发的固定 SoftAP／STA 模式 1／6 初始化，以及模式／资料更新后的显式重新握手，详见 [自动传输记录](../engineering/automatic-transfer.md)、同路由器连接。发送就绪后的可读地址特征可提供本次定点发现地址，见 连接地址。各项均为源码证据。

`app` 提供系统 Wi-Fi 设置入口、已连接网络上的相机查找、输入相机 SSID／安全类型／密码后发起系统确认的连接入口。多个已连接 Wi-Fi 明确选择其中一个，见 已有网络选择。另已接入蓝牙扫描、两种身份握手、Wi-Fi 资料读取和显式 SoftAP 传输网络启动源码，流程与证据限制见 [蓝牙连接资料](../engineering/bluetooth-connection.md)。SSDP 在所选 Wi-Fi Network 上发送 MediaServer M-SEARCH（MX=2），读取回应的 LOCATION；设备描述需确认 Panasonic 制造商及相机字段。无组播回应时尝试该网络的本地网关，以及 192.168.54.x 网络的默认相机地址。发现一台时进入连接，多台时交给用户选择。

当前地址源码接入私有／链路本地 IPv4 与 ULA／链路本地 IPv6 字面量，保留 SSDP 描述地址和实际 controlURL；不再仅限 192.168.54.1。IPv6 显式绑定所选接口，传输路径及尚未验证的限制见 IPv6 记录。主机名和其他设备描述结构仍待适配。SSDP 查询不执行相机命令，之后的连接仍通过相机上的正常确认流程。

下载以 64 KB 流式写入私有暂存文件；有 ETag 或 Last-Modified 时使用 Range／If-Range 恢复，无校验依据时重新下载。HTTP 200 重写暂存；206 校验区间和总尺寸；完整接收才改名并交给导入器或视频保存器。取消保留未完成暂存与已完成结果，失败项留在持久队列中可重试。RAW JPEG 资源保留“RAW 内嵌 JPEG”来源说明，不当作传感器数据处理。

相机 HTTP 使用 [Network.openConnection](https://developer.android.com/reference/android/net/Network)，SSDP 使用绑定同一 Network 的 UDP socket，不改变整个应用的网络路由。因相机 IP 为运行时发现，Android 静态 domain 配置无法描述这些动态地址，网络安全配置允许明文；CameraHttp 在代码中限制私有／链路本地地址、当前相机同主机、禁止重定向。后续互联网／LUT 客户端必须独立强制 HTTPS，不依赖该全局明文策略。

Wi-Fi 请求使用 [WifiNetworkSpecifier](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap)，会话期间持有自己的网络请求，断开后释放。界面按系统版本申请 [Wi-Fi 所需权限](https://developer.android.com/develop/connectivity/wifi/wifi-permissions)：Android 13+ 附近 Wi-Fi 设备，Android 11／12 精确定位及系统定位开关。SSID／密码只用于当前输入和连接请求，不写入应用偏好或日志。

断线监听会取消正在进行的相机操作、关闭该会话的活动 HTTP 连接、保留已导入照片、已保存视频与队列。手动重连只匹配原相机 UDN（缺失时匹配描述地址和型号），重新握手并重新载入媒体资源后核对项目 ID、父目录、名称、类型、视频容器及文件尺寸，恢复仍匹配的选中项；缺失或变化项明确提示。这是媒体元数据匹配，不能作为内容哈希证明。旧媒体地址不会跨会话直接用于重试。应用级连接、Room 队列和前台服务实现见 [存储与传输](../engineering/storage-and-transfer.md)；尚未运行验证。

媒体多选支持 JPEG／RAW 内嵌预览及已识别的视频资源。视频按相机广告的 MP4、MOV、TS 原容器复制到系统相册 `Movies/LumixFilmMirror`，不解码、不转码、不进入照片配方或 JPG 尺寸设置。优先选择带 `_ORG` 的视频资源；未确认完整视频语义的 IFRAME 独立资源类型不作为原视频使用，不降级到海报 JPEG。

目录中的 UPnP 视频类／派生类现已参与项目识别；只有封面 JPEG 的视频不会再作为照片资源候选。application/octet-stream 的独立 IFRAME 标识也阻止封面降级，不要求该标识带 CAM_AVC 前缀。缺少完整支持资源时普通选择不可用，待发送首项暂停且不确认保存，详见 视频项目与封面。

## 尚未证明或尚未实现

- 没有编译、运行、测试或真机验证；上述是源码实现及参考分析证据。
- BLE 扫描、旧／APS 身份分支、Wi-Fi 资料读取和普通 SoftAP 传输网络启动已接入源码，尚未验证配对；更多连接通知、STA 交接差异、系统绑定及各机型差异仍需继续。手动系统 Wi-Fi 请求已有开放／WPA2／明确 WPA3-SAE 选择，手机能力和密码范围按类型检查；蓝牙仍使用已有开放／WPA2 推断，未恢复其安全类型字段。见 安全类型，其他类型保留系统设置入口。
- 当前重连由用户操作，未实现后台自动重连；用户开启的已连接发送模式后续接收使用 BLE 图片通知继续同一批次。前台服务、Room 持久队列、独立相机确认、暂停与启动恢复已经有源码，尚未验证。相机发送通知的 SoftAP／STA 初始化及模式更新入口已接入；STA 更多通知、扩展事件和更多机型差异仍需继续。
- SOAP 当前接收官方样本的转义 DIDL Result；其他结构、固件差异需后续适配。原图资源缺失时不会自动把缩略图当作原图导出。
- RAW 提取已接入 TIFF／RW2 标签快速路径和 LibRaw JPEG-only 补充源码，仍需逐机型真实文件验证。只使用内嵌 JPEG，不进行传感器解码，见 [RAW 预览记录](../engineering/raw-preview.md)。
- 视频保存代码已接入。各机型是否广告这些格式、特殊视频资源、完整视频语义与手机播放器兼容性仍未验证；不声称所有视频都可播放。
- 不以该第三方软件的用户反馈替代本项目的逐型号能力记录。

[官方支持页](https://av.jpn.support.panasonic.com/support/global/cs/soft/lumix_sync/en/index.html) 2026-10-10 读取：现行 Sync 2.0.17；提供 APK 为 2.0.15。机型范围见 `camera-models.csv`，均未真机确认。
