# 页面与卡片截图

调试构建提供两个手动入口，发布构建不包含它们，也没有后台或自动截图任务。

- `PromoScreenActivity` 运行原有 `MainActivity` 页面及生命周期，只临时隐藏系统栏。页面比例按维护者最后确认的 9:19.5，模拟器设为 1080×2340、480dpi、字号 100%。不拉伸已有截图。
- `PromoCaptureActivity` 直接调用主页的 `CameraHero`、`ActionAtmosphere`、`UsageCard` 和 `HomeJournalCard`，通过 Compose 图层导出透明 PNG。导出与主页共用 `homeCardHeight`，并排卡片保留半列宽，取消另设的宣传画板比例。没有另一套卡片外观。

卡片类型参数 `card` 可选 `camera / bluetooth / phone / usage / journal`。维护者明确要求相机卡片截图展示 S5 已连接，因此可额外传 `connected-s5=true`；这一选择只传给导出组件，不写偏好、相机控制器或数据库，不发网络请求。其他记录使用当前工作区数据。组件输出位于应用缓存 `promo-output/<card>.png`，由维护者通过调试工具取出。

恢复的主页尺寸来自改动前的源码：内容宽度乘 0.654，限制在 282–340dp，另按原规则适配大字号。截图和软件使用同一函数，避免素材比例与产品不一致。字体、渐变、按钮和文字位置均来自现有组件。

蓝牙与手机相册两张半列入口单独采用紧凑比例：高度为半列宽的 1.35 倍，限制在 200–240dp，并给大字号保留增量。它们不再沿用整列相机卡片的高度。透明导出共用此规则，其余卡片比例保持不变。

完整页面截图、独立透明 PNG、实际页面索引及压缩附件放在 `dist/`。用户照片、例图缓存和宣传附件不提交到 Git。截图中的 S5 状态是指定的组件展示状态，不能当作实体相机连接验证证据。

0.2.3 的交付索引位于 `dist/promo-0.2.3/index.html`，清单与图片哈希在同目录 `manifest.json`。页面包含真实导出完成、收藏与拍立得展开、全 Lab 色彩探索及搜索、设置与维护弹窗。相机实物配对和媒体网格尚无硬件截图，索引明确列出，不提供虚构替代图。原生系统图标通过临时模拟器设置隐藏，结束后恢复；截图未涂抹、拼接或拉伸。

手动进入无系统栏页面：`adb shell am start -n com.lumixfilmmirror.app/.PromoScreenActivity`。

手动导出相机卡片：`adb shell am start -n com.lumixfilmmirror.app/.PromoCaptureActivity --es card camera --ez connected-s5 true`。入口只支持固定类型和应用自己的输出位置，不接受任意路径。
