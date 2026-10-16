import Foundation

/// The transparent support a frame is exposed on.
public struct FilmBase: Sendable, Equatable {
    /// Support thickness in millimeters.
    public var thicknessMM: Float
    /// Refractive index of the support: 1.48 for cellulose triacetate, 1.64
    /// for the PET/polyester Kodak sells as ESTAR.
    public var refractiveIndex: Float
    /// Optional refractive indices at the red, green, and blue record centers.
    /// `nil` means a non-dispersive support at `refractiveIndex`.
    public var recordRefractiveIndices: [Float]?

    public init(thicknessMM: Float, refractiveIndex: Float,
                recordRefractiveIndices: [Float]? = nil) {
        precondition(recordRefractiveIndices == nil || recordRefractiveIndices?.count == 3)
        self.thicknessMM = thicknessMM
        self.refractiveIndex = refractiveIndex
        self.recordRefractiveIndices = recordRefractiveIndices
    }

    /// Cellulose triacetate ("acetate safety base"), the support under almost
    /// every roll film and motion-picture camera negative.
    public static func acetate(thicknessMM: Float) -> FilmBase {
        FilmBase(thicknessMM: thicknessMM, refractiveIndex: 1.48,
                 recordRefractiveIndices: [1.474, 1.480, 1.489])
    }

    /// Polyester (Kodak ESTAR), used where dimensional stability matters —
    /// sheet film, and the thin high-strength bases of some cine products.
    public static func estar(thicknessMM: Float) -> FilmBase {
        FilmBase(thicknessMM: thicknessMM, refractiveIndex: 1.64,
                 recordRefractiveIndices: [1.635, 1.643, 1.657])
    }

    /// Angle inside the base beyond which the rear surface reflects
    /// everything: sin(theta_c) = 1 / n.
    public var criticalAngle: Float { asin(1 / refractiveIndex) }

    /// Where the halo lives.
    public var haloRadiusMM: Float {
        2 * thicknessMM * tan(criticalAngle)
    }

    /// Wavelength-dispersed refractive index for standard color record centers
    /// (0: Red ~650nm, 1: Green ~550nm, 2: Blue ~450nm).
    public func refractiveIndex(forRecord record: Int) -> Float {
        guard record >= 0 && record < 3 else { return refractiveIndex }
        guard let indices = recordRefractiveIndices else { return refractiveIndex }
        precondition(indices.count == 3)
        return indices[record]
    }
}

/// The independently calibratable shape of a stock's returned-light profile.
///
/// `FilmStock.halationStrength` remains the returned-to-direct exposure ratio. These values only
/// describe where that returned light lands, so changing a creative halation amount cannot also
/// change the stock's halo radius.
public struct HalationProfile: Codable, Sendable, Equatable {
    /// Natural-log optical depth at normal incidence for one round trip through the material
    /// between an imaging layer and the rear reflector. At angle theta the survival is
    /// `exp(-roundTripOpticalDepth / cos(theta))`.
    public var roundTripOpticalDepth: [Float]
    /// Exponent q of the normalized angular density
    /// `(q + 1) * cos(theta)^q * sin(theta)`. q = 1 is Lambertian diffusion.
    public var angularExponent: [Float]
    /// Share of returned light assigned to short-range scatter inside the emulsion rather than
    /// reflection from the rear of the support.
    public var diffuseShare: [Float]
    /// Gaussian sigma in millimeters of that short-range component.
    public var diffuseSigmaMM: [Float]
    /// Effective survival from one rear-surface return into another round trip. It collects the
    /// front-interface and emulsion losses that are not the path optical depth above.
    public var bounceRetention: [Float]
    /// Height in millimeters of each imaging record above the front surface of the support.
    /// `nil` keeps all records at the support surface.
    public var recordDepthMM: [Float]?

