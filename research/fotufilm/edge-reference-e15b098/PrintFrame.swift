import Foundation

/// Physical film/paper finishing, mounts, and the film's unexposed edge printed with the picture.
public enum PrintFrame: String, CaseIterable, Codable, Sendable, Identifiable {
    case none, film, filmPositive, slideMount, paper, paper5x7, paper8x10, paper5x5, carrier, emulsion, mount, darkMount,
         socialSquare, socialPortrait, socialStory
    public var id: String { rawValue }
    public var name: String {
        switch self {
        case .none: return "None"
        case .film: return "Film Border"
        case .filmPositive: return "Film Border Positive"
        case .slideMount: return "Slide Mount"
        case .paper: return "Paper Border 4 × 6"
        case .paper5x7: return "Paper Border 5 × 7"
        case .paper8x10: return "Paper Border 8 × 10"
        case .paper5x5: return "Paper Border 5 × 5"
        case .carrier: return "Carrier Border"
        case .emulsion: return "Emulsion Border"
        case .mount: return "White Mount"
        case .darkMount: return "Black Mount"
        case .socialSquare: return "Square Post"
        case .socialPortrait: return "Portrait Post"
        case .socialStory: return "Story"
        }
    }
    public var detail: String {
        switch self {
        case .none: return "The photograph without a border."
        case .film: return "The selected film gauge, with its physical edges and perforations."
        case .filmPositive: return "The negative's film border scanned and inverted: a dark rebate, light edge printing and black perforations around the positive photograph."
        case .slideMount: return "The developed transparency in a card slide mount."
        case .paper: return "The selected photographic paper, in a 4 × 6 inch print."
        case .paper5x7: return "The selected photographic paper, in a 5 × 7 inch print."
        case .paper8x10: return "The selected photographic paper, in an 8 × 10 inch print."
        case .paper5x5: return "The selected photographic paper, in a 5 × 5 inch print."
        case .carrier: return "The film rebate printed through a filed-out negative carrier: a black line inside the paper margin."
        case .emulsion: return "The film just outside the camera gate, printed with the photograph: the stock's own unexposed tone, glow and grain, inside a paper margin."
        case .mount: return "A clean white margin around the photograph."
        case .darkMount: return "A clean black margin around the photograph."
        case .socialSquare: return "A square 1 : 1 canvas for posting, the photograph inside a white margin."
        case .socialPortrait: return "A 4 : 5 portrait canvas for posting, the photograph inside a white margin."
        case .socialStory: return "A 9 : 16 story canvas, the photograph inside a white margin."
        }
    }

    /// A display-white canvas at a fixed posting aspect, with no manufactured material behind it.
    public var isSocialCanvas: Bool {
        switch self {
        case .socialSquare, .socialPortrait, .socialStory: return true
        default: return false
        }
    }

    /// A cut sheet of the selected photographic paper, with or without a printed rebate.
    public var isPaperSheet: Bool {
        switch self {
        case .paper, .paper5x7, .paper8x10, .paper5x5, .carrier: return true
        default: return false
        }
    }

    /// A crop-following presentation mount with no manufactured material behind it.
    public var isPlainMount: Bool { self == .mount || self == .darkMount }

    /// The developed film is seen by transmission, not printed to the chosen paper.
    public var viewsTransparency: Bool { self == .film || self == .slideMount }

    /// The physical film strip around the aperture, as the negative or as its inverted scan.
    public var showsFilmStrip: Bool { self == .film || self == .filmPositive }

    /// A margin that is a presentation or easel choice, which the editor lets the user widen
    /// or narrow. Film borders and slide mounts follow physical dimensions and keep theirs.
    public var hasAdjustableMargin: Bool {
        switch self {
        case .none, .film, .filmPositive, .slideMount: return false
        default: return true
        }
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let value = try container.decode(String.self)
        // Migrate the initial, unreleased decorative frame names into material-following modes.
        switch value {
        case "none": self = .none
        case "film", "film-35", "instant": self = .film
        case "paper", "contact", "baryta", "cotton": self = .paper
        case "emulsion": self = .emulsion
        default:
            guard let frame = PrintFrame(rawValue: value) else {
                throw DecodingError.dataCorruptedError(in: container, debugDescription: "Unknown print frame")
            }
            self = frame
        }
    }
}

/// A cut sheet of photographic paper on an easel. Sheet sizes are lab print choices, not
/// intrinsic dimensions of the emulsion; the easel margin and the filed carrier's rebate are
/// representative darkroom conventions documented in docs/print-frame-model.md.
public struct PaperSheetGeometry: Codable, Equatable, Sendable {
    public let widthMM: Double
    public let heightMM: Double
    /// The white margin held by the easel blades on every side.
    public let marginMM: Double
    /// The printed film rebate inside the easel opening; zero for a plain print.
    public let rebateMM: Double

    public static func preset(for frame: PrintFrame) -> Self? {
        switch frame {
        case .paper: return Self(widthMM: 152.4, heightMM: 101.6, marginMM: 3, rebateMM: 0)
        case .paper5x7: return Self(widthMM: 177.8, heightMM: 127, marginMM: 3, rebateMM: 0)
        case .paper8x10: return Self(widthMM: 254, heightMM: 203.2, marginMM: 3, rebateMM: 0)
        case .paper5x5: return Self(widthMM: 127, heightMM: 127, marginMM: 3, rebateMM: 0)
        case .carrier: return Self(widthMM: 254, heightMM: 203.2, marginMM: 12.7, rebateMM: 2.5)
        default: return nil
        }
    }
}

