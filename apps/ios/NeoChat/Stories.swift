import AVKit
import PhotosUI
import UIKit
import UniformTypeIdentifiers

/// Active stories on the chats screen. Their media never enters the conversation timeline.
final class StoryRailView: UIView {
    weak var host: UIViewController?
    private let scroll = UIScrollView()
    private let contents = UIStackView()
    private var current: [JSON] = []
    private var loading = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        scroll.showsHorizontalScrollIndicator = false
        scroll.translatesAutoresizingMaskIntoConstraints = false
        contents.axis = .horizontal
        contents.spacing = 14
        contents.alignment = .top
        contents.translatesAutoresizingMaskIntoConstraints = false
        addSubview(scroll)
        scroll.addSubview(contents)
        NSLayoutConstraint.activate([
            scroll.leadingAnchor.constraint(equalTo: leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: trailingAnchor),
            scroll.topAnchor.constraint(equalTo: topAnchor),
            scroll.bottomAnchor.constraint(equalTo: bottomAnchor),
            contents.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 16),
            contents.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -16),
            contents.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 4),
            contents.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -4),
            contents.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor, constant: -8),
        ])
        NotificationCenter.default.addObserver(forName: .coreEvent, object: nil, queue: .main) { [weak self] note in
            if (note.userInfo as? JSON)?.str("type") == "stories_changed" { self?.refresh() }
        }
        draw()
    }

    required init?(coder: NSCoder) { fatalError() }

    func refresh() {
        guard !loading, Core.shared.isOpen else { return }
        loading = true
        Core.shared.call("list_stories") { [weak self] result in
            guard let self else { return }
            self.loading = false
            if case .success(let data) = result {
                self.current = data as? [JSON] ?? []
                self.draw()
            }
        }
    }

    private func draw() {
        contents.arrangedSubviews.forEach { $0.removeFromSuperview() }
        contents.addArrangedSubview(tile(name: "Моя история", seed: "self-story", image: "", mine: true) { [weak self] in
            guard let self else { return }
            let mine = self.current.filter { $0.bool("mine") }
            if mine.isEmpty { self.create() } else { self.open(mine) }
        })
        var seen = Set<String>()
        for item in current {
            let id = item.str("author_id")
            guard !item.bool("mine"), !id.isEmpty, seen.insert(id).inserted else { continue }
            let selected = current.filter { $0.str("author_id") == id }
            contents.addArrangedSubview(tile(name: item.str("author"), seed: id, image: item.str("avatar_path"), mine: false) { [weak self] in
                self?.open(selected)
            })
        }
        accessibilityLabel = "Истории"
    }

    private func tile(name: String, seed: String, image: String, mine: Bool, tap: @escaping () -> Void) -> UIControl {
        let control = UIControl()
        control.widthAnchor.constraint(equalToConstant: 76).isActive = true
        control.heightAnchor.constraint(equalToConstant: 88).isActive = true
        control.accessibilityLabel = mine ? "Моя история: открыть или добавить" : "История: " + name
        control.accessibilityTraits = .button
        let avatar = AvatarView(size: 56)
        avatar.set(name, seed: seed, avatar: image)
        avatar.layer.borderWidth = 2
        avatar.layer.borderColor = Theme.accent.cgColor
        avatar.layer.cornerRadius = 28
        avatar.isUserInteractionEnabled = false
        avatar.translatesAutoresizingMaskIntoConstraints = false
        let text = label(name, 11, .medium, color: Theme.text)
        text.textAlignment = .center
        text.lineBreakMode = .byTruncatingTail
        text.translatesAutoresizingMaskIntoConstraints = false
        control.addSubview(avatar)
        control.addSubview(text)
        NSLayoutConstraint.activate([
            avatar.widthAnchor.constraint(equalToConstant: 56),
            avatar.heightAnchor.constraint(equalToConstant: 56),
            avatar.centerXAnchor.constraint(equalTo: control.centerXAnchor),
            avatar.topAnchor.constraint(equalTo: control.topAnchor),
            text.topAnchor.constraint(equalTo: avatar.bottomAnchor, constant: 5),
            text.leadingAnchor.constraint(equalTo: control.leadingAnchor),
            text.trailingAnchor.constraint(equalTo: control.trailingAnchor),
        ])
        if mine {
            let add = UIImageView(image: UIImage(systemName: "plus.circle.fill"))
            add.tintColor = Theme.accent
            add.backgroundColor = Theme.canvas
            add.layer.cornerRadius = 10
            add.translatesAutoresizingMaskIntoConstraints = false
            control.addSubview(add)
            NSLayoutConstraint.activate([
                add.trailingAnchor.constraint(equalTo: avatar.trailingAnchor, constant: 2),
                add.bottomAnchor.constraint(equalTo: avatar.bottomAnchor, constant: 2),
                add.heightAnchor.constraint(equalToConstant: 20),
                add.widthAnchor.constraint(equalToConstant: 20),
            ])
        }
        control.addAction(UIAction { _ in tap() }, for: .touchUpInside)
        return control
    }

    private func open(_ stories: [JSON]) {
        guard !stories.isEmpty, let host else { return }
        let player = StoryViewerViewController(stories: stories)
        player.modalPresentationStyle = .fullScreen
        host.present(player, animated: !Theme.reducedMotion)
    }

    private func create() {
        host?.presentSheet(StoryComposeViewController())
    }
}

