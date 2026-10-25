import AVFoundation
import UIKit

extension Notification.Name {
    /// Posted when a message starts, pauses, moves or ends; `userInfo["id"]` is the message ("" for the speed).
    static let playbackChanged = Notification.Name("NeoChatPlaybackChanged")
}

/// Records a voice message: AAC in an .m4a file, with the loudness of every moment kept for the wave.
final class VoiceRecorder {
    struct Recording {
        let url: URL
        let durationMs: Int
        let wave: Data
    }

    static let maxMs = 10 * 60 * 1000

    private var recorder: AVAudioRecorder?
    private var timer: Timer?
    private var url: URL?
    private(set) var levels: [UInt8] = []
    /// Called about every 60 ms with the time so far.
    var onTick: ((Int) -> Void)?

    var elapsedMs: Int { Int((recorder?.currentTime ?? 0) * 1000) }

    /// False when the microphone cannot be taken (a call is running, access refused).
    func start() -> Bool {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
            try session.setActive(true)
        } catch { return false }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("outgoing", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent(UUID().uuidString + "-voice.m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44100, AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 64000, AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
        ]
        guard let rec = try? AVAudioRecorder(url: file, settings: settings) else { return false }
        rec.isMeteringEnabled = true
        guard rec.record() else { return false }
        recorder = rec
        url = file
        timer = Timer.scheduledTimer(withTimeInterval: 0.06, repeats: true) { [weak self] _ in
            guard let self, let rec = self.recorder else { return }
            rec.updateMeters()
            let v = max(0, min(1, (rec.averagePower(forChannel: 0) + 55) / 55))
            self.levels.append(UInt8(v * 255))
            self.onTick?(self.elapsedMs)
        }
        return true
    }

    /// Ends the recording; nil when nothing was kept.
    func stop() -> Recording? {
        timer?.invalidate()
        timer = nil
        guard let rec = recorder, let url else { return nil }
        let ms = Int(rec.currentTime * 1000)
        rec.stop()
        recorder = nil
        self.url = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        return Recording(url: url, durationMs: ms, wave: Self.bars(levels))
    }

    func cancel() {
        timer?.invalidate()
        timer = nil
        recorder?.stop()
        recorder = nil
        if let url { try? FileManager.default.removeItem(at: url) }
        url = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// 64 bars of 8-bit loudness, scaled so that the loudest one is full.
    private static func bars(_ levels: [UInt8]) -> Data {
        let count = 64
        guard !levels.isEmpty else { return Data(repeating: 20, count: count) }
        var out = [Int](repeating: 0, count: count)
        for i in 0..<count {
            let a = i * levels.count / count
            let b = max(a + 1, (i + 1) * levels.count / count)
            var sum = 0
            for k in a..<min(b, levels.count) { sum += Int(levels[k]) }
            out[i] = sum / max(1, min(b, levels.count) - a)
        }
        let peak = max(1, out.max() ?? 1)
        return Data(out.map { UInt8(max(14, $0 * 255 / peak)) })
    }
}

/// The bars of a voice message; the part already played is drawn in the strong colour. A tap seeks.
final class WaveView: UIView {
    var bars: [UInt8] = [] { didSet { setNeedsDisplay() } }
    var progress: CGFloat = 0 { didSet { if progress != oldValue { setNeedsDisplay() } } }
    var played: UIColor = Theme.accent { didSet { setNeedsDisplay() } }
    var rest: UIColor = Theme.muted { didSet { setNeedsDisplay() } }
    var onSeek: ((CGFloat) -> Void)? {
        didSet { isUserInteractionEnabled = onSeek != nil }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        contentMode = .redraw
        isAccessibilityElement = false
        isUserInteractionEnabled = false
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapped(_:))))
    }

    required init?(coder: NSCoder) { fatalError() }

    @objc private func tapped(_ g: UITapGestureRecognizer) {
        guard bounds.width > 0 else { return }
        onSeek?(max(0, min(1, g.location(in: self).x / bounds.width)))
    }

    override func draw(_ rect: CGRect) {
        guard bounds.width > 0, bounds.height > 0 else { return }
        let n = max(1, Int((bounds.width + 2) / 5))
        let step = bounds.width / CGFloat(n)
        let barW: CGFloat = 3
        let on = played.resolvedColor(with: traitCollection), off = rest.resolvedColor(with: traitCollection).withAlphaComponent(0.6)
        for i in 0..<n {
            var v: CGFloat = 0.2
            if !bars.isEmpty {
                let a = i * bars.count / n
                let b = max(a + 1, (i + 1) * bars.count / n)
                var m = 0
                for k in a..<min(b, bars.count) { m = max(m, Int(bars[k])) }
                v = CGFloat(m) / 255
            }
            let h = max(3, v * bounds.height)
            let r = CGRect(x: CGFloat(i) * step + (step - barW) / 2, y: (bounds.height - h) / 2, width: barW, height: h)
            ((CGFloat(i) + 0.5) / CGFloat(n) <= progress ? on : off).setFill()
            UIBezierPath(roundedRect: r, cornerRadius: barW / 2).fill()
        }
    }
}

