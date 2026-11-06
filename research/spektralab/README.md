# 固定 SpektraLab 参考与 Lumatte 适配

这是原始输入和许可记录，不是开发日志。普通构建不联网、不运行参考项目或测试。

- [Android](https://github.com/SAINTTaiYi/SpektraLab-Android/tree/a2ffbc3763c5afc101479e2bfe74a8f555da38f1)：固定 `a2ffbc3763c5afc101479e2bfe74a8f555da38f1`；该 README 固定底层 JamesQiu2005/SpektraLab `445e314` 并应用 `engine-android.patch`。
- [Windows](https://github.com/YZH2006517/SpektraLab-Windows-NANATATA/tree/d667dcf7173f8c582060c43fe222da59f0f68a75)：固定 `d667dcf7173f8c582060c43fe222da59f0f68a75`。保留实际采用的原始引擎文件、字体、许可及 README。
- `android-engine/` 的 `halation_psf.hpp` / `params.hpp` 原始字节与 Windows 固定版一致；Android 补丁没有替换这两个文件的常量。没有运行参考项目或其测试。
- [原始字节 SHA-256 索引](source-index.json)。原始文本通过 `.gitattributes` 禁用换行转换；运行时只引入固定 PSF 头文件，其他参考源码不参与编译。

## 光晕

`native/mobile-film/spektralab_halation.hpp` 移植曝光阶段的核心：帧最大值高光门控（−1 至 −0.25 EV 距离上限，1.5 至 2.5 EV 高于中灰，4 EV headroom）、散射高斯核心与三项指数尾、120 µm 基底、四项带符号高斯 PSF、每层 0.30 / 0.03 / 0.003 反射、能量归一。FIR σ<3、reflect 边界、truncate=3；否则 Young/van Vliet IIR，保留水平/垂直执行顺序和 float 前向平面。CPU 使用 double 递推替代上游 GPU double-float 运算，不能据此声明逐字节相同。

CineStill 使用完整当前渲染网格，按通道复用平面而不保留四个卷积全图；空间场在写回照片之前计算完毕，支持取消。CPU / GLES 读取相同曝光场；超过 GPU 纹理能力时沿既有 GPU 失败恢复路径重解码并使用 CPU，不降低照片输出尺寸。高分辨率的内存、耗时和驱动行为尚未在手机上验收。

色彩响应、显影、接收体仍采用本项目已有 Fotufilm 表；颗粒和黑柔保持原方案。旧成片红色叠加已停用。固定参考只有 800T 的独立 no-antihalation 配置；400D 使用同一物理机制适配自己的色彩模型，不能称为上游 400D 配置复刻。完整引擎的曝光/色彩/扫描管线没有移植，故没有完整成片一致性声明。

### 1.0.1 计算与缓存适配

响应与帧最大值合并为一次并行读取，高光门控每像素只计算一次供 RGB 共用；FIR / IIR 按连续像素分块访问，复用一个输出平面。原 σ 分支、核、权重、边界、逐像素累加顺序、double 递推与 float 中间平面保持原方案，没有降低空间场分辨率或抽减核。

缓存仅保留一个不超过 384 MiB 的只读完整曝光场，以图像有效行字节、尺寸、响应表、黑柔、物理画幅和源曝光的进程内 128 位指纹索引；换输入时先释放旧缓存，失败或取消的场不发布。缓存由 CPU / GLES 共用；GPU 以场代次复用已上传纹理，空闲资源释放时清除 CPU 缓存。24 MP 场约 288 MB，这不是整条管线的峰值内存上限，仍需实机验收。

CineStill 的 EV 微调在胶片 / 颗粒后以线性光增益执行，不参与物理曝光场和高光门控。EV=0 绕过此增益，保留现有高光 / 过曝风格；其他胶卷曝光路径不变。曝光仍会改变成片及可见光晕亮度，不是冻结光晕像素。

原生日志 `LFMFilm` 增加 `cinestillSpatialMs`、`spatialCacheHit` 和 `fieldBytes`，只描述空间计算段，不代表解码到保存的总耗时。此轮没有运行基准、数值或图像比较，不能据源码编译推断骁龙 865 的加速倍数或成片逐像素一致。

## 片边与日期

`FrameStockEdge.kt` 使用 overscan.cpp 的 135 片边设计：35 mm 载体、24.3 mm 片窗短边、4.75 mm 齿孔节距、各家片号/文字位置、点阵和 DX 数据。`edge-faces.json` 是 glyph5x7、names/numbers/small 字形、filmEdge.ts 标签和 DX 对照的派生数据。Kodak、富士负片/正片、Ilford、Eastman 和 CineStill 使用不同布局；400D 使用 CineStill 布局、自己的卷名。富士半帧箭头及部分字体宽度是 Canvas 适配，未作扫描实物测量。上游明确标为 borrowed/unverified 的卷继续保持这一限制。DX 数据来源署名为 The Big Film Database（CC BY-SA 4.0）及上游所引用的 NegativeConverter；保留其来源说明。

与上游不同：本项目不强制裁剪到预设胶片机画幅，片窗沿长度随照片变化；不移植随机机身歪斜/化学曝光片边，使用后合成画框和表现用底色/墨色。源照片像素比例、导出像素数量和 RAW 内嵌 JPEG 契约保留。无来源 DX 的胶卷不生成伪造代码。

日期采用同源七段字符几何和 `'YY M D` 顺序，但作为后合成橙色发光印字。优先 EXIF 日期，缺失时取稳定的导入副本文件时间，关闭时不产生额外图层。整批开关随 FrameOptions 持久化；旧记录缺少字段时默认关闭，无边框也可使用。

## 许可和验证边界

代码为 GPL-3.0-or-later；保留原始 LICENSE、README 作者及致谢，不移除第三方许可。未导入新的参考色彩配置作为运行模型。字体采用原始 TeX Gyre Heros（GUST）与 DejaVu Sans Mono 字节，原许可与哈希俱在。运行资产附 NOTICE，应用许可页离线可查看。

目前仅确认源码编译。没有执行单元/回归/冒烟/设备/图像/性能测试，没有 APK 打包，也没有验证完整引擎输出一致性。

## Lumatte 1.0.1 表现层修正

135 的 Helvetica/TeX Gyre 字样改为自然宽度整行排字，按实际 H 字形边界确定大写高度，以 en space 保持可读词间距，取消仅对数字横向拉伸的适配。相邻 DX 模块合并为连续矩形。深色片基与墨色属于表现层选择，未改变源字体字节或原始参考。片号避免首张出现 0A。

新增独立 120 细边选择：56 mm 短边标尺、每侧 2 mm 片基，无 135 齿孔和 DX；沿照片长边自适应，不裁剪为固定画幅。提供卷名、片号和方向标，未虚构批次代码；属于参考样式适配，不声明复原某台相机或某一卷实物。
