# Lumix Film Mirror

面向松下相机的 Android 照片传输、胶片模拟、LUT 和画框导出应用。

当前按用户最新要求优先完善影像功能，UI 由用户另找前端。双 LUT、10 款用户内置及本地导入已接入；胶片光谱 C++／Vulkan、统一曝光／Halation／黑柔、逐张／首张继承、处理阶段和批次配方已接入源码。11 项含无框的共享画框绘制、导出构图预览、自定义装饰／字体与 Room 7 配置也已接入，通过独立 PhotoProcessor 交给前端。原生引擎、画框实际渲染与移动端性能尚无编译／运行证据；黑白模型等仍需继续。历史恢复及新代码不等于应用已运行；未经要求不测试、不打包。见 [前端接口交接](docs/engineering/frontend-contract.md)、[画框功能](docs/engineering/frame-export.md)。

- `docs/product/`：已确认的交互与产品规则。
- `research/filmtool/`：自有 Film Companion 的来源索引、恢复源码及报告；不加入应用构建。
- `tools/`：恢复、元数据读取与素材迁移工具；不运行旧会话命令。工具缓存与原始会话摘录不纳入 Git。
- `core/`：新项目的数据规则与可复用领域代码。
- `core/media/src/main/assets/frames/`：旧应用画框材料与 OFL 字体副本，附来源及哈希清单；用于导出，不是新 UI。
- `app/`：Android 应用入口、系统浅／深色轻拟物工作台和传输界面。
- `app/src/main/assets/luts/`：用户提供的内置 LUT 副本；原始 `lut/` 保留为本机输入目录，不重复加入 Git。
- `research/third-party-sync/`：第三方 Sync APK 分析记录与协议常量；不加入构建。
- `research/ui/`：指定字体副本和前端参考记录；不加入构建。
- `third_party/spektralab/`、`native/film/`：固定原生光谱参考、许可与 Android 适配，尚未编译。
- `native/lut/`：原生 CPU 双 LUT 扫描行与取消适配，保持 Kotlin 后备；效果预览 LRU 和后台清理见 [手机优化源码](docs/engineering/mobile-processing.md)，尚未编译或测量。
- `docs/protocol/`：协议依据、范围及逐机型状态。

批量规则、RAW 内嵌 JPEG、导出尺寸及系统主题见 [产品决策](docs/product/decisions.md)。

相机范围不包含实时取景、遥控拍摄或参数控制。当前实施进展与剩余任务见 [实施状态](docs/engineering/progress.md)。

手动连接相机 Wi-Fi 已有 WPA2／WPA3／无密码选项，按类型校验输入并检查手机 SAE 能力。蓝牙连接继续沿用原有默认类型；详见 [安全类型](docs/engineering/wifi-security.md)。源码尚未以设备验证。

相机多选也可保存已识别的 MP4／MOV／AVCHD 原视频到 `Movies/LumixFilmMirror`，沿用持久队列和前台通知。视频保持原内容，不进入胶片或 JPG 导出设置；各机型传输与手机播放兼容性尚未验证。

工作台可按需查看、清理已登记传输任务的缓存。仅清理完成或取消任务的中间文件与未发布视频占位记录，保留照片、已保存视频、历史记录和可恢复任务；此功能尚未编译或运行。

JPEG 导出另有编码缓存与未发布 JPG 的整理入口，恢复时处理可识别的残留。发布和工作台成功回调共用提交段，取消保留已发布结果。自选文件夹另有持久创建／写入记录与打开核对面板，不参与批量清理；未知创建窗口、提供者差异与持久导出队列仍需继续，见 [JPEG 生命周期](docs/engineering/jpeg-export-lifecycle.md)、[文件夹记录](docs/engineering/folder-export-records.md)。

RAW 导入先尝试 TIFF／RW2 JPEG 标签，再使用固定 LibRaw 的 JPEG 预览接口补充；仅提取内嵌 JPEG，不显影传感器数据。原生源码和构建配置已接入，尚未编译或以真实 RAW 验证，详见 [RAW 预览记录](docs/engineering/raw-preview.md)。

蓝牙查找、旧／APS 身份握手、Wi-Fi 资料读取及显式启动传输网络已加入工作台源码。读到的密码只临时用于连接；按实际特征显示蓝牙启动入口，也保留在相机上手动开启 Wi-Fi 后连接。真实配对及机型差异尚未验证，见 [蓝牙连接记录](docs/engineering/bluetooth-connection.md)。

同路由器 STA 入口支持普通浏览／下载，以及等待相机请求后初始化拍后／选定发送的源码。输入路由器名称，让已保存该网络的相机加入，随后在手机所选 Wi-Fi 上发现相机；后续接收和显式更新发送连接保留此路径。名称已接入中文等完整 UTF-8 编码、32 字节限制和按实际载荷协商长度；不配置路由器密码，多个已有 Wi-Fi 时显式选择并绑定其中一个，见 [同路由器连接](docs/engineering/router-wifi.md)和[已有网络选择](docs/engineering/wifi-network-selection.md)。均未以设备验证。

发送连接另可从 BLE 就绪后的可读特征获取本次相机 IP，绑定所选 Wi-Fi 定点读取设备描述；保留正常 HTTP／模式／UDN 核对。缺少该能力或就绪时走原发现方式，实际地址格式与逐型号时序尚未验证，见 [连接地址记录](docs/engineering/camera-connection-address.md)。

待发送会话区分拍后发送与相机选定发送；蓝牙资料页提供等待相机发送请求后启动直连的入口，并核对 HTTP 模式。用户可发起单轮接收，由前台服务逐项保存并独立记录相机确认。已关联的蓝牙发送会话还可开启后续接收：目录清空后等待 BLE 事件，收到新事件继续同一批次，通知栏可暂停或停止。模式切换或资料更新会暂停旧记录，并提供重新读取网络及握手的入口；另一模式可保留独立暂停记录，同相机只允许一条活跃接收。STA 和更多机型差异仍在接入，尚未编译或验证，见 [自动传输记录](docs/engineering/automatic-transfer.md)。

新应用代码采用 [GNU GPLv3](LICENSE)，许可正文来自 [GNU gnulib 的 COPYINGv3](https://github.com/coreutils/gnulib/blob/master/doc/COPYINGv3)。第三方依赖和恢复素材按各自的来源、COPYRIGHT 与许可清单管理，不由应用许可替代。
