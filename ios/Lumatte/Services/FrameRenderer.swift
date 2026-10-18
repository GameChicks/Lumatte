import Foundation
import UIKit

struct FrameLayout {
    let canvas: CGSize
    let photo: CGRect
    let caption: CGRect
}

enum FrameRenderer {
    private static let paper = UIColor(red: 0.949, green: 0.925, blue: 0.878, alpha: 1)
    private static let paperDeep = UIColor(red: 0.906, green: 0.871, blue: 0.808, alpha: 1)
    private static let ink = UIColor(red: 0.122, green: 0.110, blue: 0.094, alpha: 1)
    private static let amber = UIColor(red: 0.788, green: 0.427, blue: 0.176, alpha: 1)
    private static let gallery = UIColor(red: 0.055, green: 0.051, blue: 0.047, alpha: 1)

    static func layout(photoSize: CGSize, options: FrameOptions) -> FrameLayout {
        let photoW = photoSize.width
        let photoH = photoSize.height
        if options.preset == .none {
            return FrameLayout(canvas: photoSize, photo: CGRect(origin: .zero, size: photoSize),
                               caption: .zero)
        }
        let short = min(photoW, photoH)
        let pad = short * max(options.borderFraction, 0.02)
        var header = pad
        var footer = pad * 1.2
        switch options.preset {
        case .instant:
            footer = short * 0.23
        case .museum:
            header = short * 0.105
            footer = short * 0.24
        case .film:
            header = short * 0.10
            footer = short * 0.18
        case .ticket:
            header = short * 0.20
            footer = short * 0.34
        case .paper, .black, .none:
            footer = pad + (options.showMetadata ? short * 0.14 : 0)
        }
        var width = photoW + pad * 2
        var height = photoH + header + footer
        if let ratio = options.ratio.value {
            let target = (options.portrait ?? (photoW < photoH)) ? 1 / ratio : ratio
            if width / height < target { width = height * target }
            else { height = width / target }
        }
        let x = (width - photoW) / 2
        let y = header + (height - header - footer - photoH) / 2
        let photoRect = CGRect(x: x, y: y, width: photoW, height: photoH)
        let caption = CGRect(x: pad * 1.4, y: photoRect.maxY + short * 0.025,
                             width: width - pad * 2.8, height: footer - short * 0.04)
        return FrameLayout(canvas: CGSize(width: width, height: height),
                           photo: photoRect, caption: caption)
    }

    static func render(image: UIImage, options: FrameOptions, stockName: String,
                       metadata: PhotoMetadata) -> UIImage {
        let source = image
        let layout = layout(photoSize: source.size, options: options)
        let canvas = CGSize(width: ceil(layout.canvas.width), height: ceil(layout.canvas.height))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        format.preferredRange = .standard
        let renderer = UIGraphicsImageRenderer(size: canvas, format: format)
        return renderer.image { context in
            let cg = context.cgContext
            cg.interpolationQuality = .high
            drawBackground(cg, canvas: canvas, layout: layout, options: options)
            drawPhoto(cg, image: source, layout: layout, options: options)
            drawCaption(cg, layout: layout, options: options, stockName: stockName, metadata: metadata)
        }
    }

    private static func drawBackground(_ cg: CGContext, canvas: CGSize,
                                       layout: FrameLayout, options: FrameOptions) {
        switch options.preset {
        case .none:
            return
        case .paper, .instant, .ticket:
            cg.setFillColor(paper.cgColor)
            cg.fill(CGRect(origin: .zero, size: canvas))
            cg.setStrokeColor(paperDeep.withAlphaComponent(0.8).cgColor)
            cg.setLineWidth(max(1, canvas.width / 1600))
            cg.stroke(CGRect(x: 0.5, y: 0.5, width: canvas.width - 1, height: canvas.height - 1))
        case .black:
            cg.setFillColor(gallery.cgColor)
            cg.fill(CGRect(origin: .zero, size: canvas))
        case .museum:
            cg.setFillColor(paperDeep.cgColor)
            cg.fill(CGRect(origin: .zero, size: canvas))
            cg.setFillColor(paper.cgColor)
            cg.fill(layout.photo.insetBy(dx: -canvas.width * 0.025, dy: -canvas.height * 0.018))
        case .film:
            cg.setFillColor(gallery.cgColor)
            cg.fill(CGRect(origin: .zero, size: canvas))
            drawFilmPerforations(cg, canvas: canvas, layout: layout)
        }
    }

