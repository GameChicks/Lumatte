import Foundation
import UIKit
import SwiftUI

@MainActor
final class AppModel: ObservableObject {
    @Published var photos: [PhotoAsset] = []
    @Published var selectedID: UUID?
    @Published var parameters = FilmParameters()
    @Published var frame = FrameOptions()
    @Published var exportOptions = ExportOptions()
    @Published var lut: CubeLUT?
    @Published var lutStrength: Float = 1
    @Published var displayImage: UIImage?
    @Published var filmPreview: UIImage?
    @Published var isRendering = false
    @Published var showFrameInPreview = false
    @Published var errorMessage: String?
    @Published var toast: String?
    @Published var isExporting = false
    @Published var exportProgress: Double = 0
    @Published var lastExportURL: URL?
    @Published var lastExportURLs: [URL] = []
    @Published var showShareSheet = false
    @Published var photoPickerPresented = false
    @Published var fileImporterPresented = false
    @Published var showingHome = false

    private var previewTask: Task<Void, Never>?

    var selectedPhoto: PhotoAsset? {
        guard let selectedID else { return photos.first }
        return photos.first { $0.id == selectedID }
    }

    var selectedStock: FilmStock? {
        FilmCatalog.shared.stock(id: parameters.stockID) ?? FilmCatalog.shared.defaultStock()
    }

    var rendererAvailable: Bool { FilmRenderer.shared != nil }

    init() {
        if let stock = FilmCatalog.shared.defaultStock() {
            parameters.stockID = stock.id
            parameters.halationEnabled = FilmParameters.defaultHalation(for: stock.id)
        }
    }

