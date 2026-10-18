import Foundation
import UIKit

enum FilmCategory: String, Codable, CaseIterable, Identifiable {
    case cinema = "CINEMA"
    case negative = "NEGATIVE"
    case reversal = "REVERSAL"
    case monochrome = "MONOCHROME"
    var id: String { rawValue }
    var title: String {
        switch self {
        case .cinema: return "电影卷"
        case .negative: return "彩色负片"
        case .reversal: return "反转片"
        case .monochrome: return "黑白"
        }
    }
    var order: Int {
        switch self {
        case .cinema: return 0
        case .negative: return 1
        case .reversal: return 2
        case .monochrome: return 3
        }
    }
}

struct FilmStock: Identifiable, Hashable {
    let id: String
    let name: String
    let category: FilmCategory
    let printID: String
    let positive: Bool
    let group: String
    let detail: String
}

enum DiffusionGrade: Float, CaseIterable, Identifiable {
    case off = 0
    case eighth = 0.125
    case quarter = 0.25
    case half = 0.5
    var id: Float { rawValue }
    var title: String {
        switch self {
        case .off: return "关闭"
        case .eighth: return "1/8"
        case .quarter: return "1/4"
        case .half: return "1/2"
        }
    }
}

struct FilmParameters: Equatable {
    var stockID: String = "kodak_gold_200"
    var exposureEV: Float = 0
    var diffusion: DiffusionGrade = .off
    var halationEnabled: Bool = false
    var strength: Float = 1

    static func defaultHalation(for stockID: String) -> Bool {
        stockID == "cinestill_800t" || stockID == "cinestill_400d"
    }
}

enum FramePreset: String, CaseIterable, Identifiable {
    case none, paper, black, instant, museum, film, ticket
    var id: String { rawValue }
    var title: String {
        switch self {
        case .none: return "无边框"
        case .paper: return "白色相纸"
        case .black: return "黑色画廊"
        case .instant: return "拍立得"
        case .museum: return "典藏纸框"
        case .film: return "胶片暗盒"
        case .ticket: return "记忆票根"
        }
    }
}

enum FrameRatio: String, CaseIterable, Identifiable {
    case original, square, threeTwo, fourThree, fiveFour
    var id: String { rawValue }
    var title: String {
        switch self {
        case .original: return "原比例"
        case .square: return "1:1"
        case .threeTwo: return "3:2"
        case .fourThree: return "4:3"
        case .fiveFour: return "5:4"
        }
    }
    var value: CGFloat? {
        switch self {
        case .original: return nil
        case .square: return 1
        case .threeTwo: return 1.5
        case .fourThree: return 4.0 / 3.0
        case .fiveFour: return 1.25
        }
    }
}

struct FrameOptions: Equatable {
    var preset: FramePreset = .paper
    var borderFraction: CGFloat = 0.045
    var ratio: FrameRatio = .original
    var signature: String = ""
    var title: String = ""
    var showMetadata: Bool = true
    var portrait: Bool? = nil
    var showFilmLabel: Bool = true
}

enum ExportFormat: String, CaseIterable, Identifiable {
    case jpeg, png
    var id: String { rawValue }
    var title: String { self == .jpeg ? "JPEG" : "PNG" }
}

struct ExportOptions: Equatable {
    var format: ExportFormat = .jpeg
    var quality: CGFloat = 0.95
    var dimensionPercent: Int = 100
}

struct PhotoMetadata: Equatable {
    var camera: String?
    var lens: String?
    var focalLength: String?
    var aperture: String?
    var shutter: String?
    var iso: String?
    var capturedAt: Date?
    var pixelWidth: Int
    var pixelHeight: Int

    var summaryLine: String {
        [camera, lens, focalLength, aperture, shutter, iso]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
            .joined(separator: "  ·  ")
    }
    var dateLine: String {
        guard let capturedAt else { return "" }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "yyyy.MM.dd"
        return formatter.string(from: capturedAt)
    }
}

struct PhotoAsset: Identifiable {
    let id = UUID()
    /// Downscaled preview kept in memory for the editor and film strip.
    let image: UIImage
    /// Original bytes on disk. Export reloads this file at full resolution.
    let originalURL: URL
    let isRAW: Bool
    let fileName: String
    let metadata: PhotoMetadata

    var aspect: CGFloat { image.size.width / max(image.size.height, 1) }

    func fullImage() -> UIImage? {
        PhotoLoader.fullImage(url: originalURL, isRAW: isRAW)
    }
}

struct BatchJob: Identifiable {
    let id = UUID()
    let asset: PhotoAsset
    var result: UIImage?
    var error: String?
}
