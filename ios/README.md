# 松映 · Lumatte — iOS 原生版

这是 [Lumatte](https://github.com/GameChicks/Lumatte) 的 iOS 原生移植，使用 **SwiftUI + Metal** 重写，最低支持 iOS 17。它复用上游仓库中同一套三阶段胶片模型（response / development / scan 3D LUT）和 CC-BY-SA 4.0 胶片资产，不包含 Android 代码或桥接层。

## 已实现

- 相册与文件导入（JPEG / HEIC / PNG；RAW 只读取内嵌 JPEG 预览，不做 RAW 显影）
- 26 款胶片：18 款 SpektraFilm 彩色片、6 款 Fotufilm（含 CineStill 800T / 400D）、2 款项目自有银盐黑白
- Metal 三阶段 LUT 渲染，包含曝光、Halation、黑柔（1/8、1/4、1/2）与统计颗粒
- 单槽 `.cube` 3D LUT 导入、强度混合（在胶片之后应用）
- 7 种画框、画布比例、留白、标题、签名与 EXIF 参数
- 批量导入、批量导出、保存到照片图库、JPEG / PNG 与分享
- 原图落盘、内存仅保留 1600px 预览；导出长边上限 5120px，避免 Metal 纹理占用失控
- 所有处理均在本机完成，不访问网络

## 尚未实现

- 松下相机蓝牙配对、Wi-Fi 浏览与照片传输
- 双 LUT 槽、官方 LUT 浏览器与 LUT 画廊
- 胶片包装插画、胶片暗盒模板的完整美术资产
- CineStill 的多重返光物理求解（当前为单尺度高斯近似）
- Remjet 边缘光晕、视频导出、逐张独立胶片参数

这些缺口在移植记录中有详细说明：`docs/engineering/ios-swift-port-20261018.md`。

## 构建

要求：Xcode 16 或更新版本、iOS 17 SDK、Apple Silicon 或 Intel Mac。没有 CocoaPods / Swift Package 依赖。

```sh
cd ios
xcodebuild -project Lumatte.xcodeproj -scheme Lumatte \
  -sdk iphonesimulator -configuration Debug \
  CODE_SIGNING_ALLOWED=NO build
```

真机 / Release：

```sh
xcodebuild -project Lumatte.xcodeproj -scheme Lumatte \
  -configuration Release -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
```

在 Xcode 中打开 `ios/Lumatte.xcodeproj`，选择自己的 Team 即可签名安装。

### 胶片资产

iOS 工程不复制胶片模型，而是由 `scripts/stage_film_assets.sh` 在构建时从上游 Android 模块读取：

```text
core/media/src/main/assets/film/
```

如果本地只有 `ios/` 目录，可以设置 `LUMATTE_FILM_ASSETS` 指向一份暂存的 `film/` 目录，或把它放到仓库根目录的 `.film-assets/film/`（已被 Git 忽略）。

```sh
LUMATTE_FILM_ASSETS=/path/to/film xcodebuild ...
```

### Metal 着色器

着色器以 `Lumatte/Resources/Shaders.metal.txt` 形式打包，在首次渲染时通过 `device.makeLibrary(source:)` 运行时编译。这样做不依赖 Xcode 27 可选下载的 Metal Toolchain 组件，也让同一份 MSL 可以被 iOS 与本地验证脚本共用。

## 结构

| 路径 | 作用 |
| --- | --- |
| `Lumatte/Views/` | SwiftUI 首页、暗房编辑器、胶片目录、画框与导出 |
| `Lumatte/Services/FilmCatalog.swift` | 读取上游胶片目录 |
| `Lumatte/Services/FilmModel.swift` | 校验模型 SHA-256，建立 3D LUT 纹理 |
| `Lumatte/Services/FilmRenderer.swift` | Metal 多通道渲染：降采样、响应、散射、光晕、主渲染、LUT |
| `Lumatte/Services/FrameRenderer.swift` | Core Graphics 画框与文字排版 |
| `Lumatte/Services/PhotoLoader.swift` | ImageIO 导入、方向归一化与 EXIF |
| `Lumatte/Models/CubeLUT.swift` | `.cube` 3D LUT 解析 |
| `LumatteUITests/LumatteUITests.swift` | 模拟器端到端 UI 测试：导入、换胶片、画框、导出与存相册 |

## 验证记录

- Xcode 27.1（iOS SDK 27.1），`iphonesimulator` Debug 与 `iphoneos` Release 均编译通过
- Metal 着色器在 macOS 上通过 `MTLDevice.makeLibrary(source:)` 编译，导出 9 个函数
- 使用同一套 MSL 与 Portra 400 / CineStill 800T / Ektachrome 100 模型完成合成图渲染检查
- iOS 27.1 模拟器（iPhone Duo）端到端 UI 测试通过：相册导入 → Kodak Gold 200 默认渲染 → 切换 Portra 400 → 拍立得画框 → 生成 JPEG → 存入照片图库
- 导出文件为 1697 × 1361 JPEG，画框、颗粒与光晕均正常；照片图库中确认新增同尺寸图片
- 生成了未签名 IPA；**没有**在实体 iPhone、真实相机照片或真实设备相册权限下运行验证

UI 测试命令（需要可用的 iOS Simulator runtime）：

```sh
xcodebuild test -project Lumatte.xcodeproj -scheme Lumatte \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  CODE_SIGNING_ALLOWED=NO
```

## 许可

应用代码遵循 GNU GPL v3.0，与上游一致。胶片模型与资产版权/许可见 `core/media/src/main/assets/film/` 内的 `NOTICE.txt`、`CC-BY-SA-4.0.txt`、`SPEKTRAFILM_LICENSE.txt` 与 `LICENSE.txt`。本移植不重新分发这些文件，而是在构建时从上游目录复制。
