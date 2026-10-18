# iOS 原生移植记录

日期：2026-10-18

## 目标

把 Lumatte 的本地暗房工作流移植为原生 iOS 应用：导入照片、套用胶片、试套 LUT、添加画框并导出。相机无线传输不在本次范围内。

## 范围

本轮覆盖：

- 照片导入：PhotosPicker、Files、ImageIO 方向归一化、EXIF 读取。
- 胶片目录：直接读取上游 `mobile-v1`、`mobile-fotufilm-v1`、`mobile-monochrome-v1` 三组 `catalogue.json`，共 26 款。
- 胶片模型：读取 `model.json` 与三个 `.f32` 阶段表，逐文件校验 SHA-256，建立 `rgba32Float` 3D 纹理。
- 渲染：Metal 片段着色器实现三阶段 LUT、曝光增益、扩散（黑柔）、Halation、统计颗粒与整体强度。
- 空间效果：分析网格按上游取 512（普通胶片）与 1536（CineStill）长边；高斯核用于散射与返光。
- LUT：解析 `.cube` 3D LUT，在胶片之后以强度混合。
- 画框：Core Graphics 实现无边框、白色相纸、黑色画廊、拍立得、典藏纸框、胶片暗盒、记忆票根。
- 导出：JPEG / PNG、尺寸百分比、质量、照片图库、文件分享、批量导出。
- 内存：原图写入临时文件，编辑时仅保留 1600px 预览；导出长边上限 5120px。

## 与上游的差异

1. **CineStill 光晕**：上游使用多重返光物理核（`fotufilm-legacy-physical-v1`）与 1536 网格。iOS 版使用按通道加权的一阶高斯近似，保留独立红橙光晕与矩阵返光，但没有逐 bounce 求解。
2. **Remjet**：未实现边缘红色光晕。
3. **胶片暗盒美术**：未使用上游包装插画，采用简洁的齿孔与文字排版。
4. **相机传输 / 双 LUT / 官方 LUT 浏览器**：未实现。
5. **颗粒**：公式与上游 `apply_grain` 一致，但分析网格、光晕近似与 GPU 舍入会让结果不是逐像素一致。
6. **色彩管理**：输入统一转换到 sRGB，输出为 sRGB JPEG/PNG；未写入 ICC 配置或原始 EXIF 到导出文件。

## 验证

实际完成的验证：

- `xcodebuild` Debug（iphonesimulator）与 Release（generic iOS device）编译成功。
- Metal 着色器通过运行时编译，函数列表包含 `linear_downsample`、`film_response`、`blur_horizontal`、`blur_vertical`、`combine_scatter`、`combine_halo`、`film_render`、`lut_render`、`fullscreen_vertex`。
- 使用合成渐变与色块图，对 Portra 400、CineStill 800T、Ektachrome 100 运行同一套 MSL 与 LUT 表，检查均值/极值并生成 PNG。
- 在 iOS 27.1 模拟器中安装 Debug 构建，UI 测试完整跑通：相册导入 → 默认 Kodak Gold 200 渲染 → 搜索并切换 Portra 400 → 选择拍立得画框 → 生成 JPEG → 存入照片图库。导出文件 1697 × 1361，照片图库新增同尺寸资产。
- 生成了未签名 IPA。

没有完成的验证：

- 实体 iPhone 安装与运行
- 真实相机照片（尤其 RW2 内嵌预览）
- 批量导出与分享面板的端到端操作
- 与 Android 输出的逐像素对比

## 后续工作

1. 在真机上验证 Metal 内存峰值与导出速度，必要时按 tile 渲染。
2. 补齐 CineStill 多 bounce 与 remjet。
3. 接入 Lumatte 相机协议（蓝牙 BLE + Wi-Fi HTTP）。
4. 增加双 LUT 槽与 `.cube` 画廊。
5. 将导出 EXIF / ICC 与原始文件名写入输出。
