import UIKit

/// "Оформление интерфейса": the built-in themes, two slots for own themes and the way to the editor.
final class AppearanceViewController: SheetViewController {
    private var cards: [(id: String, card: ThemeCardView)] = []

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Оформление интерфейса"
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        // An editor may have changed the slots.
        build()
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        // Built-in themes show their day or night variant, so a change of style redraws the cards.
        if isViewLoaded, view.window != nil, previous?.userInterfaceStyle != traitCollection.userInterfaceStyle { build() }
    }

    private func header(_ text: String) -> UILabel {
        let h = label(text.uppercased(), 13, .semibold, color: Theme.muted)
        h.accessibilityTraits = .header
        return h
    }

    private func build() {
        clear()
        cards = []
        let dark = traitCollection.userInterfaceStyle == .dark
        stack.addArrangedSubview(header("Готовые темы"))
        let presets = Appearance.presets
        for first in stride(from: 0, to: presets.count, by: 2) {
            let row = UIStackView()
            row.spacing = 12
            row.distribution = .fillEqually
            for preset in presets[first..<min(first + 2, presets.count)] {
                // Built-in themes follow day and night, so the card shows what the screen would use now.
                let card = ThemeCardView(title: preset.name, backdrop: Appearance.backdrop(preset.id, dark: dark), dark: dark)
                card.addAction(UIAction { [weak self] _ in self?.pick(preset.id) }, for: .touchUpInside)
                cards.append((id: preset.id, card: card))
                row.addArrangedSubview(card)
            }
            stack.addArrangedSubview(row)
        }

        stack.addArrangedSubview(header("Мои темы"))
        let mine = UIStackView()
        mine.spacing = 12
        mine.distribution = .fillEqually
        mine.alignment = .top
        for slot in 0..<Appearance.customSlots {
            let column = UIStackView()
            column.axis = .vertical
            column.spacing = 4
            if let custom = Appearance.custom(slot) {
                let id = Appearance.customId(slot)
                let card = ThemeCardView(title: custom.name, backdrop: Appearance.backdrop(id, dark: true), dark: Appearance.isDarkBase(custom.base))
                card.addAction(UIAction { [weak self] _ in self?.pick(id) }, for: .touchUpInside)
                cards.append((id: id, card: card))
                column.addArrangedSubview(card)
                let change = LinkButton("Изменить")
                change.accessibilityLabel = "Изменить тему «\(custom.name)»"
                change.addAction(UIAction { [weak self] _ in self?.edit(slot) }, for: .touchUpInside)
                let remove = LinkButton("Удалить", color: Theme.warning)
                remove.accessibilityLabel = "Удалить тему «\(custom.name)»"
                remove.addAction(UIAction { [weak self, weak remove] _ in self?.confirmRemove(slot, custom.name, from: remove) }, for: .touchUpInside)
                let tools = UIStackView(arrangedSubviews: [change, remove])
                tools.distribution = .fillEqually
                column.addArrangedSubview(tools)
            } else {
                let card = ThemeCardView(title: "Новая тема", backdrop: nil, dark: false)
                card.addAction(UIAction { [weak self] _ in self?.edit(slot) }, for: .touchUpInside)
                column.addArrangedSubview(card)
            }
            mine.addArrangedSubview(column)
        }
        stack.addArrangedSubview(mine)
        stack.addArrangedSubview(label("Готовые темы следуют режиму «Устройство», «День» или «Ночь» из настроек. Своя тема сама задаёт светлый или тёмный вид по цвету фона. Можно сохранить до двух своих тем. Стекло панелей остаётся прежним: меняются фон и цвет точек.", 13, color: Theme.muted))
        markChosen()
    }

    private func markChosen() {
        for entry in cards { entry.card.isSelected = entry.id == Appearance.current }
    }

    private func pick(_ id: String) {
        Haptics.select()
        Appearance.select(id)
        markChosen()
    }

    private func edit(_ slot: Int) {
        navigationController?.pushViewController(ThemeEditorViewController(slot: slot), animated: !Theme.reducedMotion)
    }

    private func confirmRemove(_ slot: Int, _ name: String, from source: UIView?) {
        let a = UIAlertController(title: "Удалить тему «\(name)»?", message: nil, preferredStyle: .actionSheet)
        a.addAction(UIAlertAction(title: "Удалить", style: .destructive) { [weak self] _ in
            Appearance.deleteCustom(slot)
            self?.build()
        })
        a.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        a.popoverPresentationController?.sourceView = source
        present(a, animated: !Theme.reducedMotion)
    }
}

