import Foundation
import FotufilmHalide

/// Feature bits selecting which physical stages a Halide pipeline variant includes.
public enum FilmEngineFeature {
    public static let flare: Int32 = Int32(FOTUFILM_FRAME_FLARE)
    public static let mtf: Int32 = Int32(FOTUFILM_FRAME_MTF)
    public static let halation: Int32 = Int32(FOTUFILM_FRAME_HALATION)
    public static let couplers: Int32 = Int32(FOTUFILM_FRAME_COUPLERS)
    public static let adjacency: Int32 = Int32(FOTUFILM_FRAME_ADJACENCY)
    public static let grain: Int32 = Int32(FOTUFILM_FRAME_GRAIN)
    public static let reversal: Int32 = Int32(FOTUFILM_FRAME_REVERSAL)
    public static let monochrome: Int32 = Int32(FOTUFILM_FRAME_MONOCHROME)
    /// Scene-referred schedule: linear float in and out, highlights above 1.0
    /// preserved rather than clipped to display white.
    public static let floatIO: Int32 = Int32(FOTUFILM_FRAME_FLOAT_IO)
    /// Extended emulsion MTF schedule: luminance separation and/or a second positive scale;
    /// mirrors FOTUFILM_FRAME_MTF_LUMA, whose bit is retained for AOT compatibility.
    public static let mtfLuma: Int32 = Int32(FOTUFILM_FRAME_MTF_LUMA)
    /// Spatial diffusion of the DIR couplers; mirrors FOTUFILM_FRAME_COUPLER_DIFFUSION.
    public static let couplerDiffusion: Int32 = Int32(FOTUFILM_FRAME_COUPLER_DIFFUSION)
    /// The realtime schedule rather than the reference one; mirrors FOTUFILM_FRAME_REALTIME.
    public static let realtime: Int32 = Int32(FOTUFILM_FRAME_REALTIME)
    /// Exact transcendentals rather than fast_* polynomials; mirrors FOTUFILM_FRAME_EXACT_MATH.
    public static let exactMath: Int32 = Int32(FOTUFILM_FRAME_EXACT_MATH)
    /// The grain-size mixture's coarse second clump field; mirrors
    /// FOTUFILM_FRAME_GRAIN_MOTTLE. The host suppresses the stage under the
    /// film model and in any span but the full one, so a request there
    /// renders the single-radius field at full strength rather than a
    /// quieter half of the mixture.
    public static let grainMottle: Int32 = Int32(FOTUFILM_FRAME_GRAIN_MOTTLE)
    /// The enlarger and paper MTF, one blur in transmittance after grain; mirrors
    /// FOTUFILM_FRAME_PRINT_MTF.
    public static let printMTF: Int32 = Int32(FOTUFILM_FRAME_PRINT_MTF)
    /// The kernel measures veiling glare from the frame it was handed instead of being told the
    /// mean; mirrors FOTUFILM_FRAME_FLARE_MEASURE. Only a whole-frame render may ask for it, and
    /// the mean it reaches is not the host's to the bit — the GPU sums float32 where the host
    /// sums each row in double. Set it and the host must not also measure.
    public static let flareMeasure: Int32 = Int32(FOTUFILM_FRAME_FLARE_MEASURE)
    /// The kernel carries the finished frame out of the print's delivery basis and into the
    /// host's own space — matrix, transfer, premultiplication — from the slots at
    /// `outputMatrixOffset`; mirrors FOTUFILM_FRAME_ENCODE_OUT. What comes back is then the
    /// host's own encoding, not display-linear light, and it is not what the host's libm would
    /// have produced to the bit.
    public static let encodeOut: Int32 = Int32(FOTUFILM_FRAME_ENCODE_OUT)
    /// Stops after veiling glare and the emulsion MTF and hands that light back as float — the
    /// first pass of the two-pass striped still path; mirrors FOTUFILM_FRAME_LIGHT_OUT.
    public static let lightOut: Int32 = Int32(FOTUFILM_FRAME_LIGHT_OUT)
    /// Develops from provided whole-frame halation grids instead of building the pyramid from
    /// the strip; mirrors FOTUFILM_FRAME_FIELDS_IN.
    public static let fieldsIn: Int32 = Int32(FOTUFILM_FRAME_FIELDS_IN)
    /// Compile-time output-transfer arms used by realtime AOT kernels. Reference kernels leave
    /// these unset and select from the packed coefficients at runtime.
    /// Develops with no film in the gate: the creative controls, the delivery basis and the
    /// grade, and nothing the emulsion would have done. Mirrors FOTUFILM_FRAME_NO_FILM.
    ///
    /// An invocation carrying it still names a stock, because the configuration is built from
    /// one — but no variant that carries this bit reads a single slot the stock filled, which
    /// `testNoFilmIgnoresWhichStockWasNamed` is there to keep true.
    public static let noFilm: Int32 = Int32(FOTUFILM_FRAME_NO_FILM)
    public static let outputLinear: Int32 = Int32(FOTUFILM_FRAME_OUTPUT_LINEAR)
    public static let outputPower: Int32 = Int32(FOTUFILM_FRAME_OUTPUT_POWER)
    public static let outputLog: Int32 = Int32(FOTUFILM_FRAME_OUTPUT_LOG)
    /// The two halves of the density seam; mirror FOTUFILM_FRAME_DENSITY_OUT and
    /// FOTUFILM_FRAME_DENSITY_IN. `densityOut` stops the schedule at the developed negative and
    /// hands its densities back in place of a picture; `densityIn` starts it there, reading
    /// densities where it would have read scene light. The hybrid still renderer has always used
    /// the pair to split one frame across two calls; `PipelineStage` is the same cut made
    /// durable enough to cross a process boundary.
    public static let densityOut: Int32 = Int32(FOTUFILM_FRAME_DENSITY_OUT)
    public static let densityIn: Int32 = Int32(FOTUFILM_FRAME_DENSITY_IN)
    /// `PipelineStage.texture`: the schedule develops the frame twice, once with the spatial
    /// stages the mask still carries and once with none of them, and returns the source
    /// multiplied by the transmittance the two densities differ by. Mirrors
    /// FOTUFILM_FRAME_TEXTURE.
    public static let texture: Int32 = Int32(FOTUFILM_FRAME_TEXTURE)
    /// The lens diffusion filter — mist, fog, black mist — as a three-scale scattering halo over
    /// the scene light, ahead of the emulsion; mirrors FOTUFILM_FRAME_DIFFUSION. Carried by
    /// FOTUFILM_AOT_ALL_STAGES, so the mobile variants compile the stage in and collapse it to a
    /// copy when no mist is fitted.
    public static let diffusion: Int32 = Int32(FOTUFILM_FRAME_DIFFUSION)
    /// The donor capture layer — a fourth coated record that develops and releases inhibitor
    /// but forms no image dye (REALA's 4th Color Layer); mirrors FOTUFILM_FRAME_DONOR_LAYER.
    /// Outside every AOT variant list, like the mottle and the print MTF, so a mobile render
    /// masks it off and carries three records alone until the realtime variants are
    /// regenerated with it.
    public static let donorLayer: Int32 = Int32(FOTUFILM_FRAME_DONOR_LAYER)
    /// Legacy annular halation basis. New physical profiles use continuous centered fields.
    public static let annularHalation: Int32 = Int32(FOTUFILM_FRAME_HALATION_ANNULAR)
}

/// The step out of the print's delivery basis and into a host's own space, for a caller that
/// would otherwise walk the finished frame to take it.
///
/// Handed to `developStaged` per render rather than carried in `FotufilmEngine.Options`: it says
/// nothing about the photograph and everything about who is receiving it, and a caller that wants
/// display-linear light back simply does not pass one.
public struct FilmOutputTransform: Sendable, Equatable {
    /// How the host's transfer is shaped; mirrors FOTUFILM_CONFIG_OUTPUT_TRANSFER.
    public enum Transfer: Int32, Sendable {
        /// The host's space is linear; the transfer is the identity.
        case linear = 0
        /// Sign-preserving power law: |v| <= c4 ? |v| * c0 : c1 * pow(|v|, c2) + c3.
        case powerLaw = 1
        /// Signed logarithm: v <= c4 ? v * c0 + c3 : c1 * log(v + c5) + c2, the gain carrying
        /// the change of base so a natural log reaches a curve published in base two.
        case logarithmic = 2
    }

    /// Row-major 3x3 out of the print's Display P3 delivery basis into the host's primaries.
    public var matrix: [Float]
    public var transfer: Transfer
    /// c0 through c5, as the shapes above read them.
    public var coefficients: [Float]
    /// Whether the host wants its colour folded back into alpha on the way out.
    public var premultiplied: Bool
    /// The knee of the SDR shoulder to roll the highlights off against, taken after the matrix
    /// and before the transfer — where `FilmOutputConversion` takes it. Nil is no shoulder, which
    /// is what a linear space and an unshouldered encode both want; `FilmSDRDelivery` names the
    /// knees a material's SDR delivery uses. Mirrors FOTUFILM_CONFIG_OUTPUT_SHOULDER.
    public var shoulderKnee: Float?
    /// Optional luminance weights in the host's primaries. Enables a neutral-axis gamut fit
    /// after the matrix and before the shoulder and transfer; nil preserves wide-gamut light.
    public var gamutLuminance: SIMD3<Float>?

    public init(matrix: [Float], transfer: Transfer,
                coefficients: [Float], premultiplied: Bool,
                shoulderKnee: Float? = nil, gamutLuminance: SIMD3<Float>? = nil) {
        precondition(matrix.count == 9 && coefficients.count == 6)
        self.matrix = matrix
        self.transfer = transfer
        self.coefficients = coefficients
        self.premultiplied = premultiplied
        self.shoulderKnee = shoulderKnee
        self.gamutLuminance = gamutLuminance
    }
}

/// The step the other way: out of a host's own space and into the engine's scene working space,
/// for a caller that would otherwise walk the arriving frame to take it. `FilmOutputTransform`'s
/// mirror image in every respect except one — the shapes are the published curves' own inverses,
/// not this struct's coefficients read backwards, because the two directions cut at different
/// places and a space may branch on the signed value going one way and on its magnitude the other.
public struct FilmInputTransform: Sendable, Equatable {
    /// How the host's transfer is shaped; mirrors FOTUFILM_DECODE_TRANSFER.
    public enum Transfer: Int32, Sendable {
        /// The host's space is already linear; the transfer is the identity.
        case linear = 0
        /// Sign-preserving power law: |v| <= c4 ? |v| * c0 : pow(c1 * |v| + c3, c2).
        case powerLaw = 1
        /// Signed exponential: v <= c4 ? (v - c3) * c0 : exp(v * c1 - c2) - c5, the gain
        /// carrying the change of base so a natural exponential reaches a curve published in
        /// base two.
        case exponential = 2
    }

    /// Row-major 3x3 out of the host's primaries into the engine's linear Rec.2020 scene basis.
    public var matrix: [Float]
    public var transfer: Transfer
    /// c0 through c5, as the shapes above read them.
    public var coefficients: [Float]
    /// Whether the host has folded its colour into alpha. The engine wants straight alpha: a
    /// premultiplied pixel has the matte already in the colour, and developing that would put the
    /// film's response on the wrong side of the composite.
    public var premultiplied: Bool

    public init(matrix: [Float], transfer: Transfer,
                coefficients: [Float], premultiplied: Bool) {
        precondition(matrix.count == 9 && coefficients.count == 6)
        self.matrix = matrix
        self.transfer = transfer
        self.coefficients = coefficients
        self.premultiplied = premultiplied
    }

    /// This laid out the way the decode kernel reads it; mirrors the FOTUFILM_DECODE_* offsets,
    /// which are the C side's only statement of the layout.
    public var parameters: [Float] {
        var packed = [Float](repeating: 0, count: 17)
        packed.replaceSubrange(0..<9, with: matrix)
        packed[9] = Float(transfer.rawValue)
        packed.replaceSubrange(10..<16, with: coefficients)
        packed[16] = premultiplied ? 1 : 0
        return packed
    }
}

/// What one decode pass noticed on the way past, folded from the kernel's per-row report.
public struct FilmDecodeReport: Sendable, Equatable {
    /// The largest finite RGB component the host sent, read before any repair, un-premultiplication
    /// or decoding — the number an over-range warning would quote.
    public var peak: Float
    /// Some pixel arrived non-finite, or became non-finite on the way through, and was replaced.
    public var repaired: Bool

    public init(peak: Float, repaired: Bool) {
        self.peak = peak
        self.repaired = repaired
    }
}

/// Area-weighted scene samples shared by every stock rendered from one realtime frame.
public struct FilmFlareFrame: Sendable {
    public let width: Int
    public let height: Int
    fileprivate let gridWidth: Int
    fileprivate let averages: [SIMD3<Float>]
    fileprivate let counts: [Int]
    fileprivate let positions: [SIMD2<Int>]

    /// Float scene rows already in the working space, linear Rec.2020.
    public init(linearRec2020RGBA pixels: UnsafePointer<Float>,
                width: Int, height: Int) {
        self.init(width: width, height: height) { index in
            SIMD3(pixels[index * 4], pixels[index * 4 + 1], pixels[index * 4 + 2])
        }
    }

    public init(encodedDisplayP3RGBA pixels: UnsafePointer<UInt8>,
                width: Int, height: Int) {
        self.init(encodedRGBA: pixels, width: width, height: height,
                  convertFromSRGB: false)
    }

    public init(srgbRGBA pixels: UnsafePointer<UInt8>, width: Int, height: Int) {
        self.init(encodedRGBA: pixels, width: width, height: height,
                  convertFromSRGB: true)
    }

    private init(encodedRGBA pixels: UnsafePointer<UInt8>, width: Int, height: Int,
                 convertFromSRGB: Bool) {
        let decode = Self.byteDecodeTable
        self.init(width: width, height: height) { index in
            let offset = index * 4
            let alpha = pixels[offset + 3]
            let denominator = alpha > 0 && alpha < 255 ? Float(alpha) : 255
            let decoded = SIMD3<Float>(
                alpha == 0 || alpha == 255
                    ? decode[Int(pixels[offset])]
                    : ColorScience.srgbToLinear(
                        min(Float(pixels[offset]) / denominator, 1)),
                alpha == 0 || alpha == 255
                    ? decode[Int(pixels[offset + 1])]
                    : ColorScience.srgbToLinear(
                        min(Float(pixels[offset + 1]) / denominator, 1)),
                alpha == 0 || alpha == 255
                    ? decode[Int(pixels[offset + 2])]
                    : ColorScience.srgbToLinear(
                        min(Float(pixels[offset + 2]) / denominator, 1)))
            // Both byte encodings name a display space; the samples this grid keeps are
            // working-space scene light, so each decodes into linear Rec.2020 — the flare
            // config built from them is metered with the kernel's 2020 luma weights.
            return convertFromSRGB
                ? ColorScience.linearSRGBToRec2020(decoded)
                : ColorScience.linearDisplayP3ToRec2020(decoded)
        }
    }

