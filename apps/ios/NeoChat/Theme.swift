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
            refreshWindows(animated: true)
        }
    }

    /// The interface style the windows are set to: an own theme decides light or dark itself, otherwise the day/night mode does.
    static var style: UIUserInterfaceStyle {
        if let dark = Appearance.forcedDark { return dark ? .dark : .light }
        return mode == .light ? .light : mode == .dark ? .dark : .unspecified
    }

    /// Applies the mode and the chosen appearance to every window and tells the backdrops to repaint.
    static func refreshWindows(animated: Bool) {
        for scene in UIApplication.shared.connectedScenes {
            for w in (scene as? UIWindowScene)?.windows ?? [] { apply(to: w, animated: animated) }
        }
        NotificationCenter.default.post(name: Appearance.changed, object: nil)
    }

    static func apply(to w: UIWindow, animated: Bool) {
        let style = Theme.style
        guard w.overrideUserInterfaceStyle != style else { return }
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

/// A soft blob of the backdrop: x and y are fractions of the screen and may lie outside it.
struct BackdropSpot: Codable, Equatable {
    var x: CGFloat
    var y: CGFloat
    var color: UInt32
}

/// The colors of the window backdrop: a three-stop gradient and soft radial blobs.
struct Backdrop: Equatable {
    var c0: UInt32
    var c1: UInt32
    var c2: UInt32
    var spots: [BackdropSpot]
}

/// A theme made in "Оформление интерфейса": one base color and up to five blobs.
struct CustomTheme: Codable, Equatable {
    var name: String
    var base: UInt32
    var spots: [BackdropSpot]
}

/// Built-in themes and two slots for own themes ("Оформление интерфейса"); shared with Android and desktop.
enum Appearance {
    struct Preset {
        let id: String
        let name: String
    }

    static let presets = [Preset(id: "ocean", name: "Океан"), Preset(id: "sunset", name: "Закат"), Preset(id: "forest", name: "Лес"), Preset(id: "lilac", name: "Сирень")]
    static let maxSpots = 5
    static let customSlots = 2
    static let changed = Notification.Name("app.neochat.appearance.changed")

    static func customId(_ slot: Int) -> String { "custom\(slot)" }

    static func custom(_ slot: Int) -> CustomTheme? {
        guard slot >= 0, slot < customSlots, let data = UserDefaults.standard.data(forKey: "appearance_" + customId(slot)) else { return nil }
        return try? JSONDecoder().decode(CustomTheme.self, from: data)
    }

    private static func known(_ id: String) -> Bool {
        presets.contains { $0.id == id } || (0..<customSlots).contains { id == customId($0) && custom($0) != nil }
    }

    /// The saved choice; a theme that is gone means the ocean.
    static var current: String {
        let saved = UserDefaults.standard.string(forKey: "appearance") ?? "ocean"
        return known(saved) ? saved : "ocean"
    }

    static func select(_ id: String) {
        guard known(id), id != current else { return }
        UserDefaults.standard.set(id, forKey: "appearance")
        Theme.refreshWindows(animated: true)
    }

    /// Stores the theme in the slot and switches to it.
    static func saveCustom(_ slot: Int, _ theme: CustomTheme) {
        guard slot >= 0, slot < customSlots, let data = try? JSONEncoder().encode(theme) else { return }
        UserDefaults.standard.set(data, forKey: "appearance_" + customId(slot))
        UserDefaults.standard.set(customId(slot), forKey: "appearance")
        Theme.refreshWindows(animated: true)
    }

    static func deleteCustom(_ slot: Int) {
        guard slot >= 0, slot < customSlots else { return }
        UserDefaults.standard.removeObject(forKey: "appearance_" + customId(slot))
        Theme.refreshWindows(animated: true)
    }

    /// Whether light text reads better on `base` (WCAG contrast against the two text colors).
    static func isDarkBase(_ base: UInt32) -> Bool {
        func linear(_ v: UInt32) -> Double {
            let s = Double(v) / 255
            return s <= 0.03928 ? s / 12.92 : pow((s + 0.055) / 1.055, 2.4)
        }
        func luminance(_ c: UInt32) -> Double {
            0.2126 * linear((c >> 16) & 0xFF) + 0.7152 * linear((c >> 8) & 0xFF) + 0.0722 * linear(c & 0xFF)
        }
        let l = luminance(base)
        return (luminance(0xF2F7FF) + 0.05) / (l + 0.05) >= (l + 0.05) / (luminance(0x182C46) + 0.05)
    }

    /// An own theme sets light or dark by its base color; built-in themes follow the mode (nil).
    static var forcedDark: Bool? {
        for slot in 0..<customSlots where current == customId(slot) {
            if let theme = custom(slot) { return isDarkBase(theme.base) }
        }
        return nil
    }

    /// The backdrop of a theme; `dark` matters for built-in themes only.
    static func backdrop(_ id: String, dark: Bool) -> Backdrop {
        for slot in 0..<customSlots where id == customId(slot) {
            if let theme = custom(slot) { return Backdrop(c0: theme.base, c1: theme.base, c2: theme.base, spots: theme.spots) }
        }
        func make(_ c0: UInt32, _ c1: UInt32, _ c2: UInt32, _ top: UInt32, _ bottom: UInt32) -> Backdrop {
            Backdrop(c0: c0, c1: c1, c2: c2, spots: [BackdropSpot(x: 1, y: 0, color: top), BackdropSpot(x: -0.35, y: 0.95, color: bottom)])
        }
        switch id {
        case "sunset":
            return dark ? make(0x2A1626, 0x1D1021, 0x2B1A24, 0x8A3A5E, 0x8A5420) : make(0xFFF3EA, 0xFBE7DE, 0xF8E4EA, 0xF9B9A5, 0xF3C3D6)
        case "forest":
            return dark ? make(0x10241D, 0x0C1814, 0x112A21, 0x1F6048, 0x3F6A2C) : make(0xEAF6EE, 0xDFF0E5, 0xD9EDE6, 0xAEE0C4, 0xD3E8A6)
        case "lilac":
            return dark ? make(0x201B3A, 0x15112B, 0x1E1B40, 0x54409A, 0x2F5C94) : make(0xF1EDFC, 0xE7E1F7, 0xE1E8F9, 0xCDBFF6, 0xBBD4F6)
        default:
            return dark ? make(0x12213B, 0x101A2E, 0x132C42, 0x294E86, 0x215868) : make(0xE9F0FC, 0xDEE8F5, 0xD7E8F0, 0xB6CDF7, 0xB6E1DD)
        }
    }
}

/// Layers of a backdrop: the gradient and up to five radial blobs. Previews use it directly.
class BackdropView: UIView {
    private let grad = CAGradientLayer()
    private var blobs: [CAGradientLayer] = []
    /// What each blob layer shows now; nil for a free slot.
    private var shown: [BackdropSpot?] = Array(repeating: nil, count: Appearance.maxSpots)

    override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = true
        isUserInteractionEnabled = false
        layer.addSublayer(grad)
        grad.startPoint = CGPoint(x: 0, y: 0)
        grad.endPoint = CGPoint(x: 0.35, y: 1)
        grad.locations = [0, 0.56, 1]
        for _ in 0..<Appearance.maxSpots {
            let blob = CAGradientLayer()
            blob.type = .radial
            blob.startPoint = CGPoint(x: 0.5, y: 0.5)
            blob.endPoint = CGPoint(x: 1, y: 1)
            layer.addSublayer(blob)
            blobs.append(blob)
        }
    }

    required init?(coder: NSCoder) { fatalError() }

    private func blobFrame(_ spot: BackdropSpot) -> CGRect {
        let side = max(bounds.width, bounds.height) * 1.4
        return CGRect(x: bounds.width * spot.x - side / 2, y: bounds.height * spot.y - side / 2, width: side, height: side)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        grad.frame = bounds
        for (i, spot) in shown.enumerated() {
            if let spot { blobs[i].frame = blobFrame(spot) }
        }
        CATransaction.commit()
    }

    /// Shows the backdrop; with `animated` colors and blobs glide to it, a new blob grows in place and a removed one fades.
    func show(_ backdrop: Backdrop, animated: Bool) {
        let glide = animated && !Theme.reducedMotion
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        for i in 0..<blobs.count where i < backdrop.spots.count && shown[i] == nil {
            let clear = Theme.rgb(backdrop.spots[i].color).withAlphaComponent(0).cgColor
            blobs[i].frame = blobFrame(backdrop.spots[i])
            blobs[i].colors = [clear, clear]
        }
        CATransaction.commit()
        CATransaction.begin()
        if glide {
            CATransaction.setAnimationDuration(0.42)
            CATransaction.setAnimationTimingFunction(CAMediaTimingFunction(name: .easeInEaseOut))
        } else {
            CATransaction.setDisableActions(true)
        }
        grad.colors = [Theme.rgb(backdrop.c0).cgColor, Theme.rgb(backdrop.c1).cgColor, Theme.rgb(backdrop.c2).cgColor]
        for i in 0..<blobs.count {
            if i < backdrop.spots.count {
                let spot = backdrop.spots[i]
                let color = Theme.rgb(spot.color)
                blobs[i].frame = blobFrame(spot)
                blobs[i].colors = [color.cgColor, color.withAlphaComponent(0).cgColor]
                shown[i] = spot
            } else if let old = shown[i] {
                let clear = Theme.rgb(old.color).withAlphaComponent(0).cgColor
                blobs[i].colors = [clear, clear]
                shown[i] = nil
            }
        }
        CATransaction.commit()
    }
}

