import AVFoundation
import UIKit

/// What a video message carries besides the file: its first frame, the picture size and the length.
struct VideoInfo {
    var thumb: String?
    var width: Int
    var height: Int
    var duration: Int
}

/// A video ready to be sent: portable (MP4) and small enough.
struct PreparedVideo {
    let url: URL
    let name: String
    let mime: String
    let info: VideoInfo?
}

enum Videos {
    /// The core refuses larger files.
    static let limit: Int64 = 50 * 1024 * 1024

    /// 0:07, 12:30, 1:02:03.
    static func clock(_ ms: Int64) -> String {
        let total = max(0, Int(ms / 1000))
        let h = total / 3600, m = total % 3600 / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }

    /// The first frame, a little in so a fade from black does not become the poster.
    static func poster(_ url: URL, maxSide: CGFloat) -> UIImage? {
        let asset = AVURLAsset(url: url)
        let seconds = CMTimeGetSeconds(asset.duration)
        let gen = AVAssetImageGenerator(asset: asset)
        gen.appliesPreferredTrackTransform = true
        gen.maximumSize = CGSize(width: maxSide, height: maxSide)
        let at = CMTime(seconds: seconds.isFinite ? min(0.3, seconds / 2) : 0, preferredTimescale: 600)
        guard let cg = try? gen.copyCGImage(at: at, actualTime: nil) else { return nil }
        return UIImage(cgImage: cg)
    }

    /// Length, oriented size and the small poster that travels inside the message; nil when the system cannot read the file.
    static func info(_ url: URL) -> VideoInfo? {
        let asset = AVURLAsset(url: url)
        let seconds = CMTimeGetSeconds(asset.duration)
        guard seconds.isFinite, seconds > 0, let track = asset.tracks(withMediaType: .video).first else { return nil }
        let size = track.naturalSize.applying(track.preferredTransform)
        let thumb = poster(url, maxSide: 320).flatMap { encode($0) }
        return VideoInfo(thumb: thumb, width: Int(abs(size.width)), height: Int(abs(size.height)), duration: Int(seconds * 1000))
    }

    /// Shrinks the picture until its JPEG, as base64, fits the encrypted envelope.
    private static func encode(_ img: UIImage) -> String? {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        for side in [160.0, 120.0, 90.0] {
            let k = CGFloat(side) / max(img.size.width, img.size.height)
            let size = CGSize(width: max(1, (img.size.width * k).rounded()), height: max(1, (img.size.height * k).rounded()))
            let small = UIGraphicsImageRenderer(size: size, format: format).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
            if let jpeg = small.jpegData(compressionQuality: 0.55) {
                let b = jpeg.base64EncodedString()
                if b.count <= 12000 { return b }
            }
        }
        return nil
    }

    private static func size(_ url: URL) -> Int64 {
        Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
    }

    // The same rules as on the other clients (docs/30-voice-circles-stt.md): a small clip goes as it is, the rest
    // becomes H.264 + AAC in MP4 down a ladder of sizes until it fits the budget.
    private static let budget: Int64 = 45 * 1024 * 1024
    private static let keep: Int64 = 10 * 1024 * 1024
    private static let audioBps = 128_000

    private struct Rung {
        let longSide: CGFloat
        let shortSide: CGFloat
        let videoBps: Int
    }

    private static let ladder = [Rung(longSide: 1280, shortSide: 720, videoBps: 2_500_000),
                                 Rung(longSide: 854, shortSide: 480, videoBps: 1_200_000),
                                 Rung(longSide: 640, shortSide: 360, videoBps: 700_000)]