/// One message at a time plays: a voice message through the speaker, a circle on its own round picture.
final class Playback {
    static let shared = Playback()

    enum State { case idle, loading, playing, paused }

    private(set) var currentId = ""
    private(set) var state: State = .idle
    private(set) var speed: Float = 1
    private(set) var position: TimeInterval = 0
    private(set) var length: TimeInterval = 0
    private(set) var player: AVPlayer?
    private weak var circle: CircleView?
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var generation = 0

    private init() {}

    func state(of id: String) -> State { id == currentId ? state : .idle }

    func progress(of id: String) -> CGFloat { id == currentId && length > 0 ? CGFloat(min(1, position / length)) : 0 }

    private func changed(_ id: String) {
        NotificationCenter.default.post(name: .playbackChanged, object: nil, userInfo: ["id": id])
    }

    /// A circle that shows the message being played takes the picture over after its cell was reused.
    func adopt(_ view: CircleView, id: String) {
        if id == currentId, state == .playing || state == .paused {
            circle = view
            view.show(player)
        } else {
            view.show(nil)
        }
    }

    func toggle(_ m: JSON, circle view: CircleView?, failed: @escaping (String) -> Void) {
        let id = m.str("id")
        if id == currentId, let player {
            switch state {
            case .playing:
                player.pause()
                state = .paused
            case .paused:
                if length > 0, position >= length - 0.1 { player.seek(to: .zero) }
                player.playImmediately(atRate: speed)
                state = .playing
            default: return
            }
            if let view { circle = view }
            changed(id)
            return
        }
        stop()
        currentId = id
        state = .loading
        circle = view
        position = 0
        length = TimeInterval(m.obj("file")?.int64("duration") ?? 0) / 1000
        generation += 1
        let gen = generation
        changed(id)
        Core.shared.call("download_file", ["message_id": id]) { [weak self] r in
            guard let self, gen == self.generation else { return }
            switch r {
            case .failure(let e):
                self.stop()
                failed(e.message)
            case .success(let v):
                guard let path = (v as? JSON)?.str("path"), !path.isEmpty else {
                    self.stop()
                    return failed("Не удалось открыть файл")
                }
                self.begin(URL(fileURLWithPath: path))
            }
        }
    }

