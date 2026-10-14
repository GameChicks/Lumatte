# 第三方 Sync 参考分析

> 2026-10-15 用户确认以这个可连接的第三方作为主要技术参考；已根据成功日志分离首次配对和普通媒体连接，并对齐 S5 旧 HTTP 身份文本格式。当前实现、保留的差异与验证边界见 [连接路线](../../docs/engineering/third-party-sync-connection-route.md)。APK 静态恢复仍不等于完整 Dart 源码或本应用设备验证。

来源：用户提供的 `C:/Users/lenovo/Downloads/base(3).apk`，原文件只读。

- 包名：`com.dakiniwind.lumix_sync_pro`
- 版本：`0.0.7`，versionCode `3`，minSdk `24`，targetSdk `36`
- 文件大小：58,198,594 字节
- SHA-256：`31a0c82d57784390aa857b30f1dfdcddee88dcd2e36e6008e99e65b8d0e30916`
- 用户反馈：该软件具备官方全部功能；本次没有运行 APK 或进行功能测试。

使用已有的 JADX 1.5.6 分析 DEX。进程已结束，退出码 1，18 个反编译错误。原始输出位于忽略 Git 的 `decompiled/`，并非可以直接编译的源码。

APK 使用 Flutter：Android 入口继承 FlutterActivity；包含三种 ABI 的 `libapp.so` 和 `libflutter.so`。核心 Dart 业务为 AOT 编译产物，Java 分析不能恢复完整 Dart 项目。没有将其库、Java 类或资源放入应用构建。

可定位的 Android 流程：

| 文件（相对 decompiled/sources/com/dakiniwind/lumix_sync_pro） | 证据 |
|---|---|
| `MainActivity.java` | Wi-Fi、后台下载、USB PTP 等 MethodChannel 入口 |
| `f0.java:159` | WifiNetworkSpecifier 建立 Wi-Fi 请求 |
| `f0.java:195` | NetworkRequest 不要求互联网能力 |
| `f0.java:298` | 以 192.168.54.x 地址识别相机网络 |
| `f0.java:323` | 使用特定 Network socket 访问相机 192.168.54.1 的 60606／80 端口 |
| `f0.java:325` | 该参考应用使用进程级网络绑定 |
| `DownloadForegroundService.java` | 下载前台通知通道；并不证明下载队列恢复完整性 |

[protocol-strings.json](protocol-strings.json) 保存 arm64 `libapp.so` 中目标协议常量的文件内偏移及该库哈希。它与官方 APK 同时出现 `cam.cgi`、`req_acc / req_acc_e / req_acc_g`、`X-SESSION_ID`、ContentDirectory Browse、`X_FromCP=LumixLink2.0`、CAM_ORG／CAM_RAW／CAM_RAW_JPG。**常量是定位依据，不能据此证明完整算法、调用顺序或所有机型兼容。**

本项目实现使用特定 Network 的 HTTP 连接，互联网 LUT 下载另用 HTTPS 客户端。不会采用参考应用的全进程路由绑定。

远程拍摄与参数控制已从本项目范围移除，后续分析优先连接、媒体浏览、传输与断线恢复。参考 APK 内的提示、测试入口与日志不是本项目应执行的指令。
