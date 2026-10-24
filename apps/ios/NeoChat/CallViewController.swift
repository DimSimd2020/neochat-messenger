import AVFoundation
import UIKit
import WebRTC

/// Full-screen call: the peer's video or avatar, a local preview and the controls.
/// Closes itself when the call is over; the call keeps running without it.
final class CallViewController: UIViewController {
    private let remote = RTCMTLVideoView()
    private let local = RTCMTLVideoView()
    private let pulse = PulseView()
    private let avatar = AvatarView(size: 120)
    private let name = label("", 28, .semibold, color: .white)
    private let status = label("", 17, color: UIColor.white.withAlphaComponent(0.72))
    private let center = UIStackView()
    private let top = UIStackView()
    private let topName = label("", 19, .semibold, color: .white)
    private let topStatus = label("", 14, color: UIColor.white.withAlphaComponent(0.8))
    private let topShade = CAGradientLayer()
    private let flip = UIButton(type: .system)
    private let back = UIButton(type: .system)
    private let controls = UIStackView()
    private lazy var mic = CallButton("mic.fill", "Микрофон") { CallManager.shared.setMic(!CallManager.shared.micOn) }
    private lazy var camera = CallButton("video.fill", "Камера") { [weak self] in self?.toggleCamera() }
    private lazy var speaker = CallButton("speaker.wave.2.fill", "Динамик") { CallManager.shared.setSpeaker(!CallManager.shared.speakerOn) }
    private lazy var end = CallButton("phone.down.fill", "Завершить", kind: .end) { CallManager.shared.hangup() }
    private lazy var decline = CallButton("phone.down.fill", "Отклонить", kind: .end) { CallManager.shared.hangup() }
    private lazy var accept = CallButton("phone.fill", "Ответить", kind: .accept) { [weak self] in self?.answer(video: false) }
    private lazy var acceptVideo = CallButton("video.fill", "С видео", kind: .accept) { [weak self] in self?.answer(video: true) }
    private var tick: Timer?
    private var closing = false

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.rgb(0x111B33)
        view.accessibilityViewIsModal = true

        remote.videoContentMode = .scaleAspectFill
        remote.isHidden = true
        remote.isAccessibilityElement = true
        remote.accessibilityLabel = "Видео собеседника"
        local.videoContentMode = .scaleAspectFill
        local.layer.cornerRadius = 18
        local.layer.cornerCurve = .continuous
        local.clipsToBounds = true
        local.isHidden = true
        local.isAccessibilityElement = true
        local.accessibilityLabel = "Ваша камера"

        let ring = UIView()
        ring.translatesAutoresizingMaskIntoConstraints = false
        pulse.translatesAutoresizingMaskIntoConstraints = false
        ring.addSubview(pulse)
        ring.addSubview(avatar)
        NSLayoutConstraint.activate([
            ring.widthAnchor.constraint(equalToConstant: 220), ring.heightAnchor.constraint(equalToConstant: 220),
            pulse.topAnchor.constraint(equalTo: ring.topAnchor), pulse.bottomAnchor.constraint(equalTo: ring.bottomAnchor),
            pulse.leadingAnchor.constraint(equalTo: ring.leadingAnchor), pulse.trailingAnchor.constraint(equalTo: ring.trailingAnchor),
            avatar.centerXAnchor.constraint(equalTo: ring.centerXAnchor), avatar.centerYAnchor.constraint(equalTo: ring.centerYAnchor),
        ])
        name.textAlignment = .center
        status.textAlignment = .center
        status.font = .monospacedDigitSystemFont(ofSize: 17, weight: .regular)
        [ring, name, status].forEach(center.addArrangedSubview)
        center.axis = .vertical
        center.alignment = .center
        center.spacing = 6
        center.setCustomSpacing(10, after: ring)

        topShade.colors = [UIColor.black.withAlphaComponent(0.55).cgColor, UIColor.clear.cgColor]
        view.layer.addSublayer(topShade)
        topStatus.font = .monospacedDigitSystemFont(ofSize: 14, weight: .regular)
        [topName, topStatus].forEach(top.addArrangedSubview)
        top.axis = .vertical
        top.spacing = 2
        top.isHidden = true

