import Foundation
import UIKit
import ImageIO
import UniformTypeIdentifiers

enum PhotoLoaderError: LocalizedError {
    case unreadable
    case unsupported
    var errorDescription: String? {
        switch self {
        case .unreadable: return "无法读取这张照片"
        case .unsupported: return "暂不支持此文件格式"
        }
    }
}

enum PhotoLoader {
    private static var importDirectory: URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("LumatteImports", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    static func load(data: Data, fileName: String) throws -> PhotoAsset {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else {
            throw PhotoLoaderError.unreadable
        }
        let type = (CGImageSourceGetType(source) as String?)?.lowercased() ?? ""
        let raw = type.contains("raw")
        let extensionName: String
        if let uti = CGImageSourceGetType(source) as String?,
           let ext = UTType(uti)?.preferredFilenameExtension {
            extensionName = ext
        } else {
            extensionName = (fileName as NSString).pathExtension.isEmpty ? "jpg" : (fileName as NSString).pathExtension
        }
        let originalURL = importDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(extensionName)
        try data.write(to: originalURL, options: .atomic)

        guard let cgImage = previewImage(source: source, isRAW: raw, maxPixelSize: 1600) else {
            throw raw ? PhotoLoaderError.unsupported : PhotoLoaderError.unreadable
        }
        let image = UIImage(cgImage: cgImage, scale: 1, orientation: .up)
        let metadata = readMetadata(source: source, width: cgImage.width, height: cgImage.height)
        return PhotoAsset(image: image, originalURL: originalURL, isRAW: raw,
                          fileName: fileName, metadata: metadata)
    }

    static func load(url: URL) throws -> PhotoAsset {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        let data = try Data(contentsOf: url)
        return try load(data: data, fileName: url.lastPathComponent)
    }

    static func fullImage(url: URL, isRAW: Bool) -> UIImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        guard let cgImage = previewImage(source: source, isRAW: isRAW, maxPixelSize: 5120) else { return nil }
        return UIImage(cgImage: cgImage, scale: 1, orientation: .up)
    }

    private static func previewImage(source: CGImageSource, isRAW: Bool, maxPixelSize: Int) -> CGImage? {
        var options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize
        ]
        // RAW is only used for its embedded JPEG preview, matching the Android contract.
        options[kCGImageSourceCreateThumbnailFromImageAlways] = !isRAW
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    private static func readMetadata(source: CGImageSource, width: Int, height: Int) -> PhotoMetadata {
        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] ?? [:]
        let exif = properties[kCGImagePropertyExifDictionary] as? [CFString: Any] ?? [:]
        let tiff = properties[kCGImagePropertyTIFFDictionary] as? [CFString: Any] ?? [:]
        let make = tiff[kCGImagePropertyTIFFMake] as? String
        let model = tiff[kCGImagePropertyTIFFModel] as? String
        let camera = [make, model].compactMap { $0 }.joined(separator: " ").trimmingCharacters(in: .whitespaces)
        let lens = exif[kCGImagePropertyExifLensModel] as? String
        let focal = (exif[kCGImagePropertyExifFocalLength] as? NSNumber).map { "\(Int($0.doubleValue.rounded()))mm" }
        let fNumber = (exif[kCGImagePropertyExifFNumber] as? NSNumber).map {
            String(format: "f/%.1f", $0.doubleValue)
        }
        let exposure = (exif[kCGImagePropertyExifExposureTime] as? NSNumber).map { value -> String in
            let seconds = value.doubleValue
            if seconds > 0 && seconds < 1 { return "1/\(Int((1 / seconds).rounded()))s" }
            return String(format: "%.1fs", seconds)
        }
        let iso = (exif[kCGImagePropertyExifISOSpeedRatings] as? [NSNumber])?.first.map { "ISO \($0.intValue)" }
        var captured: Date?
        if let value = exif[kCGImagePropertyExifDateTimeOriginal] as? String {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.dateFormat = "yyyy:MM:dd HH:mm:ss"
            captured = formatter.date(from: value)
        }
        return PhotoMetadata(camera: camera.isEmpty ? nil : camera, lens: lens,
                             focalLength: focal, aperture: fNumber, shutter: exposure, iso: iso,
                             capturedAt: captured, pixelWidth: width, pixelHeight: height)
    }
}
