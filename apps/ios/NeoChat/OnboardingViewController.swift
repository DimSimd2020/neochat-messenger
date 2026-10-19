import UIKit

/// Sign in, registration and recovery. On a new phone the password restores the account
/// keys and the history from the password-protected copy on the server.
final class OnboardingViewController: UIViewController, UITextFieldDelegate {
    private enum Mode { case login, register, recover }

    private let done: () -> Void
    private var mode = Mode.login
    private var busy = false

    private let scroll = UIScrollView()
    private let stack = UIStackView()
    private let heading = label("", 28, .bold)
    private let hint = label("", 15, color: Theme.muted)
    private let name = Field("Имя")
    private let username = Field("Никнейм")
    private let password = Field("Пароль", secure: true)
    private let code = Field("Код восстановления")
    private let loginCode = Field("Код из чата «NeoChat уведомления»")
    private let error = label("", 14, color: Theme.warning)
    private let submit = PillButton("Войти")
    private let switchMode = LinkButton("Создать аккаунт")
    private let recoverLink = LinkButton("Восстановить доступ", color: Theme.muted)
    private var secureCover: UIView?

    init(status: JSON, done: @escaping () -> Void) {
        self.done = done
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
        for v in [heading, hint, name, username, password, code, loginCode, error, submit, switchMode, recoverLink] { stack.addArrangedSubview(v) }
        stack.setCustomSpacing(18, after: error)
        for f in [name, username, password, code, loginCode] { f.delegate = self }
        name.autocapitalizationType = .words
        name.textContentType = .givenName
        loginCode.keyboardType = .numberPad
        loginCode.textContentType = .oneTimeCode
        loginCode.isHidden = true
        username.textContentType = .username
        submit.addTarget(self, action: #selector(onSubmit), for: .touchUpInside)
        switchMode.addTarget(self, action: #selector(onSwitch), for: .touchUpInside)
        recoverLink.addTarget(self, action: #selector(onRecover), for: .touchUpInside)
        setMode(mode)
    }

    private func setMode(_ m: Mode) {
        mode = m
        code.isHidden = m != .recover
        name.isHidden = m != .register
        if m != .login { loginCode.isHidden = true }
        password.placeholder = m == .recover ? "Новый пароль" : "Пароль"
        password.accessibilityLabel = password.placeholder
        password.textContentType = m == .login ? .password : .newPassword
        switch m {
        case .login: set("Вход", "", "Войти")
        case .register: set("Регистрация", "Пароль — не меньше 8 символов.", "Создать аккаунт")
        case .recover: set("Восстановление доступа", "Остальные устройства будут отключены, переписка не перенесётся.", "Восстановить")
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
        let order = [name, username, password, code, loginCode].filter { !$0.isHidden }
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
        if user.isEmpty { return showError("Введите никнейм") }
        if pass.isEmpty { return showError("Введите пароль") }
        if mode != .login && pass.count < 8 { return showError("Пароль — не меньше 8 символов") }
        var p: JSON = ["username": user, "device_name": UIDevice.current.name, "platform": "ios"]
        let method: String
        switch mode {
        case .login:
            method = "login"
            p["password"] = pass
            if !loginCode.isHidden { p["code"] = (loginCode.text ?? "").trimmingCharacters(in: .whitespaces) }
        case .register:
            let n = (name.text ?? "").trimmingCharacters(in: .whitespaces)
            if n.isEmpty { return showError("Введите имя") }
            method = "register"
            p["password"] = pass
            p["display_name"] = n
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
            case .failure(let e):
                // Two-factor sign-in: the code arrives in the system chat on the other devices.
                if e.code == "code_required" {
                    self.loginCode.isHidden = false
                    self.loginCode.becomeFirstResponder()
                }
                self.showError(e.message)
            case .success(let v):
                self.password.text = ""
                self.code.text = ""
                self.loginCode.text = ""
                self.loginCode.isHidden = true
                let res = v as? JSON ?? [:]
                if m == .register || m == .recover { self.showRecovery(res.str("recovery_code")) } else { self.done() }
            }
        }
    }

    // MARK: recovery code

    private func showRecovery(_ codeText: String) {
        reset()
        setSecure(true)
        stack.addArrangedSubview(label("Сохраните код восстановления", 24, .bold))
        stack.addArrangedSubview(label("Код вернёт доступ к аккаунту, если все устройства потеряны. Переписка этим кодом не восстанавливается: её копия зашифрована паролем. Запишите код и храните отдельно от телефона.", 15, color: Theme.muted))
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

}
