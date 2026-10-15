# Fotufilm 六款胶片输入资料

此目录保存本项目移动模型生成所需的公开资料，不是用户照片、配对数据或上游可执行程序。

- `profiles/`：上游提交 `12c8efea259e4cea70ec22166e504634d401a0cd` 的 800T、400D、Acros 100 II、Tri-X 400、HP5 Plus、Delta 3200 配置。
- `public-packs/`：2026-10-16 从 `https://fotufilm.com/demo/packs/<id>.pack` 保存的公开浏览器 FSWP v2 表包。它们不是 FFPK 加密发行容器，没有提取密钥或绕过认证。
- `config-layout.json`：上述固定提交的 `web/src/engine-constants.js` 中 CONFIG 对象。
- `source-index.json`：实际输入字节的 SHA-256。公开 demo 未公布这些表包对应的提交；**不得将配置参考提交冒充为表包构建提交或宣称与该提交逐像素一致**。
- `licenses/`：原始许可与 NOTICE。胶片配置、渲染参数及派生表保留 CC BY-SA 4.0，署名 Fotufilm / MUAStudio Inc.；适配的点运算方程来自 Apache-2.0 源码。

生成器为 `tools/produce_fotufilm_models.py`。使用具备 NumPy 的 Python 手动执行；不会联网、执行上游脚本、运行测试或调用 Android 打包。生成阶段排除随机颗粒、光晕、微观空间作用与场景自动测光；对应移动运行效果独立处理。发布资源仅包含派生三阶段表及许可，不把公开输入包装入 APK。

此处资料与派生模型均不构成实测胶卷校准、完整 Fotufilm 引擎一致性或手机性能证据。