    private func begin(_ url: URL) {
        // The silent switch must not mute a message; a running call keeps its own audio session.
        if CallManager.shared.call.isEmpty {
            try? AVAudioSession.sharedInstance().setCategory(.playback)
            try? AVAudioSession.sharedInstance().setActive(true)
        }
        let item = AVPlayerItem(url: url)
        item.audioTimePitchAlgorithm = .timeDomain
        let p = AVPlayer(playerItem: item)
        player = p
        timeObserver = p.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.05, preferredTimescale: 600), queue: .main) { [weak self] t in
            guard let self, self.state == .playing else { return }
            self.position = max(0, t.seconds)
            if let d = p.currentItem?.duration.seconds, d.isFinite, d > 0 { self.length = d }
            self.changed(self.currentId)
        }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            self?.stop()
        }
        state = .playing
        circle?.show(p)
        p.playImmediately(atRate: speed)
        changed(currentId)
    }

    func stop() {
        generation += 1
        let id = currentId
        if let o = timeObserver { player?.removeTimeObserver(o) }
        timeObserver = nil
        if let e = endObserver { NotificationCenter.default.removeObserver(e) }
        endObserver = nil
        player?.pause()
        circle?.show(nil)
        player = nil
        circle = nil
        currentId = ""
        state = .idle
        position = 0
        if !id.isEmpty { changed(id) }
    }

    func seek(_ id: String, to fraction: CGFloat) {
        guard id == currentId, state == .playing || state == .paused, let player, length > 0 else { return }
        let to = TimeInterval(max(0, min(1, fraction))) * length
        position = to
        player.seek(to: CMTime(seconds: to, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        changed(id)
    }

    /// 1× -> 1,5× -> 2× -> 1×.
    func cycleSpeed() {
        speed = speed == 1 ? 1.5 : speed == 1.5 ? 2 : 1
        if state == .playing { player?.rate = speed }
        changed(currentId)
    }
}

/// A video message in its round: the first frame, a play mark and the length; while it plays, the video itself and a ring of progress.
final class CircleView: UIView {
    static let side: CGFloat = 220

    var boundId = ""
    var onTap: (() -> Void)?
    var ringColor: UIColor = Theme.accent {
        didSet { ring.strokeColor = ringColor.resolvedColor(with: traitCollection).cgColor }
    }

    private let disc = UIView()
    private let poster = UIImageView()
    private let playerLayer = AVPlayerLayer()
    private let ring = CAShapeLayer()
    private let markBack = UIView()
    private let mark = UIImageView(image: UIImage(systemName: "play.fill"))
    private let pill = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isAccessibilityElement = false
        disc.clipsToBounds = true
        disc.backgroundColor = UIColor(red: 0.06, green: 0.09, blue: 0.13, alpha: 1)
        addSubview(disc)
        poster.contentMode = .scaleAspectFill
        poster.clipsToBounds = true
        disc.addSubview(poster)
        playerLayer.videoGravity = .resizeAspectFill
        playerLayer.isHidden = true
        disc.layer.addSublayer(playerLayer)
        ring.fillColor = nil
        ring.strokeColor = ringColor.resolvedColor(with: traitCollection).cgColor
        ring.lineWidth = 3
        ring.lineCap = .round
        ring.strokeEnd = 0
        layer.addSublayer(ring)
        markBack.backgroundColor = UIColor(white: 0, alpha: 0.6)
        markBack.layer.cornerRadius = 26
        markBack.isUserInteractionEnabled = false
        mark.tintColor = .white
        mark.contentMode = .center
        mark.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 22, weight: .bold)
        markBack.addSubview(mark)
        addSubview(markBack)
        pill.font = .monospacedDigitSystemFont(ofSize: 12, weight: .semibold)
        pill.textColor = .white
        pill.textAlignment = .center
        pill.backgroundColor = UIColor(white: 0, alpha: 0.6)
        pill.layer.cornerRadius = 11
        pill.clipsToBounds = true
        addSubview(pill)
        addGestureRecognizer(TapGesture { [weak self] in self?.onTap?() })
        translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            widthAnchor.constraint(equalToConstant: Self.side), heightAnchor.constraint(equalToConstant: Self.side),
        ])
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        disc.frame = bounds.insetBy(dx: 4, dy: 4)
        disc.layer.cornerRadius = disc.bounds.width / 2
        poster.frame = disc.bounds
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.frame = disc.bounds
        ring.frame = bounds
        ring.path = UIBezierPath(arcCenter: CGPoint(x: bounds.midX, y: bounds.midY), radius: bounds.width / 2 - 2,
                                 startAngle: -.pi / 2, endAngle: 1.5 * .pi, clockwise: true).cgPath
        CATransaction.commit()
        markBack.frame = CGRect(x: bounds.midX - 26, y: bounds.midY - 26, width: 52, height: 52)
        mark.frame = markBack.bounds.offsetBy(dx: 2, dy: 0)
        pill.sizeToFit()
        let w = max(44, pill.bounds.width + 4)
        pill.frame = CGRect(x: bounds.midX - w / 2, y: bounds.height - 40, width: w, height: 22)
    }

    /// Shows or hides the video of the player on the round.
    func show(_ player: AVPlayer?) {
        playerLayer.player = player
        playerLayer.isHidden = player == nil
    }

    func configure(id: String, poster image: UIImage?) {
        boundId = id
        poster.image = image
        Playback.shared.adopt(self, id: id)
    }

    /// The play mark, the time and the ring follow the state of the playback.
    func update(state: Playback.State, progress: CGFloat, positionMs: Int64, lengthMs: Int64) {
        let active = state == .playing || state == .paused
        markBack.isHidden = state == .playing || state == .loading
        mark.image = UIImage(systemName: "play.fill")
        pill.text = " " + (active ? Videos.clock(positionMs) + " / " : "") + Videos.clock(lengthMs) + " "
        setNeedsLayout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        ring.strokeEnd = active ? max(0.001, progress) : 0
        CATransaction.commit()
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        ring.strokeColor = ringColor.resolvedColor(with: traitCollection).cgColor
    }
}

