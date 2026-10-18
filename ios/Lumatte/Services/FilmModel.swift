import Foundation
import Metal
import CryptoKit
import simd

struct FilmStage {
    let size: Int
    let low: SIMD3<Float>
    let high: SIMD3<Float>
    let texture: MTLTexture
}

struct FilmEffects {
    var grainActive: Bool = false
    var particleArea: Float = 0.1
    var particleScale = SIMD3<Float>(1, 1, 1)
    var densityFloor = SIMD3<Float>(0.02, 0.02, 0.02)
    var uniformity = SIMD3<Float>(0.9, 0.9, 0.9)
    var densityMaximum = SIMD3<Float>(1, 1, 1)
    var halationStrength = SIMD3<Float>(0, 0, 0)
    var halationSigmaUM = SIMD3<Float>(0, 0, 0)
    var halationBounces: Int = 3
    var halationDecay: Float = 0.5
    var grainSpacingUM: Float = 0
    var scatterAmount: Float = 1
    var scatterCoreUM = SIMD3<Float>(2.2, 2.0, 1.6)
    var scatterTailUM = SIMD3<Float>(9.3, 9.7, 9.1)
    var scatterTailWeight = SIMD3<Float>(0.78, 0.65, 0.67)
    var formatMM: Float = 36
    var cineStill: Bool = false
    var kernelSigmaMM = SIMD3<Float>(0.188, 0.283, 0.425)
    var kernelWeights: [SIMD3<Float>] = []
    var kernelMatrix: [SIMD3<Float>] = []
}

final class FilmModel {
    let stock: FilmStock
    let modelVersion: String
    let response: FilmStage
    let development: FilmStage
    let scan: FilmStage
    let effects: FilmEffects

    init(stock: FilmStock, modelVersion: String, response: FilmStage,
         development: FilmStage, scan: FilmStage, effects: FilmEffects) {
        self.stock = stock
        self.modelVersion = modelVersion
        self.response = response
        self.development = development
        self.scan = scan
        self.effects = effects
    }
}

final class FilmModelStore {
    private let device: MTLDevice
    private let cache = NSCache<NSString, FilmModel>()
    private let queue = DispatchQueue(label: "app.lumatte.film-models")
    private var models: [String: FilmModel] = [:]

    init(device: MTLDevice) {
        self.device = device
        cache.countLimit = 3
    }

    func model(for stock: FilmStock) throws -> FilmModel {
        try queue.sync {
            if let cached = cache.object(forKey: stock.id as NSString) { return cached }
            let model = try load(stock: stock)
            cache.setObject(model, forKey: stock.id as NSString)
            models[stock.id] = model
            return model
        }
    }

    private struct Manifest: Decodable {
        struct Source: Decodable {
            let repository: String?
            let commit: String?
            let license: String?
        }
        struct Stage: Decodable {
            let file: String
            let size: Int
            let bytes: Int
            let sha256: String
            let minimum: [Double]
            let maximum: [Double]
        }
        struct Tables: Decodable {
            let response: Stage
            let development: Stage
            let scan: Stage
        }
        struct Grain: Decodable {
            let active: Bool?
            let particle_area_um2: Double?
            let particle_scale: [Double]?
            let density_min: [Double]?
            let uniformity: [Double]?
            let spacing_um: Double?
        }
        struct Kernel: Decodable {
            let algorithm: String?
            let sigma_mm: [Double]
            let weights: [[Double]]
            let mix_matrix: [[Double]]
        }
        struct Halation: Decodable {
            let halation_strength: [Double]?
            let halation_first_sigma_um: [Double]?
            let halation_n_bounces: Int?
            let halation_bounce_decay: Double?
            let scatter_amount: Double?
            let scatter_core_um: [Double]?
            let scatter_tail_um: [Double]?
            let scatter_tail_weight: [Double]?
            let fotufilm_kernel: Kernel?
        }
        struct Camera: Decodable { let film_format_mm: Double? }
        struct Snapshot: Decodable { let camera: Camera? }
        let schema: Int
        let modelVersion: String
        let stockId: String
        let printId: String
        let positive: Bool
        let storage: String
        let tables: Tables
        let grainReference: Grain?
        let halationReference: Halation?
        let settingsSnapshot: Snapshot?
    }

