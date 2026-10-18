import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @State private var toastTask: Task<Void, Never>?

    var body: some View {
        ZStack {
            LumaBackground()
            Group {
                if model.photos.isEmpty || model.showingHome {
                    HomeView()
                        .transition(.opacity.combined(with: .move(edge: .bottom)))
                } else {
                    EditorView()
                        .transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: 0.35), value: model.photos.isEmpty)
            .animation(.easeInOut(duration: 0.35), value: model.showingHome)

            if let toast = model.toast {
                VStack {
                    Text(toast)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(LumaTheme.ink)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 10)
                        .background(Capsule().fill(LumaTheme.paper))
                        .shadow(color: .black.opacity(0.3), radius: 12, y: 6)
                        .padding(.top, 8)
                    Spacer()
                }
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.25), value: model.toast)
        .onChange(of: model.toast) { _, value in
            toastTask?.cancel()
            guard value != nil else { return }
            toastTask = Task {
                try? await Task.sleep(nanoseconds: 2_200_000_000)
                if !Task.isCancelled { await MainActor.run { model.toast = nil } }
            }
        }
        .alert("出了点问题", isPresented: Binding(
            get: { model.errorMessage != nil },
            set: { if !$0 { model.errorMessage = nil } }
        )) {
            Button("知道了", role: .cancel) { model.errorMessage = nil }
        } message: {
            Text(model.errorMessage ?? "")
        }
    }
}

struct HomeView: View {
    @EnvironmentObject private var model: AppModel
    @State private var pickerItems: [PhotosPickerItem] = []
    @State private var loading = false
    @State private var detailStock: FilmStock?