/// A fixed-aspect canvas for a social post. The aspects are the platforms' own; the margin is a
/// presentation choice. The photograph is fitted inside without cropping, so a landscape picture
/// on a story canvas stands between white bands, as posts do.
public struct SocialCanvasGeometry: Codable, Equatable, Sendable {
    public let aspectWidth: Double
    public let aspectHeight: Double
    /// The white margin on every side, as a fraction of the canvas's short side.
    public let margin: Double

    public static func preset(for frame: PrintFrame) -> Self? {
        switch frame {
        case .socialSquare: return Self(aspectWidth: 1, aspectHeight: 1, margin: 0.05)
        case .socialPortrait: return Self(aspectWidth: 4, aspectHeight: 5, margin: 0.05)
        case .socialStory: return Self(aspectWidth: 9, aspectHeight: 16, margin: 0.05)
        default: return nil
        }
    }
}

/// A card mount for a developed transparency. The outer size is the universal 2 × 2 inch
/// projector format; the aperture is the typical mounted image area for the gauge.
public struct SlideMountGeometry: Codable, Equatable, Sendable {
    public let mountMM: Double
    public let apertureWidth: Double
    public let apertureHeight: Double
    public let cornerRadiusMM: Double

    public static func preset(_ formatID: String) -> Self? {
        switch formatID {
        case "35mm":
            return Self(mountMM: 50.8, apertureWidth: 34.5, apertureHeight: 23, cornerRadiusMM: 1)
        case "120":
            return Self(mountMM: 70, apertureWidth: 56, apertureHeight: 56, cornerRadiusMM: 1)
        default: return nil
        }
    }
}

/// A manufacturer's sheet-film identification pattern, read left to right with the emulsion
/// facing the viewer and the sheet upright. Locations are fractions of the code's span.
/// The source diagrams identify the pattern; they do not specify cutting tolerances.
public struct SheetFilmNotchCode: Codable, Equatable, Sendable {
    public struct Notch: Codable, Equatable, Sendable {
        public var position: Double
        public var width: Double
        public var depth: Double
        public init(position: Double, width: Double, depth: Double) {
            self.position = position; self.width = width; self.depth = depth
        }
    }
    public var source: String
    public var notches: [Notch]
    public init(source: String, notches: [Notch]) {
        self.source = source; self.notches = notches
    }
}

/// A representative cut from documented edge printing, viewed from the base side.
/// Coordinates are millimetres: x along transport, y across the film, origin lower left.
/// The boxes specify approximate inscription proportions, not measured printer tolerances.
/// Unknown batch/roll numbers and machine-readable codes must not be fabricated.
public struct FilmEdgePrinting: Codable, Equatable, Sendable {
    public struct Mark: Codable, Equatable, Sendable {
        public var text: String
        public var xMM: Double
        public var yMM: Double
        public var widthMM: Double
        public var heightMM: Double
    }
    public var formatID: String
    public var sources: [String]
    public var marks: [Mark]
    /// The DX film edge barcode, on 135 negatives that carry one.
    public var dx: DXEdgeCode?

    /// One exposed bar of a machine-readable code, in the same millimetres as `marks`.
    public struct Bar: Codable, Equatable, Sendable {
        public var xMM: Double
        public var yMM: Double
        public var widthMM: Double
        public var heightMM: Double
    }

    /// The frame numbers a still cut can show: 0 before the first exposure, through the 38
    /// that a long-loaded 36-exposure roll reaches.
    public static let frameNumberRange: ClosedRange<Int> = 0...38
    /// The frame number the stock data is recorded at: its marks reading "1" and "1A" are the
    /// cut's frame number and half-frame number.
    public static let recordedFrameNumber = 1

    /// The DX codes along this cut of 135 film, on the rail below the lower perforations between
    /// its printed frame numbers: the frame and its half frame, half a frame apart. The second
    /// runs off the cut, as a strip cut at a frame line does. A code starts a quarter millimetre
    /// past the number printed before it, so a wider number moves it along.
    public func barcode(frame: Int = recordedFrameNumber) -> [Bar] {
        guard formatID == "35mm", let dx else { return [] }
        let rail = marks.filter { $0.yMM < DXEdgeCode.clockTrackMM.upperBound }
        return [(10.2, false), (29.7, true)].flatMap { start, half in
            let number = rail.filter { $0.xMM < start }.max { $0.xMM < $1.xMM }
            let after = number.map { max(start, $0.xMM + $0.widthMM + 0.25) } ?? start
            return dx.bars(frame: frame, half: half, startMM: after)
        }
    }

    /// This cut renumbered as `frame`: its frame and half-frame numbers read `frame` and
    /// `frame`A, at their recorded positions and character pitch. Every other mark is the
    /// stock's own and stays.
    public func numbered(_ frame: Int) -> FilmEdgePrinting {
        let recorded = String(Self.recordedFrameNumber)
        var copy = self
        copy.marks = marks.map { mark in
            let text: String
            switch mark.text {
            case recorded: text = String(frame)
            case recorded + "A": text = "\(frame)A"
            default: return mark
            }
            var renumbered = mark
            renumbered.widthMM = mark.widthMM * Double(text.count) / Double(mark.text.count)
            renumbered.text = text
            return renumbered
        }
        return copy
    }

