import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

private enum EditorTab: String, CaseIterable, Identifiable {
    case film = "胶片"
    case lut = "LUT"
    case frame = "画框"
    case export = "导出"
    var id: String { rawValue }
}

struct EditorView: View {
    @EnvironmentObject private var model: AppModel
    @State private var tab: EditorTab = .film
    @State private var showFilmPicker = false
    @State private var pickerItems: [PhotosPickerItem] = []
    @State private var lutImporterPresented = false

    var body: some View {
        VStack(spacing: 0) {
            topBar
            ScrollView {
                VStack(spacing: 18) {
                    preview
                    photoStrip
                    panel
                }
                .padding(.horizontal, 18)
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
            tabBar
        }
        .sheet(isPresented: $showFilmPicker) {
            FilmPickerView { stock in
                model.chooseStock(stock)
            }
        }
        .fileImporter(isPresented: $lutImporterPresented,
                      allowedContentTypes: [UTType(filenameExtension: "cube") ?? .data],
                      allowsMultipleSelection: false) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first { model.importLUT(url: url) }
            case .failure(let error):
                model.errorMessage = error.localizedDescription
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
        .onChange(of: pickerItems) { _, items in
            guard !items.isEmpty else { return }
            Task {
                for (index, item) in items.enumerated() {
                    if let data = try? await item.loadTransferable(type: Data.self) {
                        await MainActor.run {
                            model.addPhoto(data: data, fileName: "IMG_\(Int(Date().timeIntervalSince1970))_\(index)")
                        }
                    }
                }
                await MainActor.run { pickerItems = [] }
            }
        }
    }

    private var topBar: some View {
        HStack(spacing: 14) {
            Button {
                model.returnHome()
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: "chevron.left")
                    Text("返回")
                }
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(LumaTheme.secondary)
            }
            .buttonStyle(.plain)
            Spacer()
            VStack(spacing: 1) {
                Text("暗房").font(LumaTheme.serif(20, weight: .semibold))
                    .foregroundStyle(LumaTheme.cream)
                Text("DARKROOM").font(LumaTheme.mono(8)).tracking(2)
                    .foregroundStyle(LumaTheme.secondary.opacity(0.7))
            }
            Spacer()
            Button {
                tab = .export
            } label: {
                Text("导出")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(LumaTheme.ink)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(Capsule().fill(LumaTheme.paper))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 18)
        .padding(.top, 10)
        .padding(.bottom, 12)
    }

