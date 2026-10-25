import AVFoundation
import AVKit
import ImageIO
import PhotosUI
import QuickLook
import UIKit
import UniformTypeIdentifiers

private let ru = Locale(identifier: "ru_RU")

private func hhmm(_ ms: Int64) -> String {
    let f = DateFormatter()
    f.locale = ru
    f.dateFormat = "HH:mm"
    return f.string(from: Date(timeIntervalSince1970: TimeInterval(ms) / 1000))
}

private func dateTitle(_ ms: Int64) -> String {
    let d = Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
    let f = DateFormatter()
    f.locale = ru
    f.dateFormat = Calendar.current.isDate(d, equalTo: Date(), toGranularity: .year) ? "d MMMM" : "d MMMM yyyy"
    let day = f.string(from: d)
    if Calendar.current.isDateInToday(d) { return "Сегодня, " + day }
    if Calendar.current.isDateInYesterday(d) { return "Вчера, " + day }
    return day
}

func sizeText(_ b: Int64) -> String {
    if b < 1024 { return "\(b) Б" }
    if b < 1024 * 1024 { return String(format: "%.0f КБ", Double(b) / 1024) }
    return String(format: "%.1f МБ", Double(b) / 1048576).replacingOccurrences(of: ".", with: ",")
}

private func statusText(_ s: String) -> String {
    ["queued": "В очереди", "sent": "На сервере", "delivered": "Доставлено", "read": "Прочитано", "failed": "Не отправлено"][s] ?? ""
}

/// Delivery marks use the same 24-point paths and states as Android.
private final class DeliveryMarkView: UIView {
    var status = "" { didSet { setNeedsDisplay() } }
    var color = UIColor.white { didSet { setNeedsDisplay() } }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        ctx.scaleBy(x: bounds.width / 24, y: bounds.height / 24)
        ctx.setStrokeColor(color.cgColor)
        ctx.setLineWidth(1.8)
        ctx.setLineCap(.round)
        ctx.setLineJoin(.round)
        if status == "sent" {
            ctx.move(to: CGPoint(x: 5, y: 12))
            ctx.addLine(to: CGPoint(x: 9, y: 16))
            ctx.addLine(to: CGPoint(x: 19, y: 6))
        } else if status == "delivered" || status == "read" {
            ctx.move(to: CGPoint(x: 3, y: 12))
            ctx.addLine(to: CGPoint(x: 7, y: 16))
            ctx.addLine(to: CGPoint(x: 18, y: 5))
            ctx.move(to: CGPoint(x: 11, y: 16))
            ctx.addLine(to: CGPoint(x: 22, y: 5))
        } else {
            ctx.strokeEllipse(in: CGRect(x: 4, y: 4, width: 16, height: 16))
            ctx.move(to: CGPoint(x: 12, y: status == "failed" ? 8 : 7))
            ctx.addLine(to: CGPoint(x: 12, y: status == "failed" ? 13 : 12))
            if status == "failed" {
                ctx.move(to: CGPoint(x: 12, y: 16))
                ctx.addLine(to: CGPoint(x: 12, y: 16.1))
            } else { ctx.addLine(to: CGPoint(x: 16, y: 14)) }
        }
        ctx.strokePath()
    }
}

private func votesText(_ n: Int) -> String {
    let m10 = n % 10, m100 = n % 100
    let w = m10 == 1 && m100 != 11 ? "голос" : (2...4).contains(m10) && !(12...14).contains(m100) ? "голоса" : "голосов"
    return "\(n) \(w)"
}

func isImage(_ m: JSON) -> Bool { m.str("kind") == "file" && (m.obj("file")?.bool("image") ?? false) }
func isVideo(_ m: JSON) -> Bool { m.str("kind") == "file" && (m.obj("file")?.bool("video") ?? false) }
func isVoice(_ m: JSON) -> Bool { m.str("kind") == "file" && (m.obj("file")?.bool("voice") ?? false) }
func isCircle(_ m: JSON) -> Bool { m.str("kind") == "file" && (m.obj("file")?.bool("circle") ?? false) }

/// The recognized text of a voice message or circle; nil while it has none, "" when no speech was found.
func transcriptOf(_ m: JSON) -> String? { m.obj("file")?["transcript"] as? String }

/// What a file message is called in quotes, previews and the pin bar.
func filePreview(_ m: JSON) -> String {
    isImage(m) ? "Фото" : isVideo(m) ? "Видео" : isVoice(m) ? "Голосовое сообщение" : isCircle(m) ? "Видеосообщение" : "Файл"
}

/// Links found by the core's markdown parser.
func links(_ m: JSON) -> [String] { m.arr("spans").compactMap { $0["link"] as? String } }

private extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}

extension UILabel {
    /// The @name under `point`, when the finger is actually on that glyph.
    func mentionName(at point: CGPoint) -> String? {
        guard let attr = attributedText, bounds.width > 1, attr.length > 0 else { return nil }
        let storage = NSTextStorage(attributedString: attr)
        let manager = NSLayoutManager()
        let container = NSTextContainer(size: bounds.size)
        container.lineFragmentPadding = 0
        container.maximumNumberOfLines = numberOfLines
        container.lineBreakMode = lineBreakMode
        manager.addTextContainer(container)
        storage.addLayoutManager(manager)
        let index = manager.characterIndex(for: point, in: container, fractionOfDistanceBetweenInsertionPoints: nil)
        guard index < attr.length else { return nil }
        let glyph = manager.glyphIndexForCharacter(at: index)
        let rect = manager.boundingRect(forGlyphRange: NSRange(location: glyph, length: 1), in: container)
        guard rect.insetBy(dx: -2, dy: -2).contains(point) else { return nil }
        return attr.attribute(.neochatMention, at: index, effectiveRange: nil) as? String
    }
}

/// Telegram-like formatting from the core's markdown spans.
extension NSAttributedString.Key {
    static let neochatMention = NSAttributedString.Key("neochat.mention")
}

func richText(_ m: JSON, size: CGFloat, color: UIColor, outgoing: Bool = false) -> NSAttributedString {
    let base = Theme.font(size)
    let spans = m.arr("spans")
    if spans.isEmpty { return NSAttributedString(string: m.str("text"), attributes: [.font: base, .foregroundColor: color]) }
    let out = NSMutableAttributedString()
    let plain: [NSAttributedString.Key: Any] = [.font: base, .foregroundColor: color]
    for s in spans {
        let pre = s.bool("pre")
        // A code block always stands on its own lines.
        if pre, out.length > 0, !out.string.hasSuffix("\n") { out.append(NSAttributedString(string: "\n", attributes: plain)) }
        var font = base
        if pre || s.bool("code") {
            font = UIFontMetrics(forTextStyle: .body).scaledFont(for: .monospacedSystemFont(ofSize: size * 0.9, weight: s.bool("bold") ? .bold : .regular))
        } else {
            var traits: UIFontDescriptor.SymbolicTraits = []
            if s.bool("bold") { traits.insert(.traitBold) }
            if s.bool("italic") { traits.insert(.traitItalic) }
            if !traits.isEmpty, let d = base.fontDescriptor.withSymbolicTraits(traits) { font = UIFont(descriptor: d, size: 0) }
        }
        var a: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color]
        if s.bool("strike") { a[.strikethroughStyle] = NSUnderlineStyle.single.rawValue }
        if pre || s.bool("code") { a[.backgroundColor] = UIColor.gray.withAlphaComponent(0.2) }
        if s["link"] is String { a[.underlineStyle] = NSUnderlineStyle.single.rawValue }
        if let name = s["mention"] as? String {
            let broadcast = name.lowercased() == "everyone" || name.lowercased() == "here"
            a[.foregroundColor] = outgoing ? UIColor(red: 1, green: 0.89, blue: 0.63, alpha: 1) : Theme.accent
            a[.font] = Theme.font(size, .semibold)
            if !broadcast {
                a[.underlineStyle] = NSUnderlineStyle.single.rawValue
                a[.neochatMention] = name
            }
        }
        out.append(NSAttributedString(string: s.str("text"), attributes: a))
        if pre { out.append(NSAttributedString(string: "\n", attributes: plain)) }
    }
    while out.string.hasSuffix("\n") { out.deleteCharacters(in: NSRange(location: out.length - 1, length: 1)) }
    return out
}

/// Decoded photos: thumbnails from messages and downscaled originals.
enum Photos {
    static let cache = NSCache<NSString, UIImage>()
    private static var pending: [String: Date] = [:]

    static func thumb(_ m: JSON) -> UIImage? {
        guard let b = m.obj("file")?.str("thumb"), !b.isEmpty, let d = Data(base64Encoded: b) else { return nil }
        return UIImage(data: d)
    }

    /// Downloads (again after 30 s on failure) and decodes the original off the main thread.
    static func load(_ m: JSON, _ done: @escaping (UIImage) -> Void) {
        let id = m.str("id")
        if (m.obj("file")?.int64("size") ?? 0) > 25_000_000 { return }
        if let t = pending[id], Date().timeIntervalSince(t) < 30 { return }
        pending[id] = Date()
        Core.shared.call("download_file", ["message_id": id]) { r in
            guard case .success(let v) = r, let path = (v as? JSON)?.str("path") else { return }
            DispatchQueue.global(qos: .userInitiated).async {
                let img = decode(URL(fileURLWithPath: path), maxSide: 1280)
                DispatchQueue.main.async {
                    guard let img else { return }
                    pending[id] = nil
                    cache.setObject(img, forKey: id as NSString)
                    done(img)
                }
            }
        }
    }

    /// Decodes at most `maxSide` pixels on the long side with the EXIF orientation applied.
    static func decode(_ url: URL, maxSide: CGFloat) -> UIImage? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
                                     kCGImageSourceThumbnailMaxPixelSize: maxSide]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
        return UIImage(cgImage: cg)
    }

    /// Small JPEG preview sent inside the message (it must fit the encrypted envelope) and the oriented size.
    static func makeThumb(_ url: URL) -> (String, Int, Int)? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] else { return nil }
        var w = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue ?? 0
        var h = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue ?? 0
        if let o = (props[kCGImagePropertyOrientation] as? NSNumber)?.intValue, o >= 5 { swap(&w, &h) }
        for side in [160, 120, 90] {
            guard let img = decode(url, maxSide: CGFloat(side)), let jpeg = img.jpegData(compressionQuality: 0.55) else { return nil }
            let b = jpeg.base64EncodedString()
            if b.count <= 12000 { return (b, w, h) }
        }
        return nil
    }
}

private enum Item {
    case date(String)
    case msg(JSON, first: Bool, last: Bool)
    /// A service line ("ner1s был добавлен владельцем DimSimd"): text and message id.
    case note(String, id: String)
}

/// One conversation: history, composer and header with details.
/// The message field: pasting pictures (a screenshot, a copied photo) goes to the attach window, text pastes as usual.
final class ComposerTextView: UITextView {
    var onPasteImages: (([UIImage]) -> Void)?

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        if action == #selector(paste(_:)), onPasteImages != nil, UIPasteboard.general.hasImages { return true }
        return super.canPerformAction(action, withSender: sender)
    }

    override func paste(_ sender: Any?) {
        if let onPasteImages, UIPasteboard.general.hasImages, let images = UIPasteboard.general.images, !images.isEmpty {
            onPasteImages(images)
            return
        }
        super.paste(sender)
    }
}

