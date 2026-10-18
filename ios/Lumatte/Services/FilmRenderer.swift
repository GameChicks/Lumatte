import Foundation
import Metal
import UIKit
import simd

enum FilmRenderError: LocalizedError {
    case metalUnavailable
    case pipeline(String)
    case texture(String)
    case encoding(String)
    var errorDescription: String? {
        switch self {
        case .metalUnavailable: return "此设备不支持 Metal 渲染"
        case .pipeline(let name): return "无法建立渲染管线：\(name)"
        case .texture(let reason): return "无法建立纹理：\(reason)"
        case .encoding(let reason): return "无法编码输出：\(reason)"
        }
    }
}

private struct FilmUniforms {
    var responseLow = SIMD4<Float>(0, 0, 0, 0)
    var responseHigh = SIMD4<Float>(1, 1, 1, 0)
    var developmentLow = SIMD4<Float>(0, 0, 0, 0)
    var developmentHigh = SIMD4<Float>(1, 1, 1, 0)
    var scanLow = SIMD4<Float>(0, 0, 0, 0)
    var scanHigh = SIMD4<Float>(1, 1, 1, 0)
    var particleScale = SIMD4<Float>(1, 1, 1, 0)
    var floorDensity = SIMD4<Float>(0.02, 0.02, 0.02, 0.1)
    var uniformity = SIMD4<Float>(0.9, 0.9, 0.9, 0)
    var densityMax = SIMD4<Float>(1, 1, 1, 0)
    var halationMix = SIMD4<Float>(0, 0, 0, 1)
    var returnRow0 = SIMD4<Float>(0, 0, 0, 0)
    var returnRow1 = SIMD4<Float>(0, 0, 0, 0)
    var returnRow2 = SIMD4<Float>(0, 0, 0, 0)
    var lensParams = SIMD4<Float>(0, 1, 36, 0)
    var imageSize = SIMD4<Float>(1, 1, 1, 1)
    var halationEnabled: UInt32 = 0
    var grainEnabled: UInt32 = 0
    var spectralReturn: UInt32 = 0
    var hasLens: UInt32 = 0
    var seed: UInt32 = 0x9e3779b9
    var pad0: UInt32 = 0
    var pad1: UInt32 = 0
    var pad2: UInt32 = 0
}

private struct BlurUniforms {
    var sigma = SIMD4<Float>(0, 0, 0, 0)
    var texel = SIMD4<Float>(0, 0, 0, 0)
}

private struct CombineUniforms {
    var params = SIMD4<Float>(0, 0, 0, 0)
    var m0 = SIMD4<Float>(0, 0, 0, 0)
    var m1 = SIMD4<Float>(0, 0, 0, 0)
    var m2 = SIMD4<Float>(0, 0, 0, 0)
}

private struct DownsampleUniforms {
    var texel = SIMD4<Float>(0, 0, 0, 0)
}

private struct LUTUniforms {
    var domainMin = SIMD4<Float>(0, 0, 0, 0)
    var domainMax = SIMD4<Float>(1, 1, 1, 0)
    var params = SIMD4<Float>(1, 0, 0, 0)
}

final class FilmRenderer {
    static let shared = FilmRenderer()

    let device: MTLDevice
    private let queue = DispatchQueue(label: "app.lumatte.film-renderer", qos: .userInitiated)
    private let commandQueue: MTLCommandQueue
    private let library: MTLLibrary
    private let models: FilmModelStore
    private var pipelines: [String: MTLRenderPipelineState] = [:]
    private let blackTexture: MTLTexture

