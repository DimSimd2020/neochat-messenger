import UIKit

/// Navigation with a guarded swipe-back: the gesture may only start on a real
/// stacked screen and never while another transition is still running.
final class ChatNavController: UINavigationController, UIGestureRecognizerDelegate, UINavigationControllerDelegate {
    override func viewDidLoad() {
        super.viewDidLoad()
        interactivePopGestureRecognizer?.delegate = self
        delegate = self
    }

    // Leaving a chat slides the transparent tab bar back in. In the light theme its glass
    // picks up the mint spot and sits over the list, blocking taps, until the transition ends.
    func navigationController(_ nc: UINavigationController, willShow vc: UIViewController, animated: Bool) {
        guard let bar = tabBarController?.tabBar else { return }
        let returning = animated && !vc.hidesBottomBarWhenPushed && nc.transitionCoordinator?.viewController(forKey: .from)?.hidesBottomBarWhenPushed == true
        guard returning, let coordinator = nc.transitionCoordinator else {
            setBar(bar, shown: !vc.hidesBottomBarWhenPushed)
            return
        }
        setBar(bar, shown: false)
        coordinator.animate(alongsideTransition: nil) { ctx in
            self.setBar(bar, shown: !ctx.isCancelled)
        }
    }

    func navigationController(_ nc: UINavigationController, didShow vc: UIViewController, animated: Bool) {
        guard let bar = tabBarController?.tabBar else { return }
        setBar(bar, shown: !vc.hidesBottomBarWhenPushed)
    }

    private func setBar(_ bar: UITabBar, shown: Bool) {
        UIView.setAnimationsEnabled(false)
        bar.alpha = shown ? 1 : 0
        bar.isUserInteractionEnabled = shown
        UIView.setAnimationsEnabled(true)
    }

    func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
        g !== interactivePopGestureRecognizer || (viewControllers.count > 1 && transitionCoordinator == nil)
    }
}

/// Chats, contacts and profile behind a floating glass tab bar with a lens, as on Android.
final class MainTabController: UITabBarController, UITabBarControllerDelegate, UIGestureRecognizerDelegate {
    private let lens = LensTabBar(items: [("Чаты", "bubble.left.and.bubble.right"), ("Контакты", "person.2"), ("Профиль", "person.crop.circle")])
    private var tabHistory: [Int] = []
    private var activeTab = 0

    override var selectedIndex: Int {
        didSet { lens.setSelected(selectedIndex) }
    }

    override var selectedViewController: UIViewController? {
        didSet { if let i = viewControllers?.firstIndex(where: { $0 === selectedViewController }) { lens.setSelected(i) } }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if lens.superview == nil {
            lens.onSelect = { [weak self] i in self?.selectFromBar(i) }
            lens.onReselect = { [weak self] in
                (self?.selectedViewController as? UINavigationController)?.popToRootViewController(animated: !Theme.reducedMotion)
            }
            tabBar.addSubview(lens)
        }
        // During the pop the tab bar's height is briefly the whole screen. An animated bar of
        // that size is the green slab at the bottom of the chat list.
        let contentH = tabBar.bounds.height - tabBar.safeAreaInsets.bottom
        let h = min(56, max(0, contentH))
        let bar = CGRect(x: 12, y: -6, width: max(0, tabBar.bounds.width - 24), height: h + 10)
        UIView.performWithoutAnimation {
            lens.frame = bar.insetBy(dx: -LensTabBar.room.width, dy: -LensTabBar.room.height)
        }
        // Above the system items: the lens takes the touches, the transparent items keep VoiceOver.
        tabBar.bringSubviewToFront(lens)
    }

