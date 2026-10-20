import LocalAuthentication
import UIKit

extension Notification.Name {
    static let prefsChanged = Notification.Name("NeoChatPrefsChanged")
    static let typingChanged = Notification.Name("NeoChatTyping")
}

/// Preferences of this device only.
enum Prefs {
    private static let d = UserDefaults.standard
    static func bool(_ k: String, _ def: Bool) -> Bool { d.object(forKey: k) as? Bool ?? def }
    static func set(_ k: String, _ v: Any) {
        d.set(v, forKey: k)
        NotificationCenter.default.post(name: .prefsChanged, object: nil)
    }
    static var textSize: CGFloat { CGFloat(d.object(forKey: "text_size") as? Double ?? 17) }
    static var bigEmoji: Bool { bool("big_emoji", true) }
    static var autoload: Bool { bool("autoload", true) }
    static var chatSort: String { d.string(forKey: "chat_sort") ?? "activity" }
    static var archiveSort: String { d.string(forKey: "archive_sort") ?? "activity" }
}

let chatSorts = [("activity", "По активности"), ("unread", "Сначала непрочитанные"), ("name", "По названию")]

func memberCount(_ n: Int, channel: Bool = false) -> String {
    let m10 = n % 10, m100 = n % 100
    let w: String
    if m10 == 1 && m100 != 11 { w = channel ? "подписчик" : "участник" }
    else if (2...4).contains(m10) && !(12...14).contains(m100) { w = channel ? "подписчика" : "участника" }
    else { w = channel ? "подписчиков" : "участников" }
    return "\(n) \(w)"
}

var tzOffsetMinutes: Int { TimeZone.current.secondsFromGMT() / 60 }

/// "печатает…" hints: shown for 6 s after the last one.
enum Typing {
    private static var until: [String: (Date, [String: String])] = [:]

    static func onEvent(_ ev: JSON) {
        let conv = ev.str("conversation_id")
        var names = until[conv].flatMap { $0.0 > Date() ? $0.1 : nil } ?? [:]
        names[ev.str("user_id")] = ev.str("name")
        until[conv] = (Date().addingTimeInterval(6), names)
        NotificationCenter.default.post(name: .typingChanged, object: conv)
        DispatchQueue.main.asyncAfter(deadline: .now() + 6.05) { NotificationCenter.default.post(name: .typingChanged, object: conv) }
    }

    /// A message from someone ends the hint at once.
    static func clear(_ conv: String) {
        if until.removeValue(forKey: conv) != nil { NotificationCenter.default.post(name: .typingChanged, object: conv) }
    }

    static func text(_ conv: String, group: Bool) -> String? {
        guard let e = until[conv], e.0 > Date(), !e.1.isEmpty else { return nil }
        if !group { return "печатает…" }
        let names = e.1.values.map { n -> String in
            let f = n.split(separator: " ").first.map(String.init) ?? ""
            return f.isEmpty ? "Кто-то" : f
        }
        switch names.count {
        case 1: return "\(names[0]) печатает…"
        case 2: return "\(names[0]) и \(names[1]) печатают…"
        default: return "Несколько человек печатают…"
        }
    }
}

// MARK: - Emoji picker

final class EmojiPickerViewController: UIViewController, UICollectionViewDataSource, UICollectionViewDelegate {
    private let pick: (String) -> Void
    private var grid: UICollectionView!

    init(title: String = "Эмодзи", pick: @escaping (String) -> Void) {
        self.pick = pick
        super.init(nibName: nil, bundle: nil)
        self.title = title
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.addSubview(BackgroundView(frame: view.bounds))
        navigationItem.rightBarButtonItem = UIBarButtonItem(systemItem: .close, primaryAction: UIAction { [weak self] _ in
            self?.dismiss(animated: !Theme.reducedMotion)
        })
        let l = UICollectionViewFlowLayout()
        l.itemSize = CGSize(width: 44, height: 44)
        l.minimumInteritemSpacing = 2
        l.minimumLineSpacing = 2
        l.headerReferenceSize = CGSize(width: 100, height: 36)
        l.sectionInset = UIEdgeInsets(top: 0, left: 12, bottom: 8, right: 12)
        grid = UICollectionView(frame: view.bounds, collectionViewLayout: l)
        grid.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        grid.backgroundColor = .clear
        grid.dataSource = self
        grid.delegate = self
        grid.register(EmojiCell.self, forCellWithReuseIdentifier: "e")
        grid.register(EmojiHeader.self, forSupplementaryViewOfKind: UICollectionView.elementKindSectionHeader, withReuseIdentifier: "h")
        view.addSubview(grid)
    }