final class ChatViewController: UIViewController, UITableViewDataSource, UITableViewDelegate, UITextViewDelegate, UIDocumentPickerDelegate, QLPreviewControllerDataSource,
    PHPickerViewControllerDelegate, UIPopoverPresentationControllerDelegate {
    let conversationId: String
    private let accountId = Core.shared.accountId
    private var conv: JSON = [:]
    private var messages: [JSON] = []
    private var items: [Item] = []
    private var known = Set<String>()
    private var animateIds = Set<String>()
    private var loading = false
    private var hasMore = true
    private var historyGeneration = 0
    private var group: Bool { conv.str("kind") == "group" }

    private let table = UITableView(frame: .zero, style: .plain)
    private let notice = UIStackView()
    private let noticeText = label("", 14, color: Theme.warning)
    // Pinned messages: the bar shows one at a time, a tap goes to it and on to the next older one.
    private let pinBar = UIStackView()
    private let pinTap = UIControl()
    private let pinHead = label("", 12.5, .semibold, color: Theme.accent)
    private let pinText = label("", 14)
    private let pinList = UIButton(type: .system)
    private let pinOff = UIButton(type: .system)
    private var pins: [JSON] = []
    private var pinIndex = 0
    private var jumpId: String?
    private let composer = UIView()
    private let attach = UIButton(type: .system)
    private let input = ComposerTextView()
    private let placeholder = label("Написать сообщение…", 17, color: Theme.muted)
    private let send = UIButton(type: .system)
    // Voice messages and circles: two buttons where the arrow will be, and the bar that replaces the field while recording.
    private let micButton = UIButton(type: .system)
    private let circleButton = UIButton(type: .system)
    private let media = UIStackView()
    private var mediaWidth: NSLayoutConstraint!
    private let recBar = RecordingBar()
    private var recorder: VoiceRecorder?
    private var sendWidth: NSLayoutConstraint!
    private var inputHeight: NSLayoutConstraint!
    private let replyBar = UIStackView()
    private let replyTitle = label("", 13, .semibold, color: Theme.accent)
    private let replyText = label("", 14, color: Theme.muted)
    private let titleLabel = label("", 17, .semibold)
    private let subLabel = label("", 12, color: Theme.muted)
    private let titleBox = UIStackView()
    private let header = UIStackView()
    private let headerAvatar = AvatarView(size: 40)
    private let profileButton = UIControl()
    private var baseSub = ""
    private var peerOnline = false
    private var presenceTimer: Timer?
    private var replyTo: JSON?
    private var replyAnimation: UIViewPropertyAnimator?
    private let mentionRow = UIStackView()
    private var mentionGeneration = 0
    // Edit mode: the field holds the message text; the unsent draft waits in savedDraft.
    private var editTarget: JSON?
    private var savedDraft = ""
    private weak var cancelButton: UIButton?
    private var blocked = false
    private let goneLabel = label("", 15, color: Theme.muted)
    private var previewURL: URL?
    private var reloadPending = false
    private let jump = UIButton(type: .system)
    private var jumpShown = false
    // Measured row heights: estimates made the table jump while sending and left the first screen half drawn.
    private var heights: [String: CGFloat] = [:]
    private var pendingBottom = false
    private let callButton = UIButton(type: .system)
    private let videoCallButton = UIButton(type: .system)

    init(conversationId: String) {
        self.conversationId = conversationId
        super.init(nibName: nil, bundle: nil)
        hidesBottomBarWhenPushed = true
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.canvas
        view.clipsToBounds = true
        view.insertSubview(BackgroundView(frame: view.bounds), at: 0)
        navigationItem.largeTitleDisplayMode = .never

        table.backgroundColor = .clear
        table.separatorStyle = .none
        table.dataSource = self
        table.delegate = self
        table.keyboardDismissMode = .interactive
        table.register(BubbleCell.self, forCellReuseIdentifier: "b")
        table.register(DateCell.self, forCellReuseIdentifier: "d")
        table.register(NoteCell.self, forCellReuseIdentifier: "n")
        table.accessibilityLabel = "Сообщения"

        notice.axis = .horizontal
        notice.spacing = 8
        notice.alignment = .center
        notice.backgroundColor = Theme.warningBg
        notice.layer.cornerRadius = 18
        notice.isLayoutMarginsRelativeArrangement = true
        notice.layoutMargins = UIEdgeInsets(top: 10, left: 16, bottom: 10, right: 10)
        let verify = LinkButton("Сверить код")
        verify.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            self.openProfile()
        }, for: .touchUpInside)
        notice.addArrangedSubview(noticeText)
        notice.addArrangedSubview(verify)
        notice.isHidden = true

        buildPinBar()
        buildComposer()
        goneLabel.textAlignment = .center
        goneLabel.isHidden = true

        buildHeader()

        let stack = UIStackView(arrangedSubviews: [header, notice, pinBar, table, composer, goneLabel])
        stack.axis = .vertical
        stack.spacing = 6
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 4),
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 10),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -10),
            stack.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor, constant: -8),
        ])
        NotificationCenter.default.addObserver(forName: .coreEvent, object: nil, queue: .main) { [weak self] n in
            guard let self, let ev = n.userInfo as? JSON else { return }
            switch ev.str("type") {
            case "history_cleared" where ev.str("conversation_id") == self.conversationId:
                self.historyGeneration += 1
                self.messages = []; self.items = []; self.known.removeAll(); self.heights.removeAll()
                self.loading = false; self.hasMore = true
                self.table.reloadData()
                self.input.text = ""; self.textViewDidChange(self.input); self.setReply(nil)
                if ev.bool("hidden") { self.navigationController?.popViewController(animated: !Theme.reducedMotion) }
            case "messages_changed" where ev.str("conversation_id") == self.conversationId: self.scheduleReload()
            case "sync_reset": self.scheduleReload(); self.refreshConversation(first: false)
            case "conversations_changed", "contacts_changed", "identity_changed", "connection": self.refreshConversation(first: false)
            case "presence" where self.presenceIds().contains(ev.str("user_id")): self.refreshConversation(first: false)
            default: break
            }
        }
        NotificationCenter.default.addObserver(forName: .typingChanged, object: nil, queue: .main) { [weak self] n in
            guard let self, n.object as? String == self.conversationId else { return }
            self.updateSubtitle()
        }
        NotificationCenter.default.addObserver(forName: .prefsChanged, object: nil, queue: .main) { [weak self] _ in
            self?.heights.removeAll()
            self?.table.reloadData()
        }
        buildJump()
        refreshConversation(first: true)
        load(older: false)
    }

    private func buildHeader() {
        header.axis = .horizontal
        header.alignment = .center
        header.glass(30)
        header.isLayoutMarginsRelativeArrangement = true
        header.layoutMargins = UIEdgeInsets(top: 6, left: 4, bottom: 6, right: 4)
        header.accessibilityIdentifier = "chatHeader"

        let back = UIButton(type: .system)
        back.setImage(UIImage(systemName: "chevron.backward"), for: .normal)
        back.accessibilityLabel = "Вернуться к чатам"
        back.addAction(UIAction { [weak self] _ in
            self?.navigationController?.popViewController(animated: !Theme.reducedMotion)
        }, for: .touchUpInside)
        header.addArrangedSubview(back)

        titleLabel.numberOfLines = 1
        subLabel.numberOfLines = 1
        for text in [titleLabel, subLabel] {
            text.textAlignment = .left
            text.lineBreakMode = .byTruncatingTail
            text.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        }
        titleBox.axis = .vertical
        titleBox.spacing = 2
        titleBox.addArrangedSubview(titleLabel)
        titleBox.addArrangedSubview(subLabel)
        let who = UIStackView(arrangedSubviews: [headerAvatar, titleBox])
        who.alignment = .center
        who.spacing = 10
        who.isUserInteractionEnabled = false
        who.translatesAutoresizingMaskIntoConstraints = false
        profileButton.addSubview(who)
        profileButton.isAccessibilityElement = true
        profileButton.accessibilityTraits = [.button, .header]
        profileButton.accessibilityIdentifier = "chatProfile"
        profileButton.accessibilityHint = "Открывает сведения о собеседнике или чате"
        profileButton.addAction(UIAction { [weak self] _ in self?.openProfile() }, for: .touchUpInside)
        profileButton.springPress()
        NSLayoutConstraint.activate([
            who.leadingAnchor.constraint(equalTo: profileButton.leadingAnchor),
            who.trailingAnchor.constraint(equalTo: profileButton.trailingAnchor),
            who.topAnchor.constraint(equalTo: profileButton.topAnchor, constant: 2),
            who.bottomAnchor.constraint(equalTo: profileButton.bottomAnchor, constant: -2),
            profileButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 48),
        ])
        header.addArrangedSubview(profileButton)

        callButton.setImage(UIImage(systemName: "phone"), for: .normal)
        callButton.accessibilityLabel = "Аудиозвонок"
        callButton.addAction(UIAction { [weak self] _ in self?.startCall(video: false) }, for: .touchUpInside)
        videoCallButton.setImage(UIImage(systemName: "video"), for: .normal)
        videoCallButton.accessibilityLabel = "Видеозвонок"
        videoCallButton.addAction(UIAction { [weak self] _ in self?.startCall(video: true) }, for: .touchUpInside)
        for button in [callButton, videoCallButton] {
            button.isHidden = true
            header.addArrangedSubview(button)
        }

        let more = UIButton(type: .system)
        more.setImage(UIImage(systemName: "ellipsis"), for: .normal)
        more.accessibilityLabel = "Действия чата"
        more.menu = UIMenu(children: [
            UIAction(title: "Сведения", image: UIImage(systemName: "info.circle")) { [weak self] _ in self?.openProfile() },
            UIAction(title: "Очистить чат…", image: UIImage(systemName: "trash.slash"), attributes: .destructive) { [weak self] _ in
                guard let self else { return }
                confirmClearConversation(self) { [weak self] in
                    guard let self else { return }
                    Core.shared.call("clear_conversation", ["conversation_id": self.conversationId]) { [weak self] r in
                        if case .failure(let e) = r { self?.toast(e.message) }
                    }
                }
            },
        ])
        more.showsMenuAsPrimaryAction = true
        header.addArrangedSubview(more)
        for button in [back, callButton, videoCallButton, more] {
            button.tintColor = Theme.accent
            button.setPreferredSymbolConfiguration(UIImage.SymbolConfiguration(pointSize: 20, weight: .medium), forImageIn: .normal)
            button.translatesAutoresizingMaskIntoConstraints = false
            // A hidden arranged button has a required zero width from UIStackView.
            let width = button.widthAnchor.constraint(equalToConstant: 44)
            width.priority = .defaultHigh
            NSLayoutConstraint.activate([width, button.heightAnchor.constraint(equalToConstant: 44)])
            button.springPress()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if pendingBottom && table.bounds.height > 0 && !items.isEmpty {
            pendingBottom = false
            snapToBottom()
        }
    }

    private func buildJump() {
        jump.glass(22)
        jump.setImage(UIImage(systemName: "arrow.down", withConfiguration: UIImage.SymbolConfiguration(pointSize: 17, weight: .semibold)), for: .normal)
        jump.tintColor = Theme.accent
        jump.accessibilityLabel = "В самый низ"
        jump.alpha = 0
        jump.isHidden = true
        jump.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(jump)
        NSLayoutConstraint.activate([
            jump.widthAnchor.constraint(equalToConstant: 44),
            jump.heightAnchor.constraint(equalToConstant: 44),
            jump.trailingAnchor.constraint(equalTo: table.trailingAnchor, constant: -8),
            jump.bottomAnchor.constraint(equalTo: table.bottomAnchor, constant: -10),
        ])
        jump.addAction(UIAction { [weak self] _ in self?.scrollToBottom(animated: true) }, for: .touchUpInside)
        jump.springPress()
    }

    private func showJump(_ on: Bool) {
        guard on != jumpShown else { return }
        jumpShown = on
        if on && jump.isHidden { jump.isHidden = false; jump.transform = CGAffineTransform(scaleX: 0.5, y: 0.5) }
        Theme.animate(on ? 0.14 : 0.15, { self.jump.alpha = on ? 1 : 0 }, done: { [weak self] in
            guard let self, !self.jumpShown else { return }
            self.jump.isHidden = true
        })
        Motion.animate(on ? 0.4 : 0.26, on ? 0.55 : 1) { self.jump.transform = on ? .identity : CGAffineTransform(scaleX: 0.6, y: 0.6) }
    }

    private func key(_ it: Item) -> String {
        switch it {
        case .date(let t): return "d:" + t
        case .msg(let m, _, _): return "m:" + m.str("id")
        case .note(_, let id): return "n:" + id
        }
    }

    func tableView(_ tableView: UITableView, estimatedHeightForRowAt indexPath: IndexPath) -> CGFloat {
        guard indexPath.row < items.count else { return 60 }
        if let h = heights[key(items[indexPath.row])] { return h }
        if case .date = items[indexPath.row] { return 44 }
        if case .note = items[indexPath.row] { return 44 }
        return 60
    }

    func tableView(_ tableView: UITableView, willDisplay cell: UITableViewCell, forRowAt indexPath: IndexPath) {
        guard indexPath.row < items.count else { return }
        heights[key(items[indexPath.row])] = cell.frame.height
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        Notifier.visibleConversation = conversationId
        if let back = navigationController?.interactivePopGestureRecognizer {
            back.isEnabled = true
            table.panGestureRecognizer.require(toFail: back)
        }
        markRead()
        presenceTimer?.invalidate()
        presenceTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in self?.refreshPresence() }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // Detach the keyboard before the transition: the interactive keyboard
        // dismissal and the pop animation together can tear the layout guide.
        view.endEditing(true)
        if Notifier.visibleConversation == conversationId { Notifier.visibleConversation = nil }
        presenceTimer?.invalidate()
        presenceTimer = nil
        cancelVoice()
        Playback.shared.stop()
        Core.shared.call("set_draft", ["_account_id": accountId, "conversation_id": conversationId, "text": editTarget != nil ? savedDraft : (input.text ?? "")])
    }

    private func scheduleReload() {
        if reloadPending { return }
        reloadPending = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.06) {
            self.reloadPending = false
            self.load(older: false)
        }
    }

    // MARK: composer

    private func buildComposer() {
        composer.glass(26)
        let row = UIView()
        attach.setImage(UIImage(systemName: "paperclip"), for: .normal)
        attach.tintColor = Theme.text
        attach.accessibilityLabel = "Прикрепить"
        // Ask what is attached first: photos open the photo picker and arrive as photos.
        attach.showsMenuAsPrimaryAction = true
        attach.addAction(UIAction { [weak self] _ in self?.attach.imageView?.play(.wiggle) }, for: .menuActionTriggered)
        updateAttachMenu()
        input.backgroundColor = .clear
        input.font = Theme.font(17)
        input.adjustsFontForContentSizeCategory = true
        input.textColor = Theme.text
        input.delegate = self
        input.onPasteImages = { [weak self] images in self?.pasted(images) }
        input.accessibilityLabel = "Текст сообщения"
        input.isScrollEnabled = false
        input.textContainerInset = UIEdgeInsets(top: 12, left: 4, bottom: 12, right: 4)
        send.setImage(UIImage(systemName: "arrow.right", withConfiguration: UIImage.SymbolConfiguration(weight: .semibold)), for: .normal)
        send.tintColor = .white
        send.backgroundColor = Theme.action
        send.layer.cornerRadius = 20
        send.accessibilityLabel = "Отправить"
        send.addAction(UIAction { [weak self] _ in self?.sendText() }, for: .touchUpInside)
        send.clipsToBounds = true
        send.transform = Self.sendFolded
        attach.springPress()
        micButton.setImage(UIImage(systemName: "mic"), for: .normal)
        micButton.tintColor = Theme.text
        micButton.accessibilityLabel = "Записать голосовое сообщение"
        micButton.springPress()
        micButton.addAction(UIAction { [weak self] _ in self?.startVoice() }, for: .touchUpInside)
        circleButton.setImage(UIImage(systemName: "video.circle"), for: .normal)
        circleButton.tintColor = Theme.text
        circleButton.accessibilityLabel = "Записать видеосообщение"
        circleButton.springPress()
        circleButton.addAction(UIAction { [weak self] _ in self?.startCircle() }, for: .touchUpInside)
        media.axis = .horizontal
        media.distribution = .fillEqually
        media.clipsToBounds = true
        media.addArrangedSubview(circleButton)
        media.addArrangedSubview(micButton)
        recBar.isHidden = true
        recBar.trash.addAction(UIAction { [weak self] _ in self?.cancelVoice() }, for: .touchUpInside)
        recBar.send.addAction(UIAction { [weak self] _ in self?.finishVoice() }, for: .touchUpInside)
        for v in [attach, input, media, send, placeholder, recBar] {
            v.translatesAutoresizingMaskIntoConstraints = false
            row.addSubview(v)
        }
        placeholder.isUserInteractionEnabled = false
        sendWidth = send.widthAnchor.constraint(equalToConstant: 0)
        mediaWidth = media.widthAnchor.constraint(equalToConstant: 88)
        inputHeight = input.heightAnchor.constraint(equalToConstant: 46)
        NSLayoutConstraint.activate([
            attach.leadingAnchor.constraint(equalTo: row.leadingAnchor, constant: 6),
            attach.bottomAnchor.constraint(equalTo: row.bottomAnchor, constant: -1),
            attach.widthAnchor.constraint(equalToConstant: 44), attach.heightAnchor.constraint(equalToConstant: 44),
            input.leadingAnchor.constraint(equalTo: attach.trailingAnchor),
            input.topAnchor.constraint(equalTo: row.topAnchor),
            input.bottomAnchor.constraint(equalTo: row.bottomAnchor),
            inputHeight,
            media.leadingAnchor.constraint(equalTo: input.trailingAnchor),
            media.bottomAnchor.constraint(equalTo: row.bottomAnchor, constant: -1),
            media.heightAnchor.constraint(equalToConstant: 44), mediaWidth,
            send.leadingAnchor.constraint(equalTo: media.trailingAnchor, constant: 4),
            send.trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -4),
            recBar.leadingAnchor.constraint(equalTo: row.leadingAnchor), recBar.trailingAnchor.constraint(equalTo: row.trailingAnchor),
            recBar.bottomAnchor.constraint(equalTo: row.bottomAnchor),
            send.bottomAnchor.constraint(equalTo: row.bottomAnchor, constant: -3),
            send.heightAnchor.constraint(equalToConstant: 40), sendWidth,
            placeholder.leadingAnchor.constraint(equalTo: input.leadingAnchor, constant: 9),
            placeholder.centerYAnchor.constraint(equalTo: input.centerYAnchor),
        ])
        replyBar.axis = .horizontal
        replyBar.spacing = 10
        replyBar.alignment = .center
        replyBar.isLayoutMarginsRelativeArrangement = true
        replyBar.layoutMargins = UIEdgeInsets(top: 9, left: 16, bottom: 0, right: 8)
        let line = UIView()
        line.backgroundColor = Theme.accent
        line.layer.cornerRadius = 1.5
        line.widthAnchor.constraint(equalToConstant: 3).isActive = true
        line.heightAnchor.constraint(equalToConstant: 34).isActive = true
        replyTitle.numberOfLines = 1
        replyText.numberOfLines = 1
        let col = UIStackView(arrangedSubviews: [replyTitle, replyText])
        col.axis = .vertical
        col.spacing = 1
        let cancel = UIButton(type: .system)
        cancel.setImage(UIImage(systemName: "xmark.circle.fill"), for: .normal)
        cancel.tintColor = Theme.muted
        cancel.accessibilityLabel = "Отменить ответ"
        cancel.widthAnchor.constraint(equalToConstant: 36).isActive = true
        cancel.heightAnchor.constraint(equalToConstant: 36).isActive = true
        cancelButton = cancel
        cancel.addAction(UIAction { [weak self] _ in if self?.editTarget != nil { self?.cancelEdit() } else { self?.setReply(nil) } }, for: .touchUpInside)
        replyBar.addArrangedSubview(line)
        replyBar.addArrangedSubview(col)
        replyBar.addArrangedSubview(cancel)
        replyBar.isHidden = true
        mentionRow.spacing = 6
        mentionRow.axis = .vertical
        mentionRow.isHidden = true
        let box = UIStackView(arrangedSubviews: [mentionRow, replyBar, row])
        box.axis = .vertical
        box.translatesAutoresizingMaskIntoConstraints = false
        composer.addSubview(box)
        NSLayoutConstraint.activate([
            box.topAnchor.constraint(equalTo: composer.topAnchor), box.bottomAnchor.constraint(equalTo: composer.bottomAnchor),
            box.leadingAnchor.constraint(equalTo: composer.leadingAnchor), box.trailingAnchor.constraint(equalTo: composer.trailingAnchor),
        ])
    }

    func textViewDidChange(_ tv: UITextView) {
        updateMentions()
        placeholder.isHidden = !tv.text.isEmpty
        // The core sends "печатает" at most every 4 s.
        if tv.isFirstResponder && !tv.text.isEmpty && !blocked { Core.shared.call("send_typing", ["conversation_id": conversationId]) }
        // Grows to five lines, then scrolls.
        let lineH = tv.font?.lineHeight ?? 20
        let maxH = lineH * 5 + 24
        let h = min(maxH, max(46, tv.sizeThatFits(CGSize(width: tv.bounds.width, height: .greatestFiniteMagnitude)).height))
        tv.isScrollEnabled = h >= maxH
        if inputHeight.constant != h { inputHeight.constant = h }
        updateSend(animated: true)
    }

    /// Small and turned away while there is nothing to send.
    private static let sendFolded = CGAffineTransform(rotationAngle: -70 * .pi / 180).scaledBy(x: 0.4, y: 0.4)

    /// With text the send button springs in: the slot opens without bouncing so the text does not
    /// reflow twice, while the arrow grows and turns into place with a little overshoot.
    private func updateSend(animated: Bool) {
        // A caption may be emptied, so editing a file keeps the button.
        let show = !(input.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || editTarget?.str("kind") == "file"
        let target: CGFloat = show ? 40 : 0
        updateMedia(show: show, animated: animated)
        send.isEnabled = !blocked
        send.alpha = blocked ? 0.5 : 1
        send.accessibilityHint = blocked ? "Отправка приостановлена: ключ собеседника изменился" : nil
        guard sendWidth.constant != target else { return }
        sendWidth.constant = target
        guard animated else {
            send.transform = show ? .identity : Self.sendFolded
            return
        }
        Motion.animate(0.32, 1) { self.composer.layoutIfNeeded() }
        Motion.animate(show ? 0.42 : 0.28, show ? 0.6 : 1) { self.send.transform = show ? .identity : Self.sendFolded }
    }

    /// The two recording buttons take the place of the arrow while there is nothing to send.
    private func updateMedia(show: Bool, animated: Bool) {
        let on = !show && editTarget == nil
        let target: CGFloat = on ? 88 : 0
        micButton.isEnabled = !blocked
        circleButton.isEnabled = !blocked
        media.isUserInteractionEnabled = on
        guard mediaWidth.constant != target else { return }
        mediaWidth.constant = target
        let apply = {
            self.media.alpha = on ? 1 : 0
            self.composer.layoutIfNeeded()
        }
        if animated { Motion.animate(0.32, 1, apply) } else { apply() }
    }

    private func setEdit(_ m: JSON) {
        if editTarget == nil { savedDraft = input.text ?? "" }
        editTarget = m
        replyTo = nil
        let t = m.str("text").replacingOccurrences(of: "\n", with: " ")
        replyTitle.text = "Изменение сообщения"
        replyText.text = t.isEmpty ? filePreview(m) : t
        replyBar.accessibilityLabel = "Изменение сообщения: " + (replyText.text ?? "")
        cancelButton?.accessibilityLabel = "Отменить изменение"
        input.text = m.str("text")
        textViewDidChange(input)
        input.becomeFirstResponder()
        Theme.animate(0.18) {
            self.replyBar.isHidden = false
            self.view.layoutIfNeeded()
        }
    }

    private func leaveEdit() {
        guard editTarget != nil else { return }
        editTarget = nil
        input.text = savedDraft
        savedDraft = ""
        textViewDidChange(input)
        cancelButton?.accessibilityLabel = "Отменить ответ"
    }

    private func cancelEdit() {
        leaveEdit()
        setReply(nil)
    }

    private func setReply(_ m: JSON?) {
        replyAnimation?.stopAnimation(true)
        replyAnimation = nil
        leaveEdit()
        replyTo = m
        if let m {
            let t = m.str("kind") == "file" ? filePreview(m) : m.str("text").replacingOccurrences(of: "\n", with: " ")
            replyTitle.text = "В ответ " + m.str("sender_name")
            replyText.text = t
            input.becomeFirstResponder()
        }
        replyBar.accessibilityLabel = m == nil ? nil : (replyTitle.text ?? "") + ": " + (replyText.text ?? "")
        // Commit the hidden state outside the animation closure. A quick Send
        // can interrupt the opening animation of this arranged subview.
        replyBar.isHidden = m == nil
        if m == nil { replyTitle.text = nil; replyText.text = nil }
        let a = UIViewPropertyAnimator(duration:Theme.ms(0.18),timingParameters:Theme.curve)
        a.addAnimations { self.view.layoutIfNeeded() }
        replyAnimation = a
        a.startAnimation()
    }

    private func updateMentions() {
        mentionGeneration += 1
        let gen = mentionGeneration
        mentionRow.isHidden = true
        let text = input.text ?? ""
        let cursor = min(input.selectedRange.location,(text as NSString).length)
        let prefix = (text as NSString).substring(to:cursor)
        guard !blocked, let range = prefix.range(of:"(?:^|\\s)@[A-Za-z0-9_]*$",options:.regularExpression), let at = prefix[range].firstIndex(of:"@") else { return }
        let query = String(prefix[prefix.index(after:at)...])
        let start = (String(prefix[..<at]) as NSString).length
        Core.shared.call("mention_candidates",["conversation_id":conversationId,"query":query]) { [weak self] r in
            guard let self, gen == self.mentionGeneration, case .success(let v) = r else { return }
            self.mentionRow.arrangedSubviews.forEach { self.mentionRow.removeArrangedSubview($0); $0.removeFromSuperview() }
            let candidates = Array((v as? [JSON] ?? []).prefix(3))
            for c in candidates {
                let b = UIButton(type:.system)
                b.setTitle("@"+c.str("username")+"  "+c.str("display_name"),for:.normal)
                b.contentHorizontalAlignment = .leading; b.tintColor = Theme.accent
                b.contentEdgeInsets = UIEdgeInsets(top:12,left:16,bottom:12,right:16)
                b.addAction(UIAction { [weak self] _ in
                    guard let self, self.input.selectedRange.location == cursor else { return }
                    self.input.text = (self.input.text as NSString).replacingCharacters(in:NSRange(location:start,length:cursor-start),with:"@"+c.str("username")+" ")
                    self.input.selectedRange = NSRange(location:start+(c.str("username") as NSString).length+2,length:0)
                    self.textViewDidChange(self.input)
                },for:.touchUpInside)
                self.mentionRow.addArrangedSubview(b)
            }
            self.mentionRow.isHidden = candidates.isEmpty
        }
    }

    private func sendText() {
        if let m = editTarget {
            let changed = (input.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if blocked || (m.str("kind") == "text" && changed.isEmpty) { return }
            cancelEdit()
            if changed != m.str("text") {
                Core.shared.call("edit_message", ["message_id": m.str("id"), "text": changed]) { [weak self] r in
                    if case .failure(let e) = r { self?.toast(e.message) }
                }
            }
            return
        }
        let text = (input.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !blocked else { return }
        var p: JSON = ["conversation_id": conversationId, "text": text]
        if let r = replyTo { p["reply_to"] = r.str("id") }
        Haptics.confirm()
        // The arrow leaps up as the button folds away.
        if !Theme.reducedMotion, let arrow = send.imageView {
            arrow.transform = CGAffineTransform(translationX: 0, y: -9)
            Motion.animate(0.32, 0.55) { arrow.transform = .identity }
        }
        // Stored locally first: the field clears at once and the queue delivers it.
        input.text = ""
        textViewDidChange(input)
        setReply(nil)
        Core.shared.call("send_text", p) { [weak self] r in
            guard let self else { return }
            switch r {
            case .success: Core.shared.call("set_draft", ["conversation_id": self.conversationId, "text": ""])
            case .failure(let e):
                self.toast(e.message)
                if self.input.text.isEmpty { self.input.text = text; self.textViewDidChange(self.input) }
            }
        }
    }

    // MARK: voice messages and circles

    private func startVoice() {
        guard !blocked, editTarget == nil, recorder == nil else { return }
        guard CallManager.shared.call.isEmpty else { return toast("Во время звонка запись недоступна") }
        AVAudioSession.sharedInstance().requestRecordPermission { [weak self] ok in
            DispatchQueue.main.async {
                guard let self else { return }
                guard ok else { return self.toast("Нет доступа к микрофону. Разрешите его в настройках") }
                guard self.recorder == nil else { return }
                Playback.shared.stop()
                let r = VoiceRecorder()
                r.onTick = { [weak self, weak r] ms in
                    guard let self, let r else { return }
                    self.recBar.update(ms: ms, levels: r.levels)
                    if ms >= VoiceRecorder.maxMs { self.finishVoice() }
                }
                guard r.start() else { return self.toast("Не удалось начать запись") }
                self.recorder = r
                self.input.resignFirstResponder()
                self.showRecording(true)
                Haptics.select()
            }
        }
    }

    /// The bar covers the row while recording; everything under it is hidden so it does not show through the glass.
    private func showRecording(_ on: Bool) {
        let under: [UIView] = [attach, input, media, send, placeholder]
        recBar.isHidden = !on
        if on {
            recBar.reset()
            under.forEach { $0.alpha = 0 }
            UIAccessibility.post(notification: .announcement, argument: "Запись идёт")
        } else {
            [attach, input, send, placeholder].forEach { $0.alpha = 1 }
            media.alpha = mediaWidth.constant > 0 ? 1 : 0
        }
    }

    private func finishVoice() {
        guard let r = recorder else { return }
        recorder = nil
        let rec = r.stop()
        showRecording(false)
        guard let rec else { return toast("Не удалось сохранить запись") }
        guard rec.durationMs >= 600 else {
            try? FileManager.default.removeItem(at: rec.url)
            return toast("Сообщение слишком короткое")
        }
        sendVoice(rec)
    }

    private func cancelVoice() {
        guard let r = recorder else { return }
        recorder = nil
        r.cancel()
        showRecording(false)
    }

    private func sendVoice(_ rec: VoiceRecorder.Recording) {
        guard accountId == Core.shared.accountId else { try? FileManager.default.removeItem(at: rec.url); return }
        var p: JSON = ["_account_id": accountId, "conversation_id": conversationId, "path": rec.url.path, "mime": "audio/mp4", "kind": "voice",
                       "name": "voice.m4a", "duration": rec.durationMs, "wave": rec.wave.base64EncodedString()]
        if let r = replyTo { p["reply_to"] = r.str("id") }
        Haptics.confirm()
        setReply(nil)
        Core.shared.call("send_file", p) { [weak self] r in
            try? FileManager.default.removeItem(at: rec.url)
            if self?.accountId == Core.shared.accountId, case .failure(let e) = r { self?.toast(e.message) }
        }
    }

    private func startCircle() {
        guard !blocked, editTarget == nil, recorder == nil else { return }
        input.resignFirstResponder()
        CircleRecorderViewController.present(from: self) { [weak self] url, ms in self?.sendCircle(url, ms: ms) }
    }

    private func sendCircle(_ url: URL, ms: Int) {
        guard accountId == Core.shared.accountId else { try? FileManager.default.removeItem(at: url); return }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let info = Videos.info(url)
            DispatchQueue.main.async {
                guard let self, self.accountId == Core.shared.accountId else { try? FileManager.default.removeItem(at: url); return }
                var p: JSON = ["_account_id": self.accountId, "conversation_id": self.conversationId, "path": url.path, "mime": "video/mp4",
                               "kind": "circle", "name": "circle.mp4", "duration": ms]
                if let info {
                    if let t = info.thumb { p["thumb"] = t }
                    let side = min(info.width, info.height)
                    if side > 0 {
                        p["width"] = side
                        p["height"] = side
                    }
                }
                if let r = self.replyTo { p["reply_to"] = r.str("id") }
                Haptics.confirm()
                self.setReply(nil)
                Core.shared.call("send_file", p) { [weak self] r in
                    try? FileManager.default.removeItem(at: url)
                    if self?.accountId == Core.shared.accountId, case .failure(let e) = r { self?.toast(e.message) }
                }
            }
        }
    }

    private func updateAttachMenu() {
        var acts: [UIMenuElement] = [
            UIAction(title: "Фото", image: UIImage(systemName: "photo")) { [weak self] _ in self?.pickPhotos() },
            UIAction(title: "Видео", image: UIImage(systemName: "video")) { [weak self] _ in self?.pickVideos() },
            UIAction(title: "Камера", image: UIImage(systemName: "camera")) { [weak self] _ in self?.takePhoto() },
            UIAction(title: "Файл", image: UIImage(systemName: "doc")) { [weak self] _ in self?.pickFile() },
        ]
        if conv.bool("can_polls") && conv.str("kind") != "direct" {
            acts.append(UIAction(title: "Опрос", image: UIImage(systemName: "chart.bar")) { [weak self] _ in
                guard let self else { return }
                self.presentSheet(NewPollViewController(conversationId: self.conversationId))
            })
        }
        attach.menu = UIMenu(children: acts)
    }

    // Asked for by the attach window ("Добавить"): the next picker result goes there instead of opening a new window.
    private var pendingPick: (([OutgoingFile]) -> Void)?

    private func pickPhotos(more: (([OutgoingFile]) -> Void)? = nil) {
        pendingPick = more
        var cfg = PHPickerConfiguration()
        cfg.filter = .images
        cfg.selectionLimit = 10
        let picker = PHPickerViewController(configuration: cfg)
        picker.delegate = self
        (presentedViewController ?? self).present(picker, animated: true)
    }

    private func pickVideos(more: (([OutgoingFile]) -> Void)? = nil) {
        pendingPick = more
        var cfg = PHPickerConfiguration()
        cfg.filter = .videos
        cfg.selectionLimit = 5
        // The original file: it is re-encoded here, to MP4, before it is sent.
        cfg.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: cfg)
        picker.delegate = self
        (presentedViewController ?? self).present(picker, animated: true)
    }

    private func deliver(_ files: [OutgoingFile], photos: Bool) {
        if let done = pendingPick {
            pendingPick = nil
            done(files)
        } else {
            attach(files, photos: photos)
        }
    }

    /// Everything that is about to be sent waits in the attach window: preview, "as a file", caption.
    private func attach(_ files: [OutgoingFile], photos: Bool) {
        guard !files.isEmpty, !composer.isHidden else {
            files.forEach { try? FileManager.default.removeItem(at: $0.url) }
            return
        }
        input.resignFirstResponder()
        presentSheet(AttachViewController(files: files, asPhotos: photos, more: { [weak self] done in
            if files.allSatisfy({ $0.isVideo }) { self?.pickVideos(more: done) }
            else if photos && files.allSatisfy({ $0.isImage }) { self?.pickPhotos(more: done) }
            else { self?.pickFile(more: done) }
        }, send: { [weak self] chosen, asPhotos, caption in
            for (n, f) in chosen.enumerated() { self?.sendOutgoing(f, photo: asPhotos, caption: n == 0 ? caption : "") }
        }))
    }

    /// Pictures pasted into the field wait in the same window.
    private func pasted(_ images: [UIImage]) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("outgoing", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var files: [OutgoingFile] = []
        for img in images {
            // Large pictures (photos) go as JPEG to stay within the size limit; screenshots stay lossless.
            let big = img.size.width * img.scale * img.size.height * img.scale > 4_000_000
            guard let data = big ? img.jpegData(compressionQuality: 0.9) : img.pngData() else { continue }
            let name = big ? "Изображение.jpg" : "Изображение.png"
            let url = dir.appendingPathComponent(UUID().uuidString + "-" + name)
            guard (try? data.write(to: url)) != nil else { continue }
            files.append(OutgoingFile(url: url, name: name, mime: big ? "image/jpeg" : "image/png"))
        }
        if files.isEmpty { return toast("Не удалось вставить изображение") }
        attach(files, photos: true)
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        let group = DispatchGroup()
        var found = [OutgoingFile?](repeating: nil, count: results.count)
        let lock = NSLock()
        for (n, r) in results.enumerated() {
            let prov = r.itemProvider
            guard prov.hasItemConformingToTypeIdentifier(UTType.image.identifier) else {
                if prov.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
                    group.enter()
                    prov.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { url, _ in
                        defer { group.leave() }
                        guard let url else { return }
                        // The provider removes its file after this block: keep a copy.
                        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("outgoing", isDirectory: true)
                        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                        let dest = dir.appendingPathComponent(UUID().uuidString + "-" + url.lastPathComponent)
                        guard (try? FileManager.default.copyItem(at: url, to: dest)) != nil else { return }
                        let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "video/quicktime"
                        lock.lock()
                        found[n] = OutgoingFile(url: dest, name: url.lastPathComponent, mime: mime)
                        lock.unlock()
                    }
                }
                continue
            }
            group.enter()
            prov.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { url, _ in
                defer { group.leave() }
                guard let url else { return }
                // The provider removes its file after this block: keep a copy, as JPEG when the format is not portable (HEIC).
                let dir = FileManager.default.temporaryDirectory.appendingPathComponent("outgoing", isDirectory: true)
                try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let type = UTType(filenameExtension: url.pathExtension)
                var name = url.lastPathComponent
                var mime = type?.preferredMIMEType ?? "image/jpeg"
                var dest = dir.appendingPathComponent(UUID().uuidString + "-" + name)
                if type == .jpeg || type == .png || type == .gif {
                    guard (try? FileManager.default.copyItem(at: url, to: dest)) != nil else { return }
                } else {
                    guard let img = Photos.decode(url, maxSide: 4096), let data = img.jpegData(compressionQuality: 0.9) else { return }
                    name = (name as NSString).deletingPathExtension + ".jpg"
                    mime = "image/jpeg"
                    dest = dir.appendingPathComponent(UUID().uuidString + "-" + name)
                    guard (try? data.write(to: dest)) != nil else { return }
                }
                lock.lock()
                found[n] = OutgoingFile(url: dest, name: name, mime: mime)
                lock.unlock()
            }
        }
        group.notify(queue: .main) { [weak self] in
            let files = found.compactMap { $0 }
            guard let self else { return }
            if files.isEmpty { self.pendingPick = nil; return }
            self.deliver(files, photos: true)
        }
    }

    /// The thumbnail is made only for pictures that go as photos.
    private func sendOutgoing(_ f: OutgoingFile, photo: Bool, caption: String) {
        if photo && f.isVideo { return sendVideo(f, caption: caption) }
        if f.size > Videos.limit {
            try? FileManager.default.removeItem(at: f.url)
            return toast("Файл больше 50 МБ: " + f.name)
        }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let thumb = photo && f.isImage ? Photos.makeThumb(f.url) : nil
            DispatchQueue.main.async { self?.sendPhoto(f.url, name: f.name, mime: f.mime, thumb: thumb, caption: caption) }
        }
    }

    /// A video that goes as a video: made portable and small enough, then sent with its first frame and length.
    private func sendVideo(_ f: OutgoingFile, caption: String) {
        toast("Готовим видео…")
        var shown = 0
        let report = { [weak self] (pct: Int) in
            DispatchQueue.main.async {
                guard pct >= shown + 25 else { return }
                shown = pct
                self?.toast("Сжимаем видео… \(pct)%")
            }
        }
        Videos.prepare(f, progress: report) { [weak self] video in
            DispatchQueue.main.async {
                guard let self else { return }
                guard let video else { return self.toast("Видео не удалось подготовить: оно слишком длинное или в неизвестном формате") }
                var thumb: (String, Int, Int)?
                if let t = video.info?.thumb, let i = video.info { thumb = (t, i.width, i.height) }
                self.sendPhoto(video.url, name: video.name, mime: video.mime, thumb: thumb, caption: caption, duration: video.info?.duration)
            }
        }
    }

    private func sendPhoto(_ url: URL, name: String, mime: String, thumb: (String, Int, Int)?, caption: String = "", duration: Int? = nil) {
        guard accountId == Core.shared.accountId else { try? FileManager.default.removeItem(at: url); return }
        var p: JSON = ["_account_id": accountId, "conversation_id": conversationId, "path": url.path, "mime": mime, "name": name]
        if !caption.isEmpty { p["caption"] = caption }
        if let t = thumb {
            p["thumb"] = t.0
            p["width"] = t.1
            p["height"] = t.2
        }
        if let duration { p["duration"] = duration }
        Core.shared.call("send_file", p) { [weak self] r in
            try? FileManager.default.removeItem(at: url)
            if self?.accountId == Core.shared.accountId, case .failure(let e) = r { self?.toast(e.message) }
        }
    }

    /// The app's own camera; the shot is sent like a picked photo.
    private func takePhoto() {
        PhotoCameraViewController.present(from: self) { [weak self] url in
            let thumb = Photos.makeThumb(url)
            self?.sendPhoto(url, name: String(url.lastPathComponent.dropFirst(37)), mime: "image/jpeg", thumb: thumb)
        }
    }

    private func pickFile(more: (([OutgoingFile]) -> Void)? = nil) {
        pendingPick = more
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        picker.allowsMultipleSelection = true
        picker.delegate = self
        (presentedViewController ?? self).present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        if controller.documentPickerMode == .exportToService { toast("Файл сохранён"); return }
        var files: [OutgoingFile] = []
        for u in urls {
            let size = (try? u.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            let mime = UTType(filenameExtension: u.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
            // A video may be bigger: it is re-encoded smaller before it is sent.
            let isMovie = mime.hasPrefix("video/")
            if size > (isMovie ? 600 : 50) * 1024 * 1024 { toast(isMovie ? "Видео больше 600 МБ: " + u.lastPathComponent : "Файл больше 50 МБ: " + u.lastPathComponent); continue }
            files.append(OutgoingFile(url: u, name: u.lastPathComponent, mime: mime))
        }
        if files.isEmpty { pendingPick = nil; return }
        deliver(files, photos: files.allSatisfy { $0.isVideo })
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { pendingPick = nil }

    // MARK: pinned messages

    private func buildPinBar() {
        pinBar.axis = .horizontal
        pinBar.spacing = 4
        pinBar.alignment = .center
        pinBar.glass(20)
        pinBar.isLayoutMarginsRelativeArrangement = true
        pinBar.layoutMargins = UIEdgeInsets(top: 2, left: 14, bottom: 2, right: 2)
        let icon = UIImageView(image: UIImage(systemName: "pin.fill"))
        icon.tintColor = Theme.accent
        icon.contentMode = .scaleAspectFit
        icon.isAccessibilityElement = false
        icon.setContentHuggingPriority(.required, for: .horizontal)
        pinBar.addArrangedSubview(icon)
        pinHead.numberOfLines = 1
        pinText.numberOfLines = 1
        let texts = UIStackView(arrangedSubviews: [pinHead, pinText])
        texts.axis = .vertical
        texts.spacing = 2
        texts.isUserInteractionEnabled = false
        texts.translatesAutoresizingMaskIntoConstraints = false
        pinTap.addSubview(texts)
        pinTap.isAccessibilityElement = true
        pinTap.accessibilityTraits = .button
        pinTap.accessibilityHint = "Переходит к сообщению"
        NSLayoutConstraint.activate([
            texts.leadingAnchor.constraint(equalTo: pinTap.leadingAnchor, constant: 8),
            texts.trailingAnchor.constraint(equalTo: pinTap.trailingAnchor),
            texts.centerYAnchor.constraint(equalTo: pinTap.centerYAnchor),
            pinTap.heightAnchor.constraint(greaterThanOrEqualToConstant: 48),
        ])
        pinTap.addAction(UIAction { [weak self] _ in
            guard let self, let m = self.pins[safe: self.pinIndex] else { return }
            self.pinIndex = (self.pinIndex + 1) % self.pins.count
            self.showPins()
            self.jumpTo(m.str("id"))
        }, for: .touchUpInside)
        pinBar.addArrangedSubview(pinTap)
        pinList.setImage(UIImage(systemName: "list.bullet"), for: .normal)
        pinList.accessibilityLabel = "Все закреплённые"
        pinList.showsMenuAsPrimaryAction = true
        pinOff.setImage(UIImage(systemName: "xmark"), for: .normal)
        pinOff.accessibilityLabel = "Открепить"
        pinOff.addAction(UIAction { [weak self] _ in
            guard let self, let m = self.pins[safe: self.pinIndex] else { return }
            Core.shared.call("pin_message", ["message_id": m.str("id"), "on": false]) { [weak self] r in
                if case .failure(let e) = r { self?.toast(e.message) }
            }
        }, for: .touchUpInside)
        for button in [pinList, pinOff] {
            button.tintColor = Theme.accent
            button.setPreferredSymbolConfiguration(UIImage.SymbolConfiguration(pointSize: 16, weight: .medium), forImageIn: .normal)
            button.translatesAutoresizingMaskIntoConstraints = false
            let width = button.widthAnchor.constraint(equalToConstant: 44)
            width.priority = .defaultHigh
            NSLayoutConstraint.activate([width, button.heightAnchor.constraint(equalToConstant: 44)])
            button.springPress()
            pinBar.addArrangedSubview(button)
        }
        pinBar.isHidden = true
    }

    private func pinPreview(_ m: JSON) -> String {
        let t = m.str("text").replacingOccurrences(of: "\n", with: " ")
        let s: String
        if m.str("kind") == "poll" { s = "Опрос: " + t }
        else if let f = m.obj("file") { s = (isImage(m) ? "Фото" : isVideo(m) ? "Видео" : isVoice(m) ? "Голосовое сообщение" : isCircle(m) ? "Видеосообщение" : "Файл " + f.str("name")) + (t.isEmpty ? "" : ": " + t) }
        else { s = t }
        return s.count > 120 ? String(s.prefix(119)) + "…" : s
    }

    private func refreshPins() {
        if conv.int("pin_count") == 0 {
            pins = []
            pinIndex = 0
            return showPins()
        }
        Core.shared.call("list_pins", ["conversation_id": conversationId]) { [weak self] r in
            guard let self, case .success(let v) = r else { return }
            let next = v as? [JSON] ?? []
            if next.count != self.pins.count { self.pinIndex = 0 }
            self.pins = next
            if self.pinIndex >= next.count { self.pinIndex = 0 }
            self.showPins()
        }
    }

    private func showPins() {
        let m = pins[safe: pinIndex]
        if let m {
            pinHead.text = pins.count > 1 ? "Закреплённое сообщение · \(pinIndex + 1) из \(pins.count)" : "Закреплённое сообщение"
            pinText.text = pinPreview(m)
            pinTap.accessibilityLabel = (pinHead.text ?? "") + ": " + (pinText.text ?? "")
            pinList.menu = UIMenu(children: pins.map { p in
                UIAction(title: pinPreview(p)) { [weak self] _ in self?.jumpTo(p.str("id")) }
            })
        }
        pinList.isHidden = pins.count < 2
        pinOff.isHidden = !conv.bool("can_pin")
        if pinBar.isHidden != (m == nil) { Theme.animate(0.18) { self.pinBar.isHidden = m == nil } }
    }

    /// Scrolls to a message, paging older history in until it is loaded.
    private func jumpTo(_ id: String) {
        guard !id.isEmpty else { return }
        let row = items.firstIndex { if case .msg(let m, _, _) = $0 { return m.str("id") == id } else { return false } }
        if let row {
            jumpId = nil
            table.scrollToRow(at: IndexPath(row: row, section: 0), at: .middle, animated: !Theme.reducedMotion)
            DispatchQueue.main.asyncAfter(deadline: .now() + (Theme.reducedMotion ? 0 : 0.35)) { [weak self] in self?.flash(row) }
            return
        }
        if !hasMore { return toast("Сообщение не найдено в истории") }
        jumpId = id
        load(older: true)
    }

    /// A short pulse on the message that was jumped to.
    private func flash(_ row: Int) {
        guard !Theme.reducedMotion, let c = table.cellForRow(at: IndexPath(row: row, section: 0)) else { return }
        UIView.animate(withDuration: 0.22, animations: { c.contentView.alpha = 0.35 }) { _ in
            UIView.animate(withDuration: 0.38) { c.contentView.alpha = 1 }
        }
    }

    // MARK: data

    private func refreshConversation(first: Bool) {
        Core.shared.call("get_conversation", ["conversation_id": conversationId]) { [weak self] r in
            guard let self else { return }
            guard case .success(let v) = r, let c = v as? JSON else {
                if case .failure(let e) = r { self.toast(e.message) }
                return
            }
            self.conv = c
            if let nav = self.navigationController, nav.topViewController === self, nav.transitionCoordinator == nil,
               let root = nav.viewControllers.first, root is ChatsViewController {
                let returnsToArchive = nav.viewControllers.dropLast().last is ArchiveViewController
                if returnsToArchive != c.bool("in_archive") {
                    var path:[UIViewController] = [root]
                    if c.bool("in_archive") { path.append(ArchiveViewController()) }
                    path.append(self)
                    nav.setViewControllers(path,animated:false)
                }
            }
            let members = c.arr("members")
            let channel = c.str("kind") == "channel"
            let system = c.str("kind") == "system"
            let handle = members.first { !$0.bool("me") }.map { "@" + $0.str("username") } ?? ""
            let seen = c.obj("presence")?.str("text") ?? ""
            self.peerOnline = c.obj("presence")?.bool("online") ?? false
            self.baseSub = system ? "Системные сообщения" : self.group ? memberCount(c.int("member_count")) + (c.int("online_count") > 0 ? ", \(c.int("online_count")) в сети" : "") : channel ? (c.bool("saved") ? "Заметки для себя" : memberCount(c.int("member_count"), channel: true))
                : seen.isEmpty ? handle : seen
            self.titleLabel.text = c.str("title")
            self.titleLabel.textColor = ProfileStyle.name(c.str("title_color")) ?? Theme.text
            let peer = c.str("peer_user_id")
            self.headerAvatar.set(c.str("title"), seed: peer.isEmpty ? self.conversationId : peer, avatar: c.str("avatar_path"))
            self.headerAvatar.online = self.peerOnline
            self.updateSubtitle()
            if first { self.refreshPresence() }
            let gone = c.bool("left") || c.bool("archived") || system
            let muted = c.int64("muted") != 0
            let mute = gone || muted || !(c["can_post"] as? Bool ?? true)
            if mute { self.input.resignFirstResponder() }
            self.composer.isHidden = mute
            self.goneLabel.isHidden = !mute
            self.goneLabel.text = system ? "Сюда приходят коды входа и сообщения о новых устройствах"
                : c.bool("archived") ? (channel ? "Канал в архиве. Новые публикации недоступны" : "Группа в архиве. Новые сообщения недоступны")
                : c.bool("left") ? (channel ? "Вы вышли из канала" : "Вы вышли из этой группы")
                : muted ? "Вам запретили писать в этой группе"
                : channel ? "Публикуют только владелец и админы канала" : "Писать здесь могут только владелец и админы"
            self.updateAttachMenu()
            self.updateCallButton(gone: gone || mute)
            let keyChanged = !(c["key_changed_users"] as? [Any] ?? []).isEmpty
            self.blocked = keyChanged || !(c["can_send"] as? Bool ?? true)
            self.updateSend(animated: false)
            let n = c["notice"] as? String
            self.noticeText.text = n
            self.notice.arrangedSubviews.last?.isHidden = !keyChanged
            if self.notice.isHidden != (n?.isEmpty ?? true) { Theme.animate(0.18) { self.notice.isHidden = n?.isEmpty ?? true } }
            self.refreshPins()
            if first, !c.str("draft").isEmpty {
                self.input.text = c.str("draft")
                self.textViewDidChange(self.input)
            }
        }
    }

    private func updateCallButton(gone: Bool) {
        let callable = conv.str("kind") == "direct" && !gone && !conv.str("peer_user_id").isEmpty
        callButton.isHidden = !callable
        videoCallButton.isHidden = !callable
    }

    private func openProfile() {
        if conv.str("kind") == "direct" {
            navigationController?.pushViewController(PersonProfileViewController(userId:conv.str("peer_user_id")),animated:!Theme.reducedMotion)
        } else { presentSheet(ConversationInfoViewController(conversationId:conversationId)) }
    }

    private func startCall(video: Bool) {
        let c = conv
        CallManager.withPermissions(video: video, from: self) { cam in CallManager.shared.start(c, video: video && cam) }
    }

    /// "печатает…" wins over presence and the member count while it lasts.
    private func updateSubtitle() {
        let typing = Typing.text(conversationId, group: conv.str("kind") != "direct")
        subLabel.text = typing ?? baseSub
        subLabel.textColor = typing != nil || peerOnline ? Theme.accent : Theme.muted
        profileButton.accessibilityLabel = (titleLabel.text ?? "") + ", " + (subLabel.text ?? "")
    }

    /// The peer of a direct chat, or the other members of a group.
    private func presenceIds() -> [String] {
        let peer = conv.str("peer_user_id")
        if conv.str("kind") == "direct" { return peer.isEmpty ? [] : [peer] }
        guard conv.str("kind") == "group" else { return [] }
        return conv.arr("members").filter { !$0.bool("me") }.map { $0.str("user_id") }
    }

    private func refreshPresence() {
        let ids = presenceIds()
        guard !ids.isEmpty else { return }
        Core.shared.call("presence", ["user_ids": ids, "tz_offset": tzOffsetMinutes]) { [weak self] r in
            if case .success = r { self?.refreshConversation(first: false) }
        }
    }

    private var atBottom: Bool {
        table.contentOffset.y + table.bounds.height >= table.contentSize.height - 40
    }

    private func load(older: Bool) {
        if older && (loading || !hasMore) { return }
        loading = true
        let generation = historyGeneration
        var p: JSON = ["conversation_id": conversationId]
        if older {
            p["before"] = messages.first?.int64("local_id") ?? 0
            p["limit"] = 50
        } else {
            p["limit"] = min(200, max(50, messages.count + 20))
        }
        Core.shared.call("list_messages", p) { [weak self] r in
            guard let self, generation == self.historyGeneration else { return }
            self.loading = false
            switch r {
            case .failure(let e):
                self.jumpId = nil
                self.toast(e.message)
            case .success(let v):
                let page = v as? [JSON] ?? []
                if older && page.count < 50 { self.hasMore = false }
                self.apply(page, older: older)
            }
        }
    }

    private func apply(_ page: [JSON], older: Bool) {
        let first = messages.isEmpty
        let wasBottom = first || atBottom
        var merged: [String: JSON] = [:]
        var order: [String] = []
        let put = { (m: JSON) in
            let id = m.str("id")
            if merged[id] == nil { order.append(id) }
            merged[id] = m
        }
        if older {
            page.forEach(put)
            messages.forEach(put)
        } else {
            let oldest = page.first?.int64("local_id") ?? 0
            messages.filter { $0.int64("local_id") < oldest }.forEach(put)
            page.forEach(put)
        }
        let appended = order.filter { !known.contains($0) }
        let oldHeight = table.contentSize.height
        let oldOffset = table.contentOffset.y
        messages = order.compactMap { merged[$0] }
        known = Set(order)
        animateIds = older || first ? [] : Set(appended)
        items = build(messages)
        table.reloadData()
        table.layoutIfNeeded()
        if older {
            table.contentOffset.y = oldOffset + (table.contentSize.height - oldHeight)
            if let id = jumpId { jumpTo(id) }
            return
        }
        let own = page.contains { appended.contains($0.str("id")) && $0.bool("outgoing") }
        if page.contains(where: { appended.contains($0.str("id")) && !$0.bool("outgoing") }) { Typing.clear(conversationId) }
        if first {
            pendingBottom = true
            if table.bounds.height > 0 { pendingBottom = false; snapToBottom() }
        } else if wasBottom || own {
            scrollToBottom(animated: true)
        } else if !appended.isEmpty {
            showJump(true)
        }
        markRead()
    }

    private func build(_ ms: [JSON]) -> [Item] {
        var out: [Item] = []
        var lastDay: DateComponents?
        let cal = Calendar.current
        for (i, m) in ms.enumerated() {
            let d = Date(timeIntervalSince1970: TimeInterval(m.int64("sent_at")) / 1000)
            let day = cal.dateComponents([.year, .month, .day], from: d)
            if day != lastDay { out.append(.date(dateTitle(m.int64("sent_at")))); lastDay = day }
            if m.str("kind") == "service" { out.append(.note(m.str("text"), id: m.str("id"))); continue }
            let same = { (o: JSON?) -> Bool in
                guard let o, o.str("kind") != "service" else { return false }
                return o.str("sender_user_id") == m.str("sender_user_id") && cal.isDate(Date(timeIntervalSince1970: TimeInterval(o.int64("sent_at")) / 1000), inSameDayAs: d)
            }
            out.append(.msg(m, first: !same(i > 0 ? ms[i - 1] : nil), last: !same(i + 1 < ms.count ? ms[i + 1] : nil)))
        }
        return out
    }

    private func bottomOffset() -> CGFloat {
        max(-table.adjustedContentInset.top, table.contentSize.height - table.bounds.height + table.adjustedContentInset.bottom)
    }

    /// Lands exactly on the last row; repeats while real heights replace estimates.
    private func snapToBottom() {
        for _ in 0..<3 {
            table.layoutIfNeeded()
            let y = bottomOffset()
            if abs(table.contentOffset.y - y) < 1 { break }
            table.setContentOffset(CGPoint(x: 0, y: y), animated: false)
        }
    }

    private func scrollToBottom(animated: Bool) {
        showJump(false)
        guard !items.isEmpty else { return }
        table.layoutIfNeeded()
        // Far away: jump near the end first so the animation stays short and no rows are skipped.
        if !animated || Theme.reducedMotion || bottomOffset() - table.contentOffset.y > table.bounds.height * 2 {
            snapToBottom()
            return
        }
        table.setContentOffset(CGPoint(x: 0, y: bottomOffset()), animated: true)
    }

    private func markRead() {
        guard view.window != nil, UIApplication.shared.applicationState == .active, atBottom else { return }
        Core.shared.call("mark_read", ["conversation_id": conversationId])
    }

    func scrollViewDidScroll(_ s: UIScrollView) {
        if s.contentOffset.y < 200 && s.isDragging { load(older: true) }
        if atBottom {
            showJump(false)
            markRead()
        } else if table.contentSize.height - (s.contentOffset.y + s.bounds.height) > s.bounds.height {
            showJump(true)
        }
    }

    // MARK: table

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { items.count }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        switch items[indexPath.row] {
        case .date(let t):
            let c = tableView.dequeueReusableCell(withIdentifier: "d", for: indexPath) as! DateCell
            c.set(t)
            return c
        case .note(let t, _):
            let c = tableView.dequeueReusableCell(withIdentifier: "n", for: indexPath) as! NoteCell
            c.set(t)
            return c
        case .msg(let m, let first, let last):
            let c = tableView.dequeueReusableCell(withIdentifier: "b", for: indexPath) as! BubbleCell
            if let back = navigationController?.interactivePopGestureRecognizer { c.prioritizeBackGesture(back) }
            c.bind(m, group: group, first: first, last: last)
            c.onRetry = { [weak self] in self?.retry(m) }
            c.onFail = { [weak self] why in self?.toast(why) }
            c.onTranscribe = { [weak self] in
                guard let self else { return }
                Transcription.shared.request(m, from: self)
            }
            c.onPhoto = { [weak self] in if isVideo(m) { self?.openVideo(m) } else { self?.openPhoto(m) } }
            c.onProfile = { [weak self] in self?.navigationController?.pushViewController(PersonProfileViewController(userId:m.str("sender_user_id")),animated:!Theme.reducedMotion) }
            c.onMention = { [weak self] name in
                guard let self else { return }
                Core.shared.call("lookup_user", ["username": name, "conversation_id": self.conversationId]) { r in
                    if case .success(let v) = r, let id = (v as? JSON)?.str("user_id"), !id.isEmpty {
                        self.navigationController?.pushViewController(PersonProfileViewController(userId: id), animated: !Theme.reducedMotion)
                    } else if case .failure(let e) = r { self.toast(e.message) }
                }
            }
            c.onVote = { [weak self] i in self?.vote(m, i) }
            c.onTap = { [weak self, weak c] in
                guard let self, let c else { return }
                self.showMenu(m, from: c.bubbleView)
            }
            c.onReact = { [weak self] e in self?.react(m, e) }
            c.onSwipeReply = m.str("kind") != "undecryptable" && !composer.isHidden ? { [weak self] in self?.setReply(m) } : nil
            c.onDoubleTap = canReact(m) ? { [weak self] in self?.react(m, QuickReaction.current) } : nil
            if animateIds.remove(m.str("id")) != nil, !Theme.reducedMotion {
                // A new bubble grows out of its own bottom corner and rises into place.
                c.layoutIfNeeded()
                let size = c.contentView.bounds.size
                let corner = CGPoint(x: m.bool("outgoing") ? size.width / 2 : -size.width / 2, y: size.height / 2)
                c.contentView.alpha = 0
                c.contentView.transform = CGAffineTransform(translationX: corner.x, y: corner.y + 22)
                    .scaledBy(x: 0.82, y: 0.82).translatedBy(x: -corner.x, y: -corner.y)
                UIView.animate(withDuration: 0.12) { c.contentView.alpha = 1 }
                Motion.animate(0.44, 0.7) { c.contentView.transform = .identity }
            } else {
                c.contentView.alpha = 1
                c.contentView.transform = .identity
            }
            return c
        }
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: false)
    }

    func tableView(_ tableView: UITableView, contextMenuConfigurationForRowAt indexPath: IndexPath, point: CGPoint) -> UIContextMenuConfiguration? {
        guard case .msg(let m, _, _) = items[indexPath.row] else { return nil }
        let acts = actions(for: m)
        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { _ in
            UIMenu(children: acts.map { a in
                UIAction(title: a.title, image: UIImage(systemName: a.icon), attributes: a.destructive ? .destructive : []) { _ in a.run() }
            })
        }
    }

    private func canReact(_ m: JSON) -> Bool {
        ["text", "file", "poll"].contains(m.str("kind")) && m.str("status") != "failed" && !conv.bool("left") && !conv.bool("archived")
            && conv.str("kind") != "system" && (conv["key_changed_users"] as? [Any] ?? []).isEmpty
    }

    /// The same actions on a tap (with reactions) and on a long press.
    private func actions(for m: JSON) -> [MenuAction] {
        var acts: [MenuAction] = []
        let ok = m.str("kind") != "undecryptable"
        if ok && !composer.isHidden { acts.append(MenuAction(title: "Ответить", icon: "arrowshape.turn.up.left") { [weak self] in self?.setReply(m) }) }
        if ok && !m.str("text").isEmpty { acts.append(MenuAction(title: "Копировать текст", icon: "doc.on.doc") { UIPasteboard.general.string = m.str("text") }) }
        for l in links(m).prefix(3) {
            if let u = URL(string: l) { acts.append(MenuAction(title: "Открыть ссылку: " + (u.host ?? l), icon: "safari") { UIApplication.shared.open(u) }) }
        }
        if isImage(m) { acts.append(MenuAction(title: "Открыть фото", icon: "photo") { [weak self] in self?.openPhoto(m) }) }
        if isVideo(m) { acts.append(MenuAction(title: "Открыть видео", icon: "play.rectangle") { [weak self] in self?.openVideo(m) }) }
        if isVoice(m) || isCircle(m), ok {
            if transcriptOf(m) != nil {
                acts.append(MenuAction(title: "Убрать расшифровку", icon: "text.badge.xmark") { Transcription.shared.clear(m.str("id")) })
            } else {
                acts.append(MenuAction(title: "Расшифровать", icon: "text.bubble") { [weak self] in
                    guard let self else { return }
                    Transcription.shared.request(m, from: self)
                })
            }
        }
        let postable = ok && m.str("status") != "failed" && ["text", "file", "poll"].contains(m.str("kind"))
        if postable && conv.bool("can_pin") {
            let on = !m.bool("pinned")
            acts.append(MenuAction(title: on ? "Закрепить" : "Открепить", icon: on ? "pin" : "pin.slash") { [weak self] in
                Core.shared.call("pin_message", ["message_id": m.str("id"), "on": on]) { r in if case .failure(let e) = r { self?.toast(e.message) } }
            })
        }
        if postable && !conv.bool("saved") {
            acts.append(MenuAction(title: "В избранное", icon: "bookmark") { [weak self] in
                Core.shared.call("save_message", ["message_id": m.str("id")]) { r in
                    if case .failure(let e) = r { self?.toast(e.message) } else { self?.toast("Сохранено в избранное") }
                }
            })
        }
        if !composer.isHidden && m.bool("outgoing") && ["sent", "delivered", "read"].contains(m.str("status")) && ["text", "file"].contains(m.str("kind")) && !isVoice(m) && !isCircle(m) {
            acts.append(MenuAction(title: "Изменить", icon: "pencil") { [weak self] in self?.setEdit(m) })
        }
        if m.str("kind") == "poll", let p = m.obj("poll") {
            let who = p.arr("options").compactMap { o -> String? in
                let v = o["voters"] as? [String] ?? []
                return v.isEmpty ? nil : o.str("text") + ": " + v.joined(separator: ", ")
            }
            if !who.isEmpty {
                acts.append(MenuAction(title: "Кто голосовал", icon: "person.2") { [weak self] in
                    let a = UIAlertController(title: "Кто голосовал", message: who.joined(separator: "\n"), preferredStyle: .alert)
                    a.addAction(UIAlertAction(title: "Готово", style: .cancel))
                    self?.present(a, animated: !Theme.reducedMotion)
                })
            }
            if m.bool("outgoing") && !p.bool("closed") {
                acts.append(MenuAction(title: "Завершить опрос", icon: "stop.circle") { [weak self] in
                    Core.shared.call("close_poll", ["message_id": m.str("id")]) { r in if case .failure(let e) = r { self?.toast(e.message) } }
                })
            }
        }
        if m.str("kind") == "file" {
            acts.append(MenuAction(title: "Открыть файл", icon: "doc") { [weak self] in self?.openFile(m) })
            acts.append(MenuAction(title: "Сохранить как…", icon: "square.and.arrow.down") { [weak self] in self?.saveFile(m) })
        }
        if m.str("status") == "failed" { acts.append(MenuAction(title: "Повторить отправку", icon: "arrow.clockwise") { [weak self] in self?.retry(m) }) }
        if ok && m.str("status") != "failed", ["text", "file", "poll"].contains(m.str("kind")) {
            acts.append(MenuAction(title: "Переслать", icon: "arrowshape.turn.up.right") { [weak self] in
                self?.presentSheet(ForwardPickerViewController(msgs: [m]))
            })
        }
        if m.bool("outgoing") && m.str("status") != "failed" && ["text", "file", "poll"].contains(m.str("kind")) {
            acts.append(MenuAction(title: "Удалить у всех", icon: "trash.slash", destructive: true) { [weak self] in
                Core.shared.call("delete_everywhere", ["message_id": m.str("id")]) { r in if case .failure(let e) = r { self?.toast(e.message) } }
            })
        }
        if !ok { acts.append(MenuAction(title: "Повторить расшифровку", icon: "arrow.clockwise") { [weak self] in self?.retryDecrypt(m) }) }
        acts.append(MenuAction(title: "Удалить у себя", icon: "trash", destructive: true) { [weak self] in
            Core.shared.call("delete_local_message", ["message_id": m.str("id")]) { r in if case .failure(let e) = r { self?.toast(e.message) } }
        })
        return acts
    }

    /// A tap on a message: quick reactions on top, actions below.
    private func showMenu(_ m: JSON, from source: UIView) {
        let mine = m.arr("reactions").first { $0.bool("mine") }?.str("emoji")
        let vc = MessageMenuViewController(reactions: canReact(m), mine: mine, actions: actions(for: m), react: { [weak self] e in self?.react(m, e) }, more: { [weak self] in
            self?.presentSheet(EmojiPickerViewController(title: "Реакция") { e in self?.react(m, e) })
        })
        vc.modalPresentationStyle = .popover
        if let pop = vc.popoverPresentationController {
            pop.sourceView = source
            pop.sourceRect = source.bounds
            pop.permittedArrowDirections = [.up, .down]
            pop.backgroundColor = Theme.glassStrong
            pop.delegate = self
        }
        UISelectionFeedbackGenerator().selectionChanged()
        present(vc, animated: !Theme.reducedMotion)
    }

    func adaptivePresentationStyle(for controller: UIPresentationController, traitCollection: UITraitCollection) -> UIModalPresentationStyle { .none }

    /// One reaction per person: the same emoji again removes it.
    private func react(_ m: JSON, _ e: String) {
        let mine = m.arr("reactions").first { $0.bool("mine") }?.str("emoji")
        Core.shared.call("react", ["message_id": m.str("id"), "emoji": e == mine ? "" : e]) { [weak self] r in
            if case .failure(let err) = r { self?.toast(err.message) }
        }
    }

    private func vote(_ m: JSON, _ option: Int) {
        guard let p = m.obj("poll"), !p.bool("closed") else { return }
        let my = (p["my_choices"] as? [NSNumber] ?? []).map { $0.intValue }
        // Multiple choice: a tap toggles the answer.
        let choices: [Int] = p.bool("multiple") ? (my.contains(option) ? my.filter { $0 != option } : my + [option]) : [option]
        Core.shared.call("vote_poll", ["message_id": m.str("id"), "choices": choices]) { [weak self] r in
            if case .failure(let e) = r { self?.toast(e.message) }
        }
    }

    /// Opens the photo among all pictures of the chat; swiping left and right moves between them.
    private func openPhoto(_ m: JSON) {
        let photos = messages.filter { isImage($0) }
        let at = photos.firstIndex { $0.str("id") == m.str("id") }
        present(PhotoGalleryController(photos: at == nil ? [m] : photos, start: at ?? 0), animated: !Theme.reducedMotion)
    }

    /// Fetches and decrypts the video, then plays it in the system player.
    private func openVideo(_ m: JSON) {
        if m.obj("file")?.bool("downloaded") != true { toast("Загружаем видео…") }
        Core.shared.call("download_file", ["message_id": m.str("id")]) { [weak self] r in
            switch r {
            case .failure(let e): self?.toast(e.message)
            case .success(let v):
                guard let self, let path = (v as? JSON)?.str("path"), !path.isEmpty else { return }
                // The silent switch must not mute a video; a running call keeps its own audio session.
                if CallManager.shared.call.isEmpty { try? AVAudioSession.sharedInstance().setCategory(.playback) }
                let vc = AVPlayerViewController()
                vc.player = AVPlayer(url: URL(fileURLWithPath: path))
                self.present(vc, animated: !Theme.reducedMotion) { vc.player?.play() }
            }
        }
    }

    private func retry(_ m: JSON) {
        Core.shared.call("retry_message", ["message_id": m.str("id")]) { [weak self] r in if case .failure(let e) = r { self?.toast(e.message) } }
    }

    private func retryDecrypt(_ m: JSON) {
        Core.shared.call("retry_decrypt", ["message_id": m.str("id")]) { [weak self] r in
            switch r {
            case .failure(let e): self?.toast(e.message)
            case .success(let v): if (v as? JSON)?.bool("decrypted") != true { self?.toast((v as? JSON)?.str("message") ?? "") }
            }
        }
    }

    private func decrypt(_ m: JSON, _ then: @escaping (URL) -> Void) {
        toast("Расшифровываем файл…")
        Core.shared.call("download_file", ["message_id": m.str("id")]) { [weak self] r in
            switch r {
            case .failure(let e): self?.toast(e.message)
            case .success(let v): then(URL(fileURLWithPath: (v as? JSON)?.str("path") ?? ""))
            }
        }
    }

    private func openFile(_ m: JSON) {
        decrypt(m) { [weak self] url in
            guard let self else { return }
            self.previewURL = url
            let ql = QLPreviewController()
            ql.dataSource = self
            self.present(ql, animated: true)
        }
    }

    private func saveFile(_ m: JSON) {
        decrypt(m) { [weak self] url in
            let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
            picker.delegate = self
            self?.present(picker, animated: true)
        }
    }

    func numberOfPreviewItems(in controller: QLPreviewController) -> Int { previewURL == nil ? 0 : 1 }
    func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { previewURL! as NSURL }
}