/// The window backdrop of the chosen theme: built-in themes follow day and night.
final class BackgroundView: BackdropView {
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = Theme.canvas
        isOpaque = true
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
        NotificationCenter.default.addObserver(self, selector: #selector(appearanceChanged), name: Appearance.changed, object: nil)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil { refresh(animated: false) }
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        refresh(animated: false)
    }

    @objc private func appearanceChanged() { refresh(animated: window != nil) }

    private func refresh(animated: Bool) {
        show(Appearance.backdrop(Appearance.current, dark: traitCollection.userInterfaceStyle == .dark), animated: animated)
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
        springPress()
    }

    required init?(coder: NSCoder) { fatalError() }

    override var isHighlighted: Bool {
        didSet { UIView.animate(withDuration: Theme.ms(0.14)) { self.alpha = self.isHighlighted ? 0.85 : 1 } }
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
    func toast(_ text: String, actionTitle: String? = nil, action: (() -> Void)? = nil) {
        guard let host = view.window ?? view else { return }
        host.viewWithTag(0x4E4354)?.removeFromSuperview()
        let box = UIStackView()
        box.tag = 0x4E4354
        box.axis = .horizontal
        box.alignment = .center
        box.glass(18, strong: true)
        box.clipsToBounds = true
        let l = PaddedLabel()
        l.text = text
        l.numberOfLines = 0
        l.font = Theme.font(15)
        l.textColor = Theme.text
        box.addArrangedSubview(l)
        box.translatesAutoresizingMaskIntoConstraints = false
        host.addSubview(box)
        NSLayoutConstraint.activate([
            box.centerXAnchor.constraint(equalTo: host.centerXAnchor),
            box.leadingAnchor.constraint(greaterThanOrEqualTo: host.leadingAnchor, constant: 24),
            box.trailingAnchor.constraint(lessThanOrEqualTo: host.trailingAnchor, constant: -24),
            box.bottomAnchor.constraint(equalTo: host.safeAreaLayoutGuide.bottomAnchor, constant: -90),
        ])
        let hide = DispatchWorkItem { [weak box] in
            guard let box else { return }
            box.isUserInteractionEnabled = false
            Motion.animate(0.3, 1) { box.transform = CGAffineTransform(translationX: 0, y: 16).scaledBy(x: 0.94, y: 0.94) }
            UIView.animate(withDuration: Theme.ms(0.18), animations: { box.alpha = 0 }) { _ in box.removeFromSuperview() }
        }
        if let actionTitle, let action {
            let button = UIButton(type: .system)
            button.setTitle(actionTitle, for: .normal)
            button.titleLabel?.font = Theme.font(15, .semibold)
            button.tintColor = Theme.accent
            button.contentEdgeInsets = UIEdgeInsets(top: 12, left: 8, bottom: 12, right: 18)
            button.setContentCompressionResistancePriority(.required, for: .horizontal)
            button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
            var handled = false
            button.addAction(UIAction { [weak box] _ in
                guard !handled, box?.superview != nil else { return }
                handled = true
                hide.cancel()
                box?.removeFromSuperview()
                action()
            }, for: .touchUpInside)
            box.addArrangedSubview(button)
        }
        box.alpha = 0
        UIAccessibility.post(notification: .announcement, argument: text)
        UIView.animate(withDuration: Theme.ms(0.16)) { box.alpha = 1 }
        if !Theme.reducedMotion {
            box.transform = CGAffineTransform(translationX: 0, y: 28).scaledBy(x: 0.86, y: 0.86)
            Motion.animate(0.44, 0.7) { box.transform = .identity }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + (action == nil ? 3.2 : 5), execute: hide)
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
