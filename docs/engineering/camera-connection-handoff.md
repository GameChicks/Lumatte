# 连接交接修正 · 2026-10-13

目标设备来自用户反馈：vivo X50 Pro+／Android 13，松下 S5。用户已进入配对页面、退出其他 Sync；第三方软件在该组合能连接。因此优先修补本应用实现，保留相机原配置及已有配对记录，不要求用户重置或删除数据。

## 新增修正

- 蓝牙扫描过程中可点击已经发现的相机。选择会结束扫描，再在同一个连接操作中执行 GATT，取消仍结束整个操作；扫描结束到连接保留参考实现的 500 ms 交接间隔。不会同时开启两个竞争连接。
- GATT 写入检查相机特征的 `PROPERTY_WRITE`／`PROPERTY_WRITE_NO_RESPONSE`，按声明选择 Android 写入方式。以前能力检查接受两者，真正写入却始终使用 DEFAULT，存在能力判断与实际操作不一致。没有额外发明系统绑定、唤醒或遥控协议。
- `WifiNetworkSpecifier` 的连接结果同时等 Wi-Fi 能力回调及可用本地地址，不把单独的 `onAvailable` 当作 IP 准备完成。网络地址等待上限 45 秒，Android 自身请求仍为 30 秒。连接交付后固定原 Network，替换网络会通知连接丢失，不静默更换请求路由。
- 新 Wi-Fi 连接的普通发现给相机传输服务最长约 25 秒启动窗口；每轮只在原选定 Wi-Fi 上发现，保留预期相机身份筛选。发送地址／路由器原有 45 秒流程不改为全网扫描。
- HTTP 文本请求增加独立取消关闭。用户取消或发现超时，会关闭对应等待的连接；不打断其他请求的 socket。会话握手失败／取消时关闭本次客户端，防止残留连接池；相机审批／busy 循环约两分钟后给出可读超时信息。这个上限是客户端等待策略，单次请求仍可能占用已设定的 socket 超时，不宣称毫秒级硬截止。

## 对照依据

第三方：`reference/base(3).apk`，既有恢复文件 `research/third-party-sync/decompiled/sources/com/lib/flutter_blue_plus/FlutterBluePlusPlugin.java`。其 1130–1186 行从 Dart 参数构造扫描设置，包含 PHY、legacy、服务／地址／名称／厂商筛选，并使用系统 LE scanner；2222 行附近由调用参数决定写入类型。未恢复 Dart 调用方的全部参数，不能凭插件支持某选项就声称业务实际采用该选项。

官方：`research/lumix-sync/decompiled/sources/p033E4/C0391e.java#m1659j` 停止扫描并等待 500 ms 后连接；`p047G4/C0647j.java` 中的 APS／旧分支身份、名字、挑战和 SSID／密码读取顺序与现有实现继续对照。没有移植旧身份、配对数据库、账号或参考应用全部通知组。

Android 官方接口：[GATT 写入](https://developer.android.com/reference/android/bluetooth/BluetoothGatt)、[NetworkCallback](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback)。前者按特征属性选择写入类型；后者说明 Android 8+ 的能力／LinkProperties 回调在 onAvailable 之后，不应在 onAvailable 中依靠同步读取判断当前属性。

本轮修补的是可定位的实现缺口；没有确认用户原始空扫描的唯一原因，也没有证明 S5 连接成功。

## 编译证据与后续

实际执行 `:core:camera:compileDebugKotlin :app:compileDebugKotlin`，Gradle 输出 `BUILD SUCCESSFUL in 45s`，84 个任务，4 执行、80 已有最新输出。日志位于忽略 Git 的 `tools/.cache/camera-connection-review/compile.log`。只是源码编译，未执行单元、回归、冒烟、设备或性能测试，未执行 APK／AAB 打包任务。编译后仅调整了一处缩进，未改变行为。

用户暂时只需提供手机型号（已收到），无需重复尝试旧包。下一次明确获准打包后，用户在原手机与 S5 上确认一次完整连接：

1. 原 S5 配对页面保持打开，手机允许附近设备及精确定位、开启定位开关，前台查找。
2. 找到 S5 后直接选择，按相机提示确认；成功读取网络后启动传输 Wi-Fi 并确认系统连接弹窗。
3. 若失败，仅反馈停留阶段、最后提示及扫描计数；无需重置相机或删除既有配对。

真实设备结果仍是必须补充的证据，源码编译不能替代。既有 UI、素材、影像功能变更、最新原型和用户参考文件均保留。
