# 小组件、连接入口与导出反馈修正

本轮恢复“进入相册”三色 `285D34 / 89BB64 / F0DF93` 和“创作记录”三色 `AEA891 / 777967 / 414D43`，不再将这两张指定卡片随全局材料重新配色。

## 小组件配置

此前设置页直接调用 `requestPinAppWidget`，既没有预先配置，也没有成功回调，因此添加结果只能读到默认 Gold 200。现在先进入同一配置页选择胶卷、机型、名称及颜色，再请求桌面添加。使用每次唯一的持久化草稿和显式一次性 PendingIntent 回调，将桌面实际分配的 ID 与草稿绑定后更新 RemoteViews；回调不启动后台 Activity。过期草稿在下次请求时清理。每个已放置组件右上角提供 48dp 编辑入口，独立于卡片主点击；设置中的实例编辑和启动器的 reconfigurable 元数据继续保留。添加请求通过 EXTRA_APPWIDGET_PREVIEW 提供所选配置的预览，避免系统确认框仍显示 Gold 200。桌面启动主页面及配置页均使用 NEW_TASK 标记。

参考 Android 官方 [小组件配置](https://developer.android.com/develop/ui/views/appwidgets/configuration) 与 [AppWidgetManager API](https://developer.android.com/reference/android/appwidget/AppWidgetManager)。不同启动器的实际弹窗及编辑行为未进行设备验证。

## 蓝牙发现

底层原本支持扫描中提交已发现相机：PendingBluetoothSelection 保存所选地址并触发 stopRequested，扫描 finally 停止后继续 GATT。UI 原本需要先点单选框才解锁底部按钮。现在每个发现结果均有可立即点击的连接按钮，共用权限和同一底层路径；不改 S5 的配对及传输协议。实际相机连接未验证。

## 目录与退役模型

公开目录现在由基础 18 卷与新增 6 卷组成，共 24 卷。Portra 800 Push1 / Push2 从运行时 catalogue、mobile-v1 index 及打包模型文件中移除，生成目录脚本排除它们；third_party 原始资料保持原样。FilmBatchCodec / PhotoRecipeCodec 读取旧配方时映射为普通 Portra 800，保留曝光、黑柔、光晕和随机种子；FilmCatalogue.plan 对内存中的旧 ID 也作同样处理。小组件旧 ID 同步映射。Silver 两款不再出现在新选型目录，其历史配方兼容路径按原设计保留。

## 预览与导出的色彩调查

用户确认同图、同参数，与手机相册中的导出成片比较。源码中两条路径均以 BitmapFactory 的 sRGB 首选空间解码，使用同一 FilmCatalogue.plan、同一 CPU/GLES 引擎和扫描查表；输出 JPEG 标记 EXIF ColorSpace=1。没有发现编辑预览额外叠加饱和度、P3 重标或重复 gamma。显影揭示页的暂时反相矩阵在结束时为单位矩阵。

确认存在的计算差异：旧主预览先缩到约 960px，再进行密度颗粒和非线性 scan。原生颗粒方差依赖 `particle_area / pixel_area`，其中 pixel_area 依赖实际渲染尺寸；改变颗粒方差后，非线性 scan 的平均颜色不必相同。缩图与非线性响应的顺序也不同。这能造成预览/导出差异，但没有用用户样片证明它就是手机上偏饱和的全部原因；也未排除厂商相册显示管理差异。

现在主编辑/画框预览按用户选定的导出尺寸解码并运行胶片处理，再缩成显示位图。胶片输出尺寸参与预览缓存键和加载依赖，调整输出比例会重算主预览；目录和画框缩略图仍使用快速近似。输出算法及饱和度未改。原生入口增加真实 sRGB 元数据约束，禁止将未转换的其他色域位图直接当作 sRGB。更精确预览会增加计算及临时内存，取消会等待原生安全退出，展示缓存只持有小图；没有测量具体耗时或内存峰值。

## 动画

任务条整体出现/退出、正文展开/收起采用淡入淡出与高度展开/收缩，遵循减弱动画。退出期间保留最近活动快照，避免完成瞬间先把内容清空。Frame 页面按编辑、收尾、已保存、空状态做 AnimatedContent，保留出场状态中的照片直至过渡完成，避免待处理列表正确退役照片时产生硬切。

## 当前证据

- Kotlin / Android 资源编译通过：`compile-widget-flow-20261016.log`、`compile-preview-output-match-20261016.log`，位于 `tools/.cache/android-prototype/`。
- 最终资源/源码核对编译 `compile-goal-audit-20261016.log` 为 BUILD SUCCESSFUL（1m21s、84 个任务）；包含全部暗盒、退出快照及所选小组件的系统预览。
- 功能收尾后按后续明确请求生成 0.2.0 调试 APK，交付记录在 `docs/releases/0.2.0.md`；没有运行测试、启动模拟器或操作手机。源码和包校验不证明启动器、相机、显示色彩或动画已经在设备上验证。
