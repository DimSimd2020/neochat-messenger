package app.neochat.ui

import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import app.neochat.core.CoreClient
import org.json.JSONObject

/** Firebase emails the link; this view only holds the separate, private app ticket. */
class EmailVerification(private val act: MainActivity, private val registration: Boolean, private val done: () -> Unit = {}) : LinearLayout(act) {
    private val address = FloatingField(act, "Электронная почта")
    private val info = act.label("Почта видна только вам. Подтвердите адрес по ссылке из письма.", 14f, 400, Theme.Role.MUTED)
    private val send = PillButton(act, "Отправить ссылку", primary = false)
    private val check = PillButton(act, "Я подтвердил почту", primary = false)
    private val change = PillButton(act, "Изменить адрес", primary = false)
    private var ticket = ""
    private var verified = false
    private var busy = false
    private var resendAt = 0L
    val verifiedTicket get() = if (verified) ticket else ""

    init {
        orientation = VERTICAL
        address.edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        addView(address, lp())
        addView(info, lp().apply { topMargin = act.dp(8) })
        for (button in listOf(send,check,change)) addView(button, lp().apply { topMargin = act.dp(8) })
        check.visibility = View.GONE
        change.visibility = View.GONE
        send.setOnClickListener { send() }
        check.setOnClickListener { refresh() }
        change.setOnClickListener {
            if (busy) return@setOnClickListener
            ticket = ""; verified = false
            address.edit.isEnabled = true
            send.visibility = View.VISIBLE
            check.visibility = View.GONE
            change.visibility = View.GONE
            info.text = "Введите адрес и отправьте новую ссылку."
        }
    }

    private fun params() = JSONObject().also { testServer(act)?.let { server -> it.put("server", server) } }

    private fun send() {
        if (busy) return
        if (address.text.isBlank()) { info.text = "Введите электронную почту"; address.edit.requestFocus(); return }
        if (SystemClock.elapsedRealtime() < resendAt) { info.text = "Повторная отправка доступна через минуту после предыдущего письма."; return }
        busy = true; send.isEnabled = false; check.isEnabled = false; change.isEnabled = false
        CoreClient.call(if (registration) "registration_email_start" else "email_start", params().put("email",address.text.trim())) { value,error ->
            busy = false; send.isEnabled = true; check.isEnabled = true; change.isEnabled = true
            if (error != null) { info.text = error.message; return@call }
            ticket = (value as JSONObject).str("ticket")
            resendAt = SystemClock.elapsedRealtime()+60_000
            address.edit.isEnabled = false
            send.setText("Отправить повторно")
            check.visibility = View.VISIBLE
            change.visibility = if (registration) View.VISIBLE else View.GONE
            info.text = "Письмо отправлено. Откройте ссылку, нажмите «Продолжить», подтвердите привязку и вернитесь в NeoChat."
        }
    }

    private fun refresh() {
        if (busy || ticket.isEmpty()) return
        busy = true; check.isEnabled = false
        CoreClient.call(if (registration) "registration_email_status" else "email_status", params().put("ticket",ticket)) { value,error ->
            busy = false; check.isEnabled = true
            if (error != null) { info.text = error.message; return@call }
            if ((value as? JSONObject)?.optBoolean("verified") != true) { info.text = "Адрес ещё не подтверждён. Откройте письмо и завершите подтверждение."; return@call }
            verified = true
            info.text = "Почта подтверждена"
            send.visibility = View.GONE; check.visibility = View.GONE
            done()
        }
    }
}

fun showEmailBinding(act: MainActivity) {
    val sheet = Sheet(act,"Привяжите электронную почту")
    sheet.body.addView(act.label("Подтвердите почту, чтобы у аккаунта был ваш контактный адрес. Пароль, ключи и переписка не изменятся.",15f,400,Theme.Role.MUTED),lp())
    sheet.body.addView(EmailVerification(act,false) {
        sheet.dismiss()
        CoreClient.emitLocal("email_changed")
        act.toast("Почта привязана")
    },lp().apply { topMargin=act.dp(12) })
    sheet.body.addView(PillButton(act,"Позже",primary=false).apply { setOnClickListener { sheet.dismiss() } },lp().apply { topMargin=act.dp(12) })
    sheet.show()
}
