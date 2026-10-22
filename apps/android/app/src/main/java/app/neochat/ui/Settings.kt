package app.neochat.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import app.neochat.R
import app.neochat.core.CoreClient
import org.json.JSONObject
import java.io.File

/** The profile tab: profile card and all settings, grouped like Telegram. */
class SettingsPage(private val act: MainActivity, private val settingsOnly: Boolean = true) {
    private val ctx: Context = act
    private val prefs = Prefs.of(ctx)
    private val scroll = ScrollView(ctx)
    val view: View get() = scroll
    private val col = LinearLayout(ctx)
    private val avatar = AvatarView(ctx, 88)
    private val name = ctx.label("", 22f, 700)
    private val sub = ctx.label("", 14.5f, 400, Theme.Role.MUTED)
    private val bio = ctx.label("", 14.5f, 400)
    private var status = JSONObject()
    private val lockBox = LinearLayout(ctx)
    private val cacheValue = ctx.label("", 14f, 400, Theme.Role.MUTED)
    private val lastSeenValue = ctx.label("", 14f, 400, Theme.Role.MUTED)
    private val batteryValue = ctx.label("", 14f, 400, Theme.Role.MUTED)
    private val lockScreenValue = ctx.label("", 14f, 400, Theme.Role.MUTED)
    private val quickValue = ctx.label(QuickReaction.current(ctx), 14f, 400, Theme.Role.MUTED)

    init {
        scroll.isVerticalScrollBarEnabled = false
        scroll.clipToPadding = false
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        scroll.addView(col)
        build()
        load()
    }

    private fun dp(v: Int) = ctx.dp(v)

    fun setBottomPadding(px: Int) = scroll.setPadding(0, 0, 0, px)