    /// The cut `numbered(frame)` and the `frames - 1` cuts after it on the roll, each a frame's
    /// length, `pitchMM`, further along and numbered one on, as one length of film.
    public func run(from frame: Int, frames: Int, pitchMM: Double) -> FilmEdgePrinting {
        var copy = numbered(frame)
        copy.marks = (0..<max(frames, 1)).flatMap { index in
            numbered(frame + index).marks.map { mark in
                var moved = mark
                moved.xMM += Double(index) * pitchMM
                return moved
            }
        }
        return copy
    }

    /// The DX codes along `run(from:frames:pitchMM:)`: each frame's own, where its cut puts them.
    public func barcodeRun(from frame: Int, frames: Int, pitchMM: Double) -> [Bar] {
        (0..<max(frames, 1)).flatMap { index in
            numbered(frame + index).barcode(frame: frame + index).map { bar in
                var moved = bar
                moved.xMM += Double(index) * pitchMM
                return moved
            }
        }
    }
}

/// The DX film edge barcode (ISO 1007, ANSI PH1.14), printed as a latent image along 135
/// negative film: a clock track and a data track of 31 cells, the data being the stock's DX
/// number and the frame number, repeated every half frame. Only the DX number is stock data;
/// no batch or roll serial is encoded. Cell layout follows Kodak's US 4,965,628; positions
/// follow the ANSI PH1.14 figures quoted in US 5,448,049. See docs/print-frame-model.md.
public struct DXEdgeCode: Codable, Equatable, Sendable {
    /// The product class, 1 to 127.
    public var part1: Int
    /// The specifier within the class, 0 to 15.
    public var part2: Int
    /// Where the number is documented.
    public var source: String

    public init(part1: Int, part2: Int, source: String) {
        self.part1 = part1; self.part2 = part2; self.source = source
    }

    /// Width of one cell along the film: the standard's minimum bar width.
    public static let cellMM = 0.381
    /// The data track, next to the film's edge, and the clock track nearer the perforations,
    /// as distances from the edge.
    public static let dataTrackMM = 0.25...0.76
    public static let clockTrackMM = 1.30...1.80

    /// The 31 cells of the code for `frame` (0 to 63) and its half frame: entry, part 1, a
    /// blank, part 2, frame number, half-frame flag, a blank, even parity over the 18 data bits,
    /// exit. True is an exposed mark.
    public func cells(frame: Int, half: Bool) -> (data: [Bool], clock: [Bool]) {
        func bits(_ value: Int, _ count: Int) -> [Bool] {
            (0..<count).map { value >> (count - 1 - $0) & 1 == 1 }
        }
        let first: [Bool] = bits(part1, 7), second: [Bool] = bits(part2, 4)
        let count: [Bool] = bits(frame & 63, 6)
        let payload: [Bool] = first + second + count + [half]
        let parity = payload.filter { $0 }.count % 2 == 1
        // Joined a piece at a time: one long sum of array literals and calls is past what Swift
        // 6.2's type checker solves on Linux.
        var data: [Bool] = [true, false, true, false, true, false]
        data += first
        data.append(false)
        data += second
        data += count
        data += [half, false, parity, false, true, false, true]
        // The clock track is the same in every code.
        let clock = "1111101010101010101010101010111".map { $0 == "1" }
        return (data, clock)
    }

    /// The code's exposed bars from `startMM` along the film, adjacent marks run together.
    func bars(frame: Int, half: Bool, startMM: Double) -> [FilmEdgePrinting.Bar] {
        let (data, clock) = cells(frame: frame, half: half)
        func runs(_ cells: [Bool], _ track: ClosedRange<Double>) -> [FilmEdgePrinting.Bar] {
            var result: [FilmEdgePrinting.Bar] = []
            var index = 0
            while index < cells.count {
                guard cells[index] else { index += 1; continue }
                let first = index
                while index < cells.count, cells[index] { index += 1 }
                result.append(.init(xMM: startMM + Double(first) * Self.cellMM, yMM: track.lowerBound,
                                    widthMM: Double(index - first) * Self.cellMM,
                                    heightMM: track.upperBound - track.lowerBound))
            }
            return result
        }
        return runs(data, Self.dataTrackMM) + runs(clock, Self.clockTrackMM)
    }
}

/// The camera a 135 Film Border's picture was exposed in (Camera).
public enum FilmBorderCamera: String, CaseIterable, Codable, Sendable, Identifiable {
    /// A 35mm or panoramic camera, whose gate stands between the perforation rows.
    case panoramic
    /// A medium-format camera loaded with 135: its taller gate exposes the film to both edges,
    /// across the perforations and the edge printing.
    case mediumFormatBack
    public var id: String { rawValue }
    public var name: String {
        switch self {
        case .panoramic: return "35mm or Panoramic Camera"
        case .mediumFormatBack: return "Medium-Format Back"
        }
    }
    public var detail: String {
        switch self {
        case .panoramic: return "The picture stands between the perforations, as a 35mm or panoramic camera's gate exposes it."
        case .mediumFormatBack: return "135 in a medium-format camera: the picture runs across the perforations to the film's edges."
        }
    }
}

