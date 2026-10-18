import Foundation

/// The film simulation pipeline.
public struct FotufilmEngine {
    /// Whether the Halide engine was linked into this build.
    public static var isHalideBackendAvailable: Bool { HalideBackend.isAvailable }

    public struct Options: Sendable {
        /// Exposure compensation in stops (applied to the camera exposure).
        public var exposureEV: Float = 0
        /// Source range above diffuse white as a linear multiple. One is SDR identity. HDR values
        /// drive `AutoAdjustment.headroomHighlights` to fit declared range into measured latitude.
        public var sceneHeadroom: Float = 1
        /// The fraction of `sceneHeadroom`'s stops above white that reaches the film, 0...1; the
        /// rest is compressed into it (`HDRHighlightRange`). 1 keeps the whole declared range.
        public var hdrRange: Float = 1
        /// How strongly the kept range is rolled off into the film's latitude, 0...2: a multiple
        /// of the automatic highlight shaping `AutoAdjustment.headroomHighlights` solves. 0 adds
        /// none, and the film's own shoulder takes the light.
        public var hdrRollOff: Float = 1
        /// Spectral scene-light edit: Kelvin is a mired displacement from D65 when capture
        /// light is supplied; tint adds locus-relative delta-uv units. Input RGB must already
        /// be neutralized for its capture light. These controls never apply RGB correction.
        public var whiteBalance: WhiteBalance = .neutral
        /// Scene-referred highlight shaping, -1...1: an exposure shift of up to 3 EV that fades in
        /// over the six stops above mid-grey, applied before the film model so the emulsion sees
        /// the adjusted light.
        public var highlights: Float = 0
        /// Scene-referred shadow shaping, -1...1: the same shift fading in
        /// below mid-grey, keyed the same regional way.
        public var shadows: Float = 0
        /// Whether the highlight/shadow shifts key to the regional base above.
        public var localTone: Bool = true
        /// Chroma multiplier about each pixel's own luminance; 1 is untouched
        /// and 0 renders the scene achromatic before the film responds.
        public var saturation: Float = 1
        /// Signed chroma boost weighted toward the least colourful pixels,
        /// -1...1; already-vivid colours are left alone.
        public var vibrance: Float = 0
        /// Multiplier on the stock's grain strength (0 disables grain).
        public var grainScale: Float = 1
        /// Override of the stock's grain-size mixture: the fraction of the published
        /// granularity's variance carried by the coarse mottle field
        /// (`FilmStock.grainMottleShare`), 0...0.9. The published RMS stays the anchor
        /// whatever the split — the two components divide its variance, each corrected
        /// through the 48 µm aperture at its own size. nil — the default — renders the
        /// stock's own value, and a stock that says nothing is the single-radius field
        /// bit-identically.
        public var grainMottleShare: Float? = nil
        /// Coarse-population clump radius as a multiple of `grainSizeMM`, clamped to 1...8.
        /// Each component remains corrected against the 48 µm aperture. Nil uses the stock value.
        public var grainMottleSizeRatio: Float? = nil
        /// How coarse an explicitly requested mottle population lands on a
        /// delivery lattice, as a multiple of the emulsion's clump radius. A
        /// sheet-ratio clump sits under a pixel at video sizes, where neither
        /// the frame nor its encoder can hold it.
        public static let deliveryMottleSizeRatio: Float = 8
        /// Applies `deliveryMottleSizeRatio` when a mixture share is explicit but its size is not.
        /// Nil share leaves both settings unchanged.
        public mutating func completeDeliveryMottle() {
            guard grainMottleShare != nil else { return }
            grainMottleSizeRatio =
                grainMottleSizeRatio ?? Self.deliveryMottleSizeRatio
        }
        /// Which model develops the grain.
        public var grainModel: GrainModel = .clumpField
        /// How the film grain model (`GrainModel.film`) lays its grain: grain size, colour grain,
        /// each layer's share and the scan's softness. The other models ignore it.
        public var filmGrain = FilmGrain.Look()
        /// Multiplier on the fraction of light the base returns (0 disables halation).
        public var halationScale: Float = 1
        /// Red returned/direct exposure ratio, 0...1, before amount and spectrum edits.
        /// Nil follows the stock. An override scales all record returns together.
        public var halationReturnRatio: Float? = nil
        /// How much the halo keeps the source's own colour instead of the stock's layered
        /// red, 0…1. The returning light re-enters the emulsion from below, so the bottom
        /// (red) record takes nearly all of it and a colour film's ring is red whatever the
        /// light was. At 1 the dimmer records are raised to the strongest record's return —
        /// the scattered light then carries the source's own balance, a pronounced ring the
        /// colour of the light. A look choice, not a stock property: 0 is the film.
        public var halationSourceColour: Float = 0
        /// Per-wavelength gain on the full-grid halation return spectrum. Empty preserves the stock
        /// spectrum. The engine reduces the curve through each record's sensitivity;
        /// `halationSourceColour` and `halationScale` apply separately.
        public var halationReturnGain: [Float] = []
        /// Override of the stock's `halationHazeMM` — the support's impurity scatter, as a
        /// Gaussian sigma in millimeters softening the halo's edges. `nil` — the default —
        /// renders the sheet's own figure.
        public var halationHazeMM: Float? = nil
        /// Uses a provisional spatial profile when no independently calibrated profile exists.
        public var useEstimatedHalationProfile: Bool = false
        public var halationModel: HalationModel = .legacy
        /// Explicit research construction override. The stock's schema-2 construction is used
        /// otherwise. Supported by the checked planar renderer, not legacy realtime/AOT APIs.
        public var layeredTransport: LayeredTransport? = nil
        public var transportBackend: TransportBackend = .cpu
        /// Layered Transport: multiplier on the absorption of every layer of the construction but
        /// its support — the anti-halation layer, or the dyed coating standing in for one. 0
        /// removes them. Legacy ignores it.
        public var antiHalationScale: Float = 1
        /// Layered Transport: multiplier on the support's thickness. Legacy ignores it.
        public var baseThicknessScale: Float = 1
        /// Layered Transport: reflectance of a pressure plate against the back of the film, 0…1.
        /// 0 — the default — leaves the back open. Legacy ignores it.
        public var pressurePlateReflectance: Float = 0
        /// Multiplier on taking-lens veiling glare. The default is 0 because photographic inputs
        /// already include lens glare. Enable it for synthetic light or to model additional glare
        /// relative to the capture lens.
        public var flareScale: Float = 0
        /// Uniform additive camera film preflash relative to metered mid-grey, 0...0.25 (0 = off).
        /// Lifts shadow detail out of the emulsion toe and reduces shadow contrast without burning highlights.
        public var cameraPreflash: Float = 0
        /// Strength of DIR coupler inhibition (0 disables inter-image effects, 1 is the calibrated
        /// stock). Creative overdrive above 1 is compressed so the 2 endpoint applies 1.5 physical
        /// doses rather than letting spatial inhibition grow without bound.
        public var couplerScale: Float = 1
        /// Optional adjacency-model override, leaving the stock's authored model as the default.
        public var adjacencyModel: AdjacencyModel? = nil
        /// Fraction of inter-layer inhibition carried by the broad spatial component, 0...1.
        /// Nil uses the stock; zero preserves its original transport. Uniform colors are unchanged.
        public var chromaticFringeAmount: Float? = nil
        /// Broad transport sigma in millimeters on the film. Nil uses the stock (normally 0.1 mm).
        /// A radius at or below the stock's core transport radius adds no fringe.
        public var chromaticFringeRadiusMM: Float? = nil
        /// Multiplier on how far the released inhibitor reaches through the layer stack, applied to
        /// every interlayer alike (`CouplerGeometry.interlayerTransmission`). 0 seals the layers off
        /// from each other; above 1 crosses more.
        public var couplerRangeScale: Float = 1
        /// The same reach, set per interlayer instead of together: index 0 is the red–green
        /// scavenger interlayer and index 1 the green–blue yellow filter layer. `nil` — the default
        /// — applies `couplerRangeScale` to both, which is exactly what a single reach has always
        /// done, at every setting and not merely at 1.
        public var couplerGapReachScales: [Float]?
        /// Multiplier on the retained self-inhibition
        /// (`CouplerGeometry.selfRetention`), the matrix diagonal.
        public var couplerSelfScale: Float = 1
        /// Years the roll sat past its process-by date at room temperature, 0 up. Drives
        /// the age transform (`FilmStock.expired(years:)`): the lab's one-stop-per-decade
        /// speed loss with the blue-sensitive layer going first, base fog rising in
        /// proportion, and grain up with the fog that causes it. The print re-times
        /// through the risen base, so the mid corrects and the crossed, muddy toe is what
        /// shows — shoot it with the same extra stop the lab rule asks for and the look
        /// is the shadows', not the whole frame's. 0 — the default — is the fresh roll,
        /// bit-identically.
        public var expiredYears: Float = 0
        /// Shutter duration in seconds from capture metadata or manual input. Beyond the stock's
        /// reciprocity threshold, per-layer exposure and print-timing corrections are bounded by
        /// the last measured table entry. Nil, in-range values, and stocks without reciprocity data
        /// leave rendering bit-identical.
        public var shutterSeconds: Float? = nil
        /// Stops of push (positive) or pull (negative) development, the instruction on the
        /// film can's sticker. Non-zero values must name an exact condition in the stock's
        /// `developmentProfile`, each carrying complete curves and their `basis`; other values and
        /// stocks without conditions are rejected rather than interpolated. Pair a push with the
        /// underexposure it was bought for (`exposureEV`). 0 — the default — is the stock pack's
        /// reference development, bit-identically.
        public var developmentEV: Float = 0
        /// How much of the developed silver the bleach leaves in the negative, 0...1. The bleach's
        /// whole job is to take the metallic silver back out once the dyes are formed; skipping it
        /// — the lab service behind the bleach-bypass look — leaves a black-and-white image
        /// superimposed on the colour one. The retained silver is spectrally flat, so the print
        /// gains contrast and loses chroma together, and the print exposure re-anchors on the
        /// denser mid-grey exactly as a lab re-times a skip-bleach negative. 0 — the default and
        /// the fully bleached C-41 every existing render is — changes nothing. Monochrome stocks
        /// ignore it (their image *is* the silver), as does a reversal stock's direct positive.
        public var bleachBypass: Float = 0
        /// Optional digital correction of film channel-contrast mismatch, not a physical
        /// printer-light adjustment. Off by default so the measured record slopes survive.
        public var printCorrection: Float = 0
        /// The lamp house the negative is enlarged under. `.diffuser` — the default, and the
        /// diffuse densitometry every stock sheet is read in — changes nothing. `.condenser`
        /// applies the Callier effect: the negative's densities read at their specular values,
        /// so a silver negative prints harder (`Enlarger.silverCallierCoefficient`) and a dye
        /// negative only slightly so, with the print re-timed through the scaled mid-grey. Read
        /// only where a reflection sheet is optically enlarged; see `Enlarger.illuminates`.
        public var enlarger: Enlarger = .diffuser
        /// Optional simulated optical printer. Nil preserves the existing printing model.
        /// Applies only to enlarged reflection prints; exposure is separate from film exposure.
        public var printer: PrinterProfile? = nil
        /// Uniform additive paper preflash relative to paper mid-exposure, 0...0.10 (0 = off).
        /// Softens print highlight contrast and brings delicate highlights onto the paper curve
        /// without fogging maximum black (D-max). Applies only to optical prints.
        public var printerPreflash: Float = 0
        /// Where the developed image is finished. `nil` — the default — takes the medium the
        /// loaded stock was designed for, so a motion-picture camera negative reaches its release
        /// print stock and a still negative reaches the measured sheet, without the caller naming
        /// either. A stated medium, including the developed negative itself, is honoured for any
        /// stock that can reach it.
        ///
        /// Read it through `paper(for:)`; the stored value is the request, not the answer.
        public var paper: PrintPaper? = nil
        /// The lamp a physical print is viewed under, as a correlated colour temperature in
        /// kelvin: the CIE daylight series from 4000 K up (5003 is D50), and the Planckian
        /// radiator below it (2856 is CIE A). Bradford adaptation carries the lamp white to the
        /// D65 display while retaining dye metamerism. nil uses the medium's calibrated reference:
        /// D50 for reflection paper and 5400 K filtered xenon for a cinema print. Scans, screen
        /// output, viewed negatives, and reversal stocks ignore this value. Finite positive
        /// values are bounded to 1000...25000 K and canonicalized to 100 K buckets; invalid or
        /// non-positive values use the reference. The developed print is not re-timed.
        public var printViewingKelvin: Float? = nil
        /// The grade laid over the finished image — lift, gamma and gain by band.
        public var grade: ColorGrade = .neutral
        /// Which signal that grade works on. `.linear` is what this engine has always done and stays
        /// the default, because switching re-grades every picture carrying a non-neutral grade — the
        /// highlight band most of all, whose gain opens the whole midrange once it is spent on the
        /// encoded signal. `.encoded` is where a grading suite's three-way corrector works. A
        /// neutral grade is the identity either way.
        public var gradeSpace: ColorGrade.Space = .linear
        /// Film gauge the frame is exposed on.
        public var format: FilmFormat = .still35
        /// Fraction of the film frame's short edge the rendered image spans, 0...1. Cropping
        /// throws film away and enlarges the rest, so a cropped picture holds more pixels per
        /// millimetre of emulsion than the full frame at the same output size — grain, MTF,
        /// halation, adjacency and coupler diffusion all scale with it. 1 — the default, and
        /// every uncropped render — is the whole frame, bit-identically.
        public var frameCoverage: Float = 1
        /// Seed for the grain generator; same seed + input = same grain.
        public var seed: UInt64 = 0x46494C4D
        /// Show the developed negative rather than the positive medium it would otherwise make.
        /// This also selects the appearance of the `.negative` output medium; when that medium is
        /// chosen and this value is nil, the engine uses `.lightBox`.
        public var negativeViewing: NegativeViewing? = nil
        /// How developed film's tone is placed on Digital Reference: a fixed calibrated exposure,
        /// a graded paper curve at that exposure, or per-frame levels on that curve. A positive
        /// has no curve to grade, so the graded styles normalise it to SDR instead — its clear
        /// base or its brightest content to white. Other media and integral prints ignore it.
        public var digitalReference: DigitalReferenceStyle = .default
        /// The frame's brightest content, metered by the host as scene stops over mid-grey after
        /// `exposureEV`, as `FilmEngineInvocation.sceneHighlightStops` reads it, for Digital
        /// Reference's `.autoLevels` to re-time the frame on and Lab Scan's levels to place near
        /// white. Both remove `exposureEV` again, so the edit's exposure is not timed out. Nil
        /// requests the shared renderer's whole-frame meter. An invocation without scene pixels
        /// falls back to the fixed print. Video hosts can supply a temporally smoothed measurement.
        public var sceneHighlightStops: Float? = nil
        /// The frame's per-record medians, in stops over mid-grey metered like
        /// `sceneHighlightStops`, for Auto Levels' colour. Nil requests the shared meter, as that does.
        public var sceneChannelMedians: SIMD3<Float>? = nil
        /// The frame's median, darker tenth and brighter twentieth, in stops over mid-grey
        /// metered like `sceneHighlightStops` and read only alongside it: where Auto Levels reads
        /// whether a negative's shadows sit on its base and its sky past white.
        public var sceneToneStops: SIMD3<Float>? = nil
        /// Set when this develop is the unexposed film just outside the camera gate, which the
        /// Emulsion Border prints around the photograph. See `UnexposedEdge`.
        public var unexposedEdge: UnexposedEdge.Develop? = nil
        /// The contrast of the graded paper curve Digital Reference's graded styles print a
        /// negative through, as a variable-contrast paper grade 0…5. Grade 2 is the calibrated
        /// curve; softer grades roll highlights off earlier, harder grades later, each holding
        /// mid-grey at 18% and the film base at display black. Reference Exposure, positives and
        /// other media ignore it.
        public var screenGrade: Float = 2
        /// The peaks of the three bands Digital Reference reads a colour negative's dyes through,
        /// in nanometres. The paper's own measured bands by default; each placed elsewhere slides
        /// that band's measured shape along the spectrum. Other media and films ignore it.
        public var receiverBands: ReceiverBands = .paper
        /// The exposure of the screen conversion, in stops on top of the chosen style: positive
        /// lightens, the way a scanner's exposure does. On a negative it is the printer's exposure
        /// with the sign a screen expects; on a positive it is the scanner's gain. On Lab Scan it
        /// is the scanner's density key, lightening a negative's scan at mid-grey. Other media
        /// ignore it.
        public var screenExposureEV: Float = 0