    private static func outgoingDir() -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("outgoing", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// An MP4 (H.264 and AAC) goes as it is when small; anything else is re-encoded, smaller step by step until it fits.
    /// `progress` (0...99) and `done` are called on a background queue; nil when the video cannot be sent.
    static func prepare(_ f: OutgoingFile, progress: ((Int) -> Void)? = nil, done: @escaping (PreparedVideo?) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            func ready(_ url: URL, _ name: String, _ mime: String) {
                done(PreparedVideo(url: url, name: name, mime: mime, info: info(url)))
            }
            let asOriginal = f.mime == "video/mp4" && f.size <= limit
            func original() {
                if asOriginal {
                    ready(f.url, f.name, f.mime)
                } else {
                    try? FileManager.default.removeItem(at: f.url)
                    done(nil)
                }
            }
            let asset = AVURLAsset(url: f.url)
            let seconds = CMTimeGetSeconds(asset.duration)
            guard seconds.isFinite, seconds > 0, let track = asset.tracks(withMediaType: .video).first else { return original() }
            let natural = track.naturalSize.applying(track.preferredTransform)
            let oriented = CGSize(width: abs(natural.width), height: abs(natural.height))
            guard oriented.width > 0, oriented.height > 0,
                  let first = ladder.firstIndex(where: { Double($0.videoBps + audioBps) / 8 * seconds <= Double(budget) }) else { return original() }
            let estimate = Double(ladder[first].videoBps + audioBps) / 8 * seconds
            if asOriginal && ((f.size <= keep && max(oriented.width, oriented.height) <= 1920) || Double(f.size) <= estimate) {
                return ready(f.url, f.name, f.mime)
            }
            for rung in first..<ladder.count {
                guard let out = transcode(asset, track, oriented: oriented, rung: ladder[rung], seconds: seconds, progress: progress) else { break }
                if size(out) <= limit {
                    try? FileManager.default.removeItem(at: f.url)
                    return ready(out, (f.name as NSString).deletingPathExtension + ".mp4", "video/mp4")
                }
                try? FileManager.default.removeItem(at: out)
            }
            // The system cannot make it smaller, but a small MP4 can still be sent as it is.
            original()
        }
    }

    /// One pass: scaled to the rung, at most 30 frames a second, AAC sound; blocks until the file is written.
    private static func transcode(_ asset: AVURLAsset, _ track: AVAssetTrack, oriented: CGSize, rung: Rung, seconds: Double, progress: ((Int) -> Void)?) -> URL? {
        let out = outgoingDir().appendingPathComponent(UUID().uuidString + ".mp4")
        func fail() -> URL? {
            try? FileManager.default.removeItem(at: out)
            return nil
        }
        let landscape = oriented.width >= oriented.height
        let k = min(1, (landscape ? rung.longSide : rung.shortSide) / oriented.width, (landscape ? rung.shortSide : rung.longSide) / oriented.height)
        let w = max(2, Int(oriented.width * k) & ~1), h = max(2, Int(oriented.height * k) & ~1)
        let fps = min(30, max(1, Int((track.nominalFrameRate > 0 ? track.nominalFrameRate : 30).rounded())))
        let bps = max(300_000, min(rung.videoBps, track.estimatedDataRate > 0 ? Int(track.estimatedDataRate) : rung.videoBps))

        let composition = AVMutableVideoComposition()
        composition.renderSize = CGSize(width: w, height: h)
        composition.frameDuration = CMTime(value: 1, timescale: CMTimeScale(fps))
        let instruction = AVMutableVideoCompositionInstruction()
        instruction.timeRange = CMTimeRange(start: .zero, duration: asset.duration)
        let layer = AVMutableVideoCompositionLayerInstruction(assetTrack: track)
        layer.setTransform(track.preferredTransform.concatenating(CGAffineTransform(scaleX: CGFloat(w) / oriented.width, y: CGFloat(h) / oriented.height)), at: .zero)
        instruction.layerInstructions = [layer]
        composition.instructions = [instruction]

        guard let reader = try? AVAssetReader(asset: asset), let writer = try? AVAssetWriter(outputURL: out, fileType: .mp4) else { return nil }
        let videoOut = AVAssetReaderVideoCompositionOutput(videoTracks: [track], videoSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
        videoOut.videoComposition = composition
        let videoIn = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: w, AVVideoHeightKey: h,
            AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: bps, AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel, AVVideoMaxKeyFrameIntervalKey: 2 * fps],
        ])
        videoIn.expectsMediaDataInRealTime = false
        guard reader.canAdd(videoOut), writer.canAdd(videoIn) else { return nil }
        reader.add(videoOut)
        writer.add(videoIn)
        var pairs: [(AVAssetReaderOutput, AVAssetWriterInput)] = [(videoOut, videoIn)]
        if let audio = asset.tracks(withMediaType: .audio).first {
            let audioOut = AVAssetReaderAudioMixOutput(audioTracks: [audio], audioSettings: [AVFormatIDKey: kAudioFormatLinearPCM, AVNumberOfChannelsKey: 2, AVSampleRateKey: 44100])
            let audioIn = AVAssetWriterInput(mediaType: .audio, outputSettings: [AVFormatIDKey: kAudioFormatMPEG4AAC, AVNumberOfChannelsKey: 2, AVSampleRateKey: 44100, AVEncoderBitRateKey: audioBps])
            audioIn.expectsMediaDataInRealTime = false
            if reader.canAdd(audioOut), writer.canAdd(audioIn) {
                reader.add(audioOut)
                writer.add(audioIn)
                pairs.append((audioOut, audioIn))
            }
        }
        guard writer.startWriting(), reader.startReading() else { return fail() }
        writer.startSession(atSourceTime: .zero)
        let group = DispatchGroup()
        for (n, pair) in pairs.enumerated() {
            let (source, sink) = pair
            group.enter()
            sink.requestMediaDataWhenReady(on: DispatchQueue(label: "app.neochat.compress.\(n)")) {
                while sink.isReadyForMoreMediaData {
                    guard let buffer = source.copyNextSampleBuffer() else {
                        sink.markAsFinished()
                        return group.leave()
                    }
                    if n == 0 { progress?(min(99, Int(CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(buffer)) / seconds * 100))) }
                    if !sink.append(buffer) {
                        sink.markAsFinished()
                        return group.leave()
                    }
                }
            }
        }
        group.wait()
        if reader.status == .failed || writer.status == .failed {
            reader.cancelReading()
            writer.cancelWriting()
            return fail()
        }
        let finished = DispatchSemaphore(value: 0)
        writer.finishWriting { finished.signal() }
        finished.wait()
        return writer.status == .completed ? out : fail()
    }
}

