import Foundation
import simd

enum CubeLUTError: LocalizedError {
    case unreadable
    case unsupported
    case invalid(String)
    var errorDescription: String? {
        switch self {
        case .unreadable: return "无法读取 LUT 文件"
        case .unsupported: return "目前仅支持 3D .cube LUT"
        case .invalid(let reason): return "LUT 文件无效：\(reason)"
        }
    }
}

struct CubeLUT: Identifiable {
    let id = UUID()
    let name: String
    let size: Int
    let domainMin: SIMD3<Float>
    let domainMax: SIMD3<Float>
    let values: [SIMD4<Float>]

    static func parse(url: URL) throws -> CubeLUT {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        guard let text = try? String(contentsOf: url, encoding: .utf8) else {
            throw CubeLUTError.unreadable
        }
        var title = url.deletingPathExtension().lastPathComponent
        var size = 0
        var domainMin = SIMD3<Float>(0, 0, 0)
        var domainMax = SIMD3<Float>(1, 1, 1)
        var values: [SIMD4<Float>] = []

        for rawLine in text.split(whereSeparator: \.isNewline) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if line.isEmpty || line.hasPrefix("#") { continue }
            let parts = line.split(whereSeparator: \.isWhitespace).map(String.init)
            guard let keyword = parts.first else { continue }
            switch keyword.uppercased() {
            case "TITLE":
                let value = line.dropFirst(min(5, line.count)).trimmingCharacters(in: .whitespaces)
                let unquoted = value.trimmingCharacters(in: CharacterSet(charactersIn: "\""))
                if !unquoted.isEmpty { title = unquoted }
            case "LUT_3D_SIZE":
                guard parts.count >= 2, let parsed = Int(parts[1]), parsed >= 2, parsed <= 65 else {
                    throw CubeLUTError.invalid("LUT_3D_SIZE 超出 2…65")
                }
                size = parsed
            case "LUT_1D_SIZE":
                throw CubeLUTError.unsupported
            case "DOMAIN_MIN":
                guard parts.count >= 4, let r = Float(parts[1]), let g = Float(parts[2]), let b = Float(parts[3]) else {
                    throw CubeLUTError.invalid("DOMAIN_MIN")
                }
                domainMin = SIMD3<Float>(r, g, b)
            case "DOMAIN_MAX":
                guard parts.count >= 4, let r = Float(parts[1]), let g = Float(parts[2]), let b = Float(parts[3]) else {
                    throw CubeLUTError.invalid("DOMAIN_MAX")
                }
                domainMax = SIMD3<Float>(r, g, b)
            default:
                guard parts.count >= 3, let r = Float(parts[0]), let g = Float(parts[1]), let b = Float(parts[2]) else {
                    throw CubeLUTError.invalid("无法解析第 \(values.count + 1) 行")
                }
                values.append(SIMD4<Float>(r, g, b, 1))
            }
        }
        guard size >= 2 else { throw CubeLUTError.invalid("缺少 LUT_3D_SIZE") }
        guard values.count == size * size * size else {
            throw CubeLUTError.invalid("应有 \(size * size * size) 行数据，实际 \(values.count) 行")
        }
        guard domainMax.x > domainMin.x, domainMax.y > domainMin.y, domainMax.z > domainMin.z else {
            throw CubeLUTError.invalid("DOMAIN 范围无效")
        }
        return CubeLUT(name: title, size: size, domainMin: domainMin, domainMax: domainMax, values: values)
    }
}
