# 第三方 Sync 作为主要连接参考

2026-10-15 用户明确选择已在其 S5 上验证可连接的第三方 Sync 作为主要技术参考。成功行为优先依据用户提供的连接轨迹；APK 的静态源码用于解释 Android 接入，官方 Sync 源码用于补齐或交叉核对日志未给出的字段。来源为 [第三方 APK 恢复记录](../../research/third-party-sync/README.md) 与 [成功日志分析](s5-success-log-pairing.md)。

原 APK 恢复记录为 0.0.7；连接日志未标明应用版本，不把这两种证据自动当作完全相同的业务版本。

## 技术落点

第三方 APK 使用 Flutter，蓝牙由 FlutterBluePlus 调用 Android BLE，Wi-Fi 由原生 MethodChannel 调用 `WifiNetworkSpecifier` / `ConnectivityManager`，相机授权与媒体目录使用 HTTP / UPnP SOAP。核心 Dart 业务是 AOT 编译产物，Java 恢复不是完整 Dart 源码。采用它的协议和连接顺序，不把现有 Kotlin/Compose 工程重写成 Flutter，也不装入它的二进制库、配对身份或私有数据。

本项目继续使用 Android GATT 与 Wi-Fi Network 请求实现相同层级的连接。第三方的全进程网络绑定不适用于本应用同时访问相机和 HTTPS LUT 的需求，相机 HTTP / UDP 继续绑定所选 Network，互联网客户端独立走 HTTPS。相机地址限于已选择网络的私有/链路本地设备；不是从手机默认互联网路由发送相机命令。

## 配对与使用分开

之前一条 `withTransferWifi` 的完成回调同时做 HTTP 授权、播放模式、资源初始化和目录浏览。第三方成功轨迹则在首次 `req_acc` 返回 `ok` 后结束配对，后续连接使用模式 3。因此首次登记不应因照片目录尚未就绪而被误报成整个配对失败。

当前 `CameraController.connectBluetoothTransfer` 分两段：

1. 如果相机正在 pairing 广播状态，读取当前资料、发送模式 5、请求 Wi-Fi、执行通用相机会话授权；成功后只记录相机身份及 `bluetoothPairingCompleted=true`，关闭本次 HTTP/网络请求，结束初次 GATT。该阶段不发播放模式、RAW 设置、目录或缩略图请求。
2. 随后在同一个用户发起的操作里，保留 500 ms 交接间隔，重新建立普通 GATT、重新读取本次 Wi-Fi 资料、发送固定模式 3、请求新 Wi-Fi 会话、重新授权、进入播放模式、读取目录。普通连接校验与首次授权相同的相机身份，预期普通浏览访问模式。

normal 状态直接走第二段，不自动发送模式 5。广播 pairing 只选择路径，不作为配对成功凭证。第一段成功、第二段失败时，状态保留“首次配对已完成”的检查点，但 `connected` 仍为 false；不把配对和媒体可用混为一条成功状态。没有新建配对数据库或后台自动重连。

第三方日志能确认阶段分离、首次模式 5 和后续模式 3；它没有明确记录后段是否复用同一 GATT。本项目为清除阶段状态、重新读取资料而使用新的 GATT，属于独立适配选择，不能称为已逐调用复现或设备验证完成。后段日志也没有最终连接完成和目录证据。

## S5 身份表示与握手

S5 旧 HTTP 路径使用第三方成功轨迹中的 **32 位无连字符 UUID 文本格式**，但其内容来自本应用自己生成并保存的 UUID。BLE 仍使用同一个 UUID 的 16 字节值，不借用第三方手机标识，不修改已有本应用偏好。

这个格式仅在解析出的实际型号为 `DC-S5` 且 HTTP 不要求 challenge 时生效。HTTP challenge 分支及其他机型保留原有格式与编码，不根据广播名称猜测型号。`LumixCameraSession.connect` 留有 `compactLegacyIdentity` 参数，方便后续有证据时扩展；不是随机切换身份或循环尝试多个账号。

`req_acc` 是共享遥控/浏览会话授权。保留这个入口，普通媒体连接授权后执行 `playmode`，不加入实时取景、快门、录像、曝光、对焦、参数复制或电源操作。Wi-Fi 类型仍按官方首次直连分支使用 SoftAP 1；第三方日志的文字 `sta` 没有实际 cmd 11 字节，不能据此发送路由器资料。

## 等待、取消与诊断

沿用系统 Wi-Fi 90 秒、地址准备 105 秒和整次蓝牙连接 5 分钟的上限；系统确认切后台不会触发 500 ms idle cancellation，显式取消继续结束 GATT / HTTP / Network。两个阶段串行、每阶段单个 GATT，不并发竞争连接。初次阶段释放网络时先清除本应用所有权，避免自身的延迟 onLost 回调取消后续阶段。

`LFMCamera` 日志记录 `connectionFlow=sync-trace-v3`、registration / media-connect 阶段、授权状态及 `identityFormat`，不记录 UUID 值、密码、SSID、相机地址或会话 ID。前端可读取 `CameraWorkspaceState.bluetoothPairingCompleted`，现有 `connected` 仍表示目录初始化完成；本轮未重做前端。

## 验证状态

修改前基线 `7229c18`。`:core:camera:compileDebugKotlin` 和 `:app:compileDebugKotlin` 编译通过（首轮 57 秒，状态提示收尾后 app 增量编译 30 秒）；差异格式检查通过。本轮未执行设备、单元、回归、冒烟或其他测试，未生成 APK/AAB。第三方在用户设备上的成功不等同于本应用也已通过；当前安装包不会自动得到源码修正。
