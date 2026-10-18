import Foundation

public enum HalationModel: String, Codable, CaseIterable, Sendable, Identifiable {
    case legacy
    case layered
    public var id: String { rawValue }
    public var name: String { self == .legacy ? "Legacy" : "Layered Transport" }
}

public extension FotufilmEngine.Options {
    func transportConstruction(for stock: FilmStock) -> LayeredTransport? {
        guard stage != .print else { return nil }
        if let layeredTransport { return layeredTransport }
        guard halationModel == .layered else { return nil }
        return stock.layeredTransport ?? .illustrative(stock: stock, format: format)
    }

    var withoutLayeredTransport: Self {
        var copy = self
        copy.halationModel = .legacy; copy.layeredTransport = nil
        return copy
    }
}

public extension LayeredTransport {
    static let antiHalationRange: ClosedRange<Float> = 0...4
    static let baseThicknessRange: ClosedRange<Float> = 0.25...3

    /// The construction as the editor's physical controls leave it: every layer but the support
    /// absorbing `antiHalation` times as strongly, the support `baseThickness` times as thick,
    /// and a pressure plate of `pressurePlate` reflectance behind an open back. An opaque backing
    /// hides any plate behind it.
    func adjusted(antiHalation: Float, baseThickness: Float, pressurePlate: Float) throws -> Self {
        guard antiHalation.isFinite, Self.antiHalationRange.contains(antiHalation),
              baseThickness.isFinite, Self.baseThicknessRange.contains(baseThickness),
              pressurePlate.isFinite, (0...1).contains(pressurePlate) else {
            throw TransportError.invalid("invalid anti-halation, base thickness or pressure plate")
        }
        var model = self
        for i in model.layers.indices {
            if model.layers[i].id == "support" {
                model.layers[i].thicknessMM *= Double(baseThickness)
            } else {
                model.layers[i].absorptionPerMM = model.layers[i].absorptionPerMM.map { $0 * Double(antiHalation) }
            }
        }
        if pressurePlate > 0 && model.rearReflectance == nil {
            model.rearPlateReflectance = [Double(pressurePlate)]
        }
        try model.validate()
        return model
    }
}

public extension LayeredTransport {
    /// Generic geometry for model comparison; optical constants are illustrative. The stock
    /// supplies its return ratios and compact response, without modifying its calibration.
    static func illustrative(stock: FilmStock, format: FilmFormat) -> Self {
        Self(constructionID: "illustrative-generic-stack",
            layers: [
                .init(id: "blue-coating", thicknessMM: 0.006, refractiveIndex: [1.52], absorptionPerMM: [0.4]),
                .init(id: "green-coating", thicknessMM: 0.006, refractiveIndex: [1.52], absorptionPerMM: [0.4]),
                .init(id: "red-coating", thicknessMM: 0.006, refractiveIndex: [1.52], absorptionPerMM: [0.4]),
                .init(id: "support", thicknessMM: Double(format.base.thicknessMM),
                      refractiveIndex: [Double(format.base.refractiveIndex)], absorptionPerMM: [0.2])],
            recordDepthMM: [0.0155, 0.0094, 0.0052], angularExponent: [[2.2], [2], [1.8]],
            captureProbability: [[0.35], [0.4], [0.5]],
            returnedToDirect: stock.halationStrength.map { [Double($0)] },
            coreSigmaMM: stock.emulsionDiffusionMM.map(Double.init))
    }
}
