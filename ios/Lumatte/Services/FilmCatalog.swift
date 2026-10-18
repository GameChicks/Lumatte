import Foundation

enum FilmCatalogError: LocalizedError {
    case resourceMissing(String)
    case invalidManifest(String)
    case invalidTable(String)
    case hashMismatch(String)
    var errorDescription: String? {
        switch self {
        case .resourceMissing(let path): return "缺少胶片资源：\(path)"
        case .invalidManifest(let reason): return "胶片模型说明无效：\(reason)"
        case .invalidTable(let reason): return "胶片阶段表无效：\(reason)"
        case .hashMismatch(let name): return "胶片资源校验失败：\(name)"
        }
    }
}

final class FilmCatalog {
    static let shared = FilmCatalog()

    private(set) var stocks: [FilmStock] = []
    private(set) var loadError: String?

    private var rootURL: URL? {
        Bundle.main.resourceURL?.appendingPathComponent("Films", isDirectory: true)
    }

    private struct Catalogue: Decodable {
        struct Stock: Decodable {
            let id: String
            let name: String
            let category: String
            let printId: String
            let positive: Bool
            let description: String?
        }
        let stocks: [Stock]
    }

    private init() {
        reload()
    }

    func reload() {
        guard let root = rootURL else {
            loadError = "应用资源目录不可用"
            return
        }
        let groups = [
            ("mobile-v1", "film/mobile-v1"),
            ("mobile-fotufilm-v1", "film/mobile-fotufilm-v1"),
            ("mobile-monochrome-v1", "film/mobile-monochrome-v1")
        ]
        var found: [String: FilmStock] = [:]
        for (group, _) in groups {
            let url = group == "mobile-v1"
                ? root.appendingPathComponent("catalogue.json")
                : root.appendingPathComponent(group, isDirectory: true)
                    .appendingPathComponent("catalogue.json")
            guard let data = try? Data(contentsOf: url),
                  let catalogue = try? JSONDecoder().decode(Catalogue.self, from: data) else {
                continue
            }
            let fallback = "移动端三阶段 LUT 胶片模型，处理全部在本机完成。"
            for stock in catalogue.stocks {
                guard found[stock.id] == nil else { continue }
                found[stock.id] = FilmStock(
                    id: stock.id,
                    name: stock.name,
                    category: FilmCategory(rawValue: stock.category) ?? .negative,
                    printID: stock.printId,
                    positive: stock.positive,
                    group: group,
                    detail: (stock.description?.isEmpty == false ? stock.description! : fallback)
                )
            }
        }
        if found.isEmpty {
            loadError = "未找到胶片目录，请检查 Films 资源是否已打包"
        } else {
            loadError = nil
        }
        stocks = found.values.sorted {
            if $0.category.order != $1.category.order { return $0.category.order < $1.category.order }
            return $0.name.localizedStandardCompare($1.name) == .orderedAscending
        }
    }

    func stock(id: String) -> FilmStock? {
        stocks.first { $0.id == id }
    }

    func defaultStock() -> FilmStock? {
        stock(id: "kodak_gold_200") ?? stocks.first
    }

    func modelURL(for stock: FilmStock) -> URL? {
        rootURL?.appendingPathComponent(stock.group, isDirectory: true)
            .appendingPathComponent(stock.id, isDirectory: true)
            .appendingPathComponent("model.json")
    }
}
