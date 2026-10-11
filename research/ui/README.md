# 前端参考资产与副本来源

2026-10-12：本轮用户重新直接要求实施正式 UI。指定字体与 Playfair / Dancing Script 的选定副本已经接入 core/design，参考原件仍保留。本轮副本清单为 fonts/app-font-manifest.json，原清单不改写；完整来源与许可状态见 ../../docs/design/assets.md。下文“未加入构建”是 2026-10-11 交接时的历史状态。

用户 2026-10-11 指定字体，随后明确 UI 由另一个前端负责。字体从 Windows 字体登记解析到 `C:/Users/lenovo/AppData/Local/Microsoft/Windows/Fonts/`，仅复制指定文件；原件未修改。`fonts/font-manifest.json` 记录副本字节和 SHA-256。

| 文件 | 角色 | 当前许可依据 |
|---|---|---|
| oppo_sans_light／regular／medium／bold／heavy.ttf | 正文家族 L/R/M/B/H | 本机文件标注 2019 OPPO / Hanyi；[ColorOS 官方](https://www.coloros.com/article/A00000050/) 回顾 2019 开放商用授权。具体本机版本的完整条款仍需随正式分发记录 |
| nhl_minnesota.ttf | WELCOME | 本机名称表确认为 NHL Minnesota，未含可确认的许可正文 |
| dotmatrix_chinese.ttf | 中文点阵 | 本机名称表为 AaSpot，含 Apache-2.0 文本；[Aa 官方列表](https://www.mfont.com/brand-aaziku-1.html) 对波点的商用授权信息与该记录需要进一步核对，不能只凭名称表认定公开嵌入许可 |
| editorial_serif.ttf | 可选艺术混排 | 从已恢复的自有 Film Companion 中取得 Playfair Display Regular，名称／版权记录与附带 OFL 一致；原件保留在 `research/filmtool/` |

以上是用户指定本机素材的恢复记录，不是对字体重新授权。现阶段仅供前端参考，不加入 App assets／res；公开分发前补齐 NHL 和波点的具体嵌入许可记录。

UI 演示原件保留在项目 `reference/`，本次没有改写、移动或删除。功能接口及用户最新设计要求见 [前端交接](../../docs/engineering/frontend-contract.md)。
