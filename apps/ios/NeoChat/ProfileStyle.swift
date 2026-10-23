import UIKit

/// How a profile looks to others: the colour of the name and the gradient of the header.
enum ProfileStyle {
    /// Name colours on offer; an empty string is the default colour of the theme.
    static let nameColors = ["#ff6b6b", "#ff9f43", "#f7c948", "#32d583", "#2ec4b6", "#4dabf7", "#845ef7", "#f06595", "#adb5bd"]

    /// Header gradients on offer, two colours each. A profile without its own gets one by its ID.
    static let headers = [
        "#2b5876,#4e4376", "#f2709c,#ff9472", "#134e5e,#71b280", "#8e2de2,#4a00e0",
        "#cb2d3e,#ef473a", "#f7971e,#ffd200", "#11998e,#38ef7d", "#232526,#414345",
    ]

    static func parse(_ hex: String) -> UIColor? {
        guard hex.count == 7, hex.hasPrefix("#"), let v = UInt32(hex.dropFirst(), radix: 16) else { return nil }
        return Theme.rgb(v)
    }

    static func hex(_ color: UIColor) -> String {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        return String(format: "#%02x%02x%02x", Int((r * 255).rounded()), Int((g * 255).rounded()), Int((b * 255).rounded()))
    }

    private static func luminance(_ c: UIColor) -> CGFloat {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        c.getRed(&r, green: &g, blue: &b, alpha: &a)
        let f = { (v: CGFloat) -> CGFloat in v <= 0.03928 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
        return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)
    }

    private static func contrast(_ a: UIColor, _ b: UIColor) -> CGFloat {
        let (x, y) = (luminance(a), luminance(b))
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    private static func mix(_ a: UIColor, _ b: UIColor, _ t: CGFloat) -> UIColor {
        var (r1, g1, b1, a1) = (CGFloat(0), CGFloat(0), CGFloat(0), CGFloat(0))
        var (r2, g2, b2, a2) = (CGFloat(0), CGFloat(0), CGFloat(0), CGFloat(0))
        a.getRed(&r1, green: &g1, blue: &b1, alpha: &a1)
        b.getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        return UIColor(red: r1 + (r2 - r1) * t, green: g1 + (g2 - g1) * t, blue: b1 + (b2 - b1) * t, alpha: 1)
    }

    /// `color` moved toward white or black until it can be read on `ground`.
    static func readable(_ color: UIColor, on ground: UIColor) -> UIColor {
        let target: UIColor = luminance(ground) < 0.18 ? .white : .black
        for step in 0...10 {
            let c = mix(color, target, CGFloat(step) / 10)
            if contrast(c, ground) >= 4 { return c }
        }
        return target
    }

    /// The colour of a name as a dynamic colour: readable on `ground` in light and in dark mode.
    /// Nil when the person has not chosen one.
    static func name(_ hex: String, on ground: UIColor = Theme.canvas) -> UIColor? {
        guard let chosen = parse(hex) else { return nil }
        return UIColor { traits in readable(chosen, on: ground.resolvedColor(with: traits)) }
    }

    /// The colours of a header: the chosen ones, or a pair picked by the user's ID.
    static func colors(header: String, userId: String) -> [UIColor] {
        let own = header.split(separator: ",").compactMap { parse($0.trimmingCharacters(in: .whitespaces)) }
        if own.count == 1 { return [own[0], mix(own[0], .black, 0.35)] }
        if !own.isEmpty { return own }
        let pick = Int(userId.prefix(2), radix: 16).map { $0 % headers.count } ?? 0
        return headers[pick].split(separator: ",").compactMap { parse(String($0)) }
    }
}

/// The cover of a profile: a gradient with rounded bottom corners and a soft light in the corner.
final class ProfileCoverView: UIView {
    private let gradient = CAGradientLayer()
    private let glow = CAGradientLayer()
    var colors: [UIColor] = [] { didSet { gradient.colors = colors.map { $0.cgColor } } }

