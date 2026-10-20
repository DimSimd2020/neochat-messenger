import UIKit

/// Base for sheet content: a scrolling vertical stack with a close button.
class SheetViewController: UIViewController {
    let stack = UIStackView()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground.withAlphaComponent(0)
        view.addSubview(BackgroundView(frame: view.bounds))
        navigationItem.rightBarButtonItem = UIBarButtonItem(systemItem: .close, primaryAction: UIAction { [weak self] _ in
            self?.dismiss(animated: !Theme.reducedMotion)
        })
        let scroll = UIScrollView()
        scroll.keyboardDismissMode = .interactive
        scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 8),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -24),
            stack.leadingAnchor.constraint(equalTo: scroll.frameLayoutGuide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: scroll.frameLayoutGuide.trailingAnchor, constant: -20),
        ])
    }

    func muted(_ t: String) { stack.addArrangedSubview(label(t, 14, color: Theme.muted)) }
    func heading(_ t: String) { stack.addArrangedSubview(label(t, 17, .semibold)) }
    func clear() { stack.arrangedSubviews.forEach { $0.removeFromSuperview() } }

    func switchRow(_ text: String, on: Bool, _ change: @escaping (Bool) -> Void) -> UIStackView {
        let s = UISwitch()
        s.isOn = on
        s.onTintColor = Theme.action
        s.accessibilityLabel = text
        s.addAction(UIAction { _ in change(s.isOn) }, for: .valueChanged)
        let row = UIStackView(arrangedSubviews: [label(text, 16), s])
        row.alignment = .center
        row.spacing = 12
        return row
    }

    func personRow(_ name: String, _ sub: String, seed: String, subColor: UIColor = Theme.muted, action: (() -> Void)? = nil) -> UIView {
        let av = AvatarView(size: 44)
        av.set(name, seed: seed)
        let t = label(name, 16, .semibold)
        let s = label(sub, 14, color: subColor)
        let col = UIStackView(arrangedSubviews: [t, s])
        col.axis = .vertical
        let row = UIStackView(arrangedSubviews: [av, col])
        row.spacing = 12
        row.alignment = .center
        row.isLayoutMarginsRelativeArrangement = true
        row.layoutMargins = UIEdgeInsets(top: 6, left: 0, bottom: 6, right: 0)
        row.isAccessibilityElement = true
        row.accessibilityLabel = name + ", " + sub
        if let action {
            row.accessibilityTraits = .button
            row.addGestureRecognizer(TapGesture(action))
        }
        return row
    }
}

final class TapGesture: UITapGestureRecognizer {
    private let action: () -> Void
    init(_ action: @escaping () -> Void) {
        self.action = action
        super.init(target: nil, action: nil)
        addTarget(self, action: #selector(fire))
    }
    @objc private func fire() { action() }
}

final class NewContactViewController: SheetViewController {
    var done: (() -> Void)?

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Новый контакт"
        let user = Field("Никнейм")
        let first = Field("Имя (необязательно)")
        first.autocapitalizationType = .words
        let last = Field("Фамилия (необязательно)")
        last.autocapitalizationType = .words
        let err = label("", 14, color: Theme.warning)
        err.isHidden = true
        let add = PillButton("Добавить")
        [user, first, last].forEach(stack.addArrangedSubview)
        let scan = PillButton("Сканировать QR-код", primary: false)
        scan.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            ContactQr.scan(from: self)
        }, for: .touchUpInside)
        muted("Поиск идёт только по точному никнейму: публичного каталога нет. Без своего имени контакт показывается под именем из его профиля.")
        stack.addArrangedSubview(err)
        stack.addArrangedSubview(add)
        stack.addArrangedSubview(scan)
        add.addAction(UIAction { [weak self] _ in
            let f = (first.text ?? "").trimmingCharacters(in: .whitespaces)
            var u = (user.text ?? "").trimmingCharacters(in: .whitespaces)
            if u.hasPrefix("@") { u.removeFirst() }
            if u.isEmpty { err.text = "Введите никнейм"; err.isHidden = false; return }
            add.isEnabled = false
            Core.shared.call("resolve_contact", ["username": u, "first_name": f, "last_name": (last.text ?? "").trimmingCharacters(in: .whitespaces)]) { r in
                add.isEnabled = true
                switch r {
                case .failure(let e):
                    err.text = e.code == "not_found" ? "Пользователь с таким никнеймом не найден" : e.message
                    err.isHidden = false
                case .success:
                    self?.done?()
                    self?.presentingViewController?.toast("Контакт добавлен")
                    self?.dismiss(animated: true)
                }
            }
        }, for: .touchUpInside)
    }
}