    func addPhoto(data: Data, fileName: String) {
        do {
            let asset = try PhotoLoader.load(data: data, fileName: fileName)
            photos.append(asset)
            selectedID = asset.id
            filmPreview = nil
            displayImage = asset.image
            schedulePreview(immediately: true)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func importURLs(_ urls: [URL]) {
        for url in urls {
            do {
                let asset = try PhotoLoader.load(url: url)
                photos.append(asset)
                selectedID = asset.id
                displayImage = asset.image
            } catch {
                errorMessage = error.localizedDescription
            }
        }
        schedulePreview(immediately: true)
    }

    func selectPhoto(_ photo: PhotoAsset) {
        selectedID = photo.id
        filmPreview = nil
        displayImage = photo.image
        schedulePreview(immediately: true)
    }

    func removePhoto(_ photo: PhotoAsset) {
        photos.removeAll { $0.id == photo.id }
        if selectedID == photo.id {
            selectedID = photos.last?.id
            filmPreview = nil
            displayImage = photos.last?.image
            schedulePreview(immediately: true)
        }
    }

    func chooseStock(_ stock: FilmStock) {
        parameters.stockID = stock.id
        parameters.halationEnabled = FilmParameters.defaultHalation(for: stock.id)
        schedulePreview()
    }

    func importLUT(url: URL) {
        do {
            let parsed = try CubeLUT.parse(url: url)
            lut = parsed
            lutStrength = 1
            toast = "已加载 LUT · \(parsed.name)"
            schedulePreview(immediately: true)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func clearLUT() {
        lut = nil
        lutStrength = 1
        schedulePreview(immediately: true)
    }

    func resetToOriginal() {
        parameters.strength = 0
        schedulePreview(immediately: true)
    }

    func schedulePreview(immediately: Bool = false) {
        previewTask?.cancel()
        guard let photo = selectedPhoto, let stock = selectedStock else {
            displayImage = nil
            return
        }
        let parameters = self.parameters
        let frame = self.frame
        let showFrame = self.showFrameInPreview
        let lut = self.lut
        let lutStrength = self.lutStrength
        isRendering = true
        previewTask = Task { [weak self] in
            if !immediately {
                try? await Task.sleep(nanoseconds: 90_000_000)
            }
            if Task.isCancelled { return }
            let seed = Self.seed(for: photo)
            let rendered: UIImage? = await Task.detached(priority: .userInitiated) {
                guard let renderer = FilmRenderer.shared else { return nil }
                do {
                    let film = try renderer.render(image: photo.image, stock: stock,
                                                   parameters: parameters, maxDimension: 1200, seed: seed,
                                                   lut: lut, lutStrength: lutStrength)
                    if showFrame {
                        return FrameRenderer.render(image: film, options: frame,
                                                    stockName: stock.name, metadata: photo.metadata)
                    }
                    return film
                } catch {
                    return nil
                }
            }.value
            guard !Task.isCancelled, let self else { return }
            if let rendered {
                self.filmPreview = rendered
                self.displayImage = rendered
            }
            self.isRendering = false
        }
    }

    func exportCurrent(saveToPhotos: Bool) {
        guard let photo = selectedPhoto, let stock = selectedStock else { return }
        let parameters = self.parameters
        let frame = self.frame
        let exportOptions = self.exportOptions
        let lut = self.lut
        let lutStrength = self.lutStrength
        isExporting = true
        exportProgress = 0
        Task {
            defer { isExporting = false }
            let output: UIImage? = await Task.detached(priority: .userInitiated) {
                guard let renderer = FilmRenderer.shared else { return nil }
                do {
                    guard let fullImage = photo.fullImage() else { return nil }
                    let maxEdge = CGFloat(max(fullImage.size.width, fullImage.size.height)) *
                        CGFloat(exportOptions.dimensionPercent) / 100
                    let film = try renderer.render(image: fullImage, stock: stock,
                                                   parameters: parameters, maxDimension: maxEdge,
                                                   seed: Self.seed(for: photo),
                                                   lut: lut, lutStrength: lutStrength)
                    return FrameRenderer.render(image: film, options: frame,
                                                stockName: stock.name, metadata: photo.metadata)
                } catch {
                    return nil
                }
            }.value
            guard let output else {
                errorMessage = "导出渲染失败"
                return
            }
            exportProgress = 0.7
            do {
                if saveToPhotos {
                    try await ExportService.saveToPhotos(output)
                }
                let url = try ExportService.temporaryFile(image: output, options: exportOptions)
                lastExportURL = url
                lastExportURLs = [url]
                exportProgress = 1
                toast = saveToPhotos ? "已保存到照片图库" : "已生成导出文件"
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    func exportAll(saveToPhotos: Bool) {
        guard !photos.isEmpty, let stock = selectedStock else { return }
        let parameters = self.parameters
        let frame = self.frame
        let exportOptions = self.exportOptions
        let lut = self.lut
        let lutStrength = self.lutStrength
        let assets = photos
        isExporting = true
        exportProgress = 0
        Task {
            defer { isExporting = false }
            var urls: [URL] = []
            var failures = 0
            for (index, photo) in assets.enumerated() {
                let output: UIImage? = await Task.detached(priority: .userInitiated) {
                    guard let renderer = FilmRenderer.shared else { return nil }
                    do {
                        guard let fullImage = photo.fullImage() else { return nil }
                        let maxEdge = CGFloat(max(fullImage.size.width, fullImage.size.height)) *
                            CGFloat(exportOptions.dimensionPercent) / 100
                        let film = try renderer.render(image: fullImage, stock: stock,
                                                       parameters: parameters, maxDimension: maxEdge,
                                                       seed: Self.seed(for: photo),
                                                       lut: lut, lutStrength: lutStrength)
                        return FrameRenderer.render(image: film, options: frame,
                                                    stockName: stock.name, metadata: photo.metadata)
                    } catch {
                        return nil
                    }
                }.value
                if let output {
                    do {
                        if saveToPhotos { try await ExportService.saveToPhotos(output) }
                        urls.append(try ExportService.temporaryFile(image: output, options: exportOptions))
                    } catch {
                        failures += 1
                    }
                } else {
                    failures += 1
                }
                exportProgress = Double(index + 1) / Double(assets.count)
            }
            lastExportURLs = urls
            lastExportURL = urls.last
            if failures == 0 {
                toast = saveToPhotos ? "已导出 \(assets.count) 张到照片图库" : "已生成 \(assets.count) 张导出文件"
            } else {
                errorMessage = "\(failures) 张照片导出失败"
            }
        }
    }

    nonisolated static func seed(for photo: PhotoAsset) -> UInt32 {
        var value: UInt32 = 0x811c9dc5
        for byte in photo.fileName.utf8 {
            value = (value ^ UInt32(byte)) &* 16777619
        }
        return value
    }
}

extension AppModel {
    func returnHome() {
        showingHome = true
    }

    func continueEditing() {
        showingHome = false
    }
}
