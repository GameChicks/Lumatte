# 第三方 BLE 插件层静态对照

2026-10-13，继续读取用户提供 APK 已恢复的 `decompiled/sources/com/lib/flutter_blue_plus/FlutterBluePlusPlugin.java`。这不是 Dart 业务源码恢复，也没有运行 APK 或导入第三方账号、配对记录。

- `lambda$onMethodCall$3`（约 1130–1186）：扫描模式、legacy、PHY，以及服务 UUID、地址、名字、广播数据类型、厂商数据和服务数据筛选均来自调用方参数；最后调用 `BluetoothLeScanner.startScan`。插件具备的选项不能证明应用最终传入的值。
- GATT 写入分派（约 2222）：`write_type` 来自调用方，按其设置写入类型。对照发现本项目此前能力判断支持 WITH／WITHOUT RESPONSE，实际写入始终 DEFAULT，已按特征可写属性修正。
- 官方 `C0391e#m1659j` 的停止扫描／500 ms 交接、本项目已恢复的 APS／旧身份读取流程也继续用于对照；没有因第三方 AOT 常量存在就移植未经恢复的完整业务流程。

当前能证明插件的结构和本项目源码修正，不证明第三方的全部实际参数或新实现与 S5 的设备兼容。编译及后续设备证据边界见 `docs/engineering/camera-connection-handoff.md`。