        /// Lab Scan's dodging, as a share of the scanner's own: 0 turns it off, 2 doubles it.
        public var labScanDodging: Float = 1
        /// Lab Scan's finish, as a share of the minilab scanner's colour and tone: 0 delivers the
        /// neutral characterized scan.
        public var labScanLook: Float = 1

        /// Digital Reference and Lab Scan colour-negative correction, in relative units (-1...1).
        /// Positive cyan/magenta/yellow reduces red/green/blue after metering, before the
        /// receiver curve. Equal corrections change density; no brightness compensation.
        public var screenCMY: SIMD3<Float> = .zero
        /// Which span of the pipeline this render performs. `.full` — the default — is scene
        /// light in and a finished image out, and is what every render did before the seam had a
        /// name. The other three cut the pipeline at the density boundary the engine has always
        /// had; see `PipelineStage` and `NegativeInterchange` for what crosses it.
        ///
        /// A stage is not a look: nothing here selects different physics, only which of the
        /// stock's own stages this particular render runs.
        public var stage: PipelineStage = .full
        /// Which spatial stages `stage == .texture` lays over the frame. Ignored by every other
        /// stage, where the selection is the ordinary strength levers.
        public var textureStages: TextureStages = .all
        /// A scanned negative's light controls on its print (`PrintFinish`), read only by
        /// `stage == .print`. Neutral, the default, leaves the print as it is, so `.negative`
        /// followed by `.print` still reproduces `.full`.
        public var printFinish: PrintFinish = .neutral
        /// The film reading of a scanned negative handed to `stage == .print` as its linear scan
        /// RGB, read by the kernels. Nil, the default, takes the input as densities.
        public var scanReading: ApproximateNegativeScan? = nil
        /// Explicit source illuminant for film exposure. Nil uses the stock's own reference
        /// light, for RAW and processed inputs alike. Decode and white-balance the input first;
        /// capture metadata does not select this value automatically. `whiteBalance` edits this
        /// light while the stock reference remains fixed calibration data.
        public var sceneIlluminantKelvin: Float? = nil
        /// Explicit source xy white. Opt in when simulating a stated capture light; decoder
        /// metadata alone must not populate this field in the stock-native default mode.
        public var sceneIlluminantChromaticity: SIMD2<Float>? = nil
        /// Measured scene SPD on 380...780 nm in 5 nm steps (81 samples). Overrides both
        /// capture chromaticity and temperature/tint edits. Arbitrary scale; equal-Y normalization.
        public var sceneIlluminantSpectrum: [Float] = []