    private fun build() {
        if (!settingsOnly) {
        col.addView(avatar, lp(dp(88), dp(88)).apply { topMargin = dp(16) })
        avatar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        avatar.contentDescription = "Сменить фото профиля"
        avatar.isFocusable = true
        avatar.setOnClickListener { editAvatar() }
        name.gravity = Gravity.CENTER
        col.addView(name, lp(WRAP, WRAP).apply { topMargin = dp(12) })
        sub.gravity = Gravity.CENTER
        sub.setTextIsSelectable(true)
        col.addView(sub, lp(WRAP, WRAP).apply { topMargin = dp(4) })
        bio.gravity = Gravity.CENTER
        col.addView(bio, lp(WRAP, WRAP).apply { setMargins(dp(24), dp(8), dp(24), 0) })

        section("Аккаунт").apply {
            addView(nav(R.drawable.ic_profile, "Сменить фото профиля") { editAvatar() })
            addView(nav(R.drawable.ic_profile, "Изменить профиль") { editProfile() })
            addView(nav(R.drawable.ic_qr, "Мой QR-код") { Qr.showMine(act) })
            addView(nav(R.drawable.ic_person_add, "Добавить по QR-коду") { Qr.scanContact(act) })
            addView(nav(R.drawable.ic_devices, "Устройства") { Dialogs.devices(act) })
        }
        } else {
            section("Аккаунт").addView(nav(R.drawable.ic_devices, "Устройства") { Dialogs.devices(act) })
        }

        section("Конфиденциальность").apply {
            addView(nav(R.drawable.ic_clock, "Время последнего визита", lastSeenValue) { chooseLastSeen() })
            addView(note("Если скрыть время, собеседники увидят «был(а) недавно», а вы тоже не будете видеть точное время других."))
            lockBox.orientation = LinearLayout.VERTICAL
            addView(lockBox)
            val secure = toggle("Запретить снимки экрана", "secure_screen", false) { Prefs.applySecure(act) }
            addView(secure)
            val twoFactor = ToggleRow(ctx, "Подтверждение входа")
            twoFactor.setPadding(dp(14), 0, dp(14), 0)
            CoreClient.call("account_settings") { v, _ ->
                val o = v as? JSONObject ?: return@call
                twoFactor.setChecked(o.optBoolean("two_factor"), false)
                lastSeenValue.text = if (o.optBoolean("show_last_seen", true)) "Все" else "Никто"
            }
            twoFactor.onToggle = { on ->
                CoreClient.call("set_two_factor", JSONObject().put("enabled", on)) { _, e ->
                    if (e != null) {
                        act.toast(e.message)
                        twoFactor.setChecked(!on, true)
                    }
                }
            }
            addView(twoFactor)
            addView(note("При входе на новом устройстве понадобится код из чата «NeoChat уведомления». Отчёты о прочтении всегда включены."))
        }
        buildLock()

        section("Уведомления").apply {
            addView(toggle("Уведомления о сообщениях", "notifications", true))
            addView(note("В уведомлении видно только название чата, текст не показывается."))
            addView(toggle("Резервное соединение в фоне", "realtime", app.neochat.RealtimeService.enabled(ctx)) { app.neochat.RealtimeService.sync(ctx) })
            addView(note("Сообщения и звонки приходят через push без постоянного уведомления. Резервное соединение нужно, если push недоступен: с ним в шторке появляется «NeoChat на связи». Без push и резервного соединения сообщения проверяются примерно раз в 15 минут."))
            addView(nav(R.drawable.ic_shield, "Работа в фоне", batteryValue) { requestUnrestricted() })
            if (Build.VERSION.SDK_INT >= 34) addView(nav(R.drawable.ic_phone, "Звонки на заблокированном экране", lockScreenValue) { openLockScreenCalls(act) })
            addView(nav(R.drawable.ic_bell, "Звук и вибрация") {
                act.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            })
        }

        section("Оформление").apply {
            val slider = ThemeSlider(ctx)
            slider.setValue(Theme.mode.ordinal, false)
            slider.onChange = { Theme.setMode(ctx, Theme.Mode.entries[it]) }
            addView(slider, lp().apply { setMargins(dp(10), dp(4), dp(10), 0) })
            val sizes = listOf("Мелкий", "Обычный", "Крупный", "Очень крупный")
            val sizeValue = ctx.label(sizes[prefs.getInt("text_size", 1).coerceIn(0, 3)], 14f, 400, Theme.Role.MUTED)
            addView(nav(R.drawable.ic_chat, "Размер текста в чатах", sizeValue) {
                choose(sizeValue, sizes.mapIndexed { i, s -> i.toString() to s }, prefs.getInt("text_size", 1).toString()) {
                    prefs.edit().putInt("text_size", it.toInt()).apply()
                }
            })
            addView(toggle("Крупные эмодзи без текста", "big_emoji", true))
            addView(toggle("Уменьшить анимацию", "reduce_motion", false) { Theme.forceReduced = prefs.getBoolean("reduce_motion", false) })
        }

        section("Чаты").apply {
            addView(toggle("Отправка клавишей Enter", "enter_send", false))
            val sortValue = ctx.label("", 14f, 400, Theme.Role.MUTED)
            sortValue.text = CHAT_SORTS.first { it.first == prefs.getString("chat_sort", "activity") }.second
            addView(nav(R.drawable.ic_sort, "Сортировка чатов", sortValue) {
                choose(sortValue, CHAT_SORTS, prefs.getString("chat_sort", "activity")!!) {
                    prefs.edit().putString("chat_sort", it).apply()
                    CoreClient.emitLocal("conversations_changed")
                }
            })
            val loads = listOf("always" to "Всегда", "wifi" to "Только по Wi-Fi", "never" to "Никогда")
            val loadValue = ctx.label(loads.first { it.first == prefs.getString("autoload", "always") }.second, 14f, 400, Theme.Role.MUTED)
            addView(nav(R.drawable.ic_download, "Автозагрузка фото", loadValue) {
                choose(loadValue, loads, prefs.getString("autoload", "always")!!) { prefs.edit().putString("autoload", it).apply() }
            })
            addView(nav(R.drawable.ic_chat, "Быстрая реакция", quickValue) {
                QuickReaction.pick(act) { e ->
                    QuickReaction.set(ctx, e) { err -> if (err != null) act.toast(err) else quickValue.text = e }
                }
            })
            addView(note("Свайп по чату влево убирает его в архив, вправо — закрепляет (до 3 чатов). Двойное касание сообщения ставит быструю реакцию. Контакты, закреплённые чаты, архив и быстрая реакция общие для всех ваших устройств."))
        }

        section("Данные").apply {
            addView(nav(R.drawable.ic_trash, "Очистить кэш", cacheValue) {
                Photos.full.evictAll()
                Thread {
                    ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                    act.runOnUiThread {
                        act.toast("Кэш очищен")
                        updateCache()
                    }
                }.start()
            })
            addView(note("Переписка и ключи хранятся в зашифрованной базе и не удаляются."))
        }

        section("О приложении").apply {
            val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
            addView(nav(R.drawable.ic_info, "Версия", ctx.label(version, 14f, 400, Theme.Role.MUTED)) {})
            val channelValue = ctx.label(Prefs.updateChannel(ctx), 14f, 400, Theme.Role.MUTED)
            addView(nav(R.drawable.ic_info, "Сообщать об обновлениях:", channelValue) {
                choose(channelValue, listOf("stable" to "stable", "beta" to "beta"), Prefs.updateChannel(ctx)) {
                    prefs.edit().putString("update_channel", it).apply()
                    act.checkUpdate(manual = true)
                }
            })
            addView(nav(R.drawable.ic_download, "Проверить обновления") { act.checkUpdate(manual = true) })
        }

        val logout = PillButton(ctx, "Выйти из аккаунта", primary = false)
        logout.label.role(Theme.Role.WARNING)
        logout.setOnClickListener {
            PopupMenu(act, logout).add("Выйти: ключи и история на этом телефоне будут удалены", danger = true) {
                CoreClient.call("logout") { _, e ->
                    if (e != null) act.toast(e.message)
                    prefs.edit().remove("lock_app").remove("lock_archive").apply()
                    CoreClient.call("status") { sv, _ -> act.showOnboarding(sv as? JSONObject ?: JSONObject()) }
                }
            }.show()
        }
        col.addView(logout, lp().apply { setMargins(dp(16), dp(24), dp(16), dp(8)) })
        updateCache()
    }

