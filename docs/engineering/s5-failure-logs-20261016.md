# S5 用户失败日志修正

依据：用户提供的 `ConnectLog-20261015_235824_262.log.txt` 和同名 `(1).txt`。原件保留在 Downloads，不将连接身份、凭据或原始响应提交 Git。手机为 vivo V2011A / Android 13，应用 0.1.9（10），连接路线 sync-trace-v3。

## 可确认的失败位置

- 蓝牙扫描、GATT 连接、服务发现、旧身份写入和 SSID / challenge / password 读取均成功。随后在 READING_WIFI 阶段出现 IllegalStateException；没有任何 DESCRIPTOR 操作，也没有 STARTING_WIFI 或模式 5 写入。与源码 `withTransferWifi` 的启动能力门槛相符，尚不能从旧日志确认具体缺少哪个属性。
- 手动 Wi-Fi 能发现相机并收到 HTTP 200；型号日志为 other，旧协议一直以带连字符 UUID 进入等待状态，最后相机回复 err_user_refused。这证明相机尚未批准请求，不能认为已完成握手，也不能断言该状态一定由身份格式引起。

## 修改

1. 普通 SoftAP 启动能力只要求已知 Wi-Fi 类型与连接模式特征可写；可选状态通知不再阻止启动。支持 WRITE 与 WRITE_NO_RESPONSE，和实际写入函数保持一致。相机成功仍须经过 Wi-Fi 和 HTTP 授权，缺少通知不被当作成功凭证。
2. 通知能力按实际描述符判断，兼容已有描述符但未声明 NOTIFY 的旧固件；仅声明 INDICATE 时使用对应订阅值。路由器选择与连续发送需要的事件能力仍单独检查，不绕过其等待步骤。
3. UPnP 解析 modelNumber，优先采用合法 DC-/DMC- 型号。官方恢复的 C4666d 同时解析 modelName / modelNumber；前者可能只是通用产品名称。S5 身份格式选择也依据本次 HTTP 描述重新确认，不仅依赖调用前的候选资料。
4. 日志增加固定连接特征的属性位、描述符有无、普通启动/路由器/接收能力、读取负载字节数，以及本项目异常发生的类/方法/行号。没有记录凭据值、原始 payload 或异常消息。

参考：官方恢复 C0393g.m1763T0 依据描述符订阅，不要求 NOTIFY 属性；C0391e 的模式与 Wi-Fi 类型写入 UUID 与现有映射一致。第三方成功轨迹仍用于首次模式 5、后续模式 3 与 HTTP 授权顺序。

## 交付边界

修改前 Git 快照 360d881 及 dist 中 20261015-235532 的 0.1.9 APK 已交付，该包不含本次修正。core:camera 与 app Kotlin 编译通过；未运行单元、回归、设备、图像或性能测试，未再次生成 APK。S5 成功连接仍待用户在未来明确要求的新包上确认。
