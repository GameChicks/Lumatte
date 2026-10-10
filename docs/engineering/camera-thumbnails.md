# 相机浏览缩略图（源码接入）

第二阶段的普通相机目录在文件名旁显示 64 dp 预览。仅可见列表项、页面至少 STARTED、连接就绪且未执行其他操作时加载；LazyColumn 预取项不主动下载。待发送接收模式不请求预览，避免增加接收和确认时序中的请求。

## 资源与内存

- 仅使用目录中已有的 `CAM_LRGTN`，缺少时使用 `CAM_TN`。没有缩略图或请求失败显示占位，不下载原图、RAW 或原视频代替。
- HTTP 沿用当前相机的所选 Wi-Fi、同主机地址校验、X-SESSION_ID 和禁止重定向策略。仅接受 HTTP 200 的完整非空响应；已声明尺寸和实际流式字节数分别限制为 4 MiB，已知 Content-Length 必须与收到的字节数一致。
- 先读取 JPEG 尺寸和类型，再按 2 的幂采样到最长边不超过 384 像素；只接受 JPEG、单边不超过 16384、源像素数不超过 6400 万。解码在 IO 调度器上执行。
- 单个加载通道串行下载和解码，下载含等待相机命令锁上限 6 秒。位图 LruCache 按 allocationByteCount 限制 8 MiB；已显示位图、下载缓冲区、解码临时分配不包含在这个缓存额度内。缩略图不落盘、不进入照片记录，不改变传输资源选择。
- 失败标记最多保留 128 项，减少来回滚动时反复请求失败资源；刷新目录或重新连接会允许重新加载。

## 会话与取消

控制器在开始普通操作／传输和最后一个页面离开时取消预览请求；行不可见或页面停止时 Compose 的加载协程取消。取消只断开该预览自己的 HTTP 连接，不调用会话的 interrupt，不会直接断开其他下载或命令。预览同时服从相机会话命令锁。

普通操作完成后增加加载轮次，重新启动尚未完成的可见预览；即使一次操作过快、Compose 没有观察到忙碌状态，也能恢复被取消的加载。加载轮次不参与缓存键，已经显示的位图保留，原有缓存可复用。

完整目录刷新、切换文件夹、断开会话会清空缓存并增加版本号。缓存键包含版本和完整媒体信息；返回界面前再次检查会话对象、版本、当前目录项、前台状态及忙碌状态，避免相机复用资源 URL 或媒体 ID 时显示旧图。追加同一个 updateId 的分页保留缓存；目录版本变化仍沿用原有刷新提示。

## 证据与待办

接口来源是现有目录解析中的资源 profile。位图采样和缓存采用 [Android 位图加载文档](https://developer.android.com/topic/performance/graphics/load-bitmap) 与 [LruCache 文档](https://developer.android.com/reference/android/util/LruCache)；前台状态使用 [Lifecycle 的 Compose 集成](https://developer.android.com/topic/libraries/architecture/lifecycle)。取消监视采用标准协程 [awaitCancellation](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/await-cancellation.html) 子任务，只释放当前连接。

尚未编译、运行、测试或打包。真实相机的缩略图尺寸／类型、HTTP 取消响应、不同机型是否已旋正预览及 EXIF 方向差异、缓存与快速滚动表现仍待用户明确要求验证后确认。此入口仅用于最小照片浏览，不代表第四阶段的正式图库界面已完成。