    public init(roundTripOpticalDepth: [Float], angularExponent: [Float],
                diffuseShare: [Float], diffuseSigmaMM: [Float],
                bounceRetention: [Float], recordDepthMM: [Float]? = nil) {
        self.roundTripOpticalDepth = roundTripOpticalDepth
        self.angularExponent = angularExponent
        self.diffuseShare = diffuseShare
        self.diffuseSigmaMM = diffuseSigmaMM
        self.bounceRetention = bounceRetention
        self.recordDepthMM = recordDepthMM
    }
}

/// The physical model of halation: light that the emulsion failed to absorb reflecting off the back
/// of the film base and exposing the emulsion a second time, displaced.
public enum Halation {
    /// Three centered Gaussian scales are shared across layers; only the mixing weights differ.
    /// A positive mixture of centered Gaussians is continuous and monotone at every radius.
    public static let scaleCount = 3

    /// Ratio between neighbouring blur scales.
    static let scaleRatio: Float = 1.7

    /// Round trips counted before the trapped light is negligible.
    static let bounceCount = 5

    /// Midpoint samples across (0, pi/2) for the angular quadrature.
    static let angleSamples = 256

    /// Everything the halation stage needs, in millimeters on the film.
    public struct Kernel: Sendable, Equatable {
        /// Gaussian sigmas of the three centered blur scales, ascending.
        public var sigmaMM: [Float]
        /// Retained for the renderer configuration ABI. Continuous kernels keep every radius zero.
        public var ringRadiusMM: [Float]
        /// Per layer, the mixture over those three scales.
        public var weights: [[Float]]
        /// Per layer, the fraction of the developed exposure that arrives via
        /// the base rather than directly: `s / (1 + s)`.
        public var mix: [Float]
        /// The mix routed through the spectral return matrix: receiver rows by source columns,
        /// each row summing to that receiver's `mix` share. Without a stock matrix the whole
        /// share sits on the diagonal, which is the legacy scalar mix exactly.
        public var matrix: [[Float]]
    }

    /// Splits each receiver's mix share across the source records by the sheet's spectral
    /// return matrix (raw per-wavelength stack transmission integrals; only the row shapes are
    /// read — amplitude stays the strength's). A missing or degenerate row keeps the share on
    /// the diagonal.
    static func mixMatrix(mix: [Float], returnMatrix: [[Float]]?) -> [[Float]] {
        (0..<3).map { receiver in
            let row: [Float]? = {
                guard let matrix = returnMatrix, matrix.count == 3,
                      matrix[receiver].count == 3 else { return nil }
                return matrix[receiver].map { max($0, 0) }
            }()
            guard let row, row.reduce(0, +) > 0 else {
                return (0..<3).map { $0 == receiver ? mix[receiver] : 0 }
            }
            let sum = row.reduce(0, +)
            return row.map { mix[receiver] * $0 / sum }
        }
    }

    /// The share of a layer's exposure that arrives as returned light.
    public static func mix(returning fraction: Float) -> Float {
        let s = max(fraction, 0)
        return s / (1 + s)
    }

    /// Solves the blur scales and per-layer weights for a stock on a support. `returned` is each
    /// layer's reflected fraction (`FilmStock .halationStrength`, already scaled by the render
    /// options).
    public static func kernel(returned: [Float], base: FilmBase,
                              hazeMM: Float = 0,
                              returnMatrix: [[Float]]? = nil) -> Kernel {
        precondition(returned.count == 3)
        let profiles = (0..<3).map { layer in
            let optics = Optics.shared(index: base.refractiveIndex(forRecord: layer))
            return Profile(returning: returned[layer], base: base,
                           recordDepthMM: 0, optics: optics)
        }

        let anchor = max(profiles.map { $0.anchorSigmaMM }.max() ?? 0, 1e-6)
        let sigmas = [anchor / scaleRatio, anchor, anchor * scaleRatio]

        let distances = fitDistances(sigmas: sigmas)
        let basis = sigmas.map { sigma in
            distances.map { gaussianEdgeSpread(distance: $0, sigma: sigma) }
        }
        let weights = profiles.map { profile in
            simplexFit(basis: basis, target: distances.map(profile.edgeSpread))
        }
        let mixes = returned.map { mix(returning: $0) }
        return Kernel(sigmaMM: hazed(sigmas, hazeMM: hazeMM),
                      ringRadiusMM: [0, 0, 0], weights: weights,
                      mix: mixes,
                      matrix: mixMatrix(mix: mixes, returnMatrix: returnMatrix))
    }

