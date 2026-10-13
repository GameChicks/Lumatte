# Silver 暗盒资产 · 0.1.7

使用内置 imagegen，两次独立生成，transparent_background=true，1024×1536 RGBA。参考已生成 Gold 200 的筒身 / 开口 / 光照，不使用它的品牌标签。仅消费于选择界面 FilmCartridge；不进入真实成片或改变模拟模型。原生成文件、旧暗盒和恢复标签均保留，像素没有程序改写。

黑白目录当前为本项目自有 Silver Neutral / Contrast，非实测品牌胶卷。因此使用自有 Silver 标签，不把外观误示为 Ilford。用户随后要求暂时停止生图；Portra 调用返回 429 后没有再调用生成或 CLI 备选。其他 UI 项继续推进。

## Contrast

应用文件：app/src/main/res/drawable-nodpi/cartridge_silver_contrast_v1.png

原件：C:/Users/lenovo/.codex/generated_images/01a12bb5-aea6-7760-9686-60887430c0e6/exec-0e25fbec-a280-4ee7-b309-429492569956.png

SHA256 A1EF200316770FA611430E8EFBBA22EE4EAFC7FBF3E1B73E318FE9381B7701FD

映射 lfm_silver_contrast。最终提示词：

Use case: product-mockup. Create one production bitmap asset for the Android app Lumix Film Mirror, a fictional self-owned SILVER CONTRAST 35mm-style film cartridge. The provided image is a material/geometry reference ONLY: match its straight upright cylindrical proportions, black metal top lip, short OPEN central spool, black bottom rim, slightly elevated frontal viewing angle and subtle curved right surface. Do not copy Gold branding or its yellow label. The new restrained monochrome label is warm off-white with clean exact large BLACK text 'Silver', and a black lower band with exact small ivory-white text 'CONTRAST'. A thin charcoal divider near the upper label, minimal red square accent at upper left. No ISO, no invented film brand, no Kodak/Panasonic/Ilford branding. This represents the app's self-owned contrast monochrome simulation, not a real commercial film stock. Luxury restrained studio light from upper left, fine paper fibers and gently worn satin metal, readable print wrapped naturally around the cylinder, no coarse noise. Single full canister, occupy about 86% height, centered on portrait 2:3 canvas with small transparent margins. Genuine transparent alpha background. NO scene, floor, cast shadow outside the object, glow, background tint, checkerboard, phone, UI, props or box. Preserve all lighting inside the object silhouette. Consistent collectible object family suitable for scaling and rotating in a 3D-like film carousel.

## Neutral

应用文件：app/src/main/res/drawable-nodpi/cartridge_silver_neutral_v1.png

原件：C:/Users/lenovo/.codex/generated_images/01a12bb5-aea6-7760-9686-60887430c0e6/exec-2e47ad9e-9385-474b-a0f3-33a6c7baaf68.png

SHA256 A1A0F0A8F6479525E1B19A19583D4266D446DCCB8A004749E8BD50A20E826EF6

映射 lfm_silver_neutral。最终提示词：

Use case: product-mockup. Create a sibling production bitmap asset for Lumix Film Mirror: a fictional self-owned SILVER NEUTRAL 35mm-style cartridge. Reference image is the approved SILVER CONTRAST family geometry and studio light, not an edit target. Precisely match its upright dimensions, open central spool, raised satin-black circular lip and bottom rim, slightly elevated frontal view, left-edge reflection, cylindrical label curvature. Change the label to a cool soft silver-gray paper face with exact large near-black word 'Silver', thin dark upper divider, small muted blue square accent at upper left. Black lower band with exact clean small ivory-white text 'NEUTRAL'. No CONTRAST word. No ISO, no Kodak/Ilford/Panasonic branding or fictitious brand logos. This is an app-owned monochrome simulation, not a claim about real commercial film. Fine paper fibers and restrained metal wear. One full canister on portrait 2:3 canvas, same 86% height and margins as reference. True transparent alpha background; no environment, ground, exterior glow, cast shadow, backdrop color, painted checkerboard, scene, box, extra objects or UI. All light is contained within the object silhouette. Readable unclipped letters, polished physical cylindrical volume.

## 静态检查

两件均为 1024×1536 RGBA；(0,0)、(512,8)、(16,768)、(512,1530) 外缘 alpha 都为 0。此为只读资源检查，不代表全部真实设备的绘制 / 性能证据。完整消费与手动界面记录见本轮 refinement 文档。
