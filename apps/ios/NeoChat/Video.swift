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
    static let limit: Int64 = 25 * 1024 * 1024

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

    /// An MP4 (H.264 and AAC) goes as it is when small enough; anything else is re-encoded, smaller step by step until it fits.
    /// Calls `done` on a background queue; nil when the video cannot be sent.
    static func prepare(_ f: OutgoingFile, done: @escaping (PreparedVideo?) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            func ready(_ url: URL, _ name: String, _ mime: String) {
                done(PreparedVideo(url: url, name: name, mime: mime, info: info(url)))
            }
            if f.mime == "video/mp4" && f.size <= limit {
                ready(f.url, f.name, f.mime)
                return
            }
            export(f.url) { out in
                if let out {
                    try? FileManager.default.removeItem(at: f.url)
                    ready(out, (f.name as NSString).deletingPathExtension + ".mp4", "video/mp4")
                } else if f.size <= limit {
                    // The system cannot re-encode it, but it is small enough to be sent as it is.
                    ready(f.url, f.name, f.mime)
                } else {
                    try? FileManager.default.removeItem(at: f.url)
                    done(nil)
                }
            }
        }
    }

    private static func export(_ src: URL, done: @escaping (URL?) -> Void) {
        let asset = AVURLAsset(url: src)
        let allowed = AVAssetExportSession.exportPresets(compatibleWith: asset)
        let presets = [AVAssetExportPreset1280x720, AVAssetExportPreset960x540, AVAssetExportPreset640x480].filter { allowed.contains($0) }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("outgoing", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        func attempt(_ i: Int) {
            guard i < presets.count, let session = AVAssetExportSession(asset: asset, presetName: presets[i]) else { return done(nil) }
            let out = dir.appendingPathComponent(UUID().uuidString + ".mp4")
            session.outputURL = out
            session.outputFileType = .mp4
            session.shouldOptimizeForNetworkUse = true
            session.exportAsynchronously {
                guard session.status == .completed else {
                    try? FileManager.default.removeItem(at: out)
                    return done(nil)
                }
                if size(out) <= limit { return done(out) }
                try? FileManager.default.removeItem(at: out)
                attempt(i + 1)
            }
        }
        attempt(0)
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
