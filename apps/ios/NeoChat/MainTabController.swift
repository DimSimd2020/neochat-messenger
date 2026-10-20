import UIKit
import PhotosUI
import UniformTypeIdentifiers

/// Navigation with a guarded swipe-back: the gesture may only start on a real
/// stacked screen and never while another transition is still running.
final class ChatNavController: UINavigationController, UIGestureRecognizerDelegate {
    override func viewDidLoad() {
        super.viewDidLoad()
        interactivePopGestureRecognizer?.delegate = self
    }

    func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
        g !== interactivePopGestureRecognizer || (viewControllers.count > 1 && transitionCoordinator == nil)
    }
}

/// Chats, contacts and profile behind a floating glass tab bar, as on Android.
final class MainTabController: UITabBarController {
    private let pill = UIView()

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if pill.superview == nil {
            pill.glass(28)
            pill.isUserInteractionEnabled = false
            pill.layer.shadowColor = UIColor.black.cgColor
            pill.layer.shadowOpacity = 0.12
            pill.layer.shadowRadius = 14
            pill.layer.shadowOffset = CGSize(width: 0, height: 4)
            tabBar.insertSubview(pill, at: 0)
        }
        let h = tabBar.bounds.height - tabBar.safeAreaInsets.bottom
        pill.frame = CGRect(x: 12, y: -6, width: tabBar.bounds.width - 24, height: h + 10)
        tabBar.sendSubviewToBack(pill)
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        pill.layer.borderColor = Theme.rim.resolvedColor(with: traitCollection).cgColor
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        let chats = nav(ChatsViewController(), "Чаты", "bubble.left.and.bubble.right")
        let contacts = nav(ContactsViewController(), "Контакты", "person.2")
        let profile = nav(ProfileViewController(), "Профиль", "person.crop.circle")
        viewControllers = [chats, contacts, profile]
        let a = UITabBarAppearance()
        a.configureWithTransparentBackground()
        tabBar.standardAppearance = a
        tabBar.scrollEdgeAppearance = a
        tabBar.tintColor = Theme.action
        NotificationCenter.default.addObserver(forName: .openConversation, object: nil, queue: .main) { [weak self] n in
            guard let id = n.userInfo?["id"] as? String else { return }
            self?.open(id)
        }
    }

    func open(_ conversationId: String) {
        selectedIndex = 0
        guard let nav = viewControllers?.first as? UINavigationController else { return }
        if (nav.topViewController as? ChatViewController)?.conversationId == conversationId { return }
        nav.popToRootViewController(animated: false)
        nav.pushViewController(ChatViewController(conversationId: conversationId), animated: !Theme.reducedMotion)
    }

    private func nav(_ vc: UIViewController, _ title: String, _ icon: String) -> UINavigationController {
        vc.title = title
        let n = ChatNavController(rootViewController: vc)
        n.navigationBar.prefersLargeTitles = true
        n.tabBarItem = UITabBarItem(title: title, image: UIImage(systemName: icon), tag: 0)
        let a = UINavigationBarAppearance()
        a.configureWithTransparentBackground()
        a.largeTitleTextAttributes = [.foregroundColor: Theme.text]
        a.titleTextAttributes = [.foregroundColor: Theme.text]
        n.navigationBar.standardAppearance = {
            let s = UINavigationBarAppearance()
            s.configureWithTransparentBackground()
            s.backgroundEffect = UIBlurEffect(style: .systemUltraThinMaterial)
            s.backgroundColor = Theme.glass.withAlphaComponent(0.35)
            s.titleTextAttributes = [.foregroundColor: Theme.text]
            return s
        }()
        n.navigationBar.scrollEdgeAppearance = a
        n.navigationBar.tintColor = Theme.accent
        n.view.backgroundColor = .clear
        return n
    }
}