/// Creates one encrypted story for selected direct conversations.
final class StoryComposeViewController: SheetViewController, PHPickerViewControllerDelegate {
    private let preview = UIImageView()
    private let caption = Field("Подпись (до 240 символов)")
    private let overlayText = Field("Текст на изображении (до 100 символов)")
    private let recipients = UIStackView()
    private let expires = NavRow("timer", "Срок истории", value: "24 часа") {}
    private let audienceTitle = label("Кто увидит историю", 17, .semibold)
    private let send = PillButton("Опубликовать")
    private var file: OutgoingFile?
    private var choices: [JSON] = []
    private var selected = Set<String>()
    private var hours = 24
    private var textColor = 0xffffff
    private var submitted = false

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Новая история"
        preview.contentMode = .scaleAspectFit
        preview.backgroundColor = Theme.glassStrong
        preview.layer.cornerRadius = 18
        preview.clipsToBounds = true
        preview.heightAnchor.constraint(equalToConstant: 210).isActive = true
        preview.image = UIImage(systemName: "photo.on.rectangle")
        preview.tintColor = Theme.accent
        stack.addArrangedSubview(preview)
        let pick = PillButton("Выбрать фото или видео", primary: false)
        pick.addAction(UIAction { [weak self] _ in self?.pick() }, for: .touchUpInside)
        stack.addArrangedSubview(pick)
        stack.addArrangedSubview(caption)
        stack.addArrangedSubview(overlayText)