    private init(width: Int, height: Int, sample: (Int) -> SIMD3<Float>) {
        precondition(width > 0 && height > 0)
        self.width = width
        self.height = height
        let edge = 64
        let long = max(width, height)
        let gridWidth = min(width, max(1, (width * edge + long / 2) / long))
        let gridHeight = min(height, max(1, (height * edge + long / 2) / long))
        self.gridWidth = gridWidth
        var sums = [SIMD3<Double>](repeating: .zero, count: gridWidth * gridHeight)
        var counts = [Int](repeating: 0, count: sums.count)
        // Partition by cell-row bands so workers never share cells or require locks. Each cell
        // retains the serial top-to-bottom, left-to-right accumulation order, so the sums match a
        // single-threaded measurement exactly. Parallelism starts at 0.25 MP; this reduced a 12 MP
        // measurement from about 130 ms on one core.
        let parallel = width * height >= 1 << 18
        sums.withUnsafeMutableBufferPointer { sumsBuffer in
            counts.withUnsafeMutableBufferPointer { countsBuffer in
                let sums = sumsBuffer.baseAddress!
                let counts = countsBuffer.baseAddress!
                func band(_ cellY: Int) {
                    let start = (cellY * height + gridHeight - 1) / gridHeight
                    let end = cellY == gridHeight - 1
                        ? height
                        : ((cellY + 1) * height + gridHeight - 1) / gridHeight
                    for y in start..<end {
                        for x in 0..<width {
                            let cellX = min(x * gridWidth / width, gridWidth - 1)
                            let cell = cellY * gridWidth + cellX
                            let rgb = sample(y * width + x)
                            sums[cell] += SIMD3(
                                Double(rgb.x), Double(rgb.y), Double(rgb.z))
                            counts[cell] += 1
                        }
                    }
                }
                if parallel {
                    ParallelWork.forEach(iterations: gridHeight, execute: band)
                } else {
                    for cellY in 0..<gridHeight { band(cellY) }
                }
            }
        }
        self.counts = counts
        self.averages = sums.indices.map { cell in
            let count = max(counts[cell], 1)
            return SIMD3(
                Float(sums[cell].x / Double(count)),
                Float(sums[cell].y / Double(count)),
                Float(sums[cell].z / Double(count)))
        }
        self.positions = sums.indices.map { cell in
            let cellX = cell % gridWidth, cellY = cell / gridWidth
            return SIMD2(
                min(((2 * cellX + 1) * width) / (2 * gridWidth), width - 1),
                min(((2 * cellY + 1) * height) / (2 * gridHeight), height - 1))
        }
    }

    private static let byteDecodeTable: [Float] = (0..<256).map {
        ColorScience.srgbToLinear(Float($0) / 255)
    }
}

/// One packed description of a render: the packed configuration, feature
/// mask, seed, and spectral LUTs the Halide engine consumes.
public struct FilmEngineInvocation {
    /// Samples per channel in the coupler neutral-anchor table, and the
    /// log-exposure domain it spans.
    public static let couplerWarpSamples = Int(FOTUFILM_COUPLER_WARP_SAMPLES)
    public static let couplerWarpMin: Float = Float(FOTUFILM_COUPLER_WARP_MIN)
    public static let couplerWarpMax: Float = Float(FOTUFILM_COUPLER_WARP_MAX)

    /// Cells reserved per tone-base coefficient plane; mirrors FOTUFILM_TONE_GRID_CELLS.
    public static let toneGridCells = Int(FOTUFILM_TONE_GRID_CELLS)

    public static let sampledCurveStride = Int(FOTUFILM_SAMPLED_CURVE_STRIDE)
    public static let sampledCurvesOffset = Int(FOTUFILM_CONFIG_SAMPLED_CURVES)
    public static let configurationCount = Int(FOTUFILM_FRAME_CONFIGURATION_COUNT)
    /// Index of the grading-space switch; mirrors FOTUFILM_CONFIG_GRADE_SPACE.
    /// After it, appended in order so that adding each renumbered nothing:
    /// the six grain-mottle entries, the paper's red and blue records and
    /// their midpoints, then the grain density law with its anchors and fog,
    /// the per-layer grain and mottle sigmas, the print MTF, and last the
    /// host output transform.
    ///
    /// Counted forward from the start rather than back from the end, so that
    /// appending to the configuration does not silently move it.
    public static let gradeSpaceOffset = Int(FOTUFILM_CONFIG_GRADE_SPACE)
    /// Index of the grain mottle's sigma/radius/lambda and its three
    /// amplitudes; mirror FOTUFILM_CONFIG_MOTTLE_SIGMA / FOTUFILM_CONFIG_MOTTLE.
    public static let mottleSigmaOffset = Int(FOTUFILM_CONFIG_MOTTLE_SIGMA)
    public static let mottleOffset = Int(FOTUFILM_CONFIG_MOTTLE)
    /// Index of the paper's red and blue records (six curve parameters each,
    /// green staying in the legacy slot) and their calibrated midpoints;
    /// mirror FOTUFILM_CONFIG_PAPER_RED / _BLUE / _MIDPOINT_RED / _MIDPOINT_BLUE.
    public static let paperRedOffset = Int(FOTUFILM_CONFIG_PAPER_RED)
    public static let paperBlueOffset = Int(FOTUFILM_CONFIG_PAPER_BLUE)
    public static let paperMidpointRedOffset = Int(FOTUFILM_CONFIG_PAPER_MIDPOINT_RED)
    public static let paperMidpointBlueOffset = Int(FOTUFILM_CONFIG_PAPER_MIDPOINT_BLUE)
    /// Index of the grain density law, the per-layer anchor densities and fog, the per-layer
    /// grain and mottle blur sigmas, and the enlarger/paper MTF's sigma and radius; mirror
    /// FOTUFILM_CONFIG_GRAIN_LAW onward.
    public static let grainLawOffset = Int(FOTUFILM_CONFIG_GRAIN_LAW)
    public static let grainAnchorOffset = Int(FOTUFILM_CONFIG_GRAIN_ANCHOR)
    public static let grainFogOffset = Int(FOTUFILM_CONFIG_GRAIN_FOG)
    public static let grainSigmaLayerOffset = Int(FOTUFILM_CONFIG_GRAIN_SIGMA_LAYER)
    public static let mottleSigmaLayerOffset = Int(FOTUFILM_CONFIG_MOTTLE_SIGMA_LAYER)
    public static let printMTFOffset = Int(FOTUFILM_CONFIG_PRINT_MTF_SIGMA)
    /// Index of the host output transform's row-major matrix[9], transfer
    /// shape, transfer coefficients[6] and premultiplication switch; mirror
    /// FOTUFILM_CONFIG_OUTPUT_MATRIX / _TRANSFER / _COEFFICIENTS /
    /// _PREMULTIPLIED. Read only by the `encodeOut` variants. The print MTF
    /// before it takes two slots, a sigma and a radius.
    public static let outputMatrixOffset = Int(FOTUFILM_CONFIG_OUTPUT_MATRIX)
    public static let outputTransferOffset = Int(FOTUFILM_CONFIG_OUTPUT_TRANSFER)
    public static let outputCoefficientsOffset = Int(FOTUFILM_CONFIG_OUTPUT_COEFFICIENTS)
    public static let outputPremultipliedOffset = Int(FOTUFILM_CONFIG_OUTPUT_PREMULTIPLIED)
    /// Index of the print MTF's kept-detail share; mirrors FOTUFILM_CONFIG_PRINT_SHARPEN.
    /// Appended after the output transform so that adding it renumbered nothing.
    public static let printSharpenOffset = Int(FOTUFILM_CONFIG_PRINT_SHARPEN)
    /// The lens diffusion filter's slots; mirror FOTUFILM_CONFIG_DIFFUSION_*.
    public static let diffusionDirectOffset = Int(FOTUFILM_CONFIG_DIFFUSION_DIRECT)
    public static let diffusionKernelOffset = Int(FOTUFILM_CONFIG_DIFFUSION_KERNEL)
    public static let diffusionRadiusOffset = Int(FOTUFILM_CONFIG_DIFFUSION_RADIUS)
    /// Donor curve/release, followed by three reserved legacy halation-ring radii.
    public static let donorCurveOffset = Int(FOTUFILM_CONFIG_DONOR_CURVE)
    public static let donorReleaseOffset = Int(FOTUFILM_CONFIG_DONOR_RELEASE)
    public static let halationRingRadiusOffset = Int(FOTUFILM_CONFIG_HALATION_RING_RADIUS)
    public static let halationMatrixOffset = Int(FOTUFILM_CONFIG_HALATION_MATRIX)
    public static let curveSecondaryOffset = Int(FOTUFILM_CONFIG_CURVE_SECONDARY)
    public static let mtfSecondarySigmaOffset = Int(FOTUFILM_CONFIG_MTF_SECONDARY_SIGMA)
    public static let mtfSecondaryRadiusOffset = Int(FOTUFILM_CONFIG_MTF_SECONDARY_RADIUS)
    public static let mtfPrimaryShareOffset = Int(FOTUFILM_CONFIG_MTF_PRIMARY_SHARE)
    /// Per-donor Hill exponents for inhibitor release, followed by the optional donor-only
    /// layer's exponent; mirror FOTUFILM_CONFIG_COUPLER_RELEASE_GAMMA and
    /// FOTUFILM_CONFIG_DONOR_RELEASE_GAMMA.
    public static let couplerReleaseGammaOffset = Int(FOTUFILM_CONFIG_COUPLER_RELEASE_GAMMA)
    public static let donorReleaseGammaOffset = Int(FOTUFILM_CONFIG_DONOR_RELEASE_GAMMA)
    /// Optional donor capture record's lens-diffusion scale weights.
    public static let donorDiffusionKernelOffset = Int(FOTUFILM_CONFIG_DONOR_DIFFUSION_KERNEL)
    /// Whether development complements the formed density to a direct positive; mirrors
    /// FOTUFILM_CONFIG_DEVELOP_COMPLEMENT. Only a genuine reversal stock does. A negative shown
    /// on a light box or scanner also sets `FilmEngineFeature.reversal`, but only to route the
    /// output past the paper: it is developed as the negative it is.
    public static let developComplementOffset = Int(FOTUFILM_CONFIG_DEVELOP_COMPLEMENT)
    /// The chromogenic negative's granularity-against-density coefficients; mirrors
    /// FOTUFILM_CONFIG_GRAIN_DENSITY_PROFILE.
    public static let grainDensityProfileOffset = Int(FOTUFILM_CONFIG_GRAIN_DENSITY_PROFILE)
    /// Per-record `[amplitude, toe, decay, hump, humpDensity, humpWidth]` rows at
    /// FOTUFILM_CONFIG_GRAIN_DENSITY_RECORDS, red, green, blue in turn.
    public static let grainDensityRecordsOffset = Int(FOTUFILM_CONFIG_GRAIN_DENSITY_RECORDS)
    /// Index of the SDR shoulder knee: the material's own, which the byte delivery reads, until
    /// a host output transform replaces it; a negative value means none. Mirrors
    /// FOTUFILM_CONFIG_OUTPUT_SHOULDER. Appended without renumbering earlier fields.
    public static let outputShoulderOffset = Int(FOTUFILM_CONFIG_OUTPUT_SHOULDER)
    public static let outputGamutOffset = Int(FOTUFILM_CONFIG_OUTPUT_GAMUT)
    public static let adjacencyModelOffset = Int(FOTUFILM_CONFIG_ADJACENCY_MODEL)
    public static let adjacencySecondarySigmaOffset = Int(FOTUFILM_CONFIG_ADJACENCY_SECONDARY_SIGMA)
    public static let adjacencySecondaryRadiusOffset = Int(FOTUFILM_CONFIG_ADJACENCY_SECONDARY_RADIUS)
    public static let chromaticFringeAmountOffset = Int(FOTUFILM_CONFIG_CHROMATIC_FRINGE_AMOUNT)
    public static let chromaticFringeSigmaOffset = Int(FOTUFILM_CONFIG_CHROMATIC_FRINGE_SIGMA)
    public static let chromaticFringeRadiusOffset = Int(FOTUFILM_CONFIG_CHROMATIC_FRINGE_RADIUS)
    /// Reversal grain exponent and shoulder density; appended to preserve existing offsets.
    public static let grainReversalProfileOffset = Int(FOTUFILM_CONFIG_GRAIN_REVERSAL_PROFILE)
    /// Which model lays the grain: 0 the clump field, 1 the film grain model's tiles.
    public static let grainModeOffset = Int(FOTUFILM_CONFIG_GRAIN_MODE)
    public static let cameraPreflashOffset = Int(FOTUFILM_CONFIG_CAMERA_PREFLASH)
    public static let printerPreflashOffset = Int(FOTUFILM_CONFIG_PRINTER_PREFLASH)
    /// The primaries of the byte frames on the encoded-byte road, input then output: 0 is
    /// Display P3, 1 is sRGB; mirrors FOTUFILM_CONFIG_BYTE_BASIS.
    public static let byteBasisOffset = Int(FOTUFILM_CONFIG_BYTE_BASIS)
    /// Index of the three aperture-calibrated grain strengths; mirrors FOTUFILM_CONFIG_GRAIN.
    public static let grainOffset = Int(FOTUFILM_CONFIG_GRAIN)

