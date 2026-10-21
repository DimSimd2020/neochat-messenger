import UIKit

/// Springs with the same parameters as the Android client: `response` is the period of the
/// undamped motion in seconds and `damping` the damping ratio (1 settles without overshoot).
/// Reduced motion applies the end state at once.
enum Motion {
    static func spring(_ response: Double, _ damping: Double) -> UISpringTimingParameters {
        let w = 2 * Double.pi / response
        return UISpringTimingParameters(mass: 1, stiffness: w * w, damping: 2 * damping * w, initialVelocity: .zero)
    }

    /// Animates `body` on a spring from what is on screen now; a running animation of the same
    /// properties blends into it instead of jumping.
    static func animate(_ response: Double, _ damping: Double, _ body: @escaping () -> Void, done: (() -> Void)? = nil) {
        if Theme.reducedMotion { body(); done?(); return }
        let a = UIViewPropertyAnimator(duration: response * 2, timingParameters: spring(response, damping))
        a.addAnimations(body)
        a.addCompletion { _ in done?() }
        a.startAnimation()
    }
}

/// A spring for values that are drawn by hand, stepped on every screen refresh. A new target
/// keeps the current velocity, so an interrupted motion never jumps.
final class Spring {
    var response: Double
    var damping: Double
    private(set) var value: CGFloat = 0
    private(set) var velocity: CGFloat = 0
    private(set) var target: CGFloat = 0
    var running: Bool { link != nil }
    private let precision: CGFloat
    private let apply: (CGFloat) -> Void
    private var link: CADisplayLink?
    private var last: CFTimeInterval = 0
    private var rest: (() -> Void)?

    init(_ response: Double, _ damping: Double, precision: CGFloat = 0.001, _ apply: @escaping (CGFloat) -> Void) {
        self.response = response
        self.damping = damping
        self.precision = precision
        self.apply = apply
    }

    deinit { link?.invalidate() }

    func snap(_ v: CGFloat) {
        stop()
        value = v
        target = v
        velocity = 0
        apply(v)
    }

    func to(_ t: CGFloat, velocity v: CGFloat? = nil, then: (() -> Void)? = nil) {
        if Theme.reducedMotion { snap(t); then?(); return }
        target = t
        if let v { velocity = v }
        rest = then
        guard link == nil else { return }
        let l = CADisplayLink(target: Ticker(self), selector: #selector(Ticker.tick(_:)))
        l.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        l.add(to: .main, forMode: .common)
        link = l
        last = 0
    }

    func stop() {
        link?.invalidate()
        link = nil
        rest = nil
    }

    fileprivate func step(_ now: CFTimeInterval) {
        let dt = CGFloat(min(1.0 / 20, last == 0 ? 1.0 / 60 : now - last))
        last = now
        let w = 2 * CGFloat.pi / CGFloat(response)
        var left = dt
        while left > 0 {
            let h = min(left, 0.002)
            velocity += (-w * w * (value - target) - 2 * CGFloat(damping) * w * velocity) * h
            value += velocity * h
            left -= h
        }
        if abs(value - target) < precision && abs(velocity) < precision * 12 {
            value = target
            velocity = 0
            link?.invalidate()
            link = nil
            apply(value)
            let r = rest
            rest = nil
            r?()
            return
        }
        apply(value)
    }

    /// The display link keeps its target alive; this indirection lets the spring go.
    private final class Ticker: NSObject {
        weak var spring: Spring?
        init(_ s: Spring) {
            spring = s
            super.init()
        }
        @objc func tick(_ l: CADisplayLink) {
            guard let s = spring else { return l.invalidate() }
            s.step(l.targetTimestamp)
        }
    }
}

/// Haptics for moments that change something; plain taps stay silent.
enum Haptics {
    /// A choice among several: tab, filter, detent.
    static func select() { UISelectionFeedbackGenerator().selectionChanged() }
    /// Something went out: a message, a reaction.
    static func confirm() { UIImpactFeedbackGenerator(style: .medium).impactOccurred(intensity: 0.7) }
    /// A light tick for small taps that dismiss or reveal.
    static func tick() { UIImpactFeedbackGenerator(style: .light).impactOccurred(intensity: 0.6) }
}

extension UIView {
    /// Grows from `from` to full size with a bounce.
    func pop(from: CGFloat = 0.5, response: Double = 0.42, damping: Double = 0.52) {
        guard !Theme.reducedMotion else { transform = .identity; return }
        transform = CGAffineTransform(scaleX: from, y: from)
        Motion.animate(response, damping) { self.transform = .identity }
    }
}

extension UIControl {
    /// The control sinks about 5 pt on a stiff spring while held and comes back on a soft one
    /// with a small bounce.
    func springPress() {
        addAction(UIAction { [weak self] _ in self?.springPressed(true) }, for: [.touchDown, .touchDragEnter])
        addAction(UIAction { [weak self] _ in self?.springPressed(false) }, for: [.touchUpInside, .touchUpOutside, .touchCancel, .touchDragExit])
    }

    private func springPressed(_ on: Bool) {
        let sink = 1 - min(0.1, 5 / max(bounds.width, bounds.height, 1))
        Motion.animate(on ? 0.2 : 0.42, on ? 1 : 0.5) {
            self.transform = on ? CGAffineTransform(scaleX: sink, y: sink) : .identity
        }
    }
}

/// Symbol effects played on a tap, where the system has them (bounce from iOS 17, the rest from 18).
enum IconEffect { case bounce, wiggle, rotate }

extension UIImageView {
    func play(_ effect: IconEffect) {
        guard !Theme.reducedMotion else { return }
        if #available(iOS 18.0, *) {
            switch effect {
            case .bounce: once(self, BounceSymbolEffect.bounce)
            case .wiggle: once(self, WiggleSymbolEffect.wiggle)
            case .rotate: once(self, RotateSymbolEffect.rotate)
            }
        } else if #available(iOS 17.0, *) {
            once(self, BounceSymbolEffect.bounce)
        }
    }
}

extension UIBarButtonItem {
    func play(_ effect: IconEffect) {
        guard !Theme.reducedMotion else { return }
        if #available(iOS 18.0, *), effect == .wiggle {
            once(self, WiggleSymbolEffect.wiggle)
        } else if #available(iOS 17.0, *) {
            once(self, BounceSymbolEffect.bounce)
        }
    }
}

/// These effects can also repeat; typing them as discrete picks the play-once overload.
@available(iOS 17.0, *)
private func once<E: DiscreteSymbolEffect & SymbolEffect>(_ v: UIImageView, _ e: E) {
    v.addSymbolEffect(e)
}

@available(iOS 17.0, *)
private func once<E: DiscreteSymbolEffect & SymbolEffect>(_ v: UIBarButtonItem, _ e: E) {
    v.addSymbolEffect(e)
}
