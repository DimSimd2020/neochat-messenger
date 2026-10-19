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
    PHPickerViewControllerDelegate {
    let conversationId: String
    private var conv: JSON = [:]
    private var messages: [JSON] = []
    private var items: [Item] = []
    private var known = Set<String>()
    private var animateIds = Set<String>()
    private var loading = false
    private var hasMore = true
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
    private let replyText = label("", 14, color: Theme.muted)
    private var replyTo: JSON?
    private var blocked = false
    private let goneLabel = label("", 15, color: Theme.muted)
    private var previewURL: URL?
    private var reloadPending = false

    init(conversationId: String) {
        self.conversationId = conversationId
        super.init(nibName: nil, bundle: nil)
        hidesBottomBarWhenPushed = true
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        navigationItem.largeTitleDisplayMode = .never
        navigationItem.rightBarButtonItem = UIBarButtonItem(image: UIImage(systemName: "info.circle"), primaryAction: UIAction { [weak self] _ in
            guard let self else { return }
            self.presentSheet(ConversationInfoViewController(conversationId: self.conversationId))
        })
        navigationItem.rightBarButtonItem?.accessibilityLabel = "Сведения"

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

        let stack = UIStackView(arrangedSubviews: [notice, table, replyBar, composer, goneLabel])
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
            case "messages_changed" where ev.str("conversation_id") == self.conversationId: self.scheduleReload()
            case "sync_reset": self.scheduleReload(); self.refreshConversation(first: false)
            case "conversations_changed", "contacts_changed", "identity_changed", "connection": self.refreshConversation(first: false)
            default: break
            }
        }
        refreshConversation(first: true)
        load(older: false)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        Notifier.visibleConversation = conversationId
        markRead()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if Notifier.visibleConversation == conversationId { Notifier.visibleConversation = nil }
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
            composer.addSubview(v)
        }
        placeholder.isUserInteractionEnabled = false
        sendWidth = send.widthAnchor.constraint(equalToConstant: 0)
        inputHeight = input.heightAnchor.constraint(equalToConstant: 46)
        NSLayoutConstraint.activate([
            attach.leadingAnchor.constraint(equalTo: composer.leadingAnchor, constant: 6),
            attach.bottomAnchor.constraint(equalTo: composer.bottomAnchor, constant: -1),
            attach.widthAnchor.constraint(equalToConstant: 44), attach.heightAnchor.constraint(equalToConstant: 44),
            input.leadingAnchor.constraint(equalTo: attach.trailingAnchor),
            input.topAnchor.constraint(equalTo: composer.topAnchor),
            input.bottomAnchor.constraint(equalTo: composer.bottomAnchor),
            inputHeight,
            send.leadingAnchor.constraint(equalTo: input.trailingAnchor, constant: 4),
            send.trailingAnchor.constraint(equalTo: composer.trailingAnchor, constant: -4),
            send.bottomAnchor.constraint(equalTo: composer.bottomAnchor, constant: -3),
            send.heightAnchor.constraint(equalToConstant: 40), sendWidth,
            placeholder.leadingAnchor.constraint(equalTo: input.leadingAnchor, constant: 9),
            placeholder.centerYAnchor.constraint(equalTo: input.centerYAnchor),
        ])
        replyBar.axis = .horizontal
        replyBar.glass(18)
        replyBar.isLayoutMarginsRelativeArrangement = true
        replyBar.layoutMargins = UIEdgeInsets(top: 6, left: 14, bottom: 6, right: 6)
        replyText.numberOfLines = 1
        let cancel = UIButton(type: .close)
        cancel.accessibilityLabel = "Отменить ответ"
        cancel.addAction(UIAction { [weak self] _ in self?.setReply(nil) }, for: .touchUpInside)
        replyBar.addArrangedSubview(replyText)
        replyBar.addArrangedSubview(cancel)
        replyBar.isHidden = true
    }

    func textViewDidChange(_ tv: UITextView) {
        placeholder.isHidden = !tv.text.isEmpty
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
            replyText.text = "Ответ: \(m.str("sender_name")) — \(t)"
            input.becomeFirstResponder()
        }
        Theme.animate(0.18) { self.replyBar.isHidden = m == nil }
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
            let sub = self.group ? "\(c.int("member_count")) участн." : channel ? "\(c.int("member_count")) подписч."
                : members.first { !$0.bool("me") }.map { "@" + $0.str("username") } ?? ""
            self.navigationItem.titleView = self.titleView(c.str("title"), sub)
            let system = c.str("kind") == "system"
            if system { self.navigationItem.titleView = self.titleView(c.str("title"), "Системные сообщения") }
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

    private func titleView(_ title: String, _ sub: String) -> UIView {
        let t = label(title, 17, .semibold)
        t.numberOfLines = 1
        let s = label(sub, 12, color: Theme.muted)
        s.numberOfLines = 1
        let v = UIStackView(arrangedSubviews: [t, s])
        v.axis = .vertical
        v.alignment = .center
        v.isAccessibilityElement = true
        v.accessibilityLabel = title + ", " + sub
        v.accessibilityTraits = .header
        return v
    }

    private var atBottom: Bool {
        table.contentOffset.y + table.bounds.height >= table.contentSize.height - 40
    }

    private func load(older: Bool) {
        if older && (loading || !hasMore) { return }
        loading = true
        var p: JSON = ["conversation_id": conversationId]
        if older {
            p["before"] = messages.first?.int64("local_id") ?? 0
            p["limit"] = 50
        } else {
            p["limit"] = min(200, max(50, messages.count + 20))
        }
        Core.shared.call("list_messages", p) { [weak self] r in
            guard let self else { return }
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
            let oldest = page.first?.int64("local_id") ?? .max
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
        if wasBottom || own { scrollToBottom(animated: !first) }
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

    private func scrollToBottom(animated: Bool) {
        guard !items.isEmpty else { return }
        table.scrollToRow(at: IndexPath(row: items.count - 1, section: 0), at: .bottom, animated: animated && !Theme.reducedMotion)
    }

    private func markRead() {
        guard view.window != nil, UIApplication.shared.applicationState == .active, atBottom else { return }
        Core.shared.call("mark_read", ["conversation_id": conversationId])
    }

    func scrollViewDidScroll(_ s: UIScrollView) {
        if s.contentOffset.y < 200 && s.isDragging { load(older: true) }
        if atBottom { markRead() }
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
        guard case .msg(let m, _, _) = items[indexPath.row] else { return }
        if isImage(m) { openPhoto(m) } else if m.str("kind") == "file" { openFile(m) } else if links(m).count == 1, let u = URL(string: links(m)[0]) {
            UIApplication.shared.open(u)
        }
    }

    func tableView(_ tableView: UITableView, contextMenuConfigurationForRowAt indexPath: IndexPath, point: CGPoint) -> UIContextMenuConfiguration? {
        guard case .msg(let m, _, _) = items[indexPath.row] else { return nil }
        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in
            guard let self else { return nil }
            var acts: [UIMenuElement] = []
            let ok = m.str("kind") != "undecryptable"
            if ok && self.composer.isHidden == false { acts.append(UIAction(title: "Ответить", image: UIImage(systemName: "arrowshape.turn.up.left")) { _ in self.setReply(m) }) }
            if ok && !m.str("text").isEmpty { acts.append(UIAction(title: "Копировать текст", image: UIImage(systemName: "doc.on.doc")) { _ in UIPasteboard.general.string = m.str("text") }) }
            for l in links(m).prefix(3) {
                if let u = URL(string: l) { acts.append(UIAction(title: "Открыть ссылку: " + (u.host ?? l), image: UIImage(systemName: "safari")) { _ in UIApplication.shared.open(u) }) }
            }
            if isImage(m) { acts.append(UIAction(title: "Открыть фото", image: UIImage(systemName: "photo")) { _ in self.openPhoto(m) }) }
            if m.str("kind") == "poll", let p = m.obj("poll") {
                let who = p.arr("options").compactMap { o -> UIAction? in
                    let v = o["voters"] as? [String] ?? []
                    return v.isEmpty ? nil : UIAction(title: o.str("text") + ": " + v.joined(separator: ", "), attributes: .disabled) { _ in }
                }
                if !who.isEmpty { acts.append(UIMenu(title: "Кто голосовал", image: UIImage(systemName: "person.2"), children: who)) }
                if m.bool("outgoing") && !p.bool("closed") {
                    acts.append(UIAction(title: "Завершить опрос", image: UIImage(systemName: "stop.circle")) { _ in
                        Core.shared.call("close_poll", ["message_id": m.str("id")]) { r in if case .failure(let e) = r { self.toast(e.message) } }
                    })
                }
            }
            if m.str("kind") == "file" {
                acts.append(UIAction(title: "Открыть файл", image: UIImage(systemName: "doc")) { _ in self.openFile(m) })
                acts.append(UIAction(title: "Сохранить как…", image: UIImage(systemName: "square.and.arrow.down")) { _ in self.saveFile(m) })
            }
            if m.str("status") == "failed" { acts.append(UIAction(title: "Повторить отправку", image: UIImage(systemName: "arrow.clockwise")) { _ in self.retry(m) }) }
            acts.append(UIAction(title: "Удалить у себя", image: UIImage(systemName: "trash"), attributes: .destructive) { _ in
                Core.shared.call("delete_local_message", ["message_id": m.str("id")]) { r in if case .failure(let e) = r { self.toast(e.message) } }
            })
            return UIMenu(children: acts)
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
    private let sender = label("", 13, .semibold, color: Theme.muted)
    private let bubble = UIView()
    private let content = UIStackView()
    private let photo = UIImageView()
    private var photoW: NSLayoutConstraint!
    private var photoH: NSLayoutConstraint!
    private let inner = UIStackView()
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
    private let retry = UIButton(type: .system)
    private var lead: NSLayoutConstraint!
    private var trail: NSLayoutConstraint!
    private var topGap: NSLayoutConstraint!
    private var boundId = ""
    var onRetry: (() -> Void)?
    var onPhoto: (() -> Void)?
    var onVote: ((Int) -> Void)?

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
        [quote, fileRow, text, poll, meta].forEach(inner.addArrangedSubview)
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
        avatar.set(m.str("sender_name"), seed: m.str("sender_user_id"))
        sender.isHidden = !(group && !out && first)
        sender.text = m.str("sender_name") + (m.str("sender_tag").isEmpty ? "" : " · " + m.str("sender_tag"))
        bubble.backgroundColor = out ? Theme.outgoing : Theme.incoming
        bubble.layer.borderWidth = out ? 0 : 1
        bubble.layer.borderColor = Theme.rim.resolvedColor(with: traitCollection).cgColor
        let fg: UIColor = out ? .white : Theme.text
        let muted: UIColor = out ? Theme.outgoingMuted : Theme.muted
        text.textColor = fg
        fileName.textColor = fg
        fileMeta.textColor = muted
        quote.textColor = muted
        meta.textColor = muted
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
                Photos.load(m) { [weak self] img in if self?.boundId == id { self?.photo.image = img } }
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
            text.text = body
            text.textColor = Theme.warning
        } else {
            text.attributedText = richText(m, size: 17, color: fg)
        }
        text.isHidden = body.isEmpty || kind == "poll"
        let st = m.str("status")
        let mark = ["queued": " ◷", "sent": " ✓", "delivered": " ✓✓", "read": " ✓✓", "failed": " !"][st] ?? ""
        meta.text = hhmm(m.int64("sent_at")) + (out ? mark : "")
        retry.isHidden = !(out && st == "failed")
        // Poll answers stay separate buttons for VoiceOver.
        isAccessibilityElement = kind != "poll"
        var a = m.str("sender_name") + ", " + hhmm(m.int64("sent_at")) + ": "
        if image { a += "фото. " } else if let f { a += "файл " + f.str("name") + ". " }
        a += body
        if out, !statusText(st).isEmpty { a += ". " + statusText(st) }
        accessibilityLabel = a
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