/// Table cell: avatar, title, subtitle, time and unread badge.
final class RowCell: UITableViewCell {
    static let id = "row"
    let avatar = AvatarView(size: 52)
    let title = label("", 17, .semibold)
    let subtitle = label("", 15, color: Theme.muted)
    let time = label("", 13, color: Theme.muted)
    let badge = PaddedBadge()
    let pin = UIImageView(image: UIImage(systemName: "pin.fill", withConfiguration: UIImage.SymbolConfiguration(pointSize: 13, weight: .semibold)))

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        let sel = UIView()
        sel.glass(25)
        selectedBackgroundView = sel
        title.numberOfLines = 1
        subtitle.numberOfLines = 1
        time.setContentHuggingPriority(.required, for: .horizontal)
        let top = UIStackView(arrangedSubviews: [title, time])
        top.spacing = 8
        pin.tintColor = Theme.muted
        pin.isHidden = true
        pin.setContentHuggingPriority(.required, for: .horizontal)
        let bottom = UIStackView(arrangedSubviews: [subtitle, pin, badge])
        bottom.spacing = 8
        bottom.alignment = .center
        let text = UIStackView(arrangedSubviews: [top, bottom])
        text.axis = .vertical
        text.spacing = 4
        let row = UIStackView(arrangedSubviews: [avatar, text])
        row.spacing = 14
        row.alignment = .center
        row.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(row)
        NSLayoutConstraint.activate([
            row.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 10),
            row.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -10),
            row.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 16),
            row.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -16),
        ])
    }

    required init?(coder: NSCoder) { fatalError() }
}

final class PaddedBadge: UILabel {
    init() {
        super.init(frame: .zero)
        font = .systemFont(ofSize: 12, weight: .bold)
        textColor = .white
        backgroundColor = Theme.action
        textAlignment = .center
        layer.cornerRadius = 10
        clipsToBounds = true
        setContentHuggingPriority(.required, for: .horizontal)
    }

    required init?(coder: NSCoder) { fatalError() }

    override var intrinsicContentSize: CGSize {
        let s = super.intrinsicContentSize
        return CGSize(width: max(20, s.width + 12), height: 20)
    }

    func set(_ n: Int) {
        isHidden = n == 0
        text = n > 99 ? "99+" : "\(n)"
    }
}

class ListViewController: UITableViewController, UISearchResultsUpdating {
    let search = UISearchController(searchResultsController: nil)
    var rows: [JSON] = []
    private var refreshPending = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        tableView.backgroundColor = Theme.canvas
        tableView.separatorStyle = .none
        tableView.register(RowCell.self, forCellReuseIdentifier: RowCell.id)
        search.searchResultsUpdater = self
        search.obscuresBackgroundDuringPresentation = false
        navigationItem.searchController = search
        navigationItem.hidesSearchBarWhenScrolling = false
        definesPresentationContext = true
        NotificationCenter.default.addObserver(forName: .coreEvent, object: nil, queue: .main) { [weak self] n in
            guard let self, let t = (n.userInfo as? JSON)?.str("type"), self.reacts(to: t) else { return }
            self.scheduleReload()
        }
        NotificationCenter.default.addObserver(forName: .prefsChanged, object: nil, queue: .main) { [weak self] _ in self?.scheduleReload() }
        NotificationCenter.default.addObserver(forName: .typingChanged, object: nil, queue: .main) { [weak self] _ in
            guard let self = self as? ChatListViewController else { return }
            for case let cell as RowCell in self.tableView.visibleCells {
                if let path = self.tableView.indexPath(for: cell), path.row < self.rows.count {
                    bindChatCell(cell, self.rows[path.row])
                }
            }
        }
        reload()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        reload()
    }

    func scheduleReload() {
        if refreshPending { return }
        refreshPending = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) {
            self.refreshPending = false
            self.reload()
        }
    }

    func reacts(to type: String) -> Bool { false }
    func reload() {}
    func updateSearchResults(for searchController: UISearchController) { reload() }
    var query: String { search.searchBar.text ?? "" }

    func apply(_ new: [JSON], empty: String) {
        let changed = (new as NSArray) != (rows as NSArray)
        rows = new
        if changed {
            UIView.transition(with: tableView, duration: Theme.ms(0.14), options: [.transitionCrossDissolve, .allowUserInteraction]) { self.tableView.reloadData() }
        }
        if rows.isEmpty {
            // Wrapped inside side margins: a bare label ran off both edges.
            let l = label(empty, 15, color: Theme.muted)
            l.textAlignment = .center
            l.translatesAutoresizingMaskIntoConstraints = false
            let box = UIView()
            box.addSubview(l)
            NSLayoutConstraint.activate([
                l.leadingAnchor.constraint(equalTo: box.layoutMarginsGuide.leadingAnchor, constant: 24),
                l.trailingAnchor.constraint(equalTo: box.layoutMarginsGuide.trailingAnchor, constant: -24),
                l.centerYAnchor.constraint(equalTo: box.centerYAnchor, constant: -40),
            ])
            tableView.backgroundView = box
        } else {
            tableView.backgroundView = nil
        }
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { rows.count }
}

