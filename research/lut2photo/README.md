# LUT2Photo 算法参考 · 2026-10-13

只读参考 [qh7574/Lut2Photo-Android](https://github.com/qh7574/Lut2Photo-Android)，固定提交 `598b7beea6e3f2c64ffdaf0e1fc38409ae9ae0a5`。源码许可 MPL-2.0。`upstream/` 是未检出的独立浅克隆，忽略其嵌套 Git 目录；没有运行该项目脚本、安装其应用、复制其代码到应用或打包其二进制。

阅读的文件：

- `app/src/main/res/raw/fragment_shader.glsl`：4×4 Bayer 门限，量化前加入约一阶 8 位灰度以内的扰动。
- `app/src/main/cpp/vulkan/shaders/lut_processor.comp`：随机扰动，以及按亮度区间、粒径、通道相关度调制的颗粒。
- `app/src/main/java/cn/alittlecookie/lut2photo/lut2photo/core/FilmGrainProcessor.kt`：CPU 分块、种子与高斯噪声思路。
- `app/src/main/java/cn/alittlecookie/lut2photo/lut2photo/utils/VltFileManager.kt`：原文件存储映射；解析规范仍依据 Panasonic。

本项目独立编写 Bayer 门限计算并复用现有确定性亮度颗粒，颗粒 / 抖动均有独立开关。门限以 `(index + 0.5) / 16 - 0.5` 居中，使平均偏移为零。未声称移植对方完整颗粒模型，也未将其 shader 中名为 Floyd–Steinberg 的随机扰动误称为误差扩散。

VLT 依据 [Panasonic VARICAM 3DLUT rev 1.0](https://pro-av.panasonic.net/jp/cinema_camera_varicam_eva/support/pdf/VARICAM_3DLUT.pdf)：17³，输出整数 0..4095，红通道先变化。输入采样为 0、256、…、3840、4095；最后一段长度 255，不能直接使用均匀的 `value * 16` 索引。Kotlin 和原生 CPU 都保留该坐标映射。来源文件与已有 LUT 保持原状。