    private func load(stock: FilmStock) throws -> FilmModel {
        guard let url = FilmCatalog.shared.modelURL(for: stock) else {
            throw FilmCatalogError.resourceMissing(stock.id)
        }
        guard let data = try? Data(contentsOf: url) else {
            throw FilmCatalogError.resourceMissing("\(stock.group)/\(stock.id)/model.json")
        }
        let manifest: Manifest
        do { manifest = try JSONDecoder().decode(Manifest.self, from: data) }
        catch { throw FilmCatalogError.invalidManifest(error.localizedDescription) }
        guard manifest.schema == 1,
              manifest.storage == "float32_le_rgb_x_fastest",
              manifest.stockId == stock.id else {
            throw FilmCatalogError.invalidManifest("模型与所选胶卷不匹配")
        }
        let root = url.deletingLastPathComponent()
        let response = try readStage(manifest.tables.response, root: root, name: "response")
        let development = try readStage(manifest.tables.development, root: root, name: "development")
        let scan = try readStage(manifest.tables.scan, root: root, name: "scan")
        let effects = try readEffects(manifest: manifest, stock: stock)
        return FilmModel(stock: stock, modelVersion: manifest.modelVersion,
                         response: response, development: development, scan: scan, effects: effects)
    }

    private func readStage(_ stage: Manifest.Stage, root: URL, name: String) throws -> FilmStage {
        guard stage.size >= 2, stage.size <= 65,
              stage.file == "\(name).f32",
              stage.minimum.count == 3, stage.maximum.count == 3 else {
            throw FilmCatalogError.invalidTable("阶段 \(name) 描述无效")
        }
        let count = stage.size * stage.size * stage.size * 3
        guard stage.bytes == count * 4 else {
            throw FilmCatalogError.invalidTable("阶段 \(name) 大小无效")
        }
        let data = try Data(contentsOf: root.appendingPathComponent(stage.file))
        guard data.count == stage.bytes else {
            throw FilmCatalogError.invalidTable("阶段 \(name) 文件不完整")
        }
        let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        guard digest == stage.sha256 else {
            throw FilmCatalogError.hashMismatch("\(name).f32")
        }
        var values = [Float](repeating: 0, count: count)
        _ = values.withUnsafeMutableBytes { destination in
            data.copyBytes(to: destination)
        }
        let low = SIMD3<Float>(Float(stage.minimum[0]), Float(stage.minimum[1]), Float(stage.minimum[2]))
        let high = SIMD3<Float>(Float(stage.maximum[0]), Float(stage.maximum[1]), Float(stage.maximum[2]))
        guard high.x > low.x, high.y > low.y, high.z > low.z else {
            throw FilmCatalogError.invalidTable("阶段 \(name) 采样轴无效")
        }
        let descriptor = MTLTextureDescriptor()
        descriptor.textureType = .type3D
        descriptor.pixelFormat = .rgba32Float
        descriptor.width = stage.size
        descriptor.height = stage.size
        descriptor.depth = stage.size
        descriptor.usage = .shaderRead
        descriptor.storageMode = .shared
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            throw FilmCatalogError.invalidTable("无法建立阶段 \(name) 纹理")
        }
        var rgba = [SIMD4<Float>](repeating: SIMD4<Float>(0, 0, 0, 1), count: stage.size * stage.size * stage.size)
        for index in 0..<(stage.size * stage.size * stage.size) {
            rgba[index] = SIMD4<Float>(values[index * 3], values[index * 3 + 1], values[index * 3 + 2], 1)
        }
        let region = MTLRegionMake3D(0, 0, 0, stage.size, stage.size, stage.size)
        rgba.withUnsafeBytes { bytes in
            texture.replace(region: region, mipmapLevel: 0, slice: 0, withBytes: bytes.baseAddress!,
                            bytesPerRow: stage.size * MemoryLayout<SIMD4<Float>>.stride,
                            bytesPerImage: stage.size * stage.size * MemoryLayout<SIMD4<Float>>.stride)
        }
        return FilmStage(size: stage.size, low: low, high: high, texture: texture)
    }

    private func readEffects(manifest: Manifest, stock: FilmStock) throws -> FilmEffects {
        var effects = FilmEffects()
        let cineStill = stock.id == "cinestill_800t" || stock.id == "cinestill_400d"
        effects.cineStill = cineStill
        if let camera = manifest.settingsSnapshot?.camera, let format = camera.film_format_mm {
            effects.formatMM = Float(format)
        }
        let scanMaximum = manifest.tables.scan.maximum
        if scanMaximum.count == 3 {
            effects.densityMaximum = SIMD3<Float>(Float(scanMaximum[0]) - 0.2,
                                                  Float(scanMaximum[1]) - 0.2,
                                                  Float(scanMaximum[2]) - 0.2)
        }
        if let grain = manifest.grainReference {
            effects.grainActive = grain.active ?? false
            if let value = grain.particle_area_um2 { effects.particleArea = Float(value) }
            if let value = grain.particle_scale, value.count == 3 {
                effects.particleScale = SIMD3<Float>(Float(value[0]), Float(value[1]), Float(value[2]))
            }
            if let value = grain.density_min, value.count == 3 {
                effects.densityFloor = SIMD3<Float>(Float(value[0]), Float(value[1]), Float(value[2]))
            }
            if let value = grain.uniformity, value.count == 3 {
                effects.uniformity = SIMD3<Float>(Float(value[0]), Float(value[1]), Float(value[2]))
            }
            if let value = grain.spacing_um { effects.grainSpacingUM = Float(value) }
        }
        if let halation = manifest.halationReference {
            if let value = halation.halation_strength, value.count == 3 {
                effects.halationStrength = SIMD3<Float>(Float(value[0]), Float(value[1]), Float(value[2]))
            }
            if let value = halation.halation_first_sigma_um, value.count == 3 {
                effects.halationSigmaUM = SIMD3<Float>(Float(value[0]), Float(value[1]), Float(value[2]))
            }
            if let value = halation.halation_n_bounces { effects.halationBounces = value }
            if let value = halation.halation_bounce_decay { effects.halationDecay = Float(value) }
            if let value = halation.scatter_amount { effects.scatterAmount = Float(value) }
            if let value = halation.scatter_core_um, value.count == 3 {
                effects.scatterCoreUM = SIMD3<Float>(Float(value[0]), Float(value[1]), Float(value[2]))
            }
            if let value = halation.scatter_tail_um, value.count == 3 {
                effects.scatterTailUM = SIMD3<Float>(Float(value[0]), Float(value[1]), Float(value[2]))
            }
            if let value = halation.scatter_tail_weight, value.count == 3 {
                effects.scatterTailWeight = SIMD3<Float>(Float(value[0]), Float(value[1]), Float(value[2]))
            }
            if let kernel = halation.fotufilm_kernel {
                if kernel.sigma_mm.count == 3 {
                    effects.kernelSigmaMM = SIMD3<Float>(Float(kernel.sigma_mm[0]),
                                                         Float(kernel.sigma_mm[1]),
                                                         Float(kernel.sigma_mm[2]))
                }
                effects.kernelWeights = kernel.weights.compactMap { row in
                    guard row.count == 3 else { return nil }
                    return SIMD3<Float>(Float(row[0]), Float(row[1]), Float(row[2]))
                }
                effects.kernelMatrix = kernel.mix_matrix.compactMap { row in
                    guard row.count == 3 else { return nil }
                    return SIMD3<Float>(Float(row[0]), Float(row[1]), Float(row[2]))
                }
                if effects.kernelWeights.count != 3 || effects.kernelMatrix.count != 3 {
                    throw FilmCatalogError.invalidManifest("CineStill 光晕矩阵不完整")
                }
            }
        }
        return effects
    }
}