class ChatListViewController: ListViewController {
    private var selectedChats: [String: JSON] = [:]
    private var savedItems: [UIBarButtonItem]?

    override func viewDidLoad() {
        super.viewDidLoad()
        tableView.allowsMultipleSelectionDuringEditing = true
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if isMovingFromParent && isEditing { endSelection() }
    }

    func beginSelection(_ indexPath: IndexPath? = nil) {
        if !isEditing {
            savedItems = navigationItem.rightBarButtonItems
            setEditing(true, animated: !Theme.reducedMotion)
            search.searchBar.isUserInteractionEnabled = false
            navigationItem.rightBarButtonItems = [UIBarButtonItem(title: "Готово", primaryAction: UIAction { [weak self] _ in self?.endSelection() })]
            toolbarItems = [
                selectionButton("archivebox", "Архивировать", "set_archived", "archived"),
                UIBarButtonItem(systemItem: .flexibleSpace),
                selectionButton("pin", "Закрепить", "set_pinned", "pinned"),
                UIBarButtonItem(systemItem: .flexibleSpace),
                selectionButton("trash.slash", "Очистить", "clear_conversation", "hide", false),
                UIBarButtonItem(systemItem: .flexibleSpace),
                selectionButton("trash", "Удалить чаты", "clear_conversation", "hide", true),
            ]
            navigationController?.setToolbarHidden(false, animated: !Theme.reducedMotion)
        }
        if let indexPath, !rows[indexPath.row].bool("_archive") {
            tableView.selectRow(at: indexPath, animated: false, scrollPosition: .none)
        }
        selectionChanged()
    }

    private func selectionButton(_ icon: String, _ title: String, _ method: String, _ key: String, _ value: Bool? = nil) -> UIBarButtonItem {
        let b = UIBarButtonItem(image: UIImage(systemName: icon), primaryAction: UIAction { [weak self] _ in
            guard let self, !self.selectedChats.isEmpty else { return }
            let on = value ?? !self.selectedChats.values.allSatisfy { $0.bool(method == "set_pinned" ? "pinned" : "in_archive") }
            let chats = Array(self.selectedChats.values)
            let run: () -> Void = { [weak self] in
                guard let self else { return }
                self.run(chats, method, key, on)
            }
            if method == "clear_conversation" {
                confirmClearConversation(self, count: chats.count, hide: on) { run() }
            } else { run() }
        })
        b.accessibilityLabel = title
        return b
    }

    private func run(_ chats: [JSON], _ method: String, _ key: String, _ value: Bool) {
        tableView.isUserInteractionEnabled = false
        navigationItem.rightBarButtonItems?.forEach { $0.isEnabled = false }
        toolbarItems?.forEach { $0.isEnabled = false }
        func next(_ i: Int) {
            if i == chats.count { endSelection(); return }
            let id = chats[i].str("id")
            Core.shared.call(method, ["conversation_id": id, key: value]) { [weak self] r in
                guard let self else { return }
                if case .failure(let e) = r {
                    self.tableView.isUserInteractionEnabled = true
                    self.toast(e.message)
                    self.navigationItem.rightBarButtonItems?.forEach { $0.isEnabled = true }
                    self.toolbarItems?.forEach { $0.isEnabled = true }
                    return
                }
                self.selectedChats.removeValue(forKey: id)
                next(i + 1)
            }
        }
        next(0)
    }