final class DateCell: UITableViewCell {
    private let l = label("", 13, .medium, color: Theme.muted)

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none
        l.textAlignment = .center
        l.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(l)
        NSLayoutConstraint.activate([
            l.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 16), l.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -8),
            l.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
        ])
        l.accessibilityTraits = .header
    }

    required init?(coder: NSCoder) { fatalError() }
    func set(_ t: String) { l.text = t }
}

final class NoteCell: UITableViewCell {
    private let l = label("", 12.5, .medium, color: Theme.muted)
    private let pill = UIView()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none
        pill.backgroundColor = Theme.muted.withAlphaComponent(0.16)
        pill.layer.cornerRadius = 14
        pill.translatesAutoresizingMaskIntoConstraints = false
        l.textAlignment = .center
        l.numberOfLines = 0
        l.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(pill)
        pill.addSubview(l)
        NSLayoutConstraint.activate([
            pill.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 6), pill.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -6),
            pill.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
            pill.leadingAnchor.constraint(greaterThanOrEqualTo: contentView.leadingAnchor, constant: 24),
            pill.trailingAnchor.constraint(lessThanOrEqualTo: contentView.trailingAnchor, constant: -24),
            l.topAnchor.constraint(equalTo: pill.topAnchor, constant: 5), l.bottomAnchor.constraint(equalTo: pill.bottomAnchor, constant: -5),
            l.leadingAnchor.constraint(equalTo: pill.leadingAnchor, constant: 14), l.trailingAnchor.constraint(equalTo: pill.trailingAnchor, constant: -14),
        ])
    }

    required init?(coder: NSCoder) { fatalError() }

    func set(_ t: String) {
        l.text = t
        accessibilityLabel = t
        isAccessibilityElement = true
    }
}