    private var preview: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(Color.black)
            if let image = model.displayImage {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            } else {
                ProgressView().tint(LumaTheme.amber)
            }
            if model.isRendering {
                VStack {
                    Spacer()
                    HStack(spacing: 6) {
                        ProgressView().controlSize(.small).tint(LumaTheme.cream)
                        Text("显影中").font(LumaTheme.mono(9)).foregroundStyle(LumaTheme.cream)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(Color.black.opacity(0.55)))
                    .padding(10)
                }
            }
        }
        .frame(maxHeight: 430)
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(LumaTheme.rim, lineWidth: 1)
        )
    }

    private var photoStrip: some View {
        HStack(spacing: 10) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(model.photos) { photo in
                        Button {
                            model.selectPhoto(photo)
                        } label: {
                            Image(uiImage: photo.image)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 52, height: 52)
                                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                                        .strokeBorder(photo.id == model.selectedPhoto?.id ? LumaTheme.amber : LumaTheme.rim,
                                                      lineWidth: photo.id == model.selectedPhoto?.id ? 2 : 1)
                                )
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button("移除这张照片", role: .destructive) {
                                model.removePhoto(photo)
                            }
                        }
                    }
                }
            }
            PhotosPicker(selection: $pickerItems, maxSelectionCount: 24, matching: .images) {
                Image(systemName: "plus")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(LumaTheme.cream)
                    .frame(width: 52, height: 52)
                    .background(RoundedRectangle(cornerRadius: 9).fill(LumaTheme.surfaceHigh)
                        .overlay(RoundedRectangle(cornerRadius: 9).strokeBorder(LumaTheme.rim, lineWidth: 1)))
            }
        }
        .frame(height: 54)
    }

    @ViewBuilder
    private var panel: some View {
        switch tab {
        case .film: filmPanel
        case .lut: lutPanel
        case .frame: framePanel
        case .export: exportPanel
        }
    }

    private var filmPanel: some View {
        VStack(spacing: 14) {
            LumaPanel {
                VStack(alignment: .leading, spacing: 14) {
                    HStack {
                        SectionLabel(text: "胶片配方")
                        Spacer()
                        Button("更换胶卷") { showFilmPicker = true }
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(LumaTheme.amber)
                            .buttonStyle(.plain)
                    }
                    HStack(spacing: 12) {
                        if let stock = model.selectedStock {
                            FilmTile(stock: stock, selected: true)
                                .frame(width: 96, height: 124)
                        }
                        VStack(alignment: .leading, spacing: 6) {
                            Text(model.selectedStock?.name ?? "选择胶卷")
                                .font(LumaTheme.serif(25, weight: .semibold))
                                .foregroundStyle(LumaTheme.cream)
                            Text(model.selectedStock?.category.title ?? "")
                                .font(LumaTheme.mono(9)).tracking(1.5)
                                .foregroundStyle(LumaTheme.amber)
                            Text(model.selectedStock?.detail ?? "")
                                .font(.system(size: 11))
                                .foregroundStyle(LumaTheme.secondary)
                                .lineLimit(3)
                        }
                    }
                    LumaSlider(title: "曝光", valueLabel: String(format: "%+.1f EV", model.parameters.exposureEV),
                               value: Binding(get: { Double(model.parameters.exposureEV) },
                                              set: { model.parameters.exposureEV = Float($0) }),
                               range: -3...3, step: 0.1)
                        .onChange(of: model.parameters.exposureEV) { _, _ in model.schedulePreview() }
                    LumaSlider(title: "胶片强度", valueLabel: "\(Int(model.parameters.strength * 100))%",
                               value: Binding(get: { Double(model.parameters.strength) },
                                              set: { model.parameters.strength = Float($0) }),
                               range: 0...1, step: 0.01)
                        .onChange(of: model.parameters.strength) { _, _ in model.schedulePreview() }
                    LumaToggle(title: "Halation 光晕", isOn: $model.parameters.halationEnabled)
                        .onChange(of: model.parameters.halationEnabled) { _, _ in model.schedulePreview() }
                    VStack(alignment: .leading, spacing: 8) {
                        Text("黑柔").font(.system(size: 14)).foregroundStyle(LumaTheme.cream)
                        HStack(spacing: 8) {
                            ForEach(DiffusionGrade.allCases) { grade in
                                ChipButton(title: grade.title, selected: model.parameters.diffusion == grade) {
                                    model.parameters.diffusion = grade
                                    model.schedulePreview()
                                }
                            }
                        }
                    }
                    if model.parameters.strength == 0 {
                        Button("恢复胶片效果") {
                            model.parameters.strength = 1
                            model.schedulePreview()
                        }
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(LumaTheme.amber)
                    }
                }
            }
        }
    }

    private var lutPanel: some View {
        LumaPanel {
            VStack(alignment: .leading, spacing: 14) {
                SectionLabel(text: "LUT 试套")
                if let lut = model.lut {
                    Text(lut.name)
                        .font(LumaTheme.serif(27, weight: .semibold))
                        .foregroundStyle(LumaTheme.cream)
                    Text("\(lut.size)³ · DOMAIN \(String(format: "%.2f–%.2f", lut.domainMin.x, lut.domainMax.x))")
                        .font(LumaTheme.mono(9))
                        .foregroundStyle(LumaTheme.secondary)
                    LumaSlider(title: "LUT 强度", valueLabel: "\(Int(model.lutStrength * 100))%",
                               value: Binding(get: { Double(model.lutStrength) },
                                              set: { model.lutStrength = Float($0) }),
                               range: 0...1, step: 0.01)
                        .onChange(of: model.lutStrength) { _, _ in model.schedulePreview() }
                    HStack(spacing: 10) {
                        SecondaryButton(title: "更换 LUT", systemImage: "arrow.triangle.2.circlepath") {
                            lutImporterPresented = true
                        }
                        SecondaryButton(title: "关闭", systemImage: "xmark") {
                            model.clearLUT()
                        }
                    }
                } else {
                    Text("把一款 .cube LUT 叠在胶片之后。")
                        .font(LumaTheme.serif(24, weight: .medium))
                        .foregroundStyle(LumaTheme.cream)
                    Text("LUT 在胶片渲染完成后应用，不会改写原始照片。目前支持 3D LUT；双槽叠加将在后续版本加入。")
                        .font(.system(size: 12))
                        .foregroundStyle(LumaTheme.secondary)
                        .lineSpacing(3)
                    PrimaryButton(title: "导入 .cube LUT", systemImage: "square.and.arrow.down") {
                        lutImporterPresented = true
                    }
                }
            }
        }
    }

    private var framePanel: some View {
        VStack(spacing: 14) {
            LumaPanel {
                VStack(alignment: .leading, spacing: 14) {
                    HStack {
                        SectionLabel(text: "画框")
                        Spacer()
                        Toggle("预览", isOn: $model.showFrameInPreview)
                            .labelsHidden()
                            .tint(LumaTheme.amber)
                            .onChange(of: model.showFrameInPreview) { _, _ in model.schedulePreview() }
                    }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 8)], spacing: 8) {
                        ForEach(FramePreset.allCases) { preset in
                            ChipButton(title: preset.title, selected: model.frame.preset == preset) {
                                model.frame.preset = preset
                                model.showFrameInPreview = true
                                model.schedulePreview()
                            }
                        }
                    }
                    if model.frame.preset != .none {
                        LumaSlider(title: "留白", valueLabel: String(format: "%.0f%%", model.frame.borderFraction * 100),
                                   value: Binding(get: { Double(model.frame.borderFraction) },
                                                  set: { model.frame.borderFraction = CGFloat($0) }),
                                   range: 0.02...0.12, step: 0.005)
                            .onChange(of: model.frame.borderFraction) { _, _ in model.schedulePreview() }
                        VStack(alignment: .leading, spacing: 8) {
                            Text("画布比例").font(.system(size: 14)).foregroundStyle(LumaTheme.cream)
                            HStack(spacing: 8) {
                                ForEach(FrameRatio.allCases) { ratio in
                                    ChipButton(title: ratio.title, selected: model.frame.ratio == ratio) {
                                        model.frame.ratio = ratio
                                        model.schedulePreview()
                                    }
                                }
                            }
                        }
                        TextField("画框标题（默认胶片名）", text: $model.frame.title)
                            .textFieldStyle(.plain)
                            .foregroundStyle(LumaTheme.cream)
                            .padding(12)
                            .background(RoundedRectangle(cornerRadius: 10).fill(LumaTheme.surfaceHigh))
                            .onSubmit { model.schedulePreview() }
                        TextField("签名", text: $model.frame.signature)
                            .textFieldStyle(.plain)
                            .foregroundStyle(LumaTheme.cream)
                            .padding(12)
                            .background(RoundedRectangle(cornerRadius: 10).fill(LumaTheme.surfaceHigh))
                            .onSubmit { model.schedulePreview() }
                        LumaToggle(title: "显示相机参数", isOn: $model.frame.showMetadata)
                            .onChange(of: model.frame.showMetadata) { _, _ in model.schedulePreview() }
                        LumaToggle(title: "显示胶片标签", isOn: $model.frame.showFilmLabel)
                            .onChange(of: model.frame.showFilmLabel) { _, _ in model.schedulePreview() }
                    }
                }
            }
        }
    }

    private var exportPanel: some View {
        VStack(spacing: 14) {
            LumaPanel {
                VStack(alignment: .leading, spacing: 16) {
                    SectionLabel(text: "输出")
                    HStack(spacing: 8) {
                        ForEach(ExportFormat.allCases) { format in
                            ChipButton(title: format.title, selected: model.exportOptions.format == format) {
                                model.exportOptions.format = format
                            }
                        }
                    }
                    if model.exportOptions.format == .jpeg {
                        LumaSlider(title: "JPEG 质量",
                                   valueLabel: "\(Int(model.exportOptions.quality * 100))",
                                   value: Binding(get: { Double(model.exportOptions.quality) },
                                                  set: { model.exportOptions.quality = CGFloat($0) }),
                                   range: 0.5...1, step: 0.01)
                    }
                    LumaSlider(title: "照片尺寸",
                               valueLabel: "\(model.exportOptions.dimensionPercent)%",
                               value: Binding(get: { Double(model.exportOptions.dimensionPercent) },
                                              set: { model.exportOptions.dimensionPercent = Int($0.rounded()) }),
                               range: 20...100, step: 5)
                    HStack(spacing: 10) {
                        SecondaryButton(title: "存入相册", systemImage: "square.and.arrow.down") {
                            model.exportCurrent(saveToPhotos: true)
                        }
                        SecondaryButton(title: "生成文件", systemImage: "doc") {
                            model.exportCurrent(saveToPhotos: false)
                        }
                    }
                    if model.photos.count > 1 {
                        PrimaryButton(title: "导出本批 · \(model.photos.count) 张", systemImage: "square.stack.3d.up") {
                            model.exportAll(saveToPhotos: true)
                        }
                    }
                    if let url = model.lastExportURL {
                        HStack {
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(LumaTheme.amber)
                            Text(model.lastExportURLs.count > 1
                                 ? "\(model.lastExportURLs.count) 个导出文件"
                                 : url.lastPathComponent)
                                .font(LumaTheme.mono(10))
                                .foregroundStyle(LumaTheme.secondary)
                                .lineLimit(1)
                            Spacer()
                            Button("分享") { model.showShareSheet = true }
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(LumaTheme.amber)
                                .buttonStyle(.plain)
                        }
                    }
                    if model.isExporting {
                        VStack(alignment: .leading, spacing: 6) {
                            ProgressView(value: model.exportProgress).tint(LumaTheme.amber)
                            Text("正在导出 \(Int(model.exportProgress * 100))%")
                                .font(LumaTheme.mono(9)).foregroundStyle(LumaTheme.secondary)
                        }
                    }
                    Text("画框在原图外扩展画布，不会缩小照片。导出文件使用 sRGB 色彩空间。")
                        .font(.system(size: 10))
                        .foregroundStyle(LumaTheme.secondary.opacity(0.75))
                }
            }
        }
        .sheet(isPresented: $model.showShareSheet) {
            ShareSheet(items: model.lastExportURLs.isEmpty
                       ? (model.lastExportURL.map { [$0] } ?? [])
                       : model.lastExportURLs)
        }
    }

    private var tabBar: some View {
        HStack(spacing: 6) {
            ForEach(EditorTab.allCases) { item in
                Button {
                    withAnimation(.easeInOut(duration: 0.18)) { tab = item }
                } label: {
                    Text(item.rawValue)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(tab == item ? LumaTheme.ink : LumaTheme.secondary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 11)
                        .background(
                            Capsule().fill(tab == item ? LumaTheme.paper : Color.clear)
                        )
                }
                .buttonStyle(.plain)
            }
        }
        .padding(6)
        .background(Capsule().fill(LumaTheme.surface))
        .overlay(Capsule().strokeBorder(LumaTheme.rim, lineWidth: 1))
        .padding(.horizontal, 18)
        .padding(.bottom, 10)
    }
}
