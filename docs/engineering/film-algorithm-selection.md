# 手机胶片算法选型（2026-10-11）

决定：采用 **spektrafilm 模型来源＋密度域预计算＋有界空间效果** 的移动端管线，以 Filmify 的分阶段处理结构作为主要工程参考。Android 实现优先原生 OpenGL ES 3 的分块渲染，同一模型提供分块 CPU 后备；现有 SpektraLab 完整 Vulkan 管线保留为数值参考和研究路径，不把它作为普通手机全图处理的唯一实现。

这是用户授权技术取舍后的工程决定。20 个三阶段数据包已生产，默认源码接 GLES 图块扫描及同模型原生 CPU 后备，包含有界黑柔／Halation 与近似密度颗粒；GPU 失败由 writer 重新解码原始 JPEG 再做 CPU。没有编译或运行，没有测得耗时或数值误差。完整 Vulkan 独立保留开发参考，普通构建不带其库和数值资源。前端不增加引擎选择或专业参数入口，默认尺寸仍为有效输入的 100%。

## 本次读取的证据

以下版本通过 GitHub 仓库／提交／文件树 API 定位，并下载指定提交的源文件，只读取代码和许可，没有安装或运行项目。来源与文件哈希见 源码索引，缓存放项目 `tools/.cache/film-algorithms/`，不加入 Android 构建。README 中的运行命令及验证结果只是上游资料，没有执行，也不作为本项目通过的证据。