final class RecentCallsViewController: SheetViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Недавние звонки"
        let t = label("Звонков пока нет", 17, .semibold)
        t.textAlignment = .center
        stack.addArrangedSubview(t)
        let s = label("Звонки в этой версии NeoChat не реализованы.", 14, color: Theme.muted)
        s.textAlignment = .center
        stack.addArrangedSubview(s)
    }
}

final class NewChatViewController: SheetViewController {
    private var group = false // group or channel: several people are picked
    private var channel = false
    private var selected: [String] = []
    private let mode = UISegmentedControl(items: ["Личный", "Группа", "Канал"])
    private let titleField = Field("Название группы")
    private let aboutField = Field("Описание (необязательно)")
    private let list = UIStackView()
    private let create = PillButton("Создать группу")

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Новый чат"
        mode.selectedSegmentIndex = 0
        mode.addAction(UIAction { [weak self] _ in self?.setMode(self?.mode.selectedSegmentIndex ?? 0) }, for: .valueChanged)
        stack.addArrangedSubview(mode)
        let addContact = LinkButton("Новый контакт")
        addContact.contentHorizontalAlignment = .leading
        addContact.addAction(UIAction { [weak self] _ in
            let vc = NewContactViewController()
            vc.done = { self?.reload() }
            self?.navigationController?.pushViewController(vc, animated: true)
        }, for: .touchUpInside)
        stack.addArrangedSubview(addContact)
        titleField.isHidden = true
        stack.addArrangedSubview(titleField)
        aboutField.isHidden = true
        stack.addArrangedSubview(aboutField)
        list.axis = .vertical
        stack.addArrangedSubview(list)
        create.isHidden = true
        create.addAction(UIAction { [weak self] _ in self?.createGroup() }, for: .touchUpInside)
        stack.addArrangedSubview(create)
        reload()
    }

    /// 0 — direct chat, 1 — group, 2 — channel.
    private func setMode(_ m: Int) {
        group = m != 0
        channel = m == 2
        title = ["Новый чат", "Новая группа", "Новый канал"][m]
        titleField.placeholder = channel ? "Название канала" : "Название группы"
        titleField.accessibilityLabel = titleField.placeholder
        titleField.isHidden = !group
        aboutField.isHidden = !group
        create.isHidden = !group
        updateCreate()
        reload()
    }

    private func updateCreate() {
        let base = channel ? "Создать канал" : "Создать группу"
        create.setTitle(selected.isEmpty ? base : base + " (\(selected.count))", for: .normal)
    }

    private func reload() {
        Core.shared.call("list_contacts") { [weak self] r in
            guard let self, case .success(let v) = r else { return }
            self.list.arrangedSubviews.forEach { $0.removeFromSuperview() }
            let contacts = v as? [JSON] ?? []
            if contacts.isEmpty { self.list.addArrangedSubview(label("Контактов пока нет. Добавьте человека по имени пользователя.", 14, color: Theme.muted)) }
            for c in contacts {
                let uid = c.str("user_id")
                let on = self.selected.contains(uid)
                let row = self.personRow(c.str("display_name"), (self.group && on ? "✓ выбран · " : "") + "@" + c.str("username"), seed: uid) { [weak self] in self?.tap(uid) }
                self.list.addArrangedSubview(row)
            }
        }
    }

    private func tap(_ uid: String) {
        if !group {
            Core.shared.call("open_direct", ["user_id": uid]) { [weak self] r in
                switch r {
                case .failure(let e): self?.toast(e.message)
                case .success(let v):
                    let id = (v as? JSON)?.str("id") ?? ""
                    let tabs = self?.presentingViewController as? MainTabController
                    self?.dismiss(animated: true) { tabs?.open(id) }
                }
            }
            return
        }
        if let i = selected.firstIndex(of: uid) {
            selected.remove(at: i)
        } else if selected.count >= (channel ? 199 : 49) {
            toast(channel ? "В канале может быть до 199 подписчиков" : "В группе может быть до 49 других участников")
            return
        } else { selected.append(uid) }
        updateCreate()
        reload()
    }

    private func createGroup() {
        let t = (titleField.text ?? "").trimmingCharacters(in: .whitespaces)
        if t.isEmpty { return toast(channel ? "Введите название канала" : "Введите название группы") }
        if selected.isEmpty && !channel { return toast("Выберите участников") }
        create.isEnabled = false
        let p: JSON = ["title": t, "description": (aboutField.text ?? "").trimmingCharacters(in: .whitespaces), "member_ids": selected,
                       "kind": channel ? "channel" : "group"]
        Core.shared.call("create_group", p) { [weak self] r in
            self?.create.isEnabled = true
            switch r {
            case .failure(let e): self?.toast(e.message)
            case .success(let v):
                let id = (v as? JSON)?.str("id") ?? ""
                let tabs = self?.presentingViewController as? MainTabController
                self?.dismiss(animated: true) { tabs?.open(id) }
            }
        }
    }
}

