import AVFoundation
import UIKit

/// Full-screen camera for chat photos: the preview fills the screen, a pinch zooms,
/// a tap focuses, and the only button is the shutter. The shot is shown for a last look
/// before it is sent; nothing is kept on the device afterwards.
final class PhotoCameraViewController: UIViewController, AVCapturePhotoCaptureDelegate {
    private let onPhoto: (URL) -> Void
    private let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private let queue = DispatchQueue(label: "app.neochat.camera")
    private var device: AVCaptureDevice?
    private var preview: AVCaptureVideoPreviewLayer?
    private let shutter = ShutterButton()
    private let zoomLabel = label("", 15, .semibold, color: .white)
    private let focusRing = UIView()
    private let still = UIImageView()
    private let retake = PillButton("Переснять", primary: false)
    private let send = PillButton("Отправить")
    private let close = UIButton(type: .close)
    private let actions = UIStackView()
    private var review: URL?
    private var pinchStart: CGFloat = 1
    /// The zoom factor shown as 1×: wide cameras of multi-lens phones start after the ultra-wide lens.
    private var base: CGFloat = 1
    private var zoomHide: DispatchWorkItem?

    init(onPhoto: @escaping (URL) -> Void) {
        self.onPhoto = onPhoto
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
        modalTransitionStyle = .crossDissolve
    }

    required init?(coder: NSCoder) { fatalError() }

