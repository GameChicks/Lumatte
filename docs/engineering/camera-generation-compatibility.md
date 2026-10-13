# 相机代际兼容性（2026-10-13）

不能把“二代”作为协议能力判断。相机型号、固件、连接方式和实际运行证据分别记录；本机导入 JPEG、胶片/LUT 处理与相机代际无关。

| 相机 | 官方连接应用证据 | 本项目当前判断 |
| --- | --- | --- |
| S5 II / DC-S5M2 | LUMIX Sync 支持名单包含；LUMIX Lab 也包含，功能依固件 | 已列入通用连接/浏览/传输源码范围，尚无本项目实机验证，不能承诺能连接 |
| S5 IIX / DC-S5M2X | 同上 | 同上 |
| G9 II / DC-G9M2、GH5 II / DC-GH5M2 | LUMIX Sync 支持名单包含 | 同属通用源码候选，未实机验证 |
| S1 II / DC-S1M2、S1R II / DC-S1RM2、S1 II E / DC-S1M2ES | 本次官方 Lab 名单包含，Sync 名单未列出 | 现有机型范围未单独适配；需要核对 Lab 握手和浏览/传输协议，不承诺当前无线连接可用 |

“官方支持 Sync”仅能支持选择参考协议路线，不能证明自研客户端运行成功；“官方 Sync 未列出”也不证明所有旧协议必然不可用。项目 `camera-models.csv` 的 `device_verified` 当前均为 `no`。用户随后确认 S5 已能发现，但相机屏幕仍显示配对失败；完整连接、浏览和传输仍未解决，见 [配对交接修正](s5-pairing-continuity.md)。

本项目仅连接、浏览和传输，排除遥控、拍摄参数和远程电源。兼容性不能引用官方遥控功能来替代上述三个功能的证据。

来源：2026-10-13 读取的 [Panasonic LUMIX Sync 支持页](https://av.jpn.support.panasonic.com/support/global/cs/soft/lumix_sync/en/index.html)与 [LUMIX Lab 支持页及固件功能表](https://av.jpn.support.panasonic.com/support/global/cs/soft/lumix_lab/en/index.html)，结合本项目 [机型状态表](../protocol/camera-models.csv)。不新增已验证机型标记。
