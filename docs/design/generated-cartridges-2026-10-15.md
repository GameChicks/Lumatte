# 暗盒接入与生图暂停 · 2026-10-15

功能源码和小组件编译收尾后使用内置 ImageGen，共完成并接入 9 款新暗盒；没有使用 CLI / API 备选、图像编辑脚本或程序抠图。原件保留，应用副本原字节复制。

| 胶卷 | 应用资源文件，均位于 app/src/main/res/drawable-nodpi/ |
|---|---|
| Fujifilm Pro 400H | cartridge_fujifilm_pro_400h_v1.png |
| Fujifilm Provia 100F | cartridge_fujifilm_provia_100f_v1.png |
| Fujifilm X-Tra 400 | cartridge_fujifilm_xtra_400_v1.png |
| Kodak Ektachrome 100 | cartridge_kodak_ektachrome_100_v1.png |
| Kodak Ektar 100 | cartridge_kodak_ektar_100_v1.png |
| Kodak Kodachrome 64 | cartridge_kodak_kodachrome_64_v1.png |
| Kodak Portra 160 | cartridge_kodak_portra_160_v1.png |
| Kodak Portra 400 | cartridge_kodak_portra_400_v1.png |
| Kodak Portra 800 | cartridge_kodak_portra_800_v1.png |

Portra 800 的 Push 1 / Push 2 使用同一个母卷暗盒，不将推片档位误印为另一个物理感光度。原有 Gold、C200、Velvia、Vision3 500T 和两个 Silver 暗盒保持接入。其他 5 款 Ultramax 400、Verita 200D、Vision3 200T / 250D / 50D 继续使用原有标签曲面外观；用户要求暂停后不再请求生成。

新暗盒以现有 Gold 200 为几何及光照参考，保持相同立式金属结构、顶部轴芯、边缘及略高的正面视角。替换各自纸标，文字随圆柱表面变化，继续复用运行时透视和接触 / 投射阴影。它们是模拟界面的艺术表现，不作为实体产品包装的工艺或官方认证资料。

九份原图均为 1024×1536 RGBA PNG，四角 alpha=0。尺寸、哈希、原件路径及应用路径记录在 `research/film-editorial/generated-results-2026-10-15.json`；完整提示词集和 14 款计划中的完成 / 暂停状态记录在 `research/film-editorial/generated-assets-2026-10-15.json`。

用户决定继续沿用最初应用图标。其 SHA-256 为 `F0AA07057996DC74B9227753219407E496E72F1634AF454B41B0A8FDA4737194`，前景 inset 与 `#132D32` 背板均恢复。新立体图标仅作为废稿存放于 `research/film-editorial/rejected-icons/pineframe-3d-20261015.png`，未采用；扁平图标请求没有接入任何结果。

最后仅执行资源 / Kotlin 编译，不打包、不操作设备、不运行测试。实际启动器、暗盒缩放、透明边缘及阴影观感仍未实机验证。