/// Editor of an own theme: a name, the base color and up to five colored points on a canvas.
final class ThemeEditorViewController: SheetViewController {
    private let slot: Int
    private let nameField = Field("Название")
    private let canvas = BackdropCanvasView()
    private let chips = UIStackView()
    private let well = UIColorWell()
    private let wellTitle = label("", 15)
    private let addButton = PillButton("Добавить точку", primary: false)
    private let removeButton = PillButton("Удалить точку", primary: false)

    init(slot: Int) {
        self.slot = slot
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        let saved = Appearance.custom(slot)
        title = saved == nil ? "Новая тема" : "Изменить тему"
        // Dragging a point must not wait for the scroll view to decide that it is not a scroll.
        view.subviews.compactMap { $0 as? UIScrollView }.first?.delaysContentTouches = false

        nameField.text = saved?.name
        nameField.autocapitalizationType = .sentences
        nameField.addAction(UIAction { [weak self] _ in
            guard let field = self?.nameField, let text = field.text, text.count > 24 else { return }
            field.text = String(text.prefix(24))
        }, for: .editingChanged)
        stack.addArrangedSubview(nameField)

        if let saved {
            canvas.load(base: saved.base, spots: saved.spots)
        } else {
            // A new theme starts from what is on screen now, with its points moved inside the screen.
            let now = Appearance.backdrop(Appearance.current, dark: traitCollection.userInterfaceStyle == .dark)
            canvas.load(base: now.c1, spots: now.spots.map { BackdropSpot(x: min(max($0.x, 0.05), 0.95), y: min(max($0.y, 0.05), 0.95), color: $0.color) })
        }
        canvas.onSelect = { [weak self] _ in self?.sync() }
        stack.addArrangedSubview(canvas)

        // One chip for the base color and one per point; the color well edits the chosen one.
        let strip = UIScrollView()
        strip.showsHorizontalScrollIndicator = false
        chips.axis = .horizontal
        chips.spacing = 6
        chips.translatesAutoresizingMaskIntoConstraints = false
        strip.addSubview(chips)
        NSLayoutConstraint.activate([
            chips.topAnchor.constraint(equalTo: strip.contentLayoutGuide.topAnchor),
            chips.bottomAnchor.constraint(equalTo: strip.contentLayoutGuide.bottomAnchor),
            chips.leadingAnchor.constraint(equalTo: strip.contentLayoutGuide.leadingAnchor),
            chips.trailingAnchor.constraint(equalTo: strip.contentLayoutGuide.trailingAnchor),
            chips.heightAnchor.constraint(equalTo: strip.frameLayoutGuide.heightAnchor),
            strip.heightAnchor.constraint(equalToConstant: 44),
        ])
        stack.addArrangedSubview(strip)

        well.supportsAlpha = false
        well.title = "Цвет"
        well.accessibilityLabel = "Выбрать цвет"
        well.addAction(UIAction { [weak self] _ in self?.picked() }, for: .valueChanged)
        well.translatesAutoresizingMaskIntoConstraints = false
        well.widthAnchor.constraint(equalToConstant: 44).isActive = true
        well.heightAnchor.constraint(equalToConstant: 44).isActive = true
        let colorRow = UIStackView(arrangedSubviews: [wellTitle, well])
        colorRow.alignment = .center
        colorRow.spacing = 12
        stack.addArrangedSubview(colorRow)

        addButton.addAction(UIAction { [weak self] _ in self?.canvas.addSpot(x: 0.5, y: 0.5) }, for: .touchUpInside)
        removeButton.addAction(UIAction { [weak self] _ in self?.canvas.removeSelected() }, for: .touchUpInside)
        let tools = UIStackView(arrangedSubviews: [addButton, removeButton])
        tools.spacing = 10
        tools.distribution = .fillEqually
        stack.addArrangedSubview(tools)
        muted("Нажмите на фон, чтобы поставить точку, и перетащите её. Тёмный или светлый вид интерфейса выбирается по цвету фона.")

        let cancel = PillButton("Отмена", primary: false)
        let save = PillButton("Сохранить")
        cancel.addAction(UIAction { [weak self] _ in self?.close() }, for: .touchUpInside)
        save.addAction(UIAction { [weak self] _ in self?.save() }, for: .touchUpInside)
        let buttons = UIStackView(arrangedSubviews: [cancel, save])
        buttons.spacing = 10
        buttons.distribution = .fillEqually
        stack.addArrangedSubview(buttons)
        sync()
    }