    /// Index of the shared grain blur sigma and, one above it, the blur's reach; mirrors
    /// FOTUFILM_CONFIG_GRAIN_SIGMA and FOTUFILM_CONFIG_GRAIN_RADIUS.
    public static let grainSigmaOffset = Int(FOTUFILM_CONFIG_GRAIN_SIGMA)
    /// Index of the veiling-glare fraction — the lens's own, plus whatever the filter stack
    /// adds; mirrors FOTUFILM_CONFIG_FLARE.
    public static let flareOffset = Int(FOTUFILM_CONFIG_FLARE)
    /// Index of the caller-supplied veiling-glare mean; mirrors FOTUFILM_CONFIG_FLARE_MEAN.
    public static let flareMeanOffset = Int(FOTUFILM_CONFIG_FLARE_MEAN)
    /// Index of the coupler neutral-anchor table; mirrors FOTUFILM_CONFIG_COUPLER_WARP.
    public static let couplerWarpOffset = Int(FOTUFILM_CONFIG_COUPLER_WARP)
    /// Index of the camera white-balance gains; mirrors FOTUFILM_CONFIG_WHITE_BALANCE.
    public static let whiteBalanceOffset = Int(FOTUFILM_CONFIG_WHITE_BALANCE)
    /// Index of the scene adjustments (highlights, shadows, saturation,
    /// vibrance); mirrors FOTUFILM_CONFIG_HIGHLIGHTS.
    public static let sceneAdjustOffset = Int(FOTUFILM_CONFIG_HIGHLIGHTS)
    /// Index of the three-by-three halation scale weights; mirrors
    /// FOTUFILM_CONFIG_HALATION_KERNEL.
    public static let halationKernelOffset = Int(FOTUFILM_CONFIG_HALATION_KERNEL)
    /// Index of the print grade's per-channel lift, gain and inverse gamma;
    /// mirrors FOTUFILM_CONFIG_GRADE_LIFT.
    public static let gradeOffset = Int(FOTUFILM_CONFIG_GRADE_LIFT)
    /// Index of the frame's pixel dimensions; mirrors FOTUFILM_CONFIG_FRAME_WIDTH.
    public static let frameSizeOffset = Int(FOTUFILM_CONFIG_FRAME_WIDTH)
    /// Index of the tone-base grid dimensions and its two coefficient planes;
    /// mirror FOTUFILM_CONFIG_TONE_GRID_WIDTH / _A / _B.
    public static let toneGridSizeOffset = Int(FOTUFILM_CONFIG_TONE_GRID_WIDTH)
    public static let toneGridAOffset = Int(FOTUFILM_CONFIG_TONE_GRID_A)
    public static let toneGridBOffset = Int(FOTUFILM_CONFIG_TONE_GRID_B)

    public var configuration: [Float]
    /// Keeps the frame's canonical Film bank registered across cache eviction and tile calls.
    public let filmTileBinding: FilmGrain.TileBinding?
    /// The Film grain population this develop draws, bound or not; nil under another model.
    public let filmGrainPopulation: FilmGrain.Population?
    public var spectral: SpectralPipelineTables
    public var spectralCacheID: UInt64
    public var featureMask: Int32
    public var seed: UInt32
    /// Whether the tone controls may key to the regional base (Options.localTone).
    public let localToneEnabled: Bool
    /// The stock and medium this develop meters its levels for, when it meters at all: Digital
    /// Reference's Auto Levels or Lab Scan's timing.
    var meterStock: FilmStock? = nil
    var meterMedium: PrintPaper = .screen
    var meterExposureEV: Float = 0
    /// Lab Scan's dodging strength, density key and colour keys for the metered frame.
    var meterDodging: Float = 1
    var meterScanExposure: Float = 0
    var meterKeys: SIMD3<Float> = .zero
    var meterLevels = MeteredLevels()
    /// Pixels of context a tile must carry on each cut edge for its interior to develop exactly as
    /// it would inside the whole frame.
    public let spatialSupport: Int
    /// `spatialSupport` with halation's reach removed — the apron a strip needs when the
    /// halation fields arrive whole-frame rather than being built from the strip.
    public let spatialSupportSansHalation: Int
    /// The reach of the light chain alone (the emulsion MTF), which is all a LIGHT_OUT strip
    /// carries as apron.
    public let lightSupport: Int
    /// Halation's own reach — what the fields path removes from the strip apron.
    public let halationSupport: Int
    /// The three halation radii in pixels, as the fields build wants them.
    public let halationPixelRadii: [Int32]

    /// Narrowest grain blur any schedule will apply, in pixels. Mirrors the floor the schedules
    /// clamp to (`FotufilmHalide.cpp` and `FotufilmHalideAndroid.cpp`); here it is only what the
    /// covariance factorization below returns when its target degenerates to white noise, so
    /// a zero-size clump renders exactly the delta it asks for.
    static let grainSigmaFloorPixels: Float = 0.151

    /// Maps a continuous grain clump to the covariance produced by pixel-area integration.
    /// The target autocovariance is `gaussian(√2σ) ⊗ triangle`; the returned discrete Gaussian
    /// matches `R(1)/R(0)`. This correction applies only to clump grain because image content and
    /// Boolean discs already integrate over pixel area.
    /// R(t) = E[(1 - |t - Z|)+], Z ~ N(0, 2 sigma²): the pixel box's triangle read through a
    /// Gaussian field's autocovariance. `sigma` is the field's total continuous Gaussian.
    private static func gaussianLatticeCovariance(_ t: Float, sigma: Float) -> Float {
        // At microscope sampling the cloud spans many pixels. These CDF differences
        // then nearly cancel; Float arithmetic can make a wider cloud look narrower.
        // Keep the integral in Double until the packed kernel parameter is needed.
        let position = Double(t)
        let s = max(2 * Double(sigma) * Double(sigma), 1e-8).squareRoot()
        let phi = { (x: Double) in exp(-0.5 * x * x) / sqrt(2 * Double.pi) }
        let cdf = { (x: Double) in 0.5 * (1 + erf(x / sqrt(2))) }
        let z = { (x: Double) in (x - position) / s }
        return Float((1 + position) * (cdf(z(0)) - cdf(z(-1)))
            + (1 - position) * (cdf(z(1)) - cdf(z(0)))
            + s * (phi(z(-1)) - 2 * phi(z(0)) + phi(z(1))))
    }

    /// The lag-1 correlation the blur this lattice will actually run carries.
    ///
    /// Not a proxy for it. `gaussianRadius` decides how many taps the schedules lay, and past
    /// about 0.6 px that is five or more, whose correlation is not a three-tap's. Reading the
    /// kernel that will be rendered is what lets the sigma below be *solved* for instead of
    /// switched between two approximations that disagree where they meet.
    static func sampledGaussianCorrelation(_ sigma: Float) -> Float {
        let radius = gaussianRadius(sigma)
        guard radius > 0, sigma > 0 else { return 0 }
        let taps = (-radius...radius).map {
            exp(-Double($0 * $0) / (2 * Double(sigma) * Double(sigma)))
        }
        var zero: Double = 0
        var one: Double = 0
        for index in taps.indices { zero += taps[index] * taps[index] }
        for index in 0..<(taps.count - 1) { one += taps[index] * taps[index + 1] }
        return zero > 0 ? Float(one / zero) : 0
    }

    /// Solves a discrete Gaussian sigma for target lag-1 correlation `R(1)/R(0)`.
    /// Bisection is used because correlation is monotonic but piecewise as the kernel radius changes.
    /// Grain amplitude remains calibrated independently at the 48 µm aperture.
    private static func solvedSigma(target: Float) -> Float {
        guard target > 0 else { return grainSigmaFloorPixels }
        var high: Float = 1
        while sampledGaussianCorrelation(high) < target, high < 64 { high *= 2 }
        var low = grainSigmaFloorPixels
        guard sampledGaussianCorrelation(low) < target else { return low }
        for _ in 0..<40 {
            let middle = 0.5 * (low + high)
            if sampledGaussianCorrelation(middle) < target {
                low = middle
            } else {
                high = middle
            }
        }
        return 0.5 * (low + high)
    }

    /// The covariance mixture a partially-sharpened finish leaves on the grain.
    ///
    /// A scan finish is not a pure blur: it spreads the field at `fold` and hands `keep` of the
    /// detail back, so the kernel the grain passes through is `keep·δ + (1-keep)·gauss(fold)`
    /// and its self-correlation splits into three weighted reads — unspread, spread once, and
    /// spread against spread.
    private static func foldMixture(_ read: (Float) -> Float,
                                    fold: Float, keep: Float) -> Float {
        let kept = min(max(keep, 0), 1)
        return kept * kept * read(0)
            + 2 * kept * (1 - kept) * read(fold)
            + (1 - kept) * (1 - kept) * read(fold * 1.4142135)
    }

    /// The lag-1 correlation a continuous Gaussian clump of `clump` pixels leaves once this
    /// lattice has integrated it over pixel area and the finish has folded it.
    ///
    /// Exact pixel-integrated Gaussian covariance at every clump size; a quadrature approximation
    /// is inaccurate near the subpixel transition. `fold` is already self-correlated, so it
    /// contributes half its variance here.
    static func gaussianClumpCorrelation(clumpSigmaPixels clump: Float,
                                         foldSigmaPixels fold: Float,
                                         foldKeep keep: Float) -> Float {
        let read = { (t: Float, f: Float) -> Float in
            Self.gaussianLatticeCovariance(
                t, sigma: (clump * clump + f * f / 2).squareRoot())
        }
        let r0 = foldMixture({ read(0, $0) }, fold: fold, keep: keep)
        let r1 = foldMixture({ read(1, $0) }, fold: fold, keep: keep)
        return r0 > 0 ? r1 / r0 : 0
    }

    static func discreteGrainSigma(clumpSigmaPixels clump: Float,
                                   foldSigmaPixels fold: Float,
                                   foldKeep keep: Float) -> Float {
        solvedSigma(target: gaussianClumpCorrelation(
            clumpSigmaPixels: clump, foldSigmaPixels: fold, foldKeep: keep))
    }

    /// The lag-1 correlation the Boolean field of a silver emulsion leaves on this lattice.
    ///
    /// A silver emulsion's grain is `BooleanGrain`'s field of opaque discs, whose covariance
    /// `(1 - a)² (exp(λ K(h)) - 1)` shortens as coverage `a` rises: past the first disc a second
    /// one mostly lands on film already covered and adds no fluctuation there. The bare overlap
    /// `K(h)` is that field at vanishing coverage only, so the target is read at the coverage the
    /// emulsion actually sits at — `FilmStock.grainAnchorCoverage`, the density the published
    /// granularity is read at. At 157.5 px/mm and a 1.26 px disc that is a lag-1 of 0.475 rather
    /// than the bare overlap's 0.642; the real Coolscan and Noritsu silver targets read 0.27-0.55.
    ///
    /// What the lattice reads is that covariance integrated over the pixel's own area in both
    /// axes and through the enlarger fold. The pixel box's autocorrelation is a triangle per axis
    /// and the fold is separable, so the two-dimensional read factors into one weight row per
    /// axis: `R(t) = ∫∫ C(√(u² + v²)) w_t(u) w_0(v) du dv` with
    /// `w_t(u) = ∫ tri(t - u - s) g_fold(s) ds`, which is `gaussianLatticeCovariance` at `t - u`.
    /// R(0) is divided by C's integral over the plane — the density normalisation that makes a
    /// vanishing disc read 1, the white-noise limit the amplitude is calibrated to. One model at
    /// every radius: a hand-over to the Gaussian route would be a change of model, not of
    /// approximation, at the resolution where it sat.
    static func booleanGrainCorrelation(discRadiusPixels r: Float,
                                        coverage: Float,
                                        foldSigmaPixels fold: Float,
                                        foldKeep keep: Float) -> Float {
        guard r > 0 else { return 0 }
        let radius = Double(r)
        let coverage = Double(min(max(coverage, 1e-4), 0.99))
        let covariance = { (h: Double) -> Double in
            BooleanGrain.coverageCovariance(separation: h, radius: radius,
                                            coverage: coverage)
        }
        // The integrand is bounded by both of its factors: the covariance vanishes past 2r,
        // because discs that far apart can share no centre, and the weights vanish past the
        // triangle's one pixel plus the fold's tail. Whichever is narrower sets the grid, so a
        // disc many pixels wide costs no more to read than a sub-pixel one.
        let span = min(2 * radius, 2 + 6 * Double(fold))
        let steps = min(max(Int((2 * span / 0.03).rounded(.up)), 64), 256) / 2 * 2
        let step = 2 * span / Double(steps)
        let axis = (0..<steps).map { -span + (Double($0) + 0.5) * step }
        var grid = [Float](repeating: 0, count: steps * steps)
        for i in (steps / 2)..<steps {
            for j in (steps / 2)..<steps {
                let value = Float(covariance(
                    (axis[i] * axis[i] + axis[j] * axis[j]).squareRoot()))
                grid[i * steps + j] = value
                grid[(steps - 1 - i) * steps + j] = value
                grid[i * steps + (steps - 1 - j)] = value
                grid[(steps - 1 - i) * steps + (steps - 1 - j)] = value
            }
        }
        // C over the whole plane, radially: what one grain contributes to a reading that cannot
        // resolve it, and so the scale the white-noise limit is measured against.
        let radialSteps = 512
        var radial = 0.0
        for index in 0..<radialSteps {
            let h = 2 * radius * (Double(index) + 0.5) / Double(radialSteps)
            radial += covariance(h) * h
        }
        let integral = Float(2 * Double.pi * radial * 2 * radius / Double(radialSteps))
        guard integral > 0 else { return 0 }
        // `foldMixture` hands this the finish component's self-correlated width; the covariance
        // already carries both arms of the grain's own correlation.
        let response = { (t: Float, f: Float) -> Float in
            let sigma = f * 0.7071068
            let across = axis.map {
                Self.gaussianLatticeCovariance(t - Float($0), sigma: sigma)
            }
            let down = axis.map {
                Self.gaussianLatticeCovariance(-Float($0), sigma: sigma)
            }
            var total: Float = 0
            for i in 0..<steps where across[i] != 0 {
                var row: Float = 0
                for j in 0..<steps { row += grid[i * steps + j] * down[j] }
                total += across[i] * row
            }
            return total * Float(step * step) / integral
        }
        let r0 = min(foldMixture({ response(0, $0) }, fold: fold, keep: keep), 1)
        let r1 = foldMixture({ response(1, $0) }, fold: fold, keep: keep)
        return r0 > 0 ? r1 / r0 : 0
    }

    /// The Gaussian clump, in pixels, that stands in for a silver emulsion's Boolean field on
    /// this lattice: the one whose pixel-integrated correlation is the field's own.
    ///
    /// The render lays a Gaussian, so this is the clump the whole chain past the solve has to
    /// read — the amplitude's 48 µm aperture correction above all, which charges for exactly the
    /// width the render carries. It is not the crystal clump: the Boolean covariance is more
    /// peaked than any Gaussian, so the stand-in is narrower, and reading the correction at the
    /// emulsion's own radius put Tri-X 4.4% over its published granularity at 500 px/mm.
    static func silverEquivalentClumpPixels(discRadiusPixels r: Float,
                                            coverage: Float,
                                            foldSigmaPixels fold: Float,
                                            foldKeep keep: Float) -> Float {
        let target = booleanGrainCorrelation(discRadiusPixels: r, coverage: coverage,
                                             foldSigmaPixels: fold, foldKeep: keep)
        guard target > 0 else { return 0 }
        let correlation = { (clump: Float) -> Float in
            Self.gaussianClumpCorrelation(clumpSigmaPixels: clump,
                                          foldSigmaPixels: fold, foldKeep: keep)
        }
        var high: Float = 1
        while correlation(high) < target, high < 64 { high *= 2 }
        var low: Float = 0
        guard correlation(low) < target else { return 0 }
        for _ in 0..<40 {
            let middle = 0.5 * (low + high)
            if correlation(middle) < target { low = middle } else { high = middle }
        }
        return 0.5 * (low + high)
    }

