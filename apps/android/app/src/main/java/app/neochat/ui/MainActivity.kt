package app.neochat.ui

import android.Manifest
import android.annotation.TargetApi
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import app.neochat.FilesProvider
import app.neochat.Notifier
import app.neochat.RealtimeService
import app.neochat.core.CoreClient
import org.json.JSONObject
import java.io.File

/** Screens get the system bar and keyboard insets and may react to back. */
interface Screen {
    val view: View
    val pageBackground: BackgroundView? get() = null
    fun applyInsets(top: Int, bottom: Int, ime: Int) {}
    fun onBack(): Boolean = false
    fun onShown() {}
    fun onHidden() {}
    fun onEvent(ev: JSONObject) {}
}

class MainActivity : Activity(), Sheet.Host {
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    override lateinit var overlay: FrameLayout
    private val backHandlers = ArrayList<() -> Boolean>()
    private var top = 0
    private var bottom = 0
    private var ime = 0
    override val bottomInset get() = maxOf(bottom, ime)
    override val topInset get() = top
    private val insetListeners = ArrayList<() -> Unit>()
    override fun addInsetListener(l: () -> Unit) { insetListeners += l }
    override fun removeInsetListener(l: () -> Unit) { insetListeners.remove(l) }
    var scanner: QrScanner? = null
    var camera: PhotoCamera? = null
    var circleCamera: CircleCamera? = null
    private val protectedChats = mutableSetOf<View>()
    val chatCaptureProtected get() = protectedChats.isNotEmpty()

    fun protectChat(owner: View, on: Boolean) {
        val changed = if (on) protectedChats.add(owner) else protectedChats.remove(owner)
        if (changed) Prefs.applySecure(this)
    }

