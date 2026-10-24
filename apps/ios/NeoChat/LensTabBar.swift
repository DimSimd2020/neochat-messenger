import UIKit

/// The floating tab bar after the iOS 26 one: a glass bar with three items. A touch lifts the
/// selection into a lens larger than the bar; it follows the finger, magnifies what is under it
/// and paints it in the accent color, then lands on the nearest item and shrinks back while that
/// icon hops. It lies over the system tab bar, which keeps the layout and VoiceOver; the system
/// items are drawn transparent. The view reaches `room` past the bar so the lens fits.
final class LensTabBar: UIView {
    static let room = CGSize(width: 12, height: 10)
    var onSelect: ((Int) -> Void)?
    var onReselect: (() -> Void)?
    private(set) var selected = 0
    private let titles: [String]
    private let textOnly: Bool
    private let equalWidth: Bool
    private var textWidths: [CGFloat] = []
    private var buttons: [LensTextButton] = []
    private var plainIcons: [UIImage] = []
    private var lensIcons: [UIImage] = []
    private var lensX: CGFloat = 0
    private var lift: CGFloat = 0
    private var slide: Spring!
    private var sizing: Spring!
    private var lensWidth: CGFloat = 0
    private var lifting: Spring!
    private var hops: [Spring] = []
    private var downX: CGFloat = 0
    private var dragging = false
    private var near = 0
    private var pullLink: CADisplayLink?
    /// Points per second the strip is carried along while the lens is held at its edge; negative goes back.
    private var pullSpeed: CGFloat = 0
    private var fingerInWindow = CGPoint.zero

    init(items: [(title: String, icon: String)], textOnly: Bool = false, equalWidth: Bool = true) {
        titles = items.map { $0.title }
        self.textOnly = textOnly
        self.equalWidth = equalWidth
        super.init(frame: .zero)
        backgroundColor = .clear
        isOpaque = false
        contentMode = .redraw
        isAccessibilityElement = false
        accessibilityElementsHidden = !textOnly
        let config = UIImage.SymbolConfiguration(pointSize: 19, weight: .semibold)
        for it in items {
            let image = UIImage(systemName: it.icon, withConfiguration: config) ?? UIImage()
            plainIcons.append(image.withTintColor(Theme.text, renderingMode: .alwaysOriginal))
            lensIcons.append(image.withTintColor(Theme.accent, renderingMode: .alwaysOriginal))
        }
        slide = Spring(0.42, 0.72, precision: 0.3) { [weak self] v in self?.lensX = v; self?.setNeedsDisplay() }
        sizing = Spring(0.42, 0.72, precision: 0.3) { [weak self] v in self?.lensWidth = v; self?.setNeedsDisplay() }
        lifting = Spring(0.34, 0.72) { [weak self] v in self?.lift = v; self?.setNeedsDisplay() }
        hops = items.map { _ in Spring(0.38, 0.42) { [weak self] _ in self?.setNeedsDisplay() } }
        hops.forEach { $0.snap(1) }
        if textOnly {
            textWidths = titles.map { ($0 as NSString).size(withAttributes: [.font: Theme.font(14, .semibold)]).width + 28 }
            for (i, title) in titles.enumerated() {
                let button = LensTextButton(type: .custom)
                button.bar = self
                button.accessibilityLabel = title
                button.isSelected = i == selected
                button.accessibilityTraits = i == selected ? [.button, .selected] : .button
                button.addAction(UIAction { [weak self] _ in
                    guard let self, self.selected != i else { return }
                    Haptics.select()
                    self.setSelected(i)
                    self.onSelect?(i)
                }, for: .primaryActionTriggered)
                buttons.append(button)
                addSubview(button)
            }
        }
    }

    required init?(coder: NSCoder) { fatalError() }

