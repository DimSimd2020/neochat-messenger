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

private func sizeText(_ b: Int64) -> String {
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

/// Links found by the core's markdown parser.
func links(_ m: JSON) -> [String] { m.arr("spans").compactMap { $0["link"] as? String } }

/// Telegram-like formatting from the core's markdown spans.
func richText(_ m: JSON, size: CGFloat, color: UIColor) -> NSAttributedString {
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
}

/// One conversation: history, composer and header with details.
final class ChatViewController: UIViewController, UITableViewDataSource, UITableViewDelegate, UITextViewDelegate, UIDocumentPickerDelegate, QLPreviewControllerDataSource,
    PHPickerViewControllerDelegate, UIPopoverPresentationControllerDelegate {
    let conversationId: String
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
    private let composer = UIView()
    private let attach = UIButton(type: .system)
    private let input = UITextView()
    private let placeholder = label("Написать сообщение…", 17, color: Theme.muted)
    private let send = UIButton(type: .system)
    private var sendWidth: NSLayoutConstraint!
    private var inputHeight: NSLayoutConstraint!
    private let replyBar = UIStackView()
    private let replyTitle = label("", 13, .semibold, color: Theme.accent)
    private let replyText = label("", 14, color: Theme.muted)
    private let titleLabel = label("", 17, .semibold)
    private let subLabel = label("", 12, color: Theme.muted)
    private let titleBox = UIStackView()
    private var baseSub = ""
    private var peerOnline = false
    private var presenceTimer: Timer?
    private var replyTo: JSON?
    private var blocked = false
    private let goneLabel = label("", 15, color: Theme.muted)
    private var previewURL: URL?
    private var reloadPending = false
    private let jump = UIButton(type: .system)
    private var jumpShown = false
    // Measured row heights: estimates made the table jump while sending and left the first screen half drawn.
    private var heights: [String: CGFloat] = [:]
    private var pendingBottom = false

    init(conversationId: String) {
        self.conversationId = conversationId
        super.init(nibName: nil, bundle: nil)
        hidesBottomBarWhenPushed = true
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.canvas
        view.insertSubview(BackgroundView(frame: view.bounds), at: 0)
        navigationItem.largeTitleDisplayMode = .never

        table.backgroundColor = .clear
        table.separatorStyle = .none
        table.dataSource = self
        table.delegate = self
        table.keyboardDismissMode = .interactive
        table.register(BubbleCell.self, forCellReuseIdentifier: "b")
        table.register(DateCell.self, forCellReuseIdentifier: "d")
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
            self.presentSheet(ConversationInfoViewController(conversationId: self.conversationId))
        }, for: .touchUpInside)
        notice.addArrangedSubview(noticeText)
        notice.addArrangedSubview(verify)
        notice.isHidden = true

        buildComposer()
        goneLabel.textAlignment = .center
        goneLabel.isHidden = true

        titleLabel.numberOfLines = 1
        subLabel.numberOfLines = 1
        titleBox.addArrangedSubview(titleLabel)
        titleBox.addArrangedSubview(subLabel)
        titleBox.axis = .vertical
        titleBox.alignment = .center
        titleBox.isAccessibilityElement = true
        titleBox.accessibilityTraits = .header
        // Glass capsule header over a clear bar, like the Android chat header.
        let cap = UIView()
        cap.glass(21)
        titleBox.translatesAutoresizingMaskIntoConstraints = false
        cap.addSubview(titleBox)
        NSLayoutConstraint.activate([
            titleBox.topAnchor.constraint(equalTo: cap.topAnchor, constant: 4),
            titleBox.bottomAnchor.constraint(equalTo: cap.bottomAnchor, constant: -4),
            titleBox.leadingAnchor.constraint(equalTo: cap.leadingAnchor, constant: 18),
            titleBox.trailingAnchor.constraint(equalTo: cap.trailingAnchor, constant: -18),
            cap.widthAnchor.constraint(lessThanOrEqualToConstant: 260),
        ])
        navigationItem.titleView = cap
        let clear = UINavigationBarAppearance()
        clear.configureWithTransparentBackground()
        navigationItem.standardAppearance = clear
        navigationItem.scrollEdgeAppearance = clear
        let info = UIButton(type: .system)
        info.glass(20)
        info.setImage(UIImage(systemName: "info", withConfiguration: UIImage.SymbolConfiguration(pointSize: 16, weight: .semibold)), for: .normal)
        info.tintColor = Theme.accent
        info.accessibilityLabel = "Сведения"
        info.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([info.widthAnchor.constraint(equalToConstant: 40), info.heightAnchor.constraint(equalToConstant: 40)])
        info.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            self.presentSheet(ConversationInfoViewController(conversationId: self.conversationId))
        }, for: .touchUpInside)
        navigationItem.rightBarButtonItem = UIBarButtonItem(customView: info)
        navigationItem.rightBarButtonItems = [navigationItem.rightBarButtonItem!, UIBarButtonItem(image: UIImage(systemName: "ellipsis"), menu: UIMenu(children: [
            UIAction(title: "Очистить чат…", image: UIImage(systemName: "trash.slash"), attributes: .destructive) { [weak self] _ in
                guard let self else { return }
                confirmClearConversation(self) { [weak self] in
                    guard let self else { return }
                    Core.shared.call("clear_conversation", ["conversation_id": self.conversationId]) { [weak self] r in
                        if case .failure(let e) = r { self?.toast(e.message) }
                    }
                }
            },
        ]))]
        navigationItem.rightBarButtonItems?.last?.accessibilityLabel = "Действия чата"

        let stack = UIStackView(arrangedSubviews: [notice, table, composer, goneLabel])
        stack.axis = .vertical
        stack.spacing = 6
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 4),
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 10),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -10),
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
            case "presence" where ev.str("user_id") == self.conv.str("peer_user_id"): self.refreshConversation(first: false)
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
    }

    private func showJump(_ on: Bool) {
        guard on != jumpShown else { return }
        jumpShown = on
        if on { jump.isHidden = false; jump.transform = CGAffineTransform(scaleX: 0.7, y: 0.7) }
        Theme.animate(on ? 0.18 : 0.15, {
            self.jump.alpha = on ? 1 : 0
            self.jump.transform = on ? .identity : CGAffineTransform(scaleX: 0.7, y: 0.7)
        }, done: { [weak self] in
            guard let self, !self.jumpShown else { return }
            self.jump.isHidden = true
        })
    }

    private func key(_ it: Item) -> String {
        switch it {
        case .date(let t): return "d:" + t
        case .msg(let m, _, _): return "m:" + m.str("id")
        }
    }

    func tableView(_ tableView: UITableView, estimatedHeightForRowAt indexPath: IndexPath) -> CGFloat {
        guard indexPath.row < items.count else { return 60 }
        if let h = heights[key(items[indexPath.row])] { return h }
        if case .date = items[indexPath.row] { return 44 }
        return 60
    }

    func tableView(_ tableView: UITableView, willDisplay cell: UITableViewCell, forRowAt indexPath: IndexPath) {
        guard indexPath.row < items.count else { return }
        heights[key(items[indexPath.row])] = cell.frame.height
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        Notifier.visibleConversation = conversationId
        navigationController?.interactivePopGestureRecognizer?.isEnabled = true
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
        Core.shared.call("set_draft", ["conversation_id": conversationId, "text": input.text ?? ""])
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
        updateAttachMenu()
        input.backgroundColor = .clear
        input.font = Theme.font(17)
        input.adjustsFontForContentSizeCategory = true
        input.textColor = Theme.text
        input.delegate = self
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
        for v in [attach, input, send, placeholder] {
            v.translatesAutoresizingMaskIntoConstraints = false
            row.addSubview(v)
        }
        placeholder.isUserInteractionEnabled = false
        sendWidth = send.widthAnchor.constraint(equalToConstant: 0)
        inputHeight = input.heightAnchor.constraint(equalToConstant: 46)
        NSLayoutConstraint.activate([
            attach.leadingAnchor.constraint(equalTo: row.leadingAnchor, constant: 6),
            attach.bottomAnchor.constraint(equalTo: row.bottomAnchor, constant: -1),
            attach.widthAnchor.constraint(equalToConstant: 44), attach.heightAnchor.constraint(equalToConstant: 44),
            input.leadingAnchor.constraint(equalTo: attach.trailingAnchor),
            input.topAnchor.constraint(equalTo: row.topAnchor),
            input.bottomAnchor.constraint(equalTo: row.bottomAnchor),
            inputHeight,
            send.leadingAnchor.constraint(equalTo: input.trailingAnchor, constant: 4),
            send.trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -4),
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
        cancel.addAction(UIAction { [weak self] _ in self?.setReply(nil) }, for: .touchUpInside)
        replyBar.addArrangedSubview(line)
        replyBar.addArrangedSubview(col)
        replyBar.addArrangedSubview(cancel)
        replyBar.isHidden = true
        let box = UIStackView(arrangedSubviews: [replyBar, row])
        box.axis = .vertical
        box.translatesAutoresizingMaskIntoConstraints = false
        composer.addSubview(box)
        NSLayoutConstraint.activate([
            box.topAnchor.constraint(equalTo: composer.topAnchor), box.bottomAnchor.constraint(equalTo: composer.bottomAnchor),
            box.leadingAnchor.constraint(equalTo: composer.leadingAnchor), box.trailingAnchor.constraint(equalTo: composer.trailingAnchor),
        ])
    }

    func textViewDidChange(_ tv: UITextView) {
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

    /// The send button slides in over 240 ms when there is text.
    private func updateSend(animated: Bool) {
        let show = !(input.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let target: CGFloat = show ? 40 : 0
        send.isEnabled = !blocked
        send.alpha = blocked ? 0.5 : 1
        send.accessibilityHint = blocked ? "Отправка приостановлена: ключ собеседника изменился" : nil
        guard sendWidth.constant != target else { return }
        sendWidth.constant = target
        if animated { Theme.animate(0.24) { self.composer.layoutIfNeeded() } }
    }

    private func setReply(_ m: JSON?) {
        replyTo = m
        if let m {
            let t = m.str("kind") == "file" ? "Файл" : m.str("text").replacingOccurrences(of: "\n", with: " ")
            replyTitle.text = "В ответ " + m.str("sender_name")
            replyText.text = t
            input.becomeFirstResponder()
        }
        replyBar.accessibilityLabel = m == nil ? nil : (replyTitle.text ?? "") + ": " + (replyText.text ?? "")
        Theme.animate(0.18) {
            self.replyBar.isHidden = m == nil
            self.view.layoutIfNeeded()
        }
    }

    private func sendText() {
        let text = (input.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !blocked else { return }
        var p: JSON = ["conversation_id": conversationId, "text": text]
        if let r = replyTo { p["reply_to"] = r.str("id") }
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

    private func updateAttachMenu() {
        var acts: [UIMenuElement] = [
            UIAction(title: "Фото", image: UIImage(systemName: "photo")) { [weak self] _ in self?.pickPhotos() },
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

    private func pickPhotos() {
        var cfg = PHPickerConfiguration()
        cfg.filter = .images
        cfg.selectionLimit = 10
        let picker = PHPickerViewController(configuration: cfg)
        picker.delegate = self
        present(picker, animated: true)
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        for r in results {
            let prov = r.itemProvider
            guard prov.hasItemConformingToTypeIdentifier(UTType.image.identifier) else { continue }
            prov.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { [weak self] url, _ in
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
                let thumb = Photos.makeThumb(dest)
                DispatchQueue.main.async { self?.sendPhoto(dest, name: name, mime: mime, thumb: thumb) }
            }
        }
    }

    private func sendPhoto(_ url: URL, name: String, mime: String, thumb: (String, Int, Int)?) {
        var p: JSON = ["conversation_id": conversationId, "path": url.path, "mime": mime, "name": name]
        if let t = thumb {
            p["thumb"] = t.0
            p["width"] = t.1
            p["height"] = t.2
        }
        Core.shared.call("send_file", p) { [weak self] r in
            try? FileManager.default.removeItem(at: url)
            if case .failure(let e) = r { self?.toast(e.message) }
        }
    }

    private func pickFile() {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        picker.allowsMultipleSelection = true
        picker.delegate = self
        present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        if controller.documentPickerMode == .exportToService { toast("Файл сохранён"); return }
        for u in urls {
            let size = (try? u.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            if size > 25 * 1024 * 1024 { toast("Файл больше 25 МБ"); continue }
            let mime = UTType(filenameExtension: u.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
            Core.shared.call("send_file", ["conversation_id": conversationId, "path": u.path, "mime": mime, "name": u.lastPathComponent]) { [weak self] r in
                try? FileManager.default.removeItem(at: u)
                if case .failure(let e) = r { self?.toast(e.message) }
            }
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
            let members = c.arr("members")
            let channel = c.str("kind") == "channel"
            let system = c.str("kind") == "system"
            let handle = members.first { !$0.bool("me") }.map { "@" + $0.str("username") } ?? ""
            let seen = c.obj("presence")?.str("text") ?? ""
            self.peerOnline = c.obj("presence")?.bool("online") ?? false
            self.baseSub = system ? "Системные сообщения" : self.group ? memberCount(c.int("member_count")) : channel ? memberCount(c.int("member_count"), channel: true)
                : seen.isEmpty ? handle : seen
            self.titleLabel.text = c.str("title")
            self.updateSubtitle()
            if first { self.refreshPresence() }
            let gone = c.bool("left") || c.bool("archived") || system
            let mute = gone || !(c["can_post"] as? Bool ?? true)
            if mute { self.input.resignFirstResponder() }
            self.composer.isHidden = mute
            self.goneLabel.isHidden = !mute
            self.goneLabel.text = system ? "Сюда приходят коды входа и сообщения о новых устройствах"
                : c.bool("archived") ? (channel ? "Канал в архиве. Новые публикации недоступны" : "Группа в архиве. Новые сообщения недоступны")
                : c.bool("left") ? (channel ? "Вы вышли из канала" : "Вы вышли из этой группы")
                : channel ? "Публикуют только владелец и админы канала" : "Писать здесь могут только владелец и админы"
            self.updateAttachMenu()
            let keyChanged = !(c["key_changed_users"] as? [Any] ?? []).isEmpty
            self.blocked = keyChanged || !(c["can_send"] as? Bool ?? true)
            self.updateSend(animated: false)
            let n = c["notice"] as? String
            self.noticeText.text = n
            self.notice.arrangedSubviews.last?.isHidden = !keyChanged
            if self.notice.isHidden != (n?.isEmpty ?? true) { Theme.animate(0.18) { self.notice.isHidden = n?.isEmpty ?? true } }
            if first, !c.str("draft").isEmpty {
                self.input.text = c.str("draft")
                self.textViewDidChange(self.input)
            }
        }
    }

    /// "печатает…" wins over presence and the member count while it lasts.
    private func updateSubtitle() {
        let typing = Typing.text(conversationId, group: conv.str("kind") != "direct")
        subLabel.text = typing ?? baseSub
        subLabel.textColor = typing != nil || peerOnline ? Theme.accent : Theme.muted
        titleBox.accessibilityLabel = (titleLabel.text ?? "") + ", " + (subLabel.text ?? "")
    }

    private func refreshPresence() {
        let peer = conv.str("peer_user_id")
        guard conv.str("kind") == "direct", !peer.isEmpty else { return }
        Core.shared.call("presence", ["user_ids": [peer], "tz_offset": tzOffsetMinutes]) { [weak self] r in
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
            case .failure(let e): self.toast(e.message)
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
            let same = { (o: JSON?) -> Bool in
                guard let o else { return false }
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
        case .msg(let m, let first, let last):
            let c = tableView.dequeueReusableCell(withIdentifier: "b", for: indexPath) as! BubbleCell
            c.bind(m, group: group, first: first, last: last)
            c.onRetry = { [weak self] in self?.retry(m) }
            c.onPhoto = { [weak self] in self?.openPhoto(m) }
            c.onVote = { [weak self] i in self?.vote(m, i) }
            c.onTap = { [weak self, weak c] in
                guard let self, let c else { return }
                self.showMenu(m, from: c.bubbleView)
            }
            c.onReact = { [weak self] e in self?.react(m, e) }
            if animateIds.remove(m.str("id")) != nil, !Theme.reducedMotion {
                c.contentView.alpha = 0
                c.contentView.transform = CGAffineTransform(translationX: 0, y: 12)
                Theme.animate(0.22) { c.contentView.alpha = 1; c.contentView.transform = .identity }
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

    private func openPhoto(_ m: JSON) {
        Core.shared.call("download_file", ["message_id": m.str("id")]) { [weak self] r in
            switch r {
            case .failure(let e): self?.toast(e.message)
            case .success(let v):
                let url = URL(fileURLWithPath: (v as? JSON)?.str("path") ?? "")
                DispatchQueue.global(qos: .userInitiated).async {
                    let img = Photos.decode(url, maxSide: 2732)
                    DispatchQueue.main.async {
                        guard let self else { return }
                        guard let img else { return self.toast("Не удалось открыть фото") }
                        self.present(PhotoViewerController(image: img), animated: !Theme.reducedMotion)
                    }
                }
            }
        }
    }

    private func retry(_ m: JSON) {
        Core.shared.call("retry_message", ["message_id": m.str("id")]) { [weak self] r in if case .failure(let e) = r { self?.toast(e.message) } }
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

final class BubbleCell: UITableViewCell {
    private let avatar = AvatarView(size: 30)
    private let reactRow = UIStackView()
    private let bubbleTap = UITapGestureRecognizer()
    private let bubbleTapDelegate = BubbleTapDelegate()
    private let sender = label("", 13, .semibold, color: Theme.muted)
    private let bubble = UIView()
    private let content = UIStackView()
    private let photo = UIImageView()
    private var photoW: NSLayoutConstraint!
    private var photoH: NSLayoutConstraint!
    private let inner = UIStackView()
    private let fwd = label("", 13, .semibold)
    private let quote = label("", 13)
    private let fileRow = UIStackView()
    private let fileName = label("", 15, .semibold)
    private let fileMeta = label("", 13)
    private let text = label("", 17)
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
    private var lastReactsId = ""
    private var lastReactsSig: String?
    var onRetry: (() -> Void)?
    var onPhoto: (() -> Void)?
    var onVote: ((Int) -> Void)?
    var onTap: (() -> Void)?
    var onReact: ((String) -> Void)?
    var bubbleView: UIView { bubble }

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        selectionStyle = .none
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
        inner.axis = .vertical
        inner.spacing = 4
        reactRow.spacing = 6
        reactRow.alignment = .center
        let spacer = UIView()
        spacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        reactRow.addArrangedSubview(spacer)
        [fwd, quote, fileRow, text, poll, reactRow, metaRow].forEach(inner.addArrangedSubview)
        inner.setCustomSpacing(6, after: reactRow)
        bubbleTap.addTarget(self, action: #selector(tapped))
        bubbleTapDelegate.photo = photo
        bubbleTap.delegate = bubbleTapDelegate
        contentView.addGestureRecognizer(bubbleTap)
        bubble.isUserInteractionEnabled = true
        inner.isLayoutMarginsRelativeArrangement = true
        content.axis = .vertical
        content.spacing = 4
        content.addArrangedSubview(photo)
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
    }

    required init?(coder: NSCoder) { fatalError() }

    @objc private func tapped() { onTap?() }

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
            quote.text = r.str("sender_name") + ": " + (r.str("kind") == "file" ? "Файл" : r.str("text").replacingOccurrences(of: "\n", with: " "))
        } else { quote.isHidden = true }
        let f = m.obj("file")
        let image = isImage(m)
        photo.isHidden = !image
        content.layoutMargins = image ? UIEdgeInsets(top: 4, left: 4, bottom: 7, right: 4) : UIEdgeInsets(top: 9, left: 15, bottom: 7, right: 14)
        inner.layoutMargins = image ? UIEdgeInsets(top: 0, left: 11, bottom: 0, right: 10) : .zero
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
            if let img = Photos.cache.object(forKey: id as NSString) {
                photo.image = img
            } else {
                photo.image = Photos.thumb(m)
                if Prefs.autoload { Photos.load(m) { [weak self] img in if self?.boundId == id { self?.photo.image = img } } }
            }
        } else {
            photoW.isActive = false
            photoH.isActive = false
            photo.image = nil
        }
        if let f, !image {
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
        } else {
            text.attributedText = richText(m, size: Prefs.textSize, color: fg)
        }
        bindReactions(m.arr("reactions"), out: out && !big, fg: fg)
        text.isHidden = body.isEmpty || kind == "poll"
        let st = m.str("status")
        meta.text = hhmm(m.int64("sent_at"))
        delivery.isHidden = !out
        delivery.status = st
        delivery.color = st == "failed" ? Theme.warning : (big ? Theme.muted : (st == "read" ? .white : muted))
        delivery.alpha = st == "delivered" ? 0.75 : 1
        retry.isHidden = !(out && st == "failed")
        // Poll answers stay separate buttons for VoiceOver.
        isAccessibilityElement = kind != "poll"
        var a = m.str("sender_name") + ", " + hhmm(m.int64("sent_at")) + ": "
        if image { a += "фото. " } else if let f { a += "файл " + f.str("name") + ". " }
        a += body
        if out, !statusText(st).isEmpty { a += ". " + statusText(st) }
        for r in m.arr("reactions") { a += ", " + r.str("emoji") + " \(r.int("count"))" }
        accessibilityLabel = a
        accessibilityHint = "Коснитесь дважды, чтобы открыть реакции и действия"
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
            if pop {
                b.transform = CGAffineTransform(scaleX: 0.4, y: 0.4)
                b.alpha = 0
                Theme.animate(0.2, { b.transform = .identity; b.alpha = 1 })
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

/// Keep our recognizer separate from UITableViewCell's internal gesture delegate.
/// Photos, poll answers and reaction chips handle their own taps.
private final class BubbleTapDelegate: NSObject, UIGestureRecognizerDelegate {
    weak var photo: UIView?

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        var v = touch.view
        while let x = v, x !== g.view {
            if x is UIControl || x === photo { return false }
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

/// Full-screen photo: pinch to zoom, tap closes; cross-fades unless motion is reduced.
final class PhotoViewerController: UIViewController, UIScrollViewDelegate {
    private let image: UIImage
    private let scroll = UIScrollView()
    private let imageView = UIImageView()

    init(image: UIImage) {
        self.image = image
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .overFullScreen
        modalTransitionStyle = .crossDissolve
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.016, green: 0.035, blue: 0.08, alpha: 0.96)
        scroll.frame = view.bounds
        scroll.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        scroll.delegate = self
        scroll.minimumZoomScale = 1
        scroll.maximumZoomScale = 4
        scroll.showsVerticalScrollIndicator = false
        scroll.showsHorizontalScrollIndicator = false
        imageView.image = image
        imageView.contentMode = .scaleAspectFit
        imageView.frame = scroll.bounds
        imageView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        imageView.isAccessibilityElement = true
        imageView.accessibilityLabel = "Фото"
        imageView.accessibilityHint = "Коснитесь, чтобы закрыть"
        scroll.addSubview(imageView)
        view.addSubview(scroll)
        view.accessibilityViewIsModal = true
        view.addGestureRecognizer(TapGesture { [weak self] in self?.dismiss(animated: !Theme.reducedMotion) })
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

    override func accessibilityPerformEscape() -> Bool {
        dismiss(animated: !Theme.reducedMotion)
        return true
    }
}