    /** App and archive lock go through the system prompt: fingerprint, face or the phone's screen lock. */
    private fun buildLock() {
        lockBox.removeAllViews()
        if (!AppLock.available(ctx)) {
            lockBox.addView(nav(R.drawable.ic_lock, "Вход по биометрии", ctx.label("Недоступно", 14f, 400, Theme.Role.MUTED)) {
                act.toast("Нужен Android 10 и настроенная блокировка экрана или отпечаток")
            })
            return
        }
        // Turning a lock on asks the system once, so a lock that cannot be opened is never left on.
        fun lockToggle(text: String, key: String): ToggleRow {
            val t = ToggleRow(ctx, text)
            t.setPadding(dp(14), 0, dp(14), 0)
            t.setChecked(prefs.getBoolean(key, false), false)
            t.onToggle = { on ->
                if (!on) prefs.edit().putBoolean(key, false).apply()
                else AppLock.confirm(act, "Подтвердите, что это вы") { ok ->
                    prefs.edit().putBoolean(key, ok).apply()
                    if (!ok) t.setChecked(false, true)
                }
            }
            return t
        }
        lockBox.addView(lockToggle("Вход по биометрии", "lock_app"))
        lockBox.addView(lockToggle("Биометрия при входе в архив", "lock_archive"))
        val delays = listOf("0" to "Сразу", "60" to "Через 1 мин", "300" to "Через 5 мин", "3600" to "Через 1 час")
        val cur = prefs.getInt("lock_after", 0).toString()
        val value = ctx.label(delays.firstOrNull { it.first == cur }?.second ?: "Сразу", 14f, 400, Theme.Role.MUTED)
        lockBox.addView(nav(R.drawable.ic_clock, "Автоблокировка", value) {
            choose(value, delays, prefs.getInt("lock_after", 0).toString()) { prefs.edit().putInt("lock_after", it.toInt()).apply() }
        })
        lockBox.addView(note("Спрашивает система телефона: отпечаток, лицо или тот же код, что открывает экран. Своего пароля у NeoChat нет."))
    }

