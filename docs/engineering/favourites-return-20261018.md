# 收藏退出与底栏恢复 · 2026-10-18

输入：维护者提供的 `Screenrecording_20261018_081114.mp4`。录屏中两次退出收藏后，LUT 列表已出现，底栏仍短暂缺失，之后恢复；不能据此判断为永久消失。

原实现用 `favouriteClosing` 立即切换列表，但用独立的 480 ms 延时清除 `favourites`。全屏状态继续读取后者，因此列表和底栏存在确定的半秒状态错位。圆形裁剪则使用独立的 `animateFloatAsState`，退出图层保留又依赖另一段 520 ms 淡出延时。

修复将收藏打开状态作为唯一页面状态。关闭直接回到列表并释放全屏状态；圆形裁剪注册到 `AnimatedVisibilityScope.transition`，由页面过渡保留退出内容直到裁剪完成。收藏保持在列表上方收回，不通过负的目标层级管理返回。详情往返仍保留收藏位置，不重复揭示；减弱动画使用零时长。

验证：`tools/build_android_prototype.ps1 -CompileOnly` 完成原生 Debug 目标与 `:app:compileDebugKotlin`，结果为 `BUILD SUCCESSFUL`；日志位于 `dist/source-compile.log`。分析了原始录屏并检查源码差异。本轮未运行测试、未打包、未操作模拟器或真机；新的收回效果及快速往返仍待维护者在后续安装包中验收。
