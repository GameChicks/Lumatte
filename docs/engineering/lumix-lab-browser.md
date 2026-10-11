# 官方 LUT 浏览与工作台交接

2026-10-11。此文档为源码接口交接，没有服务器请求、编译、Android 图像、测试或打包证据；正式 UI 由用户另选的前端实现。

## 语言值

本轮实际运行本项目的只读资源恢复脚本，限定用户 Lab APK 的 `string/app_lang`，得到 11 个值／配置。结果与 APK、resources.arsc 哈希在 [资源证据](../../research/lumix-lab/languages-source-snapshot.json)。默认为 en；de/es/it/ja/ko/nl/ru 使用同语言配置，fr 使用 fr-rFR；中文使用 zh-rCN → zh-CN、zh-rTW → zh-TW。解析配置依据 [AOSP ResourceTypes.h](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/libs/androidfw/include/androidfw/ResourceTypes.h) 的布局，本次语言／地区均为普通 ASCII 两字母字段。

`core:media` 新增相同 qualifier 的自身 `lumix_lab_language` 资源值，由 Android 选择。`LumixLabLanguages.forContext(context)` 读取传入 Context 当前配置，前端有每应用语言覆盖时传该 Context；`supportedCodes` 提供恢复值。`PhotoProcessor.officialLutLanguage()` 使用应用 Context，下载及 ViewModel 目录刷新默认读取它，不将 Locale.toLanguageTag 的任意变体直接当作服务参数。当前只是资源声明与读取源码，未执行 Android locale 选择或证明服务器支持所有值。

## 浏览状态

`WorkspaceViewModel.officialLuts` 是共享处理器的 `LumixLabBrowser`，构造不发送请求。独立前端也可创建 `LumixLabBrowser(client, Main lifecycleScope)`。方法应在主线程调用，状态通过 `StateFlow<LumixLabBrowserState>` 订阅；浏览范围只涉及远端 metadata／数字颜色，不持有卡片 Bitmap，不改变批次、效果或画框。

| 入口 | 行为 |
|---|---|
| `loadGenres()` | 显式请求分类；独立 loading/error，重复调用取消前一次 |
| `refresh(query)` | 新查询从 offset 0 开始；清除旧条目／颜色，取消旧分页／分析／详情 |
| `loadMore()` | 显式按 nextOffset 请求；失败保留已加载项目与原位置，不自动重试 |
| `cancelPage()` | 取消分页及全库颜色分析；保留现有条目，可继续下一页或刷新 |
| `openDetail(code)` / `closeDetail()` | 独立请求／关闭详情；关闭不改变列表、顺序或查询，滚动位置由前端保存 |
| `example(item, longEdge)` | 返回前端拥有的小图，同时记录已加载匹配条目的真实创作者颜色 |
| `analyseLoadedExamples()` | 按调用时已加载、尚未分析的候选逐项取色，立即回收临时图 |
| `cancelExampleAnalysis()` | 只停全库分析；待任务清理结束再清 loading，不中断分页或前端自己的可见图请求 |
| `cancelRequests()` | 退出浏览时取消该协调器拥有的分类、分页、详情和分析请求；卡片协程由前端取消 |

分类／列表／详情分别使用请求代次屏蔽迟到结果与旧 finally 状态，换条件后旧响应不能覆盖新目录。分页按原响应条目数推进 offset，按 code 去重时保留顺序；重复 code 更新 metadata，缩略图引用变化时丢弃该条旧颜色／错误。nextOffset 只是可继续的位置，无远端总数或下一页存在性承诺；收到空页后结束，没有自动无限加载循环。

`coloursCompleted/coloursTotal` 是本次候选已尝试数量，包含失败项，不是全部官方 LUT 已分析数量；`exampleErrors` 保存实际失败条目。新页加入的项目不自动加入已运行的分析，完成后可再次调用；已知颜色保留，失败项可重试。取消和异常保持 Bitmap 所有权，状态只留数字。

`LumixLabGalleryFilter.select(state, query)` 支持已加载标题／创作者名称、颜色家族及例图近黑白筛选。它不搜索服务器尚未加载的内容，也不把未取色／取色失败当成已证明不匹配。界面应同时呈现已加载／尚未分析状态，不能宣布已经完整检索全部官方目录。分类条件使用服务器 genre code；不从色彩推测“街头／自然”等题材。

## 下载、选择和持久化

`refreshOfficialLuts(genreCode, creatorCode, language)` 是 ViewModel 的便利入口。`downloadOfficialLut(code, language)` 返回是否接受任务，忙时不启动第二次；`cancelOfficialLutDownload()` 只取消这次下载。`WorkspaceState.officialLutDownload` 提供 code、RUNNING/SAVED/CANCELLED/FAILED、阶段、成功 assetId 或失败信息。阶段为 `READING_DETAIL → DOWNLOADING → IMPORTING`，含实际解析／存储工作，没有虚构百分比；成功后已加入 `state.luts`。

下载不自动选中 LUT、不改变当前批次配方／画框、不启动导出。前端得到成功 assetId 后显式调用 `selectLutSlot(id, second=false/true)`，只改变对应槽 ID，保留两槽强度、颗粒、另一槽和整批画框；null 清空该槽。此入口只接受实际已加载 assetId，并沿用 `updateLut` 的批次草稿／延迟保存。切换到 LUT 工作流仍由前端调用 `treatment(LUT)`，下载的服务器 code 不能作为本地槽 ID。

`reloadLutLibrary()` 可显式重新读取已保存资产；取消在最后原子提交之后到达时，新增文件可能已经存在但未交付给状态，用此入口发现，不删除已保存文件。重新加载不改配方或画框，失败保留原内存目录；本地与内置 cube 解析也接入每 128 行取消检查。官方文件、来源、ZIP 和网络限制见 [下载后端](lumix-lab-catalogue.md)。

本轮只有资源解析／资料生产、功能源码与手工阅读证据。没有执行自动测试、服务器连通请求、Android 资源选择、DAO 保存／恢复、图片解码／取色或构建。原型 UI 文件未改。