final class DevicesViewController: SheetViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Устройства"
        reload()
    }

    private func platform(_ p: String) -> String { ["windows": "Windows", "android": "Android", "ios": "iOS"][p] ?? "Устройство" }

    private func reload() {
        do {
            Core.shared.call("devices") { [weak self] r in
                guard let self else { return }
                guard case .success(let v) = r else { if case .failure(let e) = r { self.toast(e.message) }; return }
                self.clear()
                for d in (v as? JSON)?.arr("devices") ?? [] {
                    var sub = self.platform(d.str("platform"))
                    if d.bool("current") { sub += " · это устройство" }
                    let canRevoke = !d.bool("current")
                    let row = self.personRow(d.str("name"), sub, seed: d.str("id"), action: canRevoke ? { [weak self] in self?.confirmRevoke(d) } : nil)
                    self.stack.addArrangedSubview(row)
                }
                self.muted("Отзыв устройства прекращает доставку новых сообщений на него. Уже скачанные на нём копии удалённо не стираются.")
                self.muted("На аккаунте может быть до 10 устройств.")
                self.heading("Вход на компьютере")
                self.muted("Откройте NeoChat на компьютере и отсканируйте QR-код с экрана входа.")
                let scan = PillButton("Сканировать QR-код")
                self.stack.addArrangedSubview(scan)
                let approve: (String) -> Void = { [weak self] qr in
                    scan.isEnabled = false
                    Core.shared.call("qr_login_approve", ["qr": qr]) { r in
                        scan.isEnabled = true
                        switch r {
                        case .failure(let e): self?.toast(e.message)
                        case .success: self?.toast("Вход на компьютере выполнен"); self?.reload()
                        }
                    }
                }
                scan.addAction(UIAction { [weak self] _ in
                    guard let self else { return }
                    QrScannerViewController.present(from: self, prefix: "neochat-login:", onResult: approve)
                }, for: .touchUpInside)
            }
        }
    }

    private func confirmRevoke(_ d: JSON) {
        let a = UIAlertController(title: "Отозвать «\(d.str("name"))»?", message: "Новые сообщения перестанут приходить на это устройство.", preferredStyle: .actionSheet)
        a.addAction(UIAlertAction(title: "Отозвать", style: .destructive) { [weak self] _ in
            Core.shared.call("revoke_device", ["device_id": d.str("id")]) { r in
                switch r {
                case .failure(let e): self?.toast(e.message)
                case .success: self?.toast("Устройство отозвано"); self?.reload()
                }
            }
        })
        a.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        present(a, animated: true)
    }
}