/// What replaces the text field while a voice message is recorded: delete, the time, the live wave and send.
final class RecordingBar: UIView {
    let trash = UIButton(type: .system)
    let send = UIButton(type: .system)
    private let dot = UIView()
    private let time = UILabel()
    private let wave = WaveView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        trash.setImage(UIImage(systemName: "trash"), for: .normal)
        trash.tintColor = Theme.warning
        trash.accessibilityLabel = "Удалить запись"
        send.setImage(UIImage(systemName: "arrow.right", withConfiguration: UIImage.SymbolConfiguration(weight: .semibold)), for: .normal)
        send.tintColor = .white
        send.backgroundColor = Theme.action
        send.layer.cornerRadius = 20
        send.clipsToBounds = true
        send.accessibilityLabel = "Отправить запись"
        dot.backgroundColor = UIColor(red: 0.88, green: 0.25, blue: 0.25, alpha: 1)
        dot.layer.cornerRadius = 5
        dot.isAccessibilityElement = false
        time.font = .monospacedDigitSystemFont(ofSize: 17, weight: .semibold)
        time.textColor = Theme.text
        time.text = "0:00"
        time.accessibilityLabel = "Идёт запись"
        wave.played = Theme.accent
        wave.progress = 1
        let row = UIStackView(arrangedSubviews: [trash, dot, time, wave, send])
        row.alignment = .center
        row.spacing = 10
        row.setCustomSpacing(4, after: trash)
        row.translatesAutoresizingMaskIntoConstraints = false
        addSubview(row)
        NSLayoutConstraint.activate([
            row.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 6), row.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -4),
            row.topAnchor.constraint(equalTo: topAnchor), row.bottomAnchor.constraint(equalTo: bottomAnchor),
            trash.widthAnchor.constraint(equalToConstant: 44), trash.heightAnchor.constraint(equalToConstant: 44),
            dot.widthAnchor.constraint(equalToConstant: 10), dot.heightAnchor.constraint(equalToConstant: 10),
            wave.heightAnchor.constraint(equalToConstant: 28),
            send.widthAnchor.constraint(equalToConstant: 40), send.heightAnchor.constraint(equalToConstant: 40),
            heightAnchor.constraint(equalToConstant: 46),
        ])
        time.setContentHuggingPriority(.required, for: .horizontal)
        wave.setContentHuggingPriority(.defaultLow, for: .horizontal)
    }

    required init?(coder: NSCoder) { fatalError() }

    func update(ms: Int, levels: [UInt8]) {
        time.text = Videos.clock(Int64(ms))
        dot.alpha = (ms / 500) % 2 == 0 ? 1 : 0.35
        wave.bars = Array(levels.suffix(60))
    }

    func reset() {
        time.text = "0:00"
        dot.alpha = 1
        wave.bars = []
    }
}