    private var bar: CGRect { bounds.insetBy(dx: Self.room.width, dy: Self.room.height) }
    private var inner: CGRect { bar.insetBy(dx: 4, dy: 4) }
    override var intrinsicContentSize: CGSize {
        CGSize(width: textWidths.reduce(0, +) + 2 * Self.room.width + 8, height: textOnly ? 68 : 76)
    }
    private func itemWidth(_ i: Int) -> CGFloat { equalWidth ? inner.width / CGFloat(titles.count) : textWidths[i] }
    private func center(_ i: Int) -> CGFloat {
        inner.minX + (0..<i).reduce(CGFloat(0)) { $0 + itemWidth($1) } + itemWidth(i) / 2
    }
    private func nearest(_ x: CGFloat) -> Int { titles.indices.min { abs(center($0) - x) < abs(center($1) - x) } ?? 0 }

    override func layoutSubviews() {
        super.layoutSubviews()
        if !slide.running { slide.snap(center(selected)) }
        if !sizing.running { sizing.snap(itemWidth(selected)) }
        for (i, button) in buttons.enumerated() {
            button.frame = CGRect(x: center(i) - itemWidth(i) / 2, y: bar.minY, width: itemWidth(i), height: bar.height)
        }
        layer.shadowPath = UIBezierPath(roundedRect: bar, cornerRadius: bar.height / 2).cgPath
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.12
        layer.shadowRadius = 14
        layer.shadowOffset = CGSize(width: 0, height: 4)
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        setNeedsDisplay()
    }

    /// Follows a selection made elsewhere, e.g. opening a chat from a notification.
    func setSelected(_ i: Int, animated: Bool = true) {
        guard i != selected, titles.indices.contains(i) else { return }
        mark(i)
        slide.response = 0.44
        slide.damping = 0.72
        if bounds.width > 0 {
            if animated { slide.to(center(i)); sizing.to(itemWidth(i)) }
            else { slide.snap(center(i)); sizing.snap(itemWidth(i)) }
            reveal(i)
        }
    }

    private func mark(_ i: Int) {
        selected = i
        for (j, button) in buttons.enumerated() {
            button.isSelected = j == i
            button.accessibilityTraits = j == i ? [.button, .selected] : .button
        }
        hops[i].snap(textOnly ? 0.92 : 0.72)
        hops[i].to(1)
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first else { return }
        let x = touch.location(in: self).x
        fingerInWindow = touch.location(in: nil)
        downX = x
        dragging = false
        near = selected
        lifting.to(1)
        follow(x)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = touches.first else { return }
        fingerInWindow = touch.location(in: nil)
        track(touch.location(in: self).x)
    }

    /// The finger is at `x` in the bar's own coordinates.
    private func track(_ x: CGFloat) {
        if !dragging && abs(x - downX) > 8 { dragging = true }
        follow(x)
        let n = nearest(x)
        if dragging && n != near {
            near = n
            Haptics.select()
        }
        edgePull(x)
    }

    /// The scroll view that carries a strip longer than the screen, if this is one.
    private var scroller: UIScrollView? {
        var v = superview
        while let s = v {
            if let scroll = s as? UIScrollView, scroll.contentSize.width > scroll.bounds.width + 1 { return scroll }
            v = s.superview
        }
        return nil
    }

