import BackgroundTasks
import UIKit
import UserNotifications

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    static let refreshTask = "app.neochat.refresh"

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        try? Core.shared.open()
        UNUserNotificationCenter.current().delegate = self
        NotificationCenter.default.addObserver(forName: .coreEvent, object: nil, queue: .main) { n in
            guard let ev = n.userInfo as? JSON, ev.str("type") == "notify" else { return }
            Notifier.post(ev)
        }
        // Without a signed push entitlement the app fetches in the background when iOS allows it.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.refreshTask, using: nil) { task in
            Self.schedule()
            let work = DispatchWorkItem {
                _ = Core.shared.callBlocking("sync_now")
                task.setTaskCompleted(success: true)
            }
            task.expirationHandler = { work.cancel() }
            DispatchQueue.global().async(execute: work)
        }
        return true
    }

    static func schedule() {
        let req = BGAppRefreshTaskRequest(identifier: refreshTask)
        req.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(req)
    }

    func application(_ application: UIApplication, configurationForConnecting session: UISceneSession, options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        UISceneConfiguration(name: "Default", sessionRole: session.role)
    }
}

extension AppDelegate: UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler done: @escaping () -> Void) {
        if let cid = response.notification.request.content.userInfo["conversation_id"] as? String {
            NotificationCenter.default.post(name: .openConversation, object: nil, userInfo: ["id": cid])
        }
        done()
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent n: UNNotification, withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
        done([.banner, .sound])
    }
}

extension Notification.Name {
    static let openConversation = Notification.Name("NeoChatOpenConversation")
}

/// Local notifications show the conversation title only, never message text.
enum Notifier {
    static var visibleConversation: String?

    static func post(_ ev: JSON) {
        let cid = ev.str("conversation_id")
        if UIApplication.shared.applicationState == .active && cid == visibleConversation { return }
        if !(UserDefaults.standard.object(forKey: "notifications") as? Bool ?? true) { return }
        let c = UNMutableNotificationContent()
        c.title = ev.str("title").isEmpty ? "NeoChat" : ev.str("title")
        c.body = "Новое сообщение"
        c.sound = .default
        c.userInfo = ["conversation_id": cid]
        c.threadIdentifier = cid
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: cid, content: c, trigger: nil))
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options: UIScene.ConnectionOptions) {
        guard let ws = scene as? UIWindowScene else { return }
        let w = UIWindow(windowScene: ws)
        window = w
        Theme.apply(to: w, animated: false)
        w.rootViewController = RootViewController()
        w.makeKeyAndVisible()
    }

    func sceneDidBecomeActive(_ scene: UIScene) {
        Core.shared.call("set_foreground", ["active": true])
    }

    func sceneDidEnterBackground(_ scene: UIScene) {
        Core.shared.call("set_foreground", ["active": false])
        AppDelegate.schedule()
    }
}

/// Switches between onboarding and the main tabs.
final class RootViewController: UIViewController {
    private var current: UIViewController?
    private var updateBanner: UIView?
    private var lastUpdateCheck = Date.distantPast

    override func viewDidLoad() {
        super.viewDidLoad()
        view.addSubview(BackgroundView(frame: view.bounds))
        NotificationCenter.default.addObserver(forName: .coreEvent, object: nil, queue: .main) { [weak self] n in
            guard let ev = n.userInfo as? JSON else { return }
            let t = ev.str("type")
            if t == "auth_required" || t == "device_revoked", self?.current is MainTabController {
                if t == "device_revoked" { self?.toast("Это устройство отозвано. Новые сообщения сюда не придут") }
                self?.start()
            } else if t == "warning" {
                self?.toast(ev.str("message"))
            }
        }
        NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            self?.checkUpdate()
        }
        start()
    }

    /// iOS cannot install an unsigned build itself: the banner opens the IPA link in Safari
    /// so it can be installed with Scarlet. Checked at most every 6 hours.
    private func checkUpdate() {
        guard updateBanner == nil, Date().timeIntervalSince(lastUpdateCheck) > 6 * 3600,
              let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String else { return }
        lastUpdateCheck = Date()
        Core.shared.call("check_update", ["platform": "ios", "version": version]) { [weak self] r in
            guard case .success(let v) = r, let o = v as? JSON, o.bool("available"), let url = URL(string: o.str("url")) else { return }
            self?.showUpdateBanner(o.str("version"), url)
        }
    }

    private func showUpdateBanner(_ version: String, _ url: URL) {
        guard updateBanner == nil else { return }
        let bar = UIView()
        bar.glass(20, strong: true)
        let text = label("Доступна версия \(version)", 15, .semibold)
        let open = PillButton("Скачать")
        open.addAction(UIAction { _ in UIApplication.shared.open(url) }, for: .touchUpInside)
        let row = UIStackView(arrangedSubviews: [text, open])
        row.alignment = .center
        row.spacing = 12
        row.translatesAutoresizingMaskIntoConstraints = false
        bar.addSubview(row)
        bar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bar)
        NSLayoutConstraint.activate([
            row.leadingAnchor.constraint(equalTo: bar.leadingAnchor, constant: 18),
            row.trailingAnchor.constraint(equalTo: bar.trailingAnchor, constant: -8),
            row.topAnchor.constraint(equalTo: bar.topAnchor, constant: 8),
            row.bottomAnchor.constraint(equalTo: bar.bottomAnchor, constant: -8),
            bar.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 12),
            bar.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -12),
            bar.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
        ])
        updateBanner = bar
        UIAccessibility.post(notification: .announcement, argument: "Доступна версия \(version)")
        bar.alpha = 0
        UIView.animate(withDuration: Theme.ms(0.22)) { bar.alpha = 1 }
    }

    func start() {
        Core.shared.call("status") { [weak self] r in
            guard let self else { return }
            switch r {
            case .success(let v):
                let s = v as? JSON ?? [:]
                if s.bool("logged_in") { self.showMain() } else { self.show(OnboardingViewController(status: s) { [weak self] in self?.showMain() }) }
            case .failure(let e):
                self.toast(e.message)
            }
        }
    }

    func showMain() {
        show(MainTabController())
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    private func show(_ vc: UIViewController) {
        let old = current
        addChild(vc)
        vc.view.frame = view.bounds
        vc.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(vc.view)
        vc.didMove(toParent: self)
        current = vc
        if let b = updateBanner { view.bringSubviewToFront(b) }
        vc.view.alpha = 0
        UIView.animate(withDuration: Theme.ms(0.22), animations: { vc.view.alpha = 1 }) { _ in
            old?.willMove(toParent: nil)
            old?.view.removeFromSuperview()
            old?.removeFromParent()
        }
    }
}