    func selectionChanged() {
        guard isEditing else { return }
        selectedChats = Dictionary(uniqueKeysWithValues: (tableView.indexPathsForSelectedRows ?? []).compactMap { p in
            let c = rows[p.row]
            return c.bool("_archive") ? nil : (c.str("id"), c)
        })
        toolbarItems?.forEach { $0.isEnabled = !selectedChats.isEmpty }
        let allArchived = !selectedChats.isEmpty && selectedChats.values.allSatisfy { $0.bool("in_archive") }
        let allPinned = !selectedChats.isEmpty && selectedChats.values.allSatisfy { $0.bool("pinned") }
        toolbarItems?.first?.accessibilityLabel = allArchived ? "Вернуть из архива" : "Архивировать"
        toolbarItems?.first?.image = UIImage(systemName: allArchived ? "tray.and.arrow.up" : "archivebox")
        if let pin = toolbarItems?[2] {
            pin.accessibilityLabel = allPinned ? "Открепить" : "Закрепить"
            pin.image = UIImage(systemName: allPinned ? "pin.slash" : "pin")
        }
        navigationItem.prompt = "Выбрано: \(selectedChats.count)"
    }

    func endSelection() {
        tableView.isUserInteractionEnabled = true
        selectedChats.removeAll()
        setEditing(false, animated: !Theme.reducedMotion)
        navigationItem.prompt = nil
        navigationItem.rightBarButtonItems = savedItems
        search.searchBar.isUserInteractionEnabled = true
        navigationController?.setToolbarHidden(true, animated: !Theme.reducedMotion)
    }

    override func apply(_ new: [JSON], empty: String) {
        let ids = Set(selectedChats.keys)
        super.apply(new, empty: empty)
        if isEditing {
            for (i, c) in rows.enumerated() where ids.contains(c.str("id")) {
                tableView.selectRow(at: IndexPath(row: i, section: 0), animated: false, scrollPosition: .none)
            }
            selectionChanged()
        }
    }

    override func tableView(_ tableView: UITableView, didDeselectRowAt indexPath: IndexPath) { selectionChanged() }

    override func tableView(_ tableView: UITableView, canEditRowAt indexPath: IndexPath) -> Bool { !rows[indexPath.row].bool("_archive") }

    override func tableView(_ tableView: UITableView, contextMenuConfigurationForRowAt indexPath: IndexPath, point: CGPoint) -> UIContextMenuConfiguration? {
        guard !rows[indexPath.row].bool("_archive"), !isEditing else { return nil }
        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in
            UIMenu(children: [UIAction(title: "Выбрать чаты", image: UIImage(systemName: "checkmark.circle")) { _ in self?.beginSelection(indexPath) }])
        }
    }
}

final class ChatsViewController: ChatListViewController {
    private var filter = 0
    private var all: [JSON] = []
    private var archived: [JSON] = []
    private var presenceTimer: Timer?
    private var archiveShown = false
    private let archiveButton = UIButton(type: .system)
    private var logoItem: UIBarButtonItem?
    private var archiveHeight: CGFloat { archiveShown && query.isEmpty && filter == 0 && !archived.isEmpty ? 56 : 0 }

