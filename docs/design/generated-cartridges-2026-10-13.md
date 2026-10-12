# 暗盒生成资产 · 2026-10-13

使用内置 imagegen，四次独立生成，transparent_background=true；没有使用 CLI 或 API 密钥。保留生成原文件和旧暗盒，不替换原始参考。输出是用于模拟界面的艺术表现，不作为实体胶卷包装的尺寸或工艺依据。

## 应用内文件

- app/src/main/res/drawable-nodpi/cartridge_gold_200_v2.png
- app/src/main/res/drawable-nodpi/cartridge_velvia_100_v2.png
- app/src/main/res/drawable-nodpi/cartridge_vision3_500t_v2.png
- app/src/main/res/drawable-nodpi/cartridge_c200_v1.png

各为 1024×1536 RGBA PNG，透明边界；实际消费在 FilmCartridge，根据稳定胶卷 ID 映射，其他胶卷沿用恢复标签。缩放、转动、远近亮度均由界面绘制，未把整张设计稿用作界面贴图。

## 最终提示词

### Gold 200

Create a single premium photorealistic 3D product cutout of a 35mm Kodak Gold 200 film cartridge for an Android film simulation selector. Portrait 2:3 canvas, one upright cylinder fully visible, occupy 84% canvas height, tightly balanced framing. Genuine transparent alpha background, no environment, no ground plane, no cast shadow outside object, no UI, no phone, no extra props. Very slightly above eye-level frontal view with subtle glimpse of the curved right side, not strongly rotated. A black satin metal canister with realistic raised top lip and central spool stalk, delicately worn machined circular black bottom rim. The paper label fills middle 63% of body height, warm rich golden yellow with subtle natural paper grain and cylindrical reflections. Exact large clean black text on the yellow face: 'Gold' on line one, '200' on line two, sharp readable bold humanist sans, with a small red rectangle at upper left. Slim warm cream label at very bottom with small '36 EXP.' black text. No invented Panasonic or Lumix branding. Natural luxury studio lighting, soft highlight on left contour, dark roundness on right, restrained photo-real microtexture, detail consistent with an elegant photographic editing application. The image should look like a physical film roll, not a flat icon or flat pasted illustration.

### velvia

Create a single photorealistic premium 3D product cutout of a Fujifilm Velvia 100 35mm film cartridge for a photographic app. Portrait 2:3 canvas, single upright fully visible canister occupying 84% height, true transparent alpha background, no scenery, no props, no exterior shadow. Frontal slightly above eye-level view, subtle curved right face. Black satin raised lip, realistic central spool stalk, precise circular bottom. Middle paper label: cream top field with exact bold black text 'Velvia', a rich green middle panel, deep cobalt blue lower panel with exact large clean white text '100'. A slim cream side strip; subtle small 'FUJIFILM' above the title. Match a restrained high-end photographic 3D UI material, softly illuminated left edge, fine metal texture with minimal wear, smooth rounded right shadow. Readable sharp typography; no Lumix branding or UI; no multiple film rolls. The cylindrical shape must be physically convincing, not an illustration or flat pasted sticker.

### vision

Create a single photorealistic premium 3D product cutout of a Kodak Vision3 500T 35mm motion picture film canister for a photographic app. Portrait 2:3 canvas, single upright fully visible canister occupying 84% height, true transparent alpha background, no scenery, no props, no exterior shadow. Frontal slightly above eye-level view, subtle curved right face. Black satin raised lip, realistic central spool stalk, precise circular bottom. A near-black middle paper label with exact warm golden yellow text 'Vision3' in a clean condensed sans on first line and larger '500T' on second line, small 'KODAK' at top and fine golden rules, thin warm cream bottom strip. Restrained high-end photographic 3D UI material, softly illuminated left edge, fine metal texture with minimal wear, smooth rounded right shadow. Sharp readable typography; no Lumix branding or UI; no multiple film rolls. The cylindrical shape must be physically convincing, not an illustration or flat pasted sticker.

### C200 · 0.1.4 新增

参照：已生成 Gold 200 的暗盒几何与光照、恢复原件 `core/media/src/main/assets/frames/art/film_pkg_fuji_c200.png` 的标签。

Use case: product-mockup. Create one production bitmap asset for the Android app Lumix Film Mirror: a photorealistic Fujifilm C200 35mm film cartridge, on a genuinely transparent background. Reference 1 is the approved cartridge family: match its straight upright orientation, cylindrical proportions, black metal top with short open central spool, elliptical rim, black bottom rim, slightly elevated frontal camera angle, soft upper-left studio light, and restrained material highlights. Reference 2 is the user's recovered C200 label: faithfully wrap its green border, yellow upper block, thin red/white diagonal upper-right accent, warm cream middle, and large BLACK lettering C200 with 200 below, on the cylindrical barrel. Preserve those readable exact words and colors. Optional small FUJIFILM lettering can be unobtrusive; no extra invented words. This is a polished restrained collectible UI object, fine label fibers and subtle metal wear, not coarse photographic noise. Full cartridge visible, centered with small uniform transparent margin, tall portrait composition, front label legible. No box, no scene, no ground plane, no floor shadow, no outer glow, no black or gray background, no checkerboard painted into the image. Keep all lighting inside the object's silhouette. Output a clean alpha PNG suitable for scaling, rotating and blurring inside a 3D-like carousel.

生成原件：`C:/Users/lenovo/.codex/generated_images/01a12bb5-aea6-7760-9686-60887430c0e6/exec-43559c6c-e750-4c25-b4b0-adeb6638a6e1.png`。

工程副本 SHA256：`8A7B64985D07B1A0F24D4DC7D4683D1BC5A6236463EB4E44A22A3658BBC91574`，1024×1536，32 位 RGBA。用只读像素检查确认 (0,0)、(512,8)、(16,768)、(512,1530) 四处外缘 alpha=0；未进行程序裁剪、抠图或改写像素。消费入口 `MirrorFilm.kt` 的 `FilmCartridge`，只映射 `fujifilm_c200`，原恢复标签和 FrameRenderer 保持原件。
