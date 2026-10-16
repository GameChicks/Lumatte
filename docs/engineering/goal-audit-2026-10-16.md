# 本轮目标逐项核对

核对范围是用户当前目标；0.2.0 打包、提交 Git 在各项源码与素材收尾后执行。用户已撤销关机要求。遵守项目约定，不运行设备、单元、回归或性能测试，源码/资源编译与资产检查不当作实机证据。

| 用户要求 | 当前实现及直接依据 | 证据边界 |
|---|---|---|
| 恢复“进入相册”和“创作记录”的配色 | MirrorHome.kt 两组原始三色常量分别恢复为 285D34/89BB64/F0DF93、AEA891/777967/414D43 | 逐项读取源码；未观察设备显示 |
| 小组件能编辑，不能只选 Gold 200 | 配置页读取 FilmCatalogue 全目录；先配置再 pin；独立持久草稿与成功回调按实例 ID 保存；三个布局都有 widget_edit 按钮、独立配置 PendingIntent；系统添加预览也使用所选名称/颜色 | 核对 Activity、Manifest、provider XML、三个布局、回调与更新路径；不同启动器未实测 |
| 蓝牙扫描中即可连接已发现设备 | CameraBluetoothControls 每个结果旁直接连接按钮在 SCANNING 可用；CameraController 的 PendingBluetoothSelection 结束扫描并继续连接；CameraBluetooth.scan finally 停止扫描 | 已核对完整调用链，不等待 20 秒超时；未与实体相机配对 |
| 同步新增/删除的卷种及暗盒 | 公开 catalogue 基础 18 + Fotufilm 6 = 24；新六卷有编辑介绍、正确分类和独立 PNG；Silver 不出现在新选型；24 个 ID 均直接匹配存在的暗盒资源 | 素材覆盖无缺项；图像逐张观察名称、外形及光照，未在手机看透视/阴影 |
| 补齐全部未完成暗盒，使用 ImageGen | 新增 11 款，所有 PNG 为 1024×1536 RGBA、四角 alpha=0、与生成原件 SHA-256 一致；接入 FilmCartridge 的共用绘制 | 完整提示词与原件路径在 research/film-editorial；未换图标；无程序改图 |
| 删除 Portra 的两个 Push 后端模型 | catalogue、mobile-v1 index 去掉两个 ID；八个运行时文件删除；目录生成脚本排除；旧配方/小组件/内存计划映射普通 Portra 800，保留用户曝光等参数 | 核对实际资产及三个读取入口；third_party 原始资料保留 |
| 调查预览偏饱和、排查色彩空间 | 解码明确 sRGB、同引擎同查表，未发现预览额外饱和或重复 gamma；确认预览先缩图导致颗粒方差及非线性颜色运算顺序不同；主预览改为导出尺寸模拟后缩小，原生入口约束真实 sRGB | 已消除源码中的计算尺寸差异；没有用用户同图在手机复现，不能证明厂商相册显示差异或全部偏色原因已排除；更精确预览增加计算/临时内存，未测耗时 |
| 导出进度展开/收起有动画 | MirrorTaskProgress 整体及正文 AnimatedVisibility 淡入淡出/高度过渡；退出保留最近活动内容，遵循减弱动画 | 源码路径完整，未运行设备动画 |
| 进入“已保存”页面有动画 | MirrorFrames AnimatedContent 按编辑/收尾/已保存/空页面切换，保留出场状态中的照片快照直到结束 | 避免队列退役照片后立刻清空出场页面；未运行设备动画 |

功能收尾编译日志：`tools/.cache/android-prototype/compile-goal-audit-20261016.log` 为 BUILD SUCCESSFUL（1m21s、84 个任务）。编译产物包含新增 contentViews / requestPin / completePin 方法，产物时间晚于最终小组件源码编辑。上述源码/素材项收尾后才执行 0.2.0 打包。打包阶段单独记录版本、签名、16KiB ZIP 对齐及 SHA-256；未完成构建不得作为交付 APK。