        public var resolvedSceneIlluminant: WhiteBalance {
            resolvedSceneIlluminant(referenceKelvin: WhiteBalance.neutralKelvin)
        }

        public func resolvedSceneIlluminant(referenceKelvin: Float) -> WhiteBalance {
            let base = sceneIlluminantKelvin ?? referenceKelvin
            precondition(base.isFinite && base > 0 && whiteBalance.kelvin.isFinite
                         && whiteBalance.kelvin > 0 && whiteBalance.tint.isFinite, "invalid scene illuminant")
            let displacement = whiteBalance.mired - WhiteBalance.neutral.mired
            return WhiteBalance(
                kelvin: WhiteBalance.miredToKelvin(WhiteBalance.kelvinToMired(base) + displacement),
                tint: whiteBalance.tint)
        }

        public var resolvedSceneSpectrum: [Float] {
            resolvedSceneSpectrum(referenceKelvin: WhiteBalance.neutralKelvin)
        }

        public func resolvedSceneSpectrum(referenceKelvin: Float) -> [Float] {
            if !sceneIlluminantSpectrum.isEmpty {
                precondition(sceneIlluminantSpectrum.count == SpectralGrid.count
                             && sceneIlluminantSpectrum.allSatisfy { $0.isFinite && $0 >= 0 },
                             "scene SPD must have 81 finite nonnegative samples")
                _ = Illuminant.luminance(sceneIlluminantSpectrum)
                return sceneIlluminantSpectrum
            }
            let edited = resolvedSceneIlluminant(referenceKelvin: referenceKelvin)
            guard let white = sceneIlluminantChromaticity else {
                return Illuminant.spectrum(edited)
            }
            // Carry the measured off-locus chromaticity directly; only UI temperature movement
            // uses mired. The original decoder white is never rounded through a tint coordinate.
            let original = WhiteBalance.chromaticity(
                kelvin: sceneIlluminantKelvin ?? referenceKelvin, tint: 0)
            let offset = WhiteBalance.uvFromXY(white) - WhiteBalance.uvFromXY(original)
            let target = WhiteBalance.uvFromXY(WhiteBalance.chromaticity(
                kelvin: edited.kelvin, tint: edited.tint)) + offset
            return Illuminant.matching(WhiteBalance.xyFromUV(target),
                                       base: Illuminant.atLocus(kelvin: edited.kelvin))
        }