/// The play mark in the middle of a video poster and its length in the corner.
final class VideoMarkView: UIView {
    private let disc = UIView()
    private let glyph = UIImageView(image: UIImage(systemName: "play.fill"))
    private let pill = UILabel()

    var length = "" {
        didSet {
            pill.text = " " + length + " "
            pill.isHidden = length.isEmpty
        }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        disc.backgroundColor = UIColor(white: 0, alpha: 0.6)
        disc.layer.cornerRadius = 26
        disc.translatesAutoresizingMaskIntoConstraints = false
        glyph.tintColor = .white
        glyph.contentMode = .center
        glyph.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 22, weight: .bold)
        glyph.translatesAutoresizingMaskIntoConstraints = false
        pill.font = .monospacedDigitSystemFont(ofSize: 12, weight: .semibold)
        pill.textColor = .white
        pill.textAlignment = .center
        pill.backgroundColor = UIColor(white: 0, alpha: 0.6)
        pill.layer.cornerRadius = 11
        pill.clipsToBounds = true
        pill.isHidden = true
        pill.translatesAutoresizingMaskIntoConstraints = false
        addSubview(disc)
        disc.addSubview(glyph)
        addSubview(pill)
        NSLayoutConstraint.activate([
            disc.centerXAnchor.constraint(equalTo: centerXAnchor), disc.centerYAnchor.constraint(equalTo: centerYAnchor),
            disc.widthAnchor.constraint(equalToConstant: 52), disc.heightAnchor.constraint(equalToConstant: 52),
            glyph.centerXAnchor.constraint(equalTo: disc.centerXAnchor, constant: 2), glyph.centerYAnchor.constraint(equalTo: disc.centerYAnchor),
            pill.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 10), pill.topAnchor.constraint(equalTo: topAnchor, constant: 10),
            pill.heightAnchor.constraint(equalToConstant: 22), pill.widthAnchor.constraint(greaterThanOrEqualToConstant: 40),
        ])
    }

    required init?(coder: NSCoder) { fatalError() }
}
