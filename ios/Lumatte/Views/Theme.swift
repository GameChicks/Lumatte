import SwiftUI

enum LumaTheme {
    static let background = Color(red: 0.071, green: 0.063, blue: 0.055)
    static let surface = Color(red: 0.110, green: 0.098, blue: 0.086)
    static let surfaceHigh = Color(red: 0.145, green: 0.129, blue: 0.114)
    static let paper = Color(red: 0.949, green: 0.925, blue: 0.878)
    static let ink = Color(red: 0.122, green: 0.110, blue: 0.094)
    static let amber = Color(red: 0.851, green: 0.478, blue: 0.180)
    static let amberSoft = Color(red: 0.851, green: 0.478, blue: 0.180).opacity(0.16)
    static let cream = Color(red: 0.949, green: 0.925, blue: 0.878)
    static let secondary = Color(red: 0.949, green: 0.925, blue: 0.878).opacity(0.58)
    static let rim = Color(red: 0.949, green: 0.925, blue: 0.878).opacity(0.14)
    static let danger = Color(red: 0.886, green: 0.376, blue: 0.318)

    static func serif(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .serif)
    }
    static func mono(_ size: CGFloat, weight: Font.Weight = .medium) -> Font {
        .system(size: size, weight: weight, design: .monospaced)
    }
}

struct LumaBackground: View {
    var body: some View {
        ZStack {
            LumaTheme.background
            RadialGradient(colors: [LumaTheme.amber.opacity(0.10), .clear],
                           center: .topLeading, startRadius: 20, endRadius: 520)
            RadialGradient(colors: [Color.white.opacity(0.045), .clear],
                           center: .bottomTrailing, startRadius: 10, endRadius: 420)
        }
        .ignoresSafeArea()
    }
}

struct LumaPanel<Content: View>: View {
    var padding: CGFloat = 16
    @ViewBuilder var content: Content
    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .fill(LumaTheme.surface.opacity(0.92))
                    .overlay(
                        RoundedRectangle(cornerRadius: 22, style: .continuous)
                            .strokeBorder(LumaTheme.rim, lineWidth: 1)
                    )
            )
    }
}

struct SectionLabel: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(LumaTheme.mono(10, weight: .semibold))
            .tracking(2.4)
            .foregroundStyle(LumaTheme.secondary)
    }
}

struct LumaSlider: View {
    let title: String
    let valueLabel: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    var step: Double? = nil
    var enabled: Bool = true

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Text(title).font(.system(size: 14)).foregroundStyle(LumaTheme.cream)
                Spacer()
                Text(valueLabel)
                    .font(LumaTheme.mono(12, weight: .semibold))
                    .foregroundStyle(LumaTheme.amber)
            }
            Slider(value: $value, in: range, step: step ?? 0.01)
                .tint(LumaTheme.amber)
                .disabled(!enabled)
                .opacity(enabled ? 1 : 0.4)
        }
    }
}

struct ChipButton: View {
    let title: String
    var selected: Bool = false
    var enabled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(selected ? LumaTheme.ink : LumaTheme.cream)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(
                    Capsule().fill(selected ? LumaTheme.paper : LumaTheme.surfaceHigh)
                )
                .overlay(
                    Capsule().strokeBorder(selected ? Color.clear : LumaTheme.rim, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
    }
}

struct LumaToggle: View {
    let title: String
    @Binding var isOn: Bool
    var body: some View {
        Toggle(isOn: $isOn) {
            Text(title).font(.system(size: 14)).foregroundStyle(LumaTheme.cream)
        }
        .tint(LumaTheme.amber)
    }
}

struct PrimaryButton: View {
    let title: String
    var systemImage: String? = nil
    var enabled: Bool = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title).font(.system(size: 15, weight: .semibold))
            }
            .foregroundStyle(LumaTheme.ink)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Capsule().fill(LumaTheme.paper))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
    }
}

struct SecondaryButton: View {
    let title: String
    var systemImage: String? = nil
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title).font(.system(size: 15, weight: .medium))
            }
            .foregroundStyle(LumaTheme.cream)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 13)
            .background(
                Capsule()
                    .fill(LumaTheme.surfaceHigh)
                    .overlay(Capsule().strokeBorder(LumaTheme.rim, lineWidth: 1))
            )
        }
        .buttonStyle(.plain)
    }
}

struct FilmTile: View {
    let stock: FilmStock
    var selected: Bool = false
    var compact: Bool = false

    private var palette: [Color] {
        switch stock.category {
        case .cinema: return [Color(red: 0.32, green: 0.20, blue: 0.16), Color(red: 0.72, green: 0.36, blue: 0.20)]
        case .negative: return [Color(red: 0.28, green: 0.30, blue: 0.20), Color(red: 0.78, green: 0.56, blue: 0.26)]
        case .reversal: return [Color(red: 0.14, green: 0.26, blue: 0.30), Color(red: 0.38, green: 0.62, blue: 0.58)]
        case .monochrome: return [Color(red: 0.16, green: 0.16, blue: 0.17), Color(red: 0.55, green: 0.54, blue: 0.52)]
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: compact ? 6 : 8) {
            ZStack(alignment: .bottomLeading) {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(LinearGradient(colors: palette, startPoint: .topLeading, endPoint: .bottomTrailing))
                    .frame(height: compact ? 64 : 92)
                Circle()
                    .strokeBorder(LumaTheme.cream.opacity(0.35), lineWidth: 1)
                    .frame(width: compact ? 26 : 38)
                    .padding(10)
                Text(stock.name.split(separator: " ").first.map(String.init) ?? stock.name)
                    .font(LumaTheme.mono(8, weight: .semibold))
                    .foregroundStyle(LumaTheme.cream.opacity(0.75))
                    .padding(8)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
            Text(stock.name)
                .font(.system(size: compact ? 11 : 13, weight: .medium))
                .foregroundStyle(selected ? LumaTheme.amber : LumaTheme.cream)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
        }
        .padding(8)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(selected ? LumaTheme.amberSoft : LumaTheme.surface)
                .overlay(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .strokeBorder(selected ? LumaTheme.amber.opacity(0.7) : LumaTheme.rim, lineWidth: 1)
                )
        )
    }
}