        /// Colorimetric approximation when no film is loaded; the film path integrates the
        /// complete spectrum against its own sensitivities instead of multiplying RGB.
        public var sceneLightGains: (r: Float, g: Float, b: Float) {
            if sceneIlluminantSpectrum.isEmpty && sceneIlluminantChromaticity == nil
                && resolvedSceneIlluminant.isNeutral {
                return (1, 1, 1)
            }
            let source = WhiteBalance.workingRGB(fromXY: Illuminant.chromaticity(resolvedSceneSpectrum))
            let reference = WhiteBalance.workingRGB(fromXY: WhiteBalance.chromaticity(kelvin: 6504, tint: 0))
            let gain = source / reference
            return (gain.x, gain.y, gain.z)
        }
        /// What is screwed onto the front of the lens, and how the exposure was set with it
        /// there. A filter is the one accessory that sits ahead of the whole engine, so it
        /// reaches only two things: the light the emulsion integrates — spectrally, against the
        /// stock's own three sensitivities, which is why the same filter behaves differently on
        /// a tungsten stock and a daylight one — and the lens's veiling glare, which every extra
        /// air-glass surface adds to. Empty, the default, changes nothing at all: no filter is
        /// not a clear filter, and a clear filter still costs light and still makes a ghost.
        public var lensFilters: LensFilterStack = .none
        /// A diffusion filter on the front of the lens — mist, fog, black mist, glimmer.
        ///
        /// Physically the glass is ahead of the emulsion: a
        /// share of the scene's light meets a particle and leaves in a new direction, and the
        /// lens images it somewhere else on the frame. The share that missed every particle is
        /// untouched, which is why a diffused picture keeps its edges instead of going soft.
        /// The engine convolves after spectral integration because both operations are linear and
        /// commute; that lets each film record use the kernel fitted to its sensitivity instead of
        /// assigning film wavelengths to RGB working-space primaries. nil is no filter.
        public var diffusionFilter: DiffusionFilter? = nil
        /// The lens's focal length in millimetres, when the source states one.
        ///
        /// Read only by the diffusion filter, which needs it because a ray deviated by an angle
        /// ahead of the lens lands `focal length × angle` off its unscattered position — so the
        /// same filter glows bigger on a long lens, exactly as it does in the world. nil falls
        /// back to the gauge's own normal lens (`FilmFormat.normalFocalLengthMM`), which is what
        /// the grade numbering on a filter's ring is calibrated around.
        public var focalLengthMM: Float? = nil

