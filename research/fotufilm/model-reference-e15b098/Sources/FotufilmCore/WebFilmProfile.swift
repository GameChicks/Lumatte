import Foundation

/// The browser can prepare a profile at its actual render size instead of interpolating a
/// prebuilt size ladder. The configuration and spectral tables are the native invocation's.
public enum WebFilmProfile {
    public enum Failure: Error, CustomStringConvertible {
        case invalidDimensions, donorTransport, transportOptics
        public var description: String {
            switch self {
            case .invalidDimensions: return "Invalid profile dimensions."
            case .donorTransport:
                return "Layered Transport in the browser does not yet carry a donor stock's fourth record."
            case .transportOptics:
                return "Layered Transport in the browser needs lens flare and diffusion filters off."
            }
        }
    }

    public static func prepare(stock: FilmStock, options: FotufilmEngine.Options,
                               width: Int, height: Int) throws -> Data {
        guard width > 0, height > 0, width <= 120_000, height <= 120_000,
              Int64(width) * Int64(height) <= 120_000_000 else {
            throw Failure.invalidDimensions
        }
        var options = options
        // The rendering worker supplies a tone base measured from the photograph.
        options.localTone = false
        let invocation = try FilmEngineInvocation(validating: stock, options: options,
                                                   width: width, height: height)
        let layered = options.transportConstruction(for: stock) != nil
        let spectral = invocation.spectral
        let lutCount = spectral.exposure.values.count
        var data = Data("FSWP".utf8)
        data.appendUInt32(layered ? 4 : 2)
        data.appendInt32(Int32(width)); data.appendInt32(Int32(height))
        data.appendInt32(invocation.featureMask); data.appendUInt32(invocation.seed)
        data.appendInt32(Int32(invocation.configuration.count))
        data.appendInt32(Int32(spectral.exposure.dimension)); data.appendInt32(Int32(lutCount))
        // 2: the paper table delivers Cineon code values, which the page shows and writes as
        // they are rather than as light.
        data.appendInt32(spectral.paperOutput == nil ? 0
                         : options.paper(for: stock).deliversCodeValues ? 2 : 1)
        data.appendFloats(invocation.configuration)
        data.appendFloats(spectral.exposure.values)
        data.appendFloats(spectral.filmOutput.values)
        data.appendFloats(spectral.paperOutput?.values ?? [Float](repeating: 0, count: lutCount))
        // A single exact rung retains the existing browser tiling/apron contract.
        data.appendInt32(1); data.appendInt32(Int32(min(width, height)))
        data.appendInt32(invocation.featureMask); data.appendUInt32(invocation.seed)
        data.appendInt32(Int32(invocation.spatialSupport)); data.appendInt32(0)
        if layered {
            data.append(try transportSection(stock: stock, options: options, width: width, height: height,
                                             sizes: [(width, height)]))
        }
        // The film grain model's tiles close the pack, their count last; see `--dump-wasm-pack`.
        if let tiles = FilmGrain.registeredTiles(configuration: invocation.configuration) {
            data.appendFloats(tiles)
            data.appendInt32(Int32(tiles.count))
        }
        return data
    }

    /// Layered Transport's section of a version 4 pack: the plan at `width` × `height` (its head
    /// and tail configurations, then each component's exposure table and stencils), and for each
    /// of `sizes`, the slots and stencils that size changes. Spectral tables do not depend on
    /// image size, so every size reuses the component tables.
    public static func transportSection(stock: FilmStock, options: FotufilmEngine.Options,
                                        width: Int, height: Int,
                                        sizes: [(width: Int, height: Int)]) throws -> Data {
        guard stock.donorLayers.isEmpty else { throw Failure.donorTransport }
        var options = options
        options.localTone = false
        let plan = try LayeredTransportRenderer.renderPlan(stock: stock, options: options,
                                                          width: width, height: height)
        guard plan.head.featureMask == FilmEngineFeature.lightOut else { throw Failure.transportOptics }
        var data = Data()
        // A component's stencil table without its empty levels: the count of levels it uses, then
        // each one's level, radius and (2r + 1)² weights from the centred slot.
        func appendStencils(_ component: TransportRenderPlan.Component) {
            let levels = TransportRadialKernel.transportLevels
            let limit = TransportRadialKernel.transportStencilRadius
            let side = 2 * limit + 1, table = component.stencils
            let used = (0..<levels).filter { table[$0] > 0 }
            data.appendInt32(Int32(used.count))
            for level in used {
                let radius = Int(table[level]), base = levels + level * side * side
                data.appendInt32(Int32(level))
                data.appendInt32(Int32(radius))
                for dy in -radius...radius {
                    let row = base + (dy + limit) * side + limit
                    data.appendFloats(Array(table[(row - radius)...(row + radius)]))
                }
            }
        }
        func appendDelta(_ values: [Float], base: [Float]) {
            let changed = values.indices.filter { values[$0].bitPattern != base[$0].bitPattern }
            data.appendInt32(Int32(changed.count))
            for index in changed { data.appendInt32(Int32(index)); data.appendFloats([values[index]]) }
        }
        data.appendInt32(plan.head.featureMask)
        data.appendInt32(LayeredTransportRenderer.continuationClears)
        data.appendFloats(plan.head.configuration)
        data.appendFloats(plan.tail.configuration)
        data.appendInt32(Int32(plan.components.count))
        for component in plan.components {
            data.appendFloats(component.exposure)
            appendStencils(component)
        }
        data.appendInt32(Int32(sizes.count))
        for size in sizes {
            let rung = size.width == width && size.height == height ? plan
                : try LayeredTransportRenderer.renderPlan(stock: stock, options: options,
                                                          width: size.width, height: size.height)
            precondition(rung.components.count == plan.components.count)
            data.appendInt32(Int32(min(size.width, size.height)))
            appendDelta(rung.head.configuration, base: plan.head.configuration)
            appendDelta(rung.tail.configuration, base: plan.tail.configuration)
            for component in rung.components { appendStencils(component) }
        }
        return data
    }
}

/// Little-endian appenders for the browser's packs. Swift's `Data` has no numeric append and the
/// browser reads these buffers as typed arrays, so the byte order is written out rather than
/// inherited from the host.
public extension Data {
    mutating func appendUInt32(_ value: UInt32) {
        Swift.withUnsafeBytes(of: value.littleEndian) { append(contentsOf: $0) }
    }

    mutating func appendInt32(_ value: Int32) { appendUInt32(UInt32(bitPattern: value)) }

    mutating func appendFloats(_ values: [Float]) {
        #if _endian(little)
        values.withUnsafeBufferPointer { append(UnsafeRawBufferPointer($0).bindMemory(to: UInt8.self)) }
        #else
        for value in values { appendUInt32(value.bitPattern) }
        #endif
    }
}
