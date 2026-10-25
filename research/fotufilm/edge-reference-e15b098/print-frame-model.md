# Print borders

The iPhone photo editor offers None, Film Border, Film Border Positive, Slide Mount, Paper Border at 4 × 6, 5 × 7,
8 × 10 and 5 × 5 inches, Carrier Border, Emulsion Border, White Mount, Black Mount and three posting
canvases — Square Post, Portrait Post and Story — on a horizontally scrolled strip of framed
previews in the print deck. Film Border
shows a negative stock's developed negative on its film, using the existing density-to-transmission
renderer. Reversal and integral instant film retain their developed positive image; Slide Mount
views a reversal transparency the same way. The paper selection is retained: every other choice
returns to the chosen output medium. Menu previews, the canvas, zoomed detail, saved thumbnails
and exports use the same material-dependent image.

Border metadata lives with film stock definitions. The finishing compositor runs after image
formation, with no photograph resampling or changes to its colour profile. Negative delivery is SDR.

The web editor offers the same finishing choices in the Mac-style Print inspector. Its isolated
native worker supplies `PrintFrameConfiguration`, `PrintFramePlacement`, perforation dimensions,
availability and the derived delivery medium. A separate Canvas compositor adds the material to
8-bit sRGB preview and export. Crop and pipeline inspection remain unframed; photo frames do not
apply to video. Native and browser rasterizers may differ slightly in lettering and procedural
texture, while geometry and spectral material colours share the native model.

## Film geometry

An automatic border uses the stock's native format. An explicit Film Gauge overrides it. Camera
sensor metadata does not select a different border. A photograph of another shape than the camera
aperture is cropped to fill it, as a frame exposed in that camera would be; the film and holes are
never stretched to the photograph.

### Framing

Film Border and Film Border Positive show the largest rectangle of the aperture's shape that fits
the photograph, turned with it (`PrintFramePlacement.photoCrop`). Where it is placed is the
framing (`PrintFrameConfiguration.framing`, `framing` in the frame request): -1 to 1 across and
down, from the photograph's left or top edge to its right or bottom, 0 centred. The plan returns the
crop in the photograph's pixels (`crop`) and as fractions of it (`cropFraction`), and lays the frame
out around the crop, so every surface crops the photograph's light first and develops, frames and
compares that part. A photograph already within a pixel of the aperture's shape is shown whole,
so a cropped picture asks for no second crop. A subject selection's point stays on the whole
photograph and is moved onto the crop for the develop. In the desktop and browser editors a drag
on the fitted picture moves the photograph under the aperture, as far as its edges; the framing
is saved with the edit and undone in one step. Every other frame shows the whole photograph.

Choosing Film Border or Film Border Positive in the desktop and browser editors also turns RAW
Corrections off, so a camera RAW in the film's frame shows the capture as the sensor recorded it:
Core Image's lens distortion and vignetting correction is left out (`HostImage.uncorrected`,
`RawDecode.Recipe.correctsLens`), and on Linux LibRaw delivers the whole visible sensor area
rather than the camera's default crop (`FFC_DECODE_SENSOR_AREA`). Core Image always delivers the
camera's crop; the browser's LibRaw decode corrects nothing and already shows the whole area.

### Frames

Frames (`frames` in the frame request, 1 to 4) exposes one photograph across several frames'
length of a 135 or 120 roll (`FilmBorderGeometry.spanning`), as a panoramic camera's wide gate
does, or 135 run through a medium-format camera. The cut runs on by the gauge's frame advance for
each frame, 38 mm on 135 and 60 mm on 6 × 6, and its aperture runs on with it unbroken: 74, 112 and
150 mm by 24 on 135, 116 and 176 mm by 56 on 120. The photograph is cropped and framed to that
aperture like any other (`photoCrop`), and the strip is developed around it as one exposure. The
roll's edge printing is exposed at the factory, so it runs along regardless: each frame's length
carries its own numbers and DX code, numbered on from Frame Number (`FilmEdgePrinting.run`,
`barcodeRun`). Motion-picture gauges, whose frame is a fixed gate, sheets and instant film keep one
frame.

### Camera

Camera (`camera` in the frame request) says what a 135 cut was exposed in. A 35mm or panoramic
camera (`panoramic`, the default) has its gate between the perforation rows, 24 mm across. A
medium-format camera loaded with 135 (`mediumFormatBack`) has a gate taller than the film is wide,
so the picture runs across all 35 mm to both cut edges (`FilmBorderGeometry.exposingEdges`): the
photograph is cropped to that, the strip has no rebate across the film, the perforations, punched
before exposure, show the scan bed through the picture, and the factory's latent edge printing
lies over it. Where both exposed the film, the denser of the two is drawn, darken for a negative's
letters and lighten for lighter ones, an approximation of the two exposures adding on the
characteristic curve. The exposed length still follows Frames rather than a particular camera's
gate; 120 fills a medium-format gate already and offers no choice. The plan's `camera` says whether
the gauge offers it.

