# 手动构建

## 工具

- JDK 17。
- Android SDK Platform 36、Build Tools 35.0.0、Platform Tools。
- Android NDK 28.2.13676358、CMake 3.22.1。
- 仓库内 Gradle Wrapper 固定为 8.13，并校验官方发行 ZIP 的 SHA-256。

通过 Android Studio 的 SDK Manager 或 Android 官方命令行工具安装 SDK。设置 `JAVA_HOME`、`ANDROID_HOME`，或使用本机忽略的 `local.properties` 指定 SDK。Gradle/SDK 首次构建可能需要联网下载依赖；仓库不附带工具安装包、SDK、签名密钥或机器缓存。

## Windows

从项目根目录运行：

```powershell
$env:JAVA_HOME = 'D:\Android\jdk-17'
$env:ANDROID_HOME = 'D:\Android\Sdk'
./tools/build_android_prototype.ps1
```

示例路径须替换为实际安装位置。此脚本默认将 Java/Gradle/Android/临时目录安排在 `D:\AndroidBuild\Pineframe`，生成的交付 APK 和日志复制到 `dist/`。`-CompileOnly` 仅编译原生库和 Kotlin，不生成交付 APK，也不运行测试。

本机可选 `tools/.local/android-paths.json` 支持 `javaHome`、`sdk`、`gradle`、`javaUserHome`、`androidUserHome`、`gradleUserHome`、`avdHome` 字段，所有路径指向项目外的已安装工具；此配置被 Git 忽略，不是其他开发者的构建前提。

## macOS / Linux

设置相应 JDK / SDK 环境后，可直接使用 Wrapper：

```sh
./gradlew :app:assembleDebug
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/pineframe-debug.apk
```

Android Studio 可直接打开根目录的 Gradle 工程。两种原生 ABI 为 arm64-v8a 与 x86_64；RAW 功能只使用 JPEG 预览提取接口。

## 模型与验证

已生产的运行表随源码保存，普通构建不运行 Python、模型烘焙或验证。手动模型生产工具为 `tools/bake_mobile_film_models.py`（需要固定 spektrafilm Git checkout）、`produce_fotufilm_models.py`、`produce_fotufilm_halation.py` 和 `produce_monochrome_models.py`；Fotufilm 输入和许可保存在 `research/fotufilm/`。

光晕验证为独立的 Python / Android native 工具，需 NumPy、SciPy、Pillow、NDK 及指定模拟器，详见[光晕记录](docs/engineering/fotufilm-physical-halation-20261017.md)。它不挂接 Gradle，不自动运行；没有把单次模拟器检查当成物理手机或相机兼容证明。

## 首次公开源码

```sh
git archive --format=zip --output=dist/pineframe-source.zip HEAD
```

该快照只包含当前已跟踪文件，不含本地 `.git` 历史和被忽略的交付物、工具、私有参考资料。字体/LUT/恢复素材的授权待确认事项见 [第三方清单](THIRD_PARTY_NOTICES.md)，应在公开分发前补齐。不要把本机签名密钥提交到仓库；调试包不是正式发行签名。