    /// Silver-grain equivalent of `discreteGrainSigma`: the lattice blur that renders the
    /// emulsion's Boolean field.
    static func discreteSilverGrainSigma(discRadiusPixels r: Float,
                                         coverage: Float,
                                         foldSigmaPixels fold: Float,
                                         foldKeep keep: Float) -> Float {
        discreteGrainSigma(
            clumpSigmaPixels: silverEquivalentClumpPixels(
                discRadiusPixels: r, coverage: coverage,
                foldSigmaPixels: fold, foldKeep: keep),
            foldSigmaPixels: fold, foldKeep: keep)
    }

    /// A reach narrow enough that every schedule resolves it to nothing: `gaussianRadius` returns
    /// zero for it, so the Gaussian collapses to one tap normalised by itself, and it is below
    /// the two-sample threshold at which a blur is run on a decimated grid, so no resampling is
    /// left behind either. What a spatial stage is given when it has been switched off.
    static let noSpatialReachPixels: Float = 0.1

    /// How far a sampled Gaussian of `sigma` has to reach before the taps left out carry less than
    /// 0.27% of its weight — the share a continuous Gaussian leaves outside three sigma, which is
    /// the bound the radii here were always meant to satisfy.
    static func gaussianRadius(_ sigma: Float) -> Int {
        guard sigma > 0.15 else { return 0 }
        let variance = 2 * sigma * sigma
        let limit = Int(ceil(4 * sigma)) + 1
        let tail = (1...limit).map { exp(-Float($0 * $0) / variance) }
        let total = 1 + 2 * tail.reduce(0, +)
        var dropped: Float = 0
        for radius in stride(from: limit, through: 1, by: -1) {
            dropped += 2 * tail[radius - 1]
            if dropped > 0.0027 * total { return radius }
        }
        return 1
    }

    /// The box radius whose triple-pass chain carries `sigmaPixels`, or 0 when the Gaussian is
    /// finer than any expressible radius. Zero is passed through, not clamped to one: the scales
    /// are a weighted mixture, so a scale rendered wider than it was solved for would smear the
    /// continuous profile at exactly the resolutions where its compact lobe is sub-pixel. An
    /// identity scale keeps its weight's energy in place and leaves the mixture sum untouched.
    static func halationBoxRadius(sigmaPixels: Float) -> Int {
        guard sigmaPixels > 0.3 else { return 0 }
        let boxWidth = sqrt(12 * sigmaPixels * sigmaPixels / 3 + 1)
        return Int((boxWidth - 1) / 2)
    }

    /// The triple box spans three radii on the decimated grid. Include the annular sample,
    /// box-cell alignment and bilinear neighbour, as the AOT storage-footprint check does.
    static func boxPyramidSupport(radius: Int, stride: Int, ringRadius: Float = 0) -> Int {
        let gridRadius = Int(fotufilm_halation_strided_radius(Int32(radius), Int32(stride)))
        let reach = 3 * gridRadius + Int(ceil(ringRadius / Float(stride)))
        return stride == 1 ? reach + 1 : stride * (reach + 2)
    }

    /// The variance a sampled Gaussian of `sigma` actually delivers on this lattice, in pixels².
    ///
    /// A continuous blur and the lattice kernel that stands for it are not the same operator. The
    /// kernel is the continuous Gaussian read at integer offsets and renormalised, and below about
    /// one pixel that reading loses most of the blur: at sigma 0.455 the neighbouring tap carries
    /// 7.6% and the second moment comes to 0.152 px² where the continuous Gaussian asks for 0.207.
    /// Truncation takes a little more at every width. What is left over is a blur the stage was
    /// asked for and did not perform, and the grain — which is the only thing on the frame with
    /// energy right up at Nyquist for it to remove — is where the shortfall shows.
    static func sampledGaussianVariance(_ sigma: Float) -> Float {
        let radius = gaussianRadius(sigma)
        guard radius > 0, sigma > 0 else { return 0 }
        var total: Float = 0
        var moment: Float = 0
        for offset in -radius...radius {
            let weight = exp(-Float(offset * offset) / (2 * sigma * sigma))
            total += weight
            moment += weight * Float(offset * offset)
        }
        return total > 0 ? moment / total : 0
    }

    /// Every stage that happens before the density seam — the scene side, the emulsion's response
    /// and the negative's own spatial work. `PipelineStage.print` starts after all of them.
    static let sceneSideFeatures: Int32 =
        FilmEngineFeature.flare | FilmEngineFeature.mtf | FilmEngineFeature.mtfLuma
        | FilmEngineFeature.halation | FilmEngineFeature.couplers
        | FilmEngineFeature.couplerDiffusion | FilmEngineFeature.adjacency
        | FilmEngineFeature.grain | FilmEngineFeature.grainMottle
        // The donor rides the coupler stage, and its exposure comes off the scene through the
        // fourth channel of the exposure LUT — a density-in span is handed a developed negative
        // and has no scene light left to expose a fourth record with, which is why the frame
        // schedule gates the stage on `!density_in`. Without it here the print span would ask
        // for a variant carrying a stage it cannot run.
        | FilmEngineFeature.donorLayer | FilmEngineFeature.annularHalation

    /// The finished feature mask for one span of the pipeline.
    ///
    /// Adds the seam bit each span is identified by, and — for `print` alone — subtracts the
    /// stages that ran before the seam. `texture`'s own selection is not made here: a stage it
    /// was not asked for has already had its radius or share driven to zero, which is the only
    /// way of turning a stage off that an AOT build honours.
    ///
    /// Nothing here can switch a stage *on*, which is what makes `negative` followed by `print`
    /// reproduce `full` rather than approximate it: between them the two spans run each stage
    /// exactly once, from the same configuration, in the same order.
    static func staged(_ mask: Int32, stage: PipelineStage) -> Int32 {
        switch stage {
        case .full: mask
        case .negative: mask | FilmEngineFeature.densityOut
        case .print: (mask & ~Self.sceneSideFeatures) | FilmEngineFeature.densityIn
        case .texture: mask | FilmEngineFeature.texture
        }
    }

    /// Compatibility initializer for known-valid development settings. Use `init(validating:...)`
    /// when settings come from an editor or another external caller and errors must be recoverable.
    public init(stock: FilmStock, options: FotufilmEngine.Options,
                width: Int, height: Int, frameIndex: UInt64 = 0,
                noFilm: Bool = false) {
        do {
            self = try Self(validating: stock, options: options, width: width, height: height,
                            frameIndex: frameIndex, noFilm: noFilm, checkCancellation: {})
        } catch {
            preconditionFailure("invalid development request: \(error)")
        }
    }

