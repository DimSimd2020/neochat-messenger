import UIKit

/// Chats, contacts and profile behind the system tab bar.
final class MainTabController: UITabBarController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        let chats = nav(ChatsViewController(), "Чаты", "bubble.left.and.bubble.right")
        let contacts = nav(ContactsViewController(), "Контакты", "person.2")
        let profile = nav(ProfileViewController(), "Профиль", "person.crop.circle")
        viewControllers = [chats, contacts, profile]
        let a = UITabBarAppearance()
        a.configureWithDefaultBackground()
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
        let n = UINavigationController(rootViewController: vc)
        n.navigationBar.prefersLargeTitles = true
        n.tabBarItem = UITabBarItem(title: title, image: UIImage(systemName: icon), tag: 0)
        let a = UINavigationBarAppearance()
        a.configureWithTransparentBackground()
        a.largeTitleTextAttributes = [.foregroundColor: Theme.text]
        a.titleTextAttributes = [.foregroundColor: Theme.text]
        n.navigationBar.standardAppearance = {
            let s = UINavigationBarAppearance()
            s.configureWithDefaultBackground()
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
        let bottom = UIStackView(arrangedSubviews: [subtitle, badge])
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
        tableView.backgroundColor = .clear
        tableView.separatorStyle = .none
        tableView.register(RowCell.self, forCellReuseIdentifier: RowCell.id)
        search.searchResultsUpdater = self
        search.obscuresBackgroundDuringPresentation = false
        navigationItem.searchController = search
        navigationItem.hidesSearchBarWhenScrolling = false
        definesPresentationContext = true
        NotificationCenter.default.addObserver(forName: .coreEvent, object: nil, queue: .main) { [weak self] n in
            guard let self, let t = (n.userInfo as? JSON)?.str("type"), self.reacts(to: t) else { return }
            if self.refreshPending { return }
            self.refreshPending = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.12) {
                self.refreshPending = false
                self.reload()
            }
        }
        reload()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        reload()
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
            let l = label(empty, 15, color: Theme.muted)
            l.textAlignment = .center
            tableView.backgroundView = l
        } else {
            tableView.backgroundView = nil
        }
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { rows.count }
}

final class ChatsViewController: ListViewController {
    private var filter = 0
    private var all: [JSON] = []

    override func viewDidLoad() {
        super.viewDidLoad()
        search.searchBar.placeholder = "Поиск чатов"
        search.searchBar.scopeButtonTitles = ["Все", "Личные", "Группы"]
        search.searchBar.showsScopeBar = true
        search.searchBar.delegate = self
        navigationItem.rightBarButtonItem = UIBarButtonItem(image: UIImage(systemName: "square.and.pencil"), primaryAction: UIAction { [weak self] _ in
            self?.presentSheet(NewChatViewController())
        })
        navigationItem.rightBarButtonItem?.accessibilityLabel = "Новый чат"
        let logo = UIImageView(image: UIImage(named: "Logo"))
        logo.frame = CGRect(x: 0, y: 0, width: 28, height: 28)
        navigationItem.leftBarButtonItem = UIBarButtonItem(customView: logo)
        title = "NeoChat"
    }

    override func reacts(to t: String) -> Bool { ["conversations_changed", "contacts_changed", "identity_changed", "messages_changed", "sync_reset", "connection"].contains(t) }

    override func reload() {
        let q = query
        Core.shared.call("list_conversations", ["query": q]) { [weak self] r in
            guard let self, q == self.query, case .success(let v) = r else { return }
            self.all = v as? [JSON] ?? []
            self.applyFilter()
        }
    }