final class BubbleCell: UITableViewCell {
    private let avatar = AvatarView(size: 30)
    private let reactRow = UIStackView()
    private let bubbleTap = UITapGestureRecognizer()
    private let bubbleTapDelegate = BubbleTapDelegate()
    private let sender = label("", 13, .semibold, color: Theme.muted)
    private let bubble = UIView()
    private let content = UIStackView()
    private let photo = UIImageView()
    private let mark = VideoMarkView()
    private let circle = CircleView()
    private let voiceRow = UIStackView()
    private let playButton = UIButton(type: .system)
    private let wave = WaveView()
    private let voiceTime = UILabel()
    private let speedButton = UIButton(type: .system)
    private let transButton = UIButton(type: .system)
    private let transText = label("", 15)
    private var waveWidth: NSLayoutConstraint!
    private var bound: JSON = [:]
    private var observers: [NSObjectProtocol] = []
    var onFail: ((String) -> Void)?
    var onTranscribe: (() -> Void)?
    private var photoW: NSLayoutConstraint!
    private var photoH: NSLayoutConstraint!
    private let inner = UIStackView()
    private let fwd = label("", 13, .semibold)
    private let quote = label("", 13)
    private let fileRow = UIStackView()
    private let fileName = label("", 15, .semibold)
    private let fileMeta = label("", 13)
    private let text = label("", 17)
    private let emojiRow = UIStackView()
    private let poll = UIStackView()
    private let pollQuestion = label("", 17, .semibold)
    private let pollInfo = label("", 13)
    private let pollOptions = UIStackView()
    private let meta = label("", 12)
    private let delivery = DeliveryMarkView()
    private let retry = UIButton(type: .system)
    private var lead: NSLayoutConstraint!
    private var trail: NSLayoutConstraint!
    private var topGap: NSLayoutConstraint!
    private var boundId = ""
    private var statusId = ""
    private var statusSig = ""
    private var lastReactsId = ""
    private var lastReactsSig: String?
    var onRetry: (() -> Void)?
    var onPhoto: (() -> Void)?
    var onVote: ((Int) -> Void)?
    private let doubleTap = UITapGestureRecognizer()
    private let replyPan = UIPanGestureRecognizer()
    private let replyPanDelegate = ReplySwipeDelegate()
    private let replyIcon = UIImageView(image: UIImage(systemName: "arrowshape.turn.up.left.fill"))
    private var replyArmed = false
    private var replyIconTrailing: NSLayoutConstraint!
    private var replyIconLeading: NSLayoutConstraint!
    var onTap: (() -> Void)?
    var onMention: ((String) -> Void)?
    var onProfile: (() -> Void)?
    var onReact: ((String) -> Void)?
    /// Reply with a swipe in either direction anywhere on the row, including empty space.
    var onSwipeReply: (() -> Void)?
    /// Double tap anywhere on the row: the quick reaction.
    var onDoubleTap: (() -> Void)? { didSet { doubleTap.isEnabled = onDoubleTap != nil } }
    var bubbleView: UIView { bubble }

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none
        avatar.isUserInteractionEnabled = true
        avatar.addGestureRecognizer(TapGesture { [weak self] in self?.onProfile?() })
        sender.isUserInteractionEnabled = true
        sender.addGestureRecognizer(TapGesture { [weak self] in self?.onProfile?() })
        bubble.layer.cornerRadius = 23
        bubble.layer.cornerCurve = .continuous
        let icon = UIImageView(image: UIImage(systemName: "doc.fill"))
        icon.tintColor = Theme.accent
        icon.contentMode = .scaleAspectFit
        icon.widthAnchor.constraint(equalToConstant: 30).isActive = true
        let fcol = UIStackView(arrangedSubviews: [fileName, fileMeta])
        fcol.axis = .vertical
        fcol.spacing = 2
        fileRow.addArrangedSubview(icon)
        fileRow.addArrangedSubview(fcol)
        fileRow.spacing = 12
        fileRow.alignment = .center
        quote.numberOfLines = 2
        meta.textAlignment = .right
        delivery.backgroundColor = .clear
        delivery.isOpaque = false
        delivery.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([delivery.widthAnchor.constraint(equalToConstant: 16), delivery.heightAnchor.constraint(equalToConstant: 16)])
        let metaSpacer = UIView()
        metaSpacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        let metaRow = UIStackView(arrangedSubviews: [metaSpacer, meta, delivery])
        metaRow.spacing = 4
        metaRow.alignment = .center
        photo.contentMode = .scaleAspectFill
        photo.clipsToBounds = true
        photo.layer.cornerRadius = 19
        photo.layer.cornerCurve = .continuous
        photo.isUserInteractionEnabled = true
        photo.addGestureRecognizer(TapGesture { [weak self] in self?.onPhoto?() })
        mark.isHidden = true
        mark.translatesAutoresizingMaskIntoConstraints = false
        photo.addSubview(mark)
        NSLayoutConstraint.activate([
            mark.topAnchor.constraint(equalTo: photo.topAnchor), mark.bottomAnchor.constraint(equalTo: photo.bottomAnchor),
            mark.leadingAnchor.constraint(equalTo: photo.leadingAnchor), mark.trailingAnchor.constraint(equalTo: photo.trailingAnchor),
        ])
        photoW = photo.widthAnchor.constraint(equalToConstant: 200)
        photoW.priority = UILayoutPriority(999)
        photoH = photo.heightAnchor.constraint(equalToConstant: 150)
        photoH.priority = UILayoutPriority(999)
        pollOptions.axis = .vertical
        poll.axis = .vertical
        poll.spacing = 2
        [pollQuestion, pollInfo, pollOptions].forEach(poll.addArrangedSubview)
        poll.setCustomSpacing(8, after: pollInfo)
        let pollWidth = poll.widthAnchor.constraint(greaterThanOrEqualToConstant: 240)
        pollWidth.priority = UILayoutPriority(999)
        pollWidth.isActive = true
        // Voice message: a round play button, the wave with the time under it and, while it plays, the speed.
        playButton.setImage(UIImage(systemName: "play.fill"), for: .normal)
        playButton.layer.cornerRadius = 20
        playButton.accessibilityLabel = "Воспроизвести"
        playButton.addAction(UIAction { [weak self] _ in self?.togglePlay() }, for: .touchUpInside)
        playButton.springPress()
        wave.onSeek = { [weak self] f in self?.seek(f) }
        waveWidth = wave.widthAnchor.constraint(equalToConstant: 150)
        waveWidth.priority = UILayoutPriority(999)
        voiceTime.font = .monospacedDigitSystemFont(ofSize: 12, weight: .regular)
        let voiceCol = UIStackView(arrangedSubviews: [wave, voiceTime])
        voiceCol.axis = .vertical
        voiceCol.spacing = 2
        speedButton.titleLabel?.font = .monospacedDigitSystemFont(ofSize: 12, weight: .semibold)
        speedButton.layer.cornerRadius = 12
        speedButton.contentEdgeInsets = UIEdgeInsets(top: 0, left: 8, bottom: 0, right: 8)
        speedButton.setTitle("1×", for: .normal)
        speedButton.alpha = 0
        speedButton.isUserInteractionEnabled = false
        speedButton.accessibilityLabel = "Скорость воспроизведения"
        speedButton.addAction(UIAction { _ in Playback.shared.cycleSpeed() }, for: .touchUpInside)
        voiceRow.addArrangedSubview(playButton)
        voiceRow.addArrangedSubview(voiceCol)
        voiceRow.addArrangedSubview(speedButton)
        voiceRow.spacing = 10
        voiceRow.alignment = .center
        voiceRow.isHidden = true
        NSLayoutConstraint.activate([
            playButton.widthAnchor.constraint(equalToConstant: 40), playButton.heightAnchor.constraint(equalToConstant: 40),
            wave.heightAnchor.constraint(equalToConstant: 28), waveWidth, speedButton.heightAnchor.constraint(equalToConstant: 24),
        ])
        circle.isHidden = true
        circle.onTap = { [weak self] in self?.togglePlay() }
        transButton.setImage(UIImage(systemName: "text.bubble"), for: .normal)
        transButton.setTitle("Расшифровать", for: .normal)
        transButton.titleLabel?.font = Theme.font(14, .semibold)
        transButton.titleEdgeInsets = UIEdgeInsets(top: 0, left: 8, bottom: 0, right: -8)
        transButton.contentHorizontalAlignment = .leading
        transButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 36).isActive = true
        transButton.addAction(UIAction { [weak self] _ in self?.onTranscribe?() }, for: .touchUpInside)
        transButton.isHidden = true
        transText.numberOfLines = 0
        transText.isHidden = true
        inner.axis = .vertical
        inner.spacing = 4
        reactRow.spacing = 6
        reactRow.alignment = .center
        let spacer = UIView()
        spacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        reactRow.addArrangedSubview(spacer)
        emojiRow.spacing = 4
        emojiRow.alignment = .center
        emojiRow.isHidden = true
        [fwd, quote, fileRow, voiceRow, emojiRow, text, transText, transButton, poll, reactRow, metaRow].forEach(inner.addArrangedSubview)
        inner.setCustomSpacing(6, after: reactRow)
        bubbleTap.addTarget(self, action: #selector(tapped(_:)))
        bubbleTapDelegate.photo = photo
        bubbleTapDelegate.circle = circle
        bubbleTapDelegate.wave = wave
        bubbleTapDelegate.avatar = avatar
        bubbleTapDelegate.sender = sender
        bubbleTap.delegate = bubbleTapDelegate
        contentView.addGestureRecognizer(bubbleTap)
        // A single tap waits for a possible second one only where a quick reaction is allowed.
        doubleTap.numberOfTapsRequired = 2
        doubleTap.addTarget(self, action: #selector(doubleTapped))
        doubleTap.delegate = bubbleTapDelegate
        doubleTap.isEnabled = false
        contentView.addGestureRecognizer(doubleTap)
        bubbleTap.require(toFail: doubleTap)
        replyPan.addTarget(self, action: #selector(panned(_:)))
        replyPanDelegate.cell = self
        replyPan.delegate = replyPanDelegate
        contentView.addGestureRecognizer(replyPan)
        replyIcon.tintColor = Theme.accent
        replyIcon.contentMode = .scaleAspectFit
        replyIcon.alpha = 0
        replyIcon.isAccessibilityElement = false
        replyIcon.translatesAutoresizingMaskIntoConstraints = false
        addSubview(replyIcon)
        replyIconTrailing = replyIcon.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -18)
        replyIconLeading = replyIcon.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 18)
        replyIconTrailing.isActive = true
        NSLayoutConstraint.activate([
            replyIcon.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            replyIcon.widthAnchor.constraint(equalToConstant: 24), replyIcon.heightAnchor.constraint(equalToConstant: 24),
        ])
        bubble.isUserInteractionEnabled = true
        inner.isLayoutMarginsRelativeArrangement = true
        content.axis = .vertical
        content.spacing = 4
        content.addArrangedSubview(photo)
        content.addArrangedSubview(circle)
        content.addArrangedSubview(inner)
        content.isLayoutMarginsRelativeArrangement = true
        content.translatesAutoresizingMaskIntoConstraints = false
        bubble.addSubview(content)
        retry.setTitle("Не отправлено · Повторить", for: .normal)
        retry.setTitleColor(Theme.warning, for: .normal)
        retry.titleLabel?.font = Theme.font(13, .semibold)
        retry.addAction(UIAction { [weak self] _ in self?.onRetry?() }, for: .touchUpInside)
        let col = UIStackView(arrangedSubviews: [sender, bubble, retry])
        col.axis = .vertical
        col.spacing = 3
        col.alignment = .leading
        col.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(avatar)
        contentView.addSubview(col)
        lead = col.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 44)
        trail = col.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -4)
        topGap = col.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 8)
        NSLayoutConstraint.activate([
            content.topAnchor.constraint(equalTo: bubble.topAnchor), content.bottomAnchor.constraint(equalTo: bubble.bottomAnchor),
            content.leadingAnchor.constraint(equalTo: bubble.leadingAnchor), content.trailingAnchor.constraint(equalTo: bubble.trailingAnchor),
            bubble.widthAnchor.constraint(lessThanOrEqualTo: contentView.widthAnchor, multiplier: 0.78),
            topGap, col.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
            avatar.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 4),
            avatar.bottomAnchor.constraint(equalTo: bubble.bottomAnchor),
        ])
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(forName: .playbackChanged, object: nil, queue: .main) { [weak self] n in
            guard let self, let id = n.userInfo?["id"] as? String, id.isEmpty || id == self.boundId else { return }
            self.bindPlayback()
        })
        observers.append(nc.addObserver(forName: .transcriptionChanged, object: nil, queue: .main) { [weak self] _ in self?.bindPlayback() })
    }

    required init?(coder: NSCoder) { fatalError() }

    deinit {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
    }

    private func togglePlay() {
        Playback.shared.toggle(bound, circle: isCircle(bound) ? circle : nil) { [weak self] why in self?.onFail?(why) }
    }

    /// A tap on the wave moves to that place; on a message that is not playing it starts it.
    private func seek(_ fraction: CGFloat) {
        if Playback.shared.state(of: boundId) == .idle { togglePlay() } else { Playback.shared.seek(boundId, to: fraction) }
    }

    /// What changes while a message plays or is turned into text: the button, the wave, the time, the status line.
    private func bindPlayback() {
        let voiceMsg = isVoice(bound), circleMsg = isCircle(bound)
        // A reused cell may still show the button of a voice message.
        transText.isHidden = true
        transButton.isHidden = true
        guard voiceMsg || circleMsg else { return }
        let id = boundId
        let total = bound.obj("file")?.int64("duration") ?? 0
        let state = Playback.shared.state(of: id)
        let active = state == .playing || state == .paused
        let pos = active ? Int64(Playback.shared.position * 1000) : 0
        if voiceMsg {
            playButton.setImage(UIImage(systemName: state == .playing ? "pause.fill" : "play.fill"), for: .normal)
            playButton.accessibilityLabel = state == .playing ? "Пауза" : "Воспроизвести"
            wave.progress = Playback.shared.progress(of: id)
            voiceTime.text = active ? Videos.clock(pos) + " / " + Videos.clock(total) : Videos.clock(total)
            speedButton.alpha = active ? 1 : 0
            speedButton.isUserInteractionEnabled = active
            let sp = Playback.shared.speed
            UIView.performWithoutAnimation {
                speedButton.setTitle(sp == 1.5 ? "1,5×" : sp == 2 ? "2×" : "1×", for: .normal)
                speedButton.layoutIfNeeded()
            }
        } else {
            circle.update(state: state, progress: Playback.shared.progress(of: id), positionMs: pos, lengthMs: total)
        }
        let text = transcriptOf(bound)
        let phase = Transcription.shared.status(of: id)
        transText.isHidden = text == nil
        transText.text = (text ?? "").isEmpty ? "Речь не найдена" : text
        transButton.isHidden = text != nil
        let title: String
        switch phase {
        case .model(let percent)?: title = "Скачиваем модель… \(percent)%"
        case .working?: title = "Расшифровываем…"
        case nil: title = "Расшифровать"
        }
        UIView.performWithoutAnimation {
            transButton.setTitle(title, for: .normal)
            transButton.layoutIfNeeded()
        }
        transButton.isEnabled = phase == nil
    }

    @objc private func tapped(_ g: UITapGestureRecognizer) {
        let point = g.location(in: text)
        if !text.isHidden, text.bounds.contains(point), let name = text.mentionName(at: point) {
            onMention?(name)
            return
        }
        onTap?()
    }

    @objc private func doubleTapped() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        onDoubleTap?()
    }

    func prioritizeBackGesture(_ back: UIGestureRecognizer) { replyPan.require(toFail: back) }

    /// The row follows the finger in either direction; past the threshold a release replies.
    @objc private func panned(_ g: UIPanGestureRecognizer) {
        let threshold: CGFloat = 56
        switch g.state {
        case .changed:
            let translation = g.translation(in: contentView).x
            let sign: CGFloat = translation >= 0 ? 1 : -1
            let raw = abs(translation)
            if sign > 0 {
                replyIconTrailing.isActive = false
                replyIconLeading.isActive = true
            } else {
                replyIconLeading.isActive = false
                replyIconTrailing.isActive = true
            }
            let dx = raw < threshold ? raw : threshold + (raw - threshold) * 0.3
            contentView.transform = CGAffineTransform(translationX: sign * min(dx, 88), y: 0)
            let p = min(1, raw / threshold)
            replyIcon.alpha = p
            replyIcon.transform = CGAffineTransform(scaleX: 0.5 + 0.5 * p, y: 0.5 + 0.5 * p)
            let armed = raw >= threshold
            if armed != replyArmed {
                replyArmed = armed
                if armed { replyHaptic() }
            }
        case .ended, .cancelled, .failed:
            let fire = g.state == .ended && replyArmed
            replyArmed = false
            let reset = { [self] in
                contentView.transform = .identity
                replyIcon.alpha = 0
                replyIcon.transform = .identity
            }
            if Theme.reducedMotion {
                reset()
            } else {
                UIView.animate(withDuration: 0.24, delay: 0, usingSpringWithDamping: 0.9, initialSpringVelocity: 0,
                               options: [.allowUserInteraction, .beginFromCurrentState], animations: reset)
            }
            if fire { onSwipeReply?() }
        default: break
        }
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        replyArmed = false
        replyIcon.alpha = 0
        replyIcon.transform = .identity
    }

    override var accessibilityCustomActions: [UIAccessibilityCustomAction]? {
        get {
            var acts: [UIAccessibilityCustomAction] = []
            if isVoice(bound) || isCircle(bound) { acts.append(UIAccessibilityCustomAction(name: "Воспроизвести или остановить") { [weak self] _ in self?.togglePlay(); return true }) }
            if let r = onSwipeReply { acts.append(UIAccessibilityCustomAction(name: "Ответить") { _ in r(); return true }) }
            if let q = onDoubleTap { acts.append(UIAccessibilityCustomAction(name: "Быстрая реакция " + QuickReaction.current) { _ in q(); return true }) }
            return acts
        }
        set {}
    }

    func bind(_ m: JSON, group: Bool, first: Bool, last: Bool) {
        boundId = m.str("id")
        let out = m.bool("outgoing")
        let kind = m.str("kind")
        let col = bubble.superview as! UIStackView
        col.alignment = out ? .trailing : .leading
        lead.isActive = false
        trail.isActive = false
        lead = out ? col.leadingAnchor.constraint(greaterThanOrEqualTo: contentView.leadingAnchor, constant: 48)
            : col.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: group ? 42 : 4)
        trail = out ? col.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -4)
            : col.trailingAnchor.constraint(lessThanOrEqualTo: contentView.trailingAnchor, constant: -32)
        lead.isActive = true
        trail.isActive = true
        topGap.constant = first ? 8 : 3
        avatar.isHidden = !(group && !out && last)
        avatar.set(m.str("sender_name"), seed: m.str("sender_user_id"), avatar: m.str("sender_avatar_path"))
        sender.isHidden = !(group && !out && first)
        sender.text = m.str("sender_name") + (m.str("sender_tag").isEmpty ? "" : " · " + m.str("sender_tag"))
        sender.textColor = ProfileStyle.name(m.str("sender_color")) ?? Theme.muted
        // One to three emoji without text are shown large, without a bubble.
        let emojiCount = Prefs.bigEmoji && kind == "text" && m.obj("reply_to") == nil ? m.int("emoji_only") : 0
        let big = emojiCount > 0
        bubble.backgroundColor = big ? .clear : out ? Theme.outgoing : Theme.incoming
        bubble.layer.borderWidth = out || big ? 0 : 1
        bubble.layer.borderColor = Theme.rim.resolvedColor(with: traitCollection).cgColor
        let fg: UIColor = out && !big ? .white : Theme.text
        let muted: UIColor = out && !big ? Theme.outgoingMuted : Theme.muted
        text.textColor = fg
        fileName.textColor = fg
        fileMeta.textColor = muted
        quote.textColor = muted
        meta.textColor = muted
        fwd.textColor = out ? .white : Theme.accent
        if let f = m.obj("fwd"), !f.str("name").isEmpty {
            fwd.isHidden = false
            fwd.text = "Переслано: " + f.str("name")
        } else { fwd.isHidden = true }
        if let r = m.obj("reply_to") {
            quote.isHidden = false
            let who = r.str("sender_name")
            let line = who + ": " + (r.str("kind") == "file" ? "Файл" : r.str("text").replacingOccurrences(of: "\n", with: " "))
            // The quoted person's own colour on their name, read against the bubble it sits in.
            if let tint = ProfileStyle.name(r.str("sender_color"), on: out ? Theme.outgoing : Theme.canvas) {
                let styled = NSMutableAttributedString(string: line, attributes: [.font: quote.font as Any, .foregroundColor: muted])
                styled.addAttribute(.foregroundColor, value: tint, range: NSRange(location: 0, length: (who as NSString).length + 1))
                quote.attributedText = styled
            } else {
                quote.text = line
            }
        } else { quote.isHidden = true }
        let f = m.obj("file")
        let video = isVideo(m)
        let image = isImage(m) || video
        let voiceMsg = isVoice(m), circleMsg = isCircle(m)
        let tight = image || circleMsg
        bound = m
        photo.isHidden = !image
        content.layoutMargins = tight ? UIEdgeInsets(top: 4, left: 4, bottom: 7, right: 4) : UIEdgeInsets(top: 9, left: 15, bottom: 7, right: 14)
        inner.layoutMargins = tight ? UIEdgeInsets(top: 0, left: 11, bottom: 0, right: 10) : .zero
        if image, let f {
            let maxW = min(300, UIScreen.main.bounds.width * 0.78 - 8)
            var iw = CGFloat((f["width"] as? NSNumber)?.doubleValue ?? 0)
            var ih = CGFloat((f["height"] as? NSNumber)?.doubleValue ?? 0)
            if iw <= 0 || ih <= 0 { iw = 4; ih = 3 }
            let k = min(maxW / iw, 340 / ih)
            photoW.constant = max(140, iw * k)
            photoH.constant = max(90, ih * k)
            photoW.isActive = true
            photoH.isActive = true
            let id = boundId
            mark.isHidden = !video
            mark.length = video && f.int64("duration") > 0 ? Videos.clock(f.int64("duration")) : ""
            photo.backgroundColor = video ? UIColor(red: 0.06, green: 0.09, blue: 0.13, alpha: 1) : nil
            if video {
                // Only the first frame is shown; the video itself is fetched when it is played.
                photo.image = Photos.thumb(m)
            } else if let img = Photos.cache.object(forKey: id as NSString) {
                photo.image = img
            } else {
                photo.image = Photos.thumb(m)
                if Prefs.autoload { Photos.load(m) { [weak self] img in if self?.boundId == id { self?.photo.image = img } } }
            }
        } else {
            photoW.isActive = false
            photoH.isActive = false
            photo.image = nil
            photo.backgroundColor = nil
            mark.isHidden = true
        }
        // Explicit wrap widths: the automatic one is the label's previous frame, so a reused
        // cell would wrap this message at the width of the message it showed before.
        let room = min(bounds.width * 0.78, bounds.width - (out ? 52 : group ? 74 : 36))
        let wrap = max(0, floor(image ? photoW.constant - 21 : circleMsg ? CircleView.side - 21 : room - 29))
        waveWidth.constant = min(176, max(96, wrap - 40 - 10 - 56))
        transText.preferredMaxLayoutWidth = wrap
        [fwd, quote, text, pollQuestion, pollInfo, meta].forEach { $0.preferredMaxLayoutWidth = wrap }
        [fileName, fileMeta].forEach { $0.preferredMaxLayoutWidth = max(0, wrap - 42) }
        sender.preferredMaxLayoutWidth = max(0, floor(room))
        let strong: UIColor = out ? .white : Theme.accent
        voiceRow.isHidden = !voiceMsg
        circle.isHidden = !circleMsg
        if voiceMsg {
            playButton.backgroundColor = out ? .white : Theme.action
            playButton.tintColor = out ? Theme.outgoing : .white
            wave.played = strong
            wave.rest = muted
            wave.bars = Array(Data(base64Encoded: m.obj("file")?.str("wave") ?? "") ?? Data())
            voiceTime.textColor = muted
            speedButton.setTitleColor(strong, for: .normal)
            speedButton.backgroundColor = strong.withAlphaComponent(0.2)
        }
        if circleMsg {
            circle.ringColor = strong
            circle.configure(id: boundId, poster: Photos.thumb(m))
        }
        transText.textColor = fg
        transButton.tintColor = strong
        if let f, !image, !voiceMsg, !circleMsg {
            fileRow.isHidden = false
            fileName.text = f.str("name")
            fileMeta.text = sizeText(f.int64("size"))
        } else { fileRow.isHidden = true }
        poll.isHidden = kind != "poll"
        if kind == "poll" { bindPoll(m, fg: fg, muted: muted) }
        let undecryptable = kind == "undecryptable"
        let body = undecryptable ? "Сообщение не удалось расшифровать" : m.str("text")
        if undecryptable {
            text.attributedText = nil
            text.font = Theme.font(Prefs.textSize)
            text.text = body
            text.textColor = Theme.warning
        } else if big {
            text.attributedText = nil
            text.font = .systemFont(ofSize: [52, 42, 34][min(emojiCount, 3) - 1])
            text.text = body
            text.textColor = fg
        } else if kind == "call", let c = m.obj("call") {
            // Call log entry: phone or camera icon, missed calls in the warning color.
            let missed = c.str("status") == "missed" && !out
            let color: UIColor = missed ? Theme.warning : fg
            let font = Theme.font(Prefs.textSize, .semibold)
            let icon = NSTextAttachment()
            icon.image = UIImage(systemName: c.bool("video") ? "video.fill" : "phone.fill", withConfiguration: UIImage.SymbolConfiguration(font: font))?
                .withTintColor(missed ? Theme.warning : out ? fg : Theme.accent, renderingMode: .alwaysOriginal)
            let a = NSMutableAttributedString(attachment: icon)
            a.append(NSAttributedString(string: "  " + body, attributes: [.font: font, .foregroundColor: color]))
            text.attributedText = a
        } else {
            text.attributedText = richText(m, size: Prefs.textSize, color: fg, outgoing: out && !big)
        }
        bindReactions(m.arr("reactions"), out: out && !big, fg: fg)
        text.isHidden = body.isEmpty || kind == "poll"
        bindEmojiRow(big && Prefs.animatedEmoji ? AnimatedEmoji.keys(for: body) : nil)
        let st = m.str("status")
        meta.text = (m.bool("edited") ? "изменено " : "") + hhmm(m.int64("sent_at"))
        delivery.isHidden = !out
        // The mark hops when this message moves on: sent, delivered, read.
        if out && statusId == boundId && !statusSig.isEmpty && statusSig != st && st != "queued" { delivery.pop(from: 0.4, response: 0.36, damping: 0.42) }
        statusId = boundId
        statusSig = st
        delivery.status = st
        delivery.color = st == "failed" ? Theme.warning : (big ? Theme.muted : (st == "read" ? .white : muted))
        delivery.alpha = st == "delivered" ? 0.75 : 1
        retry.isHidden = !(out && st == "failed")
        // Poll answers stay separate buttons for VoiceOver.
        isAccessibilityElement = kind != "poll"
        var a = m.str("sender_name") + ", " + hhmm(m.int64("sent_at")) + ": "
        if image { a += "фото. " }
        else if voiceMsg { a += "голосовое сообщение, " + Videos.clock(m.obj("file")?.int64("duration") ?? 0) + ". " }
        else if circleMsg { a += "видеосообщение, " + Videos.clock(m.obj("file")?.int64("duration") ?? 0) + ". " }
        else if let f { a += "файл " + f.str("name") + ". " }
        if let t = transcriptOf(m), !t.isEmpty { a += "Текст: " + t + ". " }
        a += body
        if out, !statusText(st).isEmpty { a += ". " + statusText(st) }
        for r in m.arr("reactions") { a += ", " + r.str("emoji") + " \(r.int("count"))" }
        accessibilityLabel = a
        accessibilityHint = "Коснитесь дважды, чтобы открыть реакции и действия"
        bindPlayback()
    }

    /// Where every emoji has an animation, they play in place of the text.
    private func bindEmojiRow(_ keys: [String]?) {
        emojiRow.arrangedSubviews.forEach { $0.removeFromSuperview() }
        let sheets = keys?.compactMap { AnimatedEmoji.sheet($0) } ?? []
        emojiRow.isHidden = sheets.isEmpty || sheets.count != keys?.count
        if emojiRow.isHidden { return }
        text.isHidden = true
        let side = AnimatedEmoji.side(sheets.count)
        for s in sheets {
            let v = AnimatedEmojiView()
            v.show(s)
            v.widthAnchor.constraint(equalToConstant: side).isActive = true
            v.heightAnchor.constraint(equalToConstant: side).isActive = true
            emojiRow.addArrangedSubview(v)
        }
    }

    private func bindReactions(_ list: [JSON], out: Bool, fg: UIColor) {
        reactRow.arrangedSubviews.dropLast().forEach { $0.removeFromSuperview() }
        reactRow.isHidden = list.isEmpty
        // Chips pop in when the reactions of the bound message changed.
        let sig = list.map { $0.str("emoji") + String($0.int("count")) }.joined(separator: " ")
        let pop = lastReactsId == boundId && lastReactsSig != nil && lastReactsSig != sig
        lastReactsId = boundId
        lastReactsSig = sig
        for (i, r) in list.prefix(8).enumerated() {
            let mine = r.bool("mine")
            let e = r.str("emoji")
            let b = UIButton(type: .system)
            b.setTitle("\(e) \(r.int("count"))", for: .normal)
            b.titleLabel?.font = Theme.font(14, .semibold)
            b.setTitleColor(fg, for: .normal)
            b.backgroundColor = mine ? (out ? UIColor.white.withAlphaComponent(0.32) : Theme.action.withAlphaComponent(0.28))
                : (out ? UIColor.white.withAlphaComponent(0.14) : Theme.muted.withAlphaComponent(0.16))
            b.layer.cornerRadius = 14
            b.layer.borderWidth = mine ? 1 : 0
            b.layer.borderColor = (out ? UIColor.white : Theme.accent).resolvedColor(with: traitCollection).cgColor
            b.contentEdgeInsets = UIEdgeInsets(top: 3, left: 9, bottom: 3, right: 9)
            b.heightAnchor.constraint(equalToConstant: 28).isActive = true
            let names = r.str("names")
            b.accessibilityLabel = "Реакция \(e), \(r.int("count"))" + (mine ? ", ваша" : "") + (names.isEmpty ? "" : ", " + names)
            b.addAction(UIAction { [weak self] _ in self?.onReact?(e) }, for: .touchUpInside)
            if pop && !Theme.reducedMotion {
                b.alpha = 0
                UIView.animate(withDuration: 0.12) { b.alpha = 1 }
                b.pop(from: 0.3, response: 0.42, damping: 0.45)
            }
            reactRow.insertArrangedSubview(b, at: i)
        }
    }

    private func bindPoll(_ m: JSON, fg: UIColor, muted: UIColor) {
        let p = m.obj("poll") ?? [:]
        let my = (p["my_choices"] as? [NSNumber] ?? []).map { $0.intValue }
        let closed = p.bool("closed")
        let multiple = p.bool("multiple")
        let total = p.int("voters")
        let results = closed || !my.isEmpty
        pollQuestion.text = m.str("text")
        pollQuestion.textColor = fg
        pollInfo.textColor = muted
        var info = closed ? "Опрос завершён" : ((p["anonymous"] as? Bool ?? true) ? "Анонимный опрос" : "Открытый опрос")
        if multiple { info += " · несколько ответов" }
        pollInfo.text = info + " · " + votesText(total)
        let strong: UIColor = m.bool("outgoing") ? .white : Theme.accent
        pollOptions.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for (i, o) in p.arr("options").enumerated() {
            let votes = o.int("votes")
            let pct = total > 0 ? Int((100 * Double(votes) / Double(total)).rounded()) : 0
            let row = PollRow(text: o.str("text"), pct: pct, fraction: total > 0 ? CGFloat(votes) / CGFloat(total) : 0, chosen: my.contains(i),
                              results: results, multiple: multiple, fg: fg, muted: muted, strong: strong)
            let voters = o["voters"] as? [String] ?? []
            row.accessibilityLabel = o.str("text") + (results ? ", \(pct)%" : "") + (my.contains(i) ? ", ваш выбор" : "") + (voters.isEmpty ? "" : ", " + voters.joined(separator: ", "))
            if closed { row.isEnabled = false } else { row.onTap = { [weak self] in self?.onVote?(i) } }
            pollOptions.addArrangedSubview(row)
        }
    }
}