        let colors: [(String, Int)] = [("Белый", 0xffffff), ("Чёрный", 0x111827), ("Жёлтый", 0xffdf80)]
        let tint = UISegmentedControl(items: colors.map { $0.0 })
        tint.selectedSegmentIndex = 0
        tint.addAction(UIAction { [weak self] _ in self?.textColor = colors[tint.selectedSegmentIndex].1 }, for: .valueChanged)
        stack.addArrangedSubview(label("Цвет текста", 13, color: Theme.muted))
        stack.addArrangedSubview(tint)
        let timer = NavRow("timer", "Срок показа", value: "24 часа") { [weak self] in self?.chooseTime() }
        stack.addArrangedSubview(timer)
        // The row holds the chosen label without coupling the editor to the alert's lifetime.
        timerRow = timer
        stack.addArrangedSubview(audienceTitle)
        recipients.axis = .vertical
        recipients.spacing = 8
        stack.addArrangedSubview(recipients)
        send.addAction(UIAction { [weak self] _ in self?.publish() }, for: .touchUpInside)
        stack.addArrangedSubview(send)
        muted("Истории видны только выбранным собеседникам в личных чатах и шифруются общим ядром NeoChat.")
        loadRecipients()
    }

    private weak var timerRow: NavRow?

    private func loadRecipients() {
        Core.shared.call("list_conversations") { [weak self] result in
            guard let self, case .success(let value) = result else { return }
            self.choices = (value as? [JSON] ?? []).filter { $0.str("kind") == "direct" && $0["can_media"] as? Bool != false }
            self.selected = Set(self.choices.map { $0.str("id") })
            self.drawRecipients()
        }
    }

    private func drawRecipients() {
        recipients.arrangedSubviews.forEach { $0.removeFromSuperview() }
        if choices.isEmpty {
            recipients.addArrangedSubview(label("Для публикации нужен хотя бы один личный чат.", 14, color: Theme.muted))
        }
        for item in choices {
            let id = item.str("id")
            let chosen = selected.contains(id)
            let row = NavRow(chosen ? "checkmark.circle.fill" : "circle", item.str("title"), chevron: false) { [weak self] in
                guard let self else { return }
                if !self.selected.insert(id).inserted { self.selected.remove(id) }
                self.drawRecipients()
            }
            recipients.addArrangedSubview(row)
        }
    }

    private func chooseTime() {
        let alert = UIAlertController(title: "Срок показа истории", message: nil, preferredStyle: .actionSheet)
        for h in [6, 12, 24, 48] {
            alert.addAction(UIAlertAction(title: "\(h) часов", style: .default) { [weak self] _ in
                self?.hours = h
                self?.timerRow?.value.text = "\(h) ч"
            })
        }
        alert.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        alert.popoverPresentationController?.sourceView = timerRow
        alert.popoverPresentationController?.sourceRect = timerRow?.bounds ?? .zero
        present(alert, animated: !Theme.reducedMotion)
    }

    private func pick() {
        var cfg = PHPickerConfiguration()
        cfg.filter = .any(of: [.images, .videos])
        cfg.selectionLimit = 1
        cfg.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: cfg)
        picker.delegate = self
        present(picker, animated: !Theme.reducedMotion)
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: !Theme.reducedMotion)
        guard let provider = results.first?.itemProvider else { return }
        let video = provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier)
        let type = video ? UTType.movie.identifier : UTType.image.identifier
        provider.loadFileRepresentation(forTypeIdentifier: type) { [weak self] original, _ in
            guard let original else { return }
            let dir = FileManager.default.temporaryDirectory
            let dest = dir.appendingPathComponent(UUID().uuidString + (video ? ".mov" : ".jpg"))
            if video {
                try? FileManager.default.copyItem(at: original, to: dest)
            } else if let picture = Photos.decode(original, maxSide: 3840), let bytes = picture.jpegData(compressionQuality: 0.9) {
                try? bytes.write(to: dest)
            }
            guard FileManager.default.fileExists(atPath: dest.path) else { return }
            DispatchQueue.main.async { [weak self] in
                guard let self else { try? FileManager.default.removeItem(at: dest); return }
                if let old = self.file { try? FileManager.default.removeItem(at: old.url) }
                self.file = OutgoingFile(url: dest, name: dest.lastPathComponent, mime: video ? "video/quicktime" : "image/jpeg")
                if video { self.preview.image = UIImage(systemName: "play.rectangle.fill") }
                else { self.preview.image = Photos.decode(dest, maxSide: 960) }
            }
        }
    }

    private func publish() {
        guard let file else { return toast("Выберите фото или видео") }
        guard !selected.isEmpty else { return toast("Выберите хотя бы одного получателя") }
        guard file.size <= Videos.limit else { return toast("Файл больше 50 МБ") }
        let captionText = String((caption.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).prefix(240))
        let style: JSON = ["text": String((overlayText.text ?? "").prefix(100)), "color": textColor]
        let account = Core.shared.accountId
        let ids = Array(selected)
        let hours = self.hours
        let sendFile: (OutgoingFile, Int?) -> Void = { [weak self] ready, duration in
            let p: JSON = [
                "_account_id": account, "path": ready.url.path, "name": ready.name,
                "mime": ready.mime, "caption": captionText, "hours": hours, "style": style,
                "options": ["screenshots": true, "audience": "selected"],
                "conversation_ids": ids,
                "duration": duration as Any? ?? NSNull(),
            ]
            Core.shared.call("publish_story", p) { [weak self] result in
                self?.send.isEnabled = true
                try? FileManager.default.removeItem(at: ready.url)
                switch result {
                case .failure(let e): self?.toast(e.message)
                case .success:
                    self?.submitted = true
                    self?.dismiss(animated: !Theme.reducedMotion)
                }
            }
        }
        send.isEnabled = false
        if file.isVideo {
            toast("Подготавливаем видео…")
            Videos.prepare(file, progress: { _ in }) { [weak self] ready in
                guard let ready, let duration = ready.info?.duration, (1...60_000).contains(duration) else {
                    self?.send.isEnabled = true
                    self?.toast("Видео истории должно длиться до 60 секунд")
                    return
                }
                sendFile(OutgoingFile(url: ready.url, name: ready.name, mime: ready.mime), duration)
            }
        } else { sendFile(file, nil) }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if (isBeingDismissed || navigationController?.isBeingDismissed == true) && !submitted {
            if let file { try? FileManager.default.removeItem(at: file.url) }
        }
    }
}

