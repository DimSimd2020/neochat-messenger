import UIKit

/// Liquid-glass palette shared with Android (docs/design/tokens.json), as dynamic colors.
enum Theme {
    enum Mode: Int { case system, light, dark }

    static func dyn(_ dark: UInt32, _ light: UInt32, _ da: CGFloat = 1, _ la: CGFloat = 1) -> UIColor {
        UIColor { $0.userInterfaceStyle == .dark ? rgb(dark, da) : rgb(light, la) }
    }

    static func rgb(_ v: UInt32, _ a: CGFloat = 1) -> UIColor {
        UIColor(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: a)
    }

    static let text = dyn(0xF2F7FF, 0x182C46)
    static let canvas = dyn(0x12213B, 0xE9F0FC)
    static let muted = dyn(0xBBCBE3, 0x4A607C)
    static let accent = dyn(0x98B9FF, 0x2456B8)
    static let action = dyn(0x4169CF, 0x315DCF)
    static let glass = dyn(0x17263E, 0xF8FCFF, 0.68, 0.66)
    static let glassStrong = dyn(0x1A2A44, 0xFAFDFF, 0.92, 0.94)
    static let rim = dyn(0xDEEEFF, 0xFFFFFF, 0.22, 0.87)
    static let incoming = dyn(0x1C2C46, 0xF9FCFF, 0.84, 0.78)
    static let outgoing = rgb(0x3159B8, 0.95)
    static let outgoingMuted = dyn(0xD6E3FF, 0xD8E4FF)
    static let warning = dyn(0xF2C989, 0x805015)
    static let warningBg = dyn(0x382F24, 0xFFF3DD, 0.88, 0.92)

    static var reducedMotion: Bool { UIAccessibility.isReduceMotionEnabled || UserDefaults.standard.bool(forKey: "reduce_motion") }
    static func ms(_ d: TimeInterval) -> TimeInterval { reducedMotion ? 0 : d }
    /// cubic-bezier(.22,.8,.25,1)
    static let curve = UICubicTimingParameters(controlPoint1: CGPoint(x: 0.22, y: 0.8), controlPoint2: CGPoint(x: 0.25, y: 1))

    static var mode: Mode {
        get { Mode(rawValue: UserDefaults.standard.integer(forKey: "theme")) ?? .system }
        set {
            UserDefaults.standard.set(newValue.rawValue, forKey: "theme")
            for scene in UIApplication.shared.connectedScenes {
                for w in (scene as? UIWindowScene)?.windows ?? [] { apply(to: w, animated: true) }
            }
        }
    }

    static func apply(to w: UIWindow, animated: Bool) {
        let style: UIUserInterfaceStyle = mode == .light ? .light : mode == .dark ? .dark : .unspecified
        guard animated, !reducedMotion else { w.overrideUserInterfaceStyle = style; return }
        UIView.transition(with: w, duration: 0.42, options: [.transitionCrossDissolve, .allowUserInteraction]) {
            w.overrideUserInterfaceStyle = style
        }
    }

    static func font(_ size: CGFloat, _ weight: UIFont.Weight = .regular, style: UIFont.TextStyle = .body) -> UIFont {
        UIFontMetrics(forTextStyle: style).scaledFont(for: .systemFont(ofSize: size, weight: weight))
    }

    static func animate(_ d: TimeInterval, _ body: @escaping () -> Void, done: (() -> Void)? = nil) {
        let a = UIViewPropertyAnimator(duration: ms(d), timingParameters: curve)
        a.addAnimations(body)
        a.addCompletion { _ in done?() }
        a.startAnimation()
    }
}

