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

    private var home: HomeScreen? = null
    private var archive: ArchiveScreen? = null
    private var chat: ChatScreen? = null
    private var current: Screen? = null
    private var pendingConversation: String? = null
    private val listener: (JSONObject) -> Unit = { onEvent(it) }
    private var updateBanner: View? = null
    private var lastUpdateCheck = 0L

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
        closeOverlays()
        home = null
        archive = null
        chat = null
        Notifier.visibleConversation = null
        RealtimeService.stop(this)
        show(OnboardingScreen(this, status) { showMain() }, 0f)
    }

    fun showMain() {
        val h = HomeScreen(this)
        home = h
        archive = null
        chat = null
        show(h, 0f)
        RealtimeService.sync(this)
        AppLock.maybeLock(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        pendingConversation?.let {
            pendingConversation = null
            openChat(it)
        }
    }

    fun openChat(conversationId: String) {
        if (home == null) return
        if (chat?.conversationId == conversationId) return
        closeOverlays()
        val c = ChatScreen(this, conversationId)
        chat = c
        show(c, dp(22).toFloat())
    }

    fun closeChat() {
        val h = home ?: return
        if (chat == null) return
        chat = null
        show(archive ?: h, -dp(22).toFloat())
    }

    fun openArchive() {
        if (home == null || archive != null) return
        val a = ArchiveScreen(this)
        archive = a
        show(a, dp(22).toFloat())
    }

    fun closeArchive() {
        val h = home ?: return
        if (archive == null) return
        archive = null
        h.hideArchiveEntry()
        show(h, -dp(22).toFloat())
    }

    /**
     * Cross-fade to [next] with a horizontal shift over 220 ms. Home stays alive under an
     * open conversation so the selected tab, drafts and searches survive.
     */
    private fun show(next: Screen, shift: Float) {
        val prev = current
        current = next
        prev?.onHidden()
        val v = next.view
        if (v.parent == null) content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        v.visibility = View.VISIBLE
        v.bringToFront()
        next.applyInsets(top, bottom, ime)
        Theme.apply()
        next.onShown()
        val pv = prev?.view
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
    }

    fun toast(text: String) = Toast.show(this, text)

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
        if (chat != null) return closeChat()
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
