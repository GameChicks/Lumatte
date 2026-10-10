# 相机 Wi-Fi 安全类型

2026-10-11：手动连接入口及核心请求源码已接入；未编译、运行、测试或连接设备，未打包。

## 手动入口

“连接相机 Wi-Fi”按用户选择生成一个明确的系统网络请求，默认选中 WPA2。用户按相机显示的网络类型选择，不通过密码长度、同名扫描结果或连接失败推断另一个类型。

| 选项 | 当前输入范围 | 系统请求 |
|---|---|---|
| WPA2 | 8–63 位可打印 ASCII 密码 | `setWpa2Passphrase` |
| WPA3 | 1–63 位可打印 ASCII 密码；手机还需报告 SAE 支持 | `setWpa3Passphrase` |
| 无密码 | 空密码；切到此项时清空输入 | 不设置密码或增强开放标志 |

SSID 使用已有完整 UTF-8／32 字节验证，保留原有空格、大小写和字面引号。密码不 trim、不更改大小写、不按十六进制 PSK 解释，也不显示在错误信息中。开放模式与空的加密密码区别明确，选中 WPA2／WPA3 时空密码不会生成开放请求。

界面按类型提示输入范围，验证失败不能点击连接。打开输入框时读取 `WifiManager.isWpa3SaeSupported`；不可用或读取失败时 WPA3 项不可选，不自动改成其他类型。连接前核心再次检查能力及输入；已有 `connectWifi` 在释放网络引用前检查，新请求构造处也检查。系统仍可能因驱动、匹配、用户拒绝或厂商限制返回不可用，沿用正常失败／取消处理，本应用不换其他类型重试。

权限弹窗前临时持有输入的名称、密码及所选类型，权限结果返回时使用同一个类型，不重新推断。暂存对象为普通类，不生成包含密码的 data-class `toString`；不进入可保存的 UI 状态、Room、DataStore、日志或应用偏好。返回权限结果或关闭输入框时释放相应引用；Java 字符串由运行时管理，不承诺物理擦除。

## 蓝牙与路由器边界

蓝牙资料当前没有恢复出的安全类型字段，仍沿用已有行为：非空密码请求 WPA2，空密码请求开放网络。新增 WPA3 仅由手动输入入口显式指定，不把蓝牙连接、发送启动或发送更新自动切成 WPA3。

同路由器 STA 使用手机已经连接的 Network，相机的路由器密码在相机上设置；本次不发送、更改或读取其安全配置，也不增加相机参数控制。

网络请求仍为本地相机用途，不请求 Internet、不改变应用默认路由；后续发现与 HTTP 继续使用同一返回 Network。原有相机确认、私有／链路本地地址限制、断线暂停、自己的网络请求释放语义保持不变。

## 依据与待办

[Android WifiNetworkSpecifier.Builder](https://developer.android.com/reference/android/net/wifi/WifiNetworkSpecifier.Builder#setWpa3Passphrase(java.lang.String)) 的公开接口要求 ASCII passphrase；[WifiManager](https://developer.android.com/reference/android/net/wifi/WifiManager#isWpa3SaeSupported()) 提供 SAE 能力查询。这些 API 自 29 提供，本项目最低 API 为 30。

[AOSP WifiConfigurationUtil 当前源码](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/service/java/com/android/server/wifi/WifiConfigurationUtil.java) 在带包装引号的密码验证中分别使用 PSK 最少 8、SAE 最少 1、最多 63 的实际字符范围；[AOSP WifiNetworkSpecifier 当前源码](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/framework/java/android/net/wifi/WifiNetworkSpecifier.java) 显示公开方法保存 ASCII passphrase 并按对应类型构造配置。这里是输入与源码映射依据，不是对所有旧 Android／厂商服务的运行证明。

尚未覆盖 OWE、企业认证、WEP、64 位原始 PSK、蓝牙安全类型字段及自动匹配广告类型。具体相机是否提供 WPA3、短 SAE 密码在手机厂商系统上的行为、权限／输入界面均未运行验证，不能宣称新增选项已连接任何型号。
