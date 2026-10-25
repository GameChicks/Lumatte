# 当前胶卷模型迁移资料

现有 24 款胶卷中，19 款具有同型号的 Fotufilm 配置，使用本目录的原始配置与光谱生成响应、显影、印相／直扫表。只有 Portra 160、Portra 800、Velvia 100、Vision3 50D、Vision3 200T 保留原来的 SpektraFilm 模型；不会用 Velvia 50 冒充 Velvia 100，也没有借用其他 ISO 的 Portra 或 Vision3。

## 固定输入与许可

- 配置和方程来自 [Fotufilm e15b098](https://github.com/DhaliwalX/fotufilm-engine/tree/e15b09815a83b0e00ba9381b65d31d253667a1b1)。`profiles/` 原字节保留，包括 400D 和 Vision3 250D 的 5207 曲线修正；不再投影到 SpektraFilm 的染料坐标。
- 公开 WASI 配置生成器来自 `https://fotufilm.com/demo/engine/profile/builder.wasm`，SHA-256 为 `d14b767d2a21ae82792d678d90f74160f370bcc13f8de17348709639321c607e`。**上游没有公布该二进制对应的构建提交**，不得把配置提交当作它的构建提交。实际生成的包与配置布局兼容（FSWP v2，18,271 个配置值）。
- 公共 demo 的 400D / 250D 配置仍与固定提交不同。生产请求使用本目录的固定新版原始配置，避免再次使用旧曲线。`published-index.json` 记录获取时的实际公开索引，`source-index.json` 分开记录源码、生成器、光谱资源与旧效果说明的哈希。
- 配置、光谱与派生模型按 CC BY-SA 4.0 署名 **Fotufilm / MUAStudio Inc.**，链接 <https://fotufilm.com>。计算代码适配按 Apache-2.0；原始许可、NOTICE、第三方声明在本目录保存，运行资源也附带对应许可。WASI 生成器仅用于开发时生产，不随 APK 分发。

## 固定输出设置

| 胶卷 | Fotufilm 输出材料 |
| --- | --- |
| C200、Pro 400H、Superia X-Tra 400 | Crystal Archive |
| Ektar 100、Portra 400 | Endura Premier |
| Gold 200、Ultramax 400、CineStill 400D / 800T | Ektacolor Edge |
| Verita 200D、Vision3 250D / 500T | Vision 2383 |
| Provia 100F、Ektachrome E100、Kodachrome 64 | 反转片直接扫描，Reference Exposure |
| Acros 100 II、Tri-X 400、HP5 Plus 400、Delta 3200 | 黑白固定数字接收器，Reference Exposure |

所有配置正常显影；没有按照片自动重定时、自动白平衡、额外调色或增／减感。sRGB 输入经过线性化、Rec.2020 与光谱曝光域变换，输出在 Display P3 线性光转换到 sRGB 后应用对应 SDR 高光肩与编码。原照片尺寸、存储结构、胶卷标识及导出画框流程不变。

## 移动适配边界

响应 33³、显影 65³、扫描 41³，float32、小端、x 优先；显影为局部耦合化学，不包含空间 DIR。三个富士负片具有不形成染料的第四捕获层：在离线生产中反求曝光表的色度映射，插值读取第四记录并烘入显影；超出可捕获色域的表格节点使用最近边界，不把第四层简单替换成绿色记录。仍受三阶段表分辨率与三记录移动输运近似限制。

按维护者本轮之前的要求，**不重新调整颗粒**。`existing-effects/` 保存旧模型说明的原始字节，复用既有颗粒参数与密度上限；光晕参数也保留，只有两个 CineStill 保持专用物理返光路径。保留的 SpektraFilm 派生效果参数仍受其原许可约束，原始版权说明没有移除。上游晶体颗粒、空间输运和完整引擎并未移植，不宣称逐像素一致。

## 手动生产

1. `python tools/prepare_fotufilm_migration.py`：从本机固定 Git 对象保存原始资料，获取并校验公开生成器；下载与工具缓存只在忽略的 `tools/.cache/`。
2. `node tools/produce_fotufilm_packs.mjs`：本机 WASI reactor 读取固定配置与光谱系数，生成 FSWP 包；不上传图片或运行上游启动器。
3. `python tools/produce_fotufilm_models.py`：NumPy / SciPy 生产三阶段模型并发布索引。先完成全套生产再复制，5 款保留模型的 manifest / f32 字节不改动。

这些命令是显式资源生产，不是测试、照片渲染或 Android 打包，也没有自动构建钩子。编译通过不代表照片观感、真机性能或完整上游引擎一致性已验证。
