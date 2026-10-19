package app.neochat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import app.neochat.R
import app.neochat.core.CoreClient
import org.json.JSONObject

/**
 * Sign in, registration and recovery. On a phone without keys "Войти" links it
 * to the account and waits for approval on the primary device.
 */
class OnboardingScreen(private val act: MainActivity, status: JSONObject, private val done: () -> Unit) : Screen {
    private enum class Mode { LOGIN, REGISTER, RECOVER }

    private val ctx: Context = act
    private val scroll = ScrollView(ctx)
    override val view: View = scroll
    private val col = LinearLayout(ctx)
    private val form = LinearLayout(ctx)
    private val recovery = LinearLayout(ctx)
    private val link = LinearLayout(ctx)

    private val heading = ctx.label("", 24f, 700)
    private val hint = ctx.label("", 14.5f, 400, Theme.Role.MUTED)
    private val hasKeys = status.optBoolean("has_device_keys")
    private val username = FloatingField(ctx, "Имя пользователя")
    private val password = FloatingField(ctx, "Пароль", password = true)
    private val code = FloatingField(ctx, "Код восстановления")
    private val error = ctx.label("", 14f, 400, Theme.Role.WARNING)
    private val submit = PillButton(ctx, "Войти")
    private val switchMode = textLink("")
    private val recoverLink = textLink("Восстановить доступ")

    private val recoveryCode = ctx.label("", 19f, 600, Theme.Role.ACCENT)
    private val saved = CheckBox(ctx)
    private val proceed = PillButton(ctx, "Продолжить")

    private val qr = QrView(ctx)
    private val linkState = ctx.label("Ожидаем подтверждения…", 14f, 400, Theme.Role.MUTED)
    private var linkText = ""
    private val poller = Handler(Looper.getMainLooper())
    private var polling = false

    private var mode = Mode.REGISTER
    private var busy = false