        public init() {}

        /// The medium this stock actually develops onto under these options: the stated request
        /// where the stock can reach it, the stock's own native medium where nothing was stated,
        /// and the only medium it can reach where neither applies.
        public func paper(for stock: FilmStock) -> PrintPaper {
            paper?.resolved(for: stock) ?? PrintPaper.default(for: stock)
        }

        /// Whether this render may deliver light above display white: a directly viewed
        /// positive on a medium that shows it. A positive levelled by a Digital Reference style
        /// has been normalised to SDR — its base or its brightest content is white — so it is
        /// delivered like a print.
        public func supportsHDRDelivery(for stock: FilmStock) -> Bool {
            let medium = paper(for: stock)
            return medium.supportsHDRDelivery(for: stock)
                && !medium.levelsPositive(for: stock, digitalReference: digitalReference)
        }

        /// The SDR delivery knee: at display white for everything bounded by its own white;
        /// only a directly viewed transparency rolls its extended highlight range off earlier.
        public func sdrShoulderKnee(for stock: FilmStock) -> Float {
            FilmSDRDelivery.shoulderKnee(carriesHeadroom: supportsHDRDelivery(for: stock))
        }
    }

    public var stock: FilmStock
    public var options: Options

    public init(stock: FilmStock, options: Options = Options()) {
        self.stock = stock
        self.options = options
    }

