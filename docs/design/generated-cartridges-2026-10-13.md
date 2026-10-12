# 暗盒生成资产 · 2026-10-13

使用内置 imagegen，三次独立生成，transparent_background=true；没有使用 CLI 或 API 密钥。保留生成原文件和旧暗盒，不替换原始参考。三张输出是用于模拟界面的艺术表现，不作为实体胶卷包装的尺寸或工艺依据。

## 应用内文件

- app/src/main/res/drawable-nodpi/cartridge_gold_200_v2.png
- app/src/main/res/drawable-nodpi/cartridge_velvia_100_v2.png
- app/src/main/res/drawable-nodpi/cartridge_vision3_500t_v2.png

各为 1024×1536 RGBA PNG，透明边界；实际消费在 FilmCartridge，根据稳定胶卷 ID 映射，其他胶卷沿用恢复标签。缩放、转动、远近亮度均由界面绘制，未把整张设计稿用作界面贴图。

## 最终提示词

### Gold 200

Create a single premium photorealistic 3D product cutout of a 35mm Kodak Gold 200 film cartridge for an Android film simulation selector. Portrait 2:3 canvas, one upright cylinder fully visible, occupy 84% canvas height, tightly balanced framing. Genuine transparent alpha background, no environment, no ground plane, no cast shadow outside object, no UI, no phone, no extra props. Very slightly above eye-level frontal view with subtle glimpse of the curved right side, not strongly rotated. A black satin metal canister with realistic raised top lip and central spool stalk, delicately worn machined circular black bottom rim. The paper label fills middle 63% of body height, warm rich golden yellow with subtle natural paper grain and cylindrical reflections. Exact large clean black text on the yellow face: 'Gold' on line one, '200' on line two, sharp readable bold humanist sans, with a small red rectangle at upper left. Slim warm cream label at very bottom with small '36 EXP.' black text. No invented Panasonic or Lumix branding. Natural luxury studio lighting, soft highlight on left contour, dark roundness on right, restrained photo-real microtexture, detail consistent with an elegant photographic editing application. The image should look like a physical film roll, not a flat icon or flat pasted illustration.

### velvia

Create a single photorealistic premium 3D product cutout of a Fujifilm Velvia 100 35mm film cartridge for a photographic app. Portrait 2:3 canvas, single upright fully visible canister occupying 84% height, true transparent alpha background, no scenery, no props, no exterior shadow. Frontal slightly above eye-level view, subtle curved right face. Black satin raised lip, realistic central spool stalk, precise circular bottom. Middle paper label: cream top field with exact bold black text 'Velvia', a rich green middle panel, deep cobalt blue lower panel with exact large clean white text '100'. A slim cream side strip; subtle small 'FUJIFILM' above the title. Match a restrained high-end photographic 3D UI material, softly illuminated left edge, fine metal texture with minimal wear, smooth rounded right shadow. Readable sharp typography; no Lumix branding or UI; no multiple film rolls. The cylindrical shape must be physically convincing, not an illustration or flat pasted sticker.

### vision

Create a single photorealistic premium 3D product cutout of a Kodak Vision3 500T 35mm motion picture film canister for a photographic app. Portrait 2:3 canvas, single upright fully visible canister occupying 84% height, true transparent alpha background, no scenery, no props, no exterior shadow. Frontal slightly above eye-level view, subtle curved right face. Black satin raised lip, realistic central spool stalk, precise circular bottom. A near-black middle paper label with exact warm golden yellow text 'Vision3' in a clean condensed sans on first line and larger '500T' on second line, small 'KODAK' at top and fine golden rules, thin warm cream bottom strip. Restrained high-end photographic 3D UI material, softly illuminated left edge, fine metal texture with minimal wear, smooth rounded right shadow. Sharp readable typography; no Lumix branding or UI; no multiple film rolls. The cylindrical shape must be physically convincing, not an illustration or flat pasted sticker.