    private func selectFromBar(_ i: Int) {
        guard let vcs = viewControllers, vcs.indices.contains(i) else { return }
        let change = {
            self.selectedIndex = i
            self.tabBarController(self, didSelect: vcs[i])
        }
        guard let container = selectedViewController?.view.superview, !Theme.reducedMotion else { return change() }
        UIView.transition(with: container, duration: 0.2, options: [.transitionCrossDissolve, .allowUserInteraction], animations: change)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        let chats = nav(ChatsViewController(), "Чаты", "bubble.left.and.bubble.right")
        let contacts = nav(ContactsViewController(), "Контакты", "person.2")
        let profile = nav(ProfileViewController(), "Профиль", "person.crop.circle")
        viewControllers = [chats, contacts, profile]
        delegate = self
        let back = UIScreenEdgePanGestureRecognizer(target:self,action:#selector(rootBack(_:)))
        back.edges = .left; back.delegate = self; view.addGestureRecognizer(back)
        let a = UITabBarAppearance()
        a.configureWithTransparentBackground()
        // The lens bar draws the items; the system ones stay for layout and VoiceOver only.
        for layout in [a.stackedLayoutAppearance, a.inlineLayoutAppearance, a.compactInlineLayoutAppearance] {
            for state in [layout.normal, layout.selected] {
                state.iconColor = .clear
                state.titleTextAttributes = [.foregroundColor: UIColor.clear]
            }
        }
        tabBar.standardAppearance = a
        tabBar.scrollEdgeAppearance = a
        tabBar.tintColor = .clear
        tabBar.unselectedItemTintColor = .clear
        NotificationCenter.default.addObserver(forName: .openConversation, object: nil, queue: .main) { [weak self] n in
            guard let id = n.userInfo?["id"] as? String else { return }
            self?.open(id)
        }
    }

    func open(_ conversationId: String) {
        if selectedIndex != 0 { tabHistory.append(selectedIndex) }
        selectedIndex = 0
        activeTab = 0
        guard let nav = viewControllers?.first as? UINavigationController else { return }
        if (nav.topViewController as? ChatViewController)?.conversationId == conversationId { return }
        Core.shared.call("get_conversation",["conversation_id":conversationId]) { r in
            guard case .success(let v) = r, let c = v as? JSON, let root = nav.viewControllers.first else { return }
            var stack: [UIViewController] = [root]
            if c.bool("in_archive") { stack.append(ArchiveViewController()) }
            nav.setViewControllers(stack,animated:false)
            nav.pushViewController(ChatViewController(conversationId:conversationId),animated:!Theme.reducedMotion)
        }
    }

    func tabBarController(_ t: UITabBarController, didSelect vc: UIViewController) {
        if selectedIndex != activeTab { tabHistory.append(activeTab); activeTab = selectedIndex }
    }
    func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
        guard let nav = selectedViewController as? UINavigationController else { return false }
        return nav.viewControllers.count == 1 && nav.transitionCoordinator == nil && (!tabHistory.isEmpty || selectedIndex != 0)
    }
    @objc private func rootBack(_ g: UIScreenEdgePanGestureRecognizer) {
        guard g.state == .ended, g.translation(in:view).x > 64 || g.velocity(in:view).x > 400 else { return }
        let index = tabHistory.popLast() ?? 0
        UIView.transition(with:view,duration:Theme.ms(0.22),options:[.transitionCrossDissolve,.allowUserInteraction]) { self.selectedIndex = index; self.activeTab = index }
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

    private var shownFor = ""
    private var shownCount = 0

    /// `chat` lets a recycled cell tell its own new messages, which make the badge hop, from another chat's count.
    func set(_ n: Int, mentions: Int = 0, chat: String = "") {
        isHidden = n == 0
        text = (mentions > 0 ? "@ " : "") + (n > 99 ? "99+" : "\(n)")
        if n > shownCount && shownFor == chat && !chat.isEmpty { pop(from: 0.55, response: 0.36, damping: 0.45) }
        shownFor = chat
        shownCount = n
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
        var archived: [String] = []
        tableView.isUserInteractionEnabled = false
        navigationItem.rightBarButtonItems?.forEach { $0.isEnabled = false }
        toolbarItems?.forEach { $0.isEnabled = false }
        func next(_ i: Int) {
            if i == chats.count {
                endSelection()
                if method == "set_archived" {
                    replyHaptic()
                    if value { archiveUndo(self, ids: archived) }
                }
                return
            }
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
                if method == "set_archived" && value && !chats[i].bool("in_archive") { archived.append(id) }
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

/// The "Архив" entry above the chat list, as tall as a chat row. Names of archived chats stay out of sight.
final class ArchiveEntryView: UIControl {
    private let icon = ArchiveIconView()
    private let disc = UIView()
    private let title = label("Архив", 17, .semibold)

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = Theme.glass
        layer.cornerRadius = 25
        isAccessibilityElement = true
        accessibilityTraits = .button
        disc.backgroundColor = Theme.accent.withAlphaComponent(0.16)
        disc.layer.cornerRadius = 26
        accessibilityLabel = "Архив"
        for v in [disc, icon, title] as [UIView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            v.isUserInteractionEnabled = false
            addSubview(v)
        }
        NSLayoutConstraint.activate([
            disc.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12),
            disc.centerYAnchor.constraint(equalTo: centerYAnchor),
            disc.widthAnchor.constraint(equalToConstant: 52),
            disc.heightAnchor.constraint(equalToConstant: 52),
            icon.centerXAnchor.constraint(equalTo: disc.centerXAnchor),
            icon.centerYAnchor.constraint(equalTo: disc.centerYAnchor),
            icon.widthAnchor.constraint(equalToConstant: 26),
            icon.heightAnchor.constraint(equalToConstant: 26),
            title.leadingAnchor.constraint(equalTo: disc.trailingAnchor, constant: 14),
            title.centerYAnchor.constraint(equalTo: centerYAnchor),
            title.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -16),
        ])
    }

    required init?(coder: NSCoder) { fatalError() }

    override var isHighlighted: Bool {
        didSet { alpha = isHighlighted ? 0.7 : 1 }
    }

    func setPullProgress(_ progress: CGFloat) { icon.progress = progress }
}

final class ChatsViewController: ChatListViewController {
    private var filter = 0
    private var all: [JSON] = []
    private var archived: [JSON] = []
    private var presenceTimer: Timer?
    private var archiveShown = false
    private var archivePullArmed = false
    private let archiveButton = ArchiveEntryView()
    private var offsetObserver: NSKeyValueObservation?
    private var logoItem: UIBarButtonItem?
    // A chat row (72) and the gap under the entry.
    private var archiveHeight: CGFloat { archiveShown && query.isEmpty && filter == 0 && !archived.isEmpty ? 80 : 0 }

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
        archiveButton.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            AppLock.unlockArchive(from: self) { [weak self] in self?.navigationController?.pushViewController(ArchiveViewController(), animated: !Theme.reducedMotion) }
        }, for: .touchUpInside)
        tableView.addSubview(archiveButton)
        tableView.alwaysBounceVertical = true
        // The entry belongs to the very top of the list: once the first chat has moved up past the bar, it goes away.
        offsetObserver = tableView.observe(\.contentOffset, options: []) { [weak self] tv, _ in
            self?.updateArchivePull()
        }
        let back = UIScreenEdgePanGestureRecognizer(target: self, action: #selector(hideArchiveBySwipe(_:)))
        back.edges = .left
        tableView.addGestureRecognizer(back)
        tableView.accessibilityCustomActions = [UIAccessibilityCustomAction(name: "Показать архив", target: self, selector: #selector(revealArchiveForAccessibility))]
    }

    private func updateBarItems() {
        let compose = UIBarButtonItem(image: UIImage(systemName: "square.and.pencil"), primaryAction: UIAction { [weak self] action in
            (action.sender as? UIBarButtonItem)?.play(.wiggle)
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
        archiveButton.frame = CGRect(x: 12, y: -80, width: tableView.bounds.width - 24, height: 72)
        updateArchivePull()
    }

    override func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        if archivePullArmed { setArchiveShown(true) }
        archivePullArmed = false
    }

    private func updateArchivePull() {
        let available = !isEditing && query.isEmpty && filter == 0 && !archived.isEmpty
        let base = tableView.adjustedContentInset.top - tableView.contentInset.top
        let exposed = max(0, -tableView.contentOffset.y - base)
        archiveButton.isHidden = !available || exposed <= 0
        archiveButton.setPullProgress(exposed / 80)
        if available && tableView.isDragging && !archiveShown {
            if !archivePullArmed && exposed >= 68 { archivePullArmed = true; replyHaptic() }
            else if exposed < 56 { archivePullArmed = false }
        }
        if archiveShown && tableView.contentOffset.y > -base + 24 { setArchiveShown(false) }
    }

    private func setArchiveShown(_ on: Bool) {
        let oldHeight = tableView.contentInset.top
        archiveShown = on && query.isEmpty && filter == 0 && !archived.isEmpty
        let height = archiveHeight
        guard oldHeight != height else { return }
        if height > 0 { archiveButton.isHidden = false }
        // Where the first chat rests under the bar without the entry.
        let base = tableView.adjustedContentInset.top - oldHeight
        let scrolledAway = height == 0 && tableView.contentOffset.y > -base
        let settle = {
            self.tableView.contentInset.top = height
            // Leave the offset alone when the entry is already out of sight, so a fling is not cut short.
            if !scrolledAway { self.tableView.contentOffset.y = -(base + height) }
            self.view.setNeedsLayout()
            self.view.layoutIfNeeded()
        }
        if scrolledAway { settle() } else { Theme.animate(0.22, settle) }
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
        Haptics.select()
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

final class ProfileViewController: PersonProfileViewController {}
