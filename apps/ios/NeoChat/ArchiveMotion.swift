import UIKit

func replyHaptic() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }

final class GestureProgressView: UIView {
    private let track = CAShapeLayer()
    private let ring = CAShapeLayer()
    private let burst = CAShapeLayer()
    private let radius: CGFloat

    init(radius: CGFloat) {
        self.radius = radius
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        accessibilityElementsHidden = true
        for shape in [track, ring, burst] {
            shape.fillColor = nil
            shape.lineWidth = 1.7
            shape.lineCap = .round
            layer.addSublayer(shape)
        }
        track.opacity = 0
        ring.opacity = 0
        ring.strokeEnd = 0
        burst.opacity = 0
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        let center = CGPoint(x: bounds.midX, y: bounds.midY)
        let circle = UIBezierPath(arcCenter: center, radius: radius, startAngle: -.pi / 2, endAngle: 1.5 * .pi, clockwise: true)
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        for shape in [track, ring, burst] {
            shape.frame = bounds
            shape.strokeColor = Theme.accent.resolvedColor(with: traitCollection).cgColor
        }
        track.path = circle.cgPath
        ring.path = circle.cgPath
        CATransaction.commit()
    }

    func setProgress(_ progress: CGFloat) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        track.opacity = progress > 0.02 ? 0.22 : 0
        ring.opacity = progress > 0.02 ? 1 : 0
        ring.strokeEnd = min(1, max(0, progress))
        CATransaction.commit()
    }

    func reset() {
        setProgress(0)
        burst.removeAllAnimations()
    }

    func complete() {
        guard !Theme.reducedMotion else { return }
        func rays(distance: CGFloat, length: CGFloat) -> CGPath {
            let path = UIBezierPath()
            for i in 0..<8 {
                let angle = (CGFloat(i) + 0.5) * .pi / 4
                let x = cos(angle), y = sin(angle)
                path.move(to: CGPoint(x: bounds.midX + x * distance, y: bounds.midY + y * distance))
                path.addLine(to: CGPoint(x: bounds.midX + x * (distance + length), y: bounds.midY + y * (distance + length)))
            }
            return path.cgPath
        }
        let travel = CABasicAnimation(keyPath: "path")
        travel.fromValue = rays(distance: radius + 3, length: 5)
        travel.toValue = rays(distance: radius + 11, length: 3)
        travel.duration = 0.32
        let fade = CABasicAnimation(keyPath: "opacity")
        fade.fromValue = 0.9
        fade.toValue = 0
        fade.duration = travel.duration
        let animation = CAAnimationGroup()
        animation.animations = [travel, fade]
        animation.duration = travel.duration
        animation.timingFunction = CAMediaTimingFunction(name: .easeOut)
        burst.add(animation, forKey: "completion")
    }
}

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
