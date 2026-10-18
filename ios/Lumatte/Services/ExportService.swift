import Foundation
import UIKit
import Photos

enum ExportServiceError: LocalizedError {
    case photoAccess
    case encoding
    var errorDescription: String? {
        switch self {
        case .photoAccess: return "没有照片图库写入权限"
        case .encoding: return "导出编码失败"
        }
    }
}

enum ExportService {
    static func data(from image: UIImage, options: ExportOptions) throws -> Data {
        switch options.format {
        case .jpeg:
            guard let data = image.jpegData(compressionQuality: options.quality) else {
                throw ExportServiceError.encoding
            }
            return data
        case .png:
            guard let data = image.pngData() else { throw ExportServiceError.encoding }
            return data
        }
    }

    static func saveToPhotos(_ image: UIImage) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            throw ExportServiceError.photoAccess
        }
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetChangeRequest.creationRequestForAsset(from: image)
        }
    }

    static func temporaryFile(image: UIImage, options: ExportOptions) throws -> URL {
        let data = try data(from: image, options: options)
        let name = "Lumatte-\(Int(Date().timeIntervalSince1970)).\(options.format == .jpeg ? "jpg" : "png")"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try data.write(to: url, options: .atomic)
        return url
    }
}