    override init(frame: CGRect) {
        super.init(frame: frame)
        gradient.startPoint = CGPoint(x: 0, y: 0)
        gradient.endPoint = CGPoint(x: 1, y: 1)
        glow.type = .radial
        glow.colors = [UIColor.white.withAlphaComponent(0.22).cgColor, UIColor.white.withAlphaComponent(0).cgColor]
        glow.startPoint = CGPoint(x: 0.9, y: 0.1)
        glow.endPoint = CGPoint(x: 1.6, y: 0.9)
        layer.addSublayer(gradient)
        layer.addSublayer(glow)
        layer.cornerRadius = 32
        layer.cornerCurve = .continuous
        layer.maskedCorners = [.layerMinXMaxYCorner, .layerMaxXMaxYCorner]
        clipsToBounds = true
        isAccessibilityElement = false
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        gradient.frame = bounds
        glow.frame = bounds
    }
}

/// A round swatch: a colour, a gradient or the "default" mark; the chosen one has a ring.
final class ColorDotControl: UIControl {
    private let gradient = CAGradientLayer()
    private let mark = UILabel()
    /// What the dot stands for, so a row can mark the chosen one without rebuilding.
    var key = ""
    var colors: [UIColor] = [] {
        didSet {
            gradient.colors = colors.map { $0.cgColor }
            gradient.isHidden = colors.isEmpty
            if colors.count == 1 { gradient.colors = [colors[0].cgColor, colors[0].cgColor] }
            setNeedsLayout()
        }
    }
    var markText = "" {
        didSet {
            mark.text = markText
            mark.textColor = colors.isEmpty ? Theme.muted : .white
        }
    }
    override var isSelected: Bool { didSet { setNeedsLayout() } }

    init(label: String) {
        super.init(frame: .zero)
        accessibilityLabel = label
        accessibilityTraits = .button
        translatesAutoresizingMaskIntoConstraints = false
        widthAnchor.constraint(equalToConstant: 44).isActive = true
        heightAnchor.constraint(equalToConstant: 44).isActive = true
        gradient.startPoint = CGPoint(x: 0, y: 0)
        gradient.endPoint = CGPoint(x: 1, y: 1)
        layer.addSublayer(gradient)
        mark.font = .systemFont(ofSize: 13, weight: .bold)
        mark.textAlignment = .center
        mark.translatesAutoresizingMaskIntoConstraints = false
        addSubview(mark)
        NSLayoutConstraint.activate([mark.centerXAnchor.constraint(equalTo: centerXAnchor), mark.centerYAnchor.constraint(equalTo: centerYAnchor)])
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        let disc = bounds.insetBy(dx: 6, dy: 6)
        gradient.frame = disc
        gradient.cornerRadius = disc.width / 2
        layer.cornerRadius = bounds.width / 2
        if isSelected {
            layer.borderWidth = 2.5
            layer.borderColor = Theme.accent.resolvedColor(with: traitCollection).cgColor
        } else {
            layer.borderWidth = colors.isEmpty ? 1.5 : 0
            layer.borderColor = Theme.muted.resolvedColor(with: traitCollection).cgColor
        }
        if colors.isEmpty {
            // The "default" dot is an outline only.
            layer.borderColor = (isSelected ? Theme.accent : Theme.muted).resolvedColor(with: traitCollection).cgColor
            layer.borderWidth = isSelected ? 2.5 : 1.5
        }
    }
}

/// The window where the colour of the name and the header gradient are chosen.
final class ProfileStyleViewController: SheetViewController {
    private let profile: JSON
    private var nameHex: String
    private var header: [String]
    private let cover = ProfileCoverView()
    private let avatar = AvatarView(size: 72)
    private let nameLabel = label("", 22, .bold)
    private let nameRow = UIStackView()
    private let headRow = UIStackView()
    private let wellRow = UIStackView()
    private let addColor = PillButton("Свои цвета шапки", primary: false)

