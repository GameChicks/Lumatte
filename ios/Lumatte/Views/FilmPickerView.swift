import SwiftUI

struct FilmPickerView: View {
    let onSelect: (FilmStock) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var groups: [(FilmCategory, [FilmStock])] {
        let filtered = FilmCatalog.shared.stocks.filter {
            query.isEmpty || $0.name.localizedCaseInsensitiveContains(query)
        }
        return FilmCategory.allCases.compactMap { category in
            let stocks = filtered.filter { $0.category == category }
            return stocks.isEmpty ? nil : (category, stocks)
        }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                LumaBackground()
                ScrollView {
                    VStack(alignment: .leading, spacing: 24) {
                        ForEach(groups, id: \.0) { category, stocks in
                            VStack(alignment: .leading, spacing: 12) {
                                HStack {
                                    SectionLabel(text: category.title)
                                    Spacer()
                                    Text("\(stocks.count) 款").font(LumaTheme.mono(9))
                                        .foregroundStyle(LumaTheme.secondary)
                                }
                                LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 10)], spacing: 12) {
                                    ForEach(stocks) { stock in
                                        Button {
                                            onSelect(stock)
                                            dismiss()
                                        } label: {
                                            FilmTile(stock: stock)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                            }
                        }
                    }
                    .padding(20)
                }
                .scrollIndicators(.hidden)
            }
            .navigationTitle("胶片目录")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜索胶片")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("关闭") { dismiss() }
                        .foregroundStyle(LumaTheme.amber)
                }
            }
            .toolbarBackground(LumaTheme.background, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
        }
        .preferredColorScheme(.dark)
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
