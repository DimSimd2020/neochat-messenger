import AVFoundation
import UIKit

/// Full-screen camera that reads a QR code with the given prefix. Frames stay on the device.
final class QrScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let prefix: String
    private let onResult: (String) -> Void
    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "app.neochat.qr")
    private let hint = label("Наведите камеру на QR-код на экране нового устройства", 16, .medium, color: .white)
    private var preview: AVCaptureVideoPreviewLayer?
    private let frame = CAShapeLayer()
    private let dim = CAShapeLayer()
    private var done = false

    init(prefix: String, onResult: @escaping (String) -> Void) {
        self.prefix = prefix
        self.onResult = onResult
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
        modalTransitionStyle = .crossDissolve
    }

    required init?(coder: NSCoder) { fatalError() }

    /// Asks for camera access when needed, then presents the scanner.
    static func present(from host: UIViewController, prefix: String, onResult: @escaping (String) -> Void) {
        let show = { host.present(QrScannerViewController(prefix: prefix, onResult: onResult), animated: !Theme.reducedMotion) }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: show()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { ok in
                DispatchQueue.main.async { ok ? show() : host.toast("Нет доступа к камере. Разрешите его в настройках или вставьте код вручную") }
            }
        default: host.toast("Нет доступа к камере. Разрешите его в настройках или вставьте код вручную")
        }
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        guard let camera = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: camera), session.canAddInput(input) else {
            hint.text = "Камера недоступна"
            layoutChrome()
            return
        }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
        }
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(layer)
        preview = layer
        layoutChrome()
    }

    private func layoutChrome() {
        dim.fillRule = .evenOdd
        dim.fillColor = UIColor.black.withAlphaComponent(0.55).cgColor
        view.layer.addSublayer(dim)
        frame.strokeColor = UIColor.white.cgColor
        frame.fillColor = UIColor.clear.cgColor
        frame.lineWidth = 3
        view.layer.addSublayer(frame)
        hint.textAlignment = .center
        hint.numberOfLines = 0
        let close = UIButton(type: .close)
        close.overrideUserInterfaceStyle = .dark
        close.accessibilityLabel = "Закрыть сканер"
        close.addAction(UIAction { [weak self] _ in self?.dismiss(animated: !Theme.reducedMotion) }, for: .touchUpInside)
        for v in [hint, close] as [UIView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(v)
        }
        NSLayoutConstraint.activate([
            close.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            close.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            close.widthAnchor.constraint(equalToConstant: 44),
            close.heightAnchor.constraint(equalToConstant: 44),
            hint.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 32),
            hint.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -32),
            hint.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -48),
        ])
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
        let side = min(view.bounds.width, view.bounds.height) * 0.68
        let rect = CGRect(x: (view.bounds.width - side) / 2, y: (view.bounds.height - side) / 2 - 24, width: side, height: side)
        let hole = UIBezierPath(roundedRect: rect, cornerRadius: 28)
        frame.path = hole.cgPath
        let all = UIBezierPath(rect: view.bounds)
        all.append(hole)
        dim.path = all.cgPath
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        let s = session
        queue.async { if !s.isRunning { s.startRunning() } }
        UIAccessibility.post(notification: .screenChanged, argument: hint)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        let s = session
        queue.async { if s.isRunning { s.stopRunning() } }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !done, let text = (objects.first as? AVMetadataMachineReadableCodeObject)?.stringValue else { return }
        guard text.hasPrefix(prefix) else {
            hint.text = "Это не QR-код привязки NeoChat"
            return
        }
        done = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        dismiss(animated: !Theme.reducedMotion) { [onResult] in onResult(text) }
    }
}
