import UIKit

func replyHaptic() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }

final class ArchiveIconView: UIView {
    var progress: CGFloat = 0 { didSet { setNeedsDisplay() } }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false
        isUserInteractionEnabled = false
    }
    required init?(coder: NSCoder) { fatalError() }

    override func draw(_ rect: CGRect) {
        guard let c = UIGraphicsGetCurrentContext() else { return }
        let p = Theme.reducedMotion ? 0 : min(1, max(0, progress))
        let lift = sin(p * .pi)
        c.scaleBy(x: bounds.width / 26, y: bounds.height / 26)
        c.setStrokeColor(Theme.accent.resolvedColor(with: traitCollection).cgColor)
        c.setLineWidth(1.8)
        c.setLineCap(.round)
        c.setLineJoin(.round)
        c.addPath(UIBezierPath(roundedRect: CGRect(x: 5, y: 11, width: 16, height: 12), cornerRadius: 2).cgPath)
        c.move(to: CGPoint(x: 10, y: 16)); c.addLine(to: CGPoint(x: 16, y: 16))
        c.strokePath()
        c.saveGState()
        c.translateBy(x: 21, y: 9)
        c.rotate(by: -24 * .pi / 180 * lift)
        c.translateBy(x: -21, y: -9)
        c.addPath(UIBezierPath(roundedRect: CGRect(x: 3, y: 7 - 2 * lift, width: 20, height: 4), cornerRadius: 1).cgPath)
        c.strokePath()
        c.restoreGState()
        c.setAlpha(lift)
        let y = -3 + 15 * p
        c.move(to: CGPoint(x: 13, y: y - 5)); c.addLine(to: CGPoint(x: 13, y: y))
        c.move(to: CGPoint(x: 10, y: y - 3)); c.addLine(to: CGPoint(x: 13, y: y))
        c.addLine(to: CGPoint(x: 16, y: y - 3))
        c.strokePath()
    }
}

func archiveUndo(_ host: UIViewController, ids: [String]) {
    guard !ids.isEmpty else { return }
    host.toast(ids.count == 1 ? "Чат в архиве" : "Чаты в архиве", actionTitle: "Отмена") { [weak host] in
        func restore(_ index: Int) {
            guard let host else { return }
            if index == ids.count { replyHaptic(); return }
            Core.shared.call("set_archived", ["conversation_id": ids[index], "archived": false]) { result in
                if case .failure(let error) = result { host.toast(error.message); return }
                restore(index + 1)
            }
        }
        restore(0)
    }
}