    /// The support's impurity scatter, applied after the solve. Convolving each centered Gaussian
    /// scale with isotropic Gaussian haze is exactly a quadrature sum of their sigmas. The fit
    /// stays the clean support's; the scatter rides on top, and 0 returns it bit-identically.
    static func hazed(_ sigmas: [Float], hazeMM: Float) -> [Float] {
        guard hazeMM > 0 else { return sigmas }
        return sigmas.map { ($0 * $0 + hazeMM * hazeMM).squareRoot() }
    }

    /// Builds the independently calibrated physical profile when one is supplied, and preserves
    /// the legacy returned-fraction inversion exactly when it is absent.
    public static func kernel(returned: [Float], base: FilmBase,
                              profile: HalationProfile?,
                              hazeMM: Float = 0,
                              returnMatrix: [[Float]]? = nil) -> Kernel {
        guard let profile else {
            return kernel(returned: returned, base: base, hazeMM: hazeMM,
                          returnMatrix: returnMatrix)
        }
        precondition(returned.count == 3)
        precondition(profile.roundTripOpticalDepth.count == 3)
        precondition(profile.angularExponent.count == 3)
        precondition(profile.diffuseShare.count == 3)
        precondition(profile.diffuseSigmaMM.count == 3)
        precondition(profile.bounceRetention.count == 3)
        precondition(profile.recordDepthMM == nil || profile.recordDepthMM?.count == 3)

        let profiles = (0..<3).map { layer in
            let optics = Optics.shared(index: base.refractiveIndex(forRecord: layer))
            return PhysicalProfile(base: base,
                                   recordDepthMM: profile.recordDepthMM?[layer] ?? 0,
                                   optics: optics,
                                   opticalDepth: max(profile.roundTripOpticalDepth[layer], 0),
                                   angularExponent: max(profile.angularExponent[layer], 0),
                                   diffuseShare: min(max(profile.diffuseShare[layer], 0), 1),
                                   diffuseSigmaMM: max(profile.diffuseSigmaMM[layer], 1e-6),
                                   bounceRetention: min(max(profile.bounceRetention[layer], 0), 1))
        }

        // Fit the continuous optical edge response with three centered Gaussian fields. Keeping
        // the renderer's existing field count avoids the AOT size problem that led to three
        // discrete annuli, while a positive centered mixture has no ring boundaries to expose.
        // The scales are selected jointly so all three records still share the same fields.
        let criticalRadius = (0..<3).map { layer -> Float in
            let index = max(base.refractiveIndex(forRecord: layer), 1.001)
            let angle = asin(1 / index)
            let depth = max(profile.recordDepthMM?[layer] ?? 0, 0)
            return 2 * (base.thicknessMM + depth) * tan(angle)
        }.max() ?? base.haloRadiusMM
        let (sigmas, weights) = continuousGaussianFit(
            profiles: profiles, radius: max(criticalRadius, 1e-6))
        let mixes = returned.map { mix(returning: $0) }
        return Kernel(sigmaMM: hazed(sigmas, hazeMM: hazeMM),
                      ringRadiusMM: [0, 0, 0], weights: weights,
                      mix: mixes,
                      matrix: mixMatrix(mix: mixes, returnMatrix: returnMatrix))
    }

    /// The part of the model that depends only on the support's refractive index: the quadrature
    /// abscissae and, at each of them, the Fresnel reflectance and the Lambertian weight.
    struct Optics {
        let index: Float
        let cosines: [Float]
        let sines: [Float]
        /// s-polarized reflectance of the rear surface at each abscissa.
        let reflectancesS: [Float]
        /// p-polarized reflectance of the rear surface at each abscissa.
        let reflectancesP: [Float]
        /// The Lambertian weight `2 cos * sin` — i.e. `sin(2 theta)`.
        let lambertian: [Float]
        let step: Float

