package app.neochat.ui

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.neochat.FilesProvider
import app.neochat.Notifier
import app.neochat.RealtimeService
import app.neochat.core.CoreClient
import org.json.JSONObject
import java.io.File

/** Screens get the system bar and keyboard insets and may react to back. */
interface Screen {
    val view: View
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

    private var home: HomeScreen? = null
    private var archive: ArchiveScreen? = null
    private var chat: ChatScreen? = null
    private var current: Screen? = null
    private val pageHistory = ArrayList<Screen>()
    private var pendingConversation: String? = null
    private var openChatGeneration = 0
    private val listener: (JSONObject) -> Unit = { onEvent(it) }
    private var updateBanner: View? = null
    private var lastUpdateCheck = 0L
    private var slideAnim: android.animation.ValueAnimator? = null
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
        edgeToEdge()
        Theme.onModeApplied = { dark -> barAppearance(dark) }
        Theme.init(this, root)
        Prefs.applySecure(this)
        CoreClient.addListener(listener)
        pendingConversation = intent?.getStringExtra("conversation_id")
        rememberTestServer(this, intent)
        start()
    }

    override fun onDestroy() {
        CoreClient.removeListener(listener)
        Theme.onModeApplied = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val cid = intent.getStringExtra("conversation_id") ?: return
        if (home != null) openChat(cid) else pendingConversation = cid
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
        root.setOnApplyWindowInsetsListener { _, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val sys = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                top = sys.top
                bottom = sys.bottom
                ime = ins.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                top = ins.systemWindowInsetTop
                // Before API 30 the bottom inset already includes the keyboard.
                val b = ins.systemWindowInsetBottom
                bottom = minOf(b, ins.stableInsetBottom)
                ime = if (b > ins.stableInsetBottom) b else 0
            }
            current?.applyInsets(top, bottom, ime)
            insetListeners.toList().forEach { it() }
            ins
        }
        if (Build.VERSION.SDK_INT >= 30) {
            root.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(insets: WindowInsets, running: MutableList<WindowInsetsAnimation>): WindowInsets {
                    ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                    current?.applyInsets(top, bottom, ime)
                    insetListeners.toList().forEach { it() }
                    return insets
                }
            })
        }
        root.requestApplyInsets()
    }

    @Suppress("DEPRECATION")
    private fun barAppearance(dark: Boolean) {
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
        RealtimeService.stop(this)
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
            if ((v as JSONObject).optBoolean("in_archive")) {
                if (archive == null) archive = ArchiveScreen(this)
            } else archive = null
            val c = if(chat?.conversationId == conversationId) chat!! else ChatScreen(this,conversationId)
            chat = c
            show(c,dp(22).toFloat(),slide=true)
        }
    }

    fun closeChat() {
        val h = home ?: return
        val leaving = chat ?: return
        val gen = ++openChatGeneration
        CoreClient.call("get_conversation",JSONObject().put("conversation_id",leaving.conversationId)) { v,_ ->
            if (gen != openChatGeneration || chat !== leaving) return@call
            if (v is JSONObject) {
                if (v.optBoolean("in_archive")) { if (archive == null) archive = ArchiveScreen(this) }
                else archive = null
            }
            chat = null
            show(archive ?: h,-dp(22).toFloat(),slide=true)
        }
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

    /**
     * Cross-fade to [next] with a horizontal shift over 220 ms. Home stays alive under an
     * open conversation so the selected tab, drafts and searches survive.
     */
    private fun show(next: Screen, shift: Float, slide: Boolean = false) {
        slideAnim?.end()
        val prev = current
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
        if (slide && pv != null && !Theme.reducedMotion && shift > 0f) {
            val w = content.width.toFloat().takeIf { it > 0f } ?: resources.displayMetrics.widthPixels.toFloat()
            v.translationX = w
        }
        v.bringToFront()
        next.applyInsets(top, bottom, ime)
        Theme.apply()
        next.onShown()
        // Home and an open archive stay alive under a conversation.
        val drop = { if (pv != null && pv !== v) { if (prev === home || (prev === archive && next is ChatScreen)) pv.visibility = View.GONE else content.removeView(pv) } }
        v.animate().cancel()
        pv?.animate()?.cancel()
        if (Theme.reducedMotion || pv == null || shift == 0f) {
            v.alpha = 1f
            v.translationX = 0f
            drop()
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
        if (type == "auth_required" || type == "device_revoked") {
            if (home == null) return
            if (type == "device_revoked") toast("Это устройство отозвано. Новые сообщения сюда не придут")
            CoreClient.call("status") { v, _ -> showOnboarding(v as? JSONObject ?: JSONObject()) }
            return
        }
        if (type == "warning") toast(ev.str("message"))
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
        // The camera must not stay open behind another app.
        scanner?.dismiss()
        camera?.dismiss()
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
        if (updateBanner != null) return
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: return
        val p = JSONObject().put("platform", "android").put("version", version)
        CoreClient.call("check_update", p) { v, e ->
            if (e != null) return@call run { if (manual) toast(e.message) }
            val o = v as? JSONObject
            if (o?.optBoolean("available") != true) return@call run { if (manual) toast("У вас последняя версия ($version)") }
            if (manual) toast("Загружаем версию ${o.str("version")}…")
            CoreClient.call("download_update", p) { r, err ->
                val d = r as? JSONObject
                if (err != null && manual) toast(err.message)
                if (err == null && d != null && updateBanner == null) showUpdateBanner(d.str("version"), File(d.str("path")))
            }
        }
    }

    private fun showUpdateBanner(version: String, apk: File) {
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GlassDrawable(this@MainActivity, GlassDrawable.Kind.STRONG, dpf(20))
            setPadding(dp(18), dp(8), dp(8), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        bar.addView(label("Обновление $version готово", 14.5f, 600), lp(0, WRAP, 1f))
        val install = PillButton(this, "Установить")
        install.setOnClickListener { installUpdate(apk) }
        bar.addView(install, lp(WRAP, WRAP))
        overlay.addView(bar, flp(MATCH, WRAP, Gravity.TOP).apply { setMargins(dp(12), top + dp(8), dp(12), 0) })
        updateBanner = bar
        if (!Theme.reducedMotion) {
            bar.alpha = 0f
            bar.translationY = -dpf(12)
            bar.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(Theme.surfaceCurve).start()
        }
    }

    private fun installUpdate(apk: File) {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(FilesProvider.uriFor(this, apk), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(i)
        } catch (_: android.content.ActivityNotFoundException) {
            toast("Не удалось открыть установщик")
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        backHandlers.lastOrNull()?.let { if (it()) return }
        if (current?.onBack() == true) return
        if (pageHistory.isNotEmpty()) return closePage()
        if (chat != null) return closeChat()
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
