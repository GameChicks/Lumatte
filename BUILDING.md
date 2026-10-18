# 构建 Lumatte

## 环境

- [JDK 17](https://adoptium.net/temurin/releases/?version=17)
- [Gradle 8.13](https://gradle.org/releases/)：自行安装，将 `bin` 加入 PATH；仓库不附带 Wrapper。
- [Android SDK](https://developer.android.com/studio)：Platform 36、Build Tools 35.0.0、Platform Tools。
- Android NDK 28.2.13676358、CMake 3.22.1，通过 SDK Manager 安装。

设置 `JAVA_HOME`、`ANDROID_HOME`；首次构建需联网解析依赖。仓库不附带工具安装包、签名密钥、机器缓存或本地开发记录。

## Windows

```powershell
$env:JAVA_HOME = 'D:\Android\jdk-17'
$env:ANDROID_HOME = 'D:\Android\Sdk'
$env:PATH = 'D:\Android\gradle-8.13\bin;' + $env:PATH
./tools/build_android_prototype.ps1
```

示例路径替换为实际安装位置。脚本手动执行 `:app:assembleDebug`，将 APK 和日志放入本地 `dist/`，不运行测试或上传文件。`-CompileOnly` 只编译原生库和 Kotlin。

Windows 的 SDK、JDK、Gradle、AVD 和可写缓存放在 D: 且位于源码树之外。本机可用忽略的 `tools/.local/android-paths.json` 配置 `javaHome`、`sdk`、`gradle`、`javaUserHome`、`androidUserHome`、`gradleUserHome`、`avdHome`，不向仓库提交路径。

## macOS / Linux

```sh
gradle :app:assembleDebug
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/Lumatte-debug.apk
```

Android Studio 可直接打开根目录 Gradle 工程。原生 ABI 为 arm64-v8a 和 x86_64。应用 ID 保持 `com.lumixfilmmirror.app`；RAW 仅提取内嵌 JPEG。

## 源码、生成文件与素材

`app/`、`core/`、`native/` 是应用源码；`third_party/` 保留固定第三方源码、许可与素材来源哈希，`research/fotufilm/` 是模型生产必需的固定原始输入及许可。运行模型随源码提供，普通构建不执行模型生产或字体下载。

交付物 `dist/`、本地开发文档 `docs/`、Gradle Wrapper 和 Room 生成的 `schemas/` 不纳入 Git。Room schema 仍在编译时按原配置输出到本地目录；数据库版本、显式迁移及用户存储契约不变。

模型生产工具为 `tools/bake_mobile_film_models.py`、`produce_fotufilm_models.py`、`produce_fotufilm_halation.py`、`produce_monochrome_models.py`。字体来源与输入哈希见 [font-sources.json](third_party/provenance/font-sources.json)，手动工具 `tools/prepare_ui_fonts.py` 需要 Python fontTools；它不挂接构建。

完整许可及原始文本链接见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。版本变动和 APK 校验信息发布在 [GitHub Releases](https://github.com/GameChicks/Lumatte/releases)。调试构建使用 Android debug 证书，不代表应用商店签名版本。

## 手动验证

仓库没有自动测试、打包或发布钩子。只有维护者明确要求时才运行测试，例如：

```sh
gradle :core:model:test :core:camera:testDebugUnitTest :core:media:testDebugUnitTest :core:storage:testDebugUnitTest
```

源码检查、编译、APK 签名与对齐核验分别记录，均不能代替真实手机、相机、图像和性能验证。