    private var home: HomeScreen? = null
    private var archive: ArchiveScreen? = null
    private var chat: ChatScreen? = null
    private var current: Screen? = null
    private val pageHistory = ArrayList<Screen>()
    private var pendingConversation: String? = null
    private var openChatGeneration = 0
    private val listener: (JSONObject) -> Unit = { onEvent(it) }
    private var updateBanner: View? = null
    private var updateBusy = false
    private var updateProgress: android.widget.ProgressBar? = null
    private var dismissedUpdate = ""
    private var lastUpdateCheck = 0L
    private var exportingUpdateApk = false
    private var slideAnim: android.animation.ValueAnimator? = null
    private class ChatBack(val list: Screen, val motion: ChatExitMotion) {
        val previousAccessibility = list.view.importantForAccessibility
    }
    private var chatBack: ChatBack? = null
    private var unregisterBack: (() -> Unit)? = null
    var readinessPrompted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        root.addView(BackgroundView(this), FrameLayout.LayoutParams(MATCH, MATCH))
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = FrameLayout(this)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33) registerBack()
        edgeToEdge()
        Theme.onModeApplied = { dark -> barAppearance(dark) }
        Theme.init(this, root)
        Prefs.applySecure(this)
        CoreClient.addListener(listener)
        rememberTestServer(this, intent)
        if (!openNotification(intent)) start()
    }

    override fun onDestroy() {
        unregisterBack?.invoke()
        slideAnim?.end()
        clearChatBack()
        CoreClient.removeListener(listener)
        Theme.onModeApplied = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openNotification(intent)
    }

    private fun openNotification(intent: Intent?): Boolean {
        val cid = intent?.getStringExtra("conversation_id") ?: return false
        val account = intent.getStringExtra("account_id").orEmpty()
        if (account.isNotEmpty() && account != CoreClient.accountId) {
            pendingConversation = cid
            CoreClient.call("switch_account", JSONObject().put("account_id", account)) { _, e ->
                if (e != null) { pendingConversation = null; toast(e.message); start() }
            }
            return true
        }
        if (home != null) openChat(cid) else pendingConversation = cid
        return home != null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Theme.onConfigurationChanged(this)
    }

    @Suppress("DEPRECATION")
    private fun edgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        // The window stays the same size when the keyboard moves. adjustResize composites the
        // previous frame over the new one for an instant, so the chat looks mirrored on itself.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        // A back swipe that hides the keyboard reports the final (hidden) inset at the
        // first frame. Applying it jumps the chat while the old frame is still on screen.
        var deferIme = false
        var imeAtStart = 0
        fun publish() {
            current?.applyInsets(top, bottom, ime)
            insetListeners.toList().forEach { it() }
        }
        root.setOnApplyWindowInsetsListener { _, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val sys = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                top = sys.top
                bottom = sys.bottom
                if (!deferIme) ime = ins.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                top = ins.systemWindowInsetTop
                // Before API 30 the bottom inset already includes the keyboard.
                val b = ins.systemWindowInsetBottom
                bottom = minOf(b, ins.stableInsetBottom)
                if (!deferIme) ime = if (b > ins.stableInsetBottom) b else 0
            }
            if (!deferIme) publish()
            ins
        }
        if (Build.VERSION.SDK_INT >= 30) {
            root.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_STOP) {
                override fun onPrepare(animation: WindowInsetsAnimation) {
                    if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                        deferIme = true
                        imeAtStart = ime
                    }
                }

                override fun onProgress(insets: WindowInsets, running: MutableList<WindowInsetsAnimation>): WindowInsets {
                    val reported = insets.getInsets(WindowInsets.Type.ime()).bottom
                    val anim = running.firstOrNull { it.typeMask and WindowInsets.Type.ime() != 0 }
                    val fraction = anim?.interpolatedFraction ?: 1f
                    // Hidden inset from the first frame of a back-swipe: follow the finger instead.
                    ime = if (deferIme && imeAtStart > 0 && reported == 0 && fraction < 1f) (imeAtStart * (1f - fraction)).toInt() else reported
                    publish()
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimation) {
                    if (animation.typeMask and WindowInsets.Type.ime() == 0) return
                    deferIme = false
                    root.requestApplyInsets()
                }
            })
        }
        root.requestApplyInsets()
    }

    @Suppress("DEPRECATION")
    internal fun barAppearance(dark: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
        } else {
            val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            root.systemUiVisibility = if (dark) root.systemUiVisibility and light.inv() else root.systemUiVisibility or light
        }
    }

    private fun start() {
        CoreClient.call("status") { v, e ->
            if (e != null) return@call toast(e.message)
            val s = v as JSONObject
            if (s.optBoolean("logged_in")) showMain() else showOnboarding(s)
        }
    }

    fun showOnboarding(status: JSONObject) {
        ++openChatGeneration
        pageHistory.clear()
        closeOverlays()
        home = null
        archive = null
        chat = null
        Notifier.visibleConversation = null
        RealtimeService.sync(this)
        app.neochat.PushRegistration.sync(this)
        show(OnboardingScreen(this, status) { showMain() }, 0f)
    }

    fun showMain() {
        ++openChatGeneration
        pageHistory.clear()
        readinessPrompted = false
        val h = HomeScreen(this)
        home = h
        archive = null
        chat = null
        show(h, 0f)
        RealtimeService.sync(this)
        app.neochat.PushRegistration.sync(this)
        AppLock.migrate(this)
        AppLock.maybeLock(this)
        QuickReaction.load(this)
        Dialogs.whatsNewIfNeeded(this)
        pendingConversation?.let {
            pendingConversation = null
            openChat(it)
        }
    }

    fun openChat(conversationId: String) {
        if (home == null) return
        if (chat?.conversationId == conversationId && current === chat) return
        val gen = ++openChatGeneration
        CoreClient.call("get_conversation",JSONObject().put("conversation_id",conversationId)) { v,e ->
            if (gen != openChatGeneration) return@call
            if (e != null) return@call toast(e.message)
            pageHistory.clear()
            closeOverlays()
            val conversation = v as JSONObject
            if (conversation.optBoolean("in_archive")) {
                if (archive == null) archive = ArchiveScreen(this)
            } else {
                archive?.takeIf { it !== current }?.let { content.removeView(it.view) }
                archive = null
            }
            val c = if (chat?.conversationId == conversationId) chat!! else ChatScreen(this, conversationId, conversation)
            chat = c
            show(c,dp(22).toFloat(),slide=true)
        }
    }

    fun closeChat() {
        val h = home ?: return
        val leaving = chat ?: return
        ++openChatGeneration
        val next = chatBack?.list ?: chatReturnScreen(leaving, h)
        chat = null
        if (next === h) {
            archive?.let { content.removeView(it.view) }
            archive = null
        }
        show(next, -dp(22).toFloat(), slide = true)
    }

    private fun chatReturnScreen(leaving: ChatScreen, h: HomeScreen): Screen =
        if (leaving.inArchive) archive ?: ArchiveScreen(this).also { archive = it } else h

    private fun startChatBack(direction: Float) {
        val leaving = chat ?: return
        val h = home ?: return
        if (current !== leaving || leaving.hasBackAction || backHandlers.isNotEmpty() ||
            ime > 0 || Theme.reducedMotion
        ) return
        slideAnim?.end()
        clearChatBack()
        val list = chatReturnScreen(leaving, h)
        val back = ChatBack(list, ChatExitMotion(leaving.view, direction))
        val v = list.view
        if (v.parent == null) content.addView(v, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        list.applyInsets(top, bottom, ime)
        v.visibility = View.VISIBLE
        chatBack = back
    }

    private fun cancelChatBack() {
        val back = chatBack ?: return
        if (back.motion.progress == 0f || Theme.reducedMotion) return clearChatBack()
        slideAnim = back.motion.settle(0f) {
            clearChatBack()
            slideAnim = null
        }
    }

    private fun clearChatBack() {
        val back = chatBack ?: return
        chatBack = null
        back.motion.reset()
        back.list.view.importantForAccessibility = back.previousAccessibility
        if (current !== back.list) back.list.view.visibility = View.INVISIBLE
    }

    fun openPage(page: Screen) {
        current?.let { pageHistory.add(it) }
        show(page, dp(22).toFloat(), slide = true)
    }

    fun closePage() {
        val previous = pageHistory.removeLastOrNull() ?: return
        show(previous, -dp(22).toFloat(), slide = true)
    }

    fun openArchive() {
        if (home == null || archive != null) return
        val a = ArchiveScreen(this)
        archive = a
        show(a, dp(22).toFloat(), slide = true)
    }

    fun closeArchive() {
        val h = home ?: return
        if (archive == null) return
        archive = null
        h.hideArchiveEntry()
        show(h, -dp(22).toFloat(), slide = true)
    }

    /** Switch screens while retaining the home list, drafts and searches. */
    private fun show(next: Screen, shift: Float, slide: Boolean = false) {
        slideAnim?.end()
        val prev = current
        val chatOpening = slide && shift > 0f && next is ChatScreen && prev != null && prev !== next
        val chatClosing = slide && prev is ChatScreen && (next is HomeScreen || next is ArchiveScreen)
        val exit = chatBack?.takeIf { chatClosing && it.list === next && !Theme.reducedMotion }
        if (exit == null) clearChatBack() else {
            chatBack = null
            next.view.importantForAccessibility = exit.previousAccessibility
        }
        current = next
        prev?.onHidden()
        val v = next.view
        val pv = prev?.view
        if (v.parent == null) content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        v.visibility = View.VISIBLE
        v.alpha = 1f
        pv?.alpha = 1f
        // Park the incoming screen off to the right before it is drawn. A translucent page
        // (settings, chat) otherwise sits on top of the previous one for a frame.
        if (slide && !chatOpening && pv != null && !Theme.reducedMotion && shift > 0f) {
            val w = content.width.toFloat().takeIf { it > 0f } ?: resources.displayMetrics.widthPixels.toFloat()
            v.translationX = w
        }
        v.bringToFront()
        next.applyInsets(top, bottom, ime)
        if (chatOpening || chatClosing) Theme.apply(v) else Theme.apply()
        next.onShown()
        // Home and an open archive stay alive under a conversation.
        val drop = {
            if (pv != null && pv !== v) {
                if (prev === home || (prev === archive && next is ChatScreen)) {
                    pv.visibility = if (next is ChatScreen) View.INVISIBLE else View.GONE
                } else content.removeView(pv)
                protectChat(pv, false)
            }
        }
        v.animate().cancel()
        pv?.animate()?.cancel()
        if (Theme.reducedMotion || pv == null || shift == 0f) {
            v.alpha = 1f
            v.translationX = 0f
            drop()
            return
        }
        if (chatOpening) {
            val width = (content.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
            slideAnim = animateChatOpen(prev, next, width) {
                drop()
                slideAnim = null
            }
            return
        }
        if (chatClosing) {
            pv.bringToFront()
            val motion = exit?.motion ?: ChatExitMotion(pv, 0f)
            slideAnim = motion.settle(1f) {
                drop()
                motion.reset()
                slideAnim = null
            }
            return
        }
        if (slide) return slideBetween(pv, v, shift > 0f, drop)
        v.alpha = 0f
        v.translationX = shift
        v.animate().alpha(1f).translationX(0f).setDuration(220).setInterpolator(Theme.surfaceCurve).setListener(null).start()
        pv.animate().alpha(0f).translationX(-shift / 2).setDuration(220).setInterpolator(Theme.surfaceCurve)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    pv.animate().setListener(null)
                    pv.alpha = 1f
                    pv.translationX = 0f
                    if (current !== prev) drop()
                }
            }).start()
    }

    /**
     * Telegram-style push: the upper screen travels the full width on a plate of the app background,
     * the one beneath drifts a third of the way the other direction. [forward] opens, otherwise closes.
     */
    private fun slideBetween(pv: View, v: View, forward: Boolean, drop: () -> Unit) {
        val w = content.width.toFloat().takeIf { it > 0f } ?: resources.displayMetrics.widthPixels.toFloat()
        val scrim = View(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val shadow = View(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(android.graphics.Color.TRANSPARENT, 0x50000000)
            )
        }
        content.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addView(shadow, FrameLayout.LayoutParams(dp(18), MATCH))
        val over = if (forward) v else pv
        val under = if (forward) pv else v
        val plate = BackgroundView(this)
        content.addView(plate, FrameLayout.LayoutParams(MATCH, MATCH))
        under.bringToFront()
        scrim.bringToFront()
        plate.bringToFront()
        over.bringToFront()
        shadow.bringToFront()
        // A newly added plate has no size until the next layout. Size it now so the first
        // frame of a translucent screen does not show the previous one through it.
        val wPx = w.toInt()
        val hPx = content.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val exactW = View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY)
        val exactH = View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY)
        for (layer in listOf(scrim, plate)) {
            layer.measure(exactW, exactH)
            layer.layout(0, 0, wPx, hPx)
        }
        shadow.measure(View.MeasureSpec.makeMeasureSpec(dp(18), View.MeasureSpec.EXACTLY), exactH)
        shadow.layout(0, 0, dp(18), hPx)
        // p is how far the upper screen has come in: 0 off to the right, 1 at rest.
        val place = { p: Float ->
            over.translationX = (1f - p) * w
            over.alpha = 1f
            plate.translationX = over.translationX
            under.translationX = -p * 0.25f * w
            under.alpha = 1f
            scrim.alpha = 0.16f * p
            shadow.translationX = over.translationX - dp(18)
            shadow.alpha = (p * 8f).coerceAtMost(1f)
        }
        place(if (forward) 0f else 1f)
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 320
        anim.interpolator = android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f)
        anim.addUpdateListener {
            val t = it.animatedValue as Float
            place(if (forward) t else 1f - t)
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                if (slideAnim === anim) slideAnim = null
                content.removeView(scrim)
                content.removeView(shadow)
                content.removeView(plate)
                over.translationX = 0f
                under.translationX = 0f
                over.alpha = 1f
                under.alpha = 1f
                if (current?.view !== pv) drop()
            }
        })
        slideAnim = anim
        anim.start()
    }

    private fun onEvent(ev: JSONObject) {
        val type = ev.str("type")
        if (type == "account_notify") return
        if (type == "account_selected") {
            updateBusy = false
            hideUpdateProgress()
            hideUpdateBanner()
            slideAnim?.end()
            clearChatBack()
            current?.onHidden()
            current = null
            content.removeAllViews()
            protectedChats.clear()
            Prefs.applySecure(this)
            home = null
            archive = null
            chat = null
            pageHistory.clear()
            closeOverlays()
            Notifier.visibleConversation = null
            Typing.reset()
            start()
            return
        }
        if (type == "auth_required" || type == "device_revoked") {
            if (home == null) return
            if (type == "device_revoked") toast("Это устройство отозвано. Новые сообщения сюда не придут")
            CoreClient.call("status") { v, _ -> showOnboarding(v as? JSONObject ?: JSONObject()) }
            return
        }
        if (type == "warning") toast(ev.str("message"))
        if (type == "story_delivery_failed") toast("История: ${ev.str("message")}")
        if (type == "update_progress") updateProgress?.let { bar ->
            val total = ev.optLong("total")
            bar.isIndeterminate = total <= 0
            if (total > 0) bar.setProgress((ev.optLong("done") * 100 / total).toInt().coerceIn(0, 100), !Theme.reducedMotion)
        }
        if (type == "typing") Typing.onEvent(ev)
        if (type == "notify") Typing.clear(ev.str("conversation_id"))
        home?.onEvent(ev)
        archive?.onEvent(ev)
        chat?.onEvent(ev)
        if (pageHistory.isNotEmpty()) current?.onEvent(ev)
    }

    fun toast(text: String) = Toast.show(this, text)
    fun toast(text: String, actionTitle: String, action: () -> Unit) = Toast.show(this, text, actionTitle, action)

    private val results = HashMap<Int, (Int, Intent?) -> Unit>()
    private var nextRequest = 100

    fun startForResult(intent: Intent, cb: (Int, Intent?) -> Unit) {
        val code = nextRequest++
        results[code] = cb
        try {
            startActivityForResult(intent, code)
        } catch (_: android.content.ActivityNotFoundException) {
            results.remove(code)
            toast("Нет приложения для этого действия")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        results.remove(requestCode)?.invoke(resultCode, data) ?: super.onActivityResult(requestCode, resultCode, data)
    }

    private val permissionResults = HashMap<Int, (Boolean) -> Unit>()

    /** Asks for a runtime permission once and reports whether it is granted. */
    fun withPermission(permission: String, cb: (Boolean) -> Unit) {
        if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) return cb(true)
        val code = nextRequest++
        permissionResults[code] = cb
        requestPermissions(arrayOf(permission), code)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        permissionResults.remove(requestCode)?.invoke(grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    override fun onPause() {
        slideAnim?.end()
        clearChatBack()
        // The camera must not stay open behind another app.
        scanner?.dismiss()
        camera?.dismiss()
        circleCamera?.dismiss()
        Playback.stop()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        if (home != null) AppLock.maybeLock(this)
    }

    override fun onStop() {
        AppLock.onBackground()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        chat?.onResumed()
        // At most once an hour; the download runs in the background.
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheck > 60 * 60 * 1000L) {
            lastUpdateCheck = now
            checkUpdate()
        }
    }

    /** Checks the signed manifest; [manual] reports "no update" and progress too. */
    fun checkUpdate(manual: Boolean = false) {
        if (updateBanner != null || updateBusy) return
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: return
        updateBusy = true
        val p = JSONObject().put("platform", "android").put("version", version)
            .put("channel", Prefs.updateChannel(this))
            .put("disable_alpha", app.neochat.BuildConfig.NEOCHAT_DISABLE_ALPHA)
        CoreClient.call("check_update", p) { v, e ->
            if (e != null) return@call run { updateBusy = false; if (manual) toast(e.message) }
            val o = v as? JSONObject
            if (o?.optBoolean("available") != true) return@call run {
                updateBusy = false
                if (manual) toast("У вас последняя версия ($version)")
            }
            if (manual) showUpdateProgress(o.str("version"))
            CoreClient.call("download_update", p) { r, err ->
                updateBusy = false
                hideUpdateProgress()
                val d = r as? JSONObject
                if (err != null && manual) toast(err.message)
                val next = d?.str("version").orEmpty()
                // A banner closed by hand stays closed for that version until the app restarts.
                if (err == null && d != null && updateBanner == null && (manual || next != dismissedUpdate)) showUpdateBanner(next, File(d.str("path")))
            }
        }
    }

    private fun showUpdateProgress(version: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassDrawable(this@MainActivity, GlassDrawable.Kind.STRONG, dpf(20))
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        card.addView(label("Загружаем обновление $version", 14.5f, 600), lp())
        updateProgress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            contentDescription = "Загрузка обновления"
            progressTintList = android.content.res.ColorStateList.valueOf(Theme.p.accent)
            card.addView(this, lp(MATCH, dp(12)).apply { topMargin = dp(4) })
        }
        overlay.addView(card, flp(MATCH, WRAP, Gravity.TOP).apply { setMargins(dp(12), top + dp(8), dp(12), 0) })
    }

    private fun hideUpdateProgress() {
        (updateProgress?.parent as? View)?.let { overlay.removeView(it) }
        updateProgress = null
    }

    /** Drops in from above on a spring; the cross sends it back up. */
    private fun showUpdateBanner(version: String, apk: File) {
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GlassDrawable(this@MainActivity, GlassDrawable.Kind.STRONG, dpf(20))
            setPadding(dp(18), dp(8), dp(4), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        bar.addView(label("Обновление $version готово", 14.5f, 600), lp(0, WRAP, 1f))
        val install = PillButton(this, "Сохранить APK")
        install.setOnClickListener { saveUpdateToFiles(apk) }
        bar.addView(install, lp(WRAP, WRAP))
        val close = IconButton(this, app.neochat.R.drawable.ic_close, "Скрыть обновление")
        close.setOnClickListener {
            dismissedUpdate = version
            Haptics.tick(close)
            hideUpdateBanner()
        }
        bar.addView(close, lp(dp(48), dp(48)).apply { marginStart = dp(2) })
        overlay.addView(bar, flp(MATCH, WRAP, Gravity.TOP).apply { setMargins(dp(12), top + dp(8), dp(12), 0) })
        updateBanner = bar
        Haptics.confirm(bar)
        if (!Theme.reducedMotion) {
            bar.alpha = 0f
            bar.animate().alpha(1f).setDuration(160).start()
            bar.spring(Prop.Y).snap(-dpf(96))
            bar.spring(Prop.SCALE).snap(0.92f)
            bar.springTo(Prop.Y, 0f, 0.5f, 0.7f)
            bar.springTo(Prop.SCALE, 1f, 0.46f, 0.66f)
        }
    }

    private fun hideUpdateBanner() {
        val bar = updateBanner ?: return
        updateBanner = null
        bar.isEnabled = false
        if (Theme.reducedMotion) return overlay.removeView(bar)
        bar.springTo(Prop.Y, -(bar.bottom + dpf(24)), 0.34f, 1f) { overlay.removeView(bar) }
        bar.springTo(Prop.SCALE, 0.94f, 0.3f, 1f)
        bar.animate().alpha(0f).setDuration(200).start()
    }

    /** The file manager, not NeoChat, requests installation after the user saves the update. */
    private fun saveUpdateToFiles(apk: File) {
        if (exportingUpdateApk) return
        if (!apk.isFile || !apk.name.endsWith(".apk", ignoreCase = true)) {
            toast("Скачанный APK недоступен. Повторите загрузку")
            return
        }
        val picker = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_TITLE, apk.name)
        startForResult(picker) { resultCode, data ->
            val destination = data?.data
            if (resultCode != RESULT_OK || destination == null) return@startForResult
            exportingUpdateApk = true
            // Documents providers may be remote; never copy a large installer on the UI thread.
            Thread {
                val error = runCatching {
                    apk.inputStream().use { input ->
                        val output = contentResolver.openOutputStream(destination, "w")
                            ?: throw java.io.IOException("Нет доступа к выбранному файлу")
                        output.use { input.copyTo(it, 64 * 1024) }
                    }
                }.exceptionOrNull()
                runOnUiThread {
                    exportingUpdateApk = false
                    if (error == null) {
                        hideUpdateBanner()
                        toast("APK сохранён. Откройте его через приложение «Файлы» и установите")
                    } else {
                        toast("Не удалось сохранить APK: " + (error.message ?: "ошибка записи"))
                    }
                }
            }.start()
        }
    }

    override fun pushBack(handler: () -> Boolean) {
        backHandlers += handler
    }

    override fun popBack(handler: () -> Boolean) {
        backHandlers.remove(handler)
    }

    private fun closeOverlays() {
        for (h in backHandlers.toList().asReversed()) h()
        backHandlers.clear()
    }

    @TargetApi(33)
    private fun registerBack() {
        val callback = if (Build.VERSION.SDK_INT >= 34) predictiveBackCallback() else OnBackInvokedCallback { handleBack() }
        val dispatcher = onBackInvokedDispatcher
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        unregisterBack = { dispatcher.unregisterOnBackInvokedCallback(callback) }
    }

    @TargetApi(34)
    private fun predictiveBackCallback() = object : OnBackAnimationCallback {
        override fun onBackStarted(event: BackEvent) {
            when (event.swipeEdge) {
                BackEvent.EDGE_LEFT -> startChatBack(1f)
                BackEvent.EDGE_RIGHT -> startChatBack(-1f)
            }
        }

        override fun onBackProgressed(event: BackEvent) {
            chatBack?.motion?.followGesture(event.progress)
        }

        override fun onBackCancelled() = cancelChatBack()
        override fun onBackInvoked() = handleBack()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = handleBack()

    private fun handleBack() {
        if (backHandlers.lastOrNull()?.invoke() == true || current?.onBack() == true) return cancelChatBack()
        if (pageHistory.isNotEmpty()) return closePage()
        if (chat != null) return closeChat()
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