    fun load() {
        batteryValue.text = if (unrestricted()) "Без ограничений" else "Ограничена"
        lockScreenValue.text = if (app.neochat.calls.CallNotifications.canShowOnLockScreen(ctx)) "Разрешены" else "Запрещены"
        QuickReaction.load(ctx) { quickValue.text = QuickReaction.current(ctx) }
        CoreClient.call("status") { v, _ ->
            val s = v as? JSONObject ?: return@call
            status = s
            val user = s.str("username")
            val display = s.str("display_name").ifEmpty { user }
            avatar.set(display, s.str("user_id"), s.str("avatar_path"))
            name.text = display
            sub.text = "@$user" + s.str("number").takeIf { it.isNotEmpty() }?.let { " · ID $it" }.orEmpty()
            bio.text = s.str("bio")
            bio.visibility = if (s.str("bio").isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun updateCache() {
        Thread {
            val bytes = ctx.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            act.runOnUiThread { cacheValue.text = sizeText(bytes) }
        }.start()
    }

    private fun editProfile() {
        val s = Sheet(act, "Профиль")
        val nameField = FloatingField(s.ctx, "Имя")
        nameField.setText(status.str("display_name"))
        nameField.edit.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        val bioField = FloatingField(s.ctx, "О себе")
        bioField.setText(status.str("bio"))
        bioField.edit.filters = arrayOf(android.text.InputFilter.LengthFilter(100))
        bioField.edit.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        val left = s.ctx.label("", 12.5f, 400, Theme.Role.MUTED)
        fun count() { left.text = "${100 - bioField.text.length} из 100" }
        bioField.edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) = count()
            override fun beforeTextChanged(e: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(e: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        count()
        val save = PillButton(s.ctx, "Сохранить")
        s.add(nameField, 4)
        s.add(bioField, 10)
        s.add(left, 4)
        s.add(s.ctx.label("Имя и описание видят ваши собеседники. Никнейм и ID не меняются.", 13.5f, 400, Theme.Role.MUTED), 8)
        s.add(save, 14)
        save.setOnClickListener {
            if (nameField.text.isBlank()) return@setOnClickListener act.toast("Введите имя")
            save.isEnabled = false
            CoreClient.call("set_profile", JSONObject().put("display_name", nameField.text.trim()).put("bio", bioField.text.trim())) { _, e ->
                save.isEnabled = true
                if (e != null) return@call act.toast(e.message)
                s.dismiss()
                load()
            }
        }
        s.show()
    }

    private fun editAvatar() {
        val menu = PopupMenu(act, avatar).add("Выбрать фото") { pickProfilePhoto(act) { load() } }
        if (status.str("avatar_path").isNotEmpty()) menu.add("Удалить фото", danger = true) {
            CoreClient.call("set_avatar", JSONObject().put("path", "")) { _, e -> if (e != null) act.toast(e.message) else load() }
        }
        menu.show()
    }

    private fun unrestricted() = ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    private fun requestUnrestricted() = openBatterySettings(act)

    companion object {
        /** Battery optimization may cut the background connection while the phone sleeps. */
        fun openBatterySettings(act: MainActivity) {
            val unrestricted = act.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(act.packageName)
            val intent = if (unrestricted) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + act.packageName))
            runCatching { act.startActivity(intent) }.onFailure { act.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }

        /** Android 14 asks for the right to take over a locked screen with a ringing call. */
        fun openLockScreenCalls(act: MainActivity) {
            val intent = if (Build.VERSION.SDK_INT >= 34)
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:" + act.packageName))
            else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, act.packageName)
            runCatching { act.startActivity(intent) }
        }
    }

    private fun chooseLastSeen() {
        choose(lastSeenValue, listOf("1" to "Все", "0" to "Никто"), if (lastSeenValue.text == "Никто") "0" else "1") { v ->
            CoreClient.call("set_last_seen_visible", JSONObject().put("visible", v == "1")) { _, e -> if (e != null) act.toast(e.message) }
        }
    }

    private fun choose(value: android.widget.TextView, options: List<Pair<String, String>>, current: String, set: (String) -> Unit) {
        val m = PopupMenu(act, value)
        for ((k, label) in options) m.add(label, checked = k == current) {
            value.text = label
            set(k)
        }
        m.show()
    }

    private fun section(title: String): LinearLayout {
        col.addView(ctx.label(title, 13.5f, 600, Theme.Role.MUTED).apply { heading() }, lp().apply { setMargins(dp(30), dp(22), dp(20), dp(6)) })
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassDrawable(ctx, radius = ctx.dpf(25))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        col.addView(card, lp().apply { setMargins(dp(16), 0, dp(16), 0) })
        return card
    }

    private fun note(t: String) = ctx.label(t, 12.5f, 400, Theme.Role.MUTED).apply { setPadding(dp(14), dp(2), dp(14), dp(8)) }

    private fun toggle(text: String, key: String, def: Boolean, after: () -> Unit = {}): ToggleRow {
        val t = ToggleRow(ctx, text)
        t.setPadding(dp(14), 0, dp(14), 0)
        t.setChecked(prefs.getBoolean(key, def), false)
        t.onToggle = {
            prefs.edit().putBoolean(key, it).apply()
            after()
        }
        return t
    }

    private fun nav(icon: Int, text: String, value: android.widget.TextView? = null, onClick: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(14), 0, dp(14), 0)
            isClickable = true
            isFocusable = true
            contentDescription = text
            background = GlassDrawable(ctx, GlassDrawable.Kind.NONE, ctx.dpf(20), rim = false)
            pressFeedback()
            setOnClickListener { onClick() }
        }
        row.addView(TintedIcon(ctx, icon), lp(dp(22), dp(22)))
        row.addView(ctx.label(text, 15.5f), lp(0, WRAP, 1f).apply { marginStart = dp(16) })
        if (value != null) row.addView(value, lp(WRAP, WRAP).apply { marginStart = dp(8) })
        return row
    }
}