        init(index: Float) {
            let n = max(index, 1.001)
            self.index = n
            let count = angleSamples
            let step = Float.pi / 2 / Float(count)
            var cosines = [Float](repeating: 0, count: count)
            var sines = [Float](repeating: 0, count: count)
            var reflectancesS = [Float](repeating: 0, count: count)
            var reflectancesP = [Float](repeating: 0, count: count)
            var lambertian = [Float](repeating: 0, count: count)
            for i in 0..<count {
                let theta = (Float(i) + 0.5) * step
                let c = cos(theta), s = sin(theta)
                cosines[i] = c
                sines[i] = s
                let (rs, rp) = Halation.polarizedReflectance(cosTheta: c, index: n)
                reflectancesS[i] = rs
                reflectancesP[i] = rp
                lambertian[i] = 2 * c * s
            }
            self.cosines = cosines
            self.sines = sines
            self.reflectancesS = reflectancesS
            self.reflectancesP = reflectancesP
            self.lambertian = lambertian
            self.step = step
        }

        /// Memoized per index.
        private static let lock = NSLock()
        nonisolated(unsafe) private static var cache: [Float: Optics] = [:]

        static func shared(index: Float) -> Optics {
            let key = max(index, 1.001)
            lock.lock()
            if let found = cache[key] {
                lock.unlock()
                return found
            }
            lock.unlock()
            let built = Optics(index: key)
            lock.lock()
            let result = cache[key] ?? built
            cache[key] = result
            lock.unlock()
            return result
        }
    }

    /// One layer's scattered light, tabulated: for every bounce and every angle, how much comes
    /// back and how far away it lands.
    struct Profile {
        let base: FilmBase
        /// Return radius for each surviving (bounce, angle) pair, bounce
        /// major and ascending within a bounce.
        private let radii: [Float]
        /// Energy returned by each pair, normalized to sum to 1.
        private let weights: [Float]
        /// Where each bounce's block starts, plus a final end marker.
        private let blocks: [Int]

        init(returning fraction: Float, base: FilmBase, recordDepthMM: Float = 0,
             optics: Optics) {
            self.base = base
            let t = Halation.transmittance(returning: fraction, optics: optics)
            let count = angleSamples
            var radii = [Float](); radii.reserveCapacity(bounceCount * count)
            var weights = [Float](); weights.reserveCapacity(bounceCount * count)
            var blocks = [0]

            let layerThickness = base.thicknessMM + max(recordDepthMM, 0)

            var perTrip = [Float](repeating: 0, count: count)
            var survival = [Float](repeating: 0, count: count)
            for i in 0..<count {
                perTrip[i] = Halation.survival(transmittance: t,
                                               cosTheta: optics.cosines[i])
                survival[i] = perTrip[i]
            }

            var total: Float = 0
            for m in 1...bounceCount {
                let reach = 2 * Float(m) * layerThickness
                var bounceTotal: Float = 0
                for i in 0..<count {
                    let reflectionCount = Float(2 * m - 1)
                    let rsM = pow(optics.reflectancesS[i], reflectionCount)
                    let rpM = pow(optics.reflectancesP[i], reflectionCount)
                    let rear = 0.5 * (rsM + rpM)
                    let weight = rear * survival[i] * optics.lambertian[i]
                    bounceTotal += weight
                    radii.append(reach * optics.sines[i] / optics.cosines[i])
                    weights.append(weight)
                    survival[i] *= perTrip[i]
                }
                blocks.append(radii.count)
                total += bounceTotal
                if bounceTotal < total * 1e-3 { break }
            }
            if total > 0 {
                for slot in weights.indices { weights[slot] /= total }
            }
            self.radii = radii
            self.weights = weights
            self.blocks = blocks
        }