    override func viewDidLoad() {
        super.viewDidLoad()
        search.searchBar.placeholder = "Поиск чатов"
        search.searchBar.scopeButtonTitles = ["Все", "Личные", "Группы"]
        search.searchBar.showsScopeBar = true
        search.searchBar.delegate = self
        // Bar items lay custom views out with Auto Layout: without constraints the logo stretched wide.
        let logo = UIImageView(image: UIImage(named: "Logo"))
        logo.contentMode = .scaleAspectFit
        logo.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([logo.widthAnchor.constraint(equalToConstant: 28), logo.heightAnchor.constraint(equalToConstant: 28)])
        navigationItem.leftBarButtonItem = UIBarButtonItem(customView: logo)
        logoItem = navigationItem.leftBarButtonItem
        navigationItem.title = "NeoChat"
        updateBarItems()
        archiveButton.setImage(UIImage(systemName: "archivebox.fill"), for: .normal)
        archiveButton.tintColor = Theme.accent
        archiveButton.backgroundColor = Theme.glass
        archiveButton.layer.cornerRadius = 18
        archiveButton.contentHorizontalAlignment = .leading
        archiveButton.contentEdgeInsets = UIEdgeInsets(top: 0, left: 18, bottom: 0, right: 18)
        archiveButton.accessibilityLabel = "Архив"
        archiveButton.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            AppLock.unlockArchive(from: self) { [weak self] in self?.navigationController?.pushViewController(ArchiveViewController(), animated: !Theme.reducedMotion) }
        }, for: .touchUpInside)
        tableView.addSubview(archiveButton)
        tableView.alwaysBounceVertical = true
        let back = UIScreenEdgePanGestureRecognizer(target: self, action: #selector(hideArchiveBySwipe(_:)))
        back.edges = .left
        tableView.addGestureRecognizer(back)
        tableView.accessibilityCustomActions = [UIAccessibilityCustomAction(name: "Показать архив", target: self, selector: #selector(revealArchiveForAccessibility))]
    }

    private func updateBarItems() {
        let compose = UIBarButtonItem(image: UIImage(systemName: "square.and.pencil"), primaryAction: UIAction { [weak self] _ in
            self?.presentSheet(NewChatViewController())
        })
        compose.accessibilityLabel = "Новый чат"
        let sorts = chatSorts.map { k, name in
            UIAction(title: name, state: k == Prefs.chatSort ? .on : .off) { [weak self] _ in
                Prefs.set("chat_sort", k)
                self?.updateBarItems()
            }
        }
        let sort = UIBarButtonItem(image: UIImage(systemName: "arrow.up.arrow.down"), menu: UIMenu(title: "Сортировка чатов", children: sorts))
        sort.accessibilityLabel = "Сортировка чатов"
        let select = UIBarButtonItem(image: UIImage(systemName: "checkmark.circle"), primaryAction: UIAction { [weak self] _ in self?.beginSelection() })
        select.accessibilityLabel = "Выбрать чаты"
        navigationItem.rightBarButtonItems = [compose, sort, select]
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        refreshPresence()
        presenceTimer?.invalidate()
        presenceTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in self?.refreshPresence() }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        presenceTimer?.invalidate()
        presenceTimer = nil
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if archiveShown { setArchiveShown(false) }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        archiveButton.frame = CGRect(x: 12, y: -archiveHeight, width: tableView.bounds.width - 24, height: 48)
        archiveButton.isHidden = archiveHeight == 0
    }

    override func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        if !isEditing && scrollView.contentOffset.y < -scrollView.adjustedContentInset.top - 64 { setArchiveShown(true) }
    }

    private func setArchiveShown(_ on: Bool) {
        let oldHeight = tableView.contentInset.top
        archiveShown = on && query.isEmpty && filter == 0 && !archived.isEmpty
        let height = archiveHeight
        guard oldHeight != height else { return }
        archiveButton.setTitle("  Архив · \(archived.count)", for: .normal)
        archiveButton.isHidden = height == 0
        let offset = tableView.contentOffset.y - (height - oldHeight)
        Theme.animate(0.22) {
            self.tableView.contentInset.top = height
            self.tableView.contentOffset.y = offset
            self.view.setNeedsLayout()
            self.view.layoutIfNeeded()
        }
        navigationItem.leftBarButtonItem = archiveShown ? UIBarButtonItem(image: UIImage(systemName: "chevron.left"), primaryAction: UIAction { [weak self] _ in self?.setArchiveShown(false) }) : logoItem
        if archiveShown { navigationItem.leftBarButtonItem?.accessibilityLabel = "Скрыть архив" }
    }

    @objc private func hideArchiveBySwipe(_ gesture: UIScreenEdgePanGestureRecognizer) {
        if !isEditing && archiveShown && gesture.state == .ended && gesture.translation(in: view).x > 44 { setArchiveShown(false) }
    }

    @objc private func revealArchiveForAccessibility() -> Bool { setArchiveShown(true); return !archived.isEmpty }

    /// Online dots for direct chats, once a minute while the list is visible.
    private func refreshPresence() {
        let ids = Set((all + archived).filter { $0.str("kind") == "direct" }.map { $0.str("peer_user_id") }.filter { !$0.isEmpty })
        guard !ids.isEmpty else { return }
        Core.shared.call("presence", ["user_ids": Array(ids.prefix(200)), "tz_offset": tzOffsetMinutes]) { [weak self] r in
            if case .success = r { self?.reload() }
        }
    }

    override func reacts(to t: String) -> Bool { ["conversations_changed", "contacts_changed", "identity_changed", "messages_changed", "sync_reset", "connection", "presence"].contains(t) }

    override func reload() {
        let q = query
        Core.shared.call("list_conversations", ["query": q, "sort": Prefs.chatSort]) { [weak self] r in
            guard let self, q == self.query, case .success(let v) = r else { return }
            let list = v as? [JSON] ?? []
            guard q.isEmpty else {
                self.all = list
                self.applyFilter()
                return
            }
            Core.shared.call("list_conversations", ["archived": true, "sort": "activity"]) { ar in
                guard q == self.query else { return }
                if case .success(let av) = ar { self.archived = av as? [JSON] ?? [] }
                self.all = list
                self.applyFilter()
            }
        }
    }

    private func applyFilter() {
        let rows = all.filter { filter == 0 || (filter == 1 ? $0.str("kind") == "direct" : ["group", "channel"].contains($0.str("kind"))) }
        if !query.isEmpty || filter != 0 || archived.isEmpty { setArchiveShown(false) }
        apply(rows, empty: all.isEmpty && query.isEmpty ? "Чатов пока нет. Начните переписку с человеком по его имени пользователя." : "Ничего не нашли")
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: RowCell.id, for: indexPath) as! RowCell
        let c = rows[indexPath.row]
        bindChatCell(cell, c)
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        if isEditing { selectionChanged(); return }
        tableView.deselectRow(at: indexPath, animated: true)
        let c = rows[indexPath.row]
        navigationController?.pushViewController(ChatViewController(conversationId: c.str("id")), animated: !Theme.reducedMotion)
    }

    override func tableView(_ tableView: UITableView, trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        chatSwipe(self, rows[indexPath.row], archive: false)
    }

    override func tableView(_ tableView: UITableView, leadingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        pinSwipe(self, rows[indexPath.row])
    }
}

