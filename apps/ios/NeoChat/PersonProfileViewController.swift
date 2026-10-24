import PhotosUI
import UIKit
import UniformTypeIdentifiers

/// A native profile page. Shared items are paged from the encrypted local store.
class PersonProfileViewController: UIViewController, PHPickerViewControllerDelegate {
    private let userId: String?
    private var profile: JSON = [:]
    private let stack = UIStackView()
    private let details = UIStackView()
    private let content = UIStackView()
    private let avatar = AvatarView(size: 112)
    private let scroll = UIScrollView()
    private var avatarTop: NSLayoutConstraint?
    // The cover reaches from the top down to the end of the description card and fades out.
    private let cover = ProfileCoverView()
    private let name = label("", 28, .bold)
    private let subtitle = label("", 14, color: Theme.muted)
    private var category = "media"
    private var before: Int64?
    private var generation = 0
    private var items: [JSON] = []
    private let tabKeys = ["media", "files", "links", "groups", "privacy"]
    private let tabs = LensTabBar(items: ["Медиа", "Файлы", "Ссылки", "Группы", "Конфиденциальность"].map { (title: $0, icon: "") }, textOnly: true, equalWidth: false)
    private var tabIndex = 0

    init(userId: String? = nil) { self.userId = userId; super.init(nibName: nil, bundle: nil); hidesBottomBarWhenPushed = userId != nil }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Профиль"
        navigationItem.titleView = GlassPill.title("Профиль")
        navigationItem.largeTitleDisplayMode = .never
        view.backgroundColor = Theme.canvas
        view.addSubview(BackgroundView(frame: view.bounds))
        [stack, details, content].forEach { $0.axis = .vertical; $0.spacing = 12 }
        stack.alignment = .center
        stack.spacing = 16
        name.textAlignment = .center
        subtitle.textAlignment = .center
        avatar.accessibilityLabel = "Открыть фото профиля"
        avatar.accessibilityTraits = .button
        avatar.isUserInteractionEnabled = true
        avatar.addGestureRecognizer(TapGesture { [weak self] in self?.showPhotos() })
        [name, subtitle, details].forEach(stack.addArrangedSubview)
        let tabScroll = UIScrollView()
        tabScroll.showsHorizontalScrollIndicator = false
        tabs.translatesAutoresizingMaskIntoConstraints = false
        tabs.onSelect = { [weak self] i in
            guard let self else { return }
            self.select(self.tabKeys[i])
        }
        tabScroll.addSubview(tabs)
        tabScroll.heightAnchor.constraint(equalToConstant: 68).isActive = true
        NSLayoutConstraint.activate([
            tabs.topAnchor.constraint(equalTo: tabScroll.contentLayoutGuide.topAnchor),
            tabs.bottomAnchor.constraint(equalTo: tabScroll.contentLayoutGuide.bottomAnchor),
            tabs.leadingAnchor.constraint(equalTo: tabScroll.contentLayoutGuide.leadingAnchor),
            tabs.trailingAnchor.constraint(equalTo: tabScroll.contentLayoutGuide.trailingAnchor),
            tabs.heightAnchor.constraint(equalTo: tabScroll.frameLayoutGuide.heightAnchor),
        ])
        stack.addArrangedSubview(tabScroll)
        stack.addArrangedSubview(content)
        scroll.translatesAutoresizingMaskIntoConstraints = false
        // The page begins at the top of the screen: the cover shows through the glass of the bar and the status bar.
        scroll.contentInsetAdjustmentBehavior = .never
        setContentScrollView(scroll, for: .top)
        stack.translatesAutoresizingMaskIntoConstraints = false
        cover.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll); scroll.addSubview(cover); scroll.addSubview(avatar); scroll.addSubview(stack)
        let avatarTop = avatar.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 94)
        self.avatarTop = avatarTop
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.topAnchor), scroll.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor), scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            cover.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor), cover.bottomAnchor.constraint(equalTo: details.bottomAnchor, constant: 16),
            cover.leadingAnchor.constraint(equalTo: scroll.frameLayoutGuide.leadingAnchor), cover.trailingAnchor.constraint(equalTo: scroll.frameLayoutGuide.trailingAnchor),
            avatar.centerXAnchor.constraint(equalTo: scroll.frameLayoutGuide.centerXAnchor), avatarTop,
            stack.topAnchor.constraint(equalTo: avatar.bottomAnchor, constant: 14), stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -28),
            stack.leadingAnchor.constraint(equalTo: scroll.frameLayoutGuide.leadingAnchor, constant: 16), stack.trailingAnchor.constraint(equalTo: scroll.frameLayoutGuide.trailingAnchor, constant: -16),
            details.widthAnchor.constraint(equalTo: stack.widthAnchor), tabScroll.widthAnchor.constraint(equalTo: stack.widthAnchor), content.widthAnchor.constraint(equalTo: stack.widthAnchor),
            name.widthAnchor.constraint(equalTo: stack.widthAnchor), subtitle.widthAnchor.constraint(equalTo: stack.widthAnchor),
        ])
        NotificationCenter.default.addObserver(forName: .coreEvent, object: nil, queue: .main) { [weak self] n in
            if ["account_changed","contacts_changed","identity_changed"].contains((n.userInfo as? JSON)?.str("type") ?? "") { self?.reload() }
        }
        reload()
    }

    override func viewWillAppear(_ animated: Bool) { super.viewWillAppear(animated); if isViewLoaded { reload() } }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        // The avatar sits as far below the bar as before, though the page now begins above it.
        let top = view.safeAreaInsets.top + 94
        if avatarTop?.constant != top { avatarTop?.constant = top }
        scroll.verticalScrollIndicatorInsets.top = view.safeAreaInsets.top
    }

    private func clear(_ col: UIStackView) { col.arrangedSubviews.forEach { col.removeArrangedSubview($0); $0.removeFromSuperview() } }
    private func button(_ title: String, _ icon: String, _ action: @escaping () -> Void) -> UIButton {
        var cfg = UIButton.Configuration.plain()
        cfg.title = title; cfg.image = UIImage(systemName: icon); cfg.imagePadding = 8
        cfg.baseForegroundColor = Theme.accent
        cfg.contentInsets = NSDirectionalEdgeInsets(top: 12, leading: 12, bottom: 12, trailing: 12)
        let b = UIButton(configuration: cfg, primaryAction: UIAction { _ in action() }); b.glass(18)
        return b
    }
    private func info(_ title: String, _ value: String, action: (() -> Void)? = nil) -> UIView {
        let valueLabel = label(value, 16)
        let col = UIStackView(arrangedSubviews: [valueLabel, label(title, 12, color: Theme.muted)])
        col.axis = .vertical; col.spacing = 5
        col.isLayoutMarginsRelativeArrangement = true; col.layoutMargins = UIEdgeInsets(top: 14, left: 16, bottom: 14, right: 16)
        if let action { col.addGestureRecognizer(TapGesture(action)); col.accessibilityTraits = .button }
        return col
    }

    private func reload() {
        Core.shared.call("user_profile", userId.map { ["user_id":$0] } ?? [:]) { [weak self] r in
            guard let self else { return }
            guard case .success(let v) = r, let p = v as? JSON else { if case .failure(let e) = r { self.toast(e.message) }; return }
            self.profile = p
            let own = p.bool("me"), uid = p.str("user_id")
            self.name.text = p.str("display_name").isEmpty ? "@"+p.str("username") : p.str("display_name")
            self.name.textColor = ProfileStyle.name(p.str("name_color")) ?? Theme.text
            self.cover.colors = ProfileStyle.colors(header: p.str("header"), userId: uid)
            self.avatar.set(self.name.text ?? "", seed: uid, avatar: p.str("avatar_path"))
            self.subtitle.text = own ? "Ваш профиль" : (p.bool("verified") ? "Ключ сверен" : "@"+p.str("username"))
            if own {
                let edit = glassBarButton(symbol: "pencil", label: "Изменить профиль")
                edit.addAction(UIAction { [weak self] _ in self?.presentSheet(EditProfileViewController()) }, for: .touchUpInside)
                self.navigationItem.rightBarButtonItem = UIBarButtonItem(customView: GlassPill.group([edit]))
            } else {
                self.navigationItem.rightBarButtonItem = nil
            }
            self.clear(self.details)
            let actions = UIStackView(); actions.spacing = 10; actions.distribution = .fillEqually
            if own {
                actions.addArrangedSubview(self.button("Настройки","gearshape") { [weak self] in self?.presentSheet(SettingsViewController()) })
            } else {
                actions.addArrangedSubview(self.button("Чат","bubble.left.fill") { [weak self] in self?.openChat() })
                actions.addArrangedSubview(self.button("Звонок","phone.fill") { [weak self] in self?.call(video:false) })
                actions.addArrangedSubview(self.button("Видео","video.fill") { [weak self] in self?.call(video:true) })
            }
            self.details.addArrangedSubview(actions)
            let card = UIStackView(); card.axis = .vertical; card.glass(24)
            let username = self.info("Имя пользователя", "@"+p.str("username")) { [weak self] in UIPasteboard.general.string = "@"+p.str("username"); self?.toast("Имя пользователя скопировано") }
            let line = UIStackView(arrangedSubviews: [username]); line.alignment = .center; line.spacing = 4
            line.addArrangedSubview(self.button("", "qrcode") { [weak self] in self?.presentSheet(MyQrViewController(userId:uid)) })
            if !own && !p.bool("is_contact") {
                let add = self.button("", "person.badge.plus") { [weak self] in self?.addContact() }
                add.accessibilityLabel = "Добавить в контакт"; line.addArrangedSubview(add)
            }
            card.addArrangedSubview(line)
            card.addArrangedSubview(self.info("О себе", p.str("bio").isEmpty ? "Не указано" : p.str("bio")))
            if own { card.addArrangedSubview(self.info("Оформление", "Цвет имени и шапка профиля") { [weak self] in self?.presentSheet(ProfileStyleViewController(profile: p)) }) }
            if !own { card.addArrangedSubview(self.info("Заметки · видны только вам", p.str("note").isEmpty ? "Добавить заметку" : p.str("note")) { [weak self] in self?.editNote() }) }
            self.details.addArrangedSubview(card)
            self.select(self.category, animated:false)
        }
    }

    private func openChat() {
        Core.shared.call("open_direct", ["user_id":profile.str("user_id")]) { [weak self] r in
            guard let self, case .success(let v) = r, let c = v as? JSON else { return }
            if let tabs = self.tabBarController as? MainTabController { tabs.open(c.str("id")) }
            else { NotificationCenter.default.post(name:.openConversation,object:nil,userInfo:["id":c.str("id")]) }
        }
    }
    private func call(video: Bool) {
        Core.shared.call("open_direct", ["user_id":profile.str("user_id")]) { [weak self] r in
            guard let self, case .success(let v) = r, let c = v as? JSON else { return }
            CallManager.withPermissions(video: video, from:self) { cam in CallManager.shared.start(c,video:video && cam) }
        }
    }
    private func addContact() {
        Core.shared.call("resolve_contact", ["username":profile.str("username")]) { [weak self] r in
            if case .failure(let e) = r { self?.toast(e.message) } else { self?.reload(); self?.toast("Контакт добавлен") }
        }
    }
    private func editNote() {
        let editor = ProfileNoteViewController(userId:profile.str("user_id"), note:profile.str("note"))
        presentSheet(editor)
    }

    private func select(_ key: String, animated: Bool = true) {
        let keys = ["media","files","links","groups","privacy"]
        let next = keys.firstIndex(of: key) ?? 0
        let dir = next == tabIndex ? 0 : (next > tabIndex ? 1 : -1)
        tabIndex = next
        category = key; before = nil; items = []; generation += 1
        tabs.setSelected(next, animated: animated && dir != 0)
        clear(content)
        if key == "privacy" {
            let text = profile.bool("me") ? "Коды безопасности доступны в профилях ваших собеседников." : profile.str("safety_number")
            content.addArrangedSubview(label("Сквозное шифрование",18,.semibold))
            content.addArrangedSubview(label(text,18,.semibold,color:Theme.accent))
            if !profile.bool("me") {
                content.addArrangedSubview(label("Сравните этот код с собеседником по другому надёжному каналу.",14,color:Theme.muted))
                if profile.bool("key_changed") { content.addArrangedSubview(button("Код сверен, принять новый ключ","checkmark.shield") { [weak self] in self?.verify("accept_key_change") }) }
                else if !profile.bool("verified") { content.addArrangedSubview(button("Отметить как сверенный","checkmark.shield") { [weak self] in self?.verify("verify_contact") }) }
            }
            reveal(dir, animated)
        } else if key == "groups" {
            let groups = profile.arr("groups")
            if groups.isEmpty { content.addArrangedSubview(label("Общих групп пока нет",15,color:Theme.muted)) }
            for g in groups { content.addArrangedSubview(button(g.str("title"),"person.3") { NotificationCenter.default.post(name:.openConversation,object:nil,userInfo:["id":g.str("id")]) }) }
            reveal(dir, animated)
        } else { loadItems(dir: dir, animated: animated) }
    }
    private func reveal(_ dir: Int, _ animated: Bool) {
        guard animated, !Theme.reducedMotion else { content.alpha = 1; content.transform = .identity; return }
        content.alpha = 0
        content.transform = CGAffineTransform(translationX: CGFloat(dir) * 24, y: 0)
        Theme.animate(0.2) { self.content.alpha = 1 }
        Motion.animate(0.42, 0.86) { self.content.transform = .identity }
    }
    private func verify(_ method: String) {
        Core.shared.call(method,["user_id":profile.str("user_id")]) { [weak self] r in if case .failure(let e) = r { self?.toast(e.message) } else { self?.reload() } }
    }
    private func loadItems(dir: Int = 0, animated: Bool = false) {
        let gen = generation
        let fresh = before == nil
        var p: JSON = ["user_id":profile.str("user_id"),"kind":category,"limit":48]
        if let before { p["before"] = before }
        Core.shared.call("profile_items",p) { [weak self] r in
            guard let self, gen == self.generation else { return }
            guard case .success(let v) = r else { if case .failure(let e) = r { self.toast(e.message) }; return }
            let page = v as? [JSON] ?? []
            self.items += page; self.before = page.last?.int64("local_id")
            self.clear(self.content)
            if self.items.isEmpty { self.content.addArrangedSubview(label("Здесь появятся общие " + (self.category == "media" ? "фото" : self.category == "links" ? "ссылки" : "файлы"),15,color:Theme.muted)) }
            if self.category == "media" {
                for start in stride(from:0,to:self.items.count,by:3) {
                    let row = UIStackView(); row.spacing = 4; row.distribution = .fillEqually
                    for i in start..<min(start+3,self.items.count) {
                        let m = self.items[i]
                        let tile = UIImageView(image:Photos.thumb(m)); tile.contentMode = .scaleAspectFill; tile.clipsToBounds = true; tile.layer.cornerRadius = 12
                        tile.backgroundColor = Theme.glass
                        tile.heightAnchor.constraint(equalTo:tile.widthAnchor).isActive = true
                        tile.isUserInteractionEnabled = true; tile.accessibilityLabel = "Открыть фото"; tile.accessibilityTraits = .button
                        tile.addGestureRecognizer(TapGesture { [weak self] in guard let self else { return }; self.present(PhotoGalleryController(photos:self.items,start:i),animated:!Theme.reducedMotion) })
                        row.addArrangedSubview(tile)
                    }
                    for _ in (min(start+3,self.items.count)-start)..<3 { row.addArrangedSubview(UIView()) }
                    self.content.addArrangedSubview(row)
                }
            } else {
                for m in self.items {
                    let title = self.category == "links" ? (links(m).first ?? m.str("text")) : (m.obj("file")?.str("name") ?? "Файл")
                    self.content.addArrangedSubview(self.button(title,self.category == "links" ? "link" : "doc") { [weak self] in
                        guard let self else { return }
                        if self.category == "links", let url = URL(string:title), ["http","https"].contains(url.scheme ?? "") { UIApplication.shared.open(url) }
                        else { Core.shared.call("download_file",["message_id":m.str("id")]) { r in
                            guard case .success(let v) = r, let path = (v as? JSON)?.str("path") else { if case .failure(let e) = r { self.toast(e.message) }; return }
                            let share = UIActivityViewController(activityItems:[URL(fileURLWithPath:path)],applicationActivities:nil); share.popoverPresentationController?.sourceView = self.content; self.present(share,animated:!Theme.reducedMotion)
                        } }
                    })
                }
            }
            if page.count == 48 { self.content.addArrangedSubview(self.button("Показать ещё","chevron.down") { [weak self] in self?.loadItems() }) }
            if fresh { self.reveal(dir, animated) }
        }
    }

    private func showPhotos() {
        let uid = profile.str("user_id")
        Core.shared.call("profile_photos",["user_id":uid]) { [weak self] r in
            guard let self else { return }
            guard case .success(let v) = r else { if case .failure(let e) = r { self.toast(e.message) }; return }
            let photos = (v as? [JSON] ?? []).map { p -> JSON in ["id":p.str("id"),"profile_user_id":uid] }
            if photos.isEmpty { if self.profile.bool("me") { self.pickPhotos() }; return }
            let gallery = PhotoGalleryController(photos:photos,start:0)
            if self.profile.bool("me") { gallery.edit = { [weak self, weak gallery] id in
                guard let self, let gallery else { return }
                let menu = UIAlertController(title:"Фото профиля",message:nil,preferredStyle:.actionSheet)
                menu.addAction(UIAlertAction(title:"Добавить фото",style:.default) { _ in gallery.dismiss(animated:!Theme.reducedMotion) { self.pickPhotos() } })
                menu.addAction(UIAlertAction(title:"Удалить это фото",style:.destructive) { _ in Core.shared.call("delete_profile_photo",["photo_id":id]) { r in
                    if case .failure(let e) = r { gallery.toast(e.message) } else { gallery.dismiss(animated:!Theme.reducedMotion); self.reload() }
                } })
                menu.addAction(UIAlertAction(title:"Отмена",style:.cancel)); menu.popoverPresentationController?.sourceView = gallery.view; gallery.present(menu,animated:!Theme.reducedMotion)
            } }
            self.present(gallery,animated:!Theme.reducedMotion)
        }
    }
    private func pickPhotos() {
        var cfg = PHPickerConfiguration(); cfg.filter = .images; cfg.selectionLimit = 10
        let picker = PHPickerViewController(configuration:cfg); picker.delegate = self; present(picker,animated:!Theme.reducedMotion)
    }
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated:!Theme.reducedMotion)
        upload(results,at:0)
    }
    private func upload(_ picks: [PHPickerResult], at index: Int) {
        guard index < picks.count else { reload(); return }
        picks[index].itemProvider.loadFileRepresentation(forTypeIdentifier:UTType.image.identifier) { [weak self] url,_ in
            guard let url, let image = Photos.decode(url,maxSide:4096), let data = image.jpegData(compressionQuality:0.9), data.count <= 8<<20 else { DispatchQueue.main.async { self?.toast("Не удалось подготовить фото") }; return }
            let file = FileManager.default.temporaryDirectory.appendingPathComponent("profile-\(UUID().uuidString).jpg")
            do { try data.write(to:file,options:.atomic) } catch { DispatchQueue.main.async { self?.toast("Не удалось сохранить фото") }; return }
            DispatchQueue.main.async {
                Core.shared.call("set_profile_photo",["path":file.path]) { r in
                    try? FileManager.default.removeItem(at:file)
                    if case .failure(let e) = r { self?.toast(e.message) } else { self?.upload(picks,at:index+1) }
                }
            }
        }
    }
}

private final class ProfileNoteViewController: SheetViewController {
    private let userId: String
    private let note: String
    init(userId: String,note: String) { self.userId = userId; self.note = note; super.init(nibName:nil,bundle:nil) }
    required init?(coder:NSCoder) { fatalError() }
    override func viewDidLoad() {
        super.viewDidLoad(); title = "Личная заметка"
        let field = Field("До 100 символов"); field.text = note
        stack.addArrangedSubview(field); muted("Видна только вам и синхронизируется между вашими устройствами.")
        let save = PillButton("Сохранить"); stack.addArrangedSubview(save)
        save.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            guard (field.text ?? "").count <= 100 else { self.toast("Заметка: до 100 символов"); return }
            save.isEnabled = false
            Core.shared.call("set_profile_note",["user_id":self.userId,"note":field.text ?? ""]) { r in
                save.isEnabled = true
                if case .failure(let e) = r { self.toast(e.message) } else { self.dismiss(animated:!Theme.reducedMotion) }
            }
        },for:.touchUpInside)
    }
}
