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
        let first = Field("Имя (обязательно)")
        first.autocapitalizationType = .words
        let last = Field("Фамилия (необязательно)")
        last.autocapitalizationType = .words
        let user = Field("Имя пользователя")
        let err = label("", 14, color: Theme.warning)
        err.isHidden = true
        let add = PillButton("Добавить")
        [first, last, user].forEach(stack.addArrangedSubview)
        muted("Поиск идёт только по точному имени пользователя: публичного каталога нет.")
        stack.addArrangedSubview(err)
        stack.addArrangedSubview(add)
        add.addAction(UIAction { [weak self] _ in
            let f = (first.text ?? "").trimmingCharacters(in: .whitespaces)
            var u = (user.text ?? "").trimmingCharacters(in: .whitespaces)
            if u.hasPrefix("@") { u.removeFirst() }
            if f.isEmpty { err.text = "Введите имя"; err.isHidden = false; return }
            if u.isEmpty { err.text = "Введите имя пользователя"; err.isHidden = false; return }
            add.isEnabled = false
            Core.shared.call("resolve_contact", ["username": u, "first_name": f, "last_name": (last.text ?? "").trimmingCharacters(in: .whitespaces)]) { r in
                add.isEnabled = true
                switch r {
                case .failure(let e):
                    err.text = e.code == "not_found" ? "Пользователь с таким именем не найден" : e.message
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
    private var group = false
    private var selected: [String] = []
    private let mode = UISegmentedControl(items: ["Личный", "Группа"])
    private let titleField = Field("Название группы")
    private let list = UIStackView()
    private let create = PillButton("Создать группу")

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Новый чат"
        mode.selectedSegmentIndex = 0
        mode.addAction(UIAction { [weak self] _ in self?.setGroup(self?.mode.selectedSegmentIndex == 1) }, for: .valueChanged)
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
        list.axis = .vertical
        stack.addArrangedSubview(list)
        create.isHidden = true
        create.addAction(UIAction { [weak self] _ in self?.createGroup() }, for: .touchUpInside)
        stack.addArrangedSubview(create)
        reload()
    }

    private func setGroup(_ g: Bool) {
        group = g
        title = g ? "Новая группа" : "Новый чат"
        titleField.isHidden = !g
        create.isHidden = !g
        reload()
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
        if let i = selected.firstIndex(of: uid) { selected.remove(at: i) } else if selected.count >= 15 { toast("В группе может быть до 15 других участников"); return } else { selected.append(uid) }
        create.setTitle(selected.isEmpty ? "Создать группу" : "Создать группу (\(selected.count))", for: .normal)
        reload()
    }

    private func createGroup() {
        let t = (titleField.text ?? "").trimmingCharacters(in: .whitespaces)
        if t.isEmpty { return toast("Введите название группы") }
        if selected.isEmpty { return toast("Выберите участников") }
        create.isEnabled = false
        Core.shared.call("create_group", ["title": t, "member_ids": selected]) { [weak self] r in
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
    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Настройки"
        heading("Тема")
        let theme = UISegmentedControl(items: ["Устройство", "День", "Ночь"])
        theme.selectedSegmentIndex = Theme.mode.rawValue
        theme.addAction(UIAction { _ in Theme.mode = Theme.Mode(rawValue: theme.selectedSegmentIndex) ?? .system }, for: .valueChanged)
        stack.addArrangedSubview(theme)
        muted("Размер текста следует системной настройке.")
        let notify = toggleRow("Уведомления о новых сообщениях", on: UserDefaults.standard.object(forKey: "notifications") as? Bool ?? true) { on in
            UserDefaults.standard.set(on, forKey: "notifications")
        }
        stack.addArrangedSubview(notify)
        muted("В уведомлении видно только название чата. Мгновенные уведомления недоступны в этой сборке: новые сообщения приходят, когда приложение открыто или iOS разрешает фоновое обновление.")
        var receiptsSwitch: UISwitch?
        let receipts = toggleRow("Отчёты о прочтении", on: true) { on in
            Core.shared.call("set_settings", ["read_receipts": on]) { [weak self] r in
                if case .failure(let e) = r { self?.toast(e.message); receiptsSwitch?.setOn(!on, animated: true) }
            }
        }
        receiptsSwitch = receipts.arrangedSubviews.last as? UISwitch
        stack.addArrangedSubview(receipts)
        muted("Если выключить, собеседники не увидят, что вы прочитали сообщение.")
        Core.shared.call("get_settings") { r in
            if case .success(let v) = r { receiptsSwitch?.isOn = (v as? JSON)?["read_receipts"] as? Bool ?? true }
        }
        var twoFactorSwitch: UISwitch?
        let twoFactor = toggleRow("Подтверждение входа", on: false) { on in
            Core.shared.call("set_two_factor", ["enabled": on]) { [weak self] r in
                if case .failure(let e) = r { self?.toast(e.message); twoFactorSwitch?.setOn(!on, animated: true) }
            }
        }
        twoFactorSwitch = twoFactor.arrangedSubviews.last as? UISwitch
        stack.addArrangedSubview(twoFactor)
        muted("При входе на новом устройстве понадобится код. Он придёт в чат «NeoChat уведомления» на ваших устройствах.")
        Core.shared.call("account_settings") { r in
            if case .success(let v) = r { twoFactorSwitch?.isOn = (v as? JSON)?["two_factor"] as? Bool ?? false }
        }
        let logout = PillButton("Выйти из аккаунта", primary: false)
        logout.setTitleColor(Theme.warning, for: .normal)
        logout.addAction(UIAction { [weak self] _ in self?.confirmLogout() }, for: .touchUpInside)
        stack.setCustomSpacing(24, after: stack.arrangedSubviews.last!)
        stack.addArrangedSubview(logout)
    }

    private func toggleRow(_ text: String, on: Bool, _ change: @escaping (Bool) -> Void) -> UIStackView {
        let s = UISwitch()
        s.isOn = on
        s.onTintColor = Theme.action
        s.addAction(UIAction { _ in change(s.isOn) }, for: .valueChanged)
        let row = UIStackView(arrangedSubviews: [label(text, 16), s])
        row.alignment = .center
        row.spacing = 12
        return row
    }

    private func confirmLogout() {
        let a = UIAlertController(title: "Выйти из аккаунта?", message: "Ключи и переписка на этом телефоне будут удалены.", preferredStyle: .actionSheet)
        a.addAction(UIAlertAction(title: "Выйти", style: .destructive) { [weak self] _ in
            Core.shared.call("logout") { _ in
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
                self.stack.addArrangedSubview(self.personRow(c.str("title"), members.first { !$0.bool("me") }.map { "@" + $0.str("username") } ?? "", seed: peer))
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
            let gone = c.bool("left") || c.bool("archived")
            self.stack.addArrangedSubview(self.personRow(c.str("title"), "Группа", seed: self.cid))
            self.heading("Участники: \(c.int("member_count"))")
            for m in members {
                let uid = m.str("user_id")
                let warn = m.bool("key_changed")
                let sub = warn ? "Ключ изменился — сверьте код в личном чате" : m.bool("me") ? "это вы" : "@" + m.str("username")
                let canKick = owner && !gone && !m.bool("me")
                self.stack.addArrangedSubview(self.personRow(m.str("display_name"), sub, seed: uid, subColor: warn ? Theme.warning : Theme.muted, action: canKick ? {
                    let a = UIAlertController(title: "Исключить \(m.str("display_name"))?", message: "Новые сообщения ему не придут.", preferredStyle: .actionSheet)
                    a.addAction(UIAlertAction(title: "Исключить", style: .destructive) { _ in
                        let ids = members.filter { !$0.bool("me") && $0.str("user_id") != uid }.map { $0.str("user_id") }
                        self.call("group_set_members", ["conversation_id": self.cid, "member_ids": ids], ok: "Участник исключён. Новые сообщения ему не придут")
                    })
                    a.addAction(UIAlertAction(title: "Отмена", style: .cancel))
                    self.present(a, animated: true)
                } : nil))
            }
            if owner && !gone {
                let name = Field("Название группы")
                name.text = c.str("title")
                self.stack.addArrangedSubview(name)
                let rename = PillButton("Переименовать", primary: false)
                rename.addAction(UIAction { _ in
                    let t = (name.text ?? "").trimmingCharacters(in: .whitespaces)
                    if t.isEmpty { return self.toast("Введите название группы") }
                    self.call("group_rename", ["conversation_id": self.cid, "title": t], ok: "Название изменено")
                }, for: .touchUpInside)
                self.stack.addArrangedSubview(rename)
                let add = PillButton("Добавить участников", primary: false)
                add.showsMenuAsPrimaryAction = true
                Core.shared.call("list_contacts") { lr in
                    guard case .success(let lv) = lr else { return }
                    let inGroup = Set(members.map { $0.str("user_id") })
                    let cands = (lv as? [JSON] ?? []).filter { !inGroup.contains($0.str("user_id")) }
                    add.menu = UIMenu(children: cands.isEmpty ? [UIAction(title: "Все контакты уже в группе", attributes: .disabled) { _ in }] : cands.map { cand in
                        UIAction(title: cand.str("display_name")) { _ in
                            let ids = members.filter { !$0.bool("me") }.map { $0.str("user_id") } + [cand.str("user_id")]
                            self.call("group_set_members", ["conversation_id": self.cid, "member_ids": ids], ok: "Участник добавлен. Прошлые сообщения ему не видны")
                        }
                    })
                }
                self.stack.addArrangedSubview(add)
            }
            self.muted("Файлы хранятся на сервере в зашифрованном виде 30 дней.")
            if !gone {
                let leave = PillButton("Выйти из группы", primary: false)
                leave.setTitleColor(Theme.warning, for: .normal)
                leave.addAction(UIAction { _ in
                    let a = UIAlertController(title: owner ? "Выйти: группа станет архивной для всех" : "Выйти: новые сообщения перестанут приходить", message: nil, preferredStyle: .actionSheet)
                    a.addAction(UIAlertAction(title: "Выйти", style: .destructive) { _ in self.call("group_leave", ["conversation_id": self.cid], close: true) })
                    a.addAction(UIAlertAction(title: "Отмена", style: .cancel))
                    self.present(a, animated: true)
                }, for: .touchUpInside)
                self.stack.addArrangedSubview(leave)
            }
        }
    }
}
