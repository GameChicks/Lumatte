# 选卷暗盒补齐 · 2026-10-16

在功能修改及 Kotlin 编译后使用内置 ImageGen，完成剩余 11 款透明暗盒：Ultramax 400、Verita 200D、Vision3 200T / 250D / 50D、CineStill 800T / 400D、Acros 100 II、Tri-X 400、HP5 Plus 400、Delta 3200。

应用副本位于 `app/src/main/res/drawable-nodpi/cartridge_<stock_id>_v1.png`；完整提示词集为 `research/film-editorial/generated-assets-2026-10-16.json`，原件路径、应用路径、尺寸、透明通道和 SHA-256 为 `research/film-editorial/generated-results-2026-10-16.json`。原件保留，应用副本按原字节复制，未用脚本修改图片。连接失败的请求没有替换成 CLI/API 或其他模型，逐张重试内置工具后完成。

参考 Gold 200 的金属结构和光照；每卷替换纸标、名称及感光度。保持完整顶轴/上下圈、标签曲面透视、略高正面视角与透明外缘。运行时继续共用 FilmCartridge 的透视、接触及投射阴影。它们是选型插画，不作为实体官方包装认证资料。两个历史 Silver 暗盒保留给旧配方，新选型使用四款独立黑白暗盒。Portra Push 模型已移除，不再作为可选款式。

公开目录为基础 18 卷加新增 6 卷，共 24 卷；均直接映射到独立暗盒 PNG，不依赖通用纸标降级。分类卡片、滚动选卷、详情页共用同一资源映射。新增 Acros 的 II 单独作为变体排版，避免丢失 100 感光度；Ilford 名称去除品牌前缀后排版。

应用图标继续使用最初版本：前景 PNG 哈希 `F0AA07057996DC74B9227753219407E496E72F1634AF454B41B0A8FDA4737194`、4% inset、`#132D32` 背板。未生成或替换图标。

资源与 Kotlin 编译见工程修正记录；暗盒在实际手机上的缩放、透视和阴影观感未进行设备验证。