/// Millimetre geometry before the finished photograph is fitted into the camera aperture.
/// x runs across the film, y along transport, except 135 stills which transport horizontally.
public struct FilmBorderGeometry: Codable, Equatable, Sendable {
    public struct PerforationDimensions: Codable, Equatable, Sendable {
        public let width: Double
        public let height: Double
        public let radius: Double
        public let edge: Double
    }
    public enum Perforation: String, Codable, Sendable {
        case kodakStandard, bellHowell, sixteen, superEight
        public var dimensions: PerforationDimensions {
            switch self {
            case .kodakStandard: return .init(width: 2.794, height: 1.981, radius: 0.51, edge: 2.01)
            case .bellHowell: return .init(width: 2.794, height: 1.854, radius: 0, edge: 2.01)
            case .sixteen: return .init(width: 1.829, height: 1.270, radius: 0.25, edge: 0.914)
            case .superEight: return .init(width: 0.914, height: 1.143, radius: 0.13, edge: 0.51)
            }
        }
    }
    public let widthMM: Double
    public let heightMM: Double
    public let apertureX: Double
    public private(set) var apertureY: Double
    public let apertureWidth: Double
    public private(set) var apertureHeight: Double
    public let perforation: Perforation?
    public let pitchMM: Double
    public let rows: Int
    public let horizontalTransport: Bool
    public let isInstant: Bool
    public let isSheet: Bool
    /// The frames' length of roll this cut runs across, one long picture exposed over them
    /// (`spanning`). The size and aperture above are the whole cut's.
    public private(set) var frames = 1
    /// Whether the aperture runs across the film to both edges (`exposingEdges`).
    public private(set) var exposesEdges = false

    /// How many frames a panorama can run across.
    public static let frameCountRange: ClosedRange<Int> = 1...4

    /// Whether one picture can run across several frames' length of this cut: 135 and 120 still
    /// roll film in a panoramic camera. A motion-picture frame is a fixed gate, and sheets and
    /// instant prints are single.
    public var spansFrames: Bool {
        !isSheet && !isInstant && (horizontalTransport || perforation == nil)
    }

    /// Whether a taller gate can expose this cut to its edges: 135, whose width is less than a
    /// medium-format camera's gate.
    public var opensToEdges: Bool { horizontalTransport && !isSheet && !isInstant }

    /// This cut exposed across its whole width, as 135 is in a medium-format camera: the aperture
    /// reaches both cut edges, over the perforations and edge printing, along the same length.
    public func exposingEdges() -> Self {
        guard opensToEdges, !exposesEdges else { return self }
        var cut = self
        cut.apertureY = 0
        cut.apertureHeight = heightMM
        cut.exposesEdges = true
        return cut
    }

    /// One frame's length of film along transport: the camera's frame advance.
    public var frameLengthMM: Double { (horizontalTransport ? widthMM : heightMM) / Double(frames) }

    /// This cut run on to `count` frames' length, exposed as one picture: a panoramic camera's
    /// wider gate, or 135 in a medium-format camera, advanced that far between exposures. The
    /// film and its aperture run on by a frame's length for each frame, the aperture unbroken.
    /// A gauge that does not `spansFrames` keeps one frame.
    public func spanning(_ count: Int) -> Self {
        let count = spansFrames
            ? min(max(count, Self.frameCountRange.lowerBound), Self.frameCountRange.upperBound) : 1
        guard count != frames else { return self }
        let length = frameLengthMM
        let aperture = (horizontalTransport ? apertureWidth : apertureHeight) - Double(frames - 1) * length
        let span = Double(count) * length, opening = aperture + Double(count - 1) * length
        var cut = Self(widthMM: horizontalTransport ? span : widthMM,
                       heightMM: horizontalTransport ? heightMM : span,
                       apertureX: apertureX, apertureY: apertureY,
                       apertureWidth: horizontalTransport ? opening : apertureWidth,
                       apertureHeight: horizontalTransport ? apertureHeight : opening,
                       perforation: perforation, pitchMM: pitchMM, rows: rows,
                       horizontalTransport: horizontalTransport, isInstant: isInstant, isSheet: isSheet)
        cut.frames = count
        return exposesEdges ? cut.exposingEdges() : cut
    }