    private func close() {
        navigationController?.popViewController(animated: !Theme.reducedMotion)
    }

    private func save() {
        let typed = (nameField.text ?? "").trimmingCharacters(in: .whitespaces)
        Appearance.saveCustom(slot, CustomTheme(name: typed.isEmpty ? "Моя тема \(slot + 1)" : typed, base: canvas.base, spots: canvas.spots))
        close()
    }

    /// Chips, buttons and the color well follow the selection.
    private func sync() {
        chips.arrangedSubviews.forEach { $0.removeFromSuperview() }
        chips.addArrangedSubview(chip("Фон", "Цвет фона", canvas.base, index: -1))
        for (i, spot) in canvas.spots.enumerated() {
            chips.addArrangedSubview(chip("\(i + 1)", "Точка \(i + 1)", spot.color, index: i))
        }
        addButton.isEnabled = canvas.spots.count < Appearance.maxSpots
        removeButton.isEnabled = canvas.selected >= 0
        let chosen = canvas.selected >= 0 && canvas.selected < canvas.spots.count
        well.selectedColor = Theme.rgb(chosen ? canvas.spots[canvas.selected].color : canvas.base)
        wellTitle.text = chosen ? "Цвет точки \(canvas.selected + 1)" : "Цвет фона"
    }

    private func chip(_ title: String, _ accessibility: String, _ color: UInt32, index: Int) -> SwatchChipView {
        let chip = SwatchChipView(title: title, accessibility: accessibility, color: Theme.rgb(color))
        chip.isSelected = canvas.selected == index
        chip.addAction(UIAction { [weak self] _ in self?.canvas.select(index) }, for: .touchUpInside)
        return chip
    }

    private func picked() {
        guard let color = well.selectedColor else { return }
        let hex = hexOf(color)
        canvas.setColor(hex)
        // Keep the chip in step without rebuilding the row under the user's hand.
        let index = canvas.selected + 1
        if index < chips.arrangedSubviews.count, let chip = chips.arrangedSubviews[index] as? SwatchChipView {
            chip.setColor(Theme.rgb(hex))
        }
    }
}

private func hexOf(_ color: UIColor) -> UInt32 {
    var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
    color.getRed(&r, green: &g, blue: &b, alpha: &a)
    func byte(_ v: CGFloat) -> UInt32 { UInt32((min(max(v, 0), 1) * 255).rounded()) }
    return byte(r) << 16 | byte(g) << 8 | byte(b)
}

/// Black or white, whichever reads on the color.
private func readable(on color: UInt32) -> UIColor {
    let r = Double((color >> 16) & 0xFF), g = Double((color >> 8) & 0xFF), b = Double(color & 0xFF)
    return (0.299 * r + 0.587 * g + 0.114 * b) / 255 > 0.6 ? Theme.rgb(0x101820) : UIColor.white
}

/// A theme in the list: its backdrop, two mock bubbles and the name. Without a backdrop it is an empty slot.
final class ThemeCardView: UIControl {
    private let titleLabel = label("", 14, .semibold)
    private let badge = UIImageView(image: UIImage(systemName: "checkmark.circle.fill"))
    private let dashes = CAShapeLayer()
    private let empty: Bool

    init(title: String, backdrop: Backdrop?, dark: Bool) {
        empty = backdrop == nil
        super.init(frame: .zero)
        layer.cornerRadius = 16
        layer.cornerCurve = .continuous
        clipsToBounds = true
        heightAnchor.constraint(equalToConstant: 124).isActive = true
        isAccessibilityElement = true
        accessibilityTraits = .button
        accessibilityLabel = backdrop == nil ? "Создать тему" : title
        titleLabel.text = title
        titleLabel.numberOfLines = 1
        if let backdrop {
            build(backdrop, dark: dark)
        } else {
            buildEmpty()
        }
        update()
    }