/// Static gradient background with two soft spots.
final class BackgroundView: UIView {
    private let grad = CAGradientLayer()
    private let top = CAGradientLayer()
    private let bottom = CAGradientLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = Theme.canvas
        isOpaque = true
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
        isUserInteractionEnabled = false
        for l in [grad, top, bottom] { layer.addSublayer(l) }
        top.type = .radial
        bottom.type = .radial
        grad.startPoint = CGPoint(x: 0, y: 0)
        grad.endPoint = CGPoint(x: 0.35, y: 1)
        grad.locations = [0, 0.56, 1]
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        grad.frame = bounds
        let s = max(bounds.width, bounds.height) * 1.4
        top.frame = CGRect(x: bounds.width - s / 2, y: -s / 2, width: s, height: s)
        top.startPoint = CGPoint(x: 0.5, y: 0.5)
        top.endPoint = CGPoint(x: 1, y: 1)
        bottom.frame = CGRect(x: -bounds.width * 0.35 - s / 2, y: bounds.height * 0.95 - s / 2, width: s, height: s)
        bottom.startPoint = CGPoint(x: 0.5, y: 0.5)
        bottom.endPoint = CGPoint(x: 1, y: 1)
        updateColors()
        CATransaction.commit()
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        updateColors()
    }

    private func updateColors() {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let dark = traitCollection.userInterfaceStyle == .dark
        let c = { (v: UInt32) in Theme.rgb(v).cgColor }
        grad.colors = dark ? [c(0x12213B), c(0x101A2E), c(0x132C42)] : [c(0xE9F0FC), c(0xDEE8F5), c(0xD7E8F0)]
        let t = dark ? Theme.rgb(0x294E86) : Theme.rgb(0xB6CDF7)
        let b = dark ? Theme.rgb(0x215868) : Theme.rgb(0xB6E1DD)
        top.colors = [t.cgColor, t.withAlphaComponent(0).cgColor]
        bottom.colors = [b.cgColor, b.withAlphaComponent(0).cgColor]
        CATransaction.commit()
    }
}

// MARK: - Small building blocks

extension UIView {
    func glass(_ radius: CGFloat, strong: Bool = false) {
        backgroundColor = strong ? Theme.glassStrong : Theme.glass
        layer.cornerRadius = radius
        layer.cornerCurve = .continuous
        layer.borderWidth = 1
        layer.borderColor = Theme.rim.resolvedColor(with: traitCollection).cgColor
    }
}

func label(_ text: String, _ size: CGFloat, _ weight: UIFont.Weight = .regular, color: UIColor = Theme.text) -> UILabel {
    let l = UILabel()
    l.text = text
    l.font = Theme.font(size, weight)
    l.adjustsFontForContentSizeCategory = true
    l.textColor = color
    l.numberOfLines = 0
    return l
}

final class PillButton: UIButton {
    init(_ title: String, primary: Bool = true) {
        super.init(frame: .zero)
        setTitle(title, for: .normal)
        titleLabel?.font = Theme.font(16, .semibold)
        titleLabel?.adjustsFontForContentSizeCategory = true
        setTitleColor(primary ? .white : Theme.text, for: .normal)
        setTitleColor((primary ? UIColor.white : Theme.text).withAlphaComponent(0.5), for: .disabled)
        if primary {
            backgroundColor = Theme.action
            layer.cornerRadius = 18
            layer.cornerCurve = .continuous
        } else {
            glass(18)
        }
        heightAnchor.constraint(greaterThanOrEqualToConstant: 50).isActive = true
        contentEdgeInsets = UIEdgeInsets(top: 10, left: 18, bottom: 10, right: 18)
    }

    required init?(coder: NSCoder) { fatalError() }

    override var isHighlighted: Bool {
        didSet { UIView.animate(withDuration: Theme.ms(0.14)) { self.alpha = self.isHighlighted ? 0.75 : 1 } }
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        layer.borderColor = Theme.rim.resolvedColor(with: traitCollection).cgColor
    }
}

final class LinkButton: UIButton {
    init(_ title: String, color: UIColor = Theme.accent) {
        super.init(frame: .zero)
        setTitle(title, for: .normal)
        setTitleColor(color, for: .normal)
        titleLabel?.font = Theme.font(16, .semibold)
        titleLabel?.adjustsFontForContentSizeCategory = true
        heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
    }