/// Horizontal drags in either direction reply; rightward drags from the screen edge
/// remain available to navigation, and vertical drags remain table scrolling.
private final class ReplySwipeDelegate: NSObject, UIGestureRecognizerDelegate {
    weak var cell: BubbleCell?

    func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
        guard let p = g as? UIPanGestureRecognizer, cell?.onSwipeReply != nil else { return false }
        let v = p.velocity(in: p.view)
        guard abs(v.x) > abs(v.y) * 1.5 else { return false }
        let startX = p.location(in: p.view?.window).x - p.translation(in: p.view?.window).x
        return v.x < 0 || startX > 32
    }
}

/// Keep our recognizer separate from UITableViewCell's internal gesture delegate.
/// Photos, poll answers and reaction chips handle their own taps.
private final class BubbleTapDelegate: NSObject, UIGestureRecognizerDelegate {
    weak var photo: UIView?
    weak var circle: UIView?
    weak var wave: UIView?
    weak var avatar: UIView?
    weak var sender: UIView?

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        var v = touch.view
        while let x = v, x !== g.view {
            if x is UIControl || x === photo || x === circle || x === wave || x === avatar || x === sender { return false }
            v = x.superview
        }
        return true
    }
}

/// One poll answer: a mark or the share of votes, the text and a result bar.
final class PollRow: UIControl {
    var onTap: (() -> Void)?