    /// Asks for camera access when needed, then presents the camera.
    static func present(from host: UIViewController, onPhoto: @escaping (URL) -> Void) {
        let show = { host.present(PhotoCameraViewController(onPhoto: onPhoto), animated: !Theme.reducedMotion) }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: show()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { ok in
                DispatchQueue.main.async { ok ? show() : host.toast("Нет доступа к камере. Разрешите его в настройках") }
            }
        default: host.toast("Нет доступа к камере. Разрешите его в настройках")
        }
    }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .portrait }
    override var prefersStatusBarHidden: Bool { true }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        let types: [AVCaptureDevice.DeviceType] = [.builtInTripleCamera, .builtInDualWideCamera, .builtInDualCamera, .builtInWideAngleCamera]
        let found = AVCaptureDevice.DiscoverySession(deviceTypes: types, mediaType: .video, position: .back).devices
        if let camera = found.first, let input = try? AVCaptureDeviceInput(device: camera), session.canAddInput(input) {
            session.beginConfiguration()
            session.sessionPreset = .photo
            session.addInput(input)
            if session.canAddOutput(output) { session.addOutput(output) }
            session.commitConfiguration()
            device = camera
            if camera.deviceType == .builtInTripleCamera || camera.deviceType == .builtInDualWideCamera,
               let first = camera.virtualDeviceSwitchOverVideoZoomFactors.first {
                base = CGFloat(truncating: first)
                try? camera.lockForConfiguration()
                camera.videoZoomFactor = base
                camera.unlockForConfiguration()
            }
            let layer = AVCaptureVideoPreviewLayer(session: session)
            layer.videoGravity = .resizeAspectFill
            view.layer.addSublayer(layer)
            preview = layer
        }
        still.contentMode = .scaleAspectFill
        still.clipsToBounds = true
        still.isHidden = true
        still.backgroundColor = .black
        view.addSubview(still)
        view.addGestureRecognizer(UIPinchGestureRecognizer(target: self, action: #selector(pinched(_:))))
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        view.addGestureRecognizer(tap)

        focusRing.layer.borderColor = UIColor.white.cgColor
        focusRing.layer.borderWidth = 2
        focusRing.layer.cornerRadius = 36
        focusRing.frame = CGRect(x: 0, y: 0, width: 72, height: 72)
        focusRing.alpha = 0
        focusRing.isUserInteractionEnabled = false
        view.addSubview(focusRing)

        zoomLabel.alpha = 0
        zoomLabel.textAlignment = .center
        zoomLabel.isAccessibilityElement = false
        close.overrideUserInterfaceStyle = .dark
        close.accessibilityLabel = "Закрыть камеру"
        close.addAction(UIAction { [weak self] _ in self?.discardAndClose() }, for: .touchUpInside)
        shutter.accessibilityLabel = "Снять"
        shutter.addAction(UIAction { [weak self] _ in self?.capture() }, for: .touchUpInside)
        retake.overrideUserInterfaceStyle = .dark
        retake.addAction(UIAction { [weak self] _ in self?.retakePhoto() }, for: .touchUpInside)
        send.addAction(UIAction { [weak self] _ in self?.sendPhoto() }, for: .touchUpInside)
        actions.addArrangedSubview(retake)
        actions.addArrangedSubview(send)
        actions.spacing = 12
        actions.distribution = .fillEqually
        actions.isHidden = true
        for v in [shutter, zoomLabel, close, actions] as [UIView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(v)
        }
        NSLayoutConstraint.activate([
            close.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            close.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            close.widthAnchor.constraint(equalToConstant: 44),
            close.heightAnchor.constraint(equalToConstant: 44),
            shutter.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            shutter.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -28),
            shutter.widthAnchor.constraint(equalToConstant: 76),
            shutter.heightAnchor.constraint(equalToConstant: 76),
            zoomLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            zoomLabel.bottomAnchor.constraint(equalTo: shutter.topAnchor, constant: -20),
            actions.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            actions.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            actions.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -28),
        ])
        if device == nil {
            shutter.isEnabled = false
            zoomLabel.text = "Камера недоступна"
            zoomLabel.alpha = 1
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
        still.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        let s = session
        queue.async { if !s.isRunning { s.startRunning() } }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        let s = session
        queue.async { if s.isRunning { s.stopRunning() } }
    }

    // MARK: gestures

    @objc private func pinched(_ g: UIPinchGestureRecognizer) {
        guard let device, review == nil else { return }
        switch g.state {
        case .began: pinchStart = device.videoZoomFactor
        case .changed:
            let lo = device.minAvailableVideoZoomFactor
            let hi = min(device.maxAvailableVideoZoomFactor, base * 10)
            let z = min(hi, max(lo, pinchStart * g.scale))
            do {
                try device.lockForConfiguration()
                device.videoZoomFactor = z
                device.unlockForConfiguration()
            } catch { return }
            showZoom(z / base)
        default: break
        }
    }

    private func showZoom(_ z: CGFloat) {
        zoomLabel.text = String(format: "%.1f×", z).replacingOccurrences(of: ".", with: ",")
        zoomLabel.alpha = 1
        zoomHide?.cancel()
        let work = DispatchWorkItem { [weak self] in Theme.animate(0.3) { self?.zoomLabel.alpha = 0 } }
        zoomHide = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2, execute: work)
    }

    @objc private func tapped(_ g: UITapGestureRecognizer) {
        guard let device, let preview, review == nil else { return }
        let p = g.location(in: view)
        let point = preview.captureDevicePointConverted(fromLayerPoint: p)
        do {
            try device.lockForConfiguration()
            if device.isFocusPointOfInterestSupported, device.isFocusModeSupported(.autoFocus) {
                device.focusPointOfInterest = point
                device.focusMode = .autoFocus
            }
            if device.isExposurePointOfInterestSupported, device.isExposureModeSupported(.autoExpose) {
                device.exposurePointOfInterest = point
                device.exposureMode = .autoExpose
            }
            device.unlockForConfiguration()
        } catch { return }
        focusRing.center = p
        focusRing.alpha = 1
        focusRing.transform = Theme.reducedMotion ? .identity : CGAffineTransform(scaleX: 1.4, y: 1.4)
        Theme.animate(0.25) { self.focusRing.transform = .identity }
        UIView.animate(withDuration: Theme.reducedMotion ? 0 : 0.3, delay: 0.8, options: [], animations: { self.focusRing.alpha = 0 })
    }

    // MARK: shooting

    private func capture() {
        guard device != nil, review == nil else { return }
        shutter.isEnabled = false
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        let settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
        output.capturePhoto(with: settings, delegate: self)
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        guard error == nil, let data = photo.fileDataRepresentation() else {
            DispatchQueue.main.async {
                self.shutter.isEnabled = true
                self.toast("Не удалось сделать снимок")
            }
            return
        }
        DispatchQueue.global(qos: .userInitiated).async {
            let url = Self.upright(data)
            DispatchQueue.main.async {
                self.shutter.isEnabled = true
                guard let url, let image = UIImage(contentsOfFile: url.path) else { return self.toast("Не удалось сохранить снимок") }
                self.showReview(url, image)
            }
        }
    }

    /// Re-encodes the shot upright, at most 2560 px on the long side and without metadata (no location).
    private static func upright(_ data: Data) -> URL? {
        guard let image = UIImage(data: data) else { return nil }
        let longest = max(image.size.width, image.size.height) * image.scale
        let k = min(1, 2560 / longest)
        let size = CGSize(width: image.size.width * image.scale * k, height: image.size.height * image.scale * k)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true
        let jpeg = UIGraphicsImageRenderer(size: size, format: format).jpegData(withCompressionQuality: 0.88) { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("outgoing", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd_HHmmss"
        let url = dir.appendingPathComponent(UUID().uuidString + "-photo_" + f.string(from: Date()) + ".jpg")
        return (try? jpeg.write(to: url)) != nil ? url : nil
    }

    private func showReview(_ url: URL, _ image: UIImage) {
        review = url
        still.image = image
        still.isHidden = false
        shutter.isHidden = true
        zoomLabel.alpha = 0
        actions.isHidden = false
        UIAccessibility.post(notification: .screenChanged, argument: send)
    }

    private func retakePhoto() {
        if let review { try? FileManager.default.removeItem(at: review) }
        review = nil
        still.isHidden = true
        still.image = nil
        shutter.isHidden = false
        actions.isHidden = true
    }

    private func sendPhoto() {
        guard let url = review else { return }
        review = nil
        dismiss(animated: !Theme.reducedMotion) { [onPhoto] in onPhoto(url) }
    }

    private func discardAndClose() {
        if let review { try? FileManager.default.removeItem(at: review) }
        review = nil
        dismiss(animated: !Theme.reducedMotion)
    }
}

/// The round white shutter: a ring around a disc that shrinks a little while pressed.
private final class ShutterButton: UIButton {
    private let disc = UIView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        layer.borderColor = UIColor.white.cgColor
        layer.borderWidth = 4
        disc.backgroundColor = .white
        disc.isUserInteractionEnabled = false
        addSubview(disc)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        layer.cornerRadius = bounds.width / 2
        disc.frame = bounds.insetBy(dx: 9, dy: 9)
        disc.layer.cornerRadius = disc.bounds.width / 2
    }

    override var isHighlighted: Bool {
        didSet {
            let s: CGFloat = isHighlighted ? 0.86 : 1
            if Theme.reducedMotion { disc.transform = CGAffineTransform(scaleX: s, y: s) } else {
                UIView.animate(withDuration: 0.12) { self.disc.transform = CGAffineTransform(scaleX: s, y: s) }
            }
        }
    }

    override var isEnabled: Bool {
        didSet { alpha = isEnabled ? 1 : 0.5 }
    }
}