        /// Halo brightness `distance` millimeters outside a straight
        /// bright/dark boundary, as a fraction of the returned light.
        func edgeSpread(distance: Float) -> Float {
            var beyond: Float = 0
            for bounce in 0..<(blocks.count - 1) {
                let start = blocks[bounce], end = blocks[bounce + 1]
                var low = start, high = end
                while low < high {
                    let middle = (low + high) / 2
                    if radii[middle] > distance { high = middle } else { low = middle + 1 }
                }
                for slot in low..<end {
                    let weight = weights[slot]
                    guard weight > 0 else { continue }
                    beyond += weight * acos(min(distance / radii[slot], 1))
                }
            }
            return beyond / .pi
        }

        /// The sigma of the Gaussian whose halo falls to half its value at
        /// the boundary where the real one does.
        var anchorSigmaMM: Float {
            guard weights.contains(where: { $0 > 0 }) else { return 0 }
            let halfWidthToSigma: Float = 1 / 0.6744897
            var low = 0.1 * base.haloRadiusMM
            var high = 30 * base.haloRadiusMM
            for _ in 0..<14 {
                let mid = (low * high).squareRoot()
                if edgeSpread(distance: mid) > 0.25 { low = mid } else { high = mid }
            }
            return (low * high).squareRoot() * halfWidthToSigma
        }
    }

    /// A returned-light profile whose energy is normalized independently of its spatial shape.
    /// This makes the stock pack's returned fraction identifiable from the halo integral while
    /// the parameters here are fitted from the normalized edge or point response.
    struct PhysicalProfile {
        private let radii: [Float]
        private let weights: [Float]
        let diffuseShare: Float
        let diffuseSigmaMM: Float

        init(base: FilmBase, recordDepthMM: Float = 0, optics: Optics,
             opticalDepth: Float,
             angularExponent: Float, diffuseShare: Float,
             diffuseSigmaMM: Float, bounceRetention: Float) {
            var radii = [Float]()
            var weights = [Float]()
            radii.reserveCapacity(bounceCount * angleSamples)
            weights.reserveCapacity(bounceCount * angleSamples)

            let layerThickness = base.thicknessMM + max(recordDepthMM, 0)

            var total: Float = 0
            for bounce in 1...bounceCount {
                let m = Float(bounce)
                var bounceTotal: Float = 0
                for index in 0..<angleSamples {
                    let cosine = optics.cosines[index]
                    let angular = (angularExponent + 1)
                        * pow(cosine, angularExponent) * optics.sines[index]
                    let pathExponent = -m * opticalDepth / max(cosine, 1e-6)
                    let path = pathExponent < -80 ? 0 : exp(pathExponent)
                    let rsM = pow(optics.reflectancesS[index], m)
                    let rpM = pow(optics.reflectancesP[index], m)
                    let rear = 0.5 * (rsM + rpM)
                    let repeats = bounce == 1
                        ? Float(1) : pow(bounceRetention, Float(bounce - 1))
                    let weight = angular * path * rear * repeats
                    bounceTotal += weight
                    total += weight
                    radii.append(2 * m * layerThickness
                                 * optics.sines[index] / cosine)
                    weights.append(weight)
                }
                if bounce > 1 && bounceTotal < total * 1e-3 { break }
            }
            if total > 0 {
                for index in weights.indices { weights[index] /= total }
            }
            self.radii = radii
            self.weights = weights
            self.diffuseShare = diffuseShare
            self.diffuseSigmaMM = diffuseSigmaMM
        }

        func edgeSpread(distance: Float) -> Float {
            var reflex: Float = 0
            for (radius, weight) in zip(radii, weights) where radius > distance {
                reflex += weight * acos(min(distance / radius, 1)) / .pi
            }
            let diffuse = Halation.gaussianEdgeSpread(
                distance: distance, sigma: diffuseSigmaMM)
            return diffuseShare * diffuse + (1 - diffuseShare) * reflex
        }
    }