    func numberOfSections(in collectionView: UICollectionView) -> Int { Emoji.categories.count }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int { Emoji.categories[section].1.count }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "e", for: indexPath) as! EmojiCell
        let e = Emoji.categories[indexPath.section].1[indexPath.item]
        cell.l.text = e
        cell.accessibilityLabel = e
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, viewForSupplementaryElementOfKind kind: String, at indexPath: IndexPath) -> UICollectionReusableView {
        let h = collectionView.dequeueReusableSupplementaryView(ofKind: kind, withReuseIdentifier: "h", for: indexPath) as! EmojiHeader
        h.l.text = Emoji.categories[indexPath.section].0
        return h
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        let e = Emoji.categories[indexPath.section].1[indexPath.item]
        let pick = self.pick
        dismiss(animated: !Theme.reducedMotion) { pick(e) }
    }
}

final class EmojiCell: UICollectionViewCell {
    let l = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        l.font = .systemFont(ofSize: 30)
        l.textAlignment = .center
        l.frame = contentView.bounds
        l.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        contentView.addSubview(l)
        isAccessibilityElement = true
        accessibilityTraits = .button
    }

    required init?(coder: NSCoder) { fatalError() }

    override var isHighlighted: Bool {
        didSet { contentView.backgroundColor = isHighlighted ? Theme.glass : .clear; contentView.layer.cornerRadius = 12 }
    }
}

final class EmojiHeader: UICollectionReusableView {
    let l = label("", 13, .semibold, color: Theme.muted)

    override init(frame: CGRect) {
        super.init(frame: frame)
        l.frame = bounds.insetBy(dx: 14, dy: 0)
        l.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        l.accessibilityTraits = .header
        addSubview(l)
    }

    required init?(coder: NSCoder) { fatalError() }
}

// MARK: - Message menu: quick reactions and actions on a tap

struct MenuAction {
    let title: String
    let icon: String
    var destructive = false
    let run: () -> Void
}

final class MessageMenuViewController: UIViewController {
    private let mine: String?
    private let showReactions: Bool
    private let actions: [MenuAction]
    private let react: (String) -> Void
    private let more: () -> Void
    private let stack = UIStackView()

    init(reactions: Bool, mine: String?, actions: [MenuAction], react: @escaping (String) -> Void, more: @escaping () -> Void) {
        showReactions = reactions
        self.mine = mine
        self.actions = actions
        self.react = react
        self.more = more
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        stack.axis = .vertical
        stack.spacing = 0
        stack.translatesAutoresizingMaskIntoConstraints = false
        if showReactions {
            let row = UIStackView()
            row.spacing = 2
            row.isLayoutMarginsRelativeArrangement = true
            row.layoutMargins = UIEdgeInsets(top: 8, left: 8, bottom: 8, right: 8)
            for e in Emoji.quick {
                let b = UIButton(type: .system)
                b.setTitle(e, for: .normal)
                b.titleLabel?.font = .systemFont(ofSize: 27)
                b.accessibilityLabel = e == mine ? "Убрать реакцию \(e)" : "Реакция \(e)"
                if e == mine {
                    b.backgroundColor = Theme.action.withAlphaComponent(0.3)
                    b.layer.cornerRadius = 21
                }
                b.widthAnchor.constraint(equalToConstant: 42).isActive = true
                b.heightAnchor.constraint(equalToConstant: 42).isActive = true
                b.addAction(UIAction { [weak self] _ in self?.close { self?.react(e) } }, for: .touchUpInside)
                row.addArrangedSubview(b)
            }
            let plus = UIButton(type: .system)
            plus.setImage(UIImage(systemName: "plus", withConfiguration: UIImage.SymbolConfiguration(pointSize: 17, weight: .semibold)), for: .normal)
            plus.tintColor = Theme.text
            plus.backgroundColor = Theme.glass
            plus.layer.cornerRadius = 19
            plus.accessibilityLabel = "Все эмодзи"
            plus.widthAnchor.constraint(equalToConstant: 38).isActive = true
            plus.heightAnchor.constraint(equalToConstant: 38).isActive = true
            plus.addAction(UIAction { [weak self] _ in self?.close { self?.more() } }, for: .touchUpInside)
            let wrap = UIStackView(arrangedSubviews: [plus])
            wrap.alignment = .center
            row.addArrangedSubview(wrap)
            stack.addArrangedSubview(row)
            if !actions.isEmpty {
                let line = UIView()
                line.backgroundColor = Theme.muted.withAlphaComponent(0.25)
                line.heightAnchor.constraint(equalToConstant: 1 / UIScreen.main.scale).isActive = true
                stack.addArrangedSubview(line)
            }
        }
        for a in actions {
            var cfg = UIButton.Configuration.plain()
            cfg.image = UIImage(systemName: a.icon)
            cfg.imagePadding = 14
            cfg.title = a.title
            cfg.baseForegroundColor = a.destructive ? .systemRed : Theme.text
            cfg.contentInsets = NSDirectionalEdgeInsets(top: 13, leading: 16, bottom: 13, trailing: 16)
            cfg.titleLineBreakMode = .byTruncatingTail
            let b = UIButton(configuration: cfg, primaryAction: UIAction { [weak self] _ in self?.close(a.run) })
            b.contentHorizontalAlignment = .leading
            stack.addArrangedSubview(b)
        }
        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)
        scroll.addSubview(stack)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: scroll.frameLayoutGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: scroll.frameLayoutGuide.trailingAnchor),
        ])
        let size = stack.systemLayoutSizeFitting(CGSize(width: 340, height: UIView.layoutFittingCompressedSize.height))
        preferredContentSize = CGSize(width: max(showReactions ? 0 : 260, min(360, size.width)), height: min(520, size.height))
    }

    private func close(_ then: @escaping () -> Void) {
        dismiss(animated: !Theme.reducedMotion, completion: then)
    }
}

