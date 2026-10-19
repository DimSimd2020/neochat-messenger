package app.neochat.ui

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import app.neochat.Notifier
import app.neochat.core.CoreClient
import org.json.JSONObject

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

    private var home: HomeScreen? = null
    private var chat: ChatScreen? = null
    private var current: Screen? = null
    private var pendingConversation: String? = null
    private val listener: (JSONObject) -> Unit = { onEvent(it) }

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
        CoreClient.addListener(listener)
        pendingConversation = intent?.getStringExtra("conversation_id")
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
            ins
        }
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
        chat = null
        Notifier.visibleConversation = null
        show(OnboardingScreen(this, status) { showMain() }, 0f)
    }

    fun showMain() {
        val h = HomeScreen(this)
        home = h
        chat = null
        show(h, 0f)
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
        val drop = { if (pv != null && pv !== v) { if (prev === home) pv.visibility = View.GONE else content.removeView(pv) } }
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
        home?.onEvent(ev)
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

    override fun onResume() {
        super.onResume()
        chat?.onResumed()
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
