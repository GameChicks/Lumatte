# CineStill 默认物理光晕移植 · 2026-10-17

后续 JPEG 观感修正见 [CineStill JPEG 光晕修正](cinestill-jpeg-halation-20261017.md)。本文保留 0.2.1 原始默认物理路径的实现及验证记录。

目标：完整移植 Fotufilm 800T / 400D 实际默认光晕模型，围绕原模型尝试手机性能优化，适度放宽两卷空间取样；其余胶卷不变。

## 模型与源码证据

固定上游 `12c8efea259e4cea70ec22166e504634d401a0cd`。`Pipeline.Options.halationModel` 默认 `.legacy`；实验 `.layered` 是另一个可选模型，不因 stock 中含 `layeredTransport` 自动启用。本次移植默认 `Halation.kernel(returned:base:profile:hazeMM:returnMatrix:)` 的带明确 profile 分支；两个指定 CineStill 均有该 profile，不使用旧的无 profile 反推分支，也不启用估计 profile。

原始文件及字节哈希存于 `research/fotufilm/halation-reference/`。上游源码 Apache-2.0、原始 NOTICE 保留于 `research/fotufilm/licenses/`；参数及派生核 CC BY-SA 4.0，署名 Fotufilm / MUAStudio Inc.。

| 原始模型行为 | 当前实现与证据 |
| --- | --- |
| 35mm acetate base 0.13mm，三色折射率 1.474 / 1.480 / 1.489 | `produce_fotufilm_halation.physical_kernel`，来自 `FilmFormat.still35` 和 `FilmBase.acetate` |
| 256 个角度中点积分、s/p Fresnel 反射和全反射 | 同函数逐色计算 rs/rp |
| 斜向往返吸收、角度分布、层深、五次上限及弱反射提前结束 | 同函数 optical depth、angular exponent、depth、retention 和 1e-3 尾部规则 |
| 独立 diffuse share / sigma、base haze | 同函数 edge spread 的 diffuse 混合及核方差合成 |
| 209 个边缘采样、14 个基础候选尺度、三尺度联合选择 | 同函数线性/对数距离与候选组合搜索 |
| 七个 simplex 面上的非负、和为一最小二乘 | `simplex_fit`，包含部分主元消去与退化阈值 |
| stock strength × look scale，返光份额 s/(1+s) | 不再使用 .12 / .065 人工值，资产使用上游两卷各自参数 |
| receiver-row / source-column 归一化返光矩阵，空行回退对角 | 离线生成 mix_matrix，加载器与 JNI 检查分配约束 |
| 每源感色层三尺度权重 | native `prepare_fotu_spatial` 的 `weights[source][scale]` |
| 三盒卷积近似、按尺度 decimation、方差补偿及有效边界归一化 | native `fotu_spread` / `fotu_sample`，适配上游 Cpu.h 与 C ABI helpers；不再套旧 24px Gaussian clamp |
| direct + M × (scattered − direct) | CPU 与 GLSL 两端都从全分辨率 direct 减 M×direct，浮点场只传 M×scattered；保持均匀场的基线 |

离线拟合使用 Float64，Android 读入 Float32；与上游公开 pack 的独立核数据进行了对照（见下），不声明与 Swift Float32 逐位一致。两卷原始空间形状相同，因此所得共同 sigma 为约 0.18812 / 0.28277 / 0.42505mm；强度、通道权重/矩阵仍来自各自配置。公开 pack 未公布其构建 commit，不能把 pack 的字节一致性说成指定 Swift revision 的直接执行结果；方程依据固定源码，核值另与公开 pack 交叉核对。

## 取样与性能

- 仅两个 CineStill 的空间场长边由 1024 提至 1536；普通胶卷仍走 512 和原 SpektraFilm 默认逻辑，普通卷色彩/颗粒/空间参数资产没有改变。
- 原始像素先求胶片曝光响应，再作面积平均；不开黑柔时使用预计算的 256 级字节坐标和三线性权重，消除该遍逐像素重复计算 LUT 坐标。没有 max pooling 或暗中增加高光倍数。
- 只开光晕时跳过未使用的镜头场分配和线性解码遍历；开黑柔时才准备该场。它减少无效工作，不改光晕数值；末轮定向数值验证覆盖此改动。
- 开黑柔时先准备原有镜头柔光场，再在每个原始像素应用柔光并求响应，之后才缩小返光输入。黑柔仍为现有独立模型，未声称移植 Fotufilm 全镜头系统。
- 折射积分和组合拟合在资源生产时执行，手机只加载小核数组；缓存由既有模型缓存持有。每尺度使用原始三盒/stride 规则，在较小网格做可分离卷积，再累积分色权重，避免对全尺寸照片进行九次大卷积。
- 全尺寸输出和共享浮点空间场沿用 CPU / GLES 后端；不分配完整分辨率 RGB float 曝光位图。保留四 worker 上限、取消检查、O3 与 IEEE 运算；不使用 fast-math。
- 原模型可按任意分辨率建立空间场。本项目明确保留 1536 空间预算及现有 33³ 点响应表，这是用户允许的手机取样约束；不能据此声称完整 Fotufilm 色彩/颗粒引擎逐像素一致或恢复 JPEG 已裁剪的场景高光。

## 用户授权的定向验证

用户在 2026-10-17 明确回复“允许光晕定向验证”。仅执行本次移植的数值、合成样图及性能检查，无整项目回归，无 APK。