    /// Prepares the invocation or throws `FilmDevelopmentError` for an unsupported or malformed
    /// development condition, or `CancellationError` when the calling task is cancelled. Cold
    /// Film grain preparation checks cancellation between calibration passes and tile batches.
    /// Valid settings produce the same configuration as `init(stock:...)`.
    ///
    /// `bindsFilmGrain: false` is for an invocation that is only measured — its feature mask and
    /// supports, for a memory estimate — and never rendered: it skips the Film grain population,
    /// which a stock without a bundled one takes seconds to prepare, and leaves the film tile block
    /// empty.
    public init(validating stock: FilmStock, options: FotufilmEngine.Options,
                width: Int, height: Int, frameIndex: UInt64 = 0,
                noFilm: Bool = false, bindsFilmGrain: Bool = true,
                checkCancellation: () throws -> Void = { try Task.checkCancellation() }) throws {
        try checkCancellation()
        var options = options
        // A larger piece of film than the aperture prints on the photograph's own levels.
        if let stops = options.unexposedEdge?.sceneHighlightStops { options.sceneHighlightStops = stops }
        if let tone = options.unexposedEdge?.sceneToneStops { options.sceneToneStops = tone }
        if let medians = options.unexposedEdge?.sceneChannelMedians {
            options.sceneChannelMedians = medians
        }
        // A development condition supplies the fresh roll's curves before age and reciprocity.
        let developed = try stock.pushed(stops: options.developmentEV)
        // The same roll at the pack's reference process, aged and reciprocity-shifted alike:
        // the coating the film grain model reads its population from, so that a pushed or
        // pulled condition is that population developed differently rather than another one.
        let referenceRoll = options.developmentEV == 0 ? nil
            : stock.expired(years: max(options.expiredYears, 0))
                .reciprocity(shutterSeconds: options.shutterSeconds ?? 0)
        // Every table key below sees the final developed roll.
        let stock = developed.expired(years: max(options.expiredYears, 0))
            .reciprocity(shutterSeconds: options.shutterSeconds ?? 0)
        let animatedSeed = options.seed &+ frameIndex &* 0x9E3779B97F4A7C15
        // Resolved once, against the developed roll: every stage below reads the medium the
        // picture actually lands on rather than the request, which may name one this stock
        // cannot reach or may name none at all.
        let printMedium = options.paper(for: stock)
        let printer = !noFilm && options.stage.writesPrint && options.negativeViewing == nil
            ? PrinterProfile.resolved(options.printer, stock: stock, paper: printMedium) : nil
        // A crop keeps only `frameCoverage` of the frame's short edge, so the same buffer
        // spans fewer millimetres of emulsion and every millimetre-sized structure grows
        // in pixels. The floor keeps a degenerate sliver from asking for unbounded radii.
        let pxPerMM = options.pixelsPerMM(width: width, height: height)
        // Disable texture stages by zeroing their radius or share, not by clearing feature bits.
        // AOT dispatch may select a superset variant, so configuration must remain authoritative.
        let selected = { (spatial: TextureStages) in
            options.stage != .texture || options.textureStages.contains(spatial)
        }
        // Set radius, not sigma, to zero: a zero-radius Gaussian is identity, while zero sigma
        // divides by zero. Diffusion radius derives from focal length and is capped at one eighth
        // of the short edge to bound strip aprons. Fold the omitted tail into retained scales.
        let diffusionHalo: DiffusionHalo? = options.diffusionFilter.flatMap { filter in
            let focal = options.focalLengthMM ?? options.format.normalFocalLengthMM
            let halo = filter.halo(stock: stock, focalLengthMM: focal,
                                   pixelPitchMM: 1 / pxPerMM,
                                   maximumSigmaPixels: Float(min(width, height)) / 8)
            return halo.isIdentity ? nil : halo
        }
        let diffusionRadii = diffusionHalo.map { halo in
            halo.sigmasPixels.map { Self.gaussianRadius($0) }
        } ?? [0, 0, 0]

        let mtfSigmas = stock.emulsionDiffusionMM.map { $0 * pxPerMM }
        let mtfRadii = selected(.emulsionMTF)
            ? mtfSigmas.map { Self.gaussianRadius($0) }
            : [Int](repeating: 0, count: mtfSigmas.count)
        let mtfSecondarySigmas = stock.emulsionDiffusionSecondaryMM.map { $0 * pxPerMM }
        let mtfSecondaryRadii = selected(.emulsionMTF)
            ? mtfSecondarySigmas.map { Self.gaussianRadius($0) }
            : [Int](repeating: 0, count: mtfSecondarySigmas.count)
        let mtfPrimaryShare = stock.emulsionDiffusionPrimaryShare.map {
            min(max($0, 0), 1)
        }

        // The look scale rides here rather than in the sheet's strengths so the calibrated
        // triple stays readable as physics and a scale of 1/lookScale recovers it exactly.
        var returned = try HalationReturn.ratios(
            for: stock, overriding: noFilm ? nil : options.halationReturnRatio).map {
            max($0, 0) * options.halationScale * stock.halationLookScale
        }
        // A drawn return spectrum multiplies what comes back at each wavelength, reduced through
        // each record's own sensitivity — the same integral the sheet's own return matrix is the
        // reduction of. It rides ahead of the source-colour blend because it is a statement about
        // the return trip, and that blend is a look laid over whatever the trip delivered.
        if !options.halationReturnGain.isEmpty {
            let gain = HalationSpectrum.recordGain(
                spectrum: options.halationReturnGain,
                sensitivity: stock.spectralProfile.layerSensitivity)
            if gain.count == returned.count {
                returned = zip(returned, gain).map { max($0 * $1, 0) }
            }
        }
        // The layered triple is why a colour film's ring is red: the light returns through
        // the stack from below and the bottom record takes nearly all of it. Raising the
        // dimmer records toward the strongest one's return hands the halo the source's own
        // balance instead. The blend rides the scaled triple, so the amount stays whatever
        // the slider and the look already say.
        if options.halationSourceColour > 0, let top = returned.max() {
            let t = min(options.halationSourceColour, 1)
            returned = returned.map { $0 + t * (top - $0) }
        }
        let halationProfile = stock.resolvedHalationProfile(
            useEstimate: options.useEstimatedHalationProfile)
        let halation = HalationRuntime.kernel(
            returned: returned, base: options.format.base,
            profile: halationProfile,
            hazeMM: options.halationHazeMM ?? stock.halationHazeMM,
            returnMatrix: stock.halationReturnMatrix)
        // Turned off at the share the scattered light is mixed in at, leaving the base's own
        // geometry alone: the mix is what `halationScale` already takes to zero, and a share of
        // zero returns the direct light whatever the blur did.
        let halationMix = selected(.halation)
            ? halation.mix : [Float](repeating: 0, count: halation.mix.count)
        let halationRadii = halation.sigmaMM.map {
            Self.halationBoxRadius(sigmaPixels: $0 * pxPerMM)
        }
        let halationRingRadii = halation.ringRadiusMM.map { max($0 * pxPerMM, 0) }

        let mtfLumaSigma = stock.lumaDiffusionMM * pxPerMM
        let mtfLumaRadius = selected(.emulsionMTF)
            ? Self.gaussianRadius(mtfLumaSigma) : 0

        // The couplers' *diffusion* and the adjacency effect are the spatial half of the coupler
        // stages; their pointwise inhibition is colour and is not selectable here.
        // These two are the only spatial stages the schedules run on a *decimated* grid, and the
        // grid's stride is chosen from the sigma alone. Turning them off at the radius would
        // therefore leave the decimation in place — a box average down and a bilinear sample back
        // up, which is a blur by any other name — so they are turned off at the reach instead.
        // Not at zero: the Gaussian's own kernel divides by the sigma, and zero returns NaN where
        // `noSpatialReachPixels` returns the identity.
        let couplerSigma = selected(.adjacency)
            ? stock.couplerDiffusionMM * pxPerMM : Self.noSpatialReachPixels
        let couplerRadius = Self.gaussianRadius(couplerSigma)
        let adjacencyReferenceSigma = selected(.adjacency)
            ? stock.adjacencyRadiusMM * pxPerMM : Self.noSpatialReachPixels
        let screenedAdjacency = (options.adjacencyModel ?? stock.adjacencyModel) == .screenedDiffusion
        let adjacencySigma = adjacencyReferenceSigma * (screenedAdjacency
            ? AdjacencyModel.screenedPrimarySigma : 1)
        let adjacencySecondarySigma = screenedAdjacency
            ? adjacencyReferenceSigma * AdjacencyModel.screenedSecondarySigma : 0
        let adjacencySecondaryRadius = Self.gaussianRadius(adjacencySecondarySigma)
        let adjacencyRadius = Self.gaussianRadius(adjacencySigma)
        let adjacencyStrength = selected(.adjacency) ? stock.adjacencyStrength : 0
        let grainScale = selected(.grain) ? options.grainScale : 0
        // The clump radius is a property of the emulsion, so each layer carries its own: a colour
        // negative coats the blue-sensitive layer on top with the coarsest crystals, and its grain
        // is lower in frequency as well as louder. The blur takes one sigma per channel and one
        // radius wide enough for the widest of them.
        let clumpSigmaMM = stock.grainLayerSizeRatio.map {
            stock.grainClumpSigmaMM * $0
        }
        // Add the enlarger variance that its sampled stage cannot represent at the current pixel
        // spacing. The residual is `sigma² - sampledGaussianVariance(sigma)`, which changes
        // continuously as the sampled kernel gains support. Apply the residual to the spread arm
        // of `keep·δ + (1-keep)·spread`. Base this on viewing mode, not pipeline span, so
        // `negative` followed by `print` matches `full`; direct film viewing omits it.
        let negativeViewing = options.negativeViewing
            ?? (printMedium.isNegative ? NegativeViewing.lightBox : nil)
        // An output medium only applies to a span that writes a finished image. The raw negative
        // span must keep returning `NegativeInterchange` densities even when the eventual output
        // is a viewed negative.
        let showingNegative = options.stage.writesPrint
            && negativeViewing != nil && !stock.isReversal
        let enlargerSigmaPixels = printMedium.enlargerBlurMM * pxPerMM
        let enlargerFoldPixels: Float = {
            guard !showingNegative, !printMedium.viewsFilmDirectly(for: stock), selected(.enlarger),
                  enlargerSigmaPixels > 0
            else { return 0 }
            let shortfall = enlargerSigmaPixels * enlargerSigmaPixels
                - Self.sampledGaussianVariance(enlargerSigmaPixels)
            return max(shortfall, 0).squareRoot()
        }()
        let enlargerFoldKeep = enlargerFoldPixels > 0
            ? min(max(printMedium.scanSharpening, 0), 1) : 1
        // A silver emulsion's grain is opaque discs, so what the lattice should carry is the
        // Boolean field's own covariance at the coverage the emulsion sits at, not a Gaussian's
        // tail — which no real silver scan shows once the pixels are coarse. The render still
        // lays a Gaussian, so the clump the rest of the chain reads is the stand-in's, matched
        // to that covariance on this lattice; everything past here — the aperture correction
        // above all — has to be charged for the width actually laid.
        let renderedClumpMM = stock.grainDensityLaw == .silver
            ? clumpSigmaMM.map { clump in
                Self.silverEquivalentClumpPixels(
                    discRadiusPixels: 2 * clump * pxPerMM,
                    coverage: stock.grainAnchorCoverage,
                    foldSigmaPixels: enlargerFoldPixels,
                    foldKeep: enlargerFoldKeep) / pxPerMM
            }
            : clumpSigmaMM
        let grainSigmaLayer = renderedClumpMM.map { clump -> Float in
            Self.discreteGrainSigma(clumpSigmaPixels: clump * pxPerMM,
                                    foldSigmaPixels: enlargerFoldPixels,
                                    foldKeep: enlargerFoldKeep)
        }
        let grainSigma = grainSigmaLayer.max() ?? Self.grainSigmaFloorPixels
        let grainRadius = Self.gaussianRadius(grainSigma)

        // Published granularity is standard deviation through a 48 µm aperture. Correct amplitude
        // using the clump the render lays, not the 0.151 px numerical sigma floor; using the
        // floor made granularity resolution-dependent. The aperture response naturally approaches
        // one for sub-pixel clumps.
        let apertureScale = renderedClumpMM.map { sigma in
            sqrt(Float.pi) * FilmStock.granularityApertureRadiusMM * pxPerMM
                / FilmStock.granularityApertureResponse(clumpSigmaMM: sigma)
        }
        // The grain-size mixture: the published RMS granularity stays the single
        // calibration anchor, split by variance between the sharp field and the
        // coarse mottle, each corrected through the 48 µm aperture at its own
        // size — so the sum reads back the published figure whatever the split.
        // Suppressed — share and split together, so the sharp field keeps its whole
        // amplitude — where the coarse field is not laid: the spans keep to the single
        // field, so a span with a mottle look renders the single-radius field at full
        // strength rather than a quieter half of the mixture.
        // The film grain model (mode 1) lays the film's own crystals from its tiles in place of
        // every field below, so the mixture that shapes them is not laid either.
        let filmActive = options.grainModel == .film
            && grainScale > 0 && stock.grainStrength > 0
        let mottleShare = options.stage != .full || filmActive
            ? 0 : min(max(
            options.grainMottleShare ?? stock.grainMottleShare, 0), 0.9)
        // The override clamps to the pack's own validated range, so a look can
        // never ask for a population the shipped AOT variants were not built for.
        let mottleSizeRatio = min(max(
            options.grainMottleSizeRatio ?? stock.grainMottleSizeRatio, 1), 8)
        let mottleClumpMM = clumpSigmaMM.map { $0 * mottleSizeRatio }
        let renderedMottleMM = stock.grainDensityLaw == .silver
            ? mottleClumpMM.map { clump in
                Self.silverEquivalentClumpPixels(
                    discRadiusPixels: 2 * clump * pxPerMM,
                    coverage: stock.grainAnchorCoverage,
                    foldSigmaPixels: enlargerFoldPixels,
                    foldKeep: enlargerFoldKeep) / pxPerMM
            }
            : mottleClumpMM
        let mottleSigmaLayer = renderedMottleMM.map { mottle -> Float in
            Self.discreteGrainSigma(clumpSigmaPixels: mottle * pxPerMM,
                                    foldSigmaPixels: enlargerFoldPixels,
                                    foldKeep: enlargerFoldKeep)
        }
        let mottleSigma = mottleSigmaLayer.max() ?? Self.grainSigmaFloorPixels
        let mottleRadius = Self.gaussianRadius(mottleSigma)
        let mottleApertureScale = renderedMottleMM.map { sigma in
            sqrt(Float.pi) * FilmStock.granularityApertureRadiusMM * pxPerMM
                / FilmStock.granularityApertureResponse(clumpSigmaMM: sigma)
        }
        // The published figure is read where the sheet says — net diffuse density 1.0 for a
        // negative, gross 1.0 for a reversal — so the density law is normalised there and the
        // render reads back the figure at the sheet's condition. Away from that density the
        // law is the emulsion's own: a colour negative's measured granularity peaks 0.15-0.2
        // above D-min and falls, so it is not monotone, and the pipeline evaluates the shape
        // per pixel.
        let grainStrength = stock.grainLayerWeights.enumerated().map { layer, weight in
            stock.grainStrength * grainScale * weight
                * apertureScale[layer] * (1 - mottleShare).squareRoot()
        }
        let mottleStrength = stock.grainLayerWeights.enumerated().map { layer, weight in
            stock.grainStrength * grainScale * weight
                * mottleApertureScale[layer] * mottleShare.squareRoot()
        }
        let clumpsPerPixel = stock.grainClumpsPerMM2 / (pxPerMM * pxPerMM)
        let mottleLambda = clumpsPerPixel
            / (mottleSizeRatio * mottleSizeRatio)
        // The enlarger lens and the paper's own scattering, as one Gaussian on the film's scale.
        // Only when something actually images the film: direct viewing has no enlarger.
        // Nothing images the negative in `PipelineStage.negative`, and in `.texture` the
        // enlarger is one of the selectable spatial stages rather than part of a print.
        let printMTFActive = !showingNegative && !printMedium.viewsFilmDirectly(for: stock)
            && printMedium.enlargerBlurMM > 0
            && options.stage != .negative && selected(.enlarger)
        let printMTFSigma = max(printMedium.enlargerBlurMM * pxPerMM,
                                Self.grainSigmaFloorPixels)
        let printMTFRadius = printMTFActive ? Self.gaussianRadius(printMTFSigma) : 0
        var contrast = printer == nil
            ? stock.printingContrastScale(correction: options.printCorrection, paper: printMedium)
            : [Float](repeating: 1, count: 3)
        // Digital Reference's graded styles level the receiver's read in these same slots: the
        // film base to the graded curve's black, and — per frame, when the host has metered
        // one — the frame's brightest content to its white. No table is rebuilt for it. A
        // positive takes the paper stage for them too; in `.texture` nothing prints, and the
        // kernel's reversal branch keeps the slide's polarity for the texture it carries back.
        let levelsPositive = printMedium.levelsPositive(
            for: stock, digitalReference: options.digitalReference) && options.stage != .texture
        // A host that metered the frame hands Auto Levels its tone reading with the highlight:
        // the film boost an under-exposed frame takes, which the print is re-timed to take back
        // out; the print grey the tone controls are keyed on; and the frame's ends, which a
        // frame wider than the print has held and lifted onto it.
        let white = !noFilm && printMedium == .screen && options.digitalReference == .autoLevels
            && options.stage.readsScene && DigitalReferenceReceiver.keysTone(stock)
            ? options.sceneHighlightStops : nil
        let filmBoost = white.map {
            DigitalReferenceReceiver.filmBoost(for: stock, white: $0, exposureEV: options.exposureEV)
        } ?? 0
        // Lab Scan's dodging takes the same reading: the frame's median, which keys it, and the
        // ends that set how far it holds and lifts.
        let labScanReading = !noFilm && printMedium == .labScan && options.stage.readsScene
            && !stock.isReversal && !stock.isReflectionPrint && options.sceneHighlightStops != nil
        let dodge = labScanReading ? options.sceneToneStops.map {
            LabScanTiming.dodge(AutoAdjustment.SceneStops(median: $0.x, bright: $0.z, dark: $0.y),
                                strength: options.labScanDodging)
        } : nil
        let toneKey = white.map { DigitalReferenceReceiver.toneKey(white: $0) + filmBoost }
            ?? dodge?.key
        let compression = white.flatMap { white in
            options.sceneToneStops.map {
                DigitalReferenceReceiver.toneCompression(
                    for: stock, AutoAdjustment.SceneStops(median: $0.x, bright: $0.z, dark: $0.y),
                    white: white)
            }
        } ?? dodge.map { (hold: $0.hold, lift: $0.lift) }
        // Lab Scan levels each frame on its scan, and takes the operator's colour keys and
        // lightening on the light its film receives (`LabScanTiming`).
        let labScanSetup = printMedium == .labScan && !noFilm && !showingNegative
            ? LabScanTiming.setup(for: stock,
                                  sceneHighlightStops: labScanReading ? options.sceneHighlightStops : nil,
                                  sceneMedianStops: options.sceneToneStops?.x,
                                  exposureEV: options.exposureEV,
                                  scanExposure: options.screenExposureEV, keys: options.screenCMY)
            : .identity
        let filmCurves = labScanSetup.curves(for: stock)
        let levels: (scale: SIMD3<Float>, shift: SIMD3<Float>)
        if printMedium == .screen && (levelsPositive || !stock.isReversal) {
            let screen = DigitalReferenceReceiver.levels(
                for: stock, style: options.digitalReference,
                sceneHighlightStops: options.sceneHighlightStops.map { $0 + filmBoost },
                exposureEV: options.exposureEV)
            levels = (SIMD3(repeating: screen.scale), SIMD3(repeating: screen.shift))
        } else {
            levels = (labScanSetup.scale, labScanSetup.print)
        }
        // Auto Levels re-times red and blue apart from green, in the same midpoint slots.
        let autoColour = printMedium == .screen && options.digitalReference == .autoLevels
            ? DigitalReferenceReceiver.autoColourShift(
                for: stock, channelMedians: options.sceneChannelMedians.map { $0 + filmBoost },
                exposureEV: options.exposureEV)
            : SIMD3<Float>.zero
        contrast = (0..<3).map { contrast[$0] * levels.scale[$0] }
        // Reverse the positive paper's exposure axis in the existing shared CPU/Metal slots.
        let masking = contrast.map { $0 * printMedium.exposureDirection }
        // The paper's three records, each anchored at its own calibrated
        // midpoint so a neutral mid-grey prints neutral through records that
        // do not share a curve. Green keeps the legacy slots; red and blue
        // ride the appended ones.
        let paperCurves = printMedium.printCurves(for: stock,
                                                  digitalReference: options.digitalReference)
        let paper = paperCurves[1]
        // The screen exposure rides the same slots, a constant the meter's deltas leave alone.
        let screenShift = levels.shift + SpectralRuntime.screenExposureShift(
            options.screenExposureEV, stock: stock, paper: printMedium,
            digitalReference: options.digitalReference, screenGrade: options.screenGrade)
        var xMids = printMedium.printExposureMidpoints(
            for: stock, digitalReference: options.digitalReference)
            .enumerated().map { $1 + printMedium.exposureDirection * (screenShift[$0] + autoColour[$0]) }
        // Manual receiver balance follows metering, so Auto Levels cannot cancel the cast.
        // The existing per-record midpoint slots are shared by CPU, Metal and AOT kernels.
        let colourShift = DigitalReferenceReceiver.colourShift(options.screenCMY,
                                                               stock: stock, paper: printMedium)
        for channel in 0..<3 { xMids[channel] += colourShift[channel] }
        // Paper exposure shifts log light after film transmission, before the paper curves.
        // Reuse the midpoint slots to preserve the packed ABI and avoid rebuilding spectral LUTs.
        if let printer, printer.exposureEV != 0 {
            let shift = printMedium.exposureDirection * printer.exposureEV * log10(Float(2))
            xMids = xMids.map { $0 + shift }
        }
        let xMid = xMids[1]

        // Two lenses' worth of veiling glare, and only one of them is optional.
        //
        // The taking lens's own is off unless asked for: the light this engine is handed has
        // usually been through a lens already, so adding it back puts a second lens in the path.
        // A filter is the other case entirely — it is glass the photograph did *not* already
        // carry, two more air-glass faces bouncing light back into the frame, and it is there
        // whatever the capture lens did. So `flareScale` scales the first term and not the
        // second, and a fitted filter still glares at `flareScale` 0.
        let veilingGlare = min(stock.flare * max(0, options.flareScale)
                               + options.lensFilters.addedVeilingGlare, 0.99)

        var featureMask: Int32 = 0
        if veilingGlare > 0 { featureMask |= FilmEngineFeature.flare }
        if diffusionHalo != nil, diffusionRadii.contains(where: { $0 > 0 }) {
            featureMask |= FilmEngineFeature.diffusion
        }
        let primaryMTFActive = zip(mtfRadii, mtfPrimaryShare)
            .contains { pair in pair.0 > 0 && pair.1 > 0 }
        let secondaryMTFActive = zip(mtfSecondaryRadii, mtfPrimaryShare)
            .contains { pair in pair.0 > 0 && pair.1 < 1 }
        if primaryMTFActive || secondaryMTFActive { featureMask |= FilmEngineFeature.mtf }
        let mtfLumaActive = featureMask & FilmEngineFeature.mtf != 0
            && stock.mtfLumaShare > 0 && mtfLumaRadius > 0
        let mtfProfileActive = featureMask & FilmEngineFeature.mtf != 0
            && (mtfLumaActive || secondaryMTFActive)
        if mtfProfileActive { featureMask |= FilmEngineFeature.mtfLuma }
        if halationMix.contains(where: { $0 > 0 })
            && (halationRadii.contains(where: { $0 > 0 })
                || halationRingRadii.contains(where: { $0 > 0 })) {
            featureMask |= FilmEngineFeature.halation
        }
        let inhibition = stock.couplerGeometry.map { geometry in
            let gaps = geometry.layerDepthUM.count - 1
            let reach = options.couplerGapReachScales
                ?? [Float](repeating: options.couplerRangeScale, count: gaps)
            return geometry.matrix(gapReachScales: reach,
                                   selfScale: options.couplerSelfScale)
        } ?? stock.couplerInhibition
        let couplerScale = Self.effectiveCouplerScale(options.couplerScale)
        let couplersActive = couplerScale > 0
            && inhibition.contains { $0.contains { $0 != 0 } }
        let requestedFringeAmount = options.chromaticFringeAmount ?? stock.chromaticFringeAmount
        let requestedFringeMM = options.chromaticFringeRadiusMM ?? stock.chromaticFringeRadiusMM
        let fringeAmount = requestedFringeAmount.isFinite ? min(max(requestedFringeAmount, 0), 1) : 0
        let fringeMM = requestedFringeMM.isFinite ? min(max(requestedFringeMM, 0), 2) : 0
        let hasCrossLayerInhibition = inhibition.enumerated().contains { receiver, row in
            row.enumerated().contains { donor, value in receiver != donor && value != 0 }
        }
        let fringeActive = selected(.adjacency) && couplersActive && !stock.isMonochrome
            && hasCrossLayerInhibition && fringeAmount > 0
            && fringeMM > stock.couplerDiffusionMM
            && Self.gaussianRadius(fringeMM * pxPerMM) > 0
        let fringeSigma = fringeActive ? fringeMM * pxPerMM : Self.noSpatialReachPixels
        let fringeRadius = fringeActive ? Self.gaussianRadius(fringeSigma) : 0
        if couplersActive { featureMask |= FilmEngineFeature.couplers }
        // The donor capture layer rides the coupler stage: its release row joins the
        // inhibition sum, its activation joins the diffusion blur, and `couplerScale` scales
        // it like the rest. Gated on the reconstruction model being loaded, because the
        // exposure cube's fourth channel is integrated with it — the compact RGB fallback has
        // no spectrum to expose a fourth record, and a donor whose exposure never rose would
        // leave the warp table adding back an inhibition no pixel released.
        let donor = stock.donorLayers.first
        let donorActive = couplerScale > 0
            && donor?.inhibition.contains { $0 != 0 } == true
            && SpectralRuntime.hasReconstructionModel
            // A 4th Color Layer is an inter-image device between colour records that modulates
            // how much *dye* each one forms, so it belongs to a chromogenic colour emulsion and
            // to nothing else: the monochrome schedule develops one record, and a silver emulsion's
            // image is opaque silver rather than a dye cloud. Both are gated here rather than left
            // to the stocks.
            && !stock.isMonochrome && stock.grainDensityLaw != .silver
        if donorActive { featureMask |= FilmEngineFeature.donorLayer }
        if (couplersActive || donorActive) && max(couplerRadius, fringeRadius) > 0 {
            featureMask |= FilmEngineFeature.couplerDiffusion
        }
        if couplerScale > 0 && adjacencyStrength > 0
            && max(adjacencyRadius, adjacencySecondaryRadius) > 0 {
            featureMask |= FilmEngineFeature.adjacency
        }
        if grainScale > 0 && stock.grainStrength > 0 {
            featureMask |= FilmEngineFeature.grain
            if mottleShare > 0 { featureMask |= FilmEngineFeature.grainMottle }
        }
        if printMTFActive && printMTFRadius > 0 {
            featureMask |= FilmEngineFeature.printMTF
        }
        if (printMedium.viewsFilmDirectly(for: stock) && !levelsPositive) || showingNegative {
            featureMask |= FilmEngineFeature.reversal
        }
        if stock.isMonochrome { featureMask |= FilmEngineFeature.monochrome }
        // Which span of the pipeline this is. Applied to the finished mask rather than threaded
        // through the solves above, so that every stage is configured exactly as the full render
        // would configure it and the only difference is which of them the schedule compiles. A
        // configuration slot whose stage is masked off is simply never read.
        featureMask = Self.staged(featureMask, stage: options.stage)
        // No film in the gate: every stage above belongs to an emulsion that is not there, and a
        // variant carrying this bit compiles none of them. Set last so the solves above ran
        // exactly as they always do — the spatial supports they produce are still honest, and a
        // no-film render simply needs no apron.
        if noFilm { featureMask = FilmEngineFeature.noFilm }

        var configuration = filmCurves.flatMap(Self.parameters)
        configuration += halationMix
        configuration += inhibition.flatMap { $0 }
        configuration += grainStrength
        configuration += Self.parameters(paper)
        configuration += masking
        configuration += mtfSigmas
        configuration += mtfRadii.map(Float.init)
        configuration += halationRadii.map(Float.init)
        configuration += [couplerSigma, Float(couplerRadius)]
        configuration += [adjacencySigma, Float(adjacencyRadius)]
        configuration += [grainSigma, Float(grainRadius)]
        configuration += [veilingGlare, couplerScale,
                          stock.adjacencyStrength * couplerScale,
                          clumpsPerPixel, exp2(options.exposureEV + filmBoost), xMid]
        configuration += [-1, -1, -1]
        // The warp mirrors exactly what a neutral pixel releases per-pixel: the matrix only
        // when the coupler stage runs, the donor row only when the donor does.
        configuration += Self.couplerWarp(
            curves: stock.curves,
            inhibition: couplersActive ? inhibition
                : inhibition.map { $0.map { _ in 0 } },
            scale: (couplersActive || donorActive) ? couplerScale : 0,
            releaseGamma: stock.couplerReleaseGamma,
            donor: donorActive ? donor : nil)
        let balance = noFilm ? options.sceneLightGains : (r: Float(1), g: Float(1), b: Float(1))
        configuration += [balance.r, balance.g, balance.b]
        // A source that declares recorded light above diffuse white has the range its edit keeps
        // (`hdrRange`, compressed by the HDR_RANGE slot) metered into the film's window with the
        // highlight shaping the one-tap solve drives — the engine's pre-emulsion light path —
        // at the strength the edit asks (`hdrRollOff`), stacked under whatever the user already
        // asked for. Headroom 1, every SDR source, adds exactly 0 and skips the latitude walk.
        var highlights = options.highlights
        let keptHeadroom = HDRHighlightRange.keptHeadroom(options.sceneHeadroom,
                                                          range: options.hdrRange)
        if keptHeadroom > 1 {
            // With no film there is no emulsion latitude to absorb the declared range first, so
            // the window is the only one this path has: the SDR ceiling at diffuse white. This
            // is the one creative slot that would otherwise read the stock, and it is why a
            // no-film invocation's output does not depend on which stock it was handed.
            let window = noFilm
                ? (shadows: Float(0), highlights: AutoAdjustment.kDiffuseWhiteStops)
                : AutoAdjustment.latitude(stock: stock,
                                          printCorrection: options.printCorrection,
                                          paper: printMedium,
                                          callier: options.enlarger.callierCoefficient(
                                              for: stock, paper: printMedium))
            highlights = max(-1, min(1, highlights + options.hdrRollOff
                * AutoAdjustment.headroomHighlights(contentHeadroom: keptHeadroom,
                                                    window: window)))
        }
        let shadowLift = min(compression?.lift ?? 0, max(1 - options.shadows, 0))
        let held = max(highlights - (compression?.hold ?? 0), -1)
        configuration += [held, options.shadows + shadowLift,
                          options.saturation, options.vibrance]
        configuration += halation.weights.flatMap { $0 }
        configuration += [stock.grainLumaCorrelation]
        configuration += [mtfLumaActive ? stock.mtfLumaShare : 0,
                          mtfLumaSigma, Float(mtfLumaRadius)]
        configuration += options.grade.packed
        configuration += [Float(width), Float(height)]
        configuration += [1, 1]
        var toneGrid = [Float](repeating: 0, count: 2 * Self.toneGridCells)
        toneGrid[0] = 1
        toneGrid[Self.toneGridCells] = -(toneKey ?? 0)
        configuration += toneGrid

        // The film grain model needs no variant: its tiles are a runtime input.
        configuration += [filmActive ? 1 : 0]
        configuration += [options.gradeSpace == .encoded ? 1 : 0]
        configuration += [mottleSigma, Float(mottleRadius), mottleLambda]
        configuration += mottleStrength
        configuration += Self.parameters(paperCurves[0])
        configuration += Self.parameters(paperCurves[2])
        configuration += [xMids[0], xMids[2]]
        configuration += [Float(stock.grainDensityLaw.rawValue)]
        configuration += (0..<3).map { stock.granularityAnchorDensity(layer: $0) }
        configuration += [Float](repeating: stock.grainFogDensity, count: 3)
        configuration += grainSigmaLayer
        configuration += mottleSigmaLayer
        configuration += [printMTFSigma, Float(printMTFRadius)]
        // Initialize the host output transform to identity. `setOutputTransform` replaces these
        // slots before enabling an `encodeOut` variant.
        configuration += [1, 0, 0, 0, 1, 0, 0, 0, 1]  // matrix, row-major
        configuration += [0]                          // transfer: the identity
        configuration += [0, 0, 0, 0, 0, 0]           // coefficients
        configuration += [0]                          // premultiplied
        configuration += [printMTFActive
            ? min(max(printMedium.scanSharpening, 0), 1) : 0]
        // The diffusion filter. With no filter the direct share is 1 and every kernel weight is
        // 0, which is the identity whether or not the stage is compiled in.
        let diffusionActive = featureMask & FilmEngineFeature.diffusion != 0
        configuration += [diffusionActive ? (diffusionHalo?.directShare ?? 1) : 1]
        if diffusionActive, let halo = diffusionHalo {
            // The weights are stated as shares of the scattered light; the engine wants shares
            // of the whole beam, so each is scaled by how much scattered at all.
            configuration += halo.weights.prefix(3).flatMap { row in
                row.map { $0 * halo.scatteredShare }
            }
        } else {
            configuration += [Float](repeating: 0, count: 9)
        }
        configuration += diffusionRadii.map(Float.init)
        // The donor capture layer's own curve and its per-receiver release. Read only when
        // FOTUFILM_FRAME_DONOR_LAYER is set, so a stock without one leaves harmless zeros.
        configuration += donorActive ? Self.parameters(labScanSetup.uncoloured(donor!.curve))
                                     : [Float](repeating: 0, count: 6)
        configuration += donorActive ? donor!.inhibition : [0, 0, 0]
        configuration += halationRingRadii
        // The spectral return matrix, in the mix domain (rows sum to the receiver's mix
        // share). Gated exactly as the scalar shares above: a masked-off stage's slots are
        // never read, and a zero matrix returns the direct light whatever the blur did.
        if selected(.halation) {
            configuration += halation.matrix.flatMap { $0 }
        } else {
            configuration += [Float](repeating: 0, count: 9)
        }
        // Optional second H&D population, followed by the two-scale MTF profile. These are
        // appended so every legacy configuration offset remains stable.
        configuration += filmCurves.flatMap(Self.secondaryParameters)
        configuration += mtfSecondarySigmas
        configuration += mtfSecondaryRadii.map(Float.init)
        configuration += mtfPrimaryShare
        configuration += stock.couplerReleaseGamma
        configuration += [donor?.releaseGamma ?? 1]
        if diffusionActive, let halo = diffusionHalo, halo.weights.count > 3 {
            configuration += halo.weights[3].map { $0 * halo.scatteredShare }
        } else {
            configuration += [0, 0, 0]
        }
        // Only a genuine reversal stock develops to a direct positive. A negative viewed on a
        // light box or scanner shares the reversal feature bit — the output routing is the same —
        // but its emulsion still forms a negative, and the grain rides that density.
        configuration += [stock.isReversal ? 1 : 0]
        // The superseded shared slot keeps the green record's first three coefficients for
        // kernels built against the earlier layout; the per-record rows are appended below.
        configuration += Array(stock.grainDensityProfile.records[1].prefix(3))
        // The material's own SDR knee, which the byte delivery reads; `setOutputTransform`
        // replaces it with the stated delivery's before enabling an `encodeOut` variant.
        configuration += [options.sdrShoulderKnee(for: stock)]
        configuration += [0, 0, 0, 0] // optional output gamut fit
        for curve in filmCurves { configuration += Self.sampledParameters(curve) }
        configuration += [screenedAdjacency ? 1 : 0,
                          adjacencySecondarySigma, Float(adjacencySecondaryRadius)]
        configuration += [fringeActive ? fringeAmount : 0, fringeSigma, Float(fringeRadius)]
        configuration += [0] // typed record-exposure seam
        configuration += stock.grainReversalProfile
        let cameraPreflash = !noFilm ? max(0, options.cameraPreflash) : 0
        let printerPreflash = (!noFilm && options.stage.writesPrint && options.negativeViewing == nil
            && Enlarger.illuminates(stock: stock, paper: printMedium)) ? max(0, options.printerPreflash) : 0
        configuration += [cameraPreflash, printerPreflash]
        // The byte frames' primaries, input then output: Display P3 until a road says sRGB.
        configuration += [0, 0]
        configuration += stock.grainDensityProfile.records.flatMap { $0 }
        filmGrainPopulation = filmActive
            ? FilmGrain.Population(stock: stock, reference: referenceRoll) : nil
        if filmActive && bindsFilmGrain {
            let film = try FilmGrain.binding(stock: stock, reference: referenceRoll,
                                             checkCancellation: checkCancellation)
            if film.registrationStatus != 0,
               fotufilm_halide_available() == 1 || fotufilm_halide_metal_available() == 1 {
                throw TransportError.backend("Film grain tile registration failed (status \(film.registrationStatus))")
            }
            try checkCancellation()
            self.filmTileBinding = film
            configuration += film.configurationBlock(pxPerMM: pxPerMM, amount: grainScale,
                                                      look: options.filmGrain)
            try checkCancellation()
        } else {
            self.filmTileBinding = nil
            configuration += [Float](repeating: 0, count: Int(FOTUFILM_CONFIG_FILM_TILE_COUNT))
        }
        configuration += options.gateConfiguration(width: width, height: height)
        configuration += [HDRHighlightRange.knee,
                          HDRHighlightRange.curvature(headroom: options.sceneHeadroom,
                                                      range: options.hdrRange)]
        configuration.append(options.gateCornerConfiguration(width: width, height: height))
        configuration += options.carrierConfiguration(width: width, height: height)
        configuration += options.stage == .print ? options.printFinish.packed
            : PrintFinish.neutral.packed
        configuration += ApproximateNegativeScan.packedReading(
            options.stage == .print ? options.scanReading : nil)
        precondition(configuration.count == Self.configurationCount)

        var optical = 0
        var lightReach = 0
        if featureMask & FilmEngineFeature.mtf != 0 {
            lightReach = max(mtfRadii.max() ?? 0,
                             mtfSecondaryRadii.max() ?? 0,
                             mtfLumaActive ? mtfLumaRadius : 0)
            optical += lightReach
        }
        // The lens diffusion filter runs ahead of the emulsion, so its reach stacks under
        // everything downstream — and a strip that stops at LIGHT_OUT has already been through
        // it, so the light chain's own reach carries it too. Measured the way halation's is,
        // since it rides the same decimated pyramid.
        if featureMask & FilmEngineFeature.diffusion != 0 {
            let reach = diffusionRadii.map {
                Self.boxPyramidSupport(radius: $0,
                    stride: Int(fotufilm_diffusion_stride(Int32($0))))
            }.max() ?? 0
            optical += reach
            lightReach += reach
        }
        var halationReach = 0
        if featureMask & FilmEngineFeature.halation != 0 {
            halationReach = zip(halationRadii, halationRingRadii).map {
                Self.boxPyramidSupport(radius: $0.0,
                    stride: Int(fotufilm_halation_stride(Int32($0.0))), ringRadius: $0.1)
            }.max() ?? 0
            optical += halationReach
        }
        // The coupler, fringe and adjacency fields blur on decimated grids, and a decimated blur
        // reads past its nominal radius: the reach is what the schedule really touches.
        var diffusion = 0
        if featureMask & FilmEngineFeature.couplers != 0 {
            diffusion = max(diffusion,
                            Int(fotufilm_gaussian_grid_reach(couplerSigma, Int32(couplerRadius))),
                            Int(fotufilm_gaussian_grid_reach(fringeSigma, Int32(fringeRadius))))
        }
        if featureMask & FilmEngineFeature.adjacency != 0 {
            diffusion = max(diffusion,
                            Int(fotufilm_gaussian_grid_reach(adjacencySigma,
                                                             Int32(adjacencyRadius))),
                            Int(fotufilm_gaussian_grid_reach(adjacencySecondarySigma,
                                                             Int32(adjacencySecondaryRadius))))
        }
        optical += diffusion
        let grainSupport = featureMask & FilmEngineFeature.grain != 0 ? grainRadius : 0
        // The print MTF reads neighbouring developed densities, so its reach stacks after both
        // the optical chain and the grain field. Omitting it from the optical reach truncates
        // the negative near a tile boundary before the print reads those neighbours.
        let printSupport = featureMask & FilmEngineFeature.printMTF != 0 ? printMTFRadius : 0
        self.spatialSupport = max(optical, grainSupport) + printSupport
        // The same support with halation's reach taken out — what a strip needs when the
        // halation fields arrive whole-frame — and the two pieces the fields path prices its
        // passes by: the reach of the light chain alone, and halation's own.
        self.spatialSupportSansHalation = max(optical - halationReach,
                                              grainSupport) + printSupport
        self.lightSupport = lightReach
        self.halationSupport = halationReach
        self.halationPixelRadii = halationRadii.map(Int32.init)

        // A regional tone base is a measurement of the scene, and `PipelineStage.print` is handed
        // a developed negative instead. The scene-referred stages it keys are not in this span at
        // all, so there is nothing for it to key and nothing to measure.
        self.localToneEnabled = options.localTone && options.stage.readsScene
        self.configuration = configuration
        let screenMeters = printMedium == .screen && (!stock.isReversal || levelsPositive)
            && options.digitalReference == .autoLevels
        let labScanMeters = printMedium == .labScan && !stock.isReversal
        if !noFilm, !showingNegative, !stock.isReflectionPrint, screenMeters || labScanMeters,
           options.stage.readsScene, options.sceneHighlightStops == nil {
            self.meterStock = stock
            self.meterMedium = printMedium
            self.meterExposureEV = options.exposureEV
            self.meterDodging = options.labScanDodging
            self.meterScanExposure = options.screenExposureEV
            self.meterKeys = options.screenCMY
            self.meterLevels = MeteredLevels(
                scale: levels.scale, shift: levels.shift + autoColour, film: labScanSetup)
        } else if toneKey != nil {
            // A host handed the reading on: what Auto Levels added is not the user's tone.
            self.meterLevels = MeteredLevels(
                scale: levels.scale, shift: levels.shift + autoColour, film: labScanSetup,
                filmBoost: filmBoost, toneKey: toneKey, shadowLift: shadowLift,
                highlightHold: highlights - held)
        }
        if noFilm {
            // Nothing samples these: the variant compiles no spectral recovery, no film cube and
            // no paper cube. Deriving a stock's tables to hand a kernel that will not read them
            // would cost the whole build and evict a real stock's from the cache, so the
            // invocation carries the empty ones the shims still need pointers to.
            self.spectral = Self.unusedSpectralTables
            self.spectralCacheID = 0x4E4F46494C4D0000
        } else if showingNegative, let look = negativeViewing {
            let base = SpectralRuntime.tables(for: stock)
            self.spectral = SpectralPipelineTables(
                exposure: base.exposure,
                filmOutput: SpectralRuntime.negativeViewing(
                    for: stock, look: look, bleachBypass: options.bleachBypass),
                paperOutput: nil)
            self.spectralCacheID = (SpectralRuntime.cacheIdentifier(
                for: stock, bleachBypass: options.bleachBypass))
                ^ (0x4E45474154495645 &+ look.ordinal)
        } else {
            let callier = options.enlarger.callierCoefficient(for: stock, paper: printMedium)
            self.spectral = SpectralRuntime.tables(
                for: stock, paper: printMedium,
                bleachBypass: options.bleachBypass,
                printViewingKelvin: options.printViewingKelvin,
                callier: callier, printer: printer,
                digitalReference: options.digitalReference,
                screenGrade: options.screenGrade, screenExposureEV: options.screenExposureEV,
                labScanLook: options.labScanLook, receiverBands: options.receiverBands)
            self.spectralCacheID = SpectralRuntime.cacheIdentifier(
                for: stock, paper: printMedium,
                bleachBypass: options.bleachBypass,
                printViewingKelvin: options.printViewingKelvin,
                callier: callier, printer: printer,
                digitalReference: options.digitalReference,
                screenGrade: options.screenGrade, screenExposureEV: options.screenExposureEV,
                labScanLook: options.labScanLook, receiverBands: options.receiverBands)
        }
        // One resolved spectrum controls both integration and upload identity. Source pixels
        // have already been neutralized at capture; applying RGB WB here would count light twice.
        if !noFilm {
            let illuminant = options.resolvedSceneSpectrum(
                referenceKelvin: stock.referenceIlluminantKelvin)
            let exposure = options.lensFilters.isEmpty
                ? SpectralRuntime.sceneExposure(for: stock, illuminant: illuminant)
                : SpectralRuntime.filteredExposure(for: stock, illuminant: illuminant,
                                                   stack: options.lensFilters)
            self.spectral = SpectralPipelineTables(
                exposure: exposure, filmOutput: self.spectral.filmOutput,
                paperOutput: self.spectral.paperOutput)
            self.spectralCacheID = (self.spectralCacheID
                ^ SpectralRuntime.illuminantSignature(illuminant)) &* 0x100000001b3
            if !options.lensFilters.isEmpty {
                self.spectralCacheID = (self.spectralCacheID
                    ^ options.lensFilters.signature) &* 0x100000001b3
            }
        }
        self.featureMask = featureMask
        self.seed = UInt32(truncatingIfNeeded: animatedSeed)
    }