    /// Holding the lens near the edge of the strip carries the strip along, so the next item,
    /// which is out of sight, comes under the finger.
    private func edgePull(_ x: CGFloat) {
        guard dragging, let scroll = scroller else { return stopPull() }
        let zone: CGFloat = 56
        let seen = convert(CGPoint(x: x, y: 0), to: scroll).x - scroll.bounds.minX
        let into = seen < zone ? seen - zone : max(0, seen - (scroll.bounds.width - zone))
        pullSpeed = max(-1, min(1, into / zone)) * 640
        if pullSpeed == 0 { return stopPull() }
        guard pullLink == nil else { return }
        let l = CADisplayLink(target: Puller(self), selector: #selector(Puller.tick(_:)))
        l.add(to: .main, forMode: .common)
        pullLink = l
    }

    private func stopPull() {
        pullLink?.invalidate()
        pullLink = nil
        pullSpeed = 0
    }

    fileprivate func pullStep(_ link: CADisplayLink) {
        guard let scroll = scroller, pullSpeed != 0 else { return stopPull() }
        let dt = min(1.0 / 20, link.targetTimestamp - link.timestamp)
        let inset = scroll.adjustedContentInset
        let low = -inset.left
        let high = max(low, scroll.contentSize.width - scroll.bounds.width + inset.right)
        let x = min(high, max(low, scroll.contentOffset.x + pullSpeed * CGFloat(dt)))
        guard x != scroll.contentOffset.x else { return }
        scroll.setContentOffset(CGPoint(x: x, y: scroll.contentOffset.y), animated: false)
        // The finger has not moved on the screen, but the strip under it has.
        track(convert(fingerInWindow, from: nil).x)
    }

    /// Keeps the chosen item in view, with a margin, when the strip is longer than the screen.
    private func reveal(_ i: Int) {
        guard let scroll = scroller else { return }
        let rect = CGRect(x: center(i) - itemWidth(i) / 2 - 16, y: 0, width: itemWidth(i) + 32, height: 1)
        scroll.scrollRectToVisible(convert(rect, to: scroll), animated: !Theme.reducedMotion)
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        land(nearest(touches.first?.location(in: self).x ?? center(selected)))
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { land(selected) }

    /// The lifted lens trails the finger a little, which reads as weight.
    private func follow(_ x: CGFloat) {
        slide.response = dragging ? 0.16 : 0.22
        slide.damping = 0.86
        slide.to(min(center(titles.count - 1), max(center(0), x)))
        sizing.to(itemWidth(nearest(x)))
    }

    private func land(_ i: Int) {
        dragging = false
        stopPull()
        if i != near { Haptics.select() }
        slide.response = 0.42
        slide.damping = 0.7
        slide.to(center(i))
        sizing.to(itemWidth(i))
        lifting.to(0)
        reveal(i)
        if i != selected {
            mark(i)
            onSelect?(i)
        } else {
            onReselect?()
        }
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let bar = self.bar
        let barPath = UIBezierPath(roundedRect: bar, cornerRadius: bar.height / 2)
        Theme.glass.setFill()
        barPath.fill()
        Theme.rim.setStroke()
        barPath.lineWidth = 1
        barPath.stroke()
        let cy = bar.midY
        let cell = lensWidth
        // At rest the lens is the pill on the chosen item; lifted it is wider and taller than the bar.
        let stretch = min(abs(slide.velocity) * 0.02, cell * 0.3)
        let w = cell * (1 + 0.24 * lift) + stretch
        let rest = inner.height
        let h = rest + (bar.height + 16 - rest) * lift
        let lens = CGRect(x: lensX - w / 2, y: cy - h / 2, width: w, height: h)
        let lensPath = UIBezierPath(roundedRect: lens, cornerRadius: h / 2)
        // Outside the lens: the plain items and the lens's shadow. Inside, only the lens content shows.
        ctx.saveGState()
        let outside = UIBezierPath(rect: bounds)
        outside.append(lensPath)
        outside.usesEvenOddFillRule = true
        outside.addClip()
        drawItems(plainIcons, Theme.text, cy)
        if lift > 0.01 {
            ctx.setShadow(offset: CGSize(width: 0, height: 4 * lift), blur: 14 * lift, color: UIColor.black.withAlphaComponent(0.28 * lift).cgColor)
            UIColor.black.setFill()
            lensPath.fill()
        }
        ctx.restoreGState()
        let lifted = traitCollection.userInterfaceStyle == .dark ? UIColor.white.withAlphaComponent(0.16) : UIColor.white.withAlphaComponent(0.35)
        mix(Theme.glassStrong, lifted, lift).setFill()
        lensPath.fill()
        // Under the lens the items are larger and in the accent color, cut to its shape.
        ctx.saveGState()
        lensPath.addClip()
        let m = 1 + 0.18 * lift
        ctx.translateBy(x: lensX, y: cy)
        ctx.scaleBy(x: m, y: m)
        ctx.translateBy(x: -lensX, y: -cy)
        drawItems(lensIcons, Theme.accent, cy)
        ctx.restoreGState()
        if lift > 0.01 {
            // A highlight along the top edge, as light catching the glass.
            ctx.saveGState()
            lensPath.addClip()
            let colors = [UIColor.white.withAlphaComponent(0.38 * lift).cgColor, UIColor.white.withAlphaComponent(0).cgColor] as CFArray
            if let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1]) {
                ctx.drawLinearGradient(g, start: CGPoint(x: 0, y: lens.minY), end: CGPoint(x: 0, y: cy), options: [])
            }
            ctx.restoreGState()
        }
        mix(Theme.rim, UIColor.white.withAlphaComponent(0.85), lift * 0.8).setStroke()
        lensPath.lineWidth = 1 + 0.6 * lift
        lensPath.stroke()
    }