    /// Camera apertures and pitch are physical format data; a nonmatching crop is fitted inside
    /// that aperture without stretching it. See docs/print-frame-model.md for sources/conventions.
    public static func preset(_ id: String, motionPictureStock: Bool = false) -> Self? {
        switch id {
        case "35mm":
            let pitch = motionPictureStock ? 4.74 : 4.75
            return Self(widthMM: pitch * 8, heightMM: 35,
                        apertureX: (pitch * 8 - 36) / 2, apertureY: 5.5,
                        apertureWidth: 36, apertureHeight: 24,
                        perforation: motionPictureStock ? .bellHowell : .kodakStandard,
                        pitchMM: pitch, rows: 2, horizontalTransport: true, isInstant: false, isSheet: false)
        case "super35":
            return Self(widthMM: 35, heightMM: 18.96, apertureX: 5.04, apertureY: 0.145,
                        apertureWidth: 24.92, apertureHeight: 18.67, perforation: .bellHowell,
                        pitchMM: 4.74, rows: 2, horizontalTransport: false, isInstant: false, isSheet: false)
        case "16mm": // The app's 16 mm choice is a single-perforation Super 16 camera gate.
            return Self(widthMM: 16, heightMM: 7.605, apertureX: 2.85, apertureY: 0.0925,
                        apertureWidth: 12.35, apertureHeight: 7.42, perforation: .sixteen,
                        pitchMM: 7.605, rows: 1, horizontalTransport: false, isInstant: false, isSheet: false)
        case "super8":
            return Self(widthMM: 7.976, heightMM: 4.234, apertureX: 1.59, apertureY: 0.112,
                        apertureWidth: 5.79, apertureHeight: 4.01, perforation: .superEight,
                        pitchMM: 4.234, rows: 1, horizontalTransport: false, isInstant: false, isSheet: false)
        case "120":
            return Self(widthMM: 61, heightMM: 60, apertureX: 2.5, apertureY: 2,
                        apertureWidth: 56, apertureHeight: 56, perforation: nil,
                        pitchMM: 0, rows: 0, horizontalTransport: false, isInstant: false, isSheet: false)
        case "4x5":
            return Self(widthMM: 100, heightMM: 125, apertureX: 2.5, apertureY: 2.5,
                        apertureWidth: 95, apertureHeight: 120, perforation: nil,
                        pitchMM: 0, rows: 0, horizontalTransport: false, isInstant: false, isSheet: true)
        case "instaxmini":
            return Self(widthMM: 54, heightMM: 86, apertureX: 4, apertureY: 20,
                        apertureWidth: 46, apertureHeight: 62, perforation: nil,
                        pitchMM: 0, rows: 0, horizontalTransport: false, isInstant: true, isSheet: false)
        case "instaxsquare":
            return Self(widthMM: 72, heightMM: 86, apertureX: 5, apertureY: 19,
                        apertureWidth: 62, apertureHeight: 62, perforation: nil,
                        pitchMM: 0, rows: 0, horizontalTransport: false, isInstant: true, isSheet: false)
        case "instaxwide":
            return Self(widthMM: 108, heightMM: 86, apertureX: 4.5, apertureY: 19.5,
                        apertureWidth: 99, apertureHeight: 62, perforation: nil,
                        pitchMM: 0, rows: 0, horizontalTransport: false, isInstant: true, isSheet: false)
        default: return nil
        }
    }
}

public struct PrintFrameConfiguration: Codable, Equatable, Sendable {
    /// Border Width: the frame's margin as a multiple of its preset, from none to three times.
    public static let marginRange: ClosedRange<Double> = 0...3
    /// The neutral scan bed seen through the physical holes of a film border.
    public static let scanBedRGB = SIMD3<Float>(repeating: 0.96)
    /// An inverted scan's rebate: the scanner sets its black point just past the clear base,
    /// removing the colour mask, so the rebate reads near-black with the open holes clipped a
    /// shade deeper. A representative presentation level, not a measured scanner.
    public static let invertedBaseRGB = SIMD3<Float>(repeating: 0.004)

    public let frame: PrintFrame
    /// The margin multiple for a frame with an adjustable margin; nil for every other frame.
    public let margin: Double?
    /// Where a film border's aperture sits on a photograph of another shape, which it crops to
    /// fill: -1 to 1 across and down, from the left or top edge to the right or bottom, 0 centred
    /// (`PrintFramePlacement.photoCrop`). Nil for every other frame.
    public let framing: SIMD2<Double>?
    public let geometry: FilmBorderGeometry?
    /// The camera a 135 film border's picture was exposed in; nil where the gauge offers no choice.
    public let camera: FilmBorderCamera?
    public let sheet: PaperSheetGeometry?
    public let slideMount: SlideMountGeometry?
    public let canvas: SocialCanvasGeometry?
    public let sheetNotches: SheetFilmNotchCode?
    /// The stock's edge printing for this gauge, renumbered as `frameNumber`; nil without any,
    /// or when the edge printing is hidden.
    public let edgePrinting: FilmEdgePrinting?
    /// The frame number the edge printing and DX barcode show; nil without edge printing.
    public let frameNumber: Int?
    /// The edge exposure viewed through the same stock and lamp as its rebate.
    public let edgeRGB: SIMD3<Float>
    /// Display-linear P3, converted into the photograph's output profile by the compositor.
    public let baseRGB: SIMD3<Float>
    /// The clear film rebate printed to the paper's maximum density through a filed carrier.
    public let rebateRGB: SIMD3<Float>
    /// What shows through perforations and notches: the scan bed, or black in an inverted scan,
    /// where the open holes are brighter than the film base the scanner set as its black point.
    public let cutoutRGB: SIMD3<Float>
    public let detail: String
    /// Positive polyester media are smooth; the existing RC papers use a lustre approximation.
    public let hasLustre: Bool

    /// The edge printing laid on a developed strip, whose rebate already carries the stock's
    /// fog, grain and the frame's glow. A negative's letters are denser than the rebate around
    /// them and pass this share of its light, so its grain runs through them. Nil where the
    /// letters are lighter than the rebate — a transparency or an inverted scan — and are laid
    /// over it in `edgeRGB`.
    public let edgeTransmission: SIMD3<Float>?
    /// The DX barcode's bars on this cut of film, in `edgePrinting` millimetres; empty without one.
    public let edgeBars: [FilmEdgePrinting.Bar]
    /// The barcode's exposure seen like `edgeRGB`.
    public let barcodeRGB: SIMD3<Float>
    /// The barcode laid on a developed negative strip, like `edgeTransmission`.
    public let barcodeTransmission: SIMD3<Float>?