extension ChatsViewController: UISearchBarDelegate {
    func searchBar(_ searchBar: UISearchBar, selectedScopeButtonIndexDidChange selectedScope: Int) {
        filter = selectedScope
        applyFilter()
    }
}

final class ContactsViewController: ListViewController {
    private var sort = "name-asc"
    private let sorts = [("name-asc", "Имя А–Я"), ("name-desc", "Имя Я–А"), ("recent", "Дата добавления")]

    override func viewDidLoad() {
        super.viewDidLoad()
        search.searchBar.placeholder = "Поиск контактов"
        navigationItem.rightBarButtonItems = [
            UIBarButtonItem(image: UIImage(systemName: "person.badge.plus"), primaryAction: UIAction { [weak self] _ in
                self?.presentSheet(NewContactViewController())
            }),
            UIBarButtonItem(image: UIImage(systemName: "qrcode.viewfinder"), primaryAction: UIAction { [weak self] _ in
                guard let self else { return }
                ContactQr.scan(from: self)
            }),
            UIBarButtonItem(image: UIImage(systemName: "phone.arrow.up.right"), primaryAction: UIAction { [weak self] _ in
                self?.presentSheet(RecentCallsViewController())
            }),
        ]
        navigationItem.rightBarButtonItems?[0].accessibilityLabel = "Новый контакт"
        navigationItem.rightBarButtonItems?[1].accessibilityLabel = "Добавить по QR-коду"
        navigationItem.rightBarButtonItems?[2].accessibilityLabel = "Недавние звонки"
        updateSortMenu()
    }