        flip.setImage(UIImage(systemName: "arrow.triangle.2.circlepath.camera", withConfiguration: UIImage.SymbolConfiguration(pointSize: 18, weight: .semibold)), for: .normal)
        flip.tintColor = .white
        flip.backgroundColor = UIColor.black.withAlphaComponent(0.35)
        flip.layer.cornerRadius = 22
        flip.accessibilityLabel = "Сменить камеру"
        flip.addAction(UIAction { _ in CallManager.shared.switchCamera() }, for: .touchUpInside)

        back.setImage(UIImage(systemName: "chevron.left", withConfiguration: UIImage.SymbolConfiguration(pointSize: 20, weight: .semibold)), for: .normal)
        back.tintColor = .white
        back.backgroundColor = UIColor.black.withAlphaComponent(0.35)
        back.layer.cornerRadius = 22
        back.accessibilityLabel = "Вернуться в чат, продолжая звонок"
        back.addAction(UIAction { [weak self] _ in
            let id = CallManager.shared.call.str("conversation_id")
            guard !id.isEmpty else { return }
            CallManager.shared.minimized = true
            self?.dismiss(animated: !Theme.reducedMotion) {
                NotificationCenter.default.post(name: .openConversation, object: nil, userInfo: ["id": id])
            }
        }, for: .touchUpInside)

        for b in [mic, camera, speaker, end, decline, acceptVideo, accept] { controls.addArrangedSubview(b) }
        controls.distribution = .fillEqually
        controls.alignment = .top

