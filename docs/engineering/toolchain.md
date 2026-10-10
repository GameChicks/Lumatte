# 固定工具链

初始源码配置：AGP 8.13.2、Kotlin / Compose 编译插件 2.2.20、Compose BOM 2025.10.00、JDK 17、Gradle 8.13、compileSdk 36、minSdk 30。

采用固定版本以便复现，不声明它们为当前最新版本。依据：

- [AGP 8.13 官方兼容表](https://developer.android.com/build/releases/agp-8-13-0-release-notes)：Gradle 8.13、JDK 17、支持 API 36。
- [Kotlin 2.2.20 发布文档](https://kotlinlang.org/docs/whatsnew2220.html)。
- [Compose 编译插件说明](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)：Kotlin 2.0+ 使用对应 Compose 编译插件。
- [Google Maven 中的 BOM 2025.10.00 POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2025.10.00/compose-bom-2025.10.00.pom) 已读取确认版本存在。

目前只有领域模块和设计模块；应用、影像管线和相机传输模块随后加入。尚未运行 Gradle、编译、测试或生成 APK。没有加入自动测试或打包任务，也未配置本机 SDK 路径、签名或发布身份。
