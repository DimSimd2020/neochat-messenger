import UIKit

/// Six native chat-only transitions. Stored keys match Android; UIKit owns the navigation stack.
enum ChatTransitions {
    static let choices: [(String, String)] = [
        ("portal", "Портал"), ("depth", "Глубина"), ("flight", "Взлёт"),
        ("hinge", "3D-створка"), ("reveal", "Раскрытие"), ("magnet", "Магнит"),
    ]
    static var selected: String {
        let key = UserDefaults.standard.string(forKey: "chat_transition") ?? "portal"
        return choices.contains { $0.0 == key } ? key : "portal"
    }
    static var milliseconds: Int {
        let saved = UserDefaults.standard.integer(forKey: "chat_transition_duration")
        return saved == 0 ? 360 : min(700, max(160, saved))
    }
    static var duration: TimeInterval { TimeInterval(milliseconds) / 1000 }
}

final class ChatTransitionAnimator: NSObject, UIViewControllerAnimatedTransitioning {
    let entering: Bool
    init(entering: Bool) { self.entering = entering }
    func transitionDuration(using ctx: UIViewControllerContextTransitioning?) -> TimeInterval {
        Theme.reducedMotion ? 0 : ChatTransitions.duration
    }

    func animateTransition(using ctx: UIViewControllerContextTransitioning) {
        guard let from = ctx.view(forKey: .from), let to = ctx.view(forKey: .to) else {
            ctx.completeTransition(false)
            return
        }
        let container = ctx.containerView
        let forward = entering
        if forward { container.addSubview(to) } else { container.insertSubview(to, belowSubview: from) }
        to.frame = ctx.finalFrame(for: ctx.viewController(forKey: .to)!)
        container.layoutIfNeeded()
        let width = max(container.bounds.width, 1)
        let height = max(container.bounds.height, 1)
        let key = ChatTransitions.selected
        let moving = forward ? to : from
        let stationary = forward ? from : to
        let wasCorner = moving.layer.cornerRadius
        let wasClipped = moving.clipsToBounds

        // Initial state. All transforms, alpha and layer state are restored even on cancellation.
        switch key {
        case "depth":
            moving.transform = CGAffineTransform(scaleX: forward ? 0.76 : 1, y: forward ? 0.76 : 1)
            moving.alpha = forward ? 0.45 : 1
        case "flight":
            moving.transform = CGAffineTransform(translationX: 0, y: forward ? height * 0.28 : 0)
            moving.alpha = forward ? 0.6 : 1
        case "hinge":
            var perspective = CATransform3DIdentity
            perspective.m34 = -1 / 950
            moving.layer.transform = forward ? CATransform3DRotate(perspective, -0.48, 0, 1, 0) : CATransform3DIdentity
            moving.alpha = forward ? 0.65 : 1
        case "reveal":
            moving.transform = forward ? CGAffineTransform(scaleX: 0.6, y: 0.6) : .identity
            moving.layer.cornerRadius = forward ? 46 : wasCorner
            moving.clipsToBounds = true
            moving.alpha = forward ? 0.5 : 1
        case "magnet":
            moving.transform = forward ? CGAffineTransform(translationX: width * 0.22, y: -28).scaledBy(x: 0.92, y: 0.92) : .identity
            moving.alpha = forward ? 0.55 : 1
        default:
            moving.transform = forward ? CGAffineTransform(scaleX: 0.88, y: 0.88) : .identity
            moving.alpha = forward ? 0 : 1
        }

        let animations = {
            if forward {
                moving.transform = .identity
                moving.layer.transform = CATransform3DIdentity
                moving.layer.cornerRadius = wasCorner
                moving.alpha = 1
                stationary.alpha = 0.72
                if key == "depth" { stationary.transform = CGAffineTransform(scaleX: 1.08, y: 1.08) }
            } else {
                switch key {
                case "depth":
                    moving.transform = CGAffineTransform(scaleX: 0.76, y: 0.76)
                    stationary.transform = .identity
                case "flight":
                    moving.transform = CGAffineTransform(translationX: 0, y: height * 0.28)
                case "hinge":
                    var perspective = CATransform3DIdentity
                    perspective.m34 = -1 / 950
                    moving.layer.transform = CATransform3DRotate(perspective, -0.48, 0, 1, 0)
                case "reveal":
                    moving.transform = CGAffineTransform(scaleX: 0.6, y: 0.6)
                    moving.layer.cornerRadius = 46
                case "magnet":
                    moving.transform = CGAffineTransform(translationX: width * 0.22, y: -28).scaledBy(x: 0.92, y: 0.92)
                default:
                    moving.transform = CGAffineTransform(scaleX: 0.88, y: 0.88)
                }
                moving.alpha = 0.05
                stationary.alpha = 1
                stationary.transform = .identity
            }
        }
        let finish: (Bool) -> Void = { _ in
            let cancelled = ctx.transitionWasCancelled
            [from, to].forEach {
                $0.transform = .identity
                $0.layer.transform = CATransform3DIdentity
                $0.alpha = 1
            }
            moving.layer.cornerRadius = wasCorner
            moving.clipsToBounds = wasClipped
            if cancelled { to.removeFromSuperview() }
            ctx.completeTransition(!cancelled)
        }
        let duration = transitionDuration(using: ctx)
        guard duration > 0 else { animations(); finish(true); return }
        UIView.animate(withDuration: duration, delay: 0,
                       usingSpringWithDamping: key == "magnet" ? 0.68 : 0.88,
                       initialSpringVelocity: key == "flight" ? 0.7 : 0.25,
                       options: [.beginFromCurrentState, .allowUserInteraction, .curveEaseOut],
                       animations: animations, completion: finish)
    }
}