    private func applyFilter() {
        let rows = all.filter { filter == 0 || (filter == 1 ? $0.str("kind") == "direct" : ["group", "channel"].contains($0.str("kind"))) }
        apply(rows, empty: all.isEmpty && query.isEmpty ? "Чатов пока нет. Начните переписку с человеком по его имени пользователя." : "Ничего не нашли")
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: RowCell.id, for: indexPath) as! RowCell
        let c = rows[indexPath.row]
        cell.avatar.set(c.str("title"), seed: c.str("peer_user_id").isEmpty ? c.str("id") : c.str("peer_user_id"))
        cell.title.text = c.str("title")
        cell.time.text = formatListTime(c.int64("last_activity"))
        cell.badge.set(c.int("unread"))
        var preview = c.str("last_preview").replacingOccurrences(of: "\n", with: " ")
        var color = Theme.muted
        let draft = c.str("draft").trimmingCharacters(in: .whitespacesAndNewlines)
        if !draft.isEmpty { preview = "Черновик: " + draft; color = Theme.accent }
        if !c.arr("key_changed_users").isEmpty || !(c["key_changed_users"] as? [Any] ?? []).isEmpty { preview = "Ключ собеседника изменился"; color = Theme.warning }
        cell.subtitle.text = preview
        cell.subtitle.textColor = color
        cell.accessibilityLabel = [c.str("title"), c.int("unread") > 0 ? "непрочитанных: \(c.int("unread"))" : "", preview, cell.time.text ?? ""].filter { !$0.isEmpty }.joined(separator: ", ")
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        navigationController?.pushViewController(ChatViewController(conversationId: rows[indexPath.row].str("id")), animated: !Theme.reducedMotion)
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
            UIBarButtonItem(image: UIImage(systemName: "phone.arrow.up.right"), primaryAction: UIAction { [weak self] _ in
                self?.presentSheet(RecentCallsViewController())
            }),
        ]
        navigationItem.rightBarButtonItems?[0].accessibilityLabel = "Новый контакт"
        navigationItem.rightBarButtonItems?[1].accessibilityLabel = "Недавние звонки"
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
        cell.avatar.set(c.str("display_name"), seed: c.str("user_id"))
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

final class ProfileViewController: UIViewController {
    private let avatar = AvatarView(size: 88)
    private let name = label("", 24, .bold)
    private let sub = label("", 15, color: Theme.muted)

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        name.textAlignment = .center
        sub.textAlignment = .center
        let card = UIStackView(arrangedSubviews: [
            row("pencil", "Изменить имя") { [weak self] in self?.editName() },
            row("laptopcomputer.and.iphone", "Устройства") { [weak self] in self?.presentSheet(DevicesViewController()) },
            row("gearshape", "Настройки") { [weak self] in self?.presentSheet(SettingsViewController()) },
        ])
        card.axis = .vertical
        card.glass(25)
        card.isLayoutMarginsRelativeArrangement = true
        card.layoutMargins = UIEdgeInsets(top: 6, left: 6, bottom: 6, right: 6)
        let stack = UIStackView(arrangedSubviews: [avatar, name, sub, card])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 10
        stack.setCustomSpacing(26, after: sub)
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20),
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            card.widthAnchor.constraint(equalTo: stack.widthAnchor),
        ])
        Core.shared.call("status") { [weak self] r in
            guard case .success(let v) = r, let s = v as? JSON else { return }
            let display = s.str("display_name").isEmpty ? s.str("username") : s.str("display_name")
            self?.avatar.set(display, seed: s.str("user_id"))
            self?.name.text = display
            self?.sub.text = "@" + s.str("username") + (s.str("number").isEmpty ? "" : " · ID " + s.str("number"))
        }
    }

    private func editName() {
        let a = UIAlertController(title: "Имя", message: "Имя видят ваши собеседники. Никнейм и ID не меняются.", preferredStyle: .alert)
        a.addTextField { [weak self] f in
            f.text = self?.name.text
            f.autocapitalizationType = .words
        }
        a.addAction(UIAlertAction(title: "Отмена", style: .cancel))
        a.addAction(UIAlertAction(title: "Сохранить", style: .default) { [weak self, weak a] _ in
            let n = (a?.textFields?.first?.text ?? "").trimmingCharacters(in: .whitespaces)
            guard !n.isEmpty else { self?.toast("Введите имя"); return }
            Core.shared.call("set_profile", ["display_name": n]) { r in
                switch r {
                case .failure(let e): self?.toast(e.message)
                case .success: self?.name.text = n
                }
            }
        })
        present(a, animated: !Theme.reducedMotion)
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
}
