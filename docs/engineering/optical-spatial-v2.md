# 移动空间效果 v2：红暖返光与吸收式黑柔

> 当前默认已调整为 `lfm-optical-spatial-v2.1-stock-defaults`：普通胶卷采用原通道比例和单位返光增益；本文的全负片默认红暖方案与固定 2 倍增益是 v2 历史行为。显式后端艺术选项保留。原因与修改见 [Gold / Ultramax 默认参数核对](film-stock-defaults-audit.md)。未因此修改基础色彩表或打包。

2026-10-14。算法标识 `lfm-optical-spatial-v2`。修改前 Git 基线为 `d9d955cacbebba6fa3fdb5cc7745390a3ff59437`，回退标签 `lfm-before-halation-v2-20261014`。这次修改默认运行源码，未生成新 APK。

## 用户参考与观感目标

用户提供的开关对比图中，火焰白色核心保留，外围增加明显红橙色扩散；标注为真实 CineStill 800T 夜景的第二张图中，灯点和亮线边缘有红暖色晕，其他区域保留冷色。目标是光源周围的返光，不是将整张照片染红，也不把第二张图的长条光轨、重叠影像和雾感全部归因于 Halation。

原图保留在 Downloads，逐字节副本及来源索引存于本机 `reference/halation/`，仅作为视觉参考，没有装入应用资源或生成图像测试输出。第二张的胶片型号来自用户说明，未独立认证。

| 保存的副本 | SHA-256 |
| --- | --- |
| `halation-toggle-candle.jpg` | `656ce9df88067b6774e9c98934562d4738439aef1c1d9d1c6dbb48af5ded3d4e` |
| `cinestill-800t-night.jpg` | `7907595284c1fd4ec66a58f0debb5797d4eaacd1961ee2ed80d8d7eb4907fd9c` |

## Halation

运行链路仍使用已有胶片响应、显影、扫描三阶段表。空间返光在胶片线性响应域处理，在曝光和显影之前合成，CPU/GLES 使用同一空间场与公式。

- 移除旧版 `max(0.06, peak*0.55)` 亮部阈值，避免低曝光灯点被绝对门槛排除。新路径散射连续响应场；没有灯光的图像仍可能发生细微局部对比变化，不承诺只影响高光。
- 移除 RGB 独立最低强度，保留零返光通道。负片/电影卷默认艺术外观使用 `R=原模型红记录返光，G=R*0.04，B=0`；黑白与反转片继续使用原模型比例。这是项目自定的红暖外观，不是导入 Fotufilm 的 800T 标定数据。
- 全局创作增益为 2。每记录返光比 `s=2*return`，混合份额 `a=s/(1+s)`。关闭时不产生返光场，也不衰减直接项。
- 三个中心高斯尺度为原模型半径乘 `0.65/1.3/2.6`，权重为 `0.25/0.50/0.25`。原半径由微米与幅面换算到分析网格，采样下限为 0.35 像素，滤波 sigma 上限仍为 24，核截断为 3 sigma，边缘延拓。
- 合成 `response_out=(1-a)*response_direct+a*三尺度散射(response)`。正空间场和直接项衰减共同实现局部再分配，取代旧版“宽场减核心后只保留正值”的空心光晕叠加。理想均匀场不会因开启而全局增亮；浮点、低分辨率响应近似和边缘采样仍会有误差。

白色核心的实际保留程度与红晕观感尚未渲染确认。三尺度权重是项目近似，没有复刻上游跨记录返光矩阵、分层传播、波长相关材料或拟合空间 profile。现有目录没有因此新增或重命名为准确复刻的 CineStill 800T。

## 黑柔

保留关闭、1/8、1/4、1/2，曝光仍只有一个控件。黑柔在输入线性光域、胶片响应之前计算，和 Halation 独立启停。

