# 移动胶片模型数据与色彩阶段（2026-10-11）

> 2026-10-16：新增独立 Fotufilm 来源的六款移动模型，缓存预算调整为最多两款／12 MiB；旧 Silver 保留渲染兼容但移出新选型目录。见 新增模型与光晕分流。以下是初版资源生产记录。

已实际生成 20 个 spektrafilm 彩色胶卷数据包；Android 读取器、缓存和 CPU 色彩阶段已写入源码。随后 GLES／CPU 图像后端、空间场和密度颗粒接回默认预览／导出，详见 [图像后端](mobile-film-rendering.md)。本轮另生产并接入两项独立的 自有黑白风格，不混入此来源版本。本项目只执行过生产资源生成，没有运行图像对照、Android 读取、编译、测试或基准，不表示新后端已运行通过。

## 资源生产与来源

运行本项目 `tools/bake_mobile_film_models.py`，使用用户在 D 盘已部署的 Python 依赖。它从 `D:/spektrafilm` 的固定提交 `3bb2c2d2801ff68b92019cf1dbcbb133d60832bc` 导出 `src/spektrafilm` 和许可到本项目忽略的 `tools/.cache/mobile-film-bake/`，只导入这一份核心源码。用户仓库的中文版 GUI 修改、启动脚本、运行状态和个人设置没有导入或修改。Python／Matplotlib／Numba／临时缓存路径显式放项目 D 盘目录。

没有执行参考文档中的命令、上游生成器或测试；本项目生产脚本调用公共 `SimulationPipeline.process(inject/collect)` taps。每卷设置明确的 sRGB 输入／输出、0 EV、相纸倍率 1、关闭自动曝光和印相曝光补偿；先应用胶卷默认参数，再启用 `lut_mode` 关闭空间／随机／图像全局变换。使用原生光谱计算生产印相／扫描表，不用已有 SpektraLab 表替代这一版本的结果。

资产在 `core/media/src/main/assets/film/mobile-v1/`；index 和每卷 manifest 含版本、胶卷／相纸 ID、正负片标识、来源及 SHA-256。Andrea Volpato 数据的 CC BY-SA 4.0 原文保存在该目录 `SPEKTRAFILM_LICENSE.txt`，修改记录在 `CHANGELOG.txt`。这是运行时模型资产，不是 APK 交付文件；未来 APK 仍集中到 `dist/`。生产脚本不加入 Gradle、提交钩子或自动测试。

## v1 阶段协议

index／manifest 固定 UTF-8 和 LF 换行，哈希计算在该字节表示上；`.f32` 标为 Git 二进制，许可原件禁止换行归一化。这避免 Windows 的文本换行转换使模型说明哈希随检出平台变化。

| 阶段 | 输入 → 输出 | 资源规格 |
|---|---|---|
| response | 编码 sRGB → 线性胶卷响应；包含参考黑／白曝光校正 | 33³ × 3 × float32，431,244 字节 |
| development | log10 胶卷曝光 → CMY 密度，包含逐像素 DIR 化学耦合 | 33³ × 3 × float32，431,244 字节 |
| scan | CMY 密度 → 固定相纸印相／扫描，或反转片直接扫描的编码 sRGB | 41³ × 3 × float32，827,052 字节 |

每卷合计 1,689,540 字节（约 1.61 MiB），20 卷数值表合计 33,790,800 字节（约 32.23 MiB），另有目录、配置与许可。它不是 APK 压缩后体积或处理峰值内存。

文件无二进制头；维度和颜色域由 manifest 定义。float32 小端，三个通道交错，第一个坐标最快：`(x + N * (y + N * z)) * 3 + channel`。每个通道有自己的 minimum／maximum；v1 的采样轴为线性，CPU 手动三线性插值，超域夹到端点。显影 log 域来自胶卷轴／gamma 并两端扩展 0.5；扫描密度域预留低端 −0.2 和高端 0.2。域、33³／41³ 分辨率和端点夹取尚未通过误差对照，不声称所有曝光／饱和颜色都已准确。

v1 的 `dir=baked_local_only`：显影曲线和非空间 DIR 耦合在三维显影表中一起计算，运行时不得再次执行 DIR。**空间 DIR 扩散省略**，因此不是完整桌面算法的逐像素复现；若以后加入空间 DIR，需要新阶段协议和重新生成资源，不能直接叠到此表上。Halation、黑柔、随机颗粒和全图高亮 boost 没有烘入；manifest 同时保存烘入配置快照和未关闭的原始 grain／Halation 参考参数，参考参数不是经过标定的移动参数。

反转片独立生成 `scan_film=true` 的直扫表，最终 RGB 不再做一次负片反相。UI 的揭示动画继续只属于前端。

## Android 数据层与 CPU 色彩核

`MobileFilmModelLibrary` 读取轻量 index，按所选胶卷检查相纸／正负片对应，再读取 manifest 与三份数值表。限定 JSON 大小、表边长和有限数值；按 16 KiB 块读取、检查取消并计算哈希，没有同时建立整份 byte[] 再复制为 float[]。来源提交、版本、颜色空间、布局和烘入阶段必须符合当前协议，缺失或损坏不改用其他胶卷。

模型表缓存最多两卷／4 MiB，超过单项预算的模型只借给当前任务，不保留。预算指缓存持有的表，未包括载入中的表、当前任务引用、元数据、位图或 GPU；清理移除缓存引用，不破坏正在使用的不可变模型。

`MobileFilmColourKernel` 有分开的 response／develop／scan 入口，方便在响应后插入 Halation、在密度后插入颗粒。曝光仅以 `2^EV` 乘在线性胶卷响应上一次，再取 `log10(max(raw,0)+1e-10)`；不同时乘到相纸阶段。`colourOnly` 仅供内部整合色彩链路，不能当作含完整空间效果／颗粒的最终胶片模拟。每个任务独立持有少量 scratch 数组，不按像素新建数组。

`PhotoProcessor.mobileFilmModelsInfo()` 向前端提供已列出的数据、来源和缓存数量／字节；读取 index 不加载全部模型、不初始化 GPU。它不是活动后端状态或实测性能。`clearProcessingCaches()` 同时清空此缓存；前端不增加技术引擎选择。

## 仍需继续

GLES／CPU 扫描、取消排空、有界 Halation／三档黑柔和近似密度颗粒已接回 PhotoProcessor 与默认 100% 导出；两项自有黑白模型也已生产并接入源码。继续核对短半径细节及模型近似，再按需要进行用户允许的实际功能验证；数值误差、视觉效果、耗时和峰值内存没有证据，不能据资源生成或源码接入成功宣称手机性能达标。
