# Lumix Film Mirror

面向松下相机的 Android 照片传输、胶片模拟、LUT 和画框导出应用。

当前按规划进入传输与最小照片链路阶段，已建立导入、预览、尺寸／质量设置和整批导出的工作台源码，并接入相机发现、系统 Wi-Fi 连接、浏览传输和手动重连入口。Room／DataStore 保存照片目录、传输队列和设置，前台服务执行照片传入，界面和通知提供暂停、取消及恢复入口。历史源码恢复及新代码不等于应用已运行；未经要求不测试、不打包。

- `docs/product/`：已确认的交互与产品规则。
- `research/filmtool/`：自有 Film Companion 的来源索引、恢复源码及报告；不加入应用构建。
- `tools/`：只读取参考资料的恢复工具。工具缓存与原始会话摘录不纳入 Git。
- `core/`：新项目的数据规则与可复用领域代码。
- `app/`：Android 应用入口、系统浅／深色轻拟物工作台和传输界面。
- `research/third-party-sync/`：第三方 Sync APK 分析记录与协议常量；不加入构建。
- `docs/protocol/`：协议依据、范围及逐机型状态。

批量规则、RAW 内嵌 JPEG、导出尺寸及系统主题见 [产品决策](docs/product/decisions.md)。

相机范围不包含实时取景、遥控拍摄或参数控制。当前实施进展与剩余任务见 [实施状态](docs/engineering/progress.md)。

相机多选也可保存已识别的 MP4／MOV／AVCHD 原视频到 `Movies/LumixFilmMirror`，沿用持久队列和前台通知。视频保持原内容，不进入胶片或 JPG 导出设置；各机型传输与手机播放兼容性尚未验证。

工作台可按需查看、清理已登记传输任务的缓存。仅清理完成或取消任务的中间文件与未发布视频占位记录，保留照片、已保存视频、历史记录和可恢复任务；此功能尚未编译或运行。

RAW 导入先尝试 TIFF／RW2 JPEG 标签，再使用固定 LibRaw 的 JPEG 预览接口补充；仅提取内嵌 JPEG，不显影传感器数据。原生源码和构建配置已接入，尚未编译或以真实 RAW 验证，详见 [RAW 预览记录](docs/engineering/raw-preview.md)。

蓝牙查找、旧／APS 身份握手、Wi-Fi 资料读取及显式启动传输网络已加入工作台源码。读到的密码只临时用于连接；按实际特征显示蓝牙启动入口，也保留在相机上手动开启 Wi-Fi 后连接。真实配对及机型差异尚未验证，见 [蓝牙连接记录](docs/engineering/bluetooth-connection.md)。

待发送会话区分拍后发送与相机选定发送，用户可从界面发起本轮接收，由前台服务逐项保存并独立记录相机确认。暂停、继续、停止及接收历史已接入源码；目录清空后结束本轮。BLE 拍后事件和连续自动触发仍在接入，尚未编译或验证，见 [自动传输记录](docs/engineering/automatic-transfer.md)。

新应用代码采用 [GNU GPLv3](LICENSE)，许可正文来自 [GNU gnulib 的 COPYINGv3](https://github.com/coreutils/gnulib/blob/master/doc/COPYINGv3)。第三方依赖和恢复素材按各自的来源、COPYRIGHT 与许可清单管理，不由应用许可替代。
