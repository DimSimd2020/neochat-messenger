import UIKit

/// LaTeX formulas typeset by the core (the "math" key of a markdown span): the core gives glyph
/// outlines and rules in em of the text, and the formula is drawn into an image in the text color
/// that sits in the line like a character.
enum MathText {
    private static var outlines: [Int: CGPath] = [:]
    private static let lock = NSLock()

    /// The formula as an attachment whose bottom edge hangs below the baseline by the depth of the formula.
    static func attributed(_ math: JSON, em: CGFloat, color: UIColor) -> NSAttributedString {
        let width = number(math["w"]) * em
        let above = number(math["h"]) * em
        let below = number(math["d"]) * em
        let attachment = NSTextAttachment()
        attachment.image = image(math, em: em, color: color, size: CGSize(width: width, height: above + below), baseline: above)
        attachment.bounds = CGRect(x: 0, y: -below, width: width, height: above + below)
        return NSAttributedString(attachment: attachment)
    }

    private static func number(_ v: Any?) -> CGFloat {
        CGFloat((v as? NSNumber)?.doubleValue ?? 0)
    }

    private static func image(_ math: JSON, em: CGFloat, color: UIColor, size: CGSize, baseline: CGFloat) -> UIImage {
        guard size.width > 0, size.height > 0 else { return UIImage() }
        let format = UIGraphicsImageRendererFormat.default()
        format.opaque = false
        let glyphs = math.obj("glyphs") ?? [:]
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            let g = context.cgContext
            g.setFillColor(color.cgColor)
            g.translateBy(x: 0, y: baseline)
            for entry in math["g"] as? [[Any]] ?? [] where entry.count == 4 {
                let id = (entry[0] as? NSNumber)?.intValue ?? -1
                guard let path = outline(id, glyphs) else { continue }
                let scale = em * number(entry[3])
                g.saveGState()
                g.translateBy(x: number(entry[1]) * em, y: number(entry[2]) * em)
                g.scaleBy(x: scale, y: scale)
                g.addPath(path)
                g.fillPath()
                g.restoreGState()
            }
            for rule in math["r"] as? [[Any]] ?? [] where rule.count == 4 {
                g.fill(CGRect(x: number(rule[0]) * em, y: number(rule[1]) * em, width: number(rule[2]) * em, height: number(rule[3]) * em))
            }
        }
    }

    /// The outline of a glyph: the same glyph has the same outline in every formula, so it is built once.
    private static func outline(_ id: Int, _ glyphs: JSON) -> CGPath? {
        lock.lock()
        defer { lock.unlock() }
        if let known = outlines[id] { return known }
        guard let cmds = (glyphs["\(id)"] as? [Any])?.map({ ($0 as? NSNumber)?.doubleValue ?? 0 }) else { return nil }
        let path = CGMutablePath()
        var i = 0
        func p(_ k: Int) -> CGPoint { CGPoint(x: cmds[i + k], y: cmds[i + k + 1]) }
        // 0 move, 1 line, 2 quadratic, 3 cubic, 4 close; each followed by its coordinates.
        while i < cmds.count {
            switch Int(cmds[i]) {
            case 0: path.move(to: p(1)); i += 3
            case 1: path.addLine(to: p(1)); i += 3
            case 2: path.addQuadCurve(to: p(3), control: p(1)); i += 5
            case 3: path.addCurve(to: p(5), control1: p(1), control2: p(3)); i += 7
            default: path.closeSubpath(); i += 1
            }
        }
        outlines[id] = path
        return path
    }
}
