# 变更记录

## 未打包 · 2026-10-16

- 新增 CineStill 800T、400D，及 Acros 100 II、Tri-X 400、HP5 Plus 400、Delta 3200 四款黑白移动模型；四款黑白替换新选型目录中的 Silver 两卷，旧配方仍可渲染。
- 两款 CineStill 默认开启独立返光，其余新配方默认关闭；普通卷使用 SpektraFilm 默认返光参数与散射结构的移动近似。保留单一曝光及独立黑柔控件。
- 修正可选 BLE 通知阻断普通 Wi-Fi 启动、WRITE_NO_RESPONSE 能力判断和 UPnP modelNumber 解析；增加固定特征能力与异常位置日志。详见 `docs/engineering/s5-failure-logs-20261016.md`。
- 源码／资源改变不代表实机连接、照片效果或耗时已经验证；本轮没有运行测试或重复打包。
- 六款模型资源已生产；两种 ABI 原生库、着色器语法、media / camera / app Kotlin 编译通过。

## 修改前备份 · 0.1.9 / 10

源码 360d881；交付 `dist/lumix-film-mirror-prototype-0.1.9-20261015-235532-debug.apk`。签名、版本和 ZIP 16 KiB 对齐核对通过。完整元数据见 `dist/pre-six-stock-0.1.9-release.json`，未进行设备或回归验证。