    /// Scene-linear RGB in, display-linear RGB out — at `options.stage == .full`, which is the
    /// default and what this has always done. Another span reads and writes what
    /// `PipelineStage` says it does: `.negative` returns the developed negative's densities,
    /// `.print` is handed them, and `.texture` returns the frame it was given.
    public func process(linearRGB image: ImageBuffer) -> ImageBuffer {
        do { return try processChecked(linearRGB: image) }
        catch { fatalError(error.localizedDescription) }
    }

    /// Recoverable development, preparation, and backend errors for either rendering model.
    public func processChecked(linearRGB image: ImageBuffer) throws -> ImageBuffer {
        try processChecked(linearRGB: image, cpuMemoryBudget: HalideBackend.defaultMemoryBudget)
    }

    /// Adds a host-supplied photographic exposure field during combined Legacy CPU development.
    /// The field is evaluated per tile and included in the intermediate-memory budget.
    public func processChecked(linearRGB image: ImageBuffer,
                               additionalRecordExposure: FilmRecordExposure) throws -> ImageBuffer {
        try processChecked(linearRGB: image, cpuMemoryBudget: HalideBackend.defaultMemoryBudget,
                           additionalRecordExposure: additionalRecordExposure)
    }

    /// Combined Legacy CPU rendering with an explicit intermediate-memory estimate in bytes.
    /// The default overload uses 192 MiB, excluding input/output frames and compilation caches.
    /// Broad spatial effects may need a larger budget; insufficient budgets throw before rendering.
    /// Layered Transport uses its own memory plan and does not use this limit.
    public func processChecked(linearRGB image: ImageBuffer, cpuMemoryBudget: Int,
                               additionalRecordExposure: FilmRecordExposure? = nil) throws -> ImageBuffer {
        if additionalRecordExposure != nil,
           options.transportConstruction(for: stock) != nil || options.stage != .full {
            throw TransportError.invalid("additional record exposure requires combined Legacy CPU rendering")
        }
        if let model = options.transportConstruction(for: stock) {
            return try LayeredTransportRenderer.process(image: image, stock: stock,
                                                         options: options, model: model)
        }
        var plain = stock; plain.layeredTransport = nil
        guard let output = try HalideBackend.process(image: image, stock: plain, options: options,
                                                    memoryBudget: cpuMemoryBudget,
                                                    additionalRecordExposure: additionalRecordExposure) else {
            throw TransportError.backend(Self.missingEngineMessage)
        }
        return output
    }

