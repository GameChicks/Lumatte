# Experimental layered transport

The planar engine and CLI can replace legacy halation and emulsion MTF with an
explicit optical construction. Existing films keep their existing rendering unless
a construction is supplied. No included commercial film is relabeled as measured
layered transport.

## Included inferred constructions

Every film in the catalogue except Instax Mini and Instax Wide includes an inferred
construction. Select **Layered Transport** to use them. Legacy remains the default and retains its
separate halation parameters.

Each construction places a film's backing at its measured density and solves the
return from the optics. The absorber's density at 450, 550 and 650 nm is the middle
of the measured range for its kind of backing: an antihalation undercoat or dyed
backing 0.55 in red and blue and 0.87 in green, a remjet 0.95. It is interpolated in
log space and held outside that interval. The red return is the launch below times
the capture the construction returns; green and blue keep the film's ratios to red,
which carry its masking and filter layers. Colour receivers sit at measured relative
depths in an 18 µm coating, a typical colour-negative total: blue 5.2 µm, green
9.4 µm and red 15.5 µm below the surface. Monochrome films use one receiver at
mid-depth. Coating thicknesses and capture probabilities remain generic assumptions.
Two-scale compact responses are reduced to the same second moment; Legacy's return
colour matrix and extra diffusion are not part of this model.

Most constructions use an effective undercoat and acetate support, and return about
0.4% of the direct red exposure. Historical cinema and Kodachrome constructions put
the absorber behind the support. VISION3 places an undercoat at remjet density and
returns 0.03%. CineStill 800T and 400D are VISION3 500T and 250D's constructions
without the absorber, as the respools are the parent films without their backing:
nothing left in the stack absorbs the red light the backing did, and they return
27%. Films without a measured backing family (Phoenix 200, the LomoChromes,
Aerochrome 1443 and Infrared Color) keep their previous strength and a shape fitted
to Legacy's estimate.
Aerocolor splits its undercoat density evenly between the undercoat and a dyed
backing on a PET support proxy.

Aerochrome 1443, Infrared Color, the three LomoChromes and Phoenix 200 have no halo
shape estimate. Legacy renders them with a Lambertian launch whose round-trip
transmittance returns the film's strength, and their constructions are fitted to
that shape instead.

All included constructions pass the renderer's existing eight-component,
0.005 edge-error limit, nonnegative partition checks, power accounting and
angular-quadrature checks. Those checks establish numerical behavior, not
agreement with real film. Instax has no inferred candidate and retains the
illustrative fallback described below.

Donor-layer films transport their fourth record with the other three, solved as a
fourth receiver at its own depth. The donor layer is coated directly beneath the green
record, between it and the red, at a measured 11.8 µm against the green record's 9.4 µm
and the red's 15.5 µm. It is sensitive on the green record's short-wave side, so the
light it receives back from the base is green light: it takes the green receiver's
launch, capture, return strength and core, and only its depth differs.

## Construction controls

Four controls under Halation reshape the construction itself. Base Haze applies to
both models; the other three apply to Layered Transport and leave Legacy unchanged.

| Control | Effect |
| --- | --- |
| Base Haze | Blurs the returned light by the base's internal scatter, a Gaussian sigma in µm. 0 keeps the film's own figure |
| Anti-Halation | Scales the absorption of every layer except the base: the anti-halation layer, or the dyed coating standing in for one. 0 removes them |
| Base Thickness | Scales the base's thickness. The ring widens; the amount does not change |
| Pressure Plate | A reflecting plate pressed against an open back. Light the back surface lets out returns from it. An opaque backing hides it |

A film's return ratio is calibrated, but its construction's absorber is fitted to
the halo's shape rather than its amount. Scaling the return by the capture alone
would let a thinned backing return thousands of times the light. An adjusted
construction's return therefore runs between two ends: the film's own ratio at its
own construction, and, for the same stack without absorbers, the launch times its
capture. The launch is the light a receiver lets through toward the base per unit
it captures, `(1 − p)/p`, so 1 at the constructions' capture probability of 0.5.
The physics sets the red record's curve between the ends, and the other records keep
the film's ratios to red, which carry its masking and filter layers. Removing the
backing from a colour negative returns about 27% of the direct red exposure, as
CineStill's constructions do.
A strongly adjusted construction that eight components cannot fit within the 0.005
edge error is fitted within 0.02.