/// All visual settings are together, as on Android. A preview uses a real chat push when available.
final class CustomizationViewController: SheetViewController, UINavigationControllerDelegate {
    func navigationController(_ navigationController: UINavigationController, animationControllerFor operation: UINavigationController.Operation,
                              from fromVC: UIViewController, to toVC: UIViewController) -> UIViewControllerAnimatedTransitioning? {
        guard !Theme.reducedMotion else { return nil }
        let push = operation == .push && fromVC === self && toVC is ChatTransitionPreviewController
        let pop = operation == .pop && fromVC is ChatTransitionPreviewController && toVC === self
        return push || pop ? ChatTransitionAnimator(entering: push) : nil
    }
    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Кастомизация"
        build()
    }

    private func build() {
        clear()
        heading("Тема, переходы и анимации")
        let mode = UISegmentedControl(items: ["Устройство", "День", "Ночь"])
        mode.selectedSegmentIndex = Theme.mode.rawValue
        mode.addAction(UIAction { _ in Theme.mode = Theme.Mode(rawValue: mode.selectedSegmentIndex) ?? .system }, for: .valueChanged)
        stack.addArrangedSubview(mode)
        stack.addArrangedSubview(NavRow("paintpalette", "Оформление интерфейса") { [weak self] in
            self?.navigationController?.pushViewController(AppearanceViewController(), animated: !Theme.reducedMotion)
        })
        let sizes: [(Double, String)] = [(15, "Мелкий"), (17, "Обычный"), (19, "Крупный"), (21, "Очень крупный")]
        let size = UISegmentedControl(items: sizes.map { $0.1 })
        size.selectedSegmentIndex = sizes.firstIndex { CGFloat($0.0) == Prefs.textSize } ?? 1
        size.addAction(UIAction { _ in Prefs.set("text_size", sizes[size.selectedSegmentIndex].0) }, for: .valueChanged)
        heading("Размер текста")
        stack.addArrangedSubview(size)
        stack.addArrangedSubview(switchRow("Крупные эмодзи без текста", on: Prefs.bigEmoji) { Prefs.set("big_emoji", $0) })
        stack.addArrangedSubview(switchRow("Уменьшить анимацию", on: UserDefaults.standard.bool(forKey: "reduce_motion")) { Prefs.set("reduce_motion", $0) })
        heading("Переход между чатами")
        let choices = ChatTransitions.choices
        for (key, name) in choices {
            let row = NavRow("circle.dotted.circle", name, value: key == ChatTransitions.selected ? "✓" : "", chevron: false) { [weak self] in
                UserDefaults.standard.set(key, forKey: "chat_transition")
                Haptics.select()
                self?.build()
            }
            row.accessibilityValue = key == ChatTransitions.selected ? "Выбрано" : ""
            stack.addArrangedSubview(row)
        }
        heading("Длительность: \(ChatTransitions.milliseconds) мс")
        let slider = UISlider()
        slider.minimumValue = 160
        slider.maximumValue = 700
        slider.value = Float(ChatTransitions.milliseconds)
        slider.accessibilityLabel = "Длительность перехода"
        slider.addAction(UIAction { [weak self] _ in
            UserDefaults.standard.set(Int(slider.value), forKey: "chat_transition_duration")
            self?.durationLabel?.text = "Длительность: \(ChatTransitions.milliseconds) мс"
        }, for: .valueChanged)
        durationLabel = stack.arrangedSubviews.last as? UILabel
        stack.addArrangedSubview(slider)
        muted("Переходы работают при открытии и закрытии переписки. Системное уменьшение движения отключает их.")
        let preview = PillButton("Предпросмотр перехода")
        preview.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            self.navigationController?.delegate = self
            self.navigationController?.pushViewController(ChatTransitionPreviewController(), animated: !Theme.reducedMotion)
        }, for: .touchUpInside)
        stack.addArrangedSubview(preview)
    }
    private weak var durationLabel: UILabel?
}

final class ChatTransitionPreviewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Предпросмотр"
        view.backgroundColor = Theme.canvas
        let demo = label("Переход между списком и перепиской", 19, .semibold, color: Theme.text)
        demo.textAlignment = .center
        demo.numberOfLines = 0
        demo.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(demo)
        NSLayoutConstraint.activate([
            demo.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            demo.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            demo.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 28),
            demo.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -28),
        ])
    }
}