    required init?(coder: NSCoder) { fatalError() }
}

final class Field: UITextField {
    init(_ placeholder: String, secure: Bool = false) {
        super.init(frame: .zero)
        self.placeholder = placeholder
        accessibilityLabel = placeholder
        isSecureTextEntry = secure
        font = Theme.font(17)
        adjustsFontForContentSizeCategory = true
        textColor = Theme.text
        autocapitalizationType = .none
        autocorrectionType = .no
        glass(16)
        heightAnchor.constraint(greaterThanOrEqualToConstant: 52).isActive = true
        leftView = UIView(frame: CGRect(x: 0, y: 0, width: 16, height: 1))
        leftViewMode = .always
        rightView = UIView(frame: CGRect(x: 0, y: 0, width: 12, height: 1))
        rightViewMode = .always
        if secure { textContentType = .password }
    }

    required init?(coder: NSCoder) { fatalError() }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        layer.borderColor = Theme.rim.resolvedColor(with: traitCollection).cgColor
    }
}

/// Initials avatar; the hue follows the seed so one person keeps one color.
final class AvatarView: UIView {
    private let initials = UILabel()
    private let icon = UIImageView()
    private let photo = UIImageView()
    private static let images = NSCache<NSString, UIImage>()
    private let dot = UIView()
    /// Green dot for a person who is online now.
    var online = false { didSet { dot.isHidden = !online } }

    init(size: CGFloat) {
        super.init(frame: .zero)
        translatesAutoresizingMaskIntoConstraints = false
        widthAnchor.constraint(equalToConstant: size).isActive = true
        heightAnchor.constraint(equalToConstant: size).isActive = true
        layer.cornerRadius = size * 18 / 48
        layer.cornerCurve = .continuous
        initials.font = .systemFont(ofSize: size * 0.36, weight: .semibold)
        initials.textAlignment = .center
        initials.frame = CGRect(x: 0, y: 0, width: size, height: size)
        addSubview(initials)
        photo.frame = initials.frame
        photo.contentMode = .scaleAspectFill
        photo.clipsToBounds = true
        photo.layer.cornerRadius = layer.cornerRadius
        addSubview(photo)
        icon.frame = CGRect(x: size * 0.25, y: size * 0.25, width: size * 0.5, height: size * 0.5)
        icon.contentMode = .scaleAspectFit
        icon.isHidden = true
        addSubview(icon)
        let d = max(10, size * 0.27)
        dot.frame = CGRect(x: size - d * 0.85, y: size - d * 0.85, width: d, height: d)
        dot.layer.cornerRadius = d / 2
        dot.backgroundColor = Theme.rgb(0x3CC46B)
        dot.layer.borderWidth = 2.5
        dot.layer.borderColor = Theme.dyn(0x12213B, 0xE9F0FC).resolvedColor(with: traitCollection).cgColor
        dot.isHidden = true
        addSubview(dot)
        isAccessibilityElement = false
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        dot.layer.borderColor = Theme.dyn(0x12213B, 0xE9F0FC).resolvedColor(with: traitCollection).cgColor
    }

    /// A symbol instead of initials, e.g. for the archive row.
    func setIcon(_ name: String) {
        photo.image = nil
        initials.text = ""
        icon.image = UIImage(systemName: name, withConfiguration: UIImage.SymbolConfiguration(weight: .semibold))
        icon.tintColor = .white
        icon.isHidden = false
        backgroundColor = Theme.rgb(0x5B6B85)
        online = false
    }

    required init?(coder: NSCoder) { fatalError() }

