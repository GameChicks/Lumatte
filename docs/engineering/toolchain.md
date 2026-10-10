# 固定工具链

初始源码配置：AGP 8.13.2、Kotlin / Compose 编译插件 2.2.20、Compose BOM 2025.10.00、JDK 17、Gradle 8.13、compileSdk 36、minSdk 30。

采用固定版本以便复现，不声明它们为当前最新版本。依据：

- [AGP 8.13 官方兼容表](https://developer.android.com/build/releases/agp-8-13-0-release-notes)：Gradle 8.13、JDK 17、支持 API 36。
- [Kotlin 2.2.20 发布文档](https://kotlinlang.org/docs/whatsnew2220.html)。
- [Compose 编译插件说明](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)：Kotlin 2.0+ 使用对应 Compose 编译插件。
- [Google Maven 中的 BOM 2025.10.00 POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2025.10.00/compose-bom-2025.10.00.pom) 已读取确认版本存在。

当前模块为 `app`、`core:model`、`core:design`、`core:media`、`core:camera`。影像模型、批量配方、主题、导入导出和相机传输分别隔离；研究资料不加入构建。

附加固定依赖：Activity Compose 1.11.0、Lifecycle Compose 2.9.4、ExifInterface 1.4.2、Palette KTX 1.0.0、Core KTX 1.17.0、DocumentFile 1.1.0、Coroutines Android 1.10.2。

尚未运行 Gradle、编译、测试或生成 APK。没有加入自动测试或打包任务，也未配置本机 SDK 路径、签名或发布身份。逐阶段状态见 `progress.md`。