| 项目与固定版本 | 实际实现与手机取舍 |
|---|---|
| [spektrafilm](https://github.com/andreavolpato/spektrafilm/tree/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc) | Python 参考模型把感光、显影耦合、颗粒、印相与扫描分开；自身已有光谱转换查表服务。保留为胶卷响应来源和参考，不直接把 Python 全图数组及桌面依赖搬入 Android。 |
| [Filmify](https://github.com/oudelabs/filmify/tree/133dbe15c1b7f384fa7568ea0018a76c2dd89bbc) | `mobile-film.js` 采用 raw 响应表、密度曲线、DIR、密度颗粒、印相／扫描表，配合带邻域的图块和较小空间场。其结构更适合保留胶卷响应、曝光与空间效果的独立性；它是浏览器工程参考，不能原封不动作为 Android 库使用。 |
| [Phos](https://github.com/LYCO6273/Phos/tree/c83e999ff8e1e938dfc0006f3d4e780c3153853d) | 0.2.3 的 Gold200／HP5 主要是 RGB／亮度权重、大核高斯散射、随机颗粒及经验曲线。计算结构比完整光谱管线简单，但源实现仍使用整图 NumPy／OpenCV 数组；不能据项目名称认定有同等光谱模型或手机性能证据。不作为主要胶卷色彩引擎。 |
| [Phos_refactor](https://github.com/Linglingletsgo/Phos_refactor/tree/f9a081d6ff43c3eac377065f565f203f8ef6f0a4) | `phos/core.py` 实现线性化、金字塔 bloom、密度串扰、噪声和色调映射。可研究多尺度扩散思想；目前各层反复放大至整图并累计，直接移植仍有内存代价。色彩与预设不是所选 spektrafilm 数据链的直接替代。 |

具体依据：[spektrafilm 光谱查表服务](https://github.com/andreavolpato/spektrafilm/blob/3bb2c2d2801ff68b92019cf1dbcbb133d60832bc/src/spektrafilm/runtime/services/spectral_lut_compute.py)、[Filmify 移动渲染](https://github.com/oudelabs/filmify/blob/133dbe15c1b7f384fa7568ea0018a76c2dd89bbc/mini-tool/minitool/mobile-film.js)、[Filmify 密度域设计](https://github.com/oudelabs/filmify/blob/133dbe15c1b7f384fa7568ea0018a76c2dd89bbc/mini-tool/docs/shared-lut-design.md)、[Phos Gold200](https://github.com/LYCO6273/Phos/blob/c83e999ff8e1e938dfc0006f3d4e780c3153853d/0.2.3/Phos/Films/Gold200/Gold200.py)、[Phos_refactor 核心](https://github.com/Linglingletsgo/Phos_refactor/blob/f9a081d6ff43c3eac377065f565f203f8ef6f0a4/phos/core.py)。这是源码结构比较，不是视觉排名或性能排名。

## 所选实现边界

处理顺序为：sRGB 解码／镜头黑柔 → 胶卷响应查表 → 胶卷域 Halation 与散射 → 单一曝光增益 → log 曝光与密度曲线／DIR → 密度颗粒 → 印相或反转片直扫表 → sRGB 输出 → 共享画框 → JPG。

预计算表承担确定性、逐像素的胶卷响应和印相／扫描变换；曝光、随机颗粒及空间扩散保留在运行时。单张普通 sRGB 风格 LUT 不能承载这套阶段关系，不把 Halation 烘进一份无邻域信息的表。用户 LUT 工作流继续独立使用两槽 `.cube` 处理，不与胶片模型混为一个预设分类。

第一版优先逐胶卷／固定相纸的专用表，避免立即引入 Filmify 共享表的多项式密度预变换及另一层拟合误差。第一批数据来源选本次固定的 spektrafilm `3bb2c2d2801ff68b92019cf1dbcbb133d60832bc`；Filmify 来源声明使用较早的 `6cd00c8d…`，不能混用这些版本的表、曲线和运行时参数，也不把已有 SpektraLab 版本的结果宣称与新数据一致。内部先使用 33³ 胶卷响应表、密度曲线和 41³ 印相／扫描表作为资源规格起点；这些尺寸是实现选择，尚未验证精度。参数数据包需要记录输入／输出空间、采样轴、密度域、耦合是否已经烘入、胶卷／相纸 ID、随机颗粒统计、版本和来源。尤其不能在烘入 DIR 后又执行一次 DIR。

照片按图块处理：空间操作带邻域，输出只取中心；颗粒坐标来自完整有效输入图，不能随图块起点重置种子。宽范围黑柔及 Halation 使用有界多尺度场，短半径保留图块邻域，避免为每种空间效果建立另一张整图 float 平面。镜头黑柔偏中性散射，Halation 使用胶卷域有色扩散，关闭时不能遗留高亮 boost。1/8、1/4、1/2 档位沿用既有产品接口，实际映射仍需视觉核对。

CPU 后备执行相同表、曲线、曝光和坐标规则，不在 GPU 失败后把同一张已部分处理的图再套一遍效果。OpenGL ES 3 不支持或浮点附件／纹理过滤不可用时，必须有明确的资源格式与 CPU 路径；不能仅根据 Android 版本认定可用。GPU 资源、渲染线程和取消排空由后端持有，不由页面持有。

## 保留与不采用

现有 Vulkan 路径继续作为完整模型参考，已加入分带、有限 staging 以及 128 线程组的源码适配；这些优化不能消除整图计算平面。默认 facade 已转向移动 GLES／CPU；完整 Vulkan 只在显式开发参考构建加入，不删除参考资源或改写用户原件。GLES 与自动后备已接入源码，尚无运行证据。

Phos／Phos_refactor 不直接成为主体引擎：它们的预设与经验式色调响应更接近另一种审美模型，替换后无法仅凭“更简单”声称保留用户已认可的 spektrafilm 效果。金字塔思想可用于空间场设计，但不能照搬反复整图上采样的方式。

许可随实际复用来源管理：Filmify 代码声明 GPL-3.0-only；其模型衍生数据注明 Andrea Volpato／CC BY-SA 4.0。Phos 原始 LICENSE 文本和 Phos_refactor LICENSE／README 指向 AGPLv3，GitHub 的 Phos 元数据 `NOASSERTION` 不能当作无许可限制。本次没有复制它们的实现到应用。未来接入模型数据仍保留完整许可、来源及修改记录。依据：[Filmify 来源声明](https://github.com/oudelabs/filmify/blob/133dbe15c1b7f384fa7568ea0018a76c2dd89bbc/mini-tool/styles/NOTICE.md)、[Phos LICENSE](https://github.com/LYCO6273/Phos/blob/c83e999ff8e1e938dfc0006f3d4e780c3153853d/LICENSE)、[Phos_refactor LICENSE](https://github.com/Linglingletsgo/Phos_refactor/blob/f9a081d6ff43c3eac377065f565f203f8ef6f0a4/LICENSE)。

## 后续实施与证据

先定义模型数据包、读取器和独立后端，再接入色彩／密度图块处理、空间场及取消排空，最后接回现有 PhotoProcessor 和持久导出队列。保持前端的胶卷 ID、三个参数、逐张／首张继承和整批画框接口。

当前已生产 20 个模型的 33³ 响应／33³ 显影／41³ 印相扫描表，逐像素 DIR 在显影表中烘入一次，空间 DIR 暂省略；曝光和空间效果不烘入。生产过程调用固定 spektrafilm 核心，不执行参考命令或测试。读取器、4 MiB 懒加载缓存与 CPU 色彩阶段见 [模型数据层](mobile-film-models.md)，后续默认照片处理接入原生 CPU、有界空间场和近似颗粒，见 [图像后端](mobile-film-rendering.md)。

上述为路线确定时的记录。当前默认移动 GLES / CPU 已编译并打包；2026-10-17 移除了不再被产品调用的 Vulkan 参考后端，避免开源树携带废弃构建路线。两款 CineStill 后续改为 Fotufilm 默认物理光晕，当前验证范围见[光晕记录](fotufilm-physical-halation-20261017.md)；其他胶卷保持原路径。真实手机耗时、整体实拍观感和机型兼容不因编译或定向检查通过而自动视为完成。