    func set(_ name: String, seed: String, avatar: String = "") {
        icon.isHidden = true
        photo.image = nil
        if !avatar.isEmpty {
            if let cached = Self.images.object(forKey: avatar as NSString) { photo.image = cached }
            else if let image = UIImage(contentsOfFile: avatar), image.size.width <= 512, image.size.height <= 512 {
                Self.images.countLimit = 48
                Self.images.setObject(image, forKey: avatar as NSString)
                photo.image = image
            }
        }
        var out = ""
        for part in name.split(separator: " ") {
            if let ch = part.first(where: { $0.isLetter || $0.isNumber }) { out.append(ch.uppercased()) }
            if out.count == 2 { break }
        }
        initials.text = out.isEmpty ? "?" : out
        let hues: [CGFloat] = [214, 190, 160, 32, 268, 340, 8, 120]
        var h: UInt32 = 0
        for u in seed.unicodeScalars { h = h &* 31 &+ u.value }
        let hue = hues[Int(h % UInt32(hues.count))] / 360
        backgroundColor = UIColor { $0.userInterfaceStyle == .dark ? UIColor(hue: hue, saturation: 0.42, brightness: 0.5, alpha: 1) : UIColor(hue: hue, saturation: 0.22, brightness: 0.9, alpha: 1) }
        initials.textColor = UIColor { $0.userInterfaceStyle == .dark ? Theme.rgb(0xF2F5FB) : Theme.rgb(0x1C2638) }
    }
}

extension UIViewController {
    func toast(_ text: String) {
        guard let host = view.window ?? view else { return }
        let l = PaddedLabel()
        l.text = text
        l.numberOfLines = 0
        l.font = Theme.font(15)
        l.textColor = Theme.text
        l.glass(18, strong: true)
        l.clipsToBounds = true
        l.translatesAutoresizingMaskIntoConstraints = false
        host.addSubview(l)
        NSLayoutConstraint.activate([
            l.centerXAnchor.constraint(equalTo: host.centerXAnchor),
            l.leadingAnchor.constraint(greaterThanOrEqualTo: host.leadingAnchor, constant: 24),
            l.bottomAnchor.constraint(equalTo: host.safeAreaLayoutGuide.bottomAnchor, constant: -90),
        ])
        l.alpha = 0
        UIAccessibility.post(notification: .announcement, argument: text)
        UIView.animate(withDuration: Theme.ms(0.18), animations: { l.alpha = 1 }) { _ in
            UIView.animate(withDuration: Theme.ms(0.18), delay: 3, options: [], animations: { l.alpha = 0 }) { _ in l.removeFromSuperview() }
        }
    }

    /// Bottom sheet with the platform's grabber and detents.
    func presentSheet(_ vc: UIViewController) {
        let nav = ChatNavController(rootViewController: vc)
        if let s = nav.sheetPresentationController {
            s.detents = [.medium(), .large()]
            s.prefersGrabberVisible = true
            s.preferredCornerRadius = 30
        }
        present(nav, animated: !Theme.reducedMotion)
    }
}

final class PaddedLabel: UILabel {
    override func drawText(in rect: CGRect) { super.drawText(in: rect.inset(by: UIEdgeInsets(top: 12, left: 18, bottom: 12, right: 18))) }
    override var intrinsicContentSize: CGSize {
        let s = super.intrinsicContentSize
        return CGSize(width: s.width + 36, height: s.height + 24)
    }
}

func formatListTime(_ ms: Int64) -> String {
    guard ms > 0 else { return "" }
    let d = Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
    let cal = Calendar.current
    let f = DateFormatter()
    f.locale = Locale(identifier: "ru_RU")
    if cal.isDateInToday(d) { f.dateFormat = "HH:mm"; return f.string(from: d) }
    if cal.isDateInYesterday(d) { return "Вчера" }
    if let days = cal.dateComponents([.day], from: cal.startOfDay(for: d), to: cal.startOfDay(for: Date())).day, days < 7 {
        f.dateFormat = "EE"; return f.string(from: d).capitalized
    }
    f.dateFormat = "dd.MM.yy"
    return f.string(from: d)
}
