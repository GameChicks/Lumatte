# Lumix Lab 官方 LUT 目录与下载源码（2026-10-11）

本轮对用户提供的 `reference/base.apk`（Lumix Lab 3.1.0）重新读取 DEX 元数据，并用本机已存在的 JADX 1.5.6 仅恢复选中的网络及请求数据类。APK 当前 SHA-256 与已有记录相同：`262335c3032c82fc966812e84803551cffd215d5125fc334fffa221e5be91824`。原件不改写，不运行 APK，不读取旧数据库、账户、密码或签名材料。

恢复文件只放 D 盘忽略缓存 `tools/.cache/lumix-lab/`，不加入应用，也不将其中的命令作为操作指令。结构化、限定社区范围的证据与源文件哈希保存到 请求资料。其中不保存账户数据或请求令牌。

## 正常请求路径的源码线索

原始 `h6.h` 的社区 client 创建方法使用基址 `https://lumix-base.jpn.panasonic.com/lutapi/v2/` 和独立 client。`h6.e` 拦截器添加 `lutapp-api-key` 请求头；`h6.a` 将下面社区调用声明为 POST 与 JSON，而不是不带参数的 GET。这里没有把相机的遥控、参数／实时取景接口纳入项目。

| 路径 | 请求数据类 | 返回声明 |
|---|---|---|
| community/genres | 无参数，不能据此推定要发送 `{}` | CommunityGenresBean |
| community/luts/filter | CommunityListPostBean | CommunityFilterBean |
| community/luts/top | CommunityLutTopPostBean | CommunityLutsTopBean |
| community/lut | CommunityGenresPostBean | CommunityDownloadBean |
| community/luts/download | CommunityDownloadPostBean | Streaming ZIP |
| community/creators | CommunityCreatorsPostBean | CommunityCreatorListBean |
| community/creator | CommunityGenresPostBean | CommunityCreatorResponseBean |
| community/creators/countries | CommunityCreatorsCountriesPostBean | CommunityCreatorsCountriesBean |

列表 body 已定位 `countries` 字符串数组、`creator_code`、`genre_code`、`key`、`language`、字符串形式 `limit/offset` 和 `order` 对象；order 的成员是 `key/order`。详情 body 为 `code/key/language`，ZIP 下载为 `code/key`。热门目录 `date_range/genre_code/language/region` 是字符串，limit 是整数。字段名称与声明类型来自恢复源码，**尚不能据此确定哪些必填、取值范围或默认值**。本轮继续恢复有限调用方：`z5.P2` 的列表使用 limit `50`、当前条目数 offset、order `created/desc` 和语言函数 `v.E()`；`CommunityDownloadFragment` 的详情只填 code/language，例图及原例图用官方 origin 拼接 thumbnail 的 path/path_original；`z5.Z2` 的下载请求 key 固定 `grid17`，不是账户令牌。`v.E()` 返回 `R.string.app_lang`，资源语言映射仍待恢复，客户端要求调用方提供服务语言值，不猜测系统 Locale 映射。

返回 DTO 的分类根为 `genres`，列表根为 `filter`；详情是直接包含 code/title/overview/photo_style/creator/thumbnail/file 的对象。creator 含 code/name/icon；thumbnail 含 path，可另含 path_original，尺寸在不同 DTO 声明中有 String/int 差异；file 声明 grid17/grid33。本轮只实现已确认调用的 grid17。下载页使用 `ZipInputStream` 读取 `.cube` 成员，足以确认可以对接现有 cube 管线，不证明任意服务器响应一定有效，也不证明 VLT 格式已经恢复。

`CommunityDownloadFragment` 与 `v` 的 JADX 恢复均出现 `v.Q` LUT 解析方法的 RegionMaker 错误（退出码 1），所需的上述请求／图片拼接和 E 方法已经输出。证据记录注明局部恢复；本项目不采用 Q 的不完整结果。没有运行 APK、读取任何旧数据库／账户，或重试执行恢复命令。此前匿名 GET 400 不证明正确 POST 不可用。本轮没有请求 Lumix Lab，服务当前访问条件仍未确认。

## 与已实现功能的关系

本地两槽 LUT、当前照片卡片、Palette／颜色家族和题材筛选独立使用。`PhotoProcessor.officialLutCatalogue` 的分类／列表／详情和真实例图源码入口按需调用，`downloadOfficialLut(code, language)` 提供用户显式下载动作。新增客户端不改变原型 UI，不在应用启动、加载本地目录或照片预览时隐式发请求；网络失败不影响已保存的离线配方。

