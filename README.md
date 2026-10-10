# Lumix Film Mirror

面向松下相机的 Android 照片传输、胶片模拟、LUT 和画框导出应用。

当前处于旧项目恢复和新架构落地阶段。历史源码恢复不等于新应用已运行；未经要求不测试、不打包。

- `docs/product/`：已确认的交互与产品规则。
- `research/filmtool/`：自有 Film Companion 的来源索引、恢复源码及报告；不加入应用构建。
- `tools/`：只读取参考资料的恢复工具。工具缓存与原始会话摘录不纳入 Git。
- `core/`：新项目的数据规则与可复用领域代码。
- `app/`：后续 Android 应用入口及界面。

批量规则、RAW 内嵌 JPEG、导出尺寸及系统主题见 [产品决策](docs/product/decisions.md)。
