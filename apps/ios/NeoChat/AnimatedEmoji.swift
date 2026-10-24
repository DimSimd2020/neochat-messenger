import UIKit

/// Animated versions of the common emoji (Animated Noto Emoji, Google, CC BY 4.0): one sprite sheet PNG per emoji
/// in the bundle folder animated-emoji, built by scripts/build-animated-emoji.py. A message made only of such
/// emoji plays them instead of showing the font's still picture; any other emoji keeps the font.
enum AnimatedEmoji {
    final class Sheet {
        let frames: [UIImage]
        let ms: Int
        init(frames: [UIImage], ms: Int) {
            self.frames = frames
            self.ms = ms
        }
        var duration: TimeInterval { Double(frames.count * ms) / 1000 }
    }

    private struct Entry {
        let frames: Int
        let ms: Int
    }

    private static let folder = "animated-emoji"
    private static let cache = NSCache<NSString, Sheet>()
    private static let index: (size: Int, cols: Int, emoji: [String: Entry]) = {
        guard let url = Bundle.main.url(forResource: "index", withExtension: "json", subdirectory: folder),
              let data = try? Data(contentsOf: url),
              let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return (96, 8, [:]) }
        var emoji: [String: Entry] = [:]
        for (key, value) in (o["emoji"] as? [String: Any]) ?? [:] {
            guard let e = value as? [String: Any] else { continue }
            emoji[key] = Entry(frames: e["frames"] as? Int ?? 1, ms: max(16, e["ms"] as? Int ?? 66))
        }
        return (o["size"] as? Int ?? 96, o["cols"] as? Int ?? 8, emoji)
    }()

    /// Names of the sheets for the emoji of `text` (up to three), or nil unless every one of them has an animation.
    /// Code points in hex joined by "-", without the variation selector.
    static func keys(for text: String) -> [String]? {
        var keys: [String] = []
        for ch in text where !ch.isWhitespace {
            let key = ch.unicodeScalars.filter { $0.value != 0xFE0F }.map { String($0.value, radix: 16) }.joined(separator: "-")
            guard keys.count < 3, index.emoji[key] != nil else { return nil }
            keys.append(key)
        }
        return keys.isEmpty ? nil : keys
    }

    static func sheet(_ key: String) -> Sheet? {
        if let s = cache.object(forKey: key as NSString) { return s }
        guard let e = index.emoji[key],
              let url = Bundle.main.url(forResource: key, withExtension: "png", subdirectory: folder),
              let all = UIImage(contentsOfFile: url.path)?.cgImage else { return nil }
        let side = index.size
        var frames: [UIImage] = []
        for n in 0..<e.frames {
            let rect = CGRect(x: n % index.cols * side, y: n / index.cols * side, width: side, height: side)
            guard let part = all.cropping(to: rect) else { return nil }
            frames.append(UIImage(cgImage: part))
        }
        let s = Sheet(frames: frames, ms: e.ms)
        cache.setObject(s, forKey: key as NSString)
        return s
    }

    /// Side of one emoji on screen for a message of `count` emoji.
    static func side(_ count: Int) -> CGFloat { count <= 1 ? 64 : count == 2 ? 52 : 42 }
}

/// Plays the frames of one emoji twice, then rests on the first. Still when motion is reduced.
final class AnimatedEmojiView: UIImageView {
    func show(_ s: AnimatedEmoji.Sheet) {
        contentMode = .scaleAspectFit
        isAccessibilityElement = false
        image = s.frames.first
        animationImages = s.frames
        animationDuration = s.duration
        animationRepeatCount = 2
        if Theme.reducedMotion { stopAnimating() } else { startAnimating() }
    }
}
