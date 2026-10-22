import UIKit

extension Notification.Name { static let emailChanged = Notification.Name("NeoChatEmailChanged") }

func registrationServerParams(_ input: JSON) -> JSON {
    var params = input
    #if DEBUG
    if let server = ProcessInfo.processInfo.environment["NEOCHAT_TEST_SERVER"], !server.isEmpty { params["server"] = server }
    #endif
    return params
}

final class EmailVerificationView: UIStackView {
    private let registration: Bool
    private let done: () -> Void
    private let address = Field("Электронная почта")
    private let info = label("Почта видна только вам. Подтвердите адрес по ссылке из письма.", 14, color: Theme.muted)
    private let send = PillButton("Отправить ссылку")
    private let check = PillButton("Я подтвердил почту")
    private let change = LinkButton("Изменить адрес")
    private var ticket = ""
    private var verified = false
    private var busy = false
    private var resendAt = Date.distantPast
    var verifiedTicket: String { verified ? ticket : "" }

    init(registration: Bool, done: @escaping () -> Void = {}) {
        self.registration = registration
        self.done = done
        super.init(frame: .zero)
        axis = .vertical; spacing = 10
        address.keyboardType = .emailAddress
        address.textContentType = .emailAddress
        address.autocapitalizationType = .none
        address.autocorrectionType = .no
        for v in [address,info,send,check,change] { addArrangedSubview(v) }
        check.isHidden = true; change.isHidden = true
        send.addTarget(self, action: #selector(sendLink), for: .touchUpInside)
        check.addTarget(self, action: #selector(refresh), for: .touchUpInside)
        change.addTarget(self, action: #selector(changeAddress), for: .touchUpInside)
    }
    required init(coder: NSCoder) { fatalError() }

    private func setBusy(_ value: Bool) {
        busy = value; send.isEnabled = !value; check.isEnabled = !value; change.isEnabled = !value
    }

    @objc private func sendLink() {
        guard !busy else { return }
        let email = (address.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !email.isEmpty else { info.text = "Введите электронную почту"; address.becomeFirstResponder(); return }
        guard Date() >= resendAt else { info.text = "Повторная отправка доступна через минуту после предыдущего письма."; return }
        setBusy(true)
        Core.shared.call(registration ? "registration_email_start" : "email_start", registrationServerParams(["email":email])) { [weak self] result in
            guard let self else { return }
            self.setBusy(false)
            switch result {
            case .failure(let error): self.info.text = error.message
            case .success(let value):
                self.ticket = (value as? JSON)?.str("ticket") ?? ""
                self.resendAt = Date().addingTimeInterval(60)
                self.address.isEnabled = false
                self.send.setTitle("Отправить повторно", for: .normal)
                self.check.isHidden = false; self.change.isHidden = !self.registration
                self.info.text = "Письмо отправлено. Откройте ссылку, нажмите «Продолжить», подтвердите привязку и вернитесь в NeoChat."
            }
        }
    }

    @objc private func refresh() {
        guard !busy, !ticket.isEmpty else { return }
        setBusy(true)
        Core.shared.call(registration ? "registration_email_status" : "email_status", registrationServerParams(["ticket":ticket])) { [weak self] result in
            guard let self else { return }
            self.setBusy(false)
            switch result {
            case .failure(let error): self.info.text = error.message
            case .success(let value):
                guard (value as? JSON)?.bool("verified") == true else { self.info.text = "Адрес ещё не подтверждён. Откройте письмо и завершите подтверждение."; return }
                self.verified = true
                self.info.text = "Почта подтверждена"
                self.send.isHidden = true; self.check.isHidden = true
                self.done()
            }
        }
    }

    @objc private func changeAddress() {
        ticket = ""; verified = false; address.isEnabled = true
        send.isHidden = false; check.isHidden = true; change.isHidden = true
        info.text = "Введите адрес и отправьте новую ссылку."
    }
}

final class EmailBindingViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.canvas
        let title = label("Привяжите электронную почту",22,.bold)
        let message = label("Подтвердите почту, чтобы у аккаунта был ваш контактный адрес. Пароль, ключи и переписка не изменятся.",15,color:Theme.muted)
        let form = EmailVerificationView(registration:false) { [weak self] in
            self?.dismiss(animated:!Theme.reducedMotion) { NotificationCenter.default.post(name:.emailChanged,object:nil) }
        }
        let later = LinkButton("Позже")
        later.addTarget(self,action:#selector(close),for:.touchUpInside)
        let stack = UIStackView(arrangedSubviews:[title,message,form,later])
        stack.axis = .vertical; stack.spacing = 16; stack.translatesAutoresizingMaskIntoConstraints = false
        let scroll = UIScrollView(); scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll); scroll.addSubview(stack)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo:view.safeAreaLayoutGuide.topAnchor),scroll.bottomAnchor.constraint(equalTo:view.keyboardLayoutGuide.topAnchor),
            scroll.leadingAnchor.constraint(equalTo:view.leadingAnchor),scroll.trailingAnchor.constraint(equalTo:view.trailingAnchor),
            stack.topAnchor.constraint(equalTo:scroll.contentLayoutGuide.topAnchor,constant:24),stack.bottomAnchor.constraint(equalTo:scroll.contentLayoutGuide.bottomAnchor,constant:-24),
            stack.leadingAnchor.constraint(equalTo:scroll.contentLayoutGuide.leadingAnchor,constant:24),stack.trailingAnchor.constraint(equalTo:scroll.contentLayoutGuide.trailingAnchor,constant:-24),
            stack.widthAnchor.constraint(equalTo:scroll.frameLayoutGuide.widthAnchor,constant:-48)
        ])
    }
    @objc private func close() { dismiss(animated:!Theme.reducedMotion) }
}
