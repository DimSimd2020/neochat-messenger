package app.neochat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
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
 * Sign in, registration and recovery. On a new phone the password restores the account
 * keys and the history from the password-protected copy on the server.
 */
class OnboardingScreen(private val act: MainActivity, status: JSONObject, private val done: () -> Unit) : Screen {
    private enum class Mode { LOGIN, REGISTER, RECOVER, RESET_REQUEST, RESET_COMPLETE }

    private val ctx: Context = act
    private val scroll = ScrollView(ctx)
    override val view: View = scroll
    private val col = LinearLayout(ctx)
    private val form = LinearLayout(ctx)
    private val recovery = LinearLayout(ctx)

    private val heading = ctx.label("", 24f, 700)
    private val hint = ctx.label("", 14.5f, 400, Theme.Role.MUTED)
    private val name = FloatingField(ctx, "Имя")
    private val username = FloatingField(ctx, "Никнейм или почта")
    private val password = FloatingField(ctx, "Пароль", password = true)
    private val code = FloatingField(ctx, "Код восстановления")
    private val resetToken = FloatingField(ctx, "Код из письма")
    private val loginCode = FloatingField(ctx, "Код из чата «NeoChat уведомления»")
    private val email = EmailVerification(act, registration = true)
    private val error = ctx.label("", 14f, 400, Theme.Role.WARNING)
    private val submit = PillButton(ctx, "Войти")
    private val switchMode = textLink("")
    private val forgotLink = textLink("Забыли пароль?")
    private val recoverLink = textLink("Восстановить доступ")

    private val recoveryCode = ctx.label("", 19f, 600, Theme.Role.ACCENT)
    private val saved = CheckBox(ctx)
    private val proceed = PillButton(ctx, "Продолжить")

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
        col.addView(form, lp())
        col.addView(recovery, lp())