    /// Polarized Fresnel reflectance (s and p components) of the base/air interface for a ray
    /// arriving from inside the base.
    static func polarizedReflectance(cosTheta: Float, index n: Float) -> (s: Float, p: Float) {
        let cosIn = min(max(cosTheta, 0), 1)
        let sinIn = (1 - cosIn * cosIn).squareRoot()
        let sinOut = n * sinIn
        guard sinOut < 1 else { return (1, 1) }
        let cosOut = (1 - sinOut * sinOut).squareRoot()
        let s = (n * cosIn - cosOut) / (n * cosIn + cosOut)
        let p = (n * cosOut - cosIn) / (n * cosOut + cosIn)
        return (s * s, p * p)
    }

    /// Unpolarized Fresnel reflectance of the base/air interface for a ray
    /// arriving from inside the base.
    static func reflectance(cosTheta: Float, index n: Float) -> Float {
        let (rs, rp) = polarizedReflectance(cosTheta: cosTheta, index: n)
        return 0.5 * (rs + rp)
    }

    /// Share of the light still travelling after one round trip at this angle: `T^(1/cos)`, the
    /// slant path through everything between the layer and the rear surface.
    static func survival(transmittance t: Float, cosTheta: Float) -> Float {
        let exponent = log(max(t, 1e-30)) / max(cosTheta, 1e-6)
        return exponent < -80 ? 0 : exp(exponent)
    }

    /// Fraction of a layer's exposure that comes back off the base, for a
    /// given round-trip transmittance.
    static func returnedFraction(transmittance t: Float, optics: Optics) -> Float {
        var total: Float = 0
        for i in 0..<angleSamples {
            let perTrip = survival(transmittance: t, cosTheta: optics.cosines[i])
            guard perTrip > 0 else { continue }
            var survivalSoFar = perTrip
            var sum: Float = 0
            for m in 1...bounceCount {
                let reflectionCount = Float(2 * m - 1)
                let rsM = pow(optics.reflectancesS[i], reflectionCount)
                let rpM = pow(optics.reflectancesP[i], reflectionCount)
                let rear = 0.5 * (rsM + rpM)
                sum += rear * survivalSoFar
                survivalSoFar *= perTrip
            }
            total += sum * optics.lambertian[i]
        }
        return total * optics.step
    }

    public static func returnedFraction(transmittance t: Float, index n: Float) -> Float {
        returnedFraction(transmittance: t, optics: Optics.shared(index: n))
    }

    /// Inverse of `returnedFraction`, by bisection.
    static func transmittance(returning fraction: Float, optics: Optics) -> Float {
        guard fraction > 0 else { return 0 }
        var low: Float = 1e-6, high: Float = 0.999
        if returnedFraction(transmittance: high, optics: optics) <= fraction { return high }
        for _ in 0..<28 {
            let mid = (low * high).squareRoot()
            if returnedFraction(transmittance: mid, optics: optics) < fraction {
                low = mid
            } else {
                high = mid
            }
        }
        return (low * high).squareRoot()
    }

    static func transmittance(returning fraction: Float, index n: Float) -> Float {
        transmittance(returning: fraction, optics: Optics.shared(index: n))
    }

    static func gaussianEdgeSpread(distance: Float, sigma: Float) -> Float {
        Float(0.5 * erfc(Double(distance) / (Double(sigma) * 2.0.squareRoot())))
    }

    /// Log-spaced sample distances, plus the boundary itself.
    private static func fitDistances(sigmas: [Float]) -> [Float] {
        let count = 40
        let low = Double(0.02 * sigmas[0]), high = Double(8 * sigmas[2])
        let step = (log(high) - log(low)) / Double(count - 1)
        return [0] + (0..<count).map { Float(exp(log(low) + Double($0) * step)) }
    }

    private static func physicalFitDistances(radius: Float) -> [Float] {
        // Linear samples resolve the critical-angle shoulder; logarithmic tail samples stop the
        // fit spending all of its freedom on that shoulder and dropping later returns.
        let linear = (0...160).map { Float($0) / 160 * 4 * radius }
        let tail = (1...48).map { step -> Float in
            let t = Float(step) / 48
            return 4 * radius * exp(t * log(Float(4)))
        }
        return linear + tail
    }