    /// Whole-frame veiling-glare mean.
    /// Correctly shaped and entirely zero. A no-film invocation still has to hand the kernels
    /// LUT pointers of the dimension they were compiled against; what is behind them is never
    /// sampled. Built once.
    static let unusedSpectralTables: SpectralPipelineTables = {
        let dimension = SpectralRuntime.lutDimension
        let empty = SpectralLUT(
            dimension: dimension,
            values: [Float](repeating: 0,
                            count: dimension * dimension * dimension * 4))
        return SpectralPipelineTables(exposure: empty, filmOutput: empty,
                                      paperOutput: nil)
    }()

    /// Packs the host's own last step into the slots the `encodeOut` variants read. Setting it
    /// does not select those variants — the caller sets the feature bit, because a build without
    /// the variant this frame needs has to fall back to encoding on the host.
    public mutating func setOutputTransform(_ transform: FilmOutputTransform) {
        for (index, value) in transform.matrix.enumerated() {
            configuration[Self.outputMatrixOffset + index] = value
        }
        configuration[Self.outputTransferOffset] = Float(transform.transfer.rawValue)
        for (index, value) in transform.coefficients.enumerated() {
            configuration[Self.outputCoefficientsOffset + index] = value
        }
        configuration[Self.outputPremultipliedOffset] = transform.premultiplied ? 1 : 0
        configuration[Self.outputShoulderOffset] = transform.shoulderKnee ?? -1
        configuration[Self.outputGamutOffset] = transform.gamutLuminance == nil ? 0 : 1
        let luminance = transform.gamutLuminance ?? .zero
        for channel in 0..<3 {
            configuration[Self.outputGamutOffset + 1 + channel] = luminance[channel]
        }
    }