    private init?() {
        guard let device = MTLCreateSystemDefaultDevice(),
              let queue = device.makeCommandQueue(),
              let shaderURL = Bundle.main.url(forResource: "Shaders", withExtension: "metal.txt"),
              let source = try? String(contentsOf: shaderURL, encoding: .utf8),
              let library = try? device.makeLibrary(source: source, options: nil) else {
            return nil
        }
        self.device = device
        self.commandQueue = queue
        self.library = library
        self.models = FilmModelStore(device: device)
        var pixel = SIMD4<Float>(0, 0, 0, 1)
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba16Float,
                                                                  width: 1, height: 1, mipmapped: false)
        descriptor.usage = .shaderRead
        descriptor.storageMode = .shared
        guard let black = device.makeTexture(descriptor: descriptor) else { return nil }
        black.replace(region: MTLRegionMake2D(0, 0, 1, 1), mipmapLevel: 0,
                      withBytes: &pixel, bytesPerRow: MemoryLayout<SIMD4<Float>>.stride)
        self.blackTexture = black
    }

    func render(image: UIImage, stock: FilmStock, parameters: FilmParameters,
                maxDimension: CGFloat? = nil, seed: UInt32 = 0x9e3779b9,
                lut: CubeLUT? = nil, lutStrength: Float = 1) throws -> UIImage {
        try queue.sync {
            try renderLocked(image: image, stock: stock, parameters: parameters,
                             maxDimension: maxDimension, seed: seed,
                             lut: lut, lutStrength: lutStrength)
        }
    }

    private func renderLocked(image: UIImage, stock: FilmStock, parameters: FilmParameters,
                              maxDimension: CGFloat?, seed: UInt32,
                              lut: CubeLUT?, lutStrength: Float) throws -> UIImage {
        guard let sourceCG = image.cgImage else { throw FilmRenderError.encoding("源图像不可用") }
        let sourceWidth = sourceCG.width
        let sourceHeight = sourceCG.height
        var width = sourceWidth
        var height = sourceHeight
        if let maxDimension, maxDimension > 0 {
            let longEdge = max(width, height)
            if CGFloat(longEdge) > maxDimension {
                let scale = maxDimension / CGFloat(longEdge)
                width = max(1, Int((CGFloat(width) * scale).rounded()))
                height = max(1, Int((CGFloat(height) * scale).rounded()))
            }
        }
        let model = try models.model(for: stock)
        let effects = model.effects
        let input = try makeInputTexture(image: image, width: width, height: height)
        let output = try makeTexture(width: width, height: height, format: .rgba8Unorm,
                                     usage: [.renderTarget, .shaderRead], storage: .shared)
        guard let commandBuffer = commandQueue.makeCommandBuffer() else {
            throw FilmRenderError.encoding("无法建立命令缓冲")
        }

        let softness = parameters.diffusion.rawValue * 0.45
        let analysisLongEdge = effects.cineStill ? 1536 : 512
        let analysisScale = min(1.0, CGFloat(analysisLongEdge) / CGFloat(max(width, height)))
        let analysisWidth = max(1, Int((CGFloat(width) * analysisScale).rounded()))
        let analysisHeight = max(1, Int((CGFloat(height) * analysisScale).rounded()))

        let linear = try makeTexture(width: analysisWidth, height: analysisHeight, format: .rgba16Float,
                                     usage: [.renderTarget, .shaderRead], storage: .private)
        var downsample = DownsampleUniforms()
        downsample.texel = SIMD4<Float>(1 / Float(analysisWidth), 1 / Float(analysisHeight), 0, 0)
        encodeAny(name: "linear_downsample", target: linear, textures: [input],
                  bytes: withUnsafeBytes(of: downsample) { Data($0) }, commandBuffer: commandBuffer)

        var lensTexture = blackTexture
        if softness > 0 {
            lensTexture = blur(input: linear, sigma: SIMD3<Float>(6, 6, 6), commandBuffer: commandBuffer)
        }

        var uniforms = FilmUniforms()
        uniforms.responseLow = SIMD4<Float>(model.response.low, 0)
        uniforms.responseHigh = SIMD4<Float>(model.response.high, 0)
        uniforms.developmentLow = SIMD4<Float>(model.development.low, 0)
        uniforms.developmentHigh = SIMD4<Float>(model.development.high, 0)
        uniforms.scanLow = SIMD4<Float>(model.scan.low, 0)
        uniforms.scanHigh = SIMD4<Float>(model.scan.high, 0)
        uniforms.particleScale = SIMD4<Float>(effects.particleScale, 0)
        uniforms.floorDensity = SIMD4<Float>(effects.densityFloor, effects.particleArea)
        uniforms.uniformity = SIMD4<Float>(effects.uniformity, 0)
        uniforms.densityMax = SIMD4<Float>(effects.densityMaximum, 0)
        uniforms.halationMix = SIMD4<Float>(0, 0, 0, min(max(parameters.strength, 0), 1))
        uniforms.lensParams = SIMD4<Float>(softness, pow(2, parameters.exposureEV),
                                           effects.formatMM, effects.grainSpacingUM)
        uniforms.imageSize = SIMD4<Float>(Float(width), Float(height), Float(sourceWidth), Float(sourceHeight))
        uniforms.halationEnabled = parameters.halationEnabled ? 1 : 0
        uniforms.grainEnabled = effects.grainActive ? 1 : 0
        uniforms.spectralReturn = effects.cineStill ? 1 : 0
        uniforms.hasLens = softness > 0 ? 1 : 0
        uniforms.seed = seed
        if effects.cineStill, effects.kernelMatrix.count == 3 {
            uniforms.returnRow0 = SIMD4<Float>(effects.kernelMatrix[0], 0)
            uniforms.returnRow1 = SIMD4<Float>(effects.kernelMatrix[1], 0)
            uniforms.returnRow2 = SIMD4<Float>(effects.kernelMatrix[2], 0)
        }

        let raw = try makeTexture(width: analysisWidth, height: analysisHeight, format: .rgba16Float,
                                  usage: [.renderTarget, .shaderRead], storage: .private)
        encode("film_response", target: raw, textures: [linear, lensTexture, model.response.texture],
               uniforms: uniforms, commandBuffer: commandBuffer)

        let halationStrength = max(effects.halationStrength.x, max(effects.halationStrength.y, effects.halationStrength.z))
        let halationActive = parameters.halationEnabled && (effects.cineStill || halationStrength > 0)
        var haloTexture = blackTexture
        if halationActive {
            var scattered = raw
            if effects.scatterAmount > 0 && !effects.cineStill {
                let sigma = scatterSigma(effects: effects, analysisWidth: analysisWidth, analysisHeight: analysisHeight)
                let blurred = blur(input: raw, sigma: sigma, commandBuffer: commandBuffer)
                let combined = try makeTexture(width: analysisWidth, height: analysisHeight, format: .rgba16Float,
                                               usage: [.renderTarget, .shaderRead], storage: .private)
                var combine = CombineUniforms()
                combine.params = SIMD4<Float>(min(max(effects.scatterAmount, 0), 1), 0, 0, 0)
                encode("combine_scatter", target: combined, textures: [raw, blurred],
                       uniforms: combine, commandBuffer: commandBuffer)
                scattered = combined
            }
            let sigma = halationSigma(effects: effects, analysisWidth: analysisWidth, analysisHeight: analysisHeight)
            let blurred = blur(input: scattered, sigma: sigma, commandBuffer: commandBuffer)
            let halo = try makeTexture(width: analysisWidth, height: analysisHeight, format: .rgba16Float,
                                       usage: [.renderTarget, .shaderRead], storage: .private)
            var combine = CombineUniforms()
            if effects.cineStill {
                combine.params = SIMD4<Float>(1, 0, 0, 0)
                if effects.kernelMatrix.count == 3 {
                    combine.m0 = SIMD4<Float>(effects.kernelMatrix[0], 0)
                    combine.m1 = SIMD4<Float>(effects.kernelMatrix[1], 0)
                    combine.m2 = SIMD4<Float>(effects.kernelMatrix[2], 0)
                }
            } else {
                let mix = effects.halationStrength / (SIMD3<Float>(1, 1, 1) + effects.halationStrength)
                combine.params = SIMD4<Float>(0, mix.x, mix.y, mix.z)
                uniforms.halationMix.x = mix.x
                uniforms.halationMix.y = mix.y
                uniforms.halationMix.z = mix.z
            }
            encode("combine_halo", target: halo, textures: [scattered, blurred, raw],
                   uniforms: combine, commandBuffer: commandBuffer)
            haloTexture = halo
        }

        encode("film_render", target: output,
               textures: [input, haloTexture, lensTexture, model.response.texture,
                          model.development.texture, model.scan.texture],
               uniforms: uniforms, commandBuffer: commandBuffer)

        commandBuffer.commit()
        commandBuffer.waitUntilCompleted()
        if let error = commandBuffer.error { throw FilmRenderError.encoding(error.localizedDescription) }
        let rendered = try makeImage(from: output)
        if let lut {
            return try applyLUTLocked(image: rendered, lut: lut, strength: lutStrength)
        }
        return rendered
    }

    private func applyLUTLocked(image: UIImage, lut: CubeLUT, strength: Float) throws -> UIImage {
        guard strength > 0.001, let cgImage = image.cgImage else { return image }
        let width = cgImage.width
        let height = cgImage.height
        let input = try makeInputTexture(image: image, width: width, height: height)
        let output = try makeTexture(width: width, height: height, format: .rgba8Unorm,
                                     usage: [.renderTarget, .shaderRead], storage: .shared)
        let lutTexture = try makeLUTTexture(lut)
        guard let commandBuffer = commandQueue.makeCommandBuffer() else {
            throw FilmRenderError.encoding("无法建立 LUT 命令缓冲")
        }
        var uniforms = LUTUniforms()
        uniforms.domainMin = SIMD4<Float>(lut.domainMin, 0)
        uniforms.domainMax = SIMD4<Float>(lut.domainMax, 0)
        uniforms.params = SIMD4<Float>(min(max(strength, 0), 1), 0, 0, 0)
        encode("lut_render", target: output, textures: [input, lutTexture],
               uniforms: uniforms, commandBuffer: commandBuffer)
        commandBuffer.commit()
        commandBuffer.waitUntilCompleted()
        if let error = commandBuffer.error { throw FilmRenderError.encoding(error.localizedDescription) }
        return try makeImage(from: output)
    }

    private func makeLUTTexture(_ lut: CubeLUT) throws -> MTLTexture {
        let descriptor = MTLTextureDescriptor()
        descriptor.textureType = .type3D
        descriptor.pixelFormat = .rgba32Float
        descriptor.width = lut.size
        descriptor.height = lut.size
        descriptor.depth = lut.size
        descriptor.usage = .shaderRead
        descriptor.storageMode = .shared
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            throw FilmRenderError.texture("LUT 3D 纹理")
        }
        lut.values.withUnsafeBytes { raw in
            texture.replace(region: MTLRegionMake3D(0, 0, 0, lut.size, lut.size, lut.size),
                            mipmapLevel: 0, slice: 0, withBytes: raw.baseAddress!,
                            bytesPerRow: lut.size * MemoryLayout<SIMD4<Float>>.stride,
                            bytesPerImage: lut.size * lut.size * MemoryLayout<SIMD4<Float>>.stride)
        }
        return texture
    }

    private func scatterSigma(effects: FilmEffects, analysisWidth: Int, analysisHeight: Int) -> SIMD3<Float> {
        let maxDim = Float(max(analysisWidth, analysisHeight))
        let pixelUM = effects.formatMM * 1000 / maxDim
        let sigmaUM = effects.scatterCoreUM * (1 - effects.scatterTailWeight) +
            effects.scatterTailUM * effects.scatterTailWeight
        return max(sigmaUM / pixelUM, SIMD3<Float>(0.05, 0.05, 0.05))
    }

    private func halationSigma(effects: FilmEffects, analysisWidth: Int, analysisHeight: Int) -> SIMD3<Float> {
        let maxDim = Float(max(analysisWidth, analysisHeight))
        if effects.cineStill {
            var sigmaMM = SIMD3<Float>(0, 0, 0)
            var weightSum = SIMD3<Float>(0, 0, 0)
            for channel in 0..<3 {
                let weights = effects.kernelWeights[channel]
                let total = weights.x + weights.y + weights.z
                if total > 1e-6 {
                    sigmaMM[channel] = (effects.kernelSigmaMM * weights).sum() / total
                    weightSum[channel] = total
                }
            }
            return max(sigmaMM * (maxDim / max(effects.formatMM, 1)), SIMD3<Float>(0.35, 0.35, 0.35))
        }
        let pixelUM = effects.formatMM * 1000 / maxDim
        return max(effects.halationSigmaUM / pixelUM, SIMD3<Float>(0.35, 0.35, 0.35))
    }

    private func blur(input: MTLTexture, sigma: SIMD3<Float>, commandBuffer: MTLCommandBuffer) -> MTLTexture {
        let width = input.width
        let height = input.height
        do {
            let horizontal = try makeTexture(width: width, height: height, format: .rgba16Float,
                                             usage: [.renderTarget, .shaderRead], storage: .private)
            let vertical = try makeTexture(width: width, height: height, format: .rgba16Float,
                                           usage: [.renderTarget, .shaderRead], storage: .private)
            var uniforms = BlurUniforms()
            uniforms.sigma = SIMD4<Float>(sigma, 0)
            uniforms.texel = SIMD4<Float>(1 / Float(width), 1 / Float(height), 0, 0)
            encode("blur_horizontal", target: horizontal, textures: [input], uniforms: uniforms,
                   commandBuffer: commandBuffer)
            encode("blur_vertical", target: vertical, textures: [horizontal], uniforms: uniforms,
                   commandBuffer: commandBuffer)
            return vertical
        } catch {
            return input
        }
    }

    private func encode(_ name: String, target: MTLTexture, textures: [MTLTexture],
                        uniforms: FilmUniforms? = nil, commandBuffer: MTLCommandBuffer) {
        encodeAny(name: name, target: target, textures: textures,
                  bytes: uniforms.map { withUnsafeBytes(of: $0) { Data($0) } }, commandBuffer: commandBuffer)
    }

    private func encode(_ name: String, target: MTLTexture, textures: [MTLTexture],
                        uniforms: BlurUniforms, commandBuffer: MTLCommandBuffer) {
        encodeAny(name: name, target: target, textures: textures,
                  bytes: withUnsafeBytes(of: uniforms) { Data($0) }, commandBuffer: commandBuffer)
    }

    private func encode(_ name: String, target: MTLTexture, textures: [MTLTexture],
                        uniforms: CombineUniforms, commandBuffer: MTLCommandBuffer) {
        encodeAny(name: name, target: target, textures: textures,
                  bytes: withUnsafeBytes(of: uniforms) { Data($0) }, commandBuffer: commandBuffer)
    }

    private func encode(_ name: String, target: MTLTexture, textures: [MTLTexture],
                        uniforms: LUTUniforms, commandBuffer: MTLCommandBuffer) {
        encodeAny(name: name, target: target, textures: textures,
                  bytes: withUnsafeBytes(of: uniforms) { Data($0) }, commandBuffer: commandBuffer)
    }

    private func encodeAny(name: String, target: MTLTexture, textures: [MTLTexture],
                           bytes: Data?, commandBuffer: MTLCommandBuffer) {
        let key = "\(name)-\(target.pixelFormat.rawValue)"
        do {
            let pipeline: MTLRenderPipelineState
            if let existing = pipelines[key] {
                pipeline = existing
            } else {
                let descriptor = MTLRenderPipelineDescriptor()
                descriptor.vertexFunction = library.makeFunction(name: "fullscreen_vertex")
                descriptor.fragmentFunction = library.makeFunction(name: name)
                descriptor.colorAttachments[0].pixelFormat = target.pixelFormat
                pipeline = try device.makeRenderPipelineState(descriptor: descriptor)
                pipelines[key] = pipeline
            }
            let pass = MTLRenderPassDescriptor()
            pass.colorAttachments[0].texture = target
            pass.colorAttachments[0].loadAction = .dontCare
            pass.colorAttachments[0].storeAction = .store
            guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else { return }
            encoder.setViewport(MTLViewport(originX: 0, originY: 0,
                                            width: Double(target.width), height: Double(target.height),
                                            znear: 0, zfar: 1))
            encoder.setRenderPipelineState(pipeline)
            for (index, texture) in textures.enumerated() {
                encoder.setFragmentTexture(texture, index: index)
            }
            if let bytes, !bytes.isEmpty {
                bytes.withUnsafeBytes { raw in
                    encoder.setFragmentBytes(raw.baseAddress!, length: bytes.count, index: 0)
                }
            }
            encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
            encoder.endEncoding()
        } catch {
            assertionFailure("Metal pass \(name) failed: \(error)")
        }
    }

    private func makeTexture(width: Int, height: Int, format: MTLPixelFormat,
                             usage: MTLTextureUsage, storage: MTLStorageMode) throws -> MTLTexture {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: format, width: width,
                                                                  height: height, mipmapped: false)
        descriptor.usage = usage
        descriptor.storageMode = storage
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            throw FilmRenderError.texture("\(width)x\(height)")
        }
        return texture
    }

    private func makeInputTexture(image: UIImage, width: Int, height: Int) throws -> MTLTexture {
        let size = CGSize(width: width, height: height)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        format.preferredRange = .standard
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        let normalized = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        guard let cgImage = normalized.cgImage else {
            throw FilmRenderError.encoding("无法读取源图像")
        }
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGImageAlphaInfo.noneSkipLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
        try bytes.withUnsafeMutableBytes { raw in
            guard let context = CGContext(data: raw.baseAddress, width: width, height: height,
                                          bitsPerComponent: 8, bytesPerRow: width * 4,
                                          space: colorSpace, bitmapInfo: bitmapInfo) else {
                throw FilmRenderError.encoding("无法建立位图上下文")
            }
            context.interpolationQuality = .high
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba8Unorm,
                                                                  width: width, height: height, mipmapped: false)
        descriptor.usage = [.shaderRead]
        descriptor.storageMode = .shared
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            throw FilmRenderError.texture("输入纹理")
        }
        try bytes.withUnsafeBytes { raw in
            texture.replace(region: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0,
                            withBytes: raw.baseAddress!, bytesPerRow: width * 4)
        }
        return texture
    }

    private func makeImage(from texture: MTLTexture) throws -> UIImage {
        let width = texture.width
        let height = texture.height
        let bytesPerRow = width * 4
        var bytes = [UInt8](repeating: 0, count: bytesPerRow * height)
        bytes.withUnsafeMutableBytes { raw in
            texture.getBytes(raw.baseAddress!, bytesPerRow: bytesPerRow,
                             from: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0)
        }
        let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        guard let provider = CGDataProvider(data: Data(bytes) as CFData),
              let image = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
                                  bytesPerRow: bytesPerRow, space: colorSpace,
                                  bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue |
                                                           CGBitmapInfo.byteOrder32Big.rawValue),
                                  provider: provider, decode: nil, shouldInterpolate: true,
                                  intent: .defaultIntent) else {
            throw FilmRenderError.encoding("无法生成输出图像")
        }
        return UIImage(cgImage: image)
    }
}

private extension SIMD3 where Scalar == Float {
    func sum() -> Float { x + y + z }
}
