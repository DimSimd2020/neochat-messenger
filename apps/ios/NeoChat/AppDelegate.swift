import BackgroundTasks
import AVFoundation
import UIKit
import UserNotifications

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    static let refreshTask = "app.neochat.refresh"

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        try? Core.shared.open()
        UNUserNotificationCenter.current().delegate = self
        _ = CallManager.shared
        QuickReaction.start()
        NotificationCenter.default.addObserver(forName: .coreEvent, object: nil, queue: .main) { n in
            guard let ev = n.userInfo as? JSON else { return }
            switch ev.str("type") {
            case "account_notify": Notifier.post(ev)
            case "notify":
                Typing.clear(ev.str("conversation_id"))
                Notifier.post(ev)
            case "typing": Typing.onEvent(ev)
            default: break
            }
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
        if response.notification.request.content.userInfo["call"] != nil {
            CallManager.shared.resume()
        } else if let cid = response.notification.request.content.userInfo["conversation_id"] as? String {
            NotificationCenter.default.post(name: .openAccountConversation, object: nil, userInfo: ["id": cid, "account_id": response.notification.request.content.userInfo["account_id"] as? String ?? ""])
        }
        done()
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent n: UNNotification, withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
        done([.banner, .sound])
    }
}

extension Notification.Name {
    static let openConversation = Notification.Name("NeoChatOpenConversation")
    static let openAccountConversation = Notification.Name("NeoChatOpenAccountConversation")
}

/// Local notifications show the conversation title only, never message text.
enum Notifier {
    static var visibleConversation: String?

    static func post(_ ev: JSON) {
        let cid = ev.str("conversation_id")
        let account = ev.str("account_id")
        if UIApplication.shared.applicationState == .active && account == Core.shared.accountId && cid == visibleConversation { return }
        if !(UserDefaults.standard.object(forKey: "notifications") as? Bool ?? true) { return }
        let c = UNMutableNotificationContent()
        c.title = ev.str("title").isEmpty ? "NeoChat" : ev.str("title")
        c.body = ev.str("text").isEmpty ? "Новое сообщение" : ev.str("text")
        c.sound = .default
        c.userInfo = ["conversation_id": cid, "account_id": account]
        c.threadIdentifier = account + ":" + cid
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: account + ":" + cid, content: c, trigger: nil))
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
        AppLock.launched(ws)
    }

    func sceneWillEnterForeground(_ scene: UIScene) {
        if let ws = scene as? UIWindowScene { AppLock.foreground(ws) }
    }

    func sceneWillResignActive(_ scene: UIScene) {
        if let ws = scene as? UIWindowScene { AppLock.resigned(ws) }
    }

    func sceneDidBecomeActive(_ scene: UIScene) {
        AppLock.activated()
        Core.shared.call("set_foreground", ["active": true])
        CallManager.shared.resume()
    }

    func sceneDidEnterBackground(_ scene: UIScene) {
        Core.shared.call("set_foreground", ["active": false])
        AppDelegate.schedule()
        if let ws = scene as? UIWindowScene { AppLock.backgrounded(ws) }
    }
}

/// Switches between onboarding and the main tabs.
final class RootViewController: UIViewController {
    private var current: UIViewController?
    private var updateBanner: UIView?
    private var dismissedUpdate = ""
    private var lastUpdateCheck = Date.distantPast
    private var pendingConversation: String?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.addSubview(BackgroundView(frame: view.bounds))
        NotificationCenter.default.addObserver(forName: .openAccountConversation, object: nil, queue: .main) { [weak self] n in
            guard let self, let cid = n.userInfo?["id"] as? String else { return }
            let account = n.userInfo?["account_id"] as? String ?? ""
            if account.isEmpty || account == Core.shared.accountId {
                if self.current is MainTabController {
                    NotificationCenter.default.post(name: .openConversation, object: nil, userInfo: ["id": cid])
                } else { self.pendingConversation = cid }
            } else {
                self.pendingConversation = cid
                Core.shared.call("switch_account", ["account_id": account]) { [weak self] result in
                    if case .failure(let e) = result { self?.pendingConversation = nil; self?.topToast(e.message) }
                }
            }
        }
        NotificationCenter.default.addObserver(forName: .coreEvent, object: nil, queue: .main) { [weak self] n in
            guard let ev = n.userInfo as? JSON else { return }
            let t = ev.str("type")
            if t == "account_selected" {
                Notifier.visibleConversation = nil
                Typing.reset()
                if self?.presentedViewController != nil {
                    self?.dismiss(animated: false) { self?.start() }
                } else { self?.start() }
                return
            }
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
    /// so it can be installed with Scarlet. Checked at most hourly, or on request from settings.
    func checkUpdate(manual: Bool = false) {
        guard manual || (updateBanner == nil && Date().timeIntervalSince(lastUpdateCheck) > 3600),
              let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String else { return }
        lastUpdateCheck = Date()
        Core.shared.call("check_update", ["platform": "ios", "version": version, "channel": Prefs.updateChannel]) { [weak self] r in
            switch r {
            case .failure(let e): if manual { self?.topToast(e.message) }
            case .success(let v):
                guard let o = v as? JSON, o.bool("available"), let url = URL(string: o.str("url")) else {
                    if manual { self?.topToast("Установлена последняя версия \(version)") }
                    return
                }
                if manual { self?.offerUpdate(o.str("version"), url) }
                // A banner closed by hand stays closed for that version until the app restarts.
                if manual || o.str("version") != self?.dismissedUpdate { self?.showUpdateBanner(o.str("version"), url) }
            }
        }
    }

    private func offerUpdate(_ version: String, _ url: URL) {
        var top: UIViewController = self
        while let p = top.presentedViewController { top = p }
        let a = UIAlertController(title: "Доступна версия \(version)", message: "Скачайте IPA и установите его через Scarlet поверх текущей версии.", preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "Позже", style: .cancel))
        a.addAction(UIAlertAction(title: "Скачать", style: .default) { _ in UIApplication.shared.open(url) })
        top.present(a, animated: !Theme.reducedMotion)
    }