    /// Convenience: 8-bit sRGB interleaved RGB or premultiplied RGBA in, same format out.
    /// Alpha and any extra bytes are preserved; fully transparent output has zero RGB.
    public func processSRGB8(_ pixels: [UInt8], width: Int, height: Int, bytesPerPixel: Int = 4) -> [UInt8] {
        precondition(pixels.count >= width * height * bytesPerPixel)
        var linear = ImageBuffer(width: width, height: height)
        for i in 0..<(width * height) {
            let alpha = bytesPerPixel >= 4 ? pixels[i * bytesPerPixel + 3] : 255
            let unpremultiply = alpha > 0 && alpha < 255 ? 255 / Float(alpha) : 1
            let srgb = SIMD3<Float>(
                ColorScience.srgbToLinear(
                    min(Float(pixels[i * bytesPerPixel]) / 255 * unpremultiply, 1)),
                ColorScience.srgbToLinear(
                    min(Float(pixels[i * bytesPerPixel + 1]) / 255 * unpremultiply, 1)),
                ColorScience.srgbToLinear(
                    min(Float(pixels[i * bytesPerPixel + 2]) / 255 * unpremultiply, 1)))
            let working = ColorScience.linearSRGBToRec2020(srgb)
            for c in 0..<3 { linear.planes[c][i] = working[c] }
        }
        let out = process(linearRGB: linear)
        var result = pixels
        let ditherSeed = UInt32(truncatingIfNeeded: options.seed)
        let shoulderKnee = options.sdrShoulderKnee(for: stock)
        for i in 0..<(width * height) {
            let alpha = bytesPerPixel >= 4 ? Float(pixels[i * bytesPerPixel + 3]) : 255
            // Into the delivery's primaries first, then its shoulder: the order every other
            // sRGB delivery takes, so a saturated highlight is rolled in the space it is shown in.
            let srgb = ColorScience.linearDisplayP3ToSRGB(SIMD3<Float>(
                out.planes[0][i], out.planes[1][i], out.planes[2][i]))
            for c in 0..<3 {
                let v = ColorScience.linearToSrgb(
                    ColorScience.displayShoulder(srgb[c], knee: shoulderKnee))
                let dither = triangularDither(index: UInt32(i), channel: UInt32(c), seed: ditherSeed)
                result[i * bytesPerPixel + c] = UInt8(clamp(v * alpha + 0.5 + dither, 0, alpha))
            }
        }
        return result
    }

    /// Convenience: 8-bit Display P3 interleaved RGB(A) in — P3 primaries under the sRGB
    /// transfer, an Android or Apple "Display P3" bitmap — and the same format out. RGBA is
    /// premultiplied on input and output, with alpha preserved and zero RGB at zero alpha. Ingest
    /// steps the decoded P3 into the Rec.2020 working space; the developed print is already
    /// Display P3, so the way out is transfer-encoding alone, with no change of primaries.
    public func processDisplayP38(_ pixels: [UInt8], width: Int, height: Int,
                                  bytesPerPixel: Int = 4) -> [UInt8] {
        precondition(pixels.count >= width * height * bytesPerPixel)
        var timing = StageTiming()
        let count = width * height
        var red = [Float](repeating: 0, count: count)
        var green = red
        var blue = red
        // Both walks either side of the engine are per-pixel and independent, and on a phone they
        // were a third of a twelve-megapixel still between them — two transfer functions over
        // seventy-two million channels, on one core. They are the same arithmetic here, taken a
        // row at a time across the cores the frame is already going to use.
        pixels.withUnsafeBufferPointer { source in
            red.withUnsafeMutableBufferPointer { red in
                green.withUnsafeMutableBufferPointer { green in
                    blue.withUnsafeMutableBufferPointer { blue in
                        let bytes = source.baseAddress!
                        let r = red.baseAddress!, g = green.baseAddress!
                        let b = blue.baseAddress!
                        ParallelWork.forEach(iterations: height) { y in
                            let row = y * width
                            for x in 0..<width {
                                let i = row + x
                                let base = i * bytesPerPixel
                                let alpha = bytesPerPixel >= 4 ? bytes[base + 3] : 255
                                var p3: SIMD3<Float>
                                if alpha == 0 || alpha == 255 {
                                    // Nothing to undo, so the byte is the whole argument and the
                                    // transfer has only 256 answers. The table is those answers,
                                    // not an approximation of them.
                                    p3 = SIMD3(srgbDecodedByte[Int(bytes[base])],
                                               srgbDecodedByte[Int(bytes[base + 1])],
                                               srgbDecodedByte[Int(bytes[base + 2])])
                                } else {
                                    let unpremultiply = 255 / Float(alpha)
                                    p3 = SIMD3(
                                        ColorScience.srgbToLinear(
                                            min(Float(bytes[base]) / 255 * unpremultiply, 1)),
                                        ColorScience.srgbToLinear(
                                            min(Float(bytes[base + 1]) / 255 * unpremultiply, 1)),
                                        ColorScience.srgbToLinear(
                                            min(Float(bytes[base + 2]) / 255 * unpremultiply, 1)))
                                }
                                let working = ColorScience.linearDisplayP3ToRec2020(p3)
                                r[i] = working[0]
                                g[i] = working[1]
                                b[i] = working[2]
                            }
                        }
                    }
                }
            }
        }
        let linear = ImageBuffer(width: width, height: height,
                                 planes: [red, green, blue])
        timing.mark("ingest")
        let out = process(linearRGB: linear)
        timing.mark("engine")
        var result = pixels
        let ditherSeed = UInt32(truncatingIfNeeded: options.seed)
        let shoulderKnee = options.sdrShoulderKnee(for: stock)
        result.withUnsafeMutableBufferPointer { destination in
            out.planes[0].withUnsafeBufferPointer { plane0 in
                out.planes[1].withUnsafeBufferPointer { plane1 in
                    out.planes[2].withUnsafeBufferPointer { plane2 in
                        let bytes = destination.baseAddress!
                        let planes = [plane0.baseAddress!, plane1.baseAddress!,
                                      plane2.baseAddress!]
                        ParallelWork.forEach(iterations: height) { y in
                            let row = y * width
                            for x in 0..<width {
                                let i = row + x
                                let alpha = bytesPerPixel >= 4 ? Float(bytes[i * bytesPerPixel + 3]) : 255
                                for c in 0..<3 {
                                    let rolled = ColorScience.displayShoulder(
                                        planes[c][i], knee: shoulderKnee)
                                    let v = ColorScience.linearToSrgb(clamp(rolled, 0, 1))
                                    let dither = triangularDither(
                                        index: UInt32(i), channel: UInt32(c), seed: ditherSeed)
                                    bytes[i * bytesPerPixel + c] =
                                        UInt8(clamp(v * alpha + 0.5 + dither, 0, alpha))
                                }
                            }
                        }
                    }
                }
            }
        }
        timing.mark("egress")
        timing.report("displayP3-8 \(width)x\(height)")
        return result
    }

