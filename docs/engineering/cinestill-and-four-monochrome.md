# CineStill 与四款黑白卷（2026-10-16）

用户要求先保存并交付修改前版本，再新增 CineStill 800T / 400D，用四款不同方向的黑白卷替换选型页中的两款自有 Silver。后续明确：只有两款 CineStill 使用独立增强光晕，其余使用 SpektraFilm 默认设置。

## 修改前交付

Git `360d881fb9852e682f27ea461d29f35a75df45dc`，标签 `lfm-before-fotufilm-six-stocks-20261015`。为避免同一工作区正在写入的前端素材影响构建，从该提交导出的固定源码快照打包。

`dist/lumix-film-mirror-prototype-0.1.9-20261015-235532-debug.apk`，0.1.9 / 10，186391644 字节。SHA-256 `EECE8BF6E5AB4C34303AEB86324133A3C124DF7D4790C0ED26BE3C451D856015`。签名、版本读取及 ZIP 的 16 KiB 对齐通过；不代表已通过实机或所有原生库运行兼容性验证。附带 `dist/pre-six-stock-0.1.9-release.json`。此包没有以下新胶卷或后续连接修复。

## 新模型

| ID | 名称 | 方向 | 新配方光晕 |
| --- | --- | --- | --- |
| cinestill_800t | CineStill 800T | 钨丝灯、夜景、较强红橙返光 | 开 |
| cinestill_400d | CineStill 400D | 日光、比 800T 克制的暖色返光 | 开 |
| fujifilm_acros_100_ii | Fujifilm Acros 100 II | 细颗粒、连续灰阶 | 关 |
| kodak_tri_x_400 | Kodak Tri-X 400 | 鲜明层次、纪实纹理 | 关 |
| ilford_hp5_plus_400 | Ilford HP5 Plus 400 | 柔和中间调、日常人像 | 关 |
| ilford_delta_3200 | Ilford Delta 3200 | 低照度气氛、明显粗颗粒 | 关 |

移动表由 Fotufilm 公开浏览器输入包生成，不是给 Vision3 或 Silver 改名。每款使用自己的曝光表、特性曲线、局部层间抑制与固定印相输出。源字节、配置参考提交、公开包日期与哈希分别保存于 `research/fotufilm/`；公开包没有公布构建提交，不能声称对应固定提交的完整引擎结果。

响应表 33³、显影表 65³、扫描表 41³，float32 / 第一通道最快，单款 4553796 字节。固定输出、不作逐图自动色阶，曝光仍只在胶片响应域乘一次；颗粒在密度域添加。模型缓存最多两款、12 MiB，避免新模型超出旧 4 MiB 预算而每次重载。

点运算适配遵循上游公开的宽色域输入转换、曝光域归一化、采样特性曲线、局部 coupler / neutral warp、相纸显影和 sRGB 输出。空间 DIR、完整银盐／晶体颗粒与完整分层光输运没有移植。颗粒强度按源颗粒资料转换到现有密度统计近似，空间尺寸按源胶片单位换算；黑白扫描固定等通道输出，不能出现彩色颗粒染色。

## 光晕分流

`lfm-optical-spatial-v3-stock-and-cinestill`：

- 仅两个明确的 CineStill ID 启用独立近场／主场／宽尾外观，800T 返光 `[.12,.004,0]`、首尺度 90 µm；400D `[.065,.003,0]`、65 µm。这是项目移动艺术参数，不是冒充上游标定值或把它们用于所有负片。两款空间场长边上限 1024，以减少小灯点被平均的程度；最终输出尺寸不改变。
- 原有 SpektraFilm 胶卷保留资产中的返光通道比例、65/50 等逐卷半径、3 次反射及 .5 衰减；不再乘 2 或强制统一红通道。按 `sqrt(k)` 宽度和归一化衰减计算，保留默认乳剂 Gaussian core + exponential tail 的参数与三高斯指数近似。
- 新黑白卷没有 SpektraFilm 的对应品牌配置，手动打开光晕时使用其通用 HalationParams 默认：`[.05,.015,0]`、65 µm、3 次、.5 衰减，不伪称逐卷实测标定；最终仍输出黑白。
- 普通卷空间修正按 E2-E0 写入已有浮点场，避免增加整图位图／纹理或让微观散射重复计入。空间场仍是最长边 512 的移动近似，不能称为桌面逐像素一致。
- 旧 `RED_LAYER_RETURN` 后端外观标记保留 API 兼容，但不再改变普通胶卷返光。普通 UI 仍只有光晕开关，黑柔独立。

新建默认和选卷由 `FilmDefaults` / `FilmParameters.withPreset` 决定。已有配方显式保存的开关不被批量改写。Silver 两款从新选型目录移除，但旧模型资源与渲染解析保留，已有草稿／导出记录仍可解析。默认目录为 20 款原有彩色加 6 款新增；没有改动数据库 schema、RAW 约定、批量模式或共用画框规则。

## 验证边界

已执行资源生产，生成器检查输入哈希、FSWP ABI、浮点有限性和表布局；这是资产生产约束，不是照片效果测试。NDK arm64-v8a / x86_64 原生库、core:media 与 app Kotlin 编译通过，Gradle 为 `BUILD SUCCESSFUL in 1m 3s`；着色器通过 glslangValidator 的 fragment / vertex 编译检查。记录位于 `tools/.cache/fotufilm-six-stocks/source-compile.log`。该次 app 编译也包含连接修复和新增异常定位日志。

未运行单元、回归、冒烟、设备、照片对照或性能测试，未生成包含这些修改的新 APK。800T 的目标观感、四款黑白的实际成片差异和骁龙 865 / 2400 万像素 / 带框 <15 秒目标尚未经过本轮实测。