// MARK: - Profile QR code

/// QR with round dots, rounded eyes and the avatar in the middle (error correction Q).
final class PrettyQrView: UIView {
    private var rows: [[Bool]] = []
    private let avatar = AvatarView(size: 54)
    var dot = Theme.rgb(0x16233C)

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        addSubview(avatar)
        NSLayoutConstraint.activate([avatar.centerXAnchor.constraint(equalTo: centerXAnchor), avatar.centerYAnchor.constraint(equalTo: centerYAnchor)])
        isAccessibilityElement = true
        accessibilityLabel = "QR-код профиля"
    }

    required init?(coder: NSCoder) { fatalError() }

    func set(_ r: [String], name: String, seed: String) {
        rows = r.map { $0.map { $0 == "1" } }
        avatar.set(name, seed: seed)
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        let n = rows.count
        guard n > 0, let ctx = UIGraphicsGetCurrentContext() else { return }
        let cell = bounds.width / CGFloat(n)
        var hole = Int(ceil(66 / cell))
        if (n - hole) % 2 == 1 { hole += 1 }
        let h0 = (n - hole) / 2, h1 = h0 + hole
        func eye(_ x: Int, _ y: Int) -> Bool { (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7) }
        dot.setFill()
        dot.setStroke()
        for y in 0..<n {
            for x in 0..<n where rows[y][x] && !eye(x, y) && !(x >= h0 && x < h1 && y >= h0 && y < h1) {
                ctx.fillEllipse(in: CGRect(x: CGFloat(x) * cell + cell * 0.07, y: CGFloat(y) * cell + cell * 0.07, width: cell * 0.86, height: cell * 0.86))
            }
        }
        for (ex, ey) in [(0, 0), (n - 7, 0), (0, n - 7)] {
            let l = CGFloat(ex) * cell, t = CGFloat(ey) * cell
            let outer = UIBezierPath(roundedRect: CGRect(x: l + cell / 2, y: t + cell / 2, width: 6 * cell, height: 6 * cell), cornerRadius: cell * 2)
            outer.lineWidth = cell
            outer.stroke()
            UIBezierPath(roundedRect: CGRect(x: l + 2 * cell, y: t + 2 * cell, width: 3 * cell, height: 3 * cell), cornerRadius: cell).fill()
        }
    }
}

/// Telegram-like card: a soft accent gradient around a white plate with the code and @username.
final class QrCardView: UIView {
    let qr = PrettyQrView()
    let handle = label("", 19, .bold, color: Theme.rgb(0x16233C))
    private let grad = CAGradientLayer()
    private let plate = UIView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        layer.cornerRadius = 30
        layer.cornerCurve = .continuous
        clipsToBounds = true
        grad.colors = [Theme.rgb(0x4169CF).cgColor, Theme.rgb(0x7B5CD6).cgColor, Theme.rgb(0x2FA5B5).cgColor]
        grad.startPoint = CGPoint(x: 0, y: 0)
        grad.endPoint = CGPoint(x: 1, y: 1)
        layer.addSublayer(grad)
        plate.backgroundColor = .white
        plate.layer.cornerRadius = 26
        plate.layer.cornerCurve = .continuous
        handle.textAlignment = .center
        handle.numberOfLines = 1
        handle.adjustsFontSizeToFitWidth = true
        let col = UIStackView(arrangedSubviews: [qr, handle])
        col.axis = .vertical
        col.spacing = 12
        col.alignment = .center
        for v in [plate, col] as [UIView] { v.translatesAutoresizingMaskIntoConstraints = false }
        addSubview(plate)
        plate.addSubview(col)
        NSLayoutConstraint.activate([
            qr.widthAnchor.constraint(equalToConstant: 236), qr.heightAnchor.constraint(equalToConstant: 236),
            handle.widthAnchor.constraint(lessThanOrEqualToConstant: 236),
            col.topAnchor.constraint(equalTo: plate.topAnchor, constant: 20), col.bottomAnchor.constraint(equalTo: plate.bottomAnchor, constant: -18),
            col.leadingAnchor.constraint(equalTo: plate.leadingAnchor, constant: 20), col.trailingAnchor.constraint(equalTo: plate.trailingAnchor, constant: -20),
            plate.centerXAnchor.constraint(equalTo: centerXAnchor),
            plate.topAnchor.constraint(equalTo: topAnchor, constant: 34), plate.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -34),
        ])
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        grad.frame = bounds
    }

    func image() -> UIImage {
        UIGraphicsImageRenderer(bounds: bounds).image { layer.render(in: $0.cgContext) }
    }
}

