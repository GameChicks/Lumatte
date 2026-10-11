# LUT 取色、题材与筛选接口（2026-10-11）

本轮补入纯功能后端，不改既有原型 UI。目录、题材标签保存、当前照片卡片预览、Palette 取色和组合筛选已有源码；没有运行 Android 编译、解析样例、图像取色、测试、设备或性能验证，没有 APK。

## 使用顺序

1. 保留 `PhotoProcessor.loadCatalogue()` 返回的数值目录，调用 `loadLutGallery(catalogue)` 获取轻量条目和独立标签读取问题。它不会重新解析 `.cube`、打开网络或生成全部卡片。
2. 对可见条目调用 `previewLutCard(source, asset, longEdge=320)`。它以用户当前照片、单槽 100% LUT、无颗粒／无画框生成实际效果缩略图，再分析颜色。返回 `LutCardPreview.lutId/image/colours`；image 由调用方持有，替换或离开页面时回收，取消返回之前后端负责回收无人接收的结果。
3. 以 lutId 核对对应条目，`entry.copy(exampleColours=preview.colours)` 得到可筛选的数据。PhotoPreview 的 exampleIdentity 是该源照片 ID；切换照片要更换相应颜色与卡片。它不是全库 LUT 属性，也不显示为创作者例图。
4. `LutGalleryFilter.select(entries, LutGalleryQuery(...))` 同时处理名称／Photo Style 搜索、全部／内置／本地、题材、颜色家族和例图近黑白筛选。缺例图的条目不匹配颜色或模式，缺标签的条目不匹配题材；无对应筛选时仍显示，输入顺序保持。
5. `setLutSubjects(asset, subjects)` 保存用户明确选择的题材，返回更新的轻量条目。前端若已持有例图颜色，可在替换 metadata 时保留其临时 exampleColours；标签接口不重新生成图片。

全库颜色筛选需先分析候选条目，不能只分析当前屏幕几张后便将其他条目视为不匹配。`lutGalleryColours(source, catalogue, longEdge=192, onEntry=...)` 串行生产全部本地候选的颜色 metadata，每张处理完成后立即回收卡片 Bitmap，只返回数字结果和各项失败说明。onEntry 逐项提供进度／颜色，消费者异常正常传播，取消停止后续条目。前端在分析期间显示进行中／未知项，切换源照片取消旧请求；需要显示图片时再请求可见卡片。真正创作者例图的全库分析仍需未来网络数据源取得原例图。

题材为日常、自然、街头、人像、建筑、夜景、其他；全部通过 query.subject=null 表示。当前标签来源是用户设置，不根据 LUT 名称或图片颜色推断题材，官方分类的后续映射需依据真实目录。

## 取色与含义

`LutGalleryColours.analyse(bitmap, origin, exampleIdentity)` 可独立分析已合法取得的软件缩略图。输入需在调用返回前保持有效且不修改；硬件 Bitmap 明确拒绝，加载方应直接解码软件缩略图，不在这里复制完整大图。生成自己的 ARGB_8888 分析图，长边最多 128／面积最多 16,384，透明部分按白色相纸合成，始终只回收分析图。

同步 Palette 操作在 Default dispatcher，取最多 16 个颜色，清除默认颜色过滤以保留黑／白／中性灰。颜色、population 及文本对比色接口依据 [Android Palette 说明](https://developer.android.com/develop/ui/views/graphics/palette-colors) 和 [Palette.Builder](https://developer.android.com/reference/androidx/palette/graphics/Palette.Builder)。量化操作前后检查取消，内部操作不提供逐像素取消。

返回最多六个主要 swatch，以及基于全部 swatch population 的颜色家族权重、主色和例图模式。HSV 饱和度低于 .12 或亮度低于 .08 的颜色归中性，其余按固定色相区间归红、橙、黄、绿、青、蓝、紫、粉；中性权重 ≥ .93 标成 NEAR_MONOCHROME。这些是图例筛选阈值，不是 LUT 是否黑白、适合拍人像／夜景的模型判定，也不是颜色管理或照片内容识别。

默认颜色家族至少占 .08 才匹配，query.minimumFamilyWeight 可在 0～1 中调整；权重仍须大于零。取色依赖照片、裁切、分析缩小及 Palette 量化，不能据单张图保证整个 LUT 色彩倾向。`origin` 明确区分 PHOTO_PREVIEW、USER_EXAMPLE、CREATOR_EXAMPLE；后者只能由取得真实创作者图片的数据源使用，当前卡片入口只返回 PHOTO_PREVIEW。

## 存储、缓存和边界

题材标签以 LUT 稳定 ID 独立原子保存到 `files/lut-gallery/<id>.json`，最大读取 32 KiB；全部库实例共用进程内写读锁。ID 限定现有内置／本地格式，不按文件名拼接任意路径；不写回 `.cube` 或原照片。读取异常作为 problems 返回，LUT 本身继续留在目录，不自动填入其他标签。

标签是持久用户数据，`clearProcessingCaches()` 不清理它们。卡片复用现有 8 MiB／两项效果预览缓存及预览串行门，正式成片仍按独立尺寸／质量处理。返回卡片 Bitmap 不计入后端 LRU 预算，前端只请求可见条目并控制持有数量；这不是整个应用内存上限。颜色结果仅包含轻量数字，不持有 Palette、Bitmap 或原照片。

官方目录、例图下载及 ZIP／VLT 兼容继续研究；当前没有官方网络数据源，不把本地取色能力称为 Lumix Lab 下载已完成。题材保存与筛选不需要 Room 迁移，也没有加入自动测试或打包步骤。