    /// Selects three shared centered Gaussian scales and their record-specific positive weights.
    /// A small logarithmic grid spans the compact return through the fourth critical-angle radius;
    /// exact diffuse widths join it so a stock-authored emulsion lobe is never quantized away.
    private static func continuousGaussianFit(
        profiles: [PhysicalProfile], radius: Float
    ) -> (sigmas: [Float], weights: [[Float]]) {
        let distances = physicalFitDistances(radius: radius)
        let targets = profiles.map { profile in distances.map(profile.edgeSpread) }
        let logarithmicCandidateCount = 14
        let low = log(Float(0.02)), high = log(Float(4))
        var candidates = (0..<logarithmicCandidateCount).map { index -> Float in
            let t = Float(index) / Float(logarithmicCandidateCount - 1)
            return radius * exp(low + t * (high - low))
        }
        candidates += profiles.filter { $0.diffuseShare > 0 }.map(\.diffuseSigmaMM)
        candidates.sort()
        candidates = candidates.reduce(into: []) { unique, candidate in
            if unique.last.map({ abs($0 - candidate) > 1e-6 * radius }) ?? true {
                unique.append(candidate)
            }
        }
        let candidateCount = candidates.count
        let candidateBasis = candidates.map { sigma in
            distances.map { gaussianEdgeSpread(distance: $0, sigma: sigma) }
        }

        var bestResidual = Float.infinity
        var bestSigmas = Array(candidates.prefix(scaleCount))
        var bestWeights = profiles.map { _ in [Float](repeating: 0, count: scaleCount) }
        for first in 0..<(candidateCount - 2) {
            for second in (first + 1)..<(candidateCount - 1) {
                for third in (second + 1)..<candidateCount {
                    let indices = [first, second, third]
                    let basis = indices.map { candidateBasis[$0] }
                    let weights = targets.map { simplexFit(basis: basis, target: $0) }
                    var residual: Float = 0
                    for (target, row) in zip(targets, weights) {
                        for sample in target.indices {
                            var fitted: Float = 0
                            for scale in 0..<scaleCount {
                                fitted += row[scale] * basis[scale][sample]
                            }
                            let error = fitted - target[sample]
                            residual += error * error
                        }
                    }
                    if residual < bestResidual {
                        bestResidual = residual
                        bestSigmas = indices.map { candidates[$0] }
                        bestWeights = weights
                    }
                }
            }
        }
        return (bestSigmas, bestWeights)
    }

    /// Least squares over the simplex: minimize |basis * w - target| subject
    /// to w >= 0 and sum(w) = 1.
    static func simplexFit(basis: [[Float]], target: [Float]) -> [Float] {
        let count = basis.count
        var best = [Float](repeating: 0, count: count)
        var bestResidual = Float.infinity
        for mask in 1..<(1 << count) {
            let active = (0..<count).filter { mask & (1 << $0) != 0 }
            guard let candidate = solveOnFace(basis: basis, target: target,
                                              active: active, count: count)
            else { continue }
            var residual: Float = 0
            for (index, wanted) in target.enumerated() {
                var value: Float = 0
                for column in 0..<count { value += candidate[column] * basis[column][index] }
                residual += (value - wanted) * (value - wanted)
            }
            if residual < bestResidual {
                bestResidual = residual
                best = candidate
            }
        }
        return best
    }