    public init(frame: PrintFrame, formatID: String?, stockID: String,
                paper: PrintPaper, viewingKelvin: Float? = nil,
                negativeViewing: NegativeViewing = .lightBox, margin: Double = 1,
                frameNumber: Int = FilmEdgePrinting.recordedFrameNumber, showsEdgePrinting: Bool = true,
                framing: SIMD2<Double> = .zero, frames: Int = 1, camera: FilmBorderCamera = .panoramic) {
        self.init(frame: frame, formatID: formatID, definition: FilmStock.presetDefinitions[stockID],
                  paper: paper, viewingKelvin: viewingKelvin, negativeViewing: negativeViewing,
                  margin: margin, frameNumber: frameNumber, showsEdgePrinting: showsEdgePrinting,
                  framing: framing, frames: frames, camera: camera)
    }

    /// Explicit stock metadata also supports isolated runtimes without a process-wide pack registry.
    public init(frame: PrintFrame, formatID: String?, definition: FilmStockDefinition?,
                paper: PrintPaper, viewingKelvin: Float? = nil,
                negativeViewing: NegativeViewing = .lightBox, margin: Double = 1,
                frameNumber: Int = FilmEdgePrinting.recordedFrameNumber, showsEdgePrinting: Bool = true,
                framing: SIMD2<Double> = .zero, frames: Int = 1, camera: FilmBorderCamera = .panoramic) {
        let nativeID = definition?.nativeFormatID
        let motionStock = nativeID.flatMap(FilmFormat.preset(id:))?.isMotionPicture == true
        let nativeInstant = nativeID.flatMap { FilmBorderGeometry.preset($0) }?.isInstant == true
        let film = formatID.flatMap { FilmBorderGeometry.preset($0, motionPictureStock: motionStock) }
        let name = formatID.flatMap(FilmFormat.preset(id:))?.name ?? "Film"
        let reflective = paper.acceptsViewingIlluminant && !paper.isProjected
        hasLustre = !paper.isPositivePaper
        let filmAvailable = definition != nil && film != nil
            && ((film?.isInstant != true && !nativeInstant) || nativeID == formatID)
        let mount = formatID.flatMap(SlideMountGeometry.preset)
        // The inverted scan turns a negative's strip positive; a transparency's is already.
        let positiveAvailable = filmAvailable && definition?.stock.isReversal == false
            && film?.isInstant == false
        let slideAvailable = definition?.stock.isReversal == true && mount != nil && !nativeInstant
        // A filed carrier prints the clear rebate of a negative; on positive paper the dark
        // rebate of a transparency prints the same as the unexposed margin and shows nothing.
        let carrierAvailable = reflective && !paper.isPositivePaper
            && definition?.stock.isReversal == false && !nativeInstant
        // The unexposed edge is developed from the stock over the gauge's gate-to-edge margins;
        // integral instant film has a mask there instead of emulsion.
        let edgeAvailable = definition != nil && !nativeInstant
            && formatID.flatMap { UnexposedEdge.Geometry.preset($0, motionPictureStock: motionStock) } != nil
        let available: Bool
        switch frame {
        case .none, .mount, .darkMount, .socialSquare, .socialPortrait, .socialStory: available = true
        case .emulsion: available = edgeAvailable
        case .film: available = filmAvailable
        case .filmPositive: available = positiveAvailable
        case .slideMount: available = slideAvailable
        case .carrier: available = carrierAvailable
        case .paper, .paper5x7, .paper8x10, .paper5x5: available = reflective
        }
        self.frame = available ? frame : .none
        let scale = margin.isFinite
            ? min(max(margin, Self.marginRange.lowerBound), Self.marginRange.upperBound) : 1
        self.margin = self.frame.hasAdjustableMargin ? scale : nil
        // Frames: a panorama exposed across several frames' length of the roll; Camera: 135 in a
        // medium-format camera, exposed to its edges.
        let run = self.frame.showsFilmStrip ? film?.spanning(frames) : nil
        geometry = camera == .mediumFormatBack ? run?.exposingEdges() : run
        self.camera = run?.opensToEdges == true ? camera : nil
        self.framing = geometry == nil ? nil : SIMD2(
            framing.x.isFinite ? min(max(framing.x, -1), 1) : 0,
            framing.y.isFinite ? min(max(framing.y, -1), 1) : 0)
        sheet = PaperSheetGeometry.preset(for: self.frame).map {
            PaperSheetGeometry(widthMM: $0.widthMM, heightMM: $0.heightMM,
                               marginMM: $0.marginMM * scale, rebateMM: $0.rebateMM)
        }
        slideMount = self.frame == .slideMount ? mount : nil
        canvas = SocialCanvasGeometry.preset(for: self.frame).map {
            SocialCanvasGeometry(aspectWidth: $0.aspectWidth, aspectHeight: $0.aspectHeight,
                                 margin: $0.margin * scale)
        }
        sheetNotches = geometry?.isSheet == true ? definition?.sheetNotches : nil
        let number = min(max(frameNumber, FilmEdgePrinting.frameNumberRange.lowerBound),
                         FilmEdgePrinting.frameNumberRange.upperBound)
        let printing = self.frame.showsFilmStrip
            ? definition?.edgePrinting?.first { $0.formatID == formatID } : nil
        let roll = (frames: geometry?.frames ?? 1, pitch: geometry?.frameLengthMM ?? 0)
        edgePrinting = showsEdgePrinting
            ? printing?.run(from: number, frames: roll.frames, pitchMM: roll.pitch) : nil
        self.frameNumber = edgePrinting == nil ? nil : number
        cutoutRGB = self.frame == .filmPositive ? .zero : Self.scanBedRGB
        // The DX barcode is printed on negatives only; a transparency's edge carries none.
        edgeBars = definition?.stock.isReversal == false && edgePrinting != nil
            ? printing?.barcodeRun(from: number, frames: roll.frames, pitchMM: roll.pitch) ?? [] : []
        // The edge printer's two exposures: lettering at a representative 72% of the density
        // span, and the barcode at the standard's 0.5 above base. Not measured edge-printer
        // calibrations. Reversal clears exposed letters; negatives darken.
        func edgeDensities(_ stock: FilmStock) -> (letters: [Float], bars: [Float]) {
            (stock.curves.map { $0.dMin + ($0.dMax - $0.dMin) * (stock.isReversal ? 0.08 : 0.72) },
             stock.curves.map { $0.dMin + 0.5 })
        }
        if self.frame == .filmPositive, let stock = definition?.stock {
            // The scan is normalised on the clear base under the reference lamp, then inverted:
            // the dense marks come out light, by how little light they passed.
            let clear = SpectralRuntime.transmissionRGB(density: stock.curves.map(\.dMin),
                                                        stock: stock, illuminant: nil)
            let one = SIMD3<Float>(repeating: 1)
            func inverted(_ density: [Float]) -> SIMD3<Float> {
                let exposed = SpectralRuntime.transmissionRGB(density: density, stock: stock, illuminant: nil)
                let passed = exposed / SIMD3(max(clear.x, 1e-6), max(clear.y, 1e-6), max(clear.z, 1e-6))
                return Self.invertedBaseRGB + (one - Self.invertedBaseRGB)
                    * (one - passed.clamped(lowerBound: .zero, upperBound: one))
            }
            let densities = edgeDensities(stock)
            edgeRGB = inverted(densities.letters)
            barcodeRGB = inverted(densities.bars)
        } else if self.frame == .film, let stock = definition?.stock, film?.isInstant == false {
            let light = viewingKelvin.map(SpectralRuntime.printLightSPD)
            let basis = stock.isReversal ? SpectralRuntime.neutralDensityBasis(for: stock) : nil
            let clear = SpectralRuntime.transmissionRGB(
                density: basis?(stock.curves.map(\.dMin)) ?? stock.curves.map(\.dMin),
                stock: stock, illuminant: light)
            func viewed(_ density: [Float]) -> SIMD3<Float> {
                let exposed = SpectralRuntime.transmissionRGB(density: basis?(density) ?? density,
                                                              stock: stock, illuminant: light)
                if stock.isReversal { return exposed }
                if paper.isNegative && negativeViewing == .scanner {
                    return exposed / SIMD3(max(clear.x, 1e-6), max(clear.y, 1e-6), max(clear.z, 1e-6))
                }
                return exposed * (SpectralRuntime.lightBoxBaseLevel / max(clear.x, clear.y, clear.z, 1e-6))
            }
            let densities = edgeDensities(stock)
            edgeRGB = viewed(densities.letters)
            barcodeRGB = edgeBars.isEmpty ? .zero : viewed(densities.bars)
        } else {
            edgeRGB = .zero
            barcodeRGB = .zero
        }
        rebateRGB = self.frame == .carrier ? paper.frameDenseRGB(viewingKelvin: viewingKelvin) ?? .zero : .zero
        switch frame {
        case .none:
            baseRGB = .zero
            detail = frame.detail
        case .film:
            if film?.isInstant == true {
                // The integral film's attached white mask is separate from its image dyes.
                baseRGB = SIMD3(repeating: 0.94)
            } else if let stock = definition?.stock {
                let light = viewingKelvin.map(SpectralRuntime.printLightSPD)
                if stock.isReversal {
                    // An unexposed reversal rebate develops to maximum density, including each
                    // stock's residual dye colour. It is not a universal display black.
                    baseRGB = SpectralRuntime.transmissionRGB(
                        density: stock.curves.map(\.dMax), stock: stock, illuminant: light)
                } else {
                    // The physical negative's clear rebate carries its own base-plus-fog and
                    // orange mask. A common light-box gain keeps that tint; per-channel gains
                    // would erase it. Scanner-normalized negative viewing remains explicit.
                    let base = SpectralRuntime.transmissionRGB(
                        density: stock.curves.map(\.dMin), stock: stock, illuminant: light)
                    baseRGB = paper.isNegative && negativeViewing == .scanner
                        ? SIMD3(repeating: 1)
                        : base * (SpectralRuntime.lightBoxBaseLevel / max(base.x, base.y, base.z, 1e-6))
                }
            } else {
                baseRGB = .zero
            }
            if !filmAvailable {
                detail = "Choose a film and a matching physical film format."
            } else if film?.isSheet == true && sheetNotches == nil {
                detail = "\(name) sheet film · notch code not documented for this stock"
            } else {
                detail = "\(name) · \(definition?.name ?? "Film")"
            }
        case .filmPositive:
            baseRGB = positiveAvailable ? Self.invertedBaseRGB : .zero
            if !positiveAvailable {
                detail = "Choose a negative film and a matching roll or sheet film format."
            } else if film?.isSheet == true && sheetNotches == nil {
                detail = "\(name) sheet film · inverted scan · notch code not documented for this stock"
            } else {
                detail = "\(name) · \(definition?.name ?? "Film") · inverted scan"
            }
        case .slideMount:
            // The rebate seen through the aperture around a nonmatching crop is the
            // transparency's own maximum density, as in Film Border. The card is drawn on top.
            if slideAvailable, let stock = definition?.stock {
                baseRGB = SpectralRuntime.transmissionRGB(
                    density: stock.curves.map(\.dMax), stock: stock,
                    illuminant: viewingKelvin.map(SpectralRuntime.printLightSPD))
                let size = mount.map { $0.mountMM == 50.8 ? "2 × 2 in" : "70 × 70 mm" } ?? ""
                detail = "\(size) mount · \(definition?.name ?? "Film")"
            } else {
                baseRGB = .zero
                detail = "Choose a slide film in 35mm or 120."
            }
        case .paper, .paper5x7, .paper8x10, .paper5x5:
            baseRGB = paper.frameBaseRGB(viewingKelvin: viewingKelvin) ?? .zero
            let size = sheet.map { "\(Int(($0.widthMM / 25.4).rounded())) × \(Int(($0.heightMM / 25.4).rounded())) in" } ?? ""
            detail = reflective ? "\(paper.name) · \(hasLustre ? "lustre" : "high gloss") · \(size)"
                : "Choose a reflection paper such as Ektacolor Edge or Ilfochrome."
        case .carrier:
            baseRGB = paper.frameBaseRGB(viewingKelvin: viewingKelvin) ?? .zero
            detail = carrierAvailable
                ? "\(paper.name) · filed carrier · 8 × 10 in"
                : "Choose a negative film and a reflection paper such as Ektacolor Edge."
        case .emulsion:
            // The band itself is developed by the host; the margin around it is the paper's white.
            baseRGB = paper.frameBaseRGB(viewingKelvin: viewingKelvin) ?? SIMD3(repeating: 0.91)
            detail = edgeAvailable
                ? "\(name) · \(definition?.name ?? "Film") · unexposed edge"
                : "Choose a film and a roll or sheet film format."
        case .mount:
            // Reflection outputs retain their modelled paper white. Other outputs use a
            // neutral presentation mount; this style does not identify a manufactured stock.
            baseRGB = paper.frameBaseRGB(viewingKelvin: viewingKelvin) ?? SIMD3(repeating: 0.91)
            detail = frame.detail
        case .darkMount:
            // A neutral presentation board, not the paper's own maximum density.
            baseRGB = SIMD3(repeating: 0.02)
            detail = frame.detail
        case .socialSquare, .socialPortrait, .socialStory:
            // Display white, so the post's margin merges with the feed it is shown in.
            baseRGB = SIMD3(repeating: 1)
            detail = frame.detail
        }
        if self.frame == .film, edgePrinting != nil, baseRGB.min() > 0,
           edgeRGB.x <= baseRGB.x, edgeRGB.y <= baseRGB.y, edgeRGB.z <= baseRGB.z {
            edgeTransmission = edgeRGB / baseRGB
            barcodeTransmission = edgeBars.isEmpty ? nil : barcodeRGB / baseRGB
        } else {
            edgeTransmission = nil
            barcodeTransmission = nil
        }
    }
}