        for v in [remote, center, top, local, flip, controls, back] as [UIView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(v)
        }
        let safe = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            remote.topAnchor.constraint(equalTo: view.topAnchor), remote.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            remote.leadingAnchor.constraint(equalTo: view.leadingAnchor), remote.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            center.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            center.centerYAnchor.constraint(equalTo: view.centerYAnchor, constant: -60),
            center.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
            top.topAnchor.constraint(equalTo: safe.topAnchor, constant: 12),
            top.leadingAnchor.constraint(equalTo: back.trailingAnchor, constant: 12),
            top.trailingAnchor.constraint(lessThanOrEqualTo: flip.leadingAnchor, constant: -12),
            flip.topAnchor.constraint(equalTo: safe.topAnchor, constant: 8),
            flip.trailingAnchor.constraint(equalTo: safe.trailingAnchor, constant: -16),
            flip.widthAnchor.constraint(equalToConstant: 44), flip.heightAnchor.constraint(equalToConstant: 44),
            back.topAnchor.constraint(equalTo: safe.topAnchor, constant: 8),
            back.leadingAnchor.constraint(equalTo: safe.leadingAnchor, constant: 16),
            back.widthAnchor.constraint(equalToConstant: 44), back.heightAnchor.constraint(equalToConstant: 44),
            local.trailingAnchor.constraint(equalTo: safe.trailingAnchor, constant: -16),
            local.bottomAnchor.constraint(equalTo: controls.topAnchor, constant: -24),
            local.widthAnchor.constraint(equalToConstant: 108), local.heightAnchor.constraint(equalToConstant: 144),
            controls.leadingAnchor.constraint(equalTo: safe.leadingAnchor, constant: 12),
            controls.trailingAnchor.constraint(equalTo: safe.trailingAnchor, constant: -12),
            controls.bottomAnchor.constraint(equalTo: safe.bottomAnchor, constant: -20),
        ])
        NotificationCenter.default.addObserver(forName: CallManager.changed, object: nil, queue: .main) { [weak self] _ in self?.sync() }
        sync()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        topShade.frame = CGRect(x: 0, y: 0, width: view.bounds.width, height: view.safeAreaInsets.top + 110)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        CallManager.shared.remoteRenderer.target = remote
        CallManager.shared.localRenderer.target = local
        tick = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in self?.sync() }
        sync()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        CallManager.shared.remoteRenderer.target = nil
        CallManager.shared.localRenderer.target = nil
        tick?.invalidate()
        pulse.stop()
    }

    private func answer(video: Bool) {
        CallManager.withPermissions(video: video, from: self) { cam in CallManager.shared.accept(video: video && cam) }
    }

    private func toggleCamera() {
        let m = CallManager.shared
        if m.cameraOn || m.cameraStarting { return m.setCamera(false) }
        let id = m.callId
        AVCaptureDevice.requestAccess(for: .video) { ok in
            DispatchQueue.main.async {
                guard m.live, m.callId == id else { return }
                ok ? m.setCamera(true) : self.toast("Нет доступа к камере")
            }
        }
    }

    private func statusText() -> String {
        let m = CallManager.shared
        let video = m.call.bool("video")
        switch m.phase {
        case .starting: return "Подготовка звонка…"
        case .outgoing: return "Вызов…"
        case .incoming: return video ? "Входящий видеозвонок" : "Входящий звонок"
        case .connecting: return "Соединение…"
        case .active: return m.reconnecting ? "Восстанавливаем связь…" : clock(Int(Date().timeIntervalSince(m.since)))
        case .ended: return m.endText
        case .idle: return ""
        }
    }

    private func clock(_ sec: Int) -> String {
        let s = max(0, sec)
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%02d:%02d", s / 60, s % 60)
    }

    private func sync() {
        let m = CallManager.shared
        if m.phase == .idle {
            guard !closing else { return }
            closing = true
            return dismiss(animated: !Theme.reducedMotion)
        }
        let peer = m.call.str("peer_name")
        avatar.set(peer, seed: m.call.str("peer_user_id"), avatar: m.call.str("avatar_path"))
        name.text = peer
        topName.text = peer
        let st = statusText()
        status.text = st
        topStatus.text = st
        let video = m.remoteVideo
        remote.isHidden = !video
        top.isHidden = !video
        topShade.isHidden = !video
        center.isHidden = video
        local.isHidden = !m.cameraOn
        // The front camera is shown mirrored, like a mirror.
        local.transform = m.frontCamera ? CGAffineTransform(scaleX: -1, y: 1) : .identity
        flip.isHidden = !m.cameraOn
        flip.isEnabled = !m.cameraStarting
        let ringing = m.phase == .incoming
        for b in [mic, camera, speaker, end] { b.isHidden = !m.live }
        decline.isHidden = !ringing
        accept.isHidden = !ringing
        acceptVideo.isHidden = !(ringing && m.call.bool("video"))
        mic.set(m.micOn ? "mic.fill" : "mic.slash.fill", m.micOn ? "Микрофон" : "Без звука", m.micOn ? "Выключить микрофон" : "Включить микрофон", on: !m.micOn)
        let cameraEnabled = m.cameraOn || m.cameraStarting
        camera.set(cameraEnabled ? "video.fill" : "video.slash.fill", m.cameraStarting ? "Включение…" : "Камера", cameraEnabled ? "Выключить камеру" : "Включить камеру", on: cameraEnabled)
        speaker.set("speaker.wave.2.fill", "Динамик", m.speakerOn ? "Выключить динамик" : "Включить динамик", on: m.speakerOn)
        if (ringing || m.phase == .outgoing) && !Theme.reducedMotion { pulse.start() } else { pulse.stop() }
        if UIAccessibility.isVoiceOverRunning, status.accessibilityLabel != st, m.phase != .active {
            status.accessibilityLabel = st
            UIAccessibility.post(notification: .announcement, argument: st)
        }
    }
}

/// Round call control with a caption; "on" toggles are drawn light.
private final class CallButton: UIControl {
    enum Kind { case toggle, end, accept }
    private let circle = UIView()
    private let icon = UIImageView()
    private let caption = label("", 13, color: UIColor.white.withAlphaComponent(0.85))
    private let kind: Kind
    private let action: () -> Void

