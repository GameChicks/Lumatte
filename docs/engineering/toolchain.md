# 固定工具链

初始源码配置：AGP 8.13.2、Kotlin / Compose 编译插件 2.2.20、Compose BOM 2025.10.00、JDK 17、Gradle 8.13、compileSdk 36、minSdk 30。

采用固定版本以便复现，不声明它们为当前最新版本。依据：

- [AGP 8.13 官方兼容表](https://developer.android.com/build/releases/agp-8-13-0-release-notes)：Gradle 8.13、JDK 17、支持 API 36。
- [Kotlin 2.2.20 发布文档](https://kotlinlang.org/docs/whatsnew2220.html)。
- [Compose 编译插件说明](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)：Kotlin 2.0+ 使用对应 Compose 编译插件。
- [Google Maven 中的 BOM 2025.10.00 POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2025.10.00/compose-bom-2025.10.00.pom) 已读取确认版本存在。

当前模块为 `app`、`core:model`、`core:design`、`core:media`、`core:camera`、`core:storage`、`core:transfer`。影像模型、批量配方、主题、导入导出、相机协议、持久存储和队列执行分别隔离；研究资料不加入构建。

附加固定依赖：Activity Compose 1.11.0、Lifecycle Compose 2.9.4、ExifInterface 1.4.2、Palette KTX 1.0.0、Core KTX 1.17.0、DocumentFile 1.1.0、Coroutines Android 1.10.2。

相机 IPv6 传输增加固定 OkHttp 4.12.0（Apache-2.0）。已读取 Maven 版本元数据与官方地址／路由／解析器接口源码；没有运行 Gradle 依赖解析或编译。路由及接口作用域记录见 [IPv6 路径](camera-ipv6.md)，发布时需纳入依赖许可告知。

原生 RAW 预览补充声明 NDK `28.2.13676358`、CMake `3.22.1`、C++20、`arm64-v8a`／`x86_64`，通过 `core:media` 的 ExternalNativeBuild 接入 `native/raw-preview`。LibRaw 0.22.2 原源码与许可固定在 `third_party/libraw`。版本存在性已读取 Google SDK 仓库清单确认；没有下载、安装或运行本机编译工具。接口和 16 KiB 配置证据范围见 [RAW 预览记录](raw-preview.md)。

持久化配置使用 [Room 2.8.5](https://developer.android.com/jetpack/androidx/releases/room)、[DataStore Preferences 1.1.7](https://developer.android.com/jetpack/androidx/releases/datastore) 和 [KSP 2.2.20-2.0.4](https://github.com/google/ksp/releases/tag/2.2.20-2.0.4)。已核对这些固定版本的发布记录，不声明组合已编译通过。Room Gradle 插件与 runtime/compiler 版本一致，配置 schemaDirectory；schema 随首次获准编译生成后纳入 Git。

2026-10-12 首次运行 Gradle 编译并生成调试原型 APK；工具与新生成的调试签名隔离在 D 盘，交付包及记录在 `dist/`。没有运行测试、模拟器或真实设备验证，没有添加自动测试或打包钩子。实际工具版本、编译修正及证据边界见 [首个原型记录](prototype-package.md)；前面的“未编译”描述保留为此前接入阶段的历史状态。
