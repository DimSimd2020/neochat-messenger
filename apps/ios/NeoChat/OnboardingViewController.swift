import UIKit

/// Sign in, registration and recovery. On a phone without keys "Войти" links it
/// to the account and waits for approval on the primary device.
final class OnboardingViewController: UIViewController, UITextFieldDelegate {
    private enum Mode { case login, register, recover }

    private let done: () -> Void
    private let hasKeys: Bool
    private var mode = Mode.login
    private var busy = false

    private let scroll = UIScrollView()
    private let stack = UIStackView()
    private let heading = label("", 28, .bold)
    private let hint = label("", 15, color: Theme.muted)
    private let username = Field("Имя пользователя")
    private let password = Field("Пароль", secure: true)
    private let code = Field("Код восстановления")
    private let error = label("", 14, color: Theme.warning)
    private let submit = PillButton("Войти")
    private let switchMode = LinkButton("Создать аккаунт")
    private let recoverLink = LinkButton("Восстановить доступ", color: Theme.muted)
    private var pollTimer: Timer?
    private var secureCover: UIView?

    init(status: JSON, done: @escaping () -> Void) {
        self.done = done
        hasKeys = status.bool("has_device_keys")
        super.init(nibName: nil, bundle: nil)
        username.text = status.str("username")
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
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
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 24),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -24),
            stack.leadingAnchor.constraint(equalTo: scroll.frameLayoutGuide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: scroll.frameLayoutGuide.trailingAnchor, constant: -20),
        ])
        showForm()
    }

    private func brand() -> UIView {
        let logo = UIImageView(image: UIImage(named: "Logo"))
        logo.widthAnchor.constraint(equalToConstant: 34).isActive = true
        logo.heightAnchor.constraint(equalToConstant: 34).isActive = true
        let row = UIStackView(arrangedSubviews: [logo, label("NeoChat", 26, .bold)])
        row.spacing = 12
        row.alignment = .center
        return row
    }

    private func reset() {
        stack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        stack.addArrangedSubview(brand())
        stack.setCustomSpacing(28, after: stack.arrangedSubviews[0])
    }

    private func showForm() {
        reset()
        setSecure(false)
        for v in [heading, hint, username, password, code, error, submit, switchMode, recoverLink] { stack.addArrangedSubview(v) }
        stack.setCustomSpacing(18, after: error)
        for f in [username, password, code] { f.delegate = self }
        username.textContentType = .username
        submit.addTarget(self, action: #selector(onSubmit), for: .touchUpInside)
        switchMode.addTarget(self, action: #selector(onSwitch), for: .touchUpInside)
        recoverLink.addTarget(self, action: #selector(onRecover), for: .touchUpInside)
        setMode(mode)
    }

    private func setMode(_ m: Mode) {
        mode = m
        code.isHidden = m != .recover
        password.placeholder = m == .recover ? "Новый пароль" : "Пароль"
        password.accessibilityLabel = password.placeholder
        password.textContentType = m == .login ? .password : .newPassword
        switch m {
        case .login: set("Вход", "", "Войти")
        case .register: set("Регистрация", "Пароль — не меньше 8 символов.", "Создать аккаунт")
        case .recover: set("Восстановление доступа", "Остальные устройства будут отключены. Переписка с них не переносится.", "Восстановить")
        }
        switchMode.setTitle(m == .login ? "Создать аккаунт" : "Уже есть аккаунт? Войти", for: .normal)
        recoverLink.isHidden = m != .login
        showError("")
    }

    private func set(_ h: String, _ t: String, _ b: String) {
        heading.text = h
        hint.text = t
        hint.isHidden = t.isEmpty
        submit.setTitle(b, for: .normal)
    }

    private func showError(_ t: String) {
        error.text = t
        error.isHidden = t.isEmpty
        if !t.isEmpty { UIAccessibility.post(notification: .announcement, argument: t) }
    }

    @objc private func onSwitch() { if !busy { setMode(mode == .login ? .register : .login) } }
    @objc private func onRecover() { if !busy { setMode(.recover) } }

    func textFieldShouldReturn(_ f: UITextField) -> Bool {
        let order = [username, password, code].filter { !$0.isHidden }
        if let i = order.firstIndex(where: { $0 === f }), i + 1 < order.count { order[i + 1].becomeFirstResponder() } else { onSubmit() }
        return true
    }

    private func setBusy(_ b: Bool) {
        busy = b
        submit.isEnabled = !b
        if b { submit.setTitle("Подождите…", for: .normal) } else { setMode(mode) }
    }

    @objc private func onSubmit() {
        if busy { return }
        var user = (username.text ?? "").trimmingCharacters(in: .whitespaces)
        if user.hasPrefix("@") { user.removeFirst() }
        let pass = password.text ?? ""
        if user.isEmpty { return showError("Введите имя пользователя") }
        if pass.isEmpty { return showError("Введите пароль") }
        if mode != .login && pass.count < 8 { return showError("Пароль — не меньше 8 символов") }
        var p: JSON = ["username": user, "device_name": UIDevice.current.name, "platform": "ios"]
        let linking = mode == .login && !hasKeys
        let method: String
        switch mode {
        case .login:
            method = linking ? "link_start" : "login"
            p["password"] = pass
        case .register:
            method = "register"
            p["password"] = pass
        case .recover:
            let c = (code.text ?? "").trimmingCharacters(in: .whitespaces)
            if c.isEmpty { return showError("Введите код восстановления") }
            method = "recover"
            p["new_password"] = pass
            p["recovery_code"] = c
        }
        showError("")
        view.endEditing(true)
        setBusy(true)
        let m = mode
        Core.shared.call(method, p) { [weak self] r in
            guard let self else { return }
            self.setBusy(false)
            switch r {
            case .failure(let e): self.showError(e.message)
            case .success(let v):
                self.password.text = ""
                self.code.text = ""
                let res = v as? JSON ?? [:]
                if m == .register || m == .recover { self.showRecovery(res.str("recovery_code")) } else if linking { self.showLink(res) } else { self.done() }
            }
        }
    }

    // MARK: recovery code

    private func showRecovery(_ codeText: String) {
        reset()
        setSecure(true)
        stack.addArrangedSubview(label("Сохраните код восстановления", 24, .bold))
        stack.addArrangedSubview(label("Код вернёт доступ к аккаунту, если все устройства потеряны. Переписка этим кодом не восстанавливается: она хранится только на ваших устройствах. Запишите код и храните отдельно от телефона.", 15, color: Theme.muted))
        let c = label(codeText, 20, .semibold, color: Theme.accent)
        c.font = UIFontMetrics(forTextStyle: .body).scaledFont(for: .monospacedSystemFont(ofSize: 20, weight: .semibold))
        c.accessibilityLabel = "Код восстановления: " + codeText
        let box = UIView()
        box.glass(20)
        c.translatesAutoresizingMaskIntoConstraints = false
        box.addSubview(c)
        NSLayoutConstraint.activate([
            c.topAnchor.constraint(equalTo: box.topAnchor, constant: 16), c.bottomAnchor.constraint(equalTo: box.bottomAnchor, constant: -16),
            c.leadingAnchor.constraint(equalTo: box.leadingAnchor, constant: 18), c.trailingAnchor.constraint(equalTo: box.trailingAnchor, constant: -18),
        ])
        stack.addArrangedSubview(box)
        let copy = PillButton("Скопировать код", primary: false)
        copy.addAction(UIAction { [weak self] _ in
            // Expires so the code does not stay in the clipboard.
            UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: codeText]], options: [.localOnly: true, .expirationDate: Date(timeIntervalSinceNow: 120)])
            self?.toast("Код скопирован")
        }, for: .touchUpInside)
        stack.addArrangedSubview(copy)
        let saved = UISwitch()
        let row = UIStackView(arrangedSubviews: [label("Я сохранил код в надёжном месте", 16), saved])
        row.alignment = .center
        row.spacing = 12
        stack.addArrangedSubview(row)
        let proceed = PillButton("Продолжить")
        proceed.isEnabled = false
        proceed.alpha = 0.5
        saved.addAction(UIAction { _ in proceed.isEnabled = saved.isOn; proceed.alpha = saved.isOn ? 1 : 0.5 }, for: .valueChanged)
        proceed.addAction(UIAction { [weak self] _ in self?.setSecure(false); self?.done() }, for: .touchUpInside)
        stack.addArrangedSubview(proceed)
    }

    /// Hide the recovery code from the app switcher snapshot.
    private func setSecure(_ on: Bool) {
        if on {
            NotificationCenter.default.addObserver(self, selector: #selector(coverOn), name: UIApplication.willResignActiveNotification, object: nil)
            NotificationCenter.default.addObserver(self, selector: #selector(coverOff), name: UIApplication.didBecomeActiveNotification, object: nil)
        } else {
            NotificationCenter.default.removeObserver(self, name: UIApplication.willResignActiveNotification, object: nil)
            NotificationCenter.default.removeObserver(self, name: UIApplication.didBecomeActiveNotification, object: nil)
            coverOff()
        }
    }

    @objc private func coverOn() {
        let v = BackgroundView(frame: view.bounds)
        view.addSubview(v)
        secureCover = v
    }

    @objc private func coverOff() {
        secureCover?.removeFromSuperview()
        secureCover = nil
    }

    // MARK: linking

    private func showLink(_ res: JSON) {
        reset()
        stack.addArrangedSubview(label("Подтвердите на основном устройстве", 24, .bold))
        stack.addArrangedSubview(label("Это новый телефон для вашего аккаунта. Откройте NeoChat на основном устройстве: Устройства → Сканировать QR-код, и наведите камеру на этот код. Прошлая переписка на этот телефон не переносится.", 15, color: Theme.muted))
        let qr = QrView(rows: res["qr_rows"] as? [String] ?? [])
        let holder = UIView()
        qr.translatesAutoresizingMaskIntoConstraints = false
        holder.addSubview(qr)
        NSLayoutConstraint.activate([
            qr.widthAnchor.constraint(equalToConstant: 240), qr.heightAnchor.constraint(equalToConstant: 240),
            qr.centerXAnchor.constraint(equalTo: holder.centerXAnchor), qr.topAnchor.constraint(equalTo: holder.topAnchor), qr.bottomAnchor.constraint(equalTo: holder.bottomAnchor),
        ])
        stack.addArrangedSubview(holder)
        let text = res.str("qr")
        let copy = PillButton("Скопировать код привязки", primary: false)
        copy.addAction(UIAction { [weak self] _ in UIPasteboard.general.string = text; self?.toast("Код скопирован") }, for: .touchUpInside)
        stack.addArrangedSubview(copy)
        let state = label("Ожидаем подтверждения… Код действует \(max(1, res.int("expires_in") / 60)) мин.", 14, color: Theme.muted)
        stack.addArrangedSubview(state)
        let cancel = PillButton("Отменить", primary: false)
        cancel.addAction(UIAction { [weak self] _ in self?.pollTimer?.invalidate(); self?.showForm() }, for: .touchUpInside)
        stack.addArrangedSubview(cancel)
        pollTimer?.invalidate()
        var inFlight = false
        pollTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] t in
            if inFlight { return }
            inFlight = true
            Core.shared.call("link_poll") { r in
                inFlight = false
                switch r {
                case .failure(let e):
                    if e.code == "network" { return }
                    t.invalidate()
                    state.text = e.message
                    state.textColor = Theme.warning
                case .success(let v):
                    let st = (v as? JSON)?.str("status") ?? "approved"
                    if st == "approved" { t.invalidate(); self?.done() } else if st != "pending" {
                        t.invalidate()
                        state.text = "Привязка отклонена или истекла. Начните заново."
                        state.textColor = Theme.warning
                    }
                }
            }
        }
    }
}

/// QR code from the core's module rows ('1' = dark).
final class QrView: UIView {
    private let rows: [String]

    init(rows: [String]) {
        self.rows = rows
        super.init(frame: .zero)
        backgroundColor = .white
        layer.cornerRadius = 18
        isAccessibilityElement = true
        accessibilityLabel = "QR-код привязки"
    }

    required init?(coder: NSCoder) { fatalError() }

    override func draw(_ rect: CGRect) {
        guard !rows.isEmpty, let ctx = UIGraphicsGetCurrentContext() else { return }
        let n = CGFloat(rows.count)
        let cell = (bounds.width - 16) / (n + 8)
        let off = 8 + 4 * cell
        ctx.setFillColor(UIColor.black.cgColor)
        for (y, row) in rows.enumerated() {
            for (x, ch) in row.enumerated() where ch == "1" {
                ctx.fill(CGRect(x: off + CGFloat(x) * cell, y: off + CGFloat(y) * cell, width: cell + 0.5, height: cell + 0.5))
            }
        }
    }
}