    private func updateSortMenu() {
        let items = sorts.map { k, name in
            UIAction(title: name, state: k == sort ? .on : .off) { [weak self] _ in
                self?.sort = k
                self?.updateSortMenu()
                self?.reload()
            }
        }
        navigationItem.leftBarButtonItem = UIBarButtonItem(title: sorts.first { $0.0 == sort }?.1, menu: UIMenu(title: "Сортировать по", children: items))
        navigationItem.leftBarButtonItem?.accessibilityLabel = "Сортировать по"
    }

    override func reacts(to t: String) -> Bool { ["contacts_changed", "identity_changed"].contains(t) }

    override func reload() {
        let q = query
        Core.shared.call("list_contacts", ["query": q, "sort": sort]) { [weak self] r in
            guard let self, q == self.query, case .success(let v) = r else { return }
            self.apply(v as? [JSON] ?? [], empty: q.isEmpty ? "Контактов пока нет. Добавьте человека по его точному имени пользователя." : "Никого не нашли по этому запросу.")
        }
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: RowCell.id, for: indexPath) as! RowCell
        let c = rows[indexPath.row]
        cell.avatar.set(c.str("display_name"), seed: c.str("user_id"), avatar: c.str("avatar_path"))
        cell.title.text = c.str("display_name")
        cell.time.text = ""
        cell.badge.set(0)
        if c.bool("key_changed") {
            cell.subtitle.text = "Ключ изменился — сверьте код"
            cell.subtitle.textColor = Theme.warning
        } else {
            cell.subtitle.text = "@" + c.str("username")
            cell.subtitle.textColor = Theme.muted
        }
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        Core.shared.call("open_direct", ["user_id": rows[indexPath.row].str("user_id")]) { [weak self] r in
            switch r {
            case .failure(let e): self?.toast(e.message)
            case .success(let v): (self?.tabBarController as? MainTabController)?.open((v as? JSON)?.str("id") ?? "")
            }
        }
    }
}