    private var catalog: FilmCatalog { .shared }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                header
                if !model.photos.isEmpty { continueCard }
                importCard
                filmLibrary
                aboutCard
            }
            .padding(.horizontal, 22)
            .padding(.top, 24)
            .padding(.bottom, 40)
        }
        .scrollIndicators(.hidden)
        .onChange(of: pickerItems) { _, items in
            guard !items.isEmpty else { return }
            Task {
                await MainActor.run { loading = true }
                for (index, item) in items.enumerated() {
                    if let data = try? await item.loadTransferable(type: Data.self) {
                        await MainActor.run {
                            model.addPhoto(data: data, fileName: "IMG_\(Int(Date().timeIntervalSince1970))_\(index)")
                        }
                    }
                }
                await MainActor.run {
                    pickerItems = []
                    loading = false
                }
            }
        }
        .fileImporter(isPresented: $model.fileImporterPresented,
                      allowedContentTypes: [.image, .rawImage],
                      allowsMultipleSelection: true) { result in
            switch result {
            case .success(let urls): model.importURLs(urls)
            case .failure(let error): model.errorMessage = error.localizedDescription
            }
        }
        .sheet(item: $detailStock) { stock in
            FilmDetailSheet(stock: stock) {
                model.chooseStock(stock)
                detailStock = nil
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text("松映")
                    .font(LumaTheme.serif(48, weight: .semibold))
                    .foregroundStyle(LumaTheme.cream)
                Text("Lumatte")
                    .font(LumaTheme.serif(20))
                    .italic()
                    .foregroundStyle(LumaTheme.amber)
                    .padding(.bottom, 6)
            }
            Text("一部装在手机里的数字暗房。")
                .font(.system(size: 15))
                .foregroundStyle(LumaTheme.secondary)
            Text("PANASONIC CAMERA · FILM SIMULATION · FRAME EXPORT")
                .font(LumaTheme.mono(9, weight: .medium))
                .tracking(1.8)
                .foregroundStyle(LumaTheme.secondary.opacity(0.7))
                .padding(.top, 4)
        }
    }

    private var continueCard: some View {
        Button { model.continueEditing() } label: {
            HStack(spacing: 12) {
                if let image = model.selectedPhoto?.image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 58, height: 58)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text("继续编辑").font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(LumaTheme.cream)
                    Text("\(model.photos.count) 张照片 · \(model.selectedStock?.name ?? "未选择胶片")")
                        .font(.system(size: 12)).foregroundStyle(LumaTheme.secondary)
                }
                Spacer()
                Image(systemName: "arrow.right").foregroundStyle(LumaTheme.amber)
            }
            .padding(12)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(LumaTheme.surface)
                    .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .strokeBorder(LumaTheme.rim, lineWidth: 1))
            )
        }
        .buttonStyle(.plain)
    }

    private var importCard: some View {
        LumaPanel(padding: 18) {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    SectionLabel(text: "开始")
                    Spacer()
                    Text("\(catalog.stocks.count) 款胶片")
                        .font(LumaTheme.mono(10, weight: .medium))
                        .foregroundStyle(LumaTheme.amber)
                }
                Text("导入一张照片，\n把它放进另一段时间。")
                    .font(LumaTheme.serif(26, weight: .medium))
                    .foregroundStyle(LumaTheme.cream)
                    .lineSpacing(3)
                PhotosPicker(selection: $pickerItems, maxSelectionCount: 24, matching: .images) {
                    HStack(spacing: 8) {
                        Image(systemName: "photo.stack")
                        Text("从相册导入").font(.system(size: 15, weight: .semibold))
                        Spacer()
                        Image(systemName: "arrow.up.right")
                    }
                    .foregroundStyle(LumaTheme.ink)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 14)
                    .background(Capsule().fill(LumaTheme.paper))
                }
                .disabled(loading)
                SecondaryButton(title: "从文件导入 JPEG / RAW", systemImage: "folder") {
                    model.fileImporterPresented = true
                }
                if loading {
                    HStack(spacing: 8) {
                        ProgressView().tint(LumaTheme.amber)
                        Text("正在读取照片…").font(.system(size: 12)).foregroundStyle(LumaTheme.secondary)
                    }
                }
                Text("胶片与画框处理全部在本机完成。RAW 仅读取相机内嵌 JPEG 预览，不做 RAW 显影。")
                    .font(.system(size: 11))
                    .foregroundStyle(LumaTheme.secondary.opacity(0.8))
                    .lineSpacing(2)
            }
        }
    }

    private var filmLibrary: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                SectionLabel(text: "胶片目录")
                Spacer()
                Text("按类别浏览")
                    .font(.system(size: 11))
                    .foregroundStyle(LumaTheme.secondary)
            }
            if let error = catalog.loadError {
                Text(error).font(.system(size: 12)).foregroundStyle(LumaTheme.danger)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(catalog.stocks) { stock in
                        Button { detailStock = stock } label: {
                            FilmTile(stock: stock)
                                .frame(width: 112)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.vertical, 2)
            }
        }
    }

    private var aboutCard: some View {
        LumaPanel(padding: 16) {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    SectionLabel(text: "关于这次移植")
                    Spacer()
                    Text("GPL-3.0").font(LumaTheme.mono(9)).foregroundStyle(LumaTheme.secondary)
                }
                Text("iOS 原生版由 SwiftUI + Metal 重写，复用上游相同的三阶段 LUT 胶片模型与 CC-BY-SA 4.0 胶片资产。")
                    .font(.system(size: 12))
                    .foregroundStyle(LumaTheme.secondary)
                    .lineSpacing(3)
                Text("当前版本覆盖本地胶片模拟、光晕/颗粒、画框与批量导出；相机无线传输将在后续版本接入。")
                    .font(.system(size: 12))
                    .foregroundStyle(LumaTheme.secondary)
                    .lineSpacing(3)
            }
        }
    }
}

struct FilmDetailSheet: View {
    let stock: FilmStock
    let onChoose: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            LumaBackground()
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    HStack {
                        SectionLabel(text: stock.category.title)
                        Spacer()
                        Button { dismiss() } label: {
                            Image(systemName: "xmark")
                                .foregroundStyle(LumaTheme.secondary)
                                .padding(8)
                                .background(Circle().fill(LumaTheme.surfaceHigh))
                        }
                        .buttonStyle(.plain)
                    }
                    Text(stock.name)
                        .font(LumaTheme.serif(34, weight: .semibold))
                        .foregroundStyle(LumaTheme.cream)
                    FilmTile(stock: stock)
                        .frame(height: 150)
                    Text(stock.detail)
                        .font(.system(size: 14))
                        .foregroundStyle(LumaTheme.secondary)
                        .lineSpacing(4)
                    HStack(spacing: 8) {
                        Text("PRINT")
                            .font(LumaTheme.mono(9))
                            .foregroundStyle(LumaTheme.secondary)
                        Text(stock.printID)
                            .font(LumaTheme.mono(10))
                            .foregroundStyle(LumaTheme.cream)
                        Spacer()
                        Text(stock.positive ? "POSITIVE" : "NEGATIVE")
                            .font(LumaTheme.mono(9))
                            .foregroundStyle(LumaTheme.amber)
                    }
                    PrimaryButton(title: "使用这款胶片", systemImage: "checkmark", action: onChoose)
                        .padding(.top, 4)
                }
                .padding(22)
            }
        }
        .preferredColorScheme(.dark)
    }
}