    required init?(coder: NSCoder) { fatalError() }

    private func build(_ backdrop: Backdrop, dark: Bool) {
        let preview = BackdropView()
        preview.show(backdrop, animated: false)
        // Two mock bubbles show how text and the accent sit on this backdrop.
        let incoming = UIView()
        incoming.backgroundColor = dark ? Theme.rgb(0x1C2C46, 0.84) : Theme.rgb(0xF9FCFF, 0.78)
        let outgoing = UIView()
        outgoing.backgroundColor = Theme.outgoing
        for bubble in [incoming, outgoing] { bubble.layer.cornerRadius = 9 }
        let strip = UIView()
        strip.backgroundColor = dark ? Theme.rgb(0x1A2A44, 0.92) : Theme.rgb(0xFAFDFF, 0.94)
        titleLabel.textColor = dark ? Theme.rgb(0xF2F7FF) : Theme.rgb(0x182C46)
        badge.tintColor = Theme.accent
        let layers: [UIView] = [preview, incoming, outgoing, strip]
        for v in layers {
            v.isUserInteractionEnabled = false
            v.translatesAutoresizingMaskIntoConstraints = false
            addSubview(v)
        }
        let inner: [UIView] = [titleLabel, badge]
        for v in inner {
            v.translatesAutoresizingMaskIntoConstraints = false
            strip.addSubview(v)
        }
        NSLayoutConstraint.activate([
            preview.topAnchor.constraint(equalTo: topAnchor), preview.bottomAnchor.constraint(equalTo: bottomAnchor),
            preview.leadingAnchor.constraint(equalTo: leadingAnchor), preview.trailingAnchor.constraint(equalTo: trailingAnchor),
            incoming.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12), incoming.topAnchor.constraint(equalTo: topAnchor, constant: 12),
            incoming.widthAnchor.constraint(equalTo: widthAnchor, multiplier: 0.52), incoming.heightAnchor.constraint(equalToConstant: 18),
            outgoing.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -12), outgoing.topAnchor.constraint(equalTo: topAnchor, constant: 36),
            outgoing.widthAnchor.constraint(equalTo: widthAnchor, multiplier: 0.4), outgoing.heightAnchor.constraint(equalToConstant: 18),
            strip.leadingAnchor.constraint(equalTo: leadingAnchor), strip.trailingAnchor.constraint(equalTo: trailingAnchor),
            strip.bottomAnchor.constraint(equalTo: bottomAnchor), strip.heightAnchor.constraint(equalToConstant: 34),
            titleLabel.leadingAnchor.constraint(equalTo: strip.leadingAnchor, constant: 12), titleLabel.centerYAnchor.constraint(equalTo: strip.centerYAnchor),
            titleLabel.trailingAnchor.constraint(lessThanOrEqualTo: badge.leadingAnchor, constant: -6),
            badge.trailingAnchor.constraint(equalTo: strip.trailingAnchor, constant: -8), badge.centerYAnchor.constraint(equalTo: strip.centerYAnchor),
            badge.widthAnchor.constraint(equalToConstant: 22), badge.heightAnchor.constraint(equalToConstant: 22),
        ])
    }

    private func buildEmpty() {
        let plus = UIImageView(image: UIImage(systemName: "plus", withConfiguration: UIImage.SymbolConfiguration(pointSize: 22, weight: .semibold)))
        plus.tintColor = Theme.accent
        titleLabel.textColor = Theme.text
        titleLabel.textAlignment = .center
        let column = UIStackView(arrangedSubviews: [plus, titleLabel])
        column.axis = .vertical
        column.spacing = 8
        column.alignment = .center
        column.isUserInteractionEnabled = false
        column.translatesAutoresizingMaskIntoConstraints = false
        addSubview(column)
        NSLayoutConstraint.activate([column.centerXAnchor.constraint(equalTo: centerXAnchor), column.centerYAnchor.constraint(equalTo: centerYAnchor)])
        dashes.fillColor = nil
        dashes.lineWidth = 1.5
        dashes.lineDashPattern = [6, 5]
        layer.addSublayer(dashes)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        dashes.frame = bounds
        dashes.path = UIBezierPath(roundedRect: bounds.insetBy(dx: 1, dy: 1), cornerRadius: 15).cgPath
    }

    private func update() {
        if empty {
            dashes.strokeColor = Theme.muted.withAlphaComponent(0.5).resolvedColor(with: traitCollection).cgColor
            return
        }
        layer.borderWidth = isSelected ? 2.5 : 1
        layer.borderColor = (isSelected ? Theme.accent : Theme.muted.withAlphaComponent(0.4)).resolvedColor(with: traitCollection).cgColor
        badge.isHidden = !isSelected
        accessibilityValue = isSelected ? "Выбрана" : nil
    }

    override var isSelected: Bool {
        didSet { update() }
    }

    override var isHighlighted: Bool {
        didSet { UIView.animate(withDuration: Theme.ms(0.14)) { self.alpha = self.isHighlighted ? 0.85 : 1 } }
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        update()
    }
}