    /// Solves the equality-constrained least squares restricted to `active`,
    /// eliminating the last active weight with `sum(w) = 1`.
    private static func solveOnFace(basis: [[Float]], target: [Float],
                                    active: [Int], count: Int) -> [Float]? {
        var weights = [Float](repeating: 0, count: count)
        let last = active[active.count - 1]
        let free = active.dropLast()
        if free.isEmpty {
            weights[last] = 1
            return weights
        }
        let size = free.count
        var normal = [Float](repeating: 0, count: size * size)
        var rhs = [Float](repeating: 0, count: size)
        for (row, j) in free.enumerated() {
            for (column, k) in free.enumerated() {
                var sum: Float = 0
                for index in target.indices {
                    sum += (basis[j][index] - basis[last][index])
                        * (basis[k][index] - basis[last][index])
                }
                normal[row * size + column] = sum
            }
            var sum: Float = 0
            for index in target.indices {
                sum += (basis[j][index] - basis[last][index])
                    * (target[index] - basis[last][index])
            }
            rhs[row] = sum
        }
        guard let solution = solveSymmetric(normal, rhs, size: size) else { return nil }
        var used: Float = 0
        for (row, j) in free.enumerated() {
            guard solution[row] >= -1e-6 else { return nil }
            weights[j] = max(solution[row], 0)
            used += weights[j]
        }
        guard used <= 1 + 1e-6 else { return nil }
        weights[last] = 1 - used
        return weights
    }

    /// Gaussian elimination with partial pivoting on a 1x1 or 2x2 system.
    private static func solveSymmetric(_ matrix: [Float], _ vector: [Float],
                                       size: Int) -> [Float]? {
        var a = matrix, b = vector
        for pivot in 0..<size {
            var row = pivot
            for candidate in (pivot + 1)..<size
            where abs(a[candidate * size + pivot]) > abs(a[row * size + pivot]) {
                row = candidate
            }
            guard abs(a[row * size + pivot]) > 1e-20 else { return nil }
            if row != pivot {
                for column in 0..<size {
                    a.swapAt(row * size + column, pivot * size + column)
                }
                b.swapAt(row, pivot)
            }
            for below in (pivot + 1)..<size {
                let factor = a[below * size + pivot] / a[pivot * size + pivot]
                guard factor != 0 else { continue }
                for column in pivot..<size {
                    a[below * size + column] -= factor * a[pivot * size + column]
                }
                b[below] -= factor * b[pivot]
            }
        }
        var solution = [Float](repeating: 0, count: size)
        for row in stride(from: size - 1, through: 0, by: -1) {
            var value = b[row]
            for column in (row + 1)..<size { value -= a[row * size + column] * solution[column] }
            solution[row] = value / a[row * size + row]
        }
        return solution
    }
}

/// Builds and caches halation kernels.
public enum HalationRuntime {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var cache: [UInt64: Halation.Kernel] = [:]
    /// Bounds cache growth while continuous slider changes produce unique keys.
    private static let cacheLimit = 64

    public static func kernel(returned: [Float], base: FilmBase,
                              profile: HalationProfile? = nil,
                              hazeMM: Float = 0,
                              returnMatrix: [[Float]]? = nil) -> Halation.Kernel {
        var key: UInt64 = 0xcbf29ce484222325
        func add(_ value: Float) {
            key = (key ^ UInt64(value.bitPattern)) &* 0x100000001b3
        }
        returned.forEach(add)
        add(base.thicknessMM)
        add(base.refractiveIndex)
        if let indices = base.recordRefractiveIndices {
            add(1)
            indices.forEach(add)
        } else {
            add(0)
        }
        add(hazeMM)
        if let profile {
            add(1)
            profile.roundTripOpticalDepth.forEach(add)
            profile.angularExponent.forEach(add)
            profile.diffuseShare.forEach(add)
            profile.diffuseSigmaMM.forEach(add)
            profile.bounceRetention.forEach(add)
            if let depths = profile.recordDepthMM {
                add(1)
                depths.forEach(add)
            } else {
                add(0)
            }
        } else {
            add(0)
        }
        if let returnMatrix {
            add(1)
            returnMatrix.forEach { $0.forEach(add) }
        } else {
            add(0)
        }

        lock.lock()
        if let found = cache[key] {
            lock.unlock()
            return found
        }
        lock.unlock()

        let built = Halation.kernel(returned: returned, base: base,
                                    profile: profile, hazeMM: hazeMM,
                                    returnMatrix: returnMatrix)
        lock.lock()
        let result = cache[key] ?? built
        if cache.count >= cacheLimit { cache.removeAll(keepingCapacity: true) }
        cache[key] = result
        lock.unlock()
        return result
    }
}