    init(text: String, pct: Int, fraction: CGFloat, chosen: Bool, results: Bool, multiple: Bool, fg: UIColor, muted: UIColor, strong: UIColor) {
        super.init(frame: .zero)
        let title = label(text, 16, chosen ? .semibold : .regular, color: fg)
        let leadView: UIView
        if results {
            let p = label("\(pct)%", 14, .bold, color: chosen ? strong : muted)
            p.widthAnchor.constraint(equalToConstant: 46).isActive = true
            leadView = p
        } else {
            let mark = UIView()
            mark.layer.borderWidth = 2
            mark.layer.borderColor = muted.cgColor
            mark.layer.cornerRadius = multiple ? 5 : 9
            mark.widthAnchor.constraint(equalToConstant: 18).isActive = true
            mark.heightAnchor.constraint(equalToConstant: 18).isActive = true
            leadView = mark
        }
        let line = UIStackView(arrangedSubviews: [leadView, title])
        line.spacing = results ? 0 : 12
        line.alignment = .center
        let col = UIStackView(arrangedSubviews: [line])
        col.axis = .vertical
        col.spacing = 5
        if results {
            let track = UIView()
            track.backgroundColor = muted.withAlphaComponent(0.25)
            track.layer.cornerRadius = 2
            track.heightAnchor.constraint(equalToConstant: 4).isActive = true
            if fraction > 0 {
                let fill = UIView()
                fill.backgroundColor = strong
                fill.layer.cornerRadius = 2
                fill.translatesAutoresizingMaskIntoConstraints = false
                track.addSubview(fill)
                NSLayoutConstraint.activate([
                    fill.leadingAnchor.constraint(equalTo: track.leadingAnchor), fill.topAnchor.constraint(equalTo: track.topAnchor),
                    fill.bottomAnchor.constraint(equalTo: track.bottomAnchor), fill.widthAnchor.constraint(equalTo: track.widthAnchor, multiplier: min(1, fraction)),
                ])
            }
            let spacer = UIView()
            spacer.widthAnchor.constraint(equalToConstant: 46).isActive = true
            col.addArrangedSubview(UIStackView(arrangedSubviews: [spacer, track]))
        }
        col.isUserInteractionEnabled = false
        col.translatesAutoresizingMaskIntoConstraints = false
        addSubview(col)
        NSLayoutConstraint.activate([
            col.topAnchor.constraint(equalTo: topAnchor, constant: 8), col.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -8),
            col.leadingAnchor.constraint(equalTo: leadingAnchor), col.trailingAnchor.constraint(equalTo: trailingAnchor),
            heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
        ])
        isAccessibilityElement = true
        accessibilityTraits = .button
        addAction(UIAction { [weak self] _ in self?.onTap?() }, for: .touchUpInside)
    }

    required init?(coder: NSCoder) { fatalError() }

    override var isHighlighted: Bool {
        didSet { alpha = isHighlighted ? 0.6 : 1 }
    }
}

