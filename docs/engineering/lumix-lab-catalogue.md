# Lumix Lab 官方 LUT 目录研究（2026-10-11）

本轮对用户提供的 `reference/base.apk`（Lumix Lab 3.1.0）重新读取 DEX 元数据，并用本机已存在的 JADX 1.5.6 仅恢复选中的网络及请求数据类。APK 当前 SHA-256 与已有记录相同：`262335c3032c82fc966812e84803551cffd215d5125fc334fffa221e5be91824`。原件不改写，不运行 APK，不读取旧数据库、账户、密码或签名材料。

恢复文件只放 D 盘忽略缓存 `tools/.cache/lumix-lab/`，不加入应用，也不将其中的命令作为操作指令。结构化、限定社区范围的证据与源文件哈希保存到 [请求资料](../../research/lumix-lab/api-source-snapshot.json)。其中不保存账户数据或请求令牌。

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

列表 body 已定位 `countries` 字符串数组、`creator_code`、`genre_code`、`key`、`language`、字符串形式 `limit/offset` 和 `order` 对象；order 的成员是 `key/order`。详情 body 为 `code/key/language`，ZIP 下载为 `code/key`。热门目录 `date_range/genre_code/language/region` 是字符串，limit 是整数。字段名称与声明类型来自恢复源码，**尚不能据此确定哪些必填、取值范围或默认值**。

此前记录的匿名 GET 400 不证明上述 POST 流程不可用，这只是根据请求定义作出的判断。当前没有再次发网络请求，也没有建立可用官方客户端，不称目录、图片或下载已经成功。下一步继续读取实际调用方如何提供 key／语言／地区、返回数据及 HTTPS 图片来源，确认正常访问条件；不猜测账户令牌或改用旧应用数据。

## 与已实现功能的关系

本地两槽 LUT、当前照片卡片、Palette／颜色家族和题材筛选继续独立使用，不依赖官方服务。新的取色入口能处理合法取得的真实创作者软件缩略图，并标明 CREATOR_EXAMPLE；获取这些图与官方目录仍是缺失功能，不用用户照片冒充。

未来 Internet 客户端必须逐次确认 HTTPS，包含重定向与例图／文件下载；不能借相机全局 cleartext 许可接受 HTTP，也不绑定相机 Wi-Fi。主动下载 LUT 与可删除的例图缓存分开，离线配方仍按本地稳定 ID 引用，来源／作者／照片风格与再分发许可另外保留。ZIP 成员和 VLT 格式尚未恢复，不承诺当前 `.cube` parser 能直接导入官方 ZIP／VLT。

本轮只有反编译、源文件／原 APK 指纹读取和资料生成证据。没有 Android 编译、网络接口调用、下载、照片运行、测试、基准或打包。
