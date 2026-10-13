# 收藏空间、球体与列表缩短 · 0.1.6

继续用户最近优先级（水印 → Film → 收藏），本轮对照 `Screenshot_20261011_064142.jpg` 的手机内画面，修正收藏上部蓝色过亮和球体偏灰。原完整目标不收缩，不以一个素材画面或 APK 成功代替全部复刻。

## 当前实现

两行明暗衬线标题与既有扇形拍立得保持。背景改为独立静态 Canvas：上部 #091C3C 深蓝留白，右上青光；下部 #96C5DF 渐层叠左侧青色、右侧淡紫漫射。浅色保留冰蓝纸面。不再调用首页通用液体材质，避免混合色权重使整个上半部偏灰亮，没有为此新增图像、纹理、依赖或计时器。

球体承载宽度从固定 112 dp 改为屏宽 36%（限制 112–154 dp），青色左上、粉紫右上和蓝色下部以多个径向色场形成，外弧与小高光重定位。球体仍是参考中的装饰可点击对象，连接当前中心 LUT 的普通详情，不是 LUT 效果色样。拍立得例图及 Palette 来源不变，纸色仍来自真实图片。删去球体上方重复的名称，名称仍在卡片下缘；页数和操作提示加入薄胶囊，避免深色底部变亮后灰字难辨。

屏高 <620 dp 或字号倍率 >1.3 时，标题和原位置叉号固定，主体纵向可滚动、扇形承载高 280 dp；其他视口采用剩余空间居中。保留圆形揭示 / 收回与真实背后像素的退出玻璃，不将静态背景误称为新增实时毛玻璃算法。

## 发现的实际崩溃

早期 085154 包在收藏数量短暂 5→4 时崩溃，日志明确 `Index 4 out of bounds for length 4`，位置在 FavouriteFan 的 pager key。起初发生在打开收藏后，不能将其具体触发原因冒充为已经确认的用户取消；修补后又明确手动取消中心 Loen 以核对数量变化路径。

pager 的旧页数快照可先于 key-map 更新，列表缩短时必须容忍旧索引：key 用 getOrNull，缺失索引仅使用临时唯一 key；内容同样用 getOrNull 跳过，不绘制已移除条目；真实卡片 key 保持原身份，继续由 pager 和已存在的边界滚动处理吸附。没有重建整个列表来掩盖错误。

085526 修补后，手动取消 Loen，5→4、City Film 成为中心，进程 PID 2130 仍运行。回目录重新收藏 Loen，再次打开有 5 项。随后横滑到 City Film，点球进入创作者 Arpad Rektor 的同一详情，系统返回保留 4 / 5。最终 090304 包在单项临时收藏夹内取消 Ionias，出现空态、PID 2596 仍运行，叉号退出后回到有四栏的目录。

## 手动视觉证据

`dist/ui-review/refinement-0.1.6/`：

- `collection-removal-crash.log`、`favourites-dark.xml` 和 `favourites-dark.png`：中间 085154 的崩溃；PNG 已回到启动器，不能当作应用视觉证明。
- `favourites-fixed-dark.png` / XML：085526 的新深蓝背景、五张真实卡片、放大球体。
- `favourite-removed.xml`：明确手动取消中心收藏后的 3 / 4；`gallery-restore-star.xml` 用于重新收藏 Loen。
- `fan-swiped.xml`、`fan-detail.xml`、`fan-detail-return.xml`：4 / 5、City Film 创作者原文、返回同页。
- `favourites-light.png`、`favourites-font150.png`：浅色和字号 1.5；`font150-scroll` 内容本来可完整容纳，没有实际位移，不能宣称它证明了滚动。
- `fan-compact.png` / XML、`fan-compact-scroll.png` / XML：840×1600、字号 1，主体滚动前后；标题与退出固定。
- `final-single.png` / XML、`final-empty.png` / XML、`final-empty-exit.xml`：最终 090304 的单项、取消最后一项和退出目录。
- `final-five-dark.png` / XML、`final-five-light.png`：最终 090304 的五项与新提示胶囊。

单项 / 空态审阅只暂改专用模拟器本应用 `mirror-personal-v1` 的 favourites 字段，其他值保留。审阅后将其按字节恢复，原始 / 恢复 SHA256 都为 `d7362c863b9d53acdcd4e29601b12cb6f95bb1c62623e63c234264bf3bc58eb6`，见 `preferences-restoration.json`。没有读取旧应用数据库、身份或相机密码，也没有把参考原件作为虚构例图。临时显示尺寸 / 字号在结束前复原。

## 最终交付与范围

`dist/lumix-film-mirror-prototype-0.1.6-20261013-090304-debug.apk`，149,321,438 字节；versionCode 7 / versionName 0.1.6；minSdk 30 / targetSdk 36。

SHA256 `7E08A52C9A7BB4BEFDCB8E7C1562C2A55042E52BC029DB9C14C679E98FC3495B`。

最终 assembleDebug 成功，42 秒，145 项任务（8 执行 / 137 沿用），v2 签名和 ZIP 16 KiB 对齐核对通过；最终安装成功。附件在 `dist/release-0.1.6-20261013-090304/`。早期构建因 centre 变量拼写失败，修正后构建成功；记录保留，不把失败算作通过。085154 不推荐安装，085526 保留为修补过程记录，最终仅又加提示胶囊和整理空行。

没有运行单元、回归、冒烟、设备或基准套件。运行证据均来自专用 Android 35 手势模拟器，工具 / AVD / 可写数据在 D:，独立 adb 5038；无物理机、马达、API 30、横屏、真实相机或完整性能证据。本轮未导出照片。

继续查看水印全部恢复样式的完整构图、Film 全目录和快速打断、收藏运动与原图空间细比，以及其他编辑 / 相机 / 任务页控件、空态和错误。原目标保持 active。