    private static func drawFilmPerforations(_ cg: CGContext, canvas: CGSize, layout: FrameLayout) {
        let holeW = canvas.width * 0.018
        let holeH = holeW * 1.35
        let margin = canvas.width * 0.012
        let step = holeH * 2.1
        cg.setFillColor(paper.withAlphaComponent(0.82).cgColor)
        var y = margin
        while y + holeH < canvas.height - margin {
            let top = CGRect(x: margin, y: y, width: holeW, height: holeH)
            let bottom = CGRect(x: canvas.width - margin - holeW, y: y, width: holeW, height: holeH)
            let path = CGMutablePath()
            path.addRoundedRect(in: top, cornerWidth: holeW * 0.22, cornerHeight: holeW * 0.22)
            path.addRoundedRect(in: bottom, cornerWidth: holeW * 0.22, cornerHeight: holeW * 0.22)
            cg.addPath(path)
            cg.fillPath()
            y += step
        }
    }

    private static func drawPhoto(_ cg: CGContext, image: UIImage,
                                  layout: FrameLayout, options: FrameOptions) {
        if options.preset == .instant || options.preset == .paper || options.preset == .museum {
            cg.saveGState()
            cg.setShadow(offset: CGSize(width: 0, height: max(1, layout.canvas.height * 0.0015)),
                         blur: max(2, layout.canvas.height * 0.006),
                         color: UIColor.black.withAlphaComponent(0.16).cgColor)
            image.draw(in: layout.photo)
            cg.restoreGState()
        } else {
            image.draw(in: layout.photo)
        }
    }

    private static func drawCaption(_ cg: CGContext, layout: FrameLayout, options: FrameOptions,
                                    stockName: String, metadata: PhotoMetadata) {
        guard options.preset != .none else { return }
        let dark = options.preset == .black || options.preset == .film
        let primary = dark ? paper : ink
        let secondary = dark ? paper.withAlphaComponent(0.62) : ink.withAlphaComponent(0.58)
        let rect = layout.caption
        guard rect.height > 4 else { return }
        let unit = min(layout.canvas.width, layout.canvas.height) / 1000
        let titleFont = UIFont.systemFont(ofSize: max(11, unit * 34), weight: .medium)
        let smallFont = UIFont.systemFont(ofSize: max(8, unit * 21), weight: .regular)
        let labelFont = UIFont.monospacedSystemFont(ofSize: max(7, unit * 18), weight: .medium)

        if options.showFilmLabel {
            draw("LUMATTE", at: CGPoint(x: rect.minX, y: rect.minY), font: labelFont,
                 color: amber, kern: unit * 5)
        }
        let title = options.title.isEmpty ? stockName : options.title
        draw(title, at: CGPoint(x: rect.minX, y: rect.minY + unit * 32), font: titleFont,
             color: primary, maxWidth: rect.width * 0.68)
        let meta = metadata.summaryLine
        let date = metadata.dateLine
        let footer = [meta, date].filter { !$0.isEmpty }.joined(separator: "  ·  ")
        if options.showMetadata, !footer.isEmpty {
            draw(footer, at: CGPoint(x: rect.minX, y: rect.maxY - unit * 24), font: smallFont,
                 color: secondary, maxWidth: rect.width)
        }
        if !options.signature.isEmpty {
            let signatureFont = UIFont(name: "SnellRoundhand", size: max(12, unit * 38)) ??
                UIFont.italicSystemFont(ofSize: max(12, unit * 30))
            let attributes: [NSAttributedString.Key: Any] = [.font: signatureFont, .foregroundColor: primary]
            let size = (options.signature as NSString).size(withAttributes: attributes)
            (options.signature as NSString).draw(at: CGPoint(x: rect.maxX - size.width,
                                                             y: rect.maxY - unit * 40),
                                                 withAttributes: attributes)
        }
        if options.preset == .ticket {
            cg.setStrokeColor(secondary.withAlphaComponent(0.45).cgColor)
            cg.setLineWidth(max(1, unit * 1.6))
            cg.setLineDash(phase: 0, lengths: [unit * 8, unit * 6])
            cg.move(to: CGPoint(x: rect.minX, y: rect.minY - unit * 10))
            cg.addLine(to: CGPoint(x: rect.maxX, y: rect.minY - unit * 10))
            cg.strokePath()
        }
    }

    private static func draw(_ text: String, at point: CGPoint, font: UIFont,
                             color: UIColor, kern: CGFloat = 0, maxWidth: CGFloat? = nil) {
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineBreakMode = .byTruncatingTail
        let attributes: [NSAttributedString.Key: Any] = [
            .font: font, .foregroundColor: color, .kern: kern, .paragraphStyle: paragraph
        ]
        let string = text as NSString
        if let maxWidth, string.size(withAttributes: attributes).width > maxWidth {
            string.draw(in: CGRect(x: point.x, y: point.y, width: maxWidth,
                                   height: font.lineHeight * 1.2),
                        withAttributes: attributes)
        } else {
            string.draw(at: point, withAttributes: attributes)
        }
    }
}