final class MyQrViewController: SheetViewController {
    private let card = QrCardView()
    private var link = ""

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Мой QR-код"
        stack.addArrangedSubview(card)
        muted("Покажите код собеседнику: он отсканирует его в NeoChat и сразу добавит вас в контакты. Код содержит отпечаток вашего ключа, поэтому ключ сразу считается сверенным.")
        let share = PillButton("Поделиться")
        share.addAction(UIAction { [weak self] _ in self?.share() }, for: .touchUpInside)
        stack.addArrangedSubview(share)
        let scan = PillButton("Сканировать QR-код", primary: false)
        scan.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            ContactQr.scan(from: self)
        }, for: .touchUpInside)
        stack.addArrangedSubview(scan)
        Core.shared.call("my_qr") { [weak self] r in
            guard let self else { return }
            guard case .success(let v) = r, let o = v as? JSON else {
                if case .failure(let e) = r { self.toast(e.message) }
                return
            }
            self.link = o.str("link")
            self.card.handle.text = "@" + o.str("username")
            Core.shared.call("status") { sr in
                guard case .success(let sv) = sr, let s = sv as? JSON else { return }
                let name = s.str("display_name").isEmpty ? s.str("username") : s.str("display_name")
                self.card.qr.set(o["qr_rows"] as? [String] ?? [], name: name, seed: s.str("user_id"))
            }
        }
    }

    private func share() {
        guard !link.isEmpty else { return }
        let vc = UIActivityViewController(activityItems: [card.image(), link], applicationActivities: nil)
        vc.popoverPresentationController?.sourceView = card
        present(vc, animated: !Theme.reducedMotion)
    }
}

/// Adding a contact from a profile QR: the core checks the key fingerprint, then the chat opens.
enum ContactQr {
    static let prefix = "neochat://u/"

    static func scan(from host: UIViewController) {
        QrScannerViewController.present(from: host, prefix: prefix, hint: "Наведите камеру на QR-код профиля NeoChat",
                                        wrong: "Это не QR-код контакта NeoChat") { link in add(link, host: host) }
    }

    static func add(_ link: String, host: UIViewController) {
        Core.shared.call("add_contact_qr", ["link": link]) { r in
            switch r {
            case .failure(let e): host.toast(e.message)
            case .success(let v):
                let uid = (v as? JSON)?.str("user_id") ?? ""
                host.toast("Контакт добавлен, ключ сверен")
                Core.shared.call("open_direct", ["user_id": uid]) { or in
                    guard case .success(let ov) = or, let id = (ov as? JSON)?.str("id") else { return }
                    let open = { NotificationCenter.default.post(name: .openConversation, object: nil, userInfo: ["id": id]) }
                    if host.presentingViewController != nil { host.dismiss(animated: !Theme.reducedMotion, completion: open) } else { open() }
                }
            }
        }
    }
}

// MARK: - Passcode lock for the app and the archive

enum AppLock {
    static var hasPasscode: Bool { Prefs.bool("has_passcode", false) }
    static var lockApp: Bool { Prefs.bool("lock_app", true) }
    static var lockArchive: Bool { Prefs.bool("lock_archive", false) }
    static var useBio: Bool { Prefs.bool("lock_bio", true) }
    /// Seconds in the background before the app locks again.
    static var lockAfter: Int { UserDefaults.standard.object(forKey: "lock_after") as? Int ?? 0 }
    static let afterChoices = [(0, "Сразу"), (60, "Через 1 мин"), (300, "Через 5 мин"), (3600, "Через 1 час")]

    private static var window: UIWindow?
    private static var cover: UIWindow?
    private static var backgroundAt: Date?
    private static var unlocked = false

