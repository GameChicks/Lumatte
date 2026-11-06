# SpektraLab for Android

把 [SpektraLab](https://github.com/JamesQiu2005/SpektraLab) 的胶片/相纸模拟引擎搬到 Android 的移植。
打开一张 RAW，选好胶片和相纸，几秒内得到一张按物理过程建模的照片——81 个波长穿过染料层，
颗粒、光晕、成色剂耦合按画幅的微米尺度计算，不是 LUT。

> 这是社区移植，不是上游官方版本。引擎来自上游 `assist/vulkan-engine-1.3.4`（commit `445e314`）。

**下载** — [spektralab.workshop.skin](https://spektralab.workshop.skin/) ·
直链 [`/download`](https://spektralab.workshop.skin/download) ·
[GitHub Releases](https://github.com/SAINTTaiYi/SpektraLab-Android/releases)

## 现在能做什么

| 项目 | 状态 |
|---|---|
| RAW 解码（LibRaw，原生） | ✅ Panasonic GH5 `.RW2` 实测 |
| 胶片 / 相纸 | ✅ **25 款胶片 + 9 款相纸**，运行时从资源目录读取 |
| 预览（live 档） | ✅ 2048 长边，约 1 秒 |
| 导出 | ✅ 三档：长边 2048 / 3400 / 全尺寸；受限设备会标注上限 |
| 全尺寸耗时 | ✅ 渲染 8.2 s / 总 8.4 s（SM8735 + Adreno 825，16.5 MP） |
| 颗粒 / 光晕 / 眩光 强度 | ✅ 三条滑杆（`grain_amount` / `halation_amount` / `glare_amount`） |
| 画幅（物理尺度） | ✅ 135 / 半格 / XPan / 645 / 6×6 / 6×7 / 6×9 / 6×12 / 6×17 |
| 换一张 | ✅ 直接替换当前照片，不需要重启 |
| 配光（Process） | ✅ 导入时自动 `spk_solve(both)` |
| 导出写盘 | ✅ 原生 libjpeg，不经 Bitmap |

## 1.2.3 里改的

- **修好了超过 1677 万像素时画面顶部未渲染**。一次 dispatch 超过 65 535 个 workgroup 时，
  旧实现把组折成 2D 行，这块驱动会整块丢弃。现在按 65 535 组切块，shader 用 4 字节
  push constant 加基址；同一帧 1 / 65 / 472 块切法输出逐字节相同，20.3 MP 帧顶部
  200 行 std=1225、无空白行。
- **导入拆成两个入口**。点击「导入」进相册；长按「导入」可选「文件（RAW）」与
  「按原始尺寸导入」。之前为了显示 `.ARW` 把选择器改成 `*/*`，Android 于是只给文件管理器。
- **导出 JPEG 改 4:4:4**。libjpeg 默认 4:2:0 把色度分辨率砍到 1/4，颗粒与染料云正好在那里。
  实测同一张图 85,840 → 137,940 字节。导出完成会同时报尺寸与体积。
- **为分块铺路**：`Arg` 增加绑定窗口 `(offset, range)`，`dispatch()` 按窗口绑定；
  默认整块，行为不变（渲染哈希复验 `0b3c0685f4c262d7`）。设计见 `docs/tiling.md`。

## 1.2.2 里新增的

- **设备探针**。启动时读 `maxStorageBufferRange`、显存与调度上限，换算成这台机器能吃的
  最大像素数，导入时据此定尺寸，并在状态栏写明有没有缩过。
- **分档导出**，并保留全尺寸。受限设备上全尺寸那行会标注「本机上限约 X MP，可能导出失败」；
  长按「导入」可以强制按原始尺寸导入。
- **错误码**。每个失败都带 `E-xxxx`（1001 缓冲超限、1101–1106 RAW 各阶段、1201–1202 渲染、
  1301–1303 请求、2001–2002 更新），截图即可反馈。
- **修复：选不到 RAW**。文件选择器原为 `image/*`，索尼 `.ARW` 等被存储层标成
  `application/octet-stream`，于是整个被过滤掉。
- **修复：竖拍照片偶发被旋转**。读 EXIF 时二次打开流会失败（一次性流），现在有
  `openFileDescriptor` 兜底。
- **更新通道**。启动 3 秒后静默检查 `update.workshop.skin`，6 小时节流，同一版本只提醒一次；
  标题长按可手动检查。

## 移植过程中修掉的三个硬问题

这三个是让「能在 Android 上出图」变成「能出对的图」的关键，全部在 `patches/engine-android.patch` 里：

1. **导出成片横向错乱** —— `spk_result.row_stride_px` 的单位是**像素**，每个像素占 4 个 uint16；
   消费端写成 `rgba16 + y * stride` 会让每行只前进 1/4 行。正确写法 `rgba16 + y * stride * 4`。
2. **全尺寸 `VK_ERROR_DEVICE_LOST`** —— IIR 高斯原来一次 dispatch 跑完整个平面，热降频下单次要 600 ms 以上，
   撞上 Adreno 的看门狗。改成**每通道 × 每 512 行**一发：单次 616 ms → 34 ms，
   输出与原实现**逐字节相同**（8 MP 输出哈希 `81ecd543d8b34a87`）。
3. **大图打包丢块** —— `spk_to_rgba16` 一次要发 6 万多个 workgroup，
   超过驱动上限的块会静默丢失（表现为未初始化显存）。改成按 512 行分带发射。

## 已知问题

- **像素数 > 16,776,960（= 65,535 workgroups × 256 threads）时仍有风险**：管线中还有 kernel 会踩同一个坑，
  表现为图像顶部约 200 行是未初始化显存。当前用导入时 `maxLongEdge = 4700` 规避。
  通用修法（给 `spk_global_index()` 加 push constant 基址、`dispatch()` 自动切块）尚未实现。
- 对比度蒙版（`contrast_mask`）上游 Vulkan 后端未移植，`capabilities.backend.unsupported_features` 里显式拒绝。
- 片边（Film Edge）、日期后背、半格拼接、几何裁剪、批量导出等**引擎已实现但 UI 尚未接入**。

## 构建

前置：Termux（aarch64）、Android SDK 36 / NDK 28、上游引擎源码。

```bash
# 1) 上游引擎 + 本项目补丁
git clone https://github.com/JamesQiu2005/SpektraLab
cd SpektraLab && git checkout 445e314
git apply /path/to/patches/engine-android.patch

# 2) 资源：把 engine/resources/ 下的 profiles/、vulkan/、*.bin、*.json
#    拷到本工程的 app/src/main/assets/resources/
#    （仓库不含约 30 MB 的二进制资源；APK 里额外的 6 个 profile 来自
#      SpektraLab 应用包，未随本仓库分发，请自行准备）

# 3) 编译引擎对象与 JNI（脚本里的路径按你的环境改）
bash app/build/build-engine.sh
bash app/build/build-native.sh
cp app/build/native/libspektrafilm_engine.so app/build/apk/lib/arm64-v8a/

# 4) Java + 打包
javac -source 17 -target 17 -d app/build/classes -cp $ANDROID_HOME/platforms/android-36/android.jar @app/build/sources.txt
d8 --release --min-api 24 --lib $ANDROID_HOME/platforms/android-36/android.jar \
   --output app/build/dex $(find app/build/classes -name '*.class')
bash build-apk.sh
```

## 目录

| 路径 | 内容 |
|---|---|
| `app/src/main/java/` | Activity、引擎 Java 门面、调试上报 |
| `app/src/main/cpp/` | JNI：会话、渲染、导出（原生 libjpeg）、RAW 打开 |
| `app/build/` | 引擎对象、JNI、资源目录的构建脚本 |
| `patches/engine-android.patch` | 对上游引擎的 6 个文件改动 |
| `build-apk.sh` | aapt + d8 + apksigner 打包 |

## 致谢与许可

- 引擎、胶片过程模型与上游实现：[JamesQiu2005/SpektraLab](https://github.com/JamesQiu2005/SpektraLab) — GPL-3.0-or-later
- 胶片模型、28 款实测配置与放大 LUT：[Andrea Volpato 的 spektrafilm](https://github.com/andreavolpato/spektrafilm) — CC BY-SA 4.0
- 本仓库：Android 移植（Vulkan 后端适配、JNI、UI、上述三处修复）

代码以 **GPL-3.0-or-later** 分发（见 `LICENSE`）；胶片与相纸配置以 **CC BY-SA 4.0** 分发（见 `LICENSE-SPEKTRAFILM.txt`）。

---

### English

An unofficial Android port of the SpektraLab film/paper simulation engine (Vulkan backend).
RAW in, physically modelled film + paper out, all on the phone.

Fixed here: the `row_stride_px` unit bug that made exports come out striped; the IIR Gaussian
launched per channel and per 512-line block so a throttled Adreno never hits its watchdog
(byte-identical output); and banded `spk_to_rgba16` packing, because dispatches past
65,535 workgroups silently lose blocks on this driver.

Known limit: frames above 16,776,960 pixels still hit that block-loss path in other kernels,
so the import caps the long edge at 4700 px for now.
