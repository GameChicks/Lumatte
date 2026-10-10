# Lumix Film Mirror

面向松下相机的 Android 照片传输、胶片模拟、LUT 和画框导出应用。

当前按规划进入传输与最小照片链路阶段，已建立导入、预览、尺寸／质量设置和整批导出的工作台源码，并接入相机发现、系统 Wi-Fi 连接、浏览传输和手动重连入口。Room／DataStore 保存照片目录、传输队列和设置，界面提供暂停、继续、取消及恢复入口。历史源码恢复及新代码不等于应用已运行；未经要求不测试、不打包。

- `docs/product/`：已确认的交互与产品规则。
- `research/filmtool/`：自有 Film Companion 的来源索引、恢复源码及报告；不加入应用构建。
- `tools/`：只读取参考资料的恢复工具。工具缓存与原始会话摘录不纳入 Git。
- `core/`：新项目的数据规则与可复用领域代码。
- `app/`：Android 应用入口、系统浅／深色轻拟物工作台和传输界面。
- `research/third-party-sync/`：第三方 Sync APK 分析记录与协议常量；不加入构建。
- `docs/protocol/`：协议依据、范围及逐机型状态。

批量规则、RAW 内嵌 JPEG、导出尺寸及系统主题见 [产品决策](docs/product/decisions.md)。

相机范围不包含实时取景、遥控拍摄或参数控制。当前实施进展与剩余任务见 [实施状态](docs/engineering/progress.md)。