    static var biometryName: String? {
        let c = LAContext()
        var e: NSError?
        guard c.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &e) else { return nil }
        return c.biometryType == .faceID ? "Face ID" : "Touch ID"
    }

    private static var appLocked: Bool { hasPasscode && lockApp }

    static func launched(_ scene: UIWindowScene) {
        if appLocked { lock(scene) } else { unlocked = true }
    }

    static func backgrounded(_ scene: UIWindowScene) {
        backgroundAt = Date()
        if appLocked && lockAfter == 0 { lock(scene) }
    }

    static func foreground(_ scene: UIWindowScene) {
        guard appLocked else { return }
        let away = backgroundAt.map { Date().timeIntervalSince($0) } ?? .infinity
        if !unlocked || away >= Double(lockAfter) { lock(scene) }
    }

    /// Hides the content in the app switcher when asked to.
    static func resigned(_ scene: UIWindowScene) {
        guard Prefs.bool("secure_screen", false), cover == nil, window == nil else { return }
        let w = UIWindow(windowScene: scene)
        w.windowLevel = .alert + 2
        Theme.apply(to: w, animated: false)
        let vc = UIViewController()
        vc.view.addSubview(BackgroundView(frame: vc.view.bounds))
        let logo = UIImageView(image: UIImage(named: "Logo"))
        logo.translatesAutoresizingMaskIntoConstraints = false
        vc.view.addSubview(logo)
        NSLayoutConstraint.activate([logo.centerXAnchor.constraint(equalTo: vc.view.centerXAnchor), logo.centerYAnchor.constraint(equalTo: vc.view.centerYAnchor),
                                     logo.widthAnchor.constraint(equalToConstant: 72), logo.heightAnchor.constraint(equalToConstant: 72)])
        w.rootViewController = vc
        w.isHidden = false
        cover = w
    }

    static func activated() {
        cover?.isHidden = true
        cover = nil
    }

    static func lock(_ scene: UIWindowScene) {
        guard window == nil else { return }
        unlocked = false
        let w = UIWindow(windowScene: scene)
        w.windowLevel = .alert + 1
        Theme.apply(to: w, animated: false)
        w.rootViewController = LockViewController(title: "NeoChat", hint: "Введите код-пароль", cancellable: false) { ok in
            guard ok else { return }
            unlocked = true
            UIView.animate(withDuration: Theme.ms(0.22), animations: { w.alpha = 0 }) { _ in
                w.isHidden = true
                window = nil
                scene.windows.first { $0 !== w && $0.windowLevel == .normal }?.makeKey()
            }
        }
        w.makeKeyAndVisible()
        window = w
    }

    /// Opens the archive after the passcode when the archive is locked.
    static func unlockArchive(from host: UIViewController, _ then: @escaping () -> Void) {
        guard hasPasscode && lockArchive else { return then() }
        let vc = LockViewController(title: "Архив", hint: "Введите код-пароль, чтобы открыть архив", cancellable: true) { [weak host] ok in
            host?.dismiss(animated: !Theme.reducedMotion) { if ok { then() } }
        }
        vc.modalPresentationStyle = .fullScreen
        vc.modalTransitionStyle = .crossDissolve
        host.present(vc, animated: !Theme.reducedMotion)
    }

    /// Asks a new passcode twice, 4–8 digits.
    static func setPasscode(from host: UIViewController, done: @escaping () -> Void) {
        let a = UIAlertController(title: "Новый код-пароль", message: "От 4 до 8 цифр. Код хранится только на этом телефоне; если забыть его, придётся выйти и войти снова.", preferredStyle: .alert)
        for p in ["Код-пароль", "Ещё раз"] {
            a.addTextField {
                $0.placeholder = p
                $0.isSecureTextEntry = true
                $0.keyboardType = .numberPad
                $0.textContentType = nil
            }
        }
        a.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        a.addAction(UIAlertAction(title: "Сохранить", style: .default) { [weak a] _ in
            let one = a?.textFields?[0].text ?? "", two = a?.textFields?[1].text ?? ""
            guard (4...8).contains(one.count), one.allSatisfy(\.isNumber) else { return host.toast("Код-пароль: от 4 до 8 цифр") }
            guard one == two else { return host.toast("Коды не совпадают") }
            Core.shared.call("lock_set", ["passcode": one]) { r in
                switch r {
                case .failure(let e): host.toast(e.message)
                case .success:
                    Prefs.set("has_passcode", true)
                    unlocked = true
                    host.toast("Код-пароль включён")
                    done()
                }
            }
        })
        host.present(a, animated: !Theme.reducedMotion)
    }

    static func disable(from host: UIViewController, done: @escaping () -> Void) {
        let vc = LockViewController(title: "Отключить код-пароль", hint: "Введите текущий код-пароль", cancellable: true) { [weak host] ok in
            host?.dismiss(animated: !Theme.reducedMotion) {
                guard ok else { return }
                Core.shared.call("lock_clear") { _ in
                    Prefs.set("has_passcode", false)
                    done()
                }
            }
        }
        vc.modalPresentationStyle = .fullScreen
        host.present(vc, animated: !Theme.reducedMotion)
    }
}

final class LockViewController: UIViewController, UITextFieldDelegate {
    private let titleText: String
    private let hint: String
    private let cancellable: Bool
    private let done: (Bool) -> Void
    private let field = Field("Код-пароль", secure: true)
    private let err = label("", 14, color: Theme.warning)
    private let unlock = PillButton("Открыть")
    private var bioTried = false

    init(title: String, hint: String, cancellable: Bool, done: @escaping (Bool) -> Void) {
        titleText = title
        self.hint = hint
        self.cancellable = cancellable
        self.done = done
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError() }