    init(profile: JSON) {
        self.profile = profile
        nameHex = profile.str("name_color")
        header = profile.str("header").split(separator: ",").map { String($0).trimmingCharacters(in: .whitespaces) }.filter { ProfileStyle.parse($0) != nil }
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError() }

    private var userId: String { profile.str("user_id") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Оформление профиля"
        stack.addArrangedSubview(makePreview())
        heading("Цвет имени")
        stack.addArrangedSubview(scroller(nameRow))
        heading("Шапка")
        stack.addArrangedSubview(scroller(headRow))
        wellRow.spacing = 4
        stack.addArrangedSubview(wellRow)
        addColor.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            if self.header.isEmpty {
                self.header = ProfileStyle.colors(header: "", userId: self.userId).map { ProfileStyle.hex($0) }
            } else {
                self.header.append(self.header.last ?? "#4dabf7")
            }
            self.rebuild()
        }, for: .touchUpInside)
        stack.addArrangedSubview(addColor)
        muted("Цвет имени виден в чатах, ответах, группах и профиле. На тёмном или светлом фоне он при необходимости подстраивается, чтобы читаться.")
        let save = PillButton("Сохранить")
        stack.addArrangedSubview(save)
        save.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            save.isEnabled = false
            Core.shared.call("set_profile", ["name_color": self.nameHex, "header": self.header.joined(separator: ",")]) { r in
                save.isEnabled = true
                switch r {
                case .failure(let e): self.toast(e.message)
                case .success:
                    NotificationCenter.default.post(name: .prefsChanged, object: nil)
                    self.dismiss(animated: !Theme.reducedMotion)
                }
            }
        }, for: .touchUpInside)
        rebuild()
    }

    private func makePreview() -> UIView {
        let box = UIView()
        box.translatesAutoresizingMaskIntoConstraints = false
        cover.translatesAutoresizingMaskIntoConstraints = false
        nameLabel.textAlignment = .center
        nameLabel.translatesAutoresizingMaskIntoConstraints = false
        let shown = profile.str("display_name").isEmpty ? "@" + profile.str("username") : profile.str("display_name")
        avatar.set(shown, seed: userId, avatar: profile.str("avatar_path"))
        nameLabel.text = shown
        box.addSubview(cover)
        box.addSubview(avatar)
        box.addSubview(nameLabel)
        NSLayoutConstraint.activate([
            box.heightAnchor.constraint(equalToConstant: 190),
            cover.topAnchor.constraint(equalTo: box.topAnchor),
            cover.leadingAnchor.constraint(equalTo: box.leadingAnchor),
            cover.trailingAnchor.constraint(equalTo: box.trailingAnchor),
            cover.heightAnchor.constraint(equalToConstant: 110),
            avatar.centerXAnchor.constraint(equalTo: box.centerXAnchor),
            avatar.topAnchor.constraint(equalTo: cover.bottomAnchor, constant: -36),
            nameLabel.topAnchor.constraint(equalTo: avatar.bottomAnchor, constant: 10),
            nameLabel.leadingAnchor.constraint(equalTo: box.leadingAnchor),
            nameLabel.trailingAnchor.constraint(equalTo: box.trailingAnchor),
        ])
        box.isAccessibilityElement = true
        box.accessibilityLabel = "Предпросмотр: " + shown
        updatePreview()
        return box
    }

    private func updatePreview() {
        cover.colors = ProfileStyle.colors(header: header.joined(separator: ","), userId: userId)
        nameLabel.textColor = ProfileStyle.name(nameHex) ?? Theme.text
    }

    private func scroller(_ row: UIStackView) -> UIView {
        row.axis = .horizontal
        row.spacing = 2
        row.translatesAutoresizingMaskIntoConstraints = false
        let scroll = UIScrollView()
        scroll.showsHorizontalScrollIndicator = false
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(row)
        NSLayoutConstraint.activate([
            scroll.heightAnchor.constraint(equalToConstant: 48),
            row.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            row.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            row.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
            row.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
            row.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor),
        ])
        return scroll
    }

    private func well(_ title: String, _ color: UIColor?, _ changed: @escaping (UIColor) -> Void) -> UIColorWell {
        let w = UIColorWell()
        w.supportsAlpha = false
        w.title = title
        w.accessibilityLabel = title
        w.selectedColor = color
        w.translatesAutoresizingMaskIntoConstraints = false
        w.widthAnchor.constraint(equalToConstant: 44).isActive = true
        w.heightAnchor.constraint(equalToConstant: 44).isActive = true
        w.addAction(UIAction { [weak w] _ in if let c = w?.selectedColor { changed(c) } }, for: .valueChanged)
        return w
    }

    /// Marks the chosen dots without rebuilding, so an open colour picker stays where it is.
    private func markSelection() {
        for case let d as ColorDotControl in nameRow.arrangedSubviews { d.isSelected = d.key == nameHex }
        let own = header.joined(separator: ",")
        for case let d as ColorDotControl in headRow.arrangedSubviews { d.isSelected = d.key == own }
    }

    private func rebuild() {
        [nameRow, headRow, wellRow].forEach { $0.arrangedSubviews.forEach { $0.removeFromSuperview() } }
        let none = ColorDotControl(label: "Цвет имени по умолчанию")
        none.markText = "A"
        none.key = ""
        none.addAction(UIAction { [weak self] _ in self?.nameHex = ""; self?.updatePreview(); self?.markSelection() }, for: .touchUpInside)
        nameRow.addArrangedSubview(none)
        for hex in ProfileStyle.nameColors {
            let d = ColorDotControl(label: "Цвет имени " + hex)
            d.colors = [ProfileStyle.parse(hex) ?? .gray]
            d.key = hex
            d.addAction(UIAction { [weak self] _ in self?.nameHex = hex; self?.updatePreview(); self?.markSelection() }, for: .touchUpInside)
            nameRow.addArrangedSubview(d)
        }
        nameRow.addArrangedSubview(well("Свой цвет имени", ProfileStyle.parse(nameHex) ?? ProfileStyle.parse("#4dabf7")) { [weak self] c in
            self?.nameHex = ProfileStyle.hex(c)
            self?.updatePreview()
            self?.markSelection()
        })

        let auto = ColorDotControl(label: "Шапка по умолчанию")
        auto.markText = "A"
        auto.key = ""
        auto.addAction(UIAction { [weak self] _ in self?.header = []; self?.rebuild() }, for: .touchUpInside)
        headRow.addArrangedSubview(auto)
        for preset in ProfileStyle.headers {
            let d = ColorDotControl(label: "Шапка " + preset)
            d.colors = preset.split(separator: ",").compactMap { ProfileStyle.parse(String($0)) }
            d.key = preset
            d.addAction(UIAction { [weak self] _ in self?.header = preset.split(separator: ",").map(String.init); self?.rebuild() }, for: .touchUpInside)
            headRow.addArrangedSubview(d)
        }
        for (i, hex) in header.enumerated() {
            wellRow.addArrangedSubview(well("Цвет шапки \(i + 1)", ProfileStyle.parse(hex)) { [weak self] c in
                guard let self, i < self.header.count else { return }
                self.header[i] = ProfileStyle.hex(c)
                self.updatePreview()
                self.markSelection()
            })
        }
        wellRow.addArrangedSubview(UIView())
        wellRow.isHidden = header.isEmpty
        addColor.isHidden = header.count >= 3
        addColor.setTitle(header.isEmpty ? "Свои цвета шапки" : "Добавить цвет", for: .normal)
        updatePreview()
        markSelection()
    }
}
