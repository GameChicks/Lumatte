# 相机局域网 IPv6 路径

当前仅完成源码接入。没有编译、运行发现／HTTP 请求、执行样例或测试，也没有打包；不能据此宣称任何实际相机支持 IPv6。

## 地址与接口

相机地址使用自行解析的 IP 字面量，不查 DNS。IPv4 保留私有／链路本地范围；IPv6 仅接受 ULA `fc00::/7` 与链路本地 `fe80::/10`，排除公网、回环、组播、未指定地址、IPv4 映射和旧站点本地范围。

Java URI 的 IPv6 host 包含方括号；URI 中的作用域标记 `%25` 与普通 socket 地址的 `%` 分开处理。接口名称或索引必须与 `ConnectivityManager.getLinkProperties(所选 Network).interfaceName` 对应；缺省的链路本地作用域继承该接口，冲突作用域拒绝。描述地址保存为带索引的 URI，资源地址可以省略作用域，但必须拥有相同的地址字节。替换 authority 保留 raw path／query，避免对已有转义再次编码。

Wi-Fi 选择列表同时展示系统报告的 IPv4／IPv6 本机地址；本机地址展示不代表相机发现或连接成功。IPv4 的 HTTP authority 也使用规范数字形式，避免不同 HTTP 实现对前导零解释不一致。

## 发现

IPv4 `239.255.255.250:1900` 与 IPv6 `[FF02::C]:1900` 同时查询，各自维持约三秒窗口与一次重发。IPv6 只在所选接口具有链路本地地址时启用：socket 的本地地址和组播目标均显式带该接口索引，绑定所选 Network，设置组播接口和一跳范围。没有某种地址族或该族发生 IO 错误时，保留另一种地址族的结果和已有网关补充路径。

SSDP LOCATION 仍要求 HTTP 200、MediaServer 标识以及响应发送者与 LOCATION 的数值地址一致。只读描述之后才显示相机；UDN 按既有方式去重。未扩大为任意子网扫描。

## HTTP

IPv4 保留 `Network.openConnection`。Android 系统 HTTP 的 IPv6 解析不支持作用域标识，因此 IPv6 使用 OkHttp 4.12.0 和小型 `HttpURLConnection` 适配器，供现有 XML、缩略图、断点传输共用。

OkHttp 对数字地址会绕过自定义 DNS；为确保 socket 收到带作用域的 `Inet6Address`，IPv6 传输内部使用固定 `camera.invalid` 路由名，自定义解析器只允许该固定名并返回已验证的地址。它不会调用系统 DNS；HTTP Host 明确使用真实、无作用域的方括号 IPv6 地址和非默认端口。socketFactory 来自所选 Network，关闭代理、重定向与连接失败自动重试；相机请求不会转到默认网络或互联网。内部路由名不进入协议 URI、HTTP Host、资源记录或用户界面。

适配器只实现当前相机链路所用 API：请求头、小型固定长度 XML 请求体、状态／响应头／长度、流式响应和 disconnect。XML 请求体上限 16 MiB，照片／视频响应继续按原有流式下载，不载入请求体缓存。取消会调用本次 Call.cancel 并关闭响应；连接池不保留空闲连接，关闭会话时再取消其请求并清理池。X-SESSION_ID、Range／If-Range、Accept-Encoding 和成功发布路径仍由既有调用层处理。

另在每次连接的网络拦截器中限制只发送一次：OkHttp 4.12 的 `503 Retry-After: 0` 跟进逻辑没有检查连接失败重试开关；相机 GET 可能改变状态，因此第二次发送之前直接返回 IO 错误，由现有队列和确认歧义处理决定后续，不在 HTTP 层偷偷重发确认命令。

## 保留边界与后续

蓝牙相机发送地址字段仍限定既有 IPv4 格式，未因 HTTP 适配擅自扩大格式；AP／STA 的蓝牙启动顺序未变。IPv6 相机功能范围仍只有连接、媒体浏览与传输，不加入遥控。

真实 Android URI 行为、接口索引／网络切换、SSDP 组播接收、相机 Host 兼容性、取消时序、SOAP／JPEG／RAW 内嵌 JPEG／视频传输、断点续传及更多设备描述差异均待后续明确授权验证。没有性能数据，也没有已通过设备清单。

## 参考依据

- [UPnP Device Architecture 1.1](https://openconnectivity.org/upnp-specs/UPnP-arch-DeviceArchitecture-v1.1.pdf)：IPv6 SSDP 链路范围发现。
- [RFC 4193](https://www.rfc-editor.org/rfc/rfc4193.html)：IPv6 ULA 地址范围。
- [Android URI](https://developer.android.com/reference/java/net/URI)：IPv6 host 方括号及 host 转义保留。
- [Android Network](https://developer.android.com/reference/android/net/Network)：绑定 socket、获取指定网络的 socketFactory 和 openConnection。
- [AOSP HttpUrl](https://android.googlesource.com/platform/external/okhttp/+/master/okhttp/src/main/java/com/squareup/okhttp/HttpUrl.java)：系统 HTTP 地址解析的源码限制。
- [OkHttp 4.12 RouteSelector](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/connection/RouteSelector.kt)：数字地址绕过自定义解析的源码行为。
- [OkHttp 4.12 RetryAndFollowUpInterceptor](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt)：503 响应跟进的源码行为。