/// A target of the color well: the base color or one point.
final class SwatchChipView: UIControl {
    private let dot = UIView()

    init(title: String, accessibility: String, color: UIColor) {
        super.init(frame: .zero)
        layer.cornerRadius = 14
        layer.cornerCurve = .continuous
        dot.backgroundColor = color
        dot.layer.cornerRadius = 9
        dot.layer.borderWidth = 1
        dot.layer.borderColor = Theme.muted.withAlphaComponent(0.5).cgColor
        dot.widthAnchor.constraint(equalToConstant: 18).isActive = true
        dot.heightAnchor.constraint(equalToConstant: 18).isActive = true
        let row = UIStackView(arrangedSubviews: [dot, label(title, 14, .semibold)])
        row.spacing = 8
        row.alignment = .center
        row.isUserInteractionEnabled = false
        row.translatesAutoresizingMaskIntoConstraints = false
        addSubview(row)
        NSLayoutConstraint.activate([
            row.topAnchor.constraint(equalTo: topAnchor, constant: 4), row.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -4),
            row.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12), row.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -14),
            heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
        ])
        isAccessibilityElement = true
        accessibilityTraits = .button
        accessibilityLabel = accessibility
        update()
    }

    required init?(coder: NSCoder) { fatalError() }

    func setColor(_ color: UIColor) { dot.backgroundColor = color }

    private func update() {
        layer.borderWidth = isSelected ? 2 : 1
        layer.borderColor = (isSelected ? Theme.accent : Theme.muted.withAlphaComponent(0.4)).resolvedColor(with: traitCollection).cgColor
        backgroundColor = isSelected ? Theme.glassStrong : .clear
        accessibilityValue = isSelected ? "Выбрано" : nil
    }

    override var isSelected: Bool {
        didSet { update() }
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        update()
    }
}

/// The preview of an own theme: a flat base color with its points. Touch a free place to add a point and
/// drag it; touch a point to select it.
final class BackdropCanvasView: UIControl {
    private(set) var base: UInt32 = 0x101A2E
    private(set) var spots: [BackdropSpot] = []
    private(set) var selected = -1
    var onSelect: ((Int) -> Void)?
    private let preview = BackdropView()
    private var handles: [UILabel] = []
    private var drag = -1

    override init(frame: CGRect) {
        super.init(frame: frame)
        layer.cornerRadius = 18
        layer.cornerCurve = .continuous
        layer.borderWidth = 1
        clipsToBounds = true
        preview.translatesAutoresizingMaskIntoConstraints = false
        addSubview(preview)
        NSLayoutConstraint.activate([
            preview.topAnchor.constraint(equalTo: topAnchor), preview.bottomAnchor.constraint(equalTo: bottomAnchor),
            preview.leadingAnchor.constraint(equalTo: leadingAnchor), preview.trailingAnchor.constraint(equalTo: trailingAnchor),
            heightAnchor.constraint(equalToConstant: 190),
        ])
        isAccessibilityElement = true
        accessibilityLabel = "Фон: нажмите, чтобы добавить точку, перетащите, чтобы передвинуть"
        layer.borderColor = Theme.rim.resolvedColor(with: traitCollection).cgColor
    }

    required init?(coder: NSCoder) { fatalError() }

    func load(base: UInt32, spots: [BackdropSpot]) {
        self.base = base
        self.spots = spots
        selected = -1
        render(rebuild: true)
    }

    func select(_ index: Int) {
        selected = index
        placeHandles()
        onSelect?(index)
    }