The inferred fields share the film profiles' CC BY-SA 4.0 license. Aerocolor's
generic PET optical constants use the CC0
[RefractiveIndex.INFO Zhang dataset](https://refractiveindex.info/?shelf=organic&book=polyethylene_terephthalate&page=Zhang).
They are a material proxy, not measurements of that film's support. The source's
400 nm values are held over 380–395 nm. No measured gelatin or emulsion dispersion
is claimed.

CineStill 400D also uses the catalogue's VISION3 250D image-dye partition as an
explicit rendering proxy, with updated halation colour ratios and return matrix.
Its other capture, curve, grain and development parameters are preserved. This
proxy does not establish that the two films share a manufacturing recipe.

```sh
swift run -c release fotufilm input.exr output.png --stock example-negative-400 \
  --transport construction.json --halation 1 --grain 0
```

Use an existing stock ID from `--list-stocks`. `--transport-backend metal` runs the
transport pipeline on Metal; spectral scene preparation and development use the CPU
reference with either backend. This is not an end-to-end GPU
renderer. `cpu` is the default. An unavailable backend produces a render error.

Use `--iterations 31` to measure 30 repeated warm frames after the first render.
The report includes median and p95 processing latency and achieved frames per second.
Warm timings exclude image decoding and output encoding and include scene preparation,
transport, development, and output-medium processing. They are sequential repeated-image
measurements; a 4K30 workload needs each 3840×2160 frame in 33.33 ms. Failed renders
abort the benchmark instead of contributing a timing sample.

In Swift, decode and validate a `LayeredTransport`, assign it to
`FotufilmEngine.Options.layeredTransport`, and call
`try FotufilmEngine(stock: stock, options: options).processChecked(linearRGB: image)`.
Input is linear Rec.2020 with middle grey at 0.18. Full, negative, print, and texture
stage semantics are retained. The throwing entry point reports preparation and
unsupported-feature errors. The older nonthrowing entry point retains its fatal
error convention.

## Construction contract

`LayeredTransport` is a Codable construction with `revision: 1` and
`kind: "conditional-launch"`. It contains:

| Field | Meaning |
| --- | --- |
| `constructionID` | Construction identifier |
| `wavelengthsNM` | Exactly the engine's 81 samples, 380–780 nm at 5 nm intervals |
| `layers` | Front-to-rear layers: unique ID, thickness in mm, refractive index, and absorption in inverse mm |
| `frontIndex`, `rearIndex` | Surrounding media at the two external interfaces |
| `rearReflectance` | Optional opaque backing reflectance; the complement is absorbed. Omit for the dielectric rear interface |
| `rearPlateReflectance` | Optional reflectance of a plate against the dielectric rear interface; not allowed with `rearReflectance` |
| `recordDepthMM` | Three capture planes measured from the exposing surface, in R/G/B record order |
| `angularExponent` | Three cosine-power conditional launch distributions |
| `captureProbability` | Three probabilities of capture per subsequent crossing of the receiver plane |
| `returnedToDirect` | Three independently supplied returned/direct response ratios |
| `coreSigmaMM` | Three compact no-return Gaussian widths; replaces, rather than adds to, legacy MTF |

Spectral fields accept either one constant or all 81 samples. Three-row fields
use one spectrum per receiver. A `FilmStockDefinition` carrying a construction
uses schema 3. Schemas 1 and 2 reject that field; schema 3 requires it. Legacy
halation profiles and return matrices may coexist with a construction: model
selection chooses which operator runs. Adding a construction therefore preserves
the stock's Legacy rendering. Other stock data and the developed/printed response
remain available.

## Optical and numerical model

The solver follows an unpolarized forward launch from each receiver, splitting
s and p power at each actual dielectric interface. Snell's invariant determines
the angle in each layer. Beer attenuation uses the oblique path length. Rear
reflections tag returned paths; each later crossing captures a declared fraction.
Paths with equal segment traversal counts merge exactly in power. The solver
tracks returned capture, capture before a rear return, absorption, escape, and
unresolved power. It fails if the unresolved conditional power exceeds `1e-7`.
Angular quadrature is deterministic; this residual is not an angular integration
error bound.

Each solved returned kernel is normalized separately from its return amount.
For receiver c and wavelength λ, the direct capture keeps weight one and the
returned kernel adds `ratio` on top of it: returned light is light that crossed
the emulsion and came back, which an anti-halation layer would have absorbed, so
it never takes exposure from the point it left. Both kernels are nonnegative and
integrate to one. The direct component tables sum to the stock's calibrated
pointwise exposure table, including its gamut continuation, so the curves keep
describing the film as measured with its backing; a film that returns light
gains that light everywhere, most visibly as a glow into darker surroundings.

The amount control scales every wavelength/receiver return ratio together,
linearly. It does not refit the angular distribution or reinterpret amount as an
absorption change. Return Spectrum changes the wavelength ratios. Source Colour
blends the returned light toward a common positive spatial endpoint as an
explicit creative control.

The compiler fits convex pairs from at most eight solved radial basis kernels,
choosing for each solved kernel the pair and share with the smallest largest error,
plus the three compact no-return kernels. It checks the edge-spread fit at 128
distances and adds the uniform error bound from equal-mass radial compression
(`0.5/512` when compression is used). The default combined threshold is 0.005.
This sampled fitting certificate does not certify the optical inputs, angular
integration, or the final multiresolution discretization.

Pixel weights integrate the overlap of translated pixel cells with each radial
quadrature node. Narrow and broad radial bands use separate grid scales, so a
distant tail cannot blur the inner shoulder. Each stencil is positive and
normalized. Reduction uses positive area weights and reconstruction uses smooth,
positive cubic B-spline weights at reduced scales; stride one keeps the original
pixel samples. The image boundary extends the nearest true edge pixel.

One Halide pipeline spreads a component: it averages the component's light over a
2 x 2 pyramid of power-of-two cells, applies each stride's stencil (the bands at one
stride added, each scaled by its share), reconstructs every stride at the pixel
centres and adds the result to the running exposure. A component's bands therefore
travel as one table of up to 13 strides with stencils of radius 12 or less. The
same pipeline runs on the CPU, on Metal (just-in-time or compiled ahead of time) and
in the browser's SIMD WebAssembly, and they agree. Components stream one at a time.
Without lens flare or diffusion, a component's head is the scene through its
exposure table and the camera gate, so native hosts expose it inside the same
pipeline: the scene's creative exposure and table coordinates are found once per
frame, and the scene and the running sum stay on the device until the last
component. With flare or diffusion each component takes its own head render, so the
lens effects stay exact per component. Amount changes reuse cached endpoint tables;
a white balance rebuilds only the tables, and a construction or halation edit reuses
every receiver solve it does not change.