设档位强度 `g=0.125/0.25/0.5`，截获份额 `p=0.45*g`；直接份额 `1-p`，散射份额 `0.55*p`，吸收份额 `0.45*p`。三尺度 sigma 为 `0.75/3.5/10`，散射权重 `0.20/0.35/0.45`。档位改变参与散射与吸收的份额，半径不随档位扩大。该参数是便于手机运行的创作近似，没有实体品牌滤镜测量依据；不能称为已标定的黑柔滤镜。

## 手机开销与精度取舍

空间分析长边最多 512，JPEG 内容输出仍为有效输入的 100%，尺寸百分比与编码质量保持独立。RAW 仍只抽取内嵌 JPEG。

旧路径需要全图峰值扫描，并对参与光晕的源像素查胶片响应表。新路径用一次全图线性面积平均，同时提供黑柔与返光输入；胶片响应分析只在紧凑网格上查表。宽散射单独采用 2/4/8 倍网格缩小，窄尺度保留细网格；按实际网格比例修正 sigma，再双线性重建。

面积平均保留线性输入光的贡献，但 `response(mean(input))` 一般不等于 `mean(response(input))`。这是有意的性能取舍，可能影响微小彩色灯点的形态与返光能量；512 网格也限制近场细节。不是桌面引擎逐像素等价替代，更不是缩小最终输出尺寸。

本轮还修复 GLES 着色器中的保留字变量 `shared`，改为 `sharedNoise`。离线着色器编译器此前报 `unexpected SHARED`，修改后编译通过。现有 GPU 失败会转入 CPU 后备，因此旧问题可能导致慢导出；没有用户设备日志，不能认定它就是此前 35 秒的根因。

## 留给前端的接口

`FilmRenderPlan.spatialOptions`、`FilmCatalogue.plan(...)` 和 `PhotoProcessor.filmPlan(...)` 支持可选 `FilmSpatialOptions`。默认 `RED_LAYER_RETURN`；开发方可显式传 `STOCK_RETURN` 保留原通道比例。该选项当前属于运行计划，未新增数据库配方字段，也未增加用户控件。

`PhotoProcessor.filmSpatialEffectsInfo()` 返回版本、模型名称、分析尺寸、近似标定说明。导出诊断 `ExportPerformanceReport.spatialAlgorithmVersion` 和 `LFMExport` 日志记录算法标识；标识只说明实现版本，不能证明实际启用该效果或 GPU 可用。

批量继承和单张独立参数逻辑不变，画框仍由整批共享导出预设决定。后续前端可以保留现有 Halation 开关和三档黑柔，不需要暴露专业模型参数。

## 来源与证据

参考 [Fotufilm Halation](https://github.com/DhaliwalX/fotufilm-engine/blob/065590107a34875728adbfd61fc1f85316d7c353/Sources/FotufilmCore/Halation.swift)、[Halide 空间阶段](https://github.com/DhaliwalX/fotufilm-engine/blob/065590107a34875728adbfd61fc1f85316d7c353/Sources/FotufilmHalide/Stages/Halation.h)、[网格几何](https://github.com/DhaliwalX/fotufilm-engine/blob/065590107a34875728adbfd61fc1f85316d7c353/Sources/FotufilmHalide/FotufilmHalideGeometry.h) 与 [DiffusionFilter](https://github.com/DhaliwalX/fotufilm-engine/blob/065590107a34875728adbfd61fc1f85316d7c353/Sources/FotufilmCore/DiffusionFilter.swift) 的散射结构；当前是本项目 C++/GLES 近似实现，没有装入上游 stock JSON 或移植 Swift/Halide 引擎。上游来源与许可差异见 [研究记录](fotufilm-800t-halation-target.md)。

已经完成的源码编译：NDK `arm64-v8a` 与 `x86_64` 的 `lfm_mobile_film`、GLES fragment 离线编译、`:core:media:compileDebugKotlin` 和 `:app:compileDebugKotlin`。源码差异格式检查通过。

未执行单元/回归/冒烟、照片渲染、设备或基准测试，未打包 APK/AAB。骁龙 865、2400 万像素、Halation 开、黑柔关、带画框完整导出小于 15 秒仍是待验证目标；视觉是否达到两张参考图也待用户授权样片验证。