    private var bio: String? { AppLock.useBio ? AppLock.biometryName : nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.addSubview(BackgroundView(frame: view.bounds))
        let icon = UIImageView(image: UIImage(systemName: "lock.fill", withConfiguration: UIImage.SymbolConfiguration(pointSize: 40, weight: .semibold)))
        icon.tintColor = Theme.accent
        icon.contentMode = .scaleAspectFit
        let t = label(titleText, 24, .bold)
        t.textAlignment = .center
        t.accessibilityTraits = .header
        let h = label(hint, 15, color: Theme.muted)
        h.textAlignment = .center
        field.keyboardType = .numberPad
        field.textContentType = nil
        field.textAlignment = .center
        field.delegate = self
        field.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            self.err.text = ""
            if (self.field.text ?? "").count >= 8 { self.check() }
        }, for: .editingChanged)
        err.textAlignment = .center
        unlock.addAction(UIAction { [weak self] _ in self?.check() }, for: .touchUpInside)
        let stack = UIStackView(arrangedSubviews: [icon, t, h, field, err, unlock])
        stack.axis = .vertical
        stack.spacing = 14
        stack.setCustomSpacing(22, after: h)
        if let bio {
            let b = LinkButton(bio == "Face ID" ? "Войти по Face ID" : "Войти по отпечатку")
            b.addAction(UIAction { [weak self] _ in self?.tryBio() }, for: .touchUpInside)
            stack.addArrangedSubview(b)
        }
        if cancellable {
            let c = LinkButton("Отмена", color: Theme.muted)
            c.addAction(UIAction { [weak self] _ in self?.done(false) }, for: .touchUpInside)
            stack.addArrangedSubview(c)
        }
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 40),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -40),
            stack.centerYAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerYAnchor, constant: -60),
        ])
        view.accessibilityViewIsModal = true
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if bio != nil && !bioTried { tryBio() } else { field.becomeFirstResponder() }
    }

    private func tryBio() {
        bioTried = true
        let c = LAContext()
        c.localizedFallbackTitle = "Ввести код-пароль"
        c.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: hint) { ok, _ in
            DispatchQueue.main.async {
                if ok { self.finish() } else { self.field.becomeFirstResponder() }
            }
        }
    }

    private func check() {
        let code = field.text ?? ""
        guard !code.isEmpty else { return }
        unlock.isEnabled = false
        Core.shared.call("lock_check", ["passcode": code]) { [weak self] r in
            guard let self else { return }
            self.unlock.isEnabled = true
            switch r {
            case .failure(let e): self.err.text = e.message
            case .success(let v):
                if (v as? JSON)?.bool("ok") == true { return self.finish() }
                self.err.text = "Неверный код-пароль"
                self.field.text = ""
                UINotificationFeedbackGenerator().notificationOccurred(.error)
                if !Theme.reducedMotion {
                    let a = CAKeyframeAnimation(keyPath: "transform.translation.x")
                    a.values = [0, -10, 10, -6, 6, 0]
                    a.duration = 0.32
                    self.field.layer.add(a, forKey: "shake")
                }
            }
            UIAccessibility.post(notification: .announcement, argument: self.err.text)
        }
    }

    private func finish() {
        field.resignFirstResponder()
        done(true)
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        check()
        return false
    }
}

// MARK: - Archive

final class ArchiveViewController: ListViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Архив"
        navigationItem.largeTitleDisplayMode = .never
        search.searchBar.placeholder = "Поиск в архиве"
        updateSortMenu()
    }

    private func updateSortMenu() {
        let items = chatSorts.map { k, name in
            UIAction(title: name, state: k == Prefs.archiveSort ? .on : .off) { [weak self] _ in
                Prefs.set("archive_sort", k)
                self?.updateSortMenu()
                self?.reload()
            }
        }
        navigationItem.rightBarButtonItem = UIBarButtonItem(image: UIImage(systemName: "arrow.up.arrow.down"), menu: UIMenu(title: "Сортировка", children: items))
        navigationItem.rightBarButtonItem?.accessibilityLabel = "Сортировка архива"
    }

    override func reacts(to t: String) -> Bool { ["conversations_changed", "contacts_changed", "messages_changed", "sync_reset", "presence"].contains(t) }

    override func reload() {
        let q = query
        Core.shared.call("list_conversations", ["query": q, "archived": true, "sort": Prefs.archiveSort]) { [weak self] r in
            guard let self, q == self.query, case .success(let v) = r else { return }
            let list = (v as? [JSON] ?? []).filter { q.isEmpty || $0.bool("in_archive") }
            self.apply(list, empty: q.isEmpty ? "В архиве пусто. Смахните чат влево, чтобы убрать его сюда." : "Ничего не нашли")
        }
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: RowCell.id, for: indexPath) as! RowCell
        bindChatCell(cell, rows[indexPath.row])
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        navigationController?.pushViewController(ChatViewController(conversationId: rows[indexPath.row].str("id")), animated: !Theme.reducedMotion)
    }

    override func tableView(_ tableView: UITableView, trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        chatSwipe(self, rows[indexPath.row], archive: true)
    }

    override func tableView(_ tableView: UITableView, leadingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        pinSwipe(self, rows[indexPath.row])
    }
}

