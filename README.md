# 松映 · Lumatte

松映 · Lumatte 是一款开源 Android 摄影工具，把松下相机照片传输、移动胶片风格、LUT 试套和画框导出放进同一条本地工作流。最低支持 Android 11。本项目由社区独立开发，与松下没有隶属关系。

[下载 Lumatte 1.0.0](https://github.com/GameChicks/-Lumatte/releases/tag/v1.0.0) · [查看版本记录](docs/releases/CHANGELOG.md) · [构建说明](BUILDING.md) · [第三方许可](THIRD_PARTY_NOTICES.md)

## 主要功能

- **相机传输：** BLE 配对与 Wi-Fi 连接、相机媒体浏览、多选传输和连接日志。相机功能范围不包含实时取景、遥控拍摄或曝光、白平衡、对焦等参数控制。
- **移动胶片：** 使用三阶段移动模型，支持 GLES 3 和原生 CPU 后备。CineStill 800T、400D 使用 Fotufilm 物理光晕路径；其他胶卷保留各自默认路径。
- **LUT 试套：** 支持导入 Cube / VLT，提供双 LUT 混合以及独立强度、颗粒、导出质量和尺寸设置。
- **风格发现：** 可试套本地 LUT，也可通过创作者示例图发现尚未下载的风格。示例图匹配只用于推荐，选用风格时才下载 LUT；照片保留在本机。
- **照片导出：** RAW 文件只提取相机内嵌 JPEG 预览，不解码或显影传感器数据。默认导出尺寸为有效输入的 100%；批量照片可逐张调整或继承首张设置，整批共用画框设置。
- **EXIF 与画框：** 可读取照片 EXIF 机型信息用于 Lumix 水印，并在胶片处理后添加导出画框。

胶片观感、相机兼容性和处理耗时会受到照片、相机与设备影响；请以实际设备体验为准。

## 下载

当前版本为 **1.0.0**（versionCode `16`），应用 ID 为 `com.lumixfilmmirror.app`，最低 Android 版本为 Android 11（API 30）。

- [GitHub Release：Lumatte 1.0.0](https://github.com/GameChicks/-Lumatte/releases/tag/v1.0.0)
- [直接下载 APK](https://github.com/GameChicks/-Lumatte/releases/download/v1.0.0/lumix-film-mirror-prototype-1.0.0-20261018-101812-debug.apk)

## 从源码构建

环境要求：JDK 17、Android SDK 36、NDK `28.2.13676358`、CMake `3.22.1`。工程使用仓库内的 Gradle 8.13 Wrapper。

Windows PowerShell：

```powershell
.\tools\build_android_prototype.ps1
```

构建脚本会生成 Android APK，并将交付包复制到 `dist/`。项目不依赖本机参考 APK、恢复会话、旧工程、SDK 副本或其他私有文件。

## 项目结构

| 路径 | 内容 |
| --- | --- |
| `app/` | Android 应用入口、Compose 界面、小组件和资源 |
| `core/` | model、design、media、camera、storage、transfer 模块 |
| `native/` | 移动胶片、双 LUT、RAW JPEG 预览 JNI |
| `third_party/libraw/` | 固定版本的 LibRaw 源码、清单及许可 |
| `research/fotufilm/` | 胶片模型生产所需的固定输入、原始方程和来源哈希 |
| `tools/` | 手动构建、模型生产及定向验证工具 |
| `docs/` | 产品规则、技术说明、协议和发布记录 |
| `gradle/` | Gradle Wrapper |
| `dist/` | 本机生成的交付包和附件；生成文件不提交 Git |

## 许可与致谢

应用代码采用 [GNU GPL v3.0](LICENSE)。第三方代码、模型、字体与图片各自遵循其适用许可和署名要求，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。GPLv3 不会取代第三方素材的单独许可。

正文使用 OPPO Sans 4.0，欢迎标题使用 Bebas Neue，点阵使用金字社统圆体 OFL 字符子集。字体包协议、署名和来源哈希随项目保存。栗壳坚坚体、10 款用户提供的 LUT 以及恢复画框图片已获维护者授权，随项目与 APK 公开分发；各素材仍保留各自来源与权利说明。