/// One picture of the gallery: pinch to zoom, tap closes. The thumbnail shows until the original is decrypted.
final class PhotoViewerController: UIViewController, UIScrollViewDelegate {
    let message: JSON
    let index: Int
    var onClose: (() -> Void)?
    private let scroll = UIScrollView()
    private let imageView = UIImageView()

    init(message: JSON, index: Int) {
        self.message = message
        self.index = index
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        scroll.frame = view.bounds
        scroll.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        scroll.delegate = self
        scroll.minimumZoomScale = 1
        scroll.maximumZoomScale = 4
        scroll.showsVerticalScrollIndicator = false
        scroll.showsHorizontalScrollIndicator = false
        imageView.image = Photos.cache.object(forKey: message.str("id") as NSString) ?? Photos.thumb(message)
        imageView.contentMode = .scaleAspectFit
        imageView.frame = scroll.bounds
        imageView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        imageView.isAccessibilityElement = true
        imageView.accessibilityLabel = "Фото"
        imageView.accessibilityHint = "Коснитесь, чтобы закрыть"
        scroll.addSubview(imageView)
        view.addSubview(scroll)
        view.addGestureRecognizer(TapGesture { [weak self] in self?.onClose?() })
        let profile = !message.str("profile_user_id").isEmpty
        let method = profile ? "profile_photo" : "download_file"
        let params: JSON = profile ? ["user_id":message.str("profile_user_id"),"photo_id":message.str("id")] : ["message_id":message.str("id")]
        Core.shared.call(method, params) { [weak self] r in
            guard case .success(let v) = r, let path = (v as? JSON)?.str("path") else { return }
            DispatchQueue.global(qos: .userInitiated).async {
                let img = Photos.decode(URL(fileURLWithPath: path), maxSide: 2732)
                DispatchQueue.main.async { if let img { self?.imageView.image = img } }
            }
        }
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }
}

