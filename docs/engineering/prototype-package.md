# 首个原型 APK

2026-10-12 用户明确首个 APK 生成后即可结束目标。本轮完成手动 `:app:assembleDebug`，不再继续扩展功能或制作后续包。

交付：`dist/lumix-film-mirror-prototype-0.1.0-20261012-004750-debug.apk`，134,706,580 字节，版本 `0.1.0`／versionCode `1`，包名 `com.lumixfilmmirror.app`。最低 Android 11（API 30），target／compile API 36，包含 ARM64、x86_64 原生库。

SHA-256：`e6a2ddf52c97a84d942aafd1d05b7e4f87e674f9ed99a5c95cfa9e15906bf75a`。

实际证据：Gradle 显示 `BUILD SUCCESSFUL in 1m 4s`，145 个任务，12 执行、133 已有最新输出。应用、模型、媒体、存储、传输、相机及设计模块通过编译；原生 RAW 内嵌 JPEG、LUT、移动胶片模块完成两种 ABI 的构建。`aapt dump badging` 读取上述包信息，`apksigner verify --verbose` 成功（v2 签名），`zipalign -c -P 16 -v 4` 成功。后者仅确认包的 ZIP 对齐，不是设备安装或整个原生运行环境的兼容证明。

这是使用独立 D 盘工具目录中新生成调试签名的原型，不是正式发行包。没有读取或复用旧项目的签名身份。没有运行单元、回归、冒烟、性能、设备测试，没有启动模拟器或真实相机验证，也没有在 Android 上确认胶片效果、RAW 提取、官方 LUT 服务返回和速度。GLES 着色器仍需在实际设备上由驱动编译；APK 生成不等于其运行已确认。

工具：Temurin JDK 17.0.20.1+1、Gradle 8.13、Android CLI 19、API 36、NDK 28.2.13676358、CMake 3.22.1，均在 `tools/.cache/android-prototype/`。AGP 实际选择并安装 Build Tools 35.0.0（预备目录也有 36.0.0）；全部 SDK、Gradle、临时目录及 Android／Java 用户目录放 D 盘。下载归档按官方摘要核对；SDK 安装器的 NDK 下载提前中断后，使用官方归档恢复并保留官方包版本及许可引用。

构建修正包括独立 Android 用户目录的环境变量冲突、Cube LUT 输入范围 lambda 变量遮蔽、现有界面代码的两处缺失导入及一次 nullable getter 类型检查。相机模块出现空编译输出后，通过仅强制重编该任务恢复；未改变相机业务行为。

此包来自当时的工作目录，包含正在完善的前端及其他未提交源码；不是单独由 `09f0195` 这一历史提交重建的产物。这些既有改动和用户原件均保留，打包没有将其回退或擅自提交。`dist/prototype-source-status.txt` 保存交付时的状态快照。

手动准备与打包脚本为 `tools/prepare_android_prototype.ps1`、`tools/build_android_prototype.ps1`。下载恢复辅助脚本不自动运行，没有添加测试或打包钩子。首次准备失败及成功构建日志、包结构核对、哈希均放 `dist/`。后续需要重新打包时由用户授权，当前目标到此完成。