    public var flareMean: SIMD3<Float>? {
        get {
            let red = configuration[Self.flareMeanOffset]
            guard red >= 0 else { return nil }
            return SIMD3(red, configuration[Self.flareMeanOffset + 1],
                         configuration[Self.flareMeanOffset + 2])
        }
        set {
            let value = newValue ?? SIMD3(-1, -1, -1)
            configuration[Self.flareMeanOffset] = value.x
            configuration[Self.flareMeanOffset + 1] = value.y
            configuration[Self.flareMeanOffset + 2] = value.z
        }
    }

    /// Mean layer exposure over a whole frame of interleaved linear RGBA, computed the same way the
    /// pipeline's first two stages would: the scene-referred colour is split into a chromaticity
    /// inside the spectral cube and a radiance multiplier outside it, so highlights above diffuse
    /// white contribute their real weight to the glare.
    public func measuredFlareMean(linearRGBA pixels: UnsafePointer<Float>,
                                  width: Int, rows: Int) -> SIMD3<Float> {
        guard width > 0, rows > 0 else { return .zero }
        let mean = flareExposureSum(linearRGBA: pixels, width: width, rows: rows)
            / Double(width * rows)
        return SIMD3(Float(mean.x), Float(mean.y), Float(mean.z))
    }

    /// Area-weighted whole-frame glare measurement for the realtime schedules. Every source pixel
    /// contributes to a cell average, so a highlight cannot disappear between sparse sample points;
    /// only the expensive RGB-to-spectrum reconstruction is evaluated on the reduced grid.
    public func measuredAreaWeightedFlareMean(
        linearRGBA pixels: UnsafePointer<Float>, width: Int, height: Int
    ) -> SIMD3<Float> {
        measuredAreaWeightedFlareMean(FilmFlareFrame(
            linearRec2020RGBA: pixels, width: width, height: height))
    }

    public func measuredAreaWeightedFlareMean(
        encodedDisplayP3RGBA pixels: UnsafePointer<UInt8>, width: Int, height: Int
    ) -> SIMD3<Float> {
        measuredAreaWeightedFlareMean(FilmFlareFrame(
            encodedDisplayP3RGBA: pixels, width: width, height: height))
    }

    public func measuredAreaWeightedFlareMean(
        srgbRGBA pixels: UnsafePointer<UInt8>, width: Int, height: Int
    ) -> SIMD3<Float> {
        measuredAreaWeightedFlareMean(FilmFlareFrame(
            srgbRGBA: pixels, width: width, height: height))
    }

    public func measuredAreaWeightedFlareMean(_ frame: FilmFlareFrame) -> SIMD3<Float> {
        var total = SIMD3<Double>.zero
        withExposureSampler(
            red: { frame.averages[$0].x }, green: { frame.averages[$0].y },
            blue: { frame.averages[$0].z }, width: frame.gridWidth, startingAt: 0,
            coordinate: { (frame.positions[$0].x, frame.positions[$0].y) }
        ) { exposure in
            for index in frame.averages.indices {
                total += exposure(index) * Double(frame.counts[index])
            }
        }
        let mean = total / Double(frame.width * frame.height)
        return SIMD3(Float(mean.x), Float(mean.y), Float(mean.z))
    }

    /// Unnormalized form of `measuredFlareMean`, so a frame measured in bands
    /// gives the same answer as one measured whole.
    public func flareExposureSum(linearRGBA pixels: UnsafePointer<Float>,
                                 width: Int, rows: Int) -> SIMD3<Double> {
        var total = SIMD3<Double>.zero
        withExposureSampler(linearRGBA: pixels, width: width) { sample in
            for index in 0..<(width * rows) { total += sample(index) }
        }
        return total
    }

