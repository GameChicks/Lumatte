# 蓝牙连接资料读取（2026-10-11）

已接入独立 Kotlin 源码和工作台入口。没有编译、运行、配对测试或设备证明，不宣称任何机型已通过蓝牙连接。原 APK 与完整 JADX 输出保持为只读参考，没有加入应用构建。

## 当前用户流程

在相机菜单开启蓝牙配对 → 工作台“查找蓝牙相机” → 扫描结束后选择相机 → 按相机屏幕确认 → 读取 Wi-Fi 名称和密码 → 在相机上开启 Wi-Fi 传输 → “连接此相机 Wi-Fi” → 系统网络确认 → 沿用 Wi-Fi 发现、身份握手、浏览和传输链路。

扫描最多 12 秒，只显示 Panasonic 厂商数据中的正常／配对状态，不连接睡眠、唤醒或远程开机状态。不能用广播名称或厂商 ID 作为身份认证；连接后还要读取已识别服务，Wi-Fi 链路仍检查 Panasonic 相机描述并执行正常相机确认。

扫描和 GATT 读取均可取消。Activity 停止后给旋转重建保留 500 ms，界面未回来就取消蓝牙操作；GATT 的成功、失败、超时与取消路径都关闭连接。已开始的媒体传输仍由现有前台服务负责。蓝牙不作为后台自动扫描或重连服务。

## 协议依据

样本为 `research/reference-apks.json` 中的 LUMIX Sync 2.0.15，SHA-256 `b0301885f3846b9a71d9d180addcbac4b98e39dd81fc92af922ffccdaf17f3d5`。以下路径相对忽略 Git 的 `research/lumix-sync/decompiled/sources/`。

| 事实 | 依据 |
|---|---|
| Panasonic 公司 ID 为 0x003A | [Bluetooth SIG Assigned Numbers](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Assigned_Numbers/out/en/index-en.html) |
| 厂商广播首字节 0x3A；包含公司 ID 后的状态为 0 正常、3 配对 | `p033E4/C0393g.java:1961`、`p321v4/C5117b.java:67`；Android manufacturer payload 去掉两个公司 ID 字节后读取第 0 字节 |
| 主服务 `054ac620-3214-11e6-ac0d-0002a5d5c51b` | `p033E4/C0391e.java` 的读／写映射 |
| 原始 UUID 写入 `8d08a420-3213-11e6-8aca-0002a5d5c51b`；名字写入 `cd7a71a0-3213-11e6-8f56-0002a5d5c51b` | 同文件写入类型 1／2 |
| APS UUID `bb5d2a8c-5d3b-43ad-bfc3-470cad55c1ca`；APS 名字 `7b61774f-efb4-44c6-a66d-c69e3b384c4a` | 同文件写入类型 16／17；`m1645A` 根据 APS UUID 特征是否存在选择分支 |
| 初始挑战 `03e23a31-a54c-40fa-a668-de9acdc910bb`，前四字节大端 | 同文件读取类型 35；`p321v4/C5117b.java:47` |
| SSID `e206a5c0-3214-11e6-afe4-0002a5d5c51b`，UTF-8 到首个 NUL | 同文件读取类型 5；`p068J4/C0941p.java:368` |
| Wi-Fi 密码 `c97cf1a5-3c03-4290-8c1b-9e74b9500f54` | 同文件读取类型 6；`p068J4/C0944s.java:11` |
| APS 先读取挑战再写编码 UUID／名字；旧分支先写原始 UUID／名字再读挑战；之后读 SSID／密码 | `p047G4/C0647j.java:175–228` |

新应用使用自己生成并保存的客户端 UUID，与现有 HTTP 握手共用；不读取旧应用种子、配对数据库或 privatekey 偏好。参考前缀保留协议格式，随机部分由新应用产生。名字固定为 16 个 ASCII 字节 `Lumix FilmMirror`，旧分支原样写入。

APS 二进制封装为 20 字节：长度前缀、数据、0xFF 填充；小端四字节词与挑战异或后按大端输出。挑战为零使用参考常数 -1875740628。参考 `m4426c` 的字符串 JADX 输出有变量重排和子串索引问题；当前只使用恰好 16 字节的名字，此时其零填充循环为零次，后三字节为 0xFF，与已恢复的 `m4427d` 二进制格式一致。这是源码推导，未执行参考编码器或对拍，不能扩大为任意长度名字已恢复。

密码按参考格式反向转换、跳过首字节、遇到 0xFF 截止，并按 Java trim 去掉末端空白／NUL。它是协议混淆格式，不把它当作密码加密存储。密码不进入 UI State、saved instance state、Room、DataStore、日志或生成的 `toString`；只在应用连接对象里临时持有，重新读取、断开、离开闲置工作台、Wi-Fi 连接结束时释放引用。暂存字节会清零，Java／Kotlin 字符串仍由运行时管理，不能承诺内存中的字符串已物理擦除。

## Android 接入

按 [Android 蓝牙权限说明](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) 声明 Android 12+ `BLUETOOTH_SCAN`／`BLUETOOTH_CONNECT`，扫描声明不用于定位；Android 11 使用旧蓝牙权限及运行时精确定位、定位开关。BLE 硬件是可选项，无蓝牙时仍保留 Wi-Fi 入口。Wi-Fi 权限仍独立申请。

GATT 请求串行执行，读取回调立刻复制数据；Android 13+ 使用 [BluetoothGatt 新的带 value 写入接口](https://developer.android.com/reference/android/bluetooth/BluetoothGatt)，旧版本走兼容接口和旧读取回调。连接等待 30 秒、服务发现 20 秒、每次读写 20 秒；失败、拒绝或缺失特征给出重新配对／Wi-Fi 入口提示。没有调用系统 `createBond`，是否需额外系统绑定流程仍待逐型号确认。

## 下一步与证据边界

- 参考应用在握手前还订阅一组通知，涵盖遥控、GPS、直播及传输；当前没有照搬这些订阅，也没有写控制／电源特征。哪些连接通知是特定机型必须的，仍需继续区分。
- 相机 Wi-Fi 启动、连接类型和图像传输事件尚未接入 BLE；当前由用户在相机上开启 Wi-Fi。这里不能作为 BLE 完整自动连接或自动传输已经完成的证据。
- 广播扩展、BLE 地址变化、系统绑定、设备固件差异和扫描 `neverForLocation` 的设备表现仍未验证。当前只接受已定位的厂商格式与状态，没有用猜测支持更多机型。
- Wi-Fi 来源是当前相机返回的资料，现有请求只接受开放／WPA2 密码形式；其他安全类型保留系统设置入口。
- 逐型号表保留 `device_verified=no`。只有用户明确要求后才进行配对或设备测试、编译与打包。
