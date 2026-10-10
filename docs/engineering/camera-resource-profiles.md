# 相机目录资源标识

第二阶段源码记录。`CameraXml` 使用独立的 `CameraProtocolInfo` 解析资源说明，`CameraResource` 再决定照片、RAW JPEG、缩略图和视频用途。

## 格式依据

- 常规格式是四段 `protocol:network:contentFormat:additionalInfo`，只在前三个字段分隔冒号处分段，保留第四段的完整附加信息。扫描分隔符时跳过带引号的值，三段兼容格式中带引号名称的冒号不会被当作第四段起点。
- 提供的官方 Sync 2.0.15 的 `p258m4/C4667e.java`、`m25825a` 还有三段分支，第三段拆出 MIME 与分号附加字段。本项目现在接入这条兼容路径；它的存在不证明某个具体型号／固件必然返回该格式。
- 官方解析对 MIME 和已知 JPEG／RAW／HSP／缩略图标识采用 `equalsIgnoreCase`。本项目分类统一去除标识外围空白、以 Locale.ROOT 大写进行比较；视频前缀、IFRAME 排除及 `_ORG` 优先选择使用同一规则。资源记录仍保存解析得到的原始 profile 和 MIME，不重写既有队列。
- `contentFormat` 比较采用 MIME 的类型部分，排除类型参数和外围空白；不会只凭 `image/jpeg` 把一个没有已知原图标识的资源当作原图。RAW 的来源说明仍由 RAW_JPG／HSP_RAW_JPG 分支决定。

四段格式及大小写比较依据 [UPnP ConnectionManager 规范](https://upnp.org/specs/av/UPnP-av-ConnectionManager-v3-Service.pdf)。三段分支及 Panasonic 标识以本次只读官方 APK 反编译结果为直接依据，位于忽略 Git 的 `research/lumix-sync/decompiled/sources/p258m4/C4667e.java`，不复制整个类或加入官方二进制。

## 附加字段

解析键值以第一个等号分离，字段名去除外围空白并统一大小写。普通值在分号处结束；单／双引号包裹的值保留内部的分号、等号和空白，只移除一对外围引号。未闭合的引号保留剩余值为不透明字段，不反复把其后缀拆为新属性。`OriginalFileName` 用于名称显示和重连核对，不作为本地写入路径。缺少字段、未知 profile 或不完整引号不会从扩展名推断原图身份。完整浏览响应的 XML 与资源说明解析已切到 Default 调度器，取消后过期解析结果不会返回界面。

预览继续只选 CAM_LRGTN／CAM_TN，照片继续按原 JPEG → RAW JPEG → RAW 文件提取预览的优先级选择。IFRAME 不作为完整视频；未确认的 CAM_MPO／CAM_MPO_JPG 仍未接入立体照片处理，不将它们猜作 RAW 或普通原尺寸照片。

## 视频项目与封面

`CameraXml` 现在读取 UPnP 命名空间的 `class`，每个 item／container 独立保存并在下一项重置。`object.item.videoItem` 及以点分隔的派生类明确标记为视频项目，不能只因其资源里出现 CAM_ORG JPEG 就转为可编辑照片。缺少 class 时保留已知视频资源的识别；class 不从标题、文件扩展名或非 UPnP 命名空间的同名元素猜测。

官方 `C4667e.m25825a` 对 application/octet-stream 先检测标识中是否包含 IFRAME，再判断 CAM_AVC 容器家族。因此本项目增加这条独立预览识别：即使标识没有 CAM_AVC 前缀，也归入视频资源，但 `videoContainer` 仍返回空，不将其当作完整 MP4／MOV／TS。

视频项目仍只选择已支持容器的资源，优先 `_ORG`。只有 JPEG 封面、独立 IFRAME 或不支持容器时，普通列表显示“未提供可传输的完整视频资源”，不可勾选；待发送目录首项遇到这种情况暂停，不导入封面、不跳过该项、不确认已保存。真正的视频继续原字节保存到相册。标准依据为 [UPnP ContentDirectory 的视频类与派生类](https://www.upnp.org/specs/av/UPnP-av-ContentDirectory-v4-Service-20101231.pdf)；独立 IFRAME 依据为只读官方 APK 源码。

媒体 class 是本次目录读取的元数据，不新增数据库列或改写既有传输记录。队列保存的是已经选定的实际资源；重新浏览的视频项目若不再提供完整资源，现有匹配无法通过，不能用封面接续原视频任务。历史已保存结果保留；新的识别不能追溯证明旧文件来源。没有执行任何目录样例或设备请求。

## 验证边界

本轮只做源码和参考资料审阅，未编译、执行解析样例、运行测试或打包。尚需实际目录响应确认三段格式、引号写法、各型号广告的资源 profile 和视频完整性；该兼容实现不代表所有相机已适配。旧队列若曾记录被截断的名称，重新浏览后的真实名称不一致仍触发既有提示，不静默改写旧任务身份。