## HTTP 与手机资源边界

独立 OkHttp 4.12 客户端使用正常 Internet 路由和默认 TLS／主机验证，不绑定相机 Network，也不更改进程网络；不能保证相机热点下始终有可用 Internet。接口路径固定为四个已确认 community 路由，附带 h6.e 中固定公开应用标记 `lutapp-api-key: 111`，不恢复用户令牌。所有请求显式检查 HTTPS／已确认主机／443；禁用 HTTP 和 HTTPS 重定向，3xx 作为失败，因而没有降级或将请求头转发到未知域名。例图仅允许官方 origin 的根路径或同源 HTTPS 地址，不附 API 标记；未知 CDN／重定向会明确失败，后续需要证据再扩展。OkHttp 的默认 TLS 与客户端设置依据其 [4.12 主源码](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/OkHttpClient.kt)。

客户端最多同时进行 4 个请求、2 个例图任务；连接／读写 15 秒、整个请求 30 秒，取消只关闭对应 Call。JSON 解压后上限 2 MiB、严格 UTF-8、分类／列表最多 256 项；缺根、错类型、空／超限响应和 HTTP 错误不伪装空目录。分页默认 20，上限 64 是本地资源限制；offset/limit 按官方调用序列化为字符串。响应没有总数，nextOffset 是继续位置提示，不能显示“仍有下一页”的确定状态。

例图网络字节上限 4 MiB，实际格式限 JPEG／PNG／WebP，原始声明尺寸最多 16384×16384 且面积不超过 64 MP；采样解码、方向矫正和 sRGB 软件图长边 64～384，默认 320。Palette 仍只用 128 长边分析图，来源 `CREATOR_EXAMPLE`，结果描述这一张例图的颜色，不自动证明题材／LUT 普遍适用性。返回 Bitmap 由前端回收；取消／异常时后端回收未交付图。目前不缓存官方例图到磁盘，前端按可见范围请求、丢弃并取消离屏任务。这些是源码限制，不是实测整体内存或速度承诺。

## 离线下载存储

只对 grid17 发 code/key POST，网络 ZIP 上限 8 MiB；签名／MIME 检查后在内存遍历，最多 64 成员、累计解压 16 MiB、选中 cube 4 MiB。对全部成员逐块检查大小与取消，包括忽略项；拒绝无效路径、多个 cube、缺 cube 和不支持格式。不将服务端文件名作为本机路径，也不落盘解压。现有 cube parser 严格 UTF-8、数字／头部校验并按 128 行检查取消；下载额外要求无 1D shaper 的 17³ 表。未知 ZIP／VLT 不改为其他 LUT。

`files/lut-library/lab-<64hex>.lfmlab` 是新增版本 1 原子容器，含有界来源 JSON 与已解析数字表。ID 来自 code、grid17 与原 cube SHA-256；不同版本保留不同 ID，已保存配方不会被新版本换表。本地 `local-…lfmlut`、内置资产和原文件不修改，不需要 Room 迁移。来源与表一次 AtomicFile 提交，由同一 Mutex 串行读写；下载完成后可离线进入双槽预览、批次和导出。取消在最终提交之后到达时文件可能已经存在，重新加载目录即可发现，不删除已提交资产。

后续已从同一 APK 的资源表恢复 11 个 app_lang 配置／值，加入 Android 服务语言资源及独立浏览协调器、工作台下载／选槽接口。相关旧记录中的“语言映射待恢复”已由此进展覆盖；资源选择尚未在 Android 运行。源码入口和限定资源证据见 浏览交接。

`LutAsset.lumixLabSource`／图库条目保留原始标题、作者编号／名称、说明、语言、照片风格、grid17 文件声明和原 cube 哈希。`LutGallerySource.OFFICIAL` 筛选已下载项，LOCAL 为普通本地导入；云端未下载 DTO 不能直接加入渲染配方。可下载不代表允许重新打包分发，当前没有推断再分发许可，不把创作者内容内置到 APK 或上传到另一个服务器。整套功能只有源码接口，尚未取得有效官方响应或真实 LUT 文件。

本轮证据为有限类恢复、源码实现／阅读、原 APK 与恢复文件指纹和资料更新。没有 Gradle／CMake／GLSL 编译、Lumix Lab 接口调用、实际下载／导入、Android 解码／取色／渲染、测试、基准或打包；原型仍待核心功能准备后按已授权范围打包到 dist。