/// Right to left: to the archive or back to the chats.
func chatSwipe(_ host: UIViewController, _ c: JSON, archive: Bool) -> UISwipeActionsConfiguration? {
    if c.bool("_archive") { return nil }
    let back = archive || c.bool("in_archive")
    let a = UIContextualAction(style: .normal, title: back ? "Вернуть" : "В архив") { _, _, done in
        Core.shared.call("set_archived", ["conversation_id": c.str("id"), "archived": !back]) { r in
            if case .failure(let e) = r { host.toast(e.message) } else if !back { host.toast("Чат в архиве") }
            done(true)
        }
    }
    a.image = UIImage(systemName: back ? "tray.and.arrow.up" : "archivebox")
    a.backgroundColor = Theme.rgb(0x5B6B85)
    return UISwipeActionsConfiguration(actions: [a])
}

/// Left to right: pin (up to 3) or unpin.
func pinSwipe(_ host: UIViewController, _ c: JSON) -> UISwipeActionsConfiguration? {
    if c.bool("_archive") { return nil }
    let pin = !c.bool("pinned")
    let a = UIContextualAction(style: .normal, title: pin ? "Закрепить" : "Открепить") { _, _, done in
        Core.shared.call("set_pinned", ["conversation_id": c.str("id"), "pinned": pin]) { r in
            if case .failure(let e) = r { host.toast(e.message) }
            done(true)
        }
    }
    a.image = UIImage(systemName: pin ? "pin" : "pin.slash")
    a.backgroundColor = Theme.action
    return UISwipeActionsConfiguration(actions: [a])
}

func bindChatCell(_ cell: RowCell, _ c: JSON) {
    let kind = c.str("kind")
    cell.avatar.set(c.str("title"), seed: c.str("peer_user_id").isEmpty ? c.str("id") : c.str("peer_user_id"))
    cell.avatar.online = kind == "direct" && (c.obj("presence")?.bool("online") ?? false)
    cell.title.text = c.str("title")
    cell.time.text = formatListTime(c.int64("last_activity"))
    cell.badge.set(c.int("unread"))
    cell.pin.isHidden = !(c.bool("pinned") && c.int("unread") == 0)
    var preview = c.str("last_preview").replacingOccurrences(of: "\n", with: " ")
    var color = Theme.muted
    let draft = c.str("draft").trimmingCharacters(in: .whitespacesAndNewlines)
    if !draft.isEmpty { preview = "Черновик: " + draft; color = Theme.accent }
    if let t = Typing.text(c.str("id"), group: kind != "direct") { preview = t; color = Theme.accent }
    if !(c["key_changed_users"] as? [Any] ?? []).isEmpty { preview = "Ключ собеседника изменился"; color = Theme.warning }
    cell.subtitle.text = preview
    cell.subtitle.textColor = color
    var a = [c.str("title")]
    if c.bool("pinned") { a.append("закреплён") }
    if cell.avatar.online { a.append("в сети") }
    if c.int("unread") > 0 { a.append("непрочитанных: \(c.int("unread"))") }
    a += [preview, cell.time.text ?? ""]
    cell.accessibilityLabel = a.filter { !$0.isEmpty }.joined(separator: ", ")
}

// MARK: - Settings rows

/// Tappable settings row: icon, title, value and a chevron.
final class NavRow: UIControl {
    let value = label("", 15, color: Theme.muted)
    private let action: () -> Void

    init(_ icon: String, _ title: String, value v: String = "", color: UIColor = Theme.text, chevron: Bool = true, _ action: @escaping () -> Void) {
        self.action = action
        super.init(frame: .zero)
        let img = UIImageView(image: UIImage(systemName: icon))
        img.tintColor = color == Theme.text ? Theme.accent : color
        img.contentMode = .scaleAspectFit
        img.widthAnchor.constraint(equalToConstant: 26).isActive = true
        let t = label(title, 16, color: color)
        value.text = v
        value.numberOfLines = 1
        value.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        t.setContentHuggingPriority(.defaultLow, for: .horizontal)
        let chev = UIImageView(image: UIImage(systemName: "chevron.right", withConfiguration: UIImage.SymbolConfiguration(pointSize: 13, weight: .semibold)))
        chev.tintColor = Theme.muted
        chev.isHidden = !chevron
        chev.setContentHuggingPriority(.required, for: .horizontal)
        let row = UIStackView(arrangedSubviews: [img, t, value, chev])
        row.spacing = 12
        row.alignment = .center
        row.isUserInteractionEnabled = false
        row.translatesAutoresizingMaskIntoConstraints = false
        addSubview(row)
        NSLayoutConstraint.activate([
            row.topAnchor.constraint(equalTo: topAnchor, constant: 12), row.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -12),
            row.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14), row.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -14),
            heightAnchor.constraint(greaterThanOrEqualToConstant: 48),
        ])
        isAccessibilityElement = true
        accessibilityTraits = .button
        accessibilityLabel = title
        addAction(UIAction { [weak self] _ in self?.action() }, for: .touchUpInside)
    }

    required init?(coder: NSCoder) { fatalError() }

    override var accessibilityValue: String? {
        get { value.text }
        set {}
    }

    override var isHighlighted: Bool {
        didSet { backgroundColor = isHighlighted ? Theme.muted.withAlphaComponent(0.12) : .clear }
    }
}

