# 松映 · Pineframe

独立第三方 Android 摄影工具：松下相机连接与照片传输、胶片模拟、双 LUT、批量处理和画框导出。工程名 Lumix Film Mirror，包名 `com.lumixfilmmirror.app`。

## 功能与边界

- BLE 配对与 Wi-Fi 连接、相机媒体网格、多选传输和连接日志。相机范围不包含实时取景、遥控拍摄或参数控制。
- 胶片使用三阶段移动模型及 GLES 3 / 原生 CPU 后备；CineStill 800T、400D 使用 Fotufilm 默认物理光晕，空间取样长边 1536，其他胶卷保持原路径。
- 双 LUT 独立强度、颗粒、质量和尺寸设置，支持导入 Cube / VLT。
- RAW 只提取内嵌 JPEG，不解码或显影传感器数据。默认导出有效输入的 100% 尺寸。
- 批量可逐张调整或继承第一张，整批共享导出画框；EXIF 机型用于 Lumix 水印。

当前原型为 **0.2.1 / 12**，最低 Android 11（API 30）。[版本记录](docs/releases/CHANGELOG.md)说明已交付内容和验证范围。编译、定向数值检查及 APK 校验不代表所有相机兼容、实拍观感或骁龙 865 的 15 秒导出目标已得到验证；本次光晕验证见[工程记录](docs/engineering/fotufilm-physical-halation-20261017.md)。

## 构建

使用 JDK 17、Android SDK 36、NDK 28.2.13676358、CMake 3.22.1 与随仓库提供的 Gradle 8.13 Wrapper。详见 [BUILDING.md](BUILDING.md)。构建不依赖本机参考 APK、恢复会话、旧工程、SDK 副本或其他私有文件。

Windows 手动打包并复制到 `dist/`：

```powershell
./tools/build_android_prototype.ps1
```

脚本不自动运行测试、启动模拟器或上传。测试与打包均按项目约定手动触发。

## 目录

| 路径 | 内容 |
| --- | --- |
| `app/` | Android 入口、Compose 界面、小组件和应用资源 |
| `core/` | model、design、media、camera、storage、transfer 模块 |
| `native/` | 移动胶片、双 LUT、RAW JPEG 预览 JNI |
| `third_party/libraw/` | 构建所需的固定 LibRaw 源码、清单及许可 |
| `research/fotufilm/` | 模型再生产所需的固定输入、原始方程和来源哈希 |
| `tools/` | 手动构建、模型生产及光晕定向验证工具 |
| `docs/` | 产品规则、前端接口、技术说明、协议和发布记录 |
| `gradle/` | 固定版本的 Gradle Wrapper |
| `dist/` | 本机交付包和附件，生成文件不提交 Git |

原始参考照片/视频、APK 逆向中间结果、恢复会话、重复素材、废弃 Vulkan 参考后端和旧开发记录已移出公开源码树。SDK、JDK、AVD、缓存及签名材料不随源码发布。

## 许可与公开分发状态

应用代码使用 [GPLv3](LICENSE)。第三方代码、模型、字体和图片保留各自许可与署名，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

**部分用户提供的字体、LUT 和恢复画框素材仍待补齐公开再分发授权。** 按维护者要求保留这些运行资源并标明状态，不能用项目 GPL 代替其授权；当前目录整理完成不等于所有素材授权已确认。

首次公开可使用 `git archive` 导出的当前源码快照。不要直接把本机构建目录、`dist/`、原始素材或旧 `.git` 历史整体上传；本地历史保留了开发与恢复记录，普通删除提交不会清除旧历史中的文件。
