import UIKit

/// A capsule of glass for the navigation bar. It blurs what scrolls under it, takes the tint of the theme and has
/// a bright rim and a sheen along its top edge. A bar made of a few such pieces floats over the page and lets it
/// show between them, instead of being one slab.
final class GlassPill: UIView {
    /// What the capsule holds, in a row.
    let content = UIStackView()
    private let blur = UIVisualEffectView(effect: UIBlurEffect(style: .systemUltraThinMaterial))
    private let sheen = CAGradientLayer()

    init(leading: CGFloat = 2, trailing: CGFloat = 2) {
        super.init(frame: .zero)
        // Bar items lay custom views out with Auto Layout: the capsule needs its size from constraints.
        translatesAutoresizingMaskIntoConstraints = false
        clipsToBounds = true
        layer.cornerCurve = .continuous
        layer.borderWidth = 1
        blur.translatesAutoresizingMaskIntoConstraints = false
        blur.isUserInteractionEnabled = false
        addSubview(blur)
        sheen.startPoint = CGPoint(x: 0.5, y: 0)
        sheen.endPoint = CGPoint(x: 0.5, y: 1)
        layer.addSublayer(sheen)
        content.translatesAutoresizingMaskIntoConstraints = false
        content.axis = .horizontal
        content.alignment = .center
        addSubview(content)
        NSLayoutConstraint.activate([
            blur.topAnchor.constraint(equalTo: topAnchor),
            blur.bottomAnchor.constraint(equalTo: bottomAnchor),
            blur.leadingAnchor.constraint(equalTo: leadingAnchor),
            blur.trailingAnchor.constraint(equalTo: trailingAnchor),
            content.topAnchor.constraint(equalTo: topAnchor),
            content.bottomAnchor.constraint(equalTo: bottomAnchor),
            content.leadingAnchor.constraint(equalTo: leadingAnchor, constant: leading),
            content.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -trailing),
            heightAnchor.constraint(equalToConstant: 40),
        ])
        refresh()
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        layer.cornerRadius = bounds.height / 2
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        sheen.frame = CGRect(x: 0, y: 0, width: bounds.width, height: bounds.height / 2)
        CATransaction.commit()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        refresh()
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        refresh()
    }

    /// Colors that depend on the interface style are resolved by hand: layers do not follow it by themselves.
    private func refresh() {
        let t = traitCollection
        layer.borderColor = Theme.rim.resolvedColor(with: t).cgColor
        blur.contentView.backgroundColor = Theme.glass.resolvedColor(with: t).withAlphaComponent(0.3)
        let white = UIColor.white
        sheen.colors = [white.withAlphaComponent(t.userInterfaceStyle == .dark ? 0.16 : 0.5).cgColor, white.withAlphaComponent(0).cgColor]
    }

    /// The name of the app with its mark.
    static func brand() -> GlassPill {
        let pill = GlassPill(leading: 12, trailing: 16)
        let logo = UIImageView(image: UIImage(named: "Logo"))
        logo.contentMode = .scaleAspectFit
        logo.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([logo.widthAnchor.constraint(equalToConstant: 24), logo.heightAnchor.constraint(equalToConstant: 24)])
        let name = UILabel()
        name.text = "NeoChat"
        name.font = .systemFont(ofSize: 20, weight: .bold)
        name.textColor = Theme.text
        pill.content.spacing = 8
        pill.content.addArrangedSubview(logo)
        pill.content.addArrangedSubview(name)
        pill.isAccessibilityElement = true
        pill.accessibilityLabel = "NeoChat"
        pill.accessibilityTraits = .header
        return pill
    }

    /// A title that stays readable over a page whose top is a picture.
    static func title(_ text: String) -> GlassPill {
        let pill = GlassPill(leading: 18, trailing: 18)
        let name = UILabel()
        name.text = text
        name.font = .systemFont(ofSize: 17, weight: .semibold)
        name.textColor = Theme.text
        pill.content.addArrangedSubview(name)
        pill.isAccessibilityElement = true
        pill.accessibilityLabel = text
        pill.accessibilityTraits = .header
        return pill
    }

    /// Buttons side by side in one capsule.
    static func group(_ buttons: [UIView]) -> GlassPill {
        let pill = GlassPill()
        buttons.forEach(pill.content.addArrangedSubview)
        return pill
    }
}

/// A button for a glass capsule: a symbol, or a title (with a small chevron when it opens a menu).
func glassBarButton(symbol: String? = nil, title: String? = nil, chevron: Bool = false, label: String) -> UIButton {
    var cfg = UIButton.Configuration.plain()
    cfg.baseForegroundColor = Theme.accent
    let weight = UIImage.SymbolConfiguration(pointSize: 17, weight: .semibold)
    if let symbol {
        cfg.image = UIImage(systemName: symbol, withConfiguration: weight)
    }
    if let title {
        cfg.title = title
        cfg.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
            var out = incoming
            out.font = Theme.font(15, .semibold)
            return out
        }
        cfg.contentInsets = NSDirectionalEdgeInsets(top: 0, leading: 14, bottom: 0, trailing: 14)
        if chevron {
            cfg.image = UIImage(systemName: "chevron.down", withConfiguration: UIImage.SymbolConfiguration(pointSize: 11, weight: .bold))
            cfg.imagePlacement = .trailing
            cfg.imagePadding = 5
        }
    } else {
        cfg.contentInsets = .zero
    }
    let button = UIButton(configuration: cfg, primaryAction: nil)
    button.accessibilityLabel = label
    button.translatesAutoresizingMaskIntoConstraints = false
    button.heightAnchor.constraint(equalToConstant: 40).isActive = true
    if title == nil { button.widthAnchor.constraint(equalToConstant: 44).isActive = true }
    return button
}
