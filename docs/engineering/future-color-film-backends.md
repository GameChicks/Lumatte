# Future 色彩/胶卷后端接口（2026-10-14）

用户明确授权判断可行后直接开发后端，前端另留接口。本轮不修改 LUT 页面推荐区，不把新增胶卷草稿直接列为可渲染预设。不开展测试或打包。

## 本地 LUT 推荐

入口：`PhotoProcessor.recommendLuts(source, assets, options)`，返回 `LutRecommendationResult`，包含推荐 ID/名称、风格、观察理由、风险提示、跳过原因、实际试套候选数及采样像素数。前端使用已有 `previewLutCard()` 展示该照片上的预览，再以 ID 建立既有 LUT 配方。

- JPEG 在正确方向下解码到长边最多 256 像素，每候选最多约 4096 个颜色样本；不处理全分辨率照片、不访问 RAW 传感器数据。
- 最多接收 256 个本地 LUT，先用中性灰阶/基色变换特征筛选，每风格取六个候选、合计最多 24 个。再对实际照片样本做三线性查表，按亮度、冷暖、饱和度变化及新增裁切重新排序。
- 默认返回自然、暖调、冷调，各最多三项；可选择黑白方向，仅接受低色度输出特征候选。不同方向允许同一 LUT 重复出现，代表审美方向而非互斥分类。
- 特征基于 LUT 实际数值、轴范围、1D/3D 大小和 VLT 网格标识的 SHA-256；版本/分析输入写入缓存名。按 16 KB 块计算哈希，检查取消；当前对象特征保留最多 64 项，磁盘缓存每项仅少量浮点数，Android 可回收。缓存写入失败仍可返回计算结果。
- `inputOverrides` 可显式声明 sRGB JPEG / V-Log / 其他 / 未知。当前源码把有 STD/NAT 输入风格元数据的库项列为 sRGB JPEG 候选，同时提示所需输入风格；这不证明任意来源 JPEG 已满足其照片风格。默认排除未确认、V-Log、其他输入；只有显式 `allowUnknownInput=true` 才加入未知项，并保留提示。
- 纯本地，无上传、无后台下载、无大型语义 AI。不会凭绿色很多就称作风景、凭例图颜色直接判定用途，也不显示伪造适合度百分比。排序是启发式选择建议，观感和速度尚无设备验证。
- `method=local-colour-preview-v1` 是算法版本，不是效果质量证明。客户端取消会终止解码后的分析，位图由 processor 回收。未来可增加元数据/例图/内容识别评分，不修改既有接口用途。

可用类型见 [LutRecommendations.kt](../../core/media/src/main/kotlin/com/lumixfilmmirror/media/LutRecommendations.kt)，入口见 [PhotoProcessor.kt](../../core/media/src/main/kotlin/com/lumixfilmmirror/media/PhotoProcessor.kt)。

## 更多胶卷与冲洗条件

入口：`PhotoProcessor.filmProfileDrafts`（`FilmProfileDraftLibrary`）。当前完成资料阶段的后端，真实新胶卷渲染模型仍未生产。

| API | 用途 |
| --- | --- |
| `save(profile)` | 校验并原子保存自有胶卷资料草稿，返回完整度评估 |
| `list()` | 读取资料/冲洗变体和缺失项 |
| `assess(profile)` | 检查当前是否有各记录的感光、染料、基底、冲洗曲线与受控测量声明 |
| `exportSourceJson(id)` | 导出 schema 1 JSON 给电脑端拟合/生成器，不执行任何外部命令 |
| `densityAt(id, variantId, logExposure)` | 对所选冲洗条件的原始密度曲线做线性插值与端点夹取；是曲线查看功能，不是彩色照片显影 |

数据包含型号/厂家/版本/分类、RGB 或 silver 记录、感光与染料光谱、基底密度、按冲洗条件分别提供的密度曲线，以及每个变体的来源标题、网址、资料版本和许可说明。冲洗条件记录流程名称、温度、时间、曝光指数、迫冲/减感量及目标印相/扫描方式。温度/时间等可明确未知，不从这些参数自动猜出新曲线。

数据范围有界，曲线横轴严格递增且数值有限；反转片密度可以随曝光下降，不强制负片的单调方向。草稿保存在本应用 `filesDir/film-profile-drafts-v1`，不改已有胶卷配方或数据库，不读取旧应用资料。最多 128 款草稿，每款最多 16 个冲洗条件，每组最多 512 个采样点、源 JSON 最多 2 MB。

`FilmProfileEvidence` 是资料提供者声明的 published/controlled/creative 来源级别，不是应用完成真实性认证。`availableAsRenderingPreset` 始终为 false，并明确要求离线拟合、输出校准、移动三阶段模型与空间/颗粒参数核对。不把缺少数据的型号包装成已经复刻成功，也不把黑白实现为彩色去饱和。

下一步应先收集一款具体胶卷及明确冲洗/输出条件的数据，把草稿接到电脑端离线模型生成，再通过哈希校验接入现有移动模型库。尚未完成新数据包导入器、任意资料到三阶段表的拟合器、新厂家预设或真实冲扫校准。本轮留接口并完成资料存取/完整度/曲线查看，不能宣称整个第二想法已完成。

类型与实现见 [FilmProfileDrafts.kt](../../core/media/src/main/kotlin/com/lumixfilmmirror/media/FilmProfileDrafts.kt)。

## 编译与运行边界

本轮只做媒体、相机及 app 的 Kotlin 源码编译；不执行推荐、胶卷草稿存取、图片、连接或性能测试，不打包。所有运行效果、缓存复用、资料往返、排序观感和手机耗时仍待获准后的核对。