    /// Returns per-layer dye densities of the developed film — `NegativeInterchange`, and the
    /// same quantity `process` produces at `PipelineStage.negative`. This is the CPU seam the
    /// spans were named after; it ignores `options.stage` and always develops the negative.
    public func developNegative(linearRGB image: ImageBuffer) -> ImageBuffer {
        do { return try developNegativeChecked(linearRGB: image) }
        catch { fatalError(error.localizedDescription) }
    }

    /// Develops density with recoverable development and backend errors.
    public func developNegativeChecked(linearRGB image: ImageBuffer) throws -> ImageBuffer {
        if options.layeredTransport != nil || options.halationModel == .layered {
            var negative = options; negative.stage = .negative
            return try FotufilmEngine(stock: stock, options: negative).processChecked(linearRGB: image)
        }
        guard let developed = try HalideBackend.develop(image: image, stock: stock,
                                                    options: options) else {
            throw TransportError.backend(Self.missingEngineMessage)
        }
        return developed
    }

    /// Converts developed densities to display-linear RGB.
    public func printPositive(negativeDensity density: ImageBuffer) -> ImageBuffer {
        do { return try printPositiveChecked(negativeDensity: density) }
        catch { fatalError(error.localizedDescription) }
    }

    /// Prints developed density with recoverable development and backend errors.
    public func printPositiveChecked(negativeDensity density: ImageBuffer) throws -> ImageBuffer {
        var plain = stock; plain.layeredTransport = nil
        var settings = options; settings.layeredTransport = nil
        guard let positive = try HalideBackend.print(density: density, stock: plain,
                                                 options: settings) else {
            throw TransportError.backend(Self.missingEngineMessage)
        }
        return positive
    }

    private static let missingEngineMessage = """
    Fotufilm's Halide engine is not available in this build. Install Halide \
    (`brew install halide`, or set HALIDE_ROOT) and rebuild; on iOS use \
    HalideMetalFilmRenderer, which links the AOT-compiled engine.
    """
}

/// The sRGB transfer of every byte an 8-bit frame can present.
///
/// The decode's argument is `byte / 255`, so it has 256 possible arguments and 256 possible
/// answers. This is those answers — not a sampling of the curve, the curve itself at the only
/// points an 8-bit ingest ever asks about. Where alpha has to be undone the argument is no longer
/// a byte, and that pixel takes the transfer directly.
let srgbDecodedByte: [Float] = (0...255).map {
    ColorScience.srgbToLinear(Float($0) / 255)
}