        status.str("username").takeIf { it.isNotEmpty() }?.let { username.setText(it) }
        setMode(Mode.LOGIN)
        page(form)
    }

    private fun dp(v: Int) = ctx.dp(v)

    private fun buildForm() {
        form.orientation = LinearLayout.VERTICAL
        form.addView(heading, lp().apply { topMargin = dp(22) })
        form.addView(hint, lp().apply { topMargin = dp(8) })
        for (f in listOf(name, username, password, code, resetToken, loginCode)) form.addView(f, lp().apply { topMargin = dp(12) })
        form.addView(email, lp().apply { topMargin = dp(12) })
        name.edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        loginCode.visibility = View.GONE
        loginCode.edit.inputType = InputType.TYPE_CLASS_NUMBER
        username.edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        code.edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        resetToken.edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        error.visibility = View.GONE
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        form.addView(error, lp().apply { topMargin = dp(12) })
        submit.setOnClickListener { submit() }
        form.addView(submit, lp().apply { topMargin = dp(18) })
        switchMode.setOnClickListener {
            if (busy) return@setOnClickListener
            setMode(when (mode) {
                Mode.RESET_REQUEST, Mode.RESET_COMPLETE -> Mode.LOGIN
                Mode.LOGIN -> Mode.REGISTER
                else -> Mode.LOGIN
            })
        }
        form.addView(switchMode, lp().apply { topMargin = dp(10) })
        forgotLink.setOnClickListener { if (!busy) setMode(Mode.RESET_REQUEST) }
        form.addView(forgotLink, lp())
        recoverLink.setOnClickListener { if (!busy) setMode(Mode.RECOVER) }
        recoverLink.role(Theme.Role.MUTED)
        form.addView(recoverLink, lp())
    }

    private fun buildRecovery() {
        recovery.orientation = LinearLayout.VERTICAL
        recovery.addView(ctx.label("Сохраните код восстановления", 22f, 700), lp().apply { topMargin = dp(22) })
        recovery.addView(
            ctx.label(
                "Код вернёт доступ к аккаунту, если все устройства потеряны и пароль забыт. Переписка этим кодом не восстанавливается: " +
                    "её копия зашифрована паролем. Запишите код и храните отдельно от телефона.", 14.5f, 400, Theme.Role.MUTED
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

    private fun page(p: View) {
        for (v in listOf(form, recovery)) v.visibility = if (v === p) View.VISIBLE else View.GONE
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
        resetToken.visibility = if (m == Mode.RESET_COMPLETE) View.VISIBLE else View.GONE
        name.visibility = if (m == Mode.REGISTER) View.VISIBLE else View.GONE
        email.visibility = if (m == Mode.REGISTER) View.VISIBLE else View.GONE
        password.visibility = if (m == Mode.RESET_REQUEST) View.GONE else View.VISIBLE
        if (m != Mode.LOGIN) loginCode.visibility = View.GONE
        password.setLabel(if (m == Mode.RECOVER || m == Mode.RESET_COMPLETE) "Новый пароль" else "Пароль")
        username.setLabel(if (m == Mode.LOGIN || m == Mode.RESET_REQUEST || m == Mode.RESET_COMPLETE) "Никнейм или почта" else "Никнейм")
        when (m) {
            Mode.LOGIN -> set("Вход", "", "Войти")
            Mode.REGISTER -> set("Регистрация", "Пароль — не меньше 8 символов.", "Создать аккаунт")
            Mode.RECOVER -> set("Восстановление доступа", "Остальные устройства будут отключены, переписка не перенесётся.", "Восстановить")
            Mode.RESET_REQUEST -> set("Сброс пароля", "Ссылка уйдёт на привязанную почту. Пароль входа сменится; ключи и история на ваших устройствах сохранятся.", "Отправить ссылку")
            Mode.RESET_COMPLETE -> set("Новый пароль", "Введите код со страницы из письма и новый пароль. Это не восстановление аккаунта.", "Сменить пароль")
        }
        switchMode.text = when (m) {
            Mode.RESET_REQUEST, Mode.RESET_COMPLETE -> "Назад ко входу"
            Mode.LOGIN -> "Создать аккаунт"
            else -> "Уже есть аккаунт? Войти"
        }
        switchMode.visibility = View.VISIBLE
        forgotLink.visibility = if (m == Mode.LOGIN) View.VISIBLE else View.GONE
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
        forgotLink.isEnabled = !b
        recoverLink.isEnabled = !b
        if (b) submit.setText("Подождите…") else setMode(mode)
    }

    private fun submit() {
        if (busy) return
        val user = username.text.trim().removePrefix("@")
        val pass = password.text
        if (user.isEmpty()) return showError(
            if (mode == Mode.LOGIN || mode == Mode.RESET_REQUEST || mode == Mode.RESET_COMPLETE) "Введите никнейм или почту" else "Введите никнейм"
        )
        if (mode != Mode.RESET_REQUEST && pass.isEmpty()) return showError("Введите пароль")
        if (mode != Mode.LOGIN && mode != Mode.RESET_REQUEST && pass.length < 8) return showError("Пароль — не меньше 8 символов")
        val p = JSONObject().put("username", user).put("device_name", deviceName()).put("platform", "android")
        testServer(ctx)?.let { p.put("server", it) }
        val method = when (mode) {
            Mode.LOGIN -> "login".also {
                p.put("password", pass)
                if (loginCode.visibility == View.VISIBLE) p.put("code", loginCode.text.trim())
            }
            Mode.REGISTER -> {
                if (name.text.isBlank()) return showError("Введите имя")
                if (email.verifiedTicket.isEmpty()) return showError("Подтвердите электронную почту по ссылке из письма")
                p.put("email_ticket",email.verifiedTicket)
                p.put("password", pass).put("display_name", name.text.trim())
                "register"
            }
            Mode.RECOVER -> {
                if (code.text.isBlank()) return showError("Введите код восстановления")
                p.put("new_password", pass).put("recovery_code", code.text.trim())
                "recover"
            }
            Mode.RESET_REQUEST -> "password_reset_request"
            Mode.RESET_COMPLETE -> {
                val token = resetToken.text.trim()
                if (token.length != 43) return showError("Введите код из письма")
                p.put("token", token).put("new_password", pass)
                "password_reset_complete"
            }
        }
        showError("")
        setBusy(true)
        val m = mode
        CoreClient.call(method, p) { v, e ->
            setBusy(false)
            if (e != null) {
                // Two-factor sign-in: the code arrives in the system chat on the other devices.
                if (e.code == "code_required") {
                    loginCode.visibility = View.VISIBLE
                    loginCode.edit.requestFocus()
                }
                return@call showError(e.message)
            }
            when (m) {
                Mode.RESET_REQUEST -> {
                    resetToken.setText("")
                    setMode(Mode.RESET_COMPLETE)
                    showError("Если почта привязана, ссылка отправлена. Откройте письмо и введите код ниже.")
                }
                Mode.RESET_COMPLETE -> {
                    password.setText("")
                    resetToken.setText("")
                    setMode(Mode.LOGIN)
                    showError("Пароль изменён. Войдите с новым паролем.")
                }
                Mode.REGISTER, Mode.RECOVER -> {
                    password.setText("")
                    code.setText("")
                    loginCode.setText("")
                    loginCode.visibility = View.GONE
                    val res = v as? JSONObject ?: JSONObject()
                    recoveryCode.text = res.str("recovery_code")
                    saved.isChecked = false
                    page(recovery)
                }
                else -> {
                    password.setText("")
                    code.setText("")
                    loginCode.setText("")
                    loginCode.visibility = View.GONE
                    done()
                }
            }
        }
    }

    override fun applyInsets(top: Int, bottom: Int, ime: Int) {
        col.setPadding(dp(20), top + dp(8), dp(20), maxOf(bottom, ime) + dp(24))
    }

    override fun onHidden() {
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

/**
 * Debug builds only: `adb shell am start -n app.neochat/.ui.MainActivity --es test_server http://10.0.2.2:8098`
 * points sign-in at a local test server. Release builds always use the built-in one.
 */
private fun debuggable(ctx: Context) = ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

fun rememberTestServer(ctx: Context, intent: android.content.Intent?) {
    val url = intent?.getStringExtra("test_server") ?: return
    if (debuggable(ctx)) Prefs.of(ctx).edit().putString("test_server", url).apply()
}

fun testServer(ctx: Context): String? = if (debuggable(ctx)) Prefs.of(ctx).getString("test_server", null) else null