/// Fullscreen sequential story viewer with pause, seeking, feedback and owner actions.
final class StoryViewerViewController: UIViewController {
    private let stories: [JSON]
    private var index = 0
    private let photo = UIImageView()
    private let titleLabel = label("", 17, .semibold, color: .white)
    private let caption = label("", 16, color: .white)
    private let overlayText = label("", 25, .semibold, color: .white)
    private let progress = UIProgressView(progressViewStyle: .default)
    private var player: AVPlayer?
    private var layer: AVPlayerLayer?
    private var timer: Timer?
    private var beganAt = Date()
    private var elapsed: TimeInterval = 0
    private var duration: TimeInterval = 6
    private var paused = false
    private var loadID = ""

    init(stories: [JSON]) {
        self.stories = stories
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        photo.contentMode = .scaleAspectFit
        photo.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(photo)
        NSLayoutConstraint.activate([
            photo.topAnchor.constraint(equalTo: view.topAnchor),
            photo.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            photo.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            photo.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        caption.translatesAutoresizingMaskIntoConstraints = false
        overlayText.translatesAutoresizingMaskIntoConstraints = false
        caption.numberOfLines = 3
        overlayText.numberOfLines = 3
        overlayText.textAlignment = .center
        progress.translatesAutoresizingMaskIntoConstraints = false
        progress.tintColor = .white
        progress.trackTintColor = UIColor.white.withAlphaComponent(0.25)
        for part in [titleLabel, caption, overlayText, progress] as [UIView] { view.addSubview(part) }
        let close = UIButton(type: .system)
        close.setImage(UIImage(systemName: "xmark.circle.fill"), for: .normal)
        close.tintColor = .white
        close.accessibilityLabel = "Закрыть историю"
        close.addAction(UIAction { [weak self] _ in self?.dismiss(animated: !Theme.reducedMotion) }, for: .touchUpInside)
        let more = UIButton(type: .system)
        more.setImage(UIImage(systemName: "ellipsis.circle.fill"), for: .normal)
        more.tintColor = .white
        more.accessibilityLabel = "Действия с историей"
        more.addAction(UIAction { [weak self] _ in self?.options() }, for: .touchUpInside)
        for button in [close, more] {
            button.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(button)
        }
        NSLayoutConstraint.activate([
            progress.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 5),
            progress.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            progress.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            titleLabel.topAnchor.constraint(equalTo: progress.bottomAnchor, constant: 20),
            titleLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 18),
            titleLabel.trailingAnchor.constraint(lessThanOrEqualTo: more.leadingAnchor, constant: -8),
            close.topAnchor.constraint(equalTo: progress.bottomAnchor, constant: 12),
            close.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            close.widthAnchor.constraint(equalToConstant: 44), close.heightAnchor.constraint(equalToConstant: 44),
            more.centerYAnchor.constraint(equalTo: close.centerYAnchor),
            more.trailingAnchor.constraint(equalTo: close.leadingAnchor, constant: -8),
            more.widthAnchor.constraint(equalToConstant: 44), more.heightAnchor.constraint(equalToConstant: 44),
            overlayText.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            overlayText.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            overlayText.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 30),
            overlayText.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -30),
            caption.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            caption.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),
            caption.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -28),
        ])
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        view.addGestureRecognizer(tap)
        let hold = UILongPressGestureRecognizer(target: self, action: #selector(hold(_:)))
        hold.minimumPressDuration = 0.25
        view.addGestureRecognizer(hold)
        show()
    }

    private func show() {
        timer?.invalidate(); timer = nil
        player?.pause(); player = nil
        layer?.removeFromSuperlayer(); layer = nil
        photo.image = nil
        elapsed = 0; progress.progress = 0; paused = false
        guard stories.indices.contains(index) else { dismiss(animated: !Theme.reducedMotion); return }
        let item = stories[index]
        let id = item.str("id")
        loadID = id
        let end = item.int64("display_until")
        if end > 0 && end <= Int64(Date().timeIntervalSince1970 * 1000) { next(1); return }
        titleLabel.text = item.str("author")
        caption.text = item.str("caption")
        let style = item.obj("style") ?? [:]
        overlayText.text = style.str("text")
        overlayText.textColor = Theme.rgb(UInt32(max(0, min(0xffffff, style.int("text_color") == 0 ? style.int("color") : style.int("text_color")))))
        duration = item.str("mime").hasPrefix("video/") ? max(1, min(60, Double(item.int64("duration")) / 1000)) : 6
        Core.shared.call("open_story", ["story_id": id]) { [weak self] result in
            guard let self, self.loadID == id else { return }
            switch result {
            case .failure(let error): self.toast(error.message); self.next(1)
            case .success(let value):
                guard let path = (value as? JSON)?.str("path"), !path.isEmpty else { self.next(1); return }
                if !item.bool("mine") {
                    Core.shared.call("story_feedback", ["story_id": id, "viewed": true]) { _ in }
                }
                if item.str("mime").hasPrefix("video/") {
                    let player = AVPlayer(url: URL(fileURLWithPath: path))
                    let layer = AVPlayerLayer(player: player)
                    layer.videoGravity = .resizeAspect
                    layer.frame = self.view.bounds
                    self.view.layer.insertSublayer(layer, at: 0)
                    self.player = player
                    self.layer = layer
                    player.play()
                } else {
                    DispatchQueue.global(qos: .userInitiated).async {
                        let image = Photos.decode(URL(fileURLWithPath: path), maxSide: 2048)
                        DispatchQueue.main.async {
                            guard self.loadID == id else { return }
                            self.photo.image = image
                        }
                    }
                }
                self.beganAt = Date()
                self.timer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in self?.tick() }
            }
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        layer?.frame = view.bounds
    }

    private func tick() {
        guard !paused else { return }
        let seconds = elapsed + Date().timeIntervalSince(beganAt)
        progress.progress = Float(min(1, seconds / duration))
        if seconds >= duration { next(1) }
    }

    private func next(_ offset: Int) {
        index += offset
        if index < 0 { index = 0 }
        show()
    }

    @objc private func tapped(_ g: UITapGestureRecognizer) {
        let x = g.location(in: view).x / max(1, view.bounds.width)
        if x < 0.34 { next(-1) }
        else if x > 0.66 { next(1) }
        else { setPaused(!paused) }
    }

    @objc private func hold(_ g: UILongPressGestureRecognizer) {
        if g.state == .began { setPaused(true) }
        if g.state == .ended || g.state == .cancelled || g.state == .failed { setPaused(false) }
    }

    private func setPaused(_ on: Bool) {
        guard on != paused else { return }
        paused = on
        if on {
            elapsed += Date().timeIntervalSince(beganAt)
            player?.pause()
        } else {
            beganAt = Date()
            player?.play()
        }
    }

    private func options() {
        guard stories.indices.contains(index) else { return }
        let item = stories[index], id = item.str("id")
        let sheet = UIAlertController(title: item.str("author"), message: nil, preferredStyle: .actionSheet)
        if item.bool("mine") {
            sheet.addAction(UIAlertAction(title: "Изменить подпись", style: .default) { [weak self] _ in self?.edit(item) })
            sheet.addAction(UIAlertAction(title: "Кто видит историю", style: .default) { [weak self] _ in
                Core.shared.call("story_audience", ["story_id": id]) { result in
                    guard let self else { return }
                    if case .success(let data) = result {
                        self.toast((data as? [String] ?? []).joined(separator: ", "))
                    }
                }
            })
            sheet.addAction(UIAlertAction(title: "Удалить историю", style: .destructive) { [weak self] _ in
                Core.shared.call("manage_story", ["story_id": id, "delete": true]) { result in
                    if case .failure(let e) = result { self?.toast(e.message) }
                    else { self?.next(1) }
                }
            })
        } else {
            for emoji in ["❤️", "🔥", "👏", "😍"] {
                sheet.addAction(UIAlertAction(title: "Реакция " + emoji, style: .default) { [weak self] _ in
                    Core.shared.call("story_feedback", ["story_id": id, "reaction": emoji]) { result in
                        if case .failure(let e) = result { self?.toast(e.message) }
                    }
                })
            }
            sheet.addAction(UIAlertAction(title: "Комментировать", style: .default) { [weak self] _ in self?.comment(id) })
        }
        sheet.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        sheet.popoverPresentationController?.sourceView = view
        sheet.popoverPresentationController?.sourceRect = CGRect(x: view.bounds.midX, y: 60, width: 2, height: 2)
        present(sheet, animated: !Theme.reducedMotion)
    }

    private func edit(_ item: JSON) {
        let a = UIAlertController(title: "Подпись истории", message: nil, preferredStyle: .alert)
        a.addTextField { $0.text = item.str("caption") }
        a.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        a.addAction(UIAlertAction(title: "Сохранить", style: .default) { [weak self, weak a] _ in
            let caption = String((a?.textFields?.first?.text ?? "").prefix(240))
            Core.shared.call("manage_story", ["story_id": item.str("id"), "caption": caption]) { result in
                if case .failure(let e) = result { self?.toast(e.message) }
                else { self?.caption.text = caption }
            }
        })
        present(a, animated: !Theme.reducedMotion)
    }

    private func comment(_ id: String) {
        let alert = UIAlertController(title: "Комментарий к истории", message: nil, preferredStyle: .alert)
        alert.addTextField { $0.placeholder = "Сообщение" }
        alert.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        alert.addAction(UIAlertAction(title: "Отправить", style: .default) { [weak self, weak alert] _ in
            guard let text = alert?.textFields?.first?.text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
            Core.shared.call("comment_story", ["story_id": id, "text": text]) { result in
                if case .failure(let e) = result { self?.toast(e.message) }
            }
        })
        present(alert, animated: !Theme.reducedMotion)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        timer?.invalidate()
        player?.pause()
    }
}