final class SettingsViewController: SheetViewController {
    private var showLastSeen = true
    private var twoFactor = false

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Настройки"
        build()
        Core.shared.call("account_settings") { [weak self] r in
            guard let self, case .success(let v) = r, let o = v as? JSON else { return }
            self.showLastSeen = o["show_last_seen"] as? Bool ?? true
            self.twoFactor = o.bool("two_factor")
            self.build()
        }
    }

    private func push(_ vc: UIViewController) {
        navigationController?.pushViewController(vc, animated: !Theme.reducedMotion)
    }

    private func build() {
        clear()
        section("Аккаунт", [
            NavRow("person.crop.circle", "Имя и описание") { [weak self] in self?.push(EditProfileViewController()) },
            NavRow("qrcode", "Мой QR-код") { [weak self] in self?.push(MyQrViewController()) },
            NavRow("qrcode.viewfinder", "Добавить контакт по QR-коду") { [weak self] in
                guard let self else { return }
                ContactQr.scan(from: self)
            },
            NavRow("laptopcomputer.and.iphone", "Устройства") { [weak self] in self?.push(DevicesViewController()) },
        ])

        var privacy: [UIView] = []
        let lastSeen = NavRow("clock", "Время последнего визита", value: showLastSeen ? "Все" : "Никто") {}
        lastSeen.addAction(UIAction { [weak self, weak lastSeen] _ in
            guard let self, let lastSeen else { return }
            self.choose("Кто видит время последнего визита", [(true, "Все"), (false, "Никто")], current: self.showLastSeen, from: lastSeen) { v, name in
                Core.shared.call("set_last_seen_visible", ["visible": v]) { r in
                    switch r {
                    case .failure(let e): self.toast(e.message)
                    case .success:
                        self.showLastSeen = v
                        lastSeen.value.text = name
                    }
                }
            }
        }, for: .touchUpInside)
        privacy.append(lastSeen)
        let has = AppLock.hasPasscode
        privacy.append(NavRow("lock", "Код-пароль", value: has ? "Вкл." : "Выкл.") { [weak self] in
            guard let self else { return }
            if has { AppLock.disable(from: self) { self.build() } } else { AppLock.setPasscode(from: self) { self.build() } }
        })
        if has {
            privacy.append(SwitchRow("iphone", "Вход в приложение по коду", on: AppLock.lockApp) { Prefs.set("lock_app", $0) })
            privacy.append(SwitchRow("archivebox", "Вход в архив по коду", on: AppLock.lockArchive) { Prefs.set("lock_archive", $0) })
            if let bio = AppLock.biometryName {
                privacy.append(SwitchRow(bio == "Face ID" ? "faceid" : "touchid", bio, on: AppLock.useBio) { Prefs.set("lock_bio", $0) })
            }
            let current = AppLock.afterChoices.first { $0.0 == AppLock.lockAfter }?.1 ?? "Сразу"
            let after = NavRow("timer", "Автоблокировка", value: current) {}
            after.addAction(UIAction { [weak self, weak after] _ in
                guard let self, let after else { return }
                self.choose("Автоблокировка", AppLock.afterChoices, current: AppLock.lockAfter, from: after) { v, name in
                    Prefs.set("lock_after", v)
                    after.value.text = name
                }
            }, for: .touchUpInside)
            privacy.append(after)
        }
        privacy.append(SwitchRow("eye.slash", "Скрывать в переключателе приложений", on: Prefs.bool("secure_screen", false)) { Prefs.set("secure_screen", $0) })
        var tfRow: SwitchRow?
        tfRow = SwitchRow("checkmark.shield", "Подтверждение входа", on: twoFactor) { [weak self] on in
            Core.shared.call("set_two_factor", ["enabled": on]) { r in
                switch r {
                case .failure(let e): self?.toast(e.message); tfRow?.toggle.setOn(!on, animated: true)
                case .success: self?.twoFactor = on
                }
            }
        }
        privacy.append(tfRow!)
        section("Конфиденциальность", privacy, footer: "Если скрыть время визита, вы тоже не увидите его у других: останется «был(а) недавно» или «давно». Код-пароль хранится только на этом телефоне. Подтверждение входа запрашивает код из чата «NeoChat уведомления» при входе на новом устройстве.")

        section("Уведомления", [
            SwitchRow("bell", "Уведомления о сообщениях", on: Prefs.bool("notifications", true)) { Prefs.set("notifications", $0) },
            NavRow("gear", "Настройки iOS") {
                if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
            },
        ], footer: "В уведомлении видно только название чата, без текста сообщения.")

        let theme = UISegmentedControl(items: ["Устройство", "День", "Ночь"])
        theme.selectedSegmentIndex = Theme.mode.rawValue
        theme.addAction(UIAction { _ in Theme.mode = Theme.Mode(rawValue: theme.selectedSegmentIndex) ?? .system }, for: .valueChanged)
        let sizes: [(Double, String)] = [(15, "Мелкий"), (17, "Обычный"), (19, "Крупный"), (21, "Очень крупный")]
        let size = NavRow("textformat.size", "Размер текста", value: sizes.first { CGFloat($0.0) == Prefs.textSize }?.1 ?? "Обычный") {}
        size.addAction(UIAction { [weak self, weak size] _ in
            guard let self, let size else { return }
            self.choose("Размер текста в чатах", sizes, current: Double(Prefs.textSize), from: size) { v, name in
                Prefs.set("text_size", v)
                size.value.text = name
            }
        }, for: .touchUpInside)
        section("Оформление", [
            padded(theme),
            size,
            SwitchRow("face.smiling", "Крупные эмодзи без текста", on: Prefs.bigEmoji) { Prefs.set("big_emoji", $0) },
            SwitchRow("figure.walk", "Уменьшить анимацию", on: UserDefaults.standard.bool(forKey: "reduce_motion")) { Prefs.set("reduce_motion", $0) },
        ])

        let sort = NavRow("arrow.up.arrow.down", "Сортировка чатов", value: chatSorts.first { $0.0 == Prefs.chatSort }?.1 ?? "") {}
        sort.addAction(UIAction { [weak self, weak sort] _ in
            guard let self, let sort else { return }
            self.choose("Сортировка чатов", chatSorts, current: Prefs.chatSort, from: sort) { v, name in
                Prefs.set("chat_sort", v)
                sort.value.text = name
            }
        }, for: .touchUpInside)
        section("Чаты", [
            sort,
            SwitchRow("photo", "Автозагрузка фото", on: Prefs.autoload) { Prefs.set("autoload", $0) },
        ], footer: "Смахните чат влево, чтобы убрать его в архив, и вправо, чтобы закрепить. Закрепить можно до 3 чатов.")

        let cache = NavRow("trash", "Очистить кэш", value: sizeOf(cacheDirs())) {}
        cache.addAction(UIAction { [weak self, weak cache] _ in
            for d in self?.cacheDirs() ?? [] {
                for f in (try? FileManager.default.contentsOfDirectory(at: d, includingPropertiesForKeys: nil)) ?? [] { try? FileManager.default.removeItem(at: f) }
            }
            Photos.cache.removeAllObjects()
            cache?.value.text = self?.sizeOf(self?.cacheDirs() ?? [])
            self?.toast("Кэш очищен")
        }, for: .touchUpInside)
        section("Данные", [cache], footer: "Удаляет временные файлы и расшифрованные копии фото. Переписка остаётся.")

        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        section("О приложении", [
            NavRow("info.circle", "Версия", value: version, chevron: false) {},
            NavRow("arrow.down.circle", "Проверить обновления") { [weak self] in
                (self?.view.window?.rootViewController as? RootViewController)?.checkUpdate(manual: true)
            },
        ])

        let logout = PillButton("Выйти из аккаунта", primary: false)
        logout.setTitleColor(Theme.warning, for: .normal)
        logout.addAction(UIAction { [weak self] _ in self?.confirmLogout() }, for: .touchUpInside)
        stack.addArrangedSubview(logout)
    }

    private func padded(_ v: UIView) -> UIView {
        let wrap = UIView()
        v.translatesAutoresizingMaskIntoConstraints = false
        wrap.addSubview(v)
        NSLayoutConstraint.activate([
            v.topAnchor.constraint(equalTo: wrap.topAnchor, constant: 10), v.bottomAnchor.constraint(equalTo: wrap.bottomAnchor, constant: -10),
            v.leadingAnchor.constraint(equalTo: wrap.leadingAnchor, constant: 14), v.trailingAnchor.constraint(equalTo: wrap.trailingAnchor, constant: -14),
        ])
        return wrap
    }

    private func cacheDirs() -> [URL] {
        [FileManager.default.temporaryDirectory] + FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)
    }

    private func sizeOf(_ dirs: [URL]) -> String {
        var total: Int64 = 0
        for d in dirs {
            let e = FileManager.default.enumerator(at: d, includingPropertiesForKeys: [.fileSizeKey])
            while let f = e?.nextObject() as? URL {
                total += Int64((try? f.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            }
        }
        if total < 1024 * 1024 { return "\(total / 1024) КБ" }
        return String(format: "%.1f МБ", Double(total) / 1048576).replacingOccurrences(of: ".", with: ",")
    }

    private func confirmLogout() {
        let a = UIAlertController(title: "Выйти из аккаунта?", message: "Ключи и переписка на этом телефоне будут удалены.", preferredStyle: .actionSheet)
        a.addAction(UIAlertAction(title: "Выйти", style: .destructive) { [weak self] _ in
            Core.shared.call("logout") { _ in
                Prefs.set("has_passcode", false)
                let root = self?.view.window?.rootViewController as? RootViewController
                self?.dismiss(animated: true) { root?.start() }
            }
        })
        a.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        present(a, animated: true)
    }
}

final class ConversationInfoViewController: SheetViewController {
    private let cid: String

    init(conversationId: String) {
        cid = conversationId
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Сведения"
        reload()
    }

    private func call(_ m: String, _ p: JSON, ok: String? = nil, close: Bool = false) {
        Core.shared.call(m, p) { [weak self] r in
            switch r {
            case .failure(let e): self?.toast(e.message)
            case .success:
                if let ok { self?.toast(ok) }
                if close { self?.dismiss(animated: true) } else { self?.reload() }
            }
        }
    }

    private func reload() {
        Core.shared.call("get_conversation", ["conversation_id": cid]) { [weak self] r in
            guard let self, case .success(let v) = r, let c = v as? JSON else { return }
            self.clear()
            let members = c.arr("members")
            if c.str("kind") == "direct" {
                let peer = c.str("peer_user_id")
                let other = members.first { !$0.bool("me") }
                let handle = other.map { "@" + $0.str("username") } ?? ""
                let seen = c.obj("presence")?.str("text") ?? ""
                self.stack.addArrangedSubview(self.personRow(c.str("title"), seen.isEmpty ? handle : handle + " · " + seen, seed: peer))
                if let bio = other?.str("bio"), !bio.isEmpty { self.stack.addArrangedSubview(label(bio, 15)) }
                Core.shared.call("contact_info", ["user_id": peer]) { r in
                    guard case .success(let iv) = r, let info = iv as? JSON else { return }
                    self.heading("Код безопасности")
                    self.muted("Сравните код с собеседником при встрече или по другому надёжному каналу. Совпадение означает, что сообщения шифруются для его настоящих ключей.")
                    let num = label(info.str("safety_number"), 18, .semibold, color: Theme.accent)
                    num.font = UIFontMetrics(forTextStyle: .body).scaledFont(for: .monospacedDigitSystemFont(ofSize: 18, weight: .semibold))
                    num.accessibilityLabel = "Код безопасности: " + info.str("safety_number")
                    self.stack.addArrangedSubview(num)
                    if info.bool("key_changed") {
                        self.stack.addArrangedSubview(label("Ключ собеседника изменился. Сверьте код перед продолжением", 15, .semibold, color: Theme.warning))
                        let b = PillButton("Код сверен, принять новый ключ")
                        b.addAction(UIAction { _ in self.call("accept_key_change", ["user_id": peer], ok: "Новый ключ принят. Сверьте код ещё раз при возможности") }, for: .touchUpInside)
                        self.stack.addArrangedSubview(b)
                    } else if info.bool("verified") {
                        self.stack.addArrangedSubview(label("Ключ сверен", 15, .semibold, color: Theme.accent))
                    } else {
                        self.stack.addArrangedSubview(label("Ключ ещё не сверен", 15, color: Theme.muted))
                        let b = PillButton("Отметить как сверенный", primary: false)
                        b.addAction(UIAction { _ in self.call("verify_contact", ["user_id": peer]) }, for: .touchUpInside)
                        self.stack.addArrangedSubview(b)
                    }
                    self.muted("Файлы хранятся на сервере в зашифрованном виде 30 дней.")
                }
                return
            }
            let owner = c.bool("is_owner")
            let admin = c.bool("is_admin")
            let channel = c.str("kind") == "channel"
            let gone = c.bool("left") || c.bool("archived")
            let ownerId = c.str("owner_id")
            let admins = Set(c["admins"] as? [String] ?? [])
            // Everyone except me, optionally without one person or with one more.
            let others = { (without: String, with: String) -> [String] in
                members.filter { !$0.bool("me") && $0.str("user_id") != without }.map { $0.str("user_id") } + (with.isEmpty ? [] : [with])
            }
            self.stack.addArrangedSubview(self.personRow(c.str("title"), (channel ? "Канал · " : "Группа · ") + memberCount(c.int("member_count"), channel: channel), seed: self.cid))
            if !c.str("description").isEmpty { self.stack.addArrangedSubview(label(c.str("description"), 15)) }
            if c.bool("can_edit_info") && !gone {
                let name = Field(channel ? "Название канала" : "Название группы")
                name.text = c.str("title")
                let about = Field("Описание")
                about.text = c.str("description")
                self.stack.addArrangedSubview(name)
                self.stack.addArrangedSubview(about)
                let save = PillButton("Сохранить название и описание", primary: false)
                save.addAction(UIAction { _ in
                    let t = (name.text ?? "").trimmingCharacters(in: .whitespaces)
                    if t.isEmpty { return self.toast("Введите название") }
                    self.call("group_set_info", ["conversation_id": self.cid, "title": t, "description": (about.text ?? "").trimmingCharacters(in: .whitespaces)], ok: "Сохранено")
                }, for: .touchUpInside)
                self.stack.addArrangedSubview(save)
            }
            self.heading((channel ? "Подписчики: " : "Участники: ") + "\(c.int("member_count"))")
            for m in members {
                let uid = m.str("user_id")
                let me = m.bool("me")
                let warn = m.bool("key_changed")
                var sub = me ? "это вы" : "@" + m.str("username")
                if uid == ownerId { sub += " · владелец" } else if admins.contains(uid) { sub += " · админ" }
                if !m.str("tag").isEmpty { sub += " · " + m.str("tag") }
                if warn { sub = "Ключ изменился — сверьте код в личном чате" }
                let name = m.str("display_name")
                self.stack.addArrangedSubview(self.personRow(name, sub, seed: uid, subColor: warn ? Theme.warning : Theme.muted, action: admin && !gone ? {
                    let a = UIAlertController(title: name, message: nil, preferredStyle: .actionSheet)
                    a.addAction(UIAlertAction(title: "Тег…", style: .default) { _ in self.editTag(uid, name: name, current: m.str("tag")) })
                    if owner && !me {
                        if admins.contains(uid) {
                            a.addAction(UIAlertAction(title: "Снять права админа", style: .default) { _ in
                                self.call("group_set_members", ["conversation_id": self.cid, "member_ids": others("", ""), "admin_ids": admins.filter { $0 != uid }.sorted()], ok: "Права админа сняты")
                            })
                        } else {
                            a.addAction(UIAlertAction(title: "Назначить админом", style: .default) { _ in
                                self.call("group_set_members", ["conversation_id": self.cid, "member_ids": others("", ""), "admin_ids": admins.sorted() + [uid]], ok: "Назначен админ")
                            })
                        }
                    }
                    if !me && uid != ownerId && (owner || !admins.contains(uid)) {
                        a.addAction(UIAlertAction(title: channel ? "Удалить из канала" : "Исключить из группы", style: .destructive) { _ in
                            self.call("group_set_members", ["conversation_id": self.cid, "member_ids": others(uid, "")], ok: "Исключён. Новые сообщения ему не придут")
                        })
                    }
                    a.addAction(UIAlertAction(title: "Отмена", style: .cancel))
                    self.present(a, animated: true)
                } : nil))
            }
            if admin && !gone {
                let add = PillButton(channel ? "Добавить подписчиков" : "Добавить участников", primary: false)
                add.showsMenuAsPrimaryAction = true
                Core.shared.call("list_contacts") { lr in
                    guard case .success(let lv) = lr else { return }
                    let inGroup = Set(members.map { $0.str("user_id") })
                    let cands = (lv as? [JSON] ?? []).filter { !inGroup.contains($0.str("user_id")) }
                    add.menu = UIMenu(children: cands.isEmpty ? [UIAction(title: "Все контакты уже здесь", attributes: .disabled) { _ in }] : cands.map { cand in
                        UIAction(title: cand.str("display_name")) { _ in
                            self.call("group_set_members", ["conversation_id": self.cid, "member_ids": others("", cand.str("user_id"))], ok: "Добавлен. Прошлые сообщения ему не видны")
                        }
                    })
                }
                self.stack.addArrangedSubview(add)
            }
            if admin && !gone && !channel {
                self.heading("Права участников")
                let st = c.obj("settings") ?? [:]
                for (key, text) in [("members_can_send", "Писать сообщения"), ("members_can_media", "Отправлять фото и файлы"),
                                    ("members_can_polls", "Создавать опросы"), ("members_can_info", "Менять название и описание")] {
                    self.stack.addArrangedSubview(self.switchRow(text, on: st.bool(key)) { on in
                        Core.shared.call("group_set_settings", ["conversation_id": self.cid, key: on]) { r in
                            if case .failure(let e) = r { self.toast(e.message); self.reload() }
                        }
                    })
                }
                self.muted("Владелец и админы могут всё. Сервер не пропускает сообщения тех, кому писать нельзя.")
            } else if channel {
                self.muted("В канале пишут только владелец и админы. Подписчики читают и голосуют в опросах.")
            }
            self.muted("Файлы хранятся на сервере в зашифрованном виде 30 дней.")
            if !gone {
                let leave = PillButton(channel ? "Выйти из канала" : "Выйти из группы", primary: false)
                leave.setTitleColor(Theme.warning, for: .normal)
                leave.addAction(UIAction { _ in
                    let what = owner ? (channel ? "Выйти: канал станет архивным для всех" : "Выйти: группа станет архивной для всех") : "Выйти: новые сообщения перестанут приходить"
                    let a = UIAlertController(title: what, message: nil, preferredStyle: .actionSheet)
                    a.addAction(UIAlertAction(title: "Выйти", style: .destructive) { _ in self.call("group_leave", ["conversation_id": self.cid], close: true) })
                    a.addAction(UIAlertAction(title: "Отмена", style: .cancel))
                    self.present(a, animated: true)
                }, for: .touchUpInside)
                self.stack.addArrangedSubview(leave)
            }
        }
    }

    private func editTag(_ uid: String, name: String, current: String) {
        let a = UIAlertController(title: "Тег: " + name, message: "Тег виден всем рядом с именем. Пустое поле убирает тег.", preferredStyle: .alert)
        a.addTextField { $0.text = current; $0.placeholder = "Например, «дизайн»" }
        a.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        a.addAction(UIAlertAction(title: "Сохранить", style: .default) { [weak self] _ in
            guard let self else { return }
            let t = (a.textFields?.first?.text ?? "").trimmingCharacters(in: .whitespaces)
            self.call("group_set_tag", ["conversation_id": self.cid, "user_id": uid, "tag": t])
        })
        present(a, animated: true)
    }
}

final class NewPollViewController: SheetViewController {
    private let cid: String
    private let question = Field("Вопрос")
    private let options = UIStackView()
    private let more = PillButton("Добавить вариант", primary: false)
    private var anonymous = true
    private var multiple = false

    init(conversationId: String) {
        cid = conversationId
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Новый опрос"
        question.autocapitalizationType = .sentences
        stack.addArrangedSubview(question)
        heading("Варианты ответа")
        options.axis = .vertical
        options.spacing = 8
        stack.addArrangedSubview(options)
        addOption()
        addOption()
        more.addAction(UIAction { [weak self] _ in self?.addOption(focus: true) }, for: .touchUpInside)
        stack.addArrangedSubview(more)
        stack.addArrangedSubview(switchRow("Анонимное голосование", on: true) { [weak self] on in self?.anonymous = on })
        stack.addArrangedSubview(switchRow("Несколько ответов", on: false) { [weak self] on in self?.multiple = on })
        let sendButton = PillButton("Отправить опрос")
        sendButton.addAction(UIAction { [weak self] _ in self?.send(sendButton) }, for: .touchUpInside)
        stack.addArrangedSubview(sendButton)
    }

    private func addOption(focus: Bool = false) {
        guard options.arrangedSubviews.count < 10 else { return }
        let f = Field("Вариант \(options.arrangedSubviews.count + 1)")
        f.autocapitalizationType = .sentences
        options.addArrangedSubview(f)
        more.isHidden = options.arrangedSubviews.count >= 10
        if focus { f.becomeFirstResponder() }
    }

    private func send(_ button: UIButton) {
        let q = (question.text ?? "").trimmingCharacters(in: .whitespaces)
        let opts = options.arrangedSubviews.compactMap { ($0 as? UITextField)?.text?.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        if q.isEmpty { return toast("Введите вопрос") }
        if opts.count < 2 { return toast("Нужно хотя бы два варианта") }
        button.isEnabled = false
        Core.shared.call("send_poll", ["conversation_id": cid, "question": q, "options": opts, "anonymous": anonymous, "multiple": multiple]) { [weak self] r in
            button.isEnabled = true
            switch r {
            case .failure(let e): self?.toast(e.message)
            case .success: self?.dismiss(animated: !Theme.reducedMotion)
            }
        }
    }
}