final class SwitchRow: UIView {
    let toggle = UISwitch()

    init(_ icon: String, _ title: String, on: Bool, _ change: @escaping (Bool) -> Void) {
        super.init(frame: .zero)
        let img = UIImageView(image: UIImage(systemName: icon))
        img.tintColor = Theme.accent
        img.contentMode = .scaleAspectFit
        img.widthAnchor.constraint(equalToConstant: 26).isActive = true
        toggle.isOn = on
        toggle.onTintColor = Theme.action
        toggle.accessibilityLabel = title
        let t = label(title, 16)
        toggle.addAction(UIAction { [weak self] _ in change(self?.toggle.isOn ?? false) }, for: .valueChanged)
        let row = UIStackView(arrangedSubviews: [img, t, toggle])
        row.spacing = 12
        row.alignment = .center
        row.translatesAutoresizingMaskIntoConstraints = false
        addSubview(row)
        NSLayoutConstraint.activate([
            row.topAnchor.constraint(equalTo: topAnchor, constant: 8), row.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -8),
            row.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14), row.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -14),
            heightAnchor.constraint(greaterThanOrEqualToConstant: 48),
        ])
    }

    required init?(coder: NSCoder) { fatalError() }
}

extension SheetViewController {
    /// A titled glass card of rows, like grouped settings.
    func section(_ title: String, _ rows: [UIView], footer: String? = nil) {
        let h = label(title.uppercased(), 13, .semibold, color: Theme.muted)
        h.accessibilityTraits = .header
        stack.addArrangedSubview(h)
        stack.setCustomSpacing(6, after: h)
        let card = UIStackView()
        card.axis = .vertical
        card.glass(22)
        card.clipsToBounds = true
        for (i, r) in rows.enumerated() {
            if i > 0 {
                let line = UIView()
                line.backgroundColor = Theme.muted.withAlphaComponent(0.18)
                line.heightAnchor.constraint(equalToConstant: 1 / UIScreen.main.scale).isActive = true
                card.addArrangedSubview(line)
            }
            card.addArrangedSubview(r)
        }
        stack.addArrangedSubview(card)
        if let footer {
            stack.setCustomSpacing(6, after: card)
            let f = label(footer, 13, color: Theme.muted)
            stack.addArrangedSubview(f)
            stack.setCustomSpacing(22, after: f)
        } else {
            stack.setCustomSpacing(22, after: card)
        }
    }

    /// Action sheet with a check mark on the current choice.
    func choose<T: Equatable>(_ title: String, _ options: [(T, String)], current: T, from source: UIView, _ pick: @escaping (T, String) -> Void) {
        let a = UIAlertController(title: title, message: nil, preferredStyle: .actionSheet)
        for (v, name) in options {
            a.addAction(UIAlertAction(title: (v == current ? "✓ " : "") + name, style: .default) { _ in pick(v, name) })
        }
        a.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        a.popoverPresentationController?.sourceView = source
        present(a, animated: !Theme.reducedMotion)
    }
}

final class EditProfileViewController: SheetViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Профиль"
        let name = Field("Имя")
        name.autocapitalizationType = .words
        let bio = Field("О себе")
        bio.autocapitalizationType = .sentences
        bio.autocorrectionType = .default
        let count = label("0/100", 13, color: Theme.muted)
        count.textAlignment = .right
        bio.addAction(UIAction { _ in
            if let t = bio.text, t.count > 100 { bio.text = String(t.prefix(100)) }
            count.text = "\(bio.text?.count ?? 0)/100"
        }, for: .editingChanged)
        stack.addArrangedSubview(name)
        muted("Имя видят ваши собеседники. Никнейм и ID не меняются.")
        stack.addArrangedSubview(bio)
        stack.addArrangedSubview(count)
        muted("Короткое описание, до 100 символов. Его видят ваши контакты и участники общих групп.")
        let save = PillButton("Сохранить")
        stack.addArrangedSubview(save)
        Core.shared.call("status") { r in
            guard case .success(let v) = r, let s = v as? JSON else { return }
            name.text = s.str("display_name")
            bio.text = s.str("bio")
            count.text = "\(s.str("bio").count)/100"
        }
        save.addAction(UIAction { [weak self] _ in
            let n = (name.text ?? "").trimmingCharacters(in: .whitespaces)
            guard !n.isEmpty else { self?.toast("Введите имя"); return }
            save.isEnabled = false
            Core.shared.call("set_profile", ["display_name": n, "bio": (bio.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)]) { r in
                save.isEnabled = true
                switch r {
                case .failure(let e): self?.toast(e.message)
                case .success:
                    NotificationCenter.default.post(name: .prefsChanged, object: nil)
                    if let nav = self?.navigationController, nav.viewControllers.count > 1 { nav.popViewController(animated: !Theme.reducedMotion) } else { self?.dismiss(animated: !Theme.reducedMotion) }
                }
            }
        }, for: .touchUpInside)
    }
}