    /// Per-row unnormalized exposure sums for `rows` contiguous rows of interleaved linear RGBA.
    public func flareExposureRowSums(
        linearRGBA pixels: UnsafePointer<Float>, width: Int, rows: Int,
        startingAt firstRow: Int = 0,
        into sums: UnsafeMutableBufferPointer<SIMD3<Double>>
    ) {
        precondition(sums.count >= rows)
        guard width > 0, rows > 0 else { return }
        let destination = sums.baseAddress!
        // Walked with the sampler rather than a per-index closure: this is the one loop in the
        // engine that runs the first stage over every pixel of the frame, so an indirect call
        // per pixel is the difference between a few milliseconds and tens of them. The row
        // decomposition and the order within a row are load-bearing — the sums are `Double` and
        // the frame's mean is their total, so regrouping them moves the answer.
        withExposureSampler { sampler in
            ParallelWork.forEach(iterations: rows) { row in
                var total = SIMD3<Double>.zero
                let scan = pixels + row * width * 4
                let y = firstRow + row
                for x in 0..<width {
                    total += sampler.exposure(
                        SIMD3(scan[x * 4], scan[x * 4 + 1], scan[x * 4 + 2]),
                        x: x, y: y)
                }
                destination[row] = total
            }
        }
    }

    /// Per-row unnormalized exposure sums for planar RGB, the layout the CPU
    /// still pipeline works in.
    public func flareExposureRowSums(
        planarR red: UnsafePointer<Float>, g green: UnsafePointer<Float>,
        b blue: UnsafePointer<Float>, width: Int, rows: Int,
        startingAt firstRow: Int = 0,
        into sums: UnsafeMutableBufferPointer<SIMD3<Double>>
    ) {
        precondition(sums.count >= rows)
        guard width > 0, rows > 0 else { return }
        let destination = sums.baseAddress!
        withExposureSampler(red: { red[$0] }, green: { green[$0] },
                            blue: { blue[$0] }, width: width,
                            startingAt: firstRow) { sample in
            ParallelWork.forEach(iterations: rows) { row in
                var total = SIMD3<Double>.zero
                let start = row * width
                for index in start..<(start + width) { total += sample(index) }
                destination[row] = total
            }
        }
    }

    /// Lends a "what exposure did pixel `index` put on the film" closure with the spectral table's
    /// storage borrowed for the whole call. Convenient, but it costs an indirect call per pixel
    /// and another three to reach the pixel: a caller walking contiguous rows should take
    /// `ExposureSampler` and write the loop itself.
    private func withExposureSampler(
        linearRGBA pixels: UnsafePointer<Float>, width: Int,
        startingAt firstRow: Int = 0,
        _ body: ((Int) -> SIMD3<Double>) -> Void
    ) {
        withExposureSampler(red: { pixels[$0 * 4] }, green: { pixels[$0 * 4 + 1] },
                            blue: { pixels[$0 * 4 + 2] }, width: width,
                            startingAt: firstRow, body)
    }

    /// The per-index closure, built on the sampler so there is still one definition of the maths.
    private func withExposureSampler(
        red: @escaping (Int) -> Float, green: @escaping (Int) -> Float,
        blue: @escaping (Int) -> Float, width: Int, startingAt firstRow: Int,
        coordinate: ((Int) -> (Int, Int))? = nil,
        _ body: ((Int) -> SIMD3<Double>) -> Void
    ) {
        withExposureSampler { sampler in
            // A neutral scene never asks where the pixel was, so it must not pay for the
            // division or the coordinate closure to find out.
            if sampler.neutral {
                body { index in
                    sampler.exposure(SIMD3(red(index), green(index), blue(index)),
                                     x: 0, y: 0)
                }
                return
            }
            body { index in
                let point = coordinate?(index)
                return sampler.exposure(
                    SIMD3(red(index), green(index), blue(index)),
                    x: point?.0 ?? index % width,
                    y: point?.1 ?? firstRow + index / width)
            }
        }
    }

    /// Borrows the spectral table and the tone grid for the length of one call and lends the
    /// sampler that reads them. Both pointers die with the call, so the sampler must not escape it.
    private func withExposureSampler(_ body: (ExposureSampler) -> Void) {
        let gain = configuration[FilmEngineInvocation.exposureGainOffset] / 0.18
        let offset = FilmEngineInvocation.whiteBalanceOffset
        let balance = SIMD3<Float>(configuration[offset], configuration[offset + 1],
                                   configuration[offset + 2])
        let adjust = FilmEngineInvocation.sceneAdjustOffset
        let highlights = configuration[adjust]
        let shadows = configuration[adjust + 1]
        let saturation = configuration[adjust + 2]
        let vibrance = configuration[adjust + 3]
        let neutral = highlights == 0 && shadows == 0
            && saturation == 1 && vibrance == 0
        let dimension = spectral.exposure.dimension
        let gridWidth = max(1, Int(configuration[
            FilmEngineInvocation.toneGridSizeOffset]))
        let gridHeight = max(1, Int(configuration[
            FilmEngineInvocation.toneGridSizeOffset + 1]))
        let frameWidth = max(configuration[
            FilmEngineInvocation.frameSizeOffset], 1)
        let frameHeight = max(configuration[
            FilmEngineInvocation.frameSizeOffset + 1], 1)
        spectral.exposure.values.withUnsafeBufferPointer { storage in
            configuration.withUnsafeBufferPointer { config in
                body(ExposureSampler(
                    gain: gain, balance: balance, highlights: highlights,
                    shadows: shadows, saturation: saturation, vibrance: vibrance,
                    neutral: neutral, values: storage.baseAddress!, dimension: dimension,
                    luma: ColorScience.luminanceWeights,
                    gridWidth: gridWidth, gridHeight: gridHeight,
                    frameWidth: frameWidth, frameHeight: frameHeight,
                    planeA: config.baseAddress! + FilmEngineInvocation.toneGridAOffset,
                    planeB: config.baseAddress! + FilmEngineInvocation.toneGridBOffset))
            }
        }
    }

    /// Mirrors FOTUFILM_CONFIG_EXPOSURE_GAIN.
    public static let exposureGainOffset = Int(FOTUFILM_CONFIG_EXPOSURE_GAIN)

    private static func parameters(_ curve: CharacteristicCurve) -> [Float] {
        // Legacy range consumers still read gamma * (shoulder - toe).
        let gamma = curve.sampled == nil ? curve.gamma
            : (curve.dMax - curve.dMin) / (curve.shoulder - curve.toe)
        return [curve.dMin, gamma, curve.toe, curve.toeWidth,
         curve.shoulder, curve.shoulderWidth]
    }

    /// The calibrated stock is the 100% position. Above it, the creative overdrive approaches a
    /// second calibrated dose instead of continuing without bound: doubled spatial inhibition can
    /// otherwise pull a small yellow subject toward a green surround. Values through 100% retain
    /// their historical linear meaning exactly; the UI's 200% endpoint resolves to 150% physical
    /// inhibition and the asymptote remains 200%.
    static func effectiveCouplerScale(_ requested: Float) -> Float {
        let nonnegative = max(requested, 0)
        guard nonnegative > 1 else { return nonnegative }
        let overdrive = nonnegative - 1
        return 1 + overdrive / (1 + overdrive)
    }

    private static func sampledParameters(_ curve: CharacteristicCurve) -> [Float] {
        var record = [Float](repeating: 0, count: sampledCurveStride)
        if let sampled = curve.sampled {
            record[0] = Float(sampled.logExposure.count)
            for i in sampled.logExposure.indices {
                record[1 + i * 3] = sampled.logExposure[i]
                record[2 + i * 3] = sampled.density[i]
                record[3 + i * 3] = sampled.slopes[i]
            }
        }
        return record
    }

    /// Re-packs the film's records, and the donor's when it has one, as `setup` exposes them:
    /// every slot a curve occupies, so a metered frame's setup reaches each renderer.
    mutating func packFilmCurves(of stock: FilmStock, setup: LabScanTiming.Setup) {
        let curves = setup.curves(for: stock)
        func write(_ values: [Float], at offset: Int) {
            configuration.replaceSubrange(offset..<(offset + values.count), with: values)
        }
        write(curves.flatMap(Self.parameters), at: Int(FOTUFILM_CONFIG_CURVES))
        write(curves.flatMap(Self.secondaryParameters), at: Self.curveSecondaryOffset)
        write(curves.flatMap(Self.sampledParameters), at: Self.sampledCurvesOffset)
        if let donor = stock.donorLayers.first, featureMask & FilmEngineFeature.donorLayer != 0 {
            write(Self.parameters(setup.uncoloured(donor.curve)), at: Int(FOTUFILM_CONFIG_DONOR_CURVE))
        }
    }

    private static func secondaryParameters(_ curve: CharacteristicCurve) -> [Float] {
        if curve.sampled != nil { return [0, 0, 1, 0, 1] }
        guard let secondary = curve.secondary else { return [0, 0, 1, 0, 1] }
        return [secondary.gamma, secondary.toe, secondary.toeWidth,
                secondary.shoulder, secondary.shoulderWidth]
    }

    /// Shared host evaluator for packed sampled film records used by Metal table builders.
    public static func sampledFilmDensity(configuration: [Float], channel: Int,
                                          logExposure x: Float) -> Float? {
        guard (0..<3).contains(channel), configuration.count == configurationCount else { return nil }
        let base = sampledCurvesOffset + channel * sampledCurveStride
        guard configuration[base].isFinite,
              configuration[base] >= 2,
              configuration[base] <= Float(SampledCharacteristicCurve.maximumSamples) else { return nil }
        let count = Int(configuration[base])
        func value(_ index: Int, _ field: Int) -> Float {
            configuration[base + 1 + index * 3 + field]
        }
        if x <= value(0, 0) { return value(0, 1) }
        if x >= value(count - 1, 0) { return value(count - 1, 1) }
        var low = 0, high = count - 1
        while high - low > 1 {
            let mid = (low + high) / 2
            if value(mid, 0) <= x { low = mid } else { high = mid }
        }
        return SampledCharacteristicCurve.hermite(
            x: x, x0: value(low, 0), x1: value(high, 0),
            y0: value(low, 1), y1: value(high, 1),
            m0: value(low, 2), m1: value(high, 2))
    }

    /// Solves the neutral anchor for the DIR coupler stage. `donor` joins the release sum the
    /// way it joins the per-pixel one: the published curves already carry every inter-image
    /// effect a neutral develops — the donor's included — so a donor outside the warp would
    /// develop its inhibition twice on the grey axis.
    static func couplerWarp(curves: [CharacteristicCurve],
                            inhibition: [[Float]], scale: Float,
                            releaseGamma: [Float] = [1, 1, 1],
                            donor: DonorCaptureLayer? = nil) -> [Float] {
        let samples = couplerWarpSamples
        guard scale > 0,
              inhibition.contains(where: { $0.contains { $0 != 0 } })
                || donor?.inhibition.contains(where: { $0 != 0 }) == true else {
            return [Float](repeating: 0, count: 3 * samples)
        }
        let solveCount = 4 * samples
        let solveLow = couplerWarpMin - 1
        let solveStep = ((couplerWarpMax + 1) - solveLow) / Float(solveCount - 1)

        var activation = [[Float]](
            repeating: [Float](repeating: 0, count: solveCount), count: 3)
        for layer in 0..<3 {
            let curve = curves[layer]
            let range = curve.dMax - curve.dMin
            guard range > 0 else { continue }
            for i in 0..<solveCount {
                let l = solveLow + Float(i) * solveStep
                let developed = (curve.density(logExposure: l) - curve.dMin) / range
                activation[layer][i] = inhibitorRelease(
                    activation: developed, gamma: releaseGamma[layer])
            }
        }
        // On the neutral axis the donor is exposed exactly as the layers are — that is what
        // the shared neutral normalization buys — so its activation sits on the same axis.
        var donorActivation = [Float](repeating: 0, count: solveCount)
        if let donor, donor.curve.dMax > donor.curve.dMin {
            let range = donor.curve.dMax - donor.curve.dMin
            for i in 0..<solveCount {
                let l = solveLow + Float(i) * solveStep
                let developed =
                    (donor.curve.density(logExposure: l) - donor.curve.dMin) / range
                donorActivation[i] = inhibitorRelease(
                    activation: developed, gamma: donor.releaseGamma)
            }
        }

        var table = [Float](repeating: 0, count: 3 * samples)
        for channel in 0..<3 {
            var u = [Float](repeating: 0, count: solveCount)
            var offset = [Float](repeating: 0, count: solveCount)
            for i in 0..<solveCount {
                var released: Float = 0
                for donorLayer in 0..<3 {
                    released += inhibition[channel][donorLayer]
                        * activation[donorLayer][i]
                }
                if let donor {
                    released += donor.inhibition[channel] * donorActivation[i]
                }
                offset[i] = scale * released
                u[i] = solveLow + Float(i) * solveStep - offset[i]
            }
            for i in 1..<solveCount where u[i] <= u[i - 1] {
                u[i] = u[i - 1] + 1e-6
            }
            var cursor = 0
            for k in 0..<samples {
                let target = couplerWarpMin
                    + (couplerWarpMax - couplerWarpMin) * Float(k) / Float(samples - 1)
                while cursor + 2 < solveCount && u[cursor + 1] < target { cursor += 1 }
                let span = u[cursor + 1] - u[cursor]
                let t = span > 0 ? min(max((target - u[cursor]) / span, 0), 1) : 0
                table[channel * samples + k] =
                    offset[cursor] + t * (offset[cursor + 1] - offset[cursor])
            }
        }
        return table
    }

    /// Threshold-and-saturation law for development-inhibitor release. The normalized Hill form
    /// fixes 0, 0.5 and 1, so it changes chromatic inter-image response without moving the neutral
    /// anchor's midpoint. Gamma 1 is kept as an exact linear compatibility mode.
    static func inhibitorRelease(activation: Float, gamma: Float) -> Float {
        let a = min(max(activation, 0), 1)
        guard gamma != 1 else { return a }
        let released = Float(pow(Double(a), Double(gamma)))
        let retained = Float(pow(Double(1 - a), Double(gamma)))
        return released / max(released + retained, Float.leastNonzeroMagnitude)
    }

    /// Lends the exposure, film-output, and paper-output LUT storage to a C call.
    public func withSpectralPointers<Result>(
        _ body: (UnsafePointer<Float>?, UnsafePointer<Float>?, UnsafePointer<Float>?) -> Result
    ) -> Result {
        let paper = spectral.paperOutput ?? spectral.filmOutput
        return withExtendedLifetime(filmTileBinding) {
            spectral.exposure.values.withUnsafeBufferPointer { exposure in
                spectral.filmOutput.values.withUnsafeBufferPointer { film in
                    paper.values.withUnsafeBufferPointer { paper in
                        body(exposure.baseAddress, film.baseAddress, paper.baseAddress)
                    }
                }
            }
        }
    }
}