| Format | Image aperture, mm | Film segment, mm | Edge construction |
| --- | --- | --- | --- |
| 135 still | 36 × 24 | 38 × 35 | Two rows of KS holes, eight pitches per frame |
| Super 35 | 24.92 × 18.67 | 35 × 18.96 | Two rows of BH holes, four pitches per frame |
| Super 16 (the app's 16mm gauge) | 12.35 × 7.42 | 16 × 7.605 | Single perforation row, holes at frame lines |
| Super 8 | 5.79 × 4.01 | 7.976 × 4.234 | Single small-perforation row, hole at frame centre |
| 120, 6 × 6 | 56 × 56 | 61 × 60 | Unperforated roll film |
| 4 × 5 sheet | 95 × 120 | 100 × 125 | Unperforated sheet, stock-specific notch code when documented |
| Instax Mini | 46 × 62 | 54 × 86 | Integral positive border |
| Instax Square | 62 × 62 | 72 × 86 | Integral positive border |
| Instax Wide | 99 × 62 | 108 × 86 | Integral positive border |

KS holes are rounded rectangles, 2.794 mm across the film and 1.981 mm along transport,
with a 0.510 mm corner radius and 4.75 mm pitch. A motion stock used in a 135 camera retains
its BH holes and 4.74 mm pitch. BH holes are circular sides bounded by
parallel flats (2.794 mm circle diameter, 1.854 mm height), at 4.74 mm pitch. 16 mm holes
measure 1.829 × 1.270 mm with a 0.250 mm corner radius. These dimensions and profiles
come from [Kodak's motion-picture catalogue, Perforations, page 13](https://www.kodak.com/content/pdfs/motion/Kodak-Motion-Picture-Products-Price-Catalog-EAMER.pdf).
Super 8 uses the Type S geometry described by SMPTE ST 149, including a 0.914 × 1.143 mm
hole and 4.234 mm long pitch. Instax image and outer dimensions follow
Fujifilm's specifications for [Mini](https://www.instax.com/mini_link/en/spec.html),
[Square](https://www.instax.com/sq1/en/) and
[Wide](https://asset.fujifilm.com/master/apac/files/2020-06/acf110878e2c263a1a0c13b762fb1cbe/instax_datasheet.pdf).

Every hole is its die's clean outline (`PrintFrameRenderer.perforationOutline`,
`perforationOutline` in web/src/print-frame-renderer.js), traced every 0.04 mm at its pitch:
perforators cut to about a hundredth of a millimetre, so the punched edge is straight and the
corners evenly rounded. A strip looks the same on every renderer and at every size, and the
developed strip punches the same holes.

The chosen 120 frame-to-frame spacing, sheet-film camera aperture, and instant border's top/bottom
allocation are representative presentation conventions: cameras, holders and product revisions
can differ. Instant borders require the matching instant-film stock. A gauge chosen for a stock
that was never sold in that size represents the selected simulation gauge, not an availability claim.

## Stock-specific notches

`FilmStockDefinition.sheetNotches` carries a source URL and the shape, relative position, width
and depth of each notch. FP4 Plus and HP5 Plus use the patterns for sizes up to 10 × 12 inches
in [Ilford's Notch Codes guide, page 1](https://www.ilfordphoto.com/wp/wp-content/uploads/2017/06/Notch-Codes-Guide..pdf).
Both have a wide shallow arc at each end and a smaller round notch between; its different
position distinguishes the stocks. They appear at the upper right with the sheet upright and
the emulsion facing the viewer, and rotate with the sheet.

The guide gives identification diagrams, not machining dimensions. Their relative profiles are
modelled within a representative 20 mm span, ending 10 mm from the corner. These sizes are
not measured manufacturer tolerances. A stock without a documented code has no invented
notches and the frame menu identifies that missing information. Roll film and paper never
receive sheet-film notches. The neutral background seen through cutouts is a scan-bed convention.
Random edge fogging and gate damage are not added.

## Edge printing

Optional `FilmStockDefinition.edgePrinting` records carry the exact inscription, source URLs,
physical text boxes and supported gauge. The renderer draws a representative segment of the
manufacturer's edge print in the outer rebate, rotating it with the film. Roll-film markings are
viewed from the base side, as in the source diagrams. The existing sheet view remains emulsion-side
up for notch identification; sheet lettering has not been verified and is omitted.

| Stock | Gauges with verified inscriptions | Inscription |
| --- | --- | --- |
| Portra 400 | 135, 120 | KODAK PORTRA 400 |
| Ektar 100 | 135, 120 | KODAK EKTAR 100 |
| Gold 200 | 135, 120 | KODAK GB 200-7 on 135; KODAK 200 on 120 |
| UltraMax 400 | 135 | KODAK GC 400 |
| Kodacolor 100 | 135 | KODAK 100-8 |
| Ektachrome E100 | 135, 120 | KODAK E100 |
| Kodachrome 64 | 135 | KODAK 5032 / KR 64 |
| Kodachrome 25, 200 | 135 | KODAK 5073 / KM 25; KODAK 5001 / KL 200 (see below) |
| Tri-X 400 | 135, 120 | KODAK 400TX |
| T-Max 100 | 135, 120 | KODAK 100TMX |
| HP5 Plus | 135 | ILFORD HP5 PLUS |
| FP4 Plus | 135, 120 | ILFORD FP4 PLUS |
| Delta 3200 | 135, 120 | ILFORD / DELTA 3200 PROFESSIONAL on 135; ILFORD DELTA 3200 PRO on 120 |
| Harman Phoenix 200 | 135, 120 | HARMAN PHOENIX 200 |
| Velvia 50 | 135, 120 | FUJI / RVP50 |
| Provia 100F | 135, 120 | FUJI / RDPIII |
| Astia 100F | 135, 120 | FUJI / RAP100F |
| PRO 400H | 135, 120 | PRO400H / FUJI |
| PRO 160NS | 120 | FUJI / PN160NS |
| C200 | 135 | 200 / FUJI / CA24 |
| Superia X-TRA 400 | 135 | S-400 / FUJI / CH23 |
| Superia 200 | 135 | S-200 / FUJI |
| Reala 100 | 135, 120 | REALA / FUJI |
| Agfachrome RSX II 100 | 135, 120 | AGFA RSX II 100 on 135; RSX II 100 / AGFA on 120 |
| Agfachrome RSX II 200 | 120 | RSX II 200 / AGFA |
| LomoChrome Metropolis, Purple, Turquoise | 135 | LOMO♥ frame METROPOLIS, PURPLE or TURQUOISE, on both edges |
| Lomography Color Negative 100 | 135, 120 | 100-2 on 135; LOMO♥ frame ◄ CN 100 on 120 |
| Vision3 250D | 35 mm still, Super 35, Super 16 | 5207 / EN |
| Vision3 500T | 35 mm still, Super 35, Super 16 | 5219 / EJ |
| Double-X | 35 mm still, Super 35, Super 16 | 5222 / KE |
| VERITA 200D | 35 mm still, Super 35, Super 16 | 5206 / ET |
| Eterna 500, Eterna Vivid 500, F-64D, Reala 500D | 35 mm still, Super 35, Super 16 | FN73 / FUJI 500, FN47 / FUJI V500, FN22 / FUJI F-64D, FN92 / FUJI 500D |
| CineStill 800T | 135 | EJ (2021 rolls) |

Sources:

- [Kodak Portra 400 product specification](https://www.kodakprofessional.com/photographers/film/color/kodak-professional-portra-400-film/516)
  and [Ektar 100 specification](https://www.kodakprofessional.com/photographers/film/color/kodak-professional-ektar-100-film/530): literal edge-print names.
- [Kodak T-Max 100 specification](https://kodakprofessional.com/photographers/film/black-white/kodak-professional-t-max-100-film/525)
  and [Tri-X specification](https://www.kodakprofessional.com/photographers/film/black-white/kodak-professional-tri-x-films/515):
  compact 100TMX and 400TX edge-print identifiers.
- [Kodak Professional catalog, L-9 (2003)](https://filmcolors.org/wp-content/uploads/2025/11/2003KodakProfessionalCatalog_L9.pdf),
  printed pp. 6R and 16R: 135 and 120 layout families and black-and-white stock inscriptions.
  The old Portra NC/VC product names are not reused. Kodak's 120 dual numbering is distinct
  from its 135 full/half-frame numbering; full/half-frame spacing on 135 is 38/19 mm.
- [Ilford's processing examples](https://www.ilfordphoto.com/common-film-processing-problems/):
  HP5 Plus lettering and full/half-frame locations in the overexposure example.
- [Fujifilm Velvia 50, AF3-0221E2](https://asset.fujifilm.com/master/emea/files/2020-10/a71dda63e2662f012b3b74110794918a/films_velvia-50_datasheet_01.pdf),
  PDF p. 6: RVP50 inscriptions and different 135/120 arrangements.
- [Kodak 35 mm KEYKODE diagram](https://www.kodak.com/content/products-brochures/Film/post-production-35mm-keykode-diagram.pdf)
  and [16 mm KEYKODE guide](https://www.kodak.com/content/products-brochures/Film/post-production-16mm-keykode-diagram.pdf),
  PDF p. 2: gauge-specific production inscriptions. A single cinema frame shows only a stock-code
  fragment, not an entire foot of KEYKODE data squeezed into one frame.
- Fujifilm datasheets with rebate diagrams:
  [Provia 100F, AF3-036E](https://asset.fujifilm.com/www/za/files/2019-09/4ca5dfc19ecbea6b26c635b5079d6ff3/films_provia-100f_datasheet_01.pdf) p. 4,
  [PRO 400H, AF3-176E](https://asset.fujifilm.com/www/za/files/2019-09/d68b00b13f1b6234629342317be9cf2b/films_pro-400h_datasheet_01.pdf) p. 6,
  [PRO 160NS](https://asset.fujifilm.com/www/jp/files/2024-04/6b75b1c1cb4253623fb2cd7a56ff7443/datasheet_pro160ns_01.pdf) p. 6,
  [C200](https://asset.fujifilm.com/master/emea/files/2020-10/98c3d5087c253f51c132a5d46059f131/films_c200_datasheet_01.pdf) p. 4 and
  [Superia X-TRA 400](https://asset.fujifilm.com/master/emea/files/2020-10/9a958fdcc6bd1442a06f71e134b811f6/films_superia-xtra400_datasheet_01.pdf) p. 4.
  The [Professional Film Data Guide](https://asset.fujifilm.com/www/ca/files/2020-04/9cdee030ab7fc76bbcfddbcd38650038/ProfessionalFilmDataGuide.pdf),
  PDF pp. 9–10, gives the RAP100F strip and the abbreviated edge names REALA and S-200; those two
  stocks have no rebate diagram of their own, so their generation codes are omitted.
- [Kodak Gold 120](https://www.kodakprofessional.com/photographers/film/color/kodak-professional-gold-film-120-format/514)
  and [Ektachrome E100](https://www.kodakprofessional.com/photographers/film/color/kodak-professional-ektachrome-film-e100/531)
  specifications give their edge prints. Gold 200 135, UltraMax 400, Kodacolor 100, Kodachrome 64,
  the Ilford, Harman, Agfa and Lomography stocks and CineStill 800T follow legible photographs of
  developed film listed in each stock's `edgePrinting.sources`.
- Kodachrome 25 and 200 use the Kodachrome 64 layout with Kodak's K-14 product codes 5073/KM and
  5001/KL from a [Kodak process table](https://www.dagiebrundert.de/alle%20Bilder/Kodachromemagicbath.pdf);
  no legible developed edge of either has been found.
- Fujifilm's [Motion Picture Film Manual KB-0707E](https://www.theodoropoulos.info/attachments/076_fujifilm_motion_picture_film_manual.pdf),
  PDF pp. 2, 13–19 and 31–34 (KB-1101E for Eterna Vivid 500), gives the FN identification mark
  and film name on 35 mm and 16 mm. Key numbers, emulsion and roll numbers are omitted.
  [VERITA 200D, H-1-5206](https://www.kodak.com/content/pdfs/motion/KODAK-VERITA-200D-5206-7206-technical-information.pdf),
  p. 2, gives 5206/7206 and identification code ET; its manufacturer word is not documented and is omitted.
- CineStill 800T rolls photographed in 2021 carry the 5219 KEYKODE prefix EJ. CineStill states
  that current rolls carry only frame numbers, so this is one batch, not every roll.

Stocks without a documented developed edge stay unlettered: Acros 100 II, Agfachrome RSX II 50,
RSX II 200 on 135, CineStill 400D, Aerochrome 1443 and Infrared Color. Respooled Aerocolor IV
2460 is sold without edge printing or frame numbers, so its border is blank by design.

These are reference cuts, not reproductions of an identified manufactured roll. The still-film
numbers select a representative position; they are not the imported photograph's original frame
number. The stock data records each cut at frame 1: its marks reading `1` and `1A` are the frame
and half-frame numbers. Frame Number (`frameNumber` in the frame request, 0 to 38) renumbers those
marks at their recorded positions, widening them by the character pitch, and the DX barcode encodes
the same frame; every other mark stays. A cut without those marks, such as a motion-picture code,
offers no Frame Number. Edge Printing off (`edgePrinting: false`) drops the letters, numbers and
barcode and leaves the developed rebate blank; the plan's `edgePrinting` and `frameNumber` say
whether the stock and gauge offer either setting. Production batch, emulsion/roll serials and year are omitted, and so are barcodes that
would encode them: KEYKODE's key number is a roll serial. Unknown stock/gauge pairs, sheet film,
Super 8, instant masks and paper fronts stay unlettered.

### DX edge barcode

135 negatives with a documented DX number (`FilmEdgePrinting.dx`) also carry the DX film edge
barcode (ISO 1007, ANSI PH1.14) on the rail below the lower perforations, between the printed frame
numbers. It encodes only the stock's DX number and the frame, so nothing about a particular roll is
invented. Each code is 31 cells of 0.381 mm, the standard's minimum bar width, on two tracks: a
data track 0.25–0.76 mm from the film's edge and a clock track 1.30–1.80 mm from it. The data
track holds the entry pattern 101010, the DX number's part 1 (7 bits) and part 2 (4 bits) either
side of a blank cell, the frame number (6 bits), the half-frame flag, a blank, even parity over the
18 data bits and the exit 0101; the clock track is 1111101010101010101010101010111 in every code.
A mark is an exposed bar. Codes repeat every half frame, so this cut shows the frame after its
printed number and the half frame after its A number, a quarter millimetre past it, the second
running off the cut. The cell layout follows Kodak's
[US 4,965,628](https://patents.google.com/patent/US4965628A/en); track positions and the 0.5
density above base follow the ANSI PH1.14-1990 figures quoted in
[US 5,448,049](https://patents.google.com/patent/US5448049A/en). The encoder reproduces the worked
examples of [Wikipedia's DX number article](https://en.wikipedia.org/wiki/DX_number) (79-15, frame
23) and [the zxing-cpp DX edge reader](https://github.com/zxing-cpp/zxing-cpp/blob/master/core/src/oned/ODDXFilmEdgeReader.cpp)'s rules.

| Stock | DX number | Source |
| --- | --- | --- |
| C200 | 39-1 | Fujifilm datasheet, p. 4 |
| Superia X-TRA 400 | 39-2 | Fujifilm EMEA datasheet, p. 4 |
| PRO 400H | 36-8 | Fujifilm AF3-176E, p. 6 |
| Reala 100 | 36-7 | I3A DX code list (2009) |
| UltraMax 400 | 96-1 | I3A DX code list (2009), MAX 400 |
| HP5 Plus, FP4 Plus, Delta 3200 | 109-9, 109-12, 108-10 | I3A DX code list (2009) |
| Portra 400, Kodacolor 100, Phoenix 200 | 95-13, 91-1, 105-1 | Community Big Film Database |

The [I3A list](https://mips.di.unimi.it/photofire/archive/additional_material/IIIA_DX_Codes_for_135-size_film_2009.pdf)
assigns cartridge numbers; the edge code carries the same two parts. The
[Big Film Database](https://github.com/Merinorus/Open-source-film-database) is a community record,
not a manufacturer's. Gold 200 (conflicting numbers), Ektar 100 (two generations), Tri-X 400 and
T-Max 100 (no evidence of a printed barcode), Superia 200 and Color Negative 100 have no barcode
here. Transparencies carry none. The barcode develops with the strip like the lettering, at 0.5
density above the base.

The published diagrams and scans establish text and layout families but do not provide precise
printer font outlines, exposure spectra or tolerances. System vector lettering and its millimetre
boxes approximate those shapes and positions. Letter density is a representative 72% of the
negative stock's density span, or 8% of a reversal stock's span above minimum density. The same
stock dye spectra, light-box gain and lamp produce the resulting letter colour. This makes negative
letters dark and reversal letters light without a universal painted amber colour. Edge density does
not claim to measure the particular roll or follow its lab development. No source images or fonts
are embedded. Image pixels, profiles and bit depth remain unchanged by the compositor.

## Film Border Positive

Film Border Positive is the same strip as Film Border, as a scanner shows it after inverting the
negative. The geometry, perforations, sheet notches and edge printing are identical. The scanner
sets its black point just past the clear base and removes the colour mask, so the rebate is a
neutral near-black 0.004 display-linear P3 (about 13 of 255 encoded); that level is a presentation choice, not a measured scanner.
The open perforations and notches passed more light than the base and clip to black. The lettering
is the Film Border edge exposure (72% of the stock's density span) normalised on the clear base
under the reference lamp and inverted, so each stock's letters come out near white, by how little
light they passed. It is offered for negative stocks in roll and sheet gauges; a reversal
transparency's Film Border is already positive and integral instant film has no rebate.

The photograph keeps the selected output medium, retaining its paper or screen look; with
Negative selected it is delivered as Lab Scan, the positive of a scanned negative. It follows
physical dimensions and ignores Border Width.

## Film colour

Film Border depicts the material surrounding the image. Where the strip is drawn rather than
developed (a scanned negative), colour-negative borders use the stock's
minimum-density records and dye spectra, retaining its base-plus-fog and orange mask. Monochrome
negative borders retain their modelled clear-base tint. A common light-box gain is used for negatives,
so it does not neutralise their colours. Reversal borders use the stock's maximum-density records:
unexposed slide-film rebate develops dark. Film Border uses the renderer's standard reference
light box for both the image and its rebate, retaining the mask even when the separate negative
preview preference is Scanner. Viewing Light remains the paper's setting and is restored with
that output. Integral instant film keeps its attached white mask, separate from the image dyes.

These colours follow the existing spectral stock models; they are not separately measured samples of
individual roll edges. The photograph inside the aperture retains the stock's development,
grain and exposure; its density is viewed through the film instead of printed to paper.

## Developed strip

Film Border and Film Border Positive develop their whole strip with the photograph, as the
Emulsion Border develops its band (`PrintFramePlacement.filmStrip`, `UnexposedEdge`). The piece
of film is the frame's canvas: the photograph in the camera aperture and the film out to its cut
edges on every side, across the perforation rows. The photograph's light, continued past its
edges, passes the gate at the photograph's edge, which framing puts at the aperture's. Beyond
the gate the film is base plus fog on a negative and maximum density on a transparency, with the
stock's grain running unbroken from the picture, and the frame's halation and emulsion scatter
spread into the rebate in that stock's colour and reach.
The develop prints on the photograph's own Auto Levels readings. There is no enlarger carrier:
the strip is viewed whole, on the light box for Film Border and through the selected medium for
Film Border Positive. With no lens aperture recorded the gate's shadow is drawn at f/2.

The perforations and sheet notches are punched after development and show the scan bed (black
in the inverted scan). The edge printing is not exposed with the frame. A negative's letters pass
the share of the rebate’s light that the edge exposure leaves them (`edgeTransmission`, the
lettering's colour over the base's), so the rebate's grain and glow continue through them; a
transparency's or an inverted scan's lighter letters are laid over the dark rebate. Blending the
delivery's encoded values stands in for blending light, which is exact for a power-law transfer
and close for sRGB. The inverted scan lifts the whole developed strip by its 0.004 black point, so
film that saw no light reads as the rebate inside the gate too and the holes stand below it. Letters therefore carry no halation of their own.

A scanned negative already shows its own film: its Film Border is drawn around the scan, as is
integral instant film's mask. The iPhone, iPad and Mac editors, the desktop app and the browser
develop the strip alike; the browser prepares a profile at the strip's size for it.

## Paper construction

Paper Border is offered for Ektacolor Edge, ENDURA Premier and Crystal Archive Type CA.
All use a real supported lustre surface on resin-coated photographic paper, with clean cut edges.
It represents a print cut from a roll at one of four common lab sizes, with a chosen 3 mm easel
margin: 4 × 6 inch (152.4 × 101.6 mm), 5 × 7 inch (177.8 × 127 mm), 8 × 10 inch (254 × 203.2 mm)
and a square 5 × 5 inch (127 × 127 mm) cut. Each size is a separate menu choice; the crop is
centred inside the easel opening and never stretched to the sheet, so a 3 : 2 photograph on an
8 × 10 or 5 × 5 sheet leaves paper above and below it. Paper roll width and trim are print
choices, not intrinsic dimensions of the emulsion.

- [Kodak Ektacolor Edge product sheet](https://business.kodakmoments.com/sites/default/files/files/products/EKTACOLOREDGE_LTR_EN_LR.pdf), surfaces E and F.
- Kodak ENDURA Premier technical publication E-4070, page 1: resin-coated paper, including E lustre.
- [Fujifilm Crystal Archive Type CA product sheet](https://asset.fujifilm.com/www/us/files/2020-02/dcbe0cc43213e90b8d4e177cf01b6c48/Fujicolor_Crystal_Archive_Paper_Type_CA.pdf): glossy, matte and lustre surfaces.

The base is calculated through the selected paper's existing minimum-density records, dye
spectra and viewing light. It is the engine's substrate approximation, not a new measurement
of paper-white reflectance or optical brighteners. Crystal Archive retains the engine's documented
RA-4 curve proxy. Fine surface stipple is procedural: published surface names do not provide
measured microtopography. Cotton fibres, baryta bases, deckled edges and artificial ageing are
not appropriate to these stocks and are not offered. Screen, scans, negatives and motion-picture
print films do not become paper sheets; Paper Border is inactive on those outputs.


## Carrier Border

Carrier Border is the darkroom practice of enlarging a negative through a carrier filed wider
than the camera aperture, so the clear rebate around the frame prints as a dark line inside the
easel's white margin. It is offered for negative stocks on the negative reflection papers above;
a transparency's rebate develops dark and would print no differently from the unexposed margin
of positive paper, so reversal film and Ilfochrome do not offer it. The sheet is an 8 × 10 inch
(254 × 203.2 mm) darkroom print with a ½ inch (12.7 mm) easel margin and a 2.5 mm printed rebate
on every side. These three sizes are presentation conventions: easel blades, carrier filing and
enlargement all vary from print to print.

The line's colour is the paper's own maximum-density records through the same receiver model,
dye spectra and Viewing Light as the paper base, not a painted black. Its inner edge is the camera
gate and stays straight against the photograph; its outer edge carries a small deterministic
unevenness (bounded to a quarter of the rebate width) standing in for a hand-filed carrier. The
lustre stipple is drawn on the margin only. No frame numbers, inscriptions or perforations are
printed: those lie outside a still camera's filed carrier.

## Slide Mount

Slide Mount shows a developed reversal transparency in a plain card mount. The outer size is the
2 × 2 inch (50.8 mm square) format used by every 35 mm projector; the aperture is the typical
mounted image area that Hunt tabulates for 35 mm still film, 34.5 × 23 mm, and 56 × 56 mm for
6 × 6 on 120 in a 70 mm square mount (R. W. G. Hunt, *The Reproduction of Colour*, 6th ed.,
Appendix 4 "Photographic parameters", printed p. 668, "Typical area used"). A crop that differs from the aperture is fitted inside it
without cropping, so any rebate that shows beside it is the stock's maximum-density colour, as in
Film Border. The mount is offered for reversal stocks in 35mm and 120 only; negatives, motion
gauges, sheet film and instant film are not mounted as slides.

The card is a neutral white at 0.86 display-linear P3, with a 1 mm aperture corner radius and a
faint shadow along the upper and left aperture edges standing for the card's thickness. Those
are presentation choices; no manufacturer's mount, embossing or printing is reproduced. As with
Film Border, the transparency is viewed by transmission on the reference light box and the paper
selection is retained for other frames.

## Plain mounts

White Mount and Black Mount are crop-following presentation margins with no material behind
them: 8% of the photograph's short side on every side, rounded up to whole pixels. White Mount
uses the selected reflection paper's modelled base and Viewing Light where there is one and the
same neutral 0.91 display-linear P3 mount as Emulsion Border's margin elsewhere. Black Mount is a neutral
0.02 display-linear P3 board on every output; it is not a paper's maximum density. Both are
available with every film and output medium, including scans and Negative.

## Posting canvases

Square Post (1 : 1), Portrait Post (4 : 5) and Story (9 : 16) place the photograph inside a
fixed-aspect canvas for social posting, with a margin of 5% of the canvas's short side on every
side. The photograph is fitted whole and centred — never cropped, resampled or rotated — so a
landscape picture on a story canvas stands between white bands, as posts do. The canvas is
display white (1.0 display-linear) on every output, including reflection papers, so the margin
merges with a white feed; it is not the paper's modelled base. The aspects are the platforms'
own; the margin is a presentation choice. These are offered with every film and output medium.

## Border Width

Border Width multiplies the margin of every frame whose margin is a presentation or easel
choice, from 0 to 3 times the preset above (shown as 0–300%, 100% by default). It scales the
plain mounts' 8% margin, the posting canvases' 5% margin and Emulsion Border's paper margin
around its band, and the easel margin of the Paper Border sizes and Carrier Border. A cut sheet
keeps its size and its printed carrier rebate, so a wider easel margin leaves a smaller opening
for the photograph; a mount or canvas grows around the photograph instead. At 0 a mount adds
nothing and a paper print is borderless. Film Border and Slide Mount are physical material and
ignore it. The setting is saved with the edit as a multiple, not in pixels, so it holds at every
export size.

## Emulsion Border

Emulsion Border prints the film just outside the camera gate around the photograph. Nothing
about the band is drawn: the photograph's light falls on a piece of film larger than the aperture,
and that whole piece is developed and printed as one frame (`UnexposedEdge`,
`FilmRender.developFilm(beyond:)`).

- **The lens's image.** The host places the photograph in the middle of the larger frame and
  continues it past its edges from the nearest edge pixel: the world does not stop at the frame
  edge, the gate does. Everything the lens forms — the direct image, a diffusion filter's halo,
  veiling glare, a camera preflash — is formed over the whole plane, so the glare meter and the
  halo near the edge see what they see in the photograph's own develop.
- **The gate.** The renderers then pass that light through the gate (FOTUFILM_CONFIG_GATE): 1
  inside the aperture, 0 beyond it, and between them the shadow of the edge. Everything after it —
  halation, emulsion diffusion, couplers, development, grain, the print — is the film's own and runs
  over the whole frame, the shaded film included.
- **Tone.** Beyond the gate the film saw no light, so it prints as whatever the chosen output makes
  of unexposed film: base plus fog on a negative, maximum density on a reversal stock, through the
  same receiver, levels and viewing light as the picture. Auto Levels prints on the photograph's own
  reading rather than one metered over film the gate shaded.
- **Glow and grain.** The frame's light spreads past the gate through the stock's halation and
  emulsion diffusion, as far and in the colour its layers return it, and the grain runs unbroken
  from the picture into the band at the stock's own density. The film's scale is the photograph's:
  it is reckoned from the aperture, not from the larger buffer.
- **Inner edge.** The gate's aperture plate stands in front of the emulsion by
  `gateSeparationMM` = 0.15 mm, about one support thickness between the film's outer and inner
  rails. Each point of the exit pupil casts the edge at a different place, so each edge passes the
  fraction of a uniform pupil disc on the open side of a straight line, of radius `0.15 / (2 N)` mm.
  The aperture's corners are rounded to `gateCornerRadiusMM` = 0.15 mm (FOTUFILM_CONFIG_GATE_CORNER),
  and the line is taken at the pixel's signed distance from that rounded edge; along the straight
  sides that is one axis's distance alone. The f/2 shadow is about 0.08 mm across, the softness
  full-frame prints show at the gate. All three figures are representative, not measurements of a
  particular camera.
- **Taking aperture.** `N` is the photograph's own aperture (`UnexposedEdge.TakingAperture`): EXIF
  FNumber, or the APEX ApertureValue where only that is written, read without needing a lens name.
  The gate's shadow is the cone of light reaching the gauge's gate, so the recorded f-number is
  scaled to the same picture on the gauge, with the same angle of view and depth of field:
  `N × gauge diagonal / sensor diagonal`, with the sensor from `SensorFrame`. An X-T50 frame at
  f/1.4 is about f/2.1 on 135; a phone's f/1.78 main camera is about f/6.3. Without a measured
  sensor the picture is taken to have been exposed on the gauge itself, and without a believable
  f-number (0.7 to 128) the shadow is drawn at `referenceFNumber` = f/2. However far the lens is
  stopped down, the edge stays no sharper than its own Fresnel fringe, `sqrt(λ z)` across at
  λ = 550 nm: the radius is `sqrt((0.15 / (2 N))² + (sqrt(λ · 0.15) / 2)²)` mm. That adds under 1%
  at f/2 and is most of the shadow at f/16. The camera's own path keeps the aperture even though
  its gauge does not follow the sensor.
- **Width.** The film runs from the gate to the first thing that is not continuous emulsion, in
  the gauge's `FilmBorderGeometry`: the inner edge of the perforation row, the film's cut edge on
  unperforated gauges, or halfway to the next frame along the roll. On 135 that is about 0.70 mm
  across and 1.0 mm along the film; 120 and sheet film run to the edge. A photograph standing the
  other way from the aperture turns the film with it.
- **Outer edge.** A negative is printed in the enlarger's carrier, filed out to the film's outer
  edge where the band ends (FOTUFILM_CONFIG_CARRIER, `UnexposedEdge.carrier…`). The develop carries
  the film `carrierReachMM` = 0.22 mm further, and the carrier holds the printing light off it: up to
  6 D added to every record of the developed film, after the grain and before the enlarger's
  spread. The opening's corners are rounded to 0.3 mm, and its sides are filed by hand up to
  0.025 mm inside it, wandering over about 2 mm on a smooth value-noise lattice. It stands a step in
  front of the film, so its edge casts the shadow of a 0.15 mm disc, the enlarger's cone of light
  across that step. Across the shadow the paper sees a fraction of the light through the film base,
  and its records leave their toe unevenly, so the edge passes through the paper's own fringe
  colour on its way to white. Beyond it the paper saw no light at all, and the margin is that
  paper as developed (the developed piece's corner), so there is no seam. A transparency is seen
  whole, with no carrier. These figures are representative, read from full-frame prints, not
  measurements of a particular carrier.
- **Selective edits** are drawn on the photograph; their mask reaches the film beyond as the picture
  does, continued from the nearest edge.

The band is offered with any film and any roll or sheet gauge; integral instant film has its mask
there instead of emulsion and Normal has no film. The browser does not offer it: its kernels carry the
gate, but its host does not build the larger frame. The paper margin beyond the band follows the
crop at 7% of the photograph's short side horizontally and 11% vertically, rounded up to whole
pixels. Beyond a negative's carrier it is the paper as developed; around a transparency it is the
selected reflection paper's modelled base and Viewing Light where there is one and a neutral 0.91
display-linear P3 mount elsewhere. The developed film goes down whole at integer
pixel coordinates in its colour profile and 16-bit precision. The photograph inside it is the same
develop as the band, so it is not the photograph's own unframed develop pixel for pixel: near the
gate the film's halation and scatter see the dark film beyond it instead of more picture.

## Positive paper

Ilfochrome/Cibachrome uses the same 4 × 6 inch presentation geometry. Its unexposed
margin retains maximum dye density and is dark. The polyester surface is represented
as smooth, without the RC lustre stipple. Border color shares the positive paper's
provisional receiver model; it is not a measured substrate spectrum. Film Border
shows the original reversal transparency while retaining the chosen paper for later use.