    init {
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        col.orientation = LinearLayout.VERTICAL
        scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))

        val brand = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(ctx).apply { setImageResource(R.drawable.logo); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, lp(dp(34), dp(34)))
            addView(ctx.label("NeoChat", 26f, 700), lp(WRAP, WRAP).apply { marginStart = dp(12) })
        }
        col.addView(brand, lp().apply { topMargin = dp(18) })

        buildForm()
        buildRecovery()
        buildLink()
        col.addView(form, lp())
        col.addView(recovery, lp())
        col.addView(link, lp())

        status.str("username").takeIf { it.isNotEmpty() }?.let { username.setText(it) }
        setMode(Mode.LOGIN)
        page(form)
    }

    private fun dp(v: Int) = ctx.dp(v)

    private fun buildForm() {
        form.orientation = LinearLayout.VERTICAL
        form.addView(heading, lp().apply { topMargin = dp(22) })
        form.addView(hint, lp().apply { topMargin = dp(8) })
        for (f in listOf(username, password, code)) form.addView(f, lp().apply { topMargin = dp(12) })
        username.edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        code.edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        error.visibility = View.GONE
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        form.addView(error, lp().apply { topMargin = dp(12) })
        submit.setOnClickListener { submit() }
        form.addView(submit, lp().apply { topMargin = dp(18) })
        switchMode.setOnClickListener { if (!busy) setMode(if (mode == Mode.LOGIN) Mode.REGISTER else Mode.LOGIN) }
        form.addView(switchMode, lp().apply { topMargin = dp(10) })
        recoverLink.setOnClickListener { if (!busy) setMode(Mode.RECOVER) }
        recoverLink.role(Theme.Role.MUTED)
        form.addView(recoverLink, lp())
    }

    private fun buildRecovery() {
        recovery.orientation = LinearLayout.VERTICAL
        recovery.addView(ctx.label("Сохраните код восстановления", 22f, 700), lp().apply { topMargin = dp(22) })
        recovery.addView(
            ctx.label(
                "Код вернёт доступ к аккаунту, если все устройства потеряны. История сообщений этим кодом не восстанавливается: " +
                    "она хранится только на ваших устройствах. Запишите код и храните отдельно от телефона.", 14.5f, 400, Theme.Role.MUTED
            ), lp().apply { topMargin = dp(8) }
        )
        recoveryCode.typeface = android.graphics.Typeface.MONOSPACE
        recoveryCode.setTextIsSelectable(true)
        recoveryCode.contentDescription = "Код восстановления"
        val box = FrameLayout(ctx).apply {
            background = GlassDrawable(ctx, radius = ctx.dpf(20))
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(recoveryCode)
        }
        recovery.addView(box, lp().apply { topMargin = dp(16) })
        val copy = PillButton(ctx, "Скопировать код", primary = false)
        copy.setOnClickListener {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            val clip = ClipData.newPlainText("NeoChat", recoveryCode.text)
            // Keep the code out of the clipboard preview and keyboard suggestions.
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            cm.setPrimaryClip(clip)
            if (Build.VERSION.SDK_INT < 33) act.toast("Код скопирован")
        }
        recovery.addView(copy, lp().apply { topMargin = dp(12) })
        saved.text = "Я сохранил код в надёжном месте"
        saved.textSize = 15f
        saved.minHeight = dp(48)
        saved.setOnCheckedChangeListener { _, on -> proceed.isEnabled = on }
        recovery.addView(saved, lp().apply { topMargin = dp(8) })
        proceed.isEnabled = false
        proceed.setOnClickListener {
            recoveryCode.text = ""
            done()
        }
        recovery.addView(proceed, lp().apply { topMargin = dp(12) })
    }

    private fun buildLink() {
        link.orientation = LinearLayout.VERTICAL
        link.addView(ctx.label("Подтвердите на основном устройстве", 22f, 700), lp().apply { topMargin = dp(22) })
        link.addView(
            ctx.label(
                "Это новый телефон для вашего аккаунта. Откройте NeoChat на основном устройстве: Устройства → Сканировать QR-код, и наведите камеру на этот код. " +
                    "Прошлая переписка на этот телефон не переносится.", 14.5f, 400, Theme.Role.MUTED
            ), lp().apply { topMargin = dp(8) }
        )
        link.addView(qr, lp(dp(240), dp(240)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(18) })
        val copy = PillButton(ctx, "Скопировать код привязки", primary = false)
        copy.setOnClickListener {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("NeoChat", linkText))
            if (Build.VERSION.SDK_INT < 33) act.toast("Код скопирован")
        }
        link.addView(copy, lp().apply { topMargin = dp(16) })
        linkState.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        link.addView(linkState, lp().apply { topMargin = dp(12) })
        val cancel = PillButton(ctx, "Отменить", primary = false)
        cancel.setOnClickListener {
            stopPoll()
            page(form)
        }
        link.addView(cancel, lp().apply { topMargin = dp(12) })
    }

    private fun page(p: View) {
        for (v in listOf(form, recovery, link)) v.visibility = if (v === p) View.VISIBLE else View.GONE
        // The recovery code must not end up in screenshots or the recents preview.
        if (p === recovery) act.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else act.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun textLink(t: String) = ctx.label(t, 15f, 600, Theme.Role.ACCENT).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48)
        isClickable = true
        isFocusable = true
    }

    private fun setMode(m: Mode) {
        mode = m
        code.visibility = if (m == Mode.RECOVER) View.VISIBLE else View.GONE
        password.setLabel(if (m == Mode.RECOVER) "Новый пароль" else "Пароль")
        when (m) {
            Mode.LOGIN -> set("Вход", "", "Войти")
            Mode.REGISTER -> set("Регистрация", "Пароль — не меньше 8 символов.", "Создать аккаунт")
            Mode.RECOVER -> set("Восстановление доступа", "Остальные устройства будут отключены. Переписка с них не переносится.", "Восстановить")
        }
        switchMode.text = if (m == Mode.LOGIN) "Создать аккаунт" else "Уже есть аккаунт? Войти"
        recoverLink.visibility = if (m == Mode.LOGIN) View.VISIBLE else View.GONE
        showError("")
    }

    private fun set(h: String, t: String, button: String) {
        heading.text = h
        hint.text = t
        hint.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
        submit.setText(button)
    }

    private fun showError(t: String) {
        error.text = t
        error.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun setBusy(b: Boolean) {
        busy = b
        submit.isEnabled = !b
        switchMode.isEnabled = !b
        recoverLink.isEnabled = !b
        if (b) submit.setText("Подождите…") else setMode(mode)
    }

    private fun submit() {
        if (busy) return
        val user = username.text.trim().removePrefix("@")
        val pass = password.text
        if (user.isEmpty()) return showError("Введите имя пользователя")
        if (pass.isEmpty()) return showError("Введите пароль")
        if (mode != Mode.LOGIN && pass.length < 8) return showError("Пароль — не меньше 8 символов")
        val p = JSONObject().put("username", user).put("device_name", deviceName()).put("platform", "android")
        // Without keys on this phone signing in means linking it to the account.
        val linking = mode == Mode.LOGIN && !hasKeys
        val method = when (mode) {
            Mode.LOGIN -> (if (linking) "link_start" else "login").also { p.put("password", pass) }
            Mode.REGISTER -> "register".also { p.put("password", pass) }
            Mode.RECOVER -> {
                if (code.text.isBlank()) return showError("Введите код восстановления")
                p.put("new_password", pass).put("recovery_code", code.text.trim())
                "recover"
            }
        }
        showError("")
        setBusy(true)
        val m = mode
        CoreClient.call(method, p) { v, e ->
            setBusy(false)
            if (e != null) return@call showError(e.message)
            password.setText("")
            code.setText("")
            val res = v as? JSONObject ?: JSONObject()
            when {
                m == Mode.REGISTER || m == Mode.RECOVER -> {
                    recoveryCode.text = res.str("recovery_code")
                    saved.isChecked = false
                    page(recovery)
                }
                linking -> showLink(res)
                else -> done()
            }
        }
    }

    private fun showLink(res: JSONObject) {
        linkText = res.str("qr")
        val arr = res.optJSONArray("qr_rows")
        qr.setRows(List(arr?.length() ?: 0) { arr!!.getString(it) })
        val minutes = maxOf(1, res.optInt("expires_in") / 60)
        linkState.text = "Ожидаем подтверждения… Код действует $minutes мин."
        linkState.role(Theme.Role.MUTED)
        page(link)
        polling = true
        poll()
    }

    private fun poll() {
        if (!polling) return
        poller.postDelayed({
            if (!polling) return@postDelayed
            CoreClient.call("link_poll") { v, e ->
                if (!polling) return@call
                if (e != null) {
                    if (e.code == "network") return@call poll()
                    stopPoll()
                    linkState.text = e.message
                    linkState.role(Theme.Role.WARNING)
                    return@call
                }
                val st = (v as? JSONObject)?.str("status") ?: "approved"
                when (st) {
                    "approved" -> { stopPoll(); done() }
                    "pending" -> poll()
                    else -> {
                        stopPoll()
                        linkState.text = "Привязка отклонена или истекла. Начните заново."
                        linkState.role(Theme.Role.WARNING)
                    }
                }
            }
        }, 2000)
    }

    private fun stopPoll() {
        polling = false
        poller.removeCallbacksAndMessages(null)
    }

    override fun applyInsets(top: Int, bottom: Int, ime: Int) {
        col.setPadding(dp(20), top + dp(8), dp(20), maxOf(bottom, ime) + dp(24))
    }

    override fun onHidden() {
        stopPoll()
        act.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun deviceName(): String {
        val m = Build.MODEL ?: "Android"
        val name = if (m.startsWith(Build.MANUFACTURER ?: "", ignoreCase = true)) m else "${Build.MANUFACTURER} $m"
        return name.trim().take(40).ifEmpty { "Android" }
    }

    companion object {
    }
}