    private func drawItems(_ icons: [UIImage], _ color: UIColor, _ cy: CGFloat) {
        let font = textOnly ? Theme.font(14, .semibold) : UIFont.systemFont(ofSize: 10.5, weight: .semibold)
        for (i, t) in titles.enumerated() {
            let x = center(i)
            let s = hops[i].value
            if textOnly {
                let text = NSAttributedString(string: t, attributes: [.font: font, .foregroundColor: color])
                let size = text.size()
                guard let ctx = UIGraphicsGetCurrentContext() else { continue }
                ctx.saveGState()
                ctx.translateBy(x: x, y: cy)
                ctx.scaleBy(x: s, y: s)
                text.draw(at: CGPoint(x: -size.width / 2, y: -size.height / 2))
                ctx.restoreGState()
                continue
            }
            let img = icons[i]
            let size = CGSize(width: img.size.width * s, height: img.size.height * s)
            img.draw(in: CGRect(x: x - size.width / 2, y: cy - 8 - size.height / 2, width: size.width, height: size.height))
            let text = NSAttributedString(string: t, attributes: [.font: font, .foregroundColor: color])
            let ts = text.size()
            text.draw(at: CGPoint(x: x - ts.width / 2, y: cy + 6))
        }
    }

    private func mix(_ a: UIColor, _ b: UIColor, _ t: CGFloat) -> UIColor {
        var r1: CGFloat = 0, g1: CGFloat = 0, b1: CGFloat = 0, a1: CGFloat = 0
        var r2: CGFloat = 0, g2: CGFloat = 0, b2: CGFloat = 0, a2: CGFloat = 0
        a.resolvedColor(with: traitCollection).getRed(&r1, green: &g1, blue: &b1, alpha: &a1)
        b.resolvedColor(with: traitCollection).getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        let k = min(1, max(0, t))
        return UIColor(red: r1 + (r2 - r1) * k, green: g1 + (g2 - g1) * k, blue: b1 + (b2 - b1) * k, alpha: a1 + (a2 - a1) * k)
    }
}

/// The display link keeps its target alive; this indirection lets the bar go.
private final class Puller: NSObject {
    weak var bar: LensTabBar?
    init(_ b: LensTabBar) {
        bar = b
        super.init()
    }
    @objc func tick(_ l: CADisplayLink) {
        guard let b = bar else { return l.invalidate() }
        b.pullStep(l)
    }
}

/// Native buttons keep text tabs accessible to VoiceOver and the hardware keyboard;
/// forwarding touch phases gives them the same lifted lens as the main navigation.
private final class LensTextButton: UIButton {
    weak var bar: LensTabBar?
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) { super.touchesBegan(touches, with: event); bar?.touchesBegan(touches, with: event) }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) { super.touchesMoved(touches, with: event); bar?.touchesMoved(touches, with: event) }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) { bar?.touchesEnded(touches, with: event); super.touchesEnded(touches, with: event) }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { bar?.touchesCancelled(touches, with: event); super.touchesCancelled(touches, with: event) }
}