独立预期值由 `tools/validate_fotufilm_halation.py` 产生：核直接读取原始 pack 的配置数组，不调用生产求解器；空间预期使用 SciPy 三线性采样、积分面积均值、独立 `np.convolve` 三盒核、可分离有效边界归一化与双线性回采样。被测端为 `tools/validate_fotufilm_halation_native.cpp` 调用实际生产 native / GLES 实现，不用另一个模拟实现代替被测端。

| 检查范围 | 末轮结果 |
| --- | --- |
| 两卷离线拟合权重 vs 上游 pack | 最大绝对差均 9.2147052e-7 |
| 返光矩阵 vs 上游 pack | 800T 2.2054474e-9；400D 1.7489721e-9 |
| 1600×900 默认核离散半径 | 两卷均为 6 / 10 / 15，与 pack 完全相同 |
| 每卷常量、阶跃、彩色灯带/单像素灯、2000×1333 缩小，共 8 组 | 全部有限；最大曝光绝对误差 1.4305115e-6（门限 2e-5） |
| 常量场不改变基线 | 两卷最大漂移 2.3841858e-7（门限 2e-6） |
| 每卷 513×343 与 2000×1333 CPU/GLES，共 4 组 | 最大差均为 1 个 8-bit 色阶；大图覆盖 1024 tile 边界和 1536 空间预算 |
| 800T 光晕 + 1/4 黑柔 CPU/GLES | 最大差 1 个 8-bit 色阶 |

1536 预算另与同一公式、2000 长边不缩小的独立空间计算对照：800T 最大/平均曝光绝对差 0.0045166 / 3.7600e-5，400D 为 0.0045638 / 3.8510e-5。它证实预算仍会改变细小高光的局部返光，不能声称完全无损；这是合成灯带与单像素输入的数值，不能换算成通用“画质损失百分比”。

合成开关图已实际查看：交付附件 `halation-validation/cinestill_800t-lights-off-on.png`（左关右开），400D 同名对应图。在这组 8-bit 合成灯带上，两卷开关最大只改变 **3 个色阶**，肉眼差异弱。模型/后端对照通过不等于用户期待的强红晕观感已经达成；没有擅自放大原始返光参数，也没有把图中已裁剪的白色重建成 HDR。若要继续追求参考实拍的强度，需要另行核对实际原片、曝光输入映射与完整色彩链路，不能仅凭本次小误差判定照片效果满意。

## 2400 万像素性能检查的边界

Windows 上 Android x86_64 API 35 模拟器，主机 GLES 后端 NVIDIA GTX 1650；NDK 28.2、O3、关闭浮点收缩，4 worker 上限。6000×4000 合成灯带，关闭颗粒/黑柔。计时从模型与输入加载后开始，包含原生渲染及原始 RGBA 写盘；不含 JPEG 解码/编码、EXIF、画框、Android 导出任务或 UI。单次检查包含冷 GLES 初始化，不能当成统计基准或骁龙 865 性能结论。

| 模式 | 耗时 | native 进程峰值 RSS |
| --- | --- | --- |
| 只建立 1536 光晕场 | 557.292ms | 157.20MiB |
| CPU，光晕关 / 开 | 1192.52 / 1378.78ms | 103.09 / 157.30MiB |
| GLES，光晕关 / 开 | 2247.60 / 2603.09ms | 115.11 / 164.96MiB |

这些结果说明实现可以执行全尺寸流程，不能保证真实手机连同画框/JPEG 在 15 秒内；主机 GPU 模拟器的 CPU/GLES 耗时也不适合用来选择手机后端。移除无用镜头场之前同环境 CPU 开光晕为 1696.38ms，之后 1378.78ms，但场准备检查本身从 448.739ms 波动到 557.292ms；样本/并发负载不足以证明稳定加速百分比，因此只保留原始观察，不宣传速度提升比例。

软件 SwiftShader 后端曾出现无属性 `gl_VertexID` 三角形未绘制，改成等坐标的显式 VBO 后能够绘制，但色彩采样仍异常；该模拟器随后退出。主机 GPU 下全链路对照通过，未把软件后端失败覆盖成“所有驱动通过”。生产代码保留等价显式顶点，未加入调试用硬编码 LUT 尺寸或绕过数值断言。

资源生产完成，两款 manifest / 索引更新；三张色彩表字节、其他卷 manifest 未修改。末轮 GLSL 顶点/片元语法编译、arm64-v8a / x86_64 native、media / app Kotlin 编译通过（交付附件 `halation-source-compile.log`，BUILD SUCCESSFUL，45s）。没有物理 Android/骁龙 865 或相机验证。移植验证之后用户另行授权打包，已交付 [0.2.1](../releases/0.2.1.md)。

`results.json`、`performance-results.json`、PNG 和编译日志已随 0.2.1 保存到 `dist/release-0.2.1-20261017-064901/`；可再生 fixtures 和旧缓存已移出项目。手动复现前须取得当次验证授权，工具不会自动启动测试或打包：先用 NDK x86_64 clang++ 编译 validator 与生产 `mobile_film_gles.cpp`（C++20、O3、`-ffp-contract=off`、静态 libc++、EGL/GLESv3），生成 `mobile_shaders.hpp` 使用生产模板和两份生产 shader；在 D: 的 `LumixMirrorVisual` AVD（端口 5582，host GPU）启动后运行验证脚本，另以 `--performance-only` 执行上述单次大图检查。ADB 从 `ANDROID_HOME` / `PATH` 或本机忽略配置查找，不依赖项目内 SDK 副本。