    private func topToast(_ text: String) {
        var top: UIViewController = self
        while let p = top.presentedViewController { top = p }
        top.toast(text)
    }

    /// Drops in from above on a spring; the cross sends it back up.
    private func showUpdateBanner(_ version: String, _ url: URL) {
        guard updateBanner == nil else { return }
        let bar = UIView()
        bar.glass(20, strong: true)
        let text = label("Доступна версия \(version)", 15, .semibold)
        let open = PillButton("Скачать")
        open.addAction(UIAction { _ in UIApplication.shared.open(url) }, for: .touchUpInside)
        let close = UIButton(type: .system)
        close.setImage(UIImage(systemName: "xmark", withConfiguration: UIImage.SymbolConfiguration(pointSize: 15, weight: .semibold)), for: .normal)
        close.tintColor = Theme.muted
        close.accessibilityLabel = "Скрыть обновление"
        close.widthAnchor.constraint(equalToConstant: 44).isActive = true
        close.heightAnchor.constraint(equalToConstant: 44).isActive = true
        close.springPress()
        close.addAction(UIAction { [weak self] _ in
            self?.dismissedUpdate = version
            Haptics.tick()
            self?.hideUpdateBanner()
        }, for: .touchUpInside)
        let row = UIStackView(arrangedSubviews: [text, open, close])
        row.alignment = .center
        row.spacing = 8
        row.setCustomSpacing(12, after: text)
        row.translatesAutoresizingMaskIntoConstraints = false
        bar.addSubview(row)
        bar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bar)
        NSLayoutConstraint.activate([
            row.leadingAnchor.constraint(equalTo: bar.leadingAnchor, constant: 18),
            row.trailingAnchor.constraint(equalTo: bar.trailingAnchor, constant: -4),
            row.topAnchor.constraint(equalTo: bar.topAnchor, constant: 8),
            row.bottomAnchor.constraint(equalTo: bar.bottomAnchor, constant: -8),
            bar.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 12),
            bar.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -12),
            bar.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
        ])
        updateBanner = bar
        UIAccessibility.post(notification: .announcement, argument: "Доступна версия \(version)")
        bar.alpha = 0
        UIView.animate(withDuration: Theme.ms(0.16)) { bar.alpha = 1 }
        if !Theme.reducedMotion {
            bar.transform = CGAffineTransform(translationX: 0, y: -96).scaledBy(x: 0.92, y: 0.92)
            Motion.animate(0.5, 0.7) { bar.transform = .identity }
        }
    }

    private func hideUpdateBanner() {
        guard let bar = updateBanner else { return }
        updateBanner = nil
        bar.isUserInteractionEnabled = false
        guard !Theme.reducedMotion else { return bar.removeFromSuperview() }
        UIView.animate(withDuration: 0.2) { bar.alpha = 0 }
        Motion.animate(0.34, 1, { bar.transform = CGAffineTransform(translationX: 0, y: -(bar.frame.maxY + 24)).scaledBy(x: 0.94, y: 0.94) }) {
            bar.removeFromSuperview()
        }
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
        if let cid = pendingConversation {
            pendingConversation = nil
            NotificationCenter.default.post(name: .openConversation, object: nil, userInfo: ["id": cid])
        }
        QuickReaction.load()
        CallReadiness.request(from:self) { [weak self] in guard let self else { return }; WhatsNewViewController.showIfNeeded(from:self) }
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

/// iOS exposes background refresh as a setting, not a runtime permission.
private enum CallReadiness {
    static func request(from host:UIViewController,done:@escaping()->Void) {
        let center=UNUserNotificationCenter.current()
        center.getNotificationSettings { settings in
            let next = { checkMicrophone(from:host,notifications:settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional,done:done) }
            if settings.authorizationStatus == .notDetermined {
                center.requestAuthorization(options:[.alert,.sound,.badge]) { ok,_ in checkMicrophone(from:host,notifications:ok,done:done) }
            } else { next() }
        }
    }
    private static func checkMicrophone(from host:UIViewController,notifications:Bool,done:@escaping()->Void) {
        let status=AVCaptureDevice.authorizationStatus(for:.audio)
        if status == .notDetermined { AVCaptureDevice.requestAccess(for:.audio) { ok in show(from:host,notifications:notifications,microphone:ok,done:done) } }
        else { show(from:host,notifications:notifications,microphone:status == .authorized,done:done) }
    }
    private static func show(from host:UIViewController,notifications:Bool,microphone:Bool,done:@escaping()->Void) {
        DispatchQueue.main.async {
            var missing:[String]=[]
            if !notifications { missing.append("Разрешите уведомления, чтобы видеть входящие звонки и сообщения на заблокированном экране.") }
            if !microphone { missing.append("Разрешите микрофон для звонков.") }
            if UIApplication.shared.backgroundRefreshStatus != .available { missing.append("Включите «Обновление контента» в настройках iOS.") }
            guard !missing.isEmpty else { done();return }
            let alert=UIAlertController(title:"Звонки и работа в фоне",message:missing.joined(separator:"\n\n"),preferredStyle:.alert)
            alert.addAction(UIAlertAction(title:"Открыть настройки",style:.default) { _ in
                if let url=URL(string:UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                done()
            })
            alert.addAction(UIAlertAction(title:"Позже",style:.cancel) { _ in done() })
            host.present(alert,animated:!Theme.reducedMotion)
        }
    }
}