    /// Recolors the base (nothing selected) or the selected point.
    func setColor(_ color: UInt32) {
        if selected < 0 {
            base = color
        } else if selected < spots.count {
            spots[selected].color = color
        }
        render(rebuild: false)
    }

    func addSpot(x: CGFloat, y: CGFloat) {
        guard spots.count < Appearance.maxSpots else { return }
        spots.append(BackdropSpot(x: x, y: y, color: freshColor()))
        selected = spots.count - 1
        render(rebuild: true)
        onSelect?(selected)
    }

    func removeSelected() {
        guard selected >= 0, selected < spots.count else { return }
        spots.remove(at: selected)
        selected = spots.isEmpty ? -1 : min(selected, spots.count - 1)
        render(rebuild: true)
        onSelect?(selected)
    }

    /// A color no point uses yet.
    private func freshColor() -> UInt32 {
        let colors: [UInt32] = [0x6D8DFF, 0xFF7A9C, 0x5FD4B5, 0xFFC76B, 0xB48CFF]
        return colors.first { color in !spots.contains { $0.color == color } } ?? colors[0]
    }

    private func render(rebuild: Bool) {
        preview.show(Backdrop(c0: base, c1: base, c2: base, spots: spots), animated: false)
        if rebuild || handles.count != spots.count { rebuildHandles() }
        placeHandles()
    }

    private func rebuildHandles() {
        handles.forEach { $0.removeFromSuperview() }
        handles = (0..<spots.count).map { _ -> UILabel in
            let h = UILabel()
            h.textAlignment = .center
            h.font = UIFont.systemFont(ofSize: 12, weight: .bold)
            h.clipsToBounds = true
            h.layer.borderColor = UIColor.white.cgColor
            h.isUserInteractionEnabled = false
            addSubview(h)
            return h
        }
    }

    // A point on the very edge is drawn inside the canvas, so its handle is never cut off.
    private func position(of spot: BackdropSpot) -> CGPoint {
        let margin: CGFloat = 17
        return CGPoint(x: min(max(spot.x * bounds.width, margin), max(margin, bounds.width - margin)),
                       y: min(max(spot.y * bounds.height, margin), max(margin, bounds.height - margin)))
    }

    private func placeHandles() {
        for (i, spot) in spots.enumerated() where i < handles.count {
            let h = handles[i]
            let on = i == selected
            let size: CGFloat = on ? 32 : 26
            h.bounds = CGRect(x: 0, y: 0, width: size, height: size)
            h.center = position(of: spot)
            h.layer.cornerRadius = size / 2
            h.layer.borderWidth = on ? 3 : 2
            h.backgroundColor = Theme.rgb(spot.color)
            h.textColor = readable(on: spot.color)
            h.text = "\(i + 1)"
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        placeHandles()
    }

    private func hit(_ p: CGPoint) -> Int {
        for i in stride(from: spots.count - 1, through: 0, by: -1) {
            let c = position(of: spots[i])
            if hypot(p.x - c.x, p.y - c.y) <= 24 { return i }
        }
        return -1
    }

    private func fraction(of p: CGPoint) -> CGPoint {
        CGPoint(x: min(max(p.x / max(bounds.width, 1), 0), 1), y: min(max(p.y / max(bounds.height, 1), 0), 1))
    }

    override func beginTracking(_ touch: UITouch, with event: UIEvent?) -> Bool {
        let p = touch.location(in: self)
        drag = hit(p)
        if drag >= 0 {
            select(drag)
        } else if spots.count < Appearance.maxSpots {
            // A new point follows the finger at once.
            let f = fraction(of: p)
            addSpot(x: f.x, y: f.y)
            drag = selected
        }
        return true
    }

    override func continueTracking(_ touch: UITouch, with event: UIEvent?) -> Bool {
        if drag >= 0, drag < spots.count {
            let f = fraction(of: touch.location(in: self))
            spots[drag].x = f.x
            spots[drag].y = f.y
            render(rebuild: false)
        }
        return true
    }

    override func endTracking(_ touch: UITouch?, with event: UIEvent?) { drag = -1 }

    override func cancelTracking(with event: UIEvent?) { drag = -1 }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        layer.borderColor = Theme.rim.resolvedColor(with: traitCollection).cgColor
    }
}
