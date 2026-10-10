# RAW 内嵌 JPEG 读取

源码实施记录：2026-10-11。没有运行 CMake 配置、编译、Gradle、测试、性能基准或 APK 打包。原始输入和提供的 APK 均保持只读。

## 导入流程

1. 将输入流复制到本应用导入任务目录，不把整份 RAW 放入 Java 字节数组。
2. 若已经是 JPEG，沿用普通照片导入。
3. 非 JPEG 首先走现有 TIFF／RW2 标签快速路径，识别 Panasonic `0x002e`、JPEG offset／length 和 SubIFD。仅接受长度不超过 64 MiB 的 JPEG 候选；安卓读取真实尺寸，保留该路径内面积最大的可用 JPEG。
4. 快速路径无可用 JPEG 或布局读取失败时，调用 LibRaw 原生补充路径。
5. LibRaw 只用 `open_file` 和 JPEG 类型的 `unpack_thumb_ex`；读取前检查内部格式，拒绝位图、PPM、JPEG XL、H.265 等预览。最多八个候选，逐一写入私有临时文件，再由安卓读取实际 JPEG 尺寸并选择可用最大项。没有 JPEG 时提示改用相机 JPEG 成片，组件缺失、内存不足或数据损坏分别提示。
6. 继续既有 EXIF 与 SourceManifest 完成记录流程，删除任务的 RAW 暂存副本与多余预览。原始 RAW、相机卡及源文件提供方均不改写。来源记为 RawEmbeddedJpeg，编辑／导出页小字使用实际 JPEG 尺寸。

快速路径成功后不再打开 LibRaw，避免重复解析。这不证明快速路径的 JPEG 是某份 RAW 所有隐藏预览中的全局最大项。默认 JPG 尺寸 100% 以当前选中 JPEG 的有效像素为准，不能恢复 RAW 传感器分辨率。

## 原生边界

应用 JNI 接口仅有内嵌 JPEG 提取和任务取消检查。没有 sensor buffer 接口，不调用 `unpack()`、`raw2image()`、`dcraw_process()`、白平衡处理或去马赛克。原库为解析／链接所需保留的 RAW 解码源码不意味着应用调用它们。JPEG 像素不在 JNI 中解码、重压缩或转色；后续照片处理仍沿用统一 JPEG 链路。

每个候选独立创建 LibRaw 实例，避免损坏候选使后续实例状态失效。压缩预览限制 64 MiB，嵌入配置文件读取限制 16 MiB，禁止启用外部 SDK、OpenMP、可选 JPEG 解码库及额外去马赛克包。一个实例只持有一份压缩预览，但多个候选可暂占磁盘；这些限制不证明所有元数据解析的内存峰值或性能达标。

取消检查覆盖 JNI 阶段边界、LibRaw 实际提供的 progress 回调，以及 64 KB 写入块。单次元数据或预览读取仍可能延迟取消，不能据此宣称即时中止。失败由导入器清理当前私有任务；单张失败不阻止批量处理其他照片。

EXIF 仍采用预览中的方向优先、原 RAW EXIF 补充，缺失时使用方向 1。不把不同厂商特殊 preview flip 或缺失元数据的恢复宣称为已兼容，后续需真实松下文件证据。

## 版本与依据

- LibRaw 固定 [0.22.2 commit](https://github.com/LibRaw/LibRaw/tree/b93f6e45c194f5df9b02a43b1af9a54b4f41f33f)。官方 [C++ API](https://www.libraw.org/docs/API-CXX.html) 说明预览读取与 RAW unpack 是独立方法；本次同时读取固定版本 `src/decoders/unpack_thumb.cpp`，确认先筛 JPEG 才调用接口。
- 上游原文件、LICENSE、COPYRIGHT 与 SHA-256 清单保存到 `third_party/libraw/`；选择上游提供的 LGPL 2.1 许可选项。未复制、使用旧 APK 的 native 二进制。
- 固定 NDK `28.2.13676358`、CMake `3.22.1`；2026-10-11 从 [Google SDK 仓库清单](https://dl.google.com/android/repository/repository2-3.xml) 读取确认两个版本存在，没有下载安装 SDK 或工具链。
- [Android ExternalNativeBuild](https://developer.android.com/studio/projects/gradle-external-native-builds) 与 [NDK CMake](https://developer.android.com/ndk/guides/cmake) 是接线依据。构建声明限制 `arm64-v8a`／`x86_64`，符合原规划的 64 位设备范围。
- [Android 16 KB 页面说明](https://developer.android.com/guide/practices/page-sizes) 是 r28／ELF 对齐配置依据。JNI 目标包含 16 KiB 对齐链接参数、使用 NDK libc++ shared，app 声明非 legacy JNI 包装；没有 `.so`／APK 产物，因此尚无真实 ELF、ZIP 或运行证明。

仍待后续明确授权的验证：两个 ABI 编译与 JNI 名称、未压缩包装和 16 KiB 对齐、真实 RW2 的 JPEG／EXIF／方向、损坏或缺失预览、取消和内存行为。当前源代码不能证明支持每一款相机或每一种 RAW。