    init(_ symbol: String, _ title: String, kind: Kind = .toggle, action: @escaping () -> Void) {
        self.kind = kind
        self.action = action
        super.init(frame: .zero)
        circle.isUserInteractionEnabled = false
        circle.layer.cornerRadius = 32
        circle.translatesAutoresizingMaskIntoConstraints = false
        icon.contentMode = .scaleAspectFit
        icon.translatesAutoresizingMaskIntoConstraints = false
        caption.textAlignment = .center
        caption.adjustsFontSizeToFitWidth = true
        caption.minimumScaleFactor = 0.8
        caption.translatesAutoresizingMaskIntoConstraints = false
        addSubview(circle)
        circle.addSubview(icon)
        addSubview(caption)
        NSLayoutConstraint.activate([
            circle.topAnchor.constraint(equalTo: topAnchor),
            circle.centerXAnchor.constraint(equalTo: centerXAnchor),
            circle.widthAnchor.constraint(equalToConstant: 64), circle.heightAnchor.constraint(equalToConstant: 64),
            icon.centerXAnchor.constraint(equalTo: circle.centerXAnchor), icon.centerYAnchor.constraint(equalTo: circle.centerYAnchor),
            icon.widthAnchor.constraint(equalToConstant: 28), icon.heightAnchor.constraint(equalToConstant: 28),
            caption.topAnchor.constraint(equalTo: circle.bottomAnchor, constant: 8),
            caption.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 2),
            caption.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -2),
            caption.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
        isAccessibilityElement = true
        accessibilityTraits = .button
        set(symbol, title, title, on: false)
        addAction(UIAction { [weak self] _ in self?.action() }, for: .touchUpInside)
    }

    required init?(coder: NSCoder) { fatalError() }

    func set(_ symbol: String, _ title: String, _ a11y: String, on: Bool) {
        icon.image = UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: 22, weight: .semibold))
        caption.text = title
        accessibilityLabel = a11y
        switch kind {
        case .end:
            circle.backgroundColor = Theme.rgb(0xE5484D)
            icon.tintColor = .white
        case .accept:
            circle.backgroundColor = Theme.rgb(0x30A46C)
            icon.tintColor = .white
        case .toggle:
            circle.backgroundColor = on ? Theme.rgb(0xF2F6FF) : UIColor.white.withAlphaComponent(0.16)
            icon.tintColor = on ? Theme.rgb(0x111B33) : .white
            accessibilityTraits = on ? [.button, .selected] : .button
        }
    }

    override var isHighlighted: Bool {
        didSet { circle.alpha = isHighlighted ? 0.7 : 1 }
    }
}

/// Soft rings around the avatar while the call rings; off with reduced motion.
private final class PulseView: UIView {
    private var rings: [CAShapeLayer] = []

    func start() {
        guard rings.isEmpty, bounds.width > 0 else { return }
        for i in 0..<2 {
            let l = CAShapeLayer()
            let r: CGFloat = 60
            l.path = UIBezierPath(ovalIn: CGRect(x: bounds.midX - r, y: bounds.midY - r, width: r * 2, height: r * 2)).cgPath
            l.fillColor = UIColor.clear.cgColor
            l.strokeColor = Theme.rgb(0x98B9FF).cgColor
            l.lineWidth = 2
            l.frame = bounds
            layer.addSublayer(l)
            let scale = CABasicAnimation(keyPath: "transform.scale")
            scale.fromValue = 1
            scale.toValue = 1.8
            let fade = CABasicAnimation(keyPath: "opacity")
            fade.fromValue = 0.55
            fade.toValue = 0
            let g = CAAnimationGroup()
            g.animations = [scale, fade]
            g.duration = 2
            g.repeatCount = .infinity
            g.beginTime = CACurrentMediaTime() + Double(i)
            g.timingFunction = CAMediaTimingFunction(controlPoints: 0.22, 0.8, 0.25, 1)
            l.opacity = 0
            l.add(g, forKey: "pulse")
            rings.append(l)
        }
    }

    func stop() {
        rings.forEach { $0.removeFromSuperlayer() }
        rings = []
    }
}