## Scope and limitations

This is the conditional-launch analytical model, not a solved volumetric
multiple-scattering radiative-transfer equation. It does not infer optical constants
or commercial-film calibration from stock names. The no-return core and return
ratios are supplied independently; captured conditional power is not an absolute
scene-photon absorption measurement.

The current execution paths use whole-frame intermediates with streamed components.
A fixed working-memory budget and transport strip scheduling are not implemented.
Additional Gaussian support haze and stage-sequence exports are rejected. Apple camera capture encodes transport in the same command buffer as
its existing HDR frame graph; the editor and plugin hosts use the AOT transport path.
Browser transport packs use SIMD WebAssembly. Model selection and export details
are described below.

## Reproduce the A/B

```sh
FOTUFILM_TRANSPORT_AB_OUTPUT=/tmp/fotufilm-transport-ab \
  swift test -c release --filter LayeredTransportComparisonTests
```

On macOS this writes legacy/layered PNGs, a Metal comparison when available, the
illustrative construction JSON, and numeric metrics. The scene, construction,
and negative stock are synthetic. The test uses the same scene, return strengths,
gauge, development, and print on both sides; grain, adjacency, and local tone are
disabled to make optical differences legible. The comparison is a diagnostic of
the models, not evidence that the construction matches a specific film stock.

## Model selection across hosts

`Options.halationModel` selects `legacy` (default) or `layered`. An explicit
`Options.layeredTransport` construction overrides that selector. With `layered`, the
stock's construction is used when present; otherwise the engine creates an explicitly
illustrative stack from the selected format and the stock's return strength and core
spread. This fallback is not a measured stock calibration.

Mac and iOS persist the selector in Film Model settings. Resolve appends bridge slot
49, and Final Cut appends parameter 88; zero retains Legacy for existing projects.
Apple hosts use AOT scene/development passes and the transport pipeline compiled ahead
of time for Metal; Linux hosts compile it ahead of time for the CPU. Native zoomed
previews currently develop the complete virtual frame before cropping to preserve
transport tails and reduction-grid alignment. This increases memory use at high zoom.

`--halation-model legacy|layered` selects the CLI model. Browser pack version 4 adds
the head mask, the stages the continuation leaves off, head/tail configurations,
component exposure LUTs and each component's stencil table (its nonempty strides
only) to the version 2 size-ladder layout. The browser lays the head and tail
configurations over the frame's own, so the controls, local tone and screen levels
apply, and shares the camera preflash among the heads as native hosts do. `tools/build-wasm.sh` exports both `.pack` and
`.layered.pack` for supported stocks. Browser packs carry three records, so the index
declares `layeredTransport: false` for donor-layer stocks, which require Legacy in the
browser. The browser reports that limitation without substituting models. Layered packs use the SIMD backend,
including when WebGPU is available;
Legacy keeps its existing WebGPU/SIMD selection. Runtime exposure, colour and grain
controls work with either model. Layered stage-sequence exports and browser lens
flare/diffusion pack overrides are rejected explicitly. Rebuild every AOT kernel and
browser pack after the appended record-exposure configuration slot; old pack/config
length mismatches are rejected by the runtime. Browser transport develops a complete
frame to preserve convolution support and reduction-grid alignment; its memory use
therefore grows with the full image, even when Legacy would render in tiles.