/// Full-screen photos of a chat in one pager: swipe left and right, cross-fades unless motion is reduced.
final class PhotoGalleryController: UIPageViewController, UIPageViewControllerDataSource, UIPageViewControllerDelegate {
    var edit: ((String) -> Void)?
    private let photos: [JSON]
    private var current: Int
    private let counter = UILabel()

    init(photos: [JSON], start: Int) {
        self.photos = photos
        self.current = start
        super.init(transitionStyle: .scroll, navigationOrientation: .horizontal, options: [.interPageSpacing: 16])
        modalPresentationStyle = .overFullScreen
        modalTransitionStyle = .crossDissolve
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.016, green: 0.035, blue: 0.08, alpha: 0.96)
        dataSource = self
        delegate = self
        setViewControllers([page(current)], direction: .forward, animated: false)
        counter.font = .systemFont(ofSize: 14, weight: .semibold)
        counter.textColor = .white
        counter.textAlignment = .center
        counter.backgroundColor = UIColor(white: 0, alpha: 0.4)
        counter.layer.cornerRadius = 15
        counter.layer.masksToBounds = true
        counter.translatesAutoresizingMaskIntoConstraints = false
        counter.isHidden = photos.count < 2
        view.addSubview(counter)
        NSLayoutConstraint.activate([
            counter.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            counter.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            counter.heightAnchor.constraint(equalToConstant: 30),
            counter.widthAnchor.constraint(greaterThanOrEqualToConstant: 64),
        ])
        view.accessibilityViewIsModal = true
        let back = UIButton(type:.system)
        back.setImage(UIImage(systemName:"chevron.left"),for:.normal)
        back.tintColor = .white; back.accessibilityLabel = "Назад"
        back.addAction(UIAction { [weak self] _ in self?.dismiss(animated:!Theme.reducedMotion) },for:.touchUpInside)
        back.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(back)
        NSLayoutConstraint.activate([back.leadingAnchor.constraint(equalTo:view.safeAreaLayoutGuide.leadingAnchor,constant:12),back.topAnchor.constraint(equalTo:view.safeAreaLayoutGuide.topAnchor),back.widthAnchor.constraint(equalToConstant:44),back.heightAnchor.constraint(equalToConstant:44)])
        if edit != nil {
            let pencil = UIButton(type:.system); pencil.setImage(UIImage(systemName:"pencil"),for:.normal); pencil.tintColor = .white; pencil.accessibilityLabel = "Изменить фото профиля"
            pencil.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(pencil)
            pencil.addAction(UIAction { [weak self] _ in guard let self else { return }; self.edit?(self.photos[self.current].str("id")) },for:.touchUpInside)
            NSLayoutConstraint.activate([pencil.trailingAnchor.constraint(equalTo:view.safeAreaLayoutGuide.trailingAnchor,constant:-12),pencil.topAnchor.constraint(equalTo:view.safeAreaLayoutGuide.topAnchor),pencil.widthAnchor.constraint(equalToConstant:44),pencil.heightAnchor.constraint(equalToConstant:44)])
        }
        updateCounter()
    }

    private func page(_ i: Int) -> PhotoViewerController {
        let p = PhotoViewerController(message: photos[i], index: i)
        p.onClose = { [weak self] in self?.dismiss(animated: !Theme.reducedMotion) }
        return p
    }

    private func updateCounter() {
        counter.text = "\(current + 1) из \(photos.count)"
        counter.accessibilityLabel = "Фото \(current + 1) из \(photos.count)"
    }

    func pageViewController(_ pvc: UIPageViewController, viewControllerBefore vc: UIViewController) -> UIViewController? {
        guard let i = (vc as? PhotoViewerController)?.index, i > 0 else { return nil }
        return page(i - 1)
    }

    func pageViewController(_ pvc: UIPageViewController, viewControllerAfter vc: UIViewController) -> UIViewController? {
        guard let i = (vc as? PhotoViewerController)?.index, i + 1 < photos.count else { return nil }
        return page(i + 1)
    }

    func pageViewController(_ pvc: UIPageViewController, didFinishAnimating finished: Bool, previousViewControllers: [UIViewController], transitionCompleted completed: Bool) {
        guard completed, let i = (viewControllers?.first as? PhotoViewerController)?.index else { return }
        current = i
        updateCounter()
    }

    override func accessibilityPerformEscape() -> Bool {
        dismiss(animated: !Theme.reducedMotion)
        return true
    }
}
