import AVFoundation
import UIKit

/// Records a video message ("circle"): the viewfinder is a round, one tap starts and the next stops and sends,
/// a ring fills up to 60 seconds. The file is H.264 + AAC in MP4; receivers show it in a round of their own.
final class CircleRecorderViewController: UIViewController, AVCaptureFileOutputRecordingDelegate {
    static let maxSeconds = 60

    private let onCircle: (URL, Int) -> Void
    private let session = AVCaptureSession()
    private let output = AVCaptureMovieFileOutput()
    private let queue = DispatchQueue(label: "app.neochat.circle")
    private var preview: AVCaptureVideoPreviewLayer?
    private var videoInput: AVCaptureDeviceInput?
    private var front = true
    private let round = UIView()
    private let ring = CAShapeLayer()
    private let track = CAShapeLayer()
    private let time = UILabel()
    private let hint = label("Нажмите, чтобы начать запись", 15, color: UIColor(white: 1, alpha: 0.75))
    private let record = RecordButton()
    private let flip = UIButton(type: .system)
    private let close = UIButton(type: .close)
    private var recording = false
    private var finishing = false
    private var discard = false
    private var file: URL?
    private var ticker: Timer?
    private var startedAt = Date()

    init(onCircle: @escaping (URL, Int) -> Void) {
        self.onCircle = onCircle
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
        modalTransitionStyle = .crossDissolve
    }

    required init?(coder: NSCoder) { fatalError() }

