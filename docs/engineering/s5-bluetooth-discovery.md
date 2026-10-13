# S5 蓝牙空扫描排查 · 2026-10-13

用户反馈：松下 S5，Android 13；已进入相机配对页面、其他 Sync 已退出，应用仍提示“未发现可连接的蓝牙相机”。手机具体型号、广播内容及系统回调没有取得。故障已确定停在 BLE 发现阶段，不能据此指向 Wi-Fi、HTTP 身份握手或照片浏览。

此前原型仅有编译与安装包结构证据，连接完成没有真实相机依据。本次不把源码实现或 APK 生成当作 S5 已连通的证据。

## 读取的依据

- 原样保留的官方 LUMIX Sync 2.0.15 APK：`reference/base(2).apk`。静态读取 manifest，`BLUETOOTH_SCAN` 无 `neverForLocation`，精确定位权限没有版本上限。没有运行参考 APK、读取旧账户／配对数据库或沿用旧身份。
- 恢复源码 `research/lumix-sync/decompiled/sources/p033E4/C0393g.java`：`m1781k1` 调用无硬件筛选的 `startScan(this)`；`onScanResult` 在回调中解析厂商广播、读取公司 ID 后的状态。其解析数组会补零，没有检查实际广播必须具有完整六字节身份。
- `p321v4/C5117b.java#m27956f`：0 为 normal、3 为 pairing；其他状态没有因此增加连接／唤醒入口。
- [Android 权限说明](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)：`neverForLocation` 可能过滤部分 BLE 广播。
- [Android 扫描接口](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner)：允许 filters 为 null，无筛选扫描在息屏时暂停。因此保持前台、屏幕亮起，不借此次修正增加后台扫描。

## 本次源码修正

1. 删除空厂商数据的硬件扫描筛选，改为短时前台扫描后在软件中筛选 Panasonic（0x003A）与 normal／pairing 状态。没有证明硬件筛选是用户手机的根因；这是去掉相较参考实现的额外依赖。
2. 只要求厂商数据包含实际读取的状态字节，不再因缺少完整六字节身份丢弃结果。发现结果只是候选，GATT 已识别服务和随后相机描述／握手仍决定身份与连接。
3. 去掉 BLE 的 `neverForLocation` 标记，查找相机在 Android 12+ 同时申请附近设备及 coarse／fine 定位权限，并检查定位开关。应用不读取位置、不申请后台定位。连接已知地址的 GATT 操作仅检查连接权限，不将定位开关作为条件。
4. 扫描等待最多 20 秒，失败提示区分完全没有回调、收到其他厂商广播、松下广播缺少状态、收到未可连接状态。保留本轮广播数量／松下数量／状态集合，不记录广播原文、地址、设备名称、密码或定位。
5. 更新界面权限说明和系统定位设置入口，避免拒绝精确定位被误报为只缺蓝牙权限。

## 尚未确认

这些修改只完成源码对照和调用方阅读，没有运行测试、编译、打包、手机扫描或 S5 GATT 连接。用户当前安装的 APK 尚未包含本次修正。原始“未发现”没有广播计数，不能确定是系统过滤、硬件筛选、广播格式还是其他原因。当前仍不能声明 S5 连接可用；下一次获准交付后，应以用户设备实际发现和握手结果确认。

只修改本次连接相关文件，既有 UI／字体／画稿变更和最新现有原型均保留。本轮没有清理缓存或重新打包。