extension PrintPaper {
    /// An unexposed border is clear paper for negative paper and maximum density for positive
    /// paper, under the viewing lamp. Only a reflection print has a border.
    func frameBaseRGB(viewingKelvin: Float?) -> SIMD3<Float>? {
        frameRGB(viewingKelvin: viewingKelvin, dense: false)
    }

    /// A fully exposed negative-paper border, as the clear rebate of a negative prints through
    /// a filed carrier. Positive paper has no dark rebate exposure to print.
    func frameDenseRGB(viewingKelvin: Float?) -> SIMD3<Float>? {
        isPositivePaper ? nil : frameRGB(viewingKelvin: viewingKelvin, dense: true)
    }

    /// Density above the paper base, which is what the receiver reads and what the photograph's
    /// own tones are made of: clear paper is the print's white in the frame and in the picture.
    private func frameRGB(viewingKelvin: Float?, dense: Bool) -> SIMD3<Float>? {
        guard let stock = printStock, stock.process.isReflection else { return nil }
        // Positive paper is at its maximum density where it was never exposed.
        let full = dense || stock.process.isPositiveWorking
        let records = stock.curves.map { full ? $0.dMax - $0.dMin : 0 }
        let density = SIMD3(records[0], records[1], records[2])
        let receiver = SpectralRuntime.PrintReceiver(dyes: analyticalDyes,
            viewingLight: viewingKelvin.map(SpectralRuntime.printLightSPD) ?? Illuminant.d50,
            unmix: PrintDyeUnmix(dyes: analyticalDyes))
        return receiver.rgb(density: density)
    }
}