    /// Asks for the camera and the microphone when needed, then presents the recorder.
    static func present(from host: UIViewController, onCircle: @escaping (URL, Int) -> Void) {
        guard CallManager.shared.call.isEmpty else { return host.toast("Во время звонка запись недоступна") }
        let show = { host.present(CircleRecorderViewController(onCircle: onCircle), animated: !Theme.reducedMotion) }
        let denied = { host.toast("Нет доступа к камере или микрофону. Разрешите его в настройках") }
        func ask(_ type: AVMediaType, _ next: @escaping () -> Void) {
            switch AVCaptureDevice.authorizationStatus(for: type) {
            case .authorized: next()
            case .notDetermined: AVCaptureDevice.requestAccess(for: type) { ok in DispatchQueue.main.async { ok ? next() : denied() } }
            default: denied()
            }
        }
        ask(.video) { ask(.audio, show) }
    }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .portrait }
    override var prefersStatusBarHidden: Bool { true }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        round.clipsToBounds = true
        round.backgroundColor = UIColor(white: 0.1, alpha: 1)
        view.addSubview(round)
        configureSession()
        if videoInput != nil {
            let layer = AVCaptureVideoPreviewLayer(session: session)
            layer.videoGravity = .resizeAspectFill
            round.layer.addSublayer(layer)
            preview = layer
        }
        for l in [track, ring] {
            l.fillColor = nil
            l.lineWidth = 4
            l.lineCap = .round
            view.layer.addSublayer(l)
        }
        track.strokeColor = UIColor(white: 1, alpha: 0.25).cgColor
        ring.strokeColor = UIColor(red: 0.88, green: 0.25, blue: 0.25, alpha: 1).cgColor
        ring.strokeEnd = 0
        time.font = .monospacedDigitSystemFont(ofSize: 20, weight: .semibold)
        time.textColor = .white
        time.textAlignment = .center
        time.text = "0:00"
        time.isHidden = true
        hint.textAlignment = .center
        close.overrideUserInterfaceStyle = .dark
        close.accessibilityLabel = "Закрыть камеру"
        close.addAction(UIAction { [weak self] _ in self?.cancel() }, for: .touchUpInside)
        flip.setImage(UIImage(systemName: "arrow.triangle.2.circlepath.camera"), for: .normal)
        flip.tintColor = .white
        flip.accessibilityLabel = "Сменить камеру"
        flip.addAction(UIAction { [weak self] _ in self?.switchCamera() }, for: .touchUpInside)
        record.accessibilityLabel = "Начать запись"
        record.addAction(UIAction { [weak self] _ in self?.toggle() }, for: .touchUpInside)
        for v in [time, hint, record, flip, close] as [UIView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(v)
        }
        NSLayoutConstraint.activate([
            close.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            close.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            close.widthAnchor.constraint(equalToConstant: 44), close.heightAnchor.constraint(equalToConstant: 44),
            record.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            record.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -28),
            record.widthAnchor.constraint(equalToConstant: 76), record.heightAnchor.constraint(equalToConstant: 76),
            flip.centerYAnchor.constraint(equalTo: record.centerYAnchor),
            flip.leadingAnchor.constraint(equalTo: record.trailingAnchor, constant: 36),
            flip.widthAnchor.constraint(equalToConstant: 48), flip.heightAnchor.constraint(equalToConstant: 48),
            time.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            time.bottomAnchor.constraint(equalTo: record.topAnchor, constant: -24),
            hint.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            hint.bottomAnchor.constraint(equalTo: record.topAnchor, constant: -24),
        ])
        if videoInput == nil {
            record.isEnabled = false
            hint.text = "Камера недоступна"
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let side = min(view.bounds.width - 56, 320)
        let top = view.safeAreaInsets.top + 70
        let free = view.bounds.height - top - 190
        let y = top + max(0, (free - side) / 2)
        round.frame = CGRect(x: (view.bounds.width - side) / 2, y: y, width: side, height: side)
        round.layer.cornerRadius = side / 2
        preview?.frame = round.bounds
        let path = UIBezierPath(arcCenter: CGPoint(x: round.frame.midX, y: round.frame.midY), radius: side / 2 + 8,
                                startAngle: -.pi / 2, endAngle: 1.5 * .pi, clockwise: true).cgPath
        track.path = path
        ring.path = path
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        let s = session
        queue.async { if !s.isRunning { s.startRunning() } }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if recording {
            discard = true
            output.stopRecording()
        }
        let s = session
        queue.async { if s.isRunning { s.stopRunning() } }
    }

    // MARK: camera

    private func camera(front: Bool) -> AVCaptureDevice? {
        AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: front ? .front : .back)
            ?? AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .unspecified)
    }

    private func configureSession() {
        session.beginConfiguration()
        if session.canSetSessionPreset(.vga640x480) { session.sessionPreset = .vga640x480 }
        if let device = camera(front: front), let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) {
            session.addInput(input)
            videoInput = input
        }
        if let mic = AVCaptureDevice.default(for: .audio), let input = try? AVCaptureDeviceInput(device: mic), session.canAddInput(input) {
            session.addInput(input)
        }
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.maxRecordedDuration = CMTime(seconds: Double(Self.maxSeconds), preferredTimescale: 600)
            output.movieFragmentInterval = .invalid
        }
        session.commitConfiguration()
        applyConnection()
    }

    /// Upright, and mirrored for the front camera so that the recording looks like the viewfinder.
    private func applyConnection() {
        if let c = output.connection(with: .video) {
            if c.isVideoOrientationSupported { c.videoOrientation = .portrait }
            if c.isVideoMirroringSupported {
                c.automaticallyAdjustsVideoMirroring = false
                c.isVideoMirrored = front
            }
            if output.availableVideoCodecTypes.contains(.h264) { output.setOutputSettings([AVVideoCodecKey: AVVideoCodecType.h264], for: c) }
        }
        if let p = preview?.connection, p.isVideoOrientationSupported { p.videoOrientation = .portrait }
        if let p = preview?.connection, p.isVideoMirroringSupported {
            p.automaticallyAdjustsVideoMirroring = false
            p.isVideoMirrored = front
        }
    }

    private func switchCamera() {
        guard !recording, let old = videoInput else { return }
        let next = !front
        guard let device = camera(front: next), device.uniqueID != old.device.uniqueID,
              let input = try? AVCaptureDeviceInput(device: device) else { return }
        session.beginConfiguration()
        session.removeInput(old)
        if session.canAddInput(input) {
            session.addInput(input)
            videoInput = input
            front = next
        } else {
            session.addInput(old)
        }
        session.commitConfiguration()
        applyConnection()
        Haptics.select()
    }

    // MARK: recording

    private func toggle() {
        if recording { finish() } else { begin() }
    }

    private func begin() {
        guard !recording, videoInput != nil else { return }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("outgoing", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(UUID().uuidString + "-circle.mp4")
        file = url
        discard = false
        recording = true
        startedAt = Date()
        output.startRecording(to: url, recordingDelegate: self)
        Haptics.confirm()
        record.recording = true
        record.accessibilityLabel = "Остановить и отправить"
        UIAccessibility.post(notification: .announcement, argument: "Запись идёт")
        hint.isHidden = true
        time.isHidden = false
        flip.alpha = 0.3
        ticker = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in self?.tick() }
    }

    private func tick() {
        let s = Date().timeIntervalSince(startedAt)
        time.text = Videos.clock(Int64(s * 1000))
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        ring.strokeEnd = min(1, CGFloat(s / Double(Self.maxSeconds)))
        CATransaction.commit()
    }

    private func finish() {
        guard recording, !finishing else { return }
        finishing = true
        ticker?.invalidate()
        ticker = nil
        output.stopRecording()
    }

    private func cancel() {
        if recording {
            discard = true
            finish()
        }
        dismiss(animated: !Theme.reducedMotion)
    }

    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo url: URL, from connections: [AVCaptureConnection], error: Error?) {
        // The recording counts as made when the system says it finished well, even if it ended because of the limit.
        let kept = (error as NSError?)?.userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool ?? (error == nil)
        let ms = Int(CMTimeGetSeconds(output.recordedDuration) * 1000)
        DispatchQueue.main.async {
            self.recording = false
            self.finishing = false
            self.ticker?.invalidate()
            self.ticker = nil
            if self.discard || !kept {
                try? FileManager.default.removeItem(at: url)
                if !self.discard { self.toast("Не удалось сохранить запись") }
                return
            }
            if ms < 600 {
                try? FileManager.default.removeItem(at: url)
                self.toast("Видеосообщение слишком короткое")
                self.dismiss(animated: !Theme.reducedMotion)
                return
            }
            self.file = nil
            self.dismiss(animated: !Theme.reducedMotion) { self.onCircle(url, ms) }
        }
    }

    deinit {
        if let file { try? FileManager.default.removeItem(at: file) }
    }
}

/// The round record button: a ring around a red disc that becomes a square while recording.
private final class RecordButton: UIButton {
    private let disc = UIView()

    var recording = false {
        didSet {
            guard recording != oldValue else { return }
            let apply = { self.layoutDisc() }
            if Theme.reducedMotion { apply() } else { UIView.animate(withDuration: 0.2, animations: apply) }
        }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        layer.borderColor = UIColor.white.cgColor
        layer.borderWidth = 4
        disc.backgroundColor = UIColor(red: 0.88, green: 0.25, blue: 0.25, alpha: 1)
        disc.isUserInteractionEnabled = false
        addSubview(disc)
    }

    required init?(coder: NSCoder) { fatalError() }

    private func layoutDisc() {
        let inset: CGFloat = recording ? 22 : 9
        disc.frame = bounds.insetBy(dx: inset, dy: inset)
        disc.layer.cornerRadius = recording ? 8 : disc.bounds.width / 2
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        layer.cornerRadius = bounds.width / 2
        layoutDisc()
    }

    override var isEnabled: Bool {
        didSet { alpha = isEnabled ? 1 : 0.5 }
    }
}