final class ProfileViewController: UIViewController, PHPickerViewControllerDelegate {
    private let avatar = AvatarView(size: 88)
    private let name = label("", 24, .bold)
    private let sub = label("", 15, color: Theme.muted)
    private let bio = label("", 15)
    private var hasAvatar = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        avatar.isUserInteractionEnabled = true
        avatar.isAccessibilityElement = true
        avatar.accessibilityLabel = "Сменить фото профиля"
        avatar.accessibilityTraits = .button
        avatar.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(changeAvatar)))
        name.textAlignment = .center
        sub.textAlignment = .center
        bio.textAlignment = .center
        bio.isHidden = true
        let card = UIStackView(arrangedSubviews: [
            row("camera", "Сменить фото профиля") { [weak self] in self?.changeAvatar() },
            row("pencil", "Имя и описание") { [weak self] in self?.presentSheet(EditProfileViewController()) },
            row("qrcode", "Мой QR-код") { [weak self] in self?.presentSheet(MyQrViewController()) },
            row("laptopcomputer.and.iphone", "Устройства") { [weak self] in self?.presentSheet(DevicesViewController()) },
            row("gearshape", "Настройки") { [weak self] in self?.presentSheet(SettingsViewController()) },
        ])
        card.axis = .vertical
        card.glass(25)
        card.isLayoutMarginsRelativeArrangement = true
        card.layoutMargins = UIEdgeInsets(top: 6, left: 6, bottom: 6, right: 6)
        let stack = UIStackView(arrangedSubviews: [avatar, name, sub, bio, card])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 10
        stack.setCustomSpacing(26, after: bio)
        stack.translatesAutoresizingMaskIntoConstraints = false
        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)
        scroll.addSubview(stack)
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 20),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -24),
            stack.leadingAnchor.constraint(equalTo: scroll.frameLayoutGuide.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: scroll.frameLayoutGuide.trailingAnchor, constant: -16),
            card.widthAnchor.constraint(equalTo: stack.widthAnchor),
        ])
        NotificationCenter.default.addObserver(forName: .prefsChanged, object: nil, queue: .main) { [weak self] _ in self?.reload() }
        reload()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        reload()
    }

    private func reload() {
        Core.shared.call("status") { [weak self] r in
            guard let self, case .success(let v) = r, let s = v as? JSON else { return }
            let display = s.str("display_name").isEmpty ? s.str("username") : s.str("display_name")
            self.avatar.set(display, seed: s.str("user_id"), avatar: s.str("avatar_path"))
            self.hasAvatar = !s.str("avatar_path").isEmpty
            self.name.text = display
            self.sub.text = "@" + s.str("username") + (s.str("number").isEmpty ? "" : " · ID " + s.str("number"))
            self.bio.text = s.str("bio")
            self.bio.isHidden = s.str("bio").isEmpty
        }
    }

    private func row(_ icon: String, _ text: String, _ action: @escaping () -> Void) -> UIView {
        var cfg = UIButton.Configuration.plain()
        cfg.image = UIImage(systemName: icon)
        cfg.imagePadding = 16
        cfg.title = text
        cfg.baseForegroundColor = Theme.text
        cfg.contentInsets = NSDirectionalEdgeInsets(top: 16, leading: 14, bottom: 16, trailing: 14)
        let b = UIButton(configuration: cfg, primaryAction: UIAction { _ in action() })
        b.contentHorizontalAlignment = .leading
        return b
    }

    @objc private func changeAvatar() {
        let menu = UIAlertController(title: "Фото профиля", message: nil, preferredStyle: .actionSheet)
        menu.addAction(UIAlertAction(title: "Выбрать фото", style: .default) { [weak self] _ in
            var cfg = PHPickerConfiguration()
            cfg.filter = .images
            cfg.selectionLimit = 1
            let picker = PHPickerViewController(configuration: cfg)
            picker.delegate = self
            self?.present(picker, animated: !Theme.reducedMotion)
        })
        if hasAvatar { menu.addAction(UIAlertAction(title: "Удалить фото", style: .destructive) { [weak self] _ in self?.saveAvatar("") }) }
        menu.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        menu.popoverPresentationController?.sourceView = avatar
        present(menu, animated: !Theme.reducedMotion)
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: !Theme.reducedMotion)
        guard let provider = results.first?.itemProvider else { return }
        provider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { [weak self] url, _ in
            guard let url, let image = Photos.decode(url, maxSide: 512) else {
                DispatchQueue.main.async { self?.toast("Не удалось прочитать фото") }
                return
            }
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            format.opaque = true
            let square = UIGraphicsImageRenderer(size: CGSize(width: 256, height: 256), format: format).image { ctx in
                UIColor.white.setFill()
                ctx.fill(CGRect(x: 0, y: 0, width: 256, height: 256))
                let scale = 256 / min(image.size.width, image.size.height)
                let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
                image.draw(in: CGRect(x: (256 - size.width) / 2, y: (256 - size.height) / 2, width: size.width, height: size.height))
            }
            guard let data = square.jpegData(compressionQuality: 0.75), data.count <= 49152 else {
                DispatchQueue.main.async { self?.toast("Не удалось подготовить фото") }
                return
            }
            let file = FileManager.default.temporaryDirectory.appendingPathComponent("avatar-\(UUID().uuidString).jpg")
            do { try data.write(to: file, options: .atomic) }
            catch { DispatchQueue.main.async { self?.toast("Не удалось подготовить фото") }; return }
            DispatchQueue.main.async {
                guard let self else { try? FileManager.default.removeItem(at: file); return }
                self.saveAvatar(file.path)
            }
        }
    }

    private func saveAvatar(_ path: String) {
        Core.shared.call("set_avatar", ["path": path]) { [weak self] r in
            if !path.isEmpty { try? FileManager.default.removeItem(atPath: path) }
            switch r {
            case .failure(let e): self?.toast(e.message)
            case .success: self?.reload()
            }
        }
    }
}
