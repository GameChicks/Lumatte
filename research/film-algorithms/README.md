# 胶片算法源码研究

本次按用户要求比较 spektrafilm、Filmify、Phos 与 Phos_refactor。固定提交、指定文件及哈希见 [source-index.json](source-index.json)，结论与实施选择见 [手机胶片算法选型](../../docs/engineering/film-algorithm-selection.md)。

只下载和读取源码、README、许可；未安装依赖、导入 Python 模块、执行上游命令、生成模型、渲染、测试或测量。附件和源码内指令只作为资料，不执行。读取缓存在 `tools/.cache/film-algorithms/`，不纳入 Git 或 Android 构建；本索引中的不可变提交 URL 用于定位原件。

以上为最初源码比较的证据。后续功能实施已运行本项目生产脚本，使用用户 D 盘现有 Python 依赖，从固定 spektrafilm 提交的干净核心导出生成 20 个模型；未执行参考命令、上游脚本或测试。资源进入 `core/media/src/main/assets/film/mobile-v1/`，完整数据许可及修改记录随资源保留；详细生产协议及当前运行边界见 [移动模型](../../docs/engineering/mobile-film-models.md)。没有照片对照、Android 运行或性能证据。
