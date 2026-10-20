package app.neochat.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.neochat.R
import app.neochat.core.CoreClient
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Modal sheets of the main screen. */
object Dialogs {
    private fun Sheet.muted(t: String, top: Int = 12) = add(ctx.label(t, 13.5f, 400, Theme.Role.MUTED), top)
    private fun Sheet.heading(t: String, top: Int = 18) = add(ctx.label(t, 15.5f, 600), top)

    private fun errorLabel(ctx: Context) = ctx.label("", 13.5f, 400, Theme.Role.WARNING).apply {
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private fun TextView.show(t: String) {
        text = t
        visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun onChange(f: FloatingField, cb: () -> Unit) = f.edit.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = cb()
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })

    // ---- contacts ----

    fun newContact(act: MainActivity, done: ((JSONObject) -> Unit)? = null) {
        val s = Sheet(act, "Новый контакт")
        val ctx = s.ctx
        // Fields do not take focus on open, so the keyboard does not jump up.
        val user = FloatingField(ctx, "Никнейм")
        val first = FloatingField(ctx, "Имя (необязательно)")
        val last = FloatingField(ctx, "Фамилия (необязательно)")
        user.edit.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val err = errorLabel(ctx)
        val submit = PillButton(ctx, "Добавить")
        s.add(user, 4)
        s.add(first, 10)
        s.add(last, 10)
        s.muted("Поиск идёт только по точному никнейму: публичного каталога нет. Без своего имени контакт показывается под именем из его профиля.", 10)
        s.add(err, 8)
        s.add(submit, 14)
        submit.setOnClickListener {
            if (user.text.isBlank()) return@setOnClickListener err.show("Введите никнейм")
            err.show("")
            submit.isEnabled = false
            val p = JSONObject().put("username", user.text.trim().removePrefix("@")).put("first_name", first.text.trim()).put("last_name", last.text.trim())
            CoreClient.call("resolve_contact", p) { v, e ->
                submit.isEnabled = true
                if (e != null) return@call err.show(if (e.code == "not_found") "Пользователь с таким никнеймом не найден" else e.message)
                s.dismiss()
                act.toast("Контакт добавлен")
                done?.invoke(v as JSONObject)
            }
        }
        s.show()
    }

    fun recentCalls(act: MainActivity) {
        val s = Sheet(act, "Недавние звонки")
        val box = LinearLayout(s.ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, s.ctx.dp(18), 0, s.ctx.dp(18))
        }
        box.addView(TintedIcon(s.ctx, R.drawable.ic_call_history, Theme.Role.MUTED), lp(s.ctx.dp(40), s.ctx.dp(40)))
        box.addView(s.ctx.label("Звонков пока нет", 16f, 600).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP).apply { topMargin = s.ctx.dp(12) })
        box.addView(
            s.ctx.label("Звонки в этой версии NeoChat не реализованы.", 13.5f, 400, Theme.Role.MUTED).apply { gravity = Gravity.CENTER },
            lp(WRAP, WRAP).apply { topMargin = s.ctx.dp(6) }
        )
        s.add(box, 0)
        s.show()
    }

    // ---- new chat / group ----

    fun newChat(act: MainActivity) {
        val s = Sheet(act, "Новый чат")
        val ctx = s.ctx
        var group = false // group or channel: several people are picked
        var channel = false
        val selected = LinkedHashSet<String>()
        val modes = LinearLayout(ctx)
        val direct = Chip(ctx, "Личный").apply { isSelected = true }
        val grp = Chip(ctx, "Группа")
        val chn = Chip(ctx, "Канал")
        modes.addView(direct)
        modes.addView(grp, lp(WRAP, WRAP).apply { marginStart = ctx.dp(4) })
        modes.addView(chn, lp(WRAP, WRAP).apply { marginStart = ctx.dp(4) })
        modes.addView(View(ctx), lp(0, 1, 1f))
        val addContact = Chip(ctx, "Новый контакт")
        modes.addView(addContact)
        s.add(modes, 0)
        val title = FloatingField(ctx, "Название группы")
        title.visibility = View.GONE
        s.add(title, 10)
        val about = FloatingField(ctx, "Описание (необязательно)")
        about.visibility = View.GONE
        s.add(about, 10)
        val search = SearchBox(ctx, "Поиск контактов")
        s.add(search, 10)
        val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        s.add(rows, 6)
        val empty = ctx.label("Контактов пока нет. Добавьте человека по имени пользователя.", 14f, 400, Theme.Role.MUTED)
        s.add(empty, 6)
        val create = PillButton(ctx, "Создать группу")
        fun createText(): String {
            val base = if (channel) "Создать канал" else "Создать группу"
            return if (selected.isEmpty()) base else "$base (${selected.size})"
        }
        create.visibility = View.GONE
        s.add(create, 12)

        lateinit var reload: () -> Unit
        reload = {
            CoreClient.call("list_contacts", JSONObject().put("query", search.edit.text.toString())) { v, e ->
                if (e != null) return@call act.toast(e.message)
                val list = (v as JSONArray).objects()
                rows.removeAllViews()
                empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                if (list.isEmpty() && search.edit.text.isNotEmpty()) empty.text = "Никого не нашли по этому запросу."
                for (c in list) {
                    val uid = c.str("user_id")
                    val row = ListRow(ctx, 44)
                    row.avatar.set(c.str("display_name"), uid)
                    row.title.text = c.str("display_name")
                    row.subtitle.text = "@" + c.str("username")
                    row.setChecked(if (group) uid in selected else null)
                    row.contentDescription = c.str("display_name") + if (group && uid in selected) ", выбран" else ""
                    row.setOnClickListener {
                        if (!group) {
                            CoreClient.call("open_direct", JSONObject().put("user_id", uid)) { cv, ce ->
                                if (ce != null) return@call act.toast(ce.message)
                                s.dismiss()
                                act.openChat((cv as JSONObject).str("id"))
                            }
                            return@setOnClickListener
                        }
                        if (uid in selected) selected.remove(uid)
                        else if (selected.size >= (if (channel) 199 else 49))
                            return@setOnClickListener act.toast(if (channel) "В канале может быть до 199 подписчиков" else "В группе может быть до 49 других участников")
                        else selected.add(uid)
                        row.setChecked(uid in selected)
                        create.setText(createText())
                    }
                    rows.addView(row, lp())
                }
            }
        }
        // 0 — direct chat, 1 — group, 2 — channel.
        val setMode = { mode: Int ->
            group = mode != 0
            channel = mode == 2
            direct.isSelected = mode == 0
            grp.isSelected = mode == 1
            chn.isSelected = mode == 2
            title.setLabel(if (channel) "Название канала" else "Название группы")
            title.visibility = if (group) View.VISIBLE else View.GONE
            about.visibility = if (group) View.VISIBLE else View.GONE
            create.visibility = if (group) View.VISIBLE else View.GONE
            create.setText(createText())
            s.setTitle(arrayOf("Новый чат", "Новая группа", "Новый канал")[mode])
            reload()
        }
        direct.setOnClickListener { setMode(0) }
        grp.setOnClickListener { setMode(1) }
        chn.setOnClickListener { setMode(2) }
        addContact.setOnClickListener { newContact(act) { reload() } }
        search.edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) = reload()
            override fun beforeTextChanged(e: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(e: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        create.setOnClickListener {
            if (title.text.isBlank()) return@setOnClickListener act.toast(if (channel) "Введите название канала" else "Введите название группы")
            if (selected.isEmpty() && !channel) return@setOnClickListener act.toast("Выберите участников")
            create.isEnabled = false
            val p = JSONObject().put("title", title.text.trim()).put("description", about.text.trim()).put("member_ids", JSONArray(selected.toList()))
                .put("kind", if (channel) "channel" else "group")
            CoreClient.call("create_group", p) { v, e ->
                create.isEnabled = true
                if (e != null) return@call act.toast(e.message)
                s.dismiss()
                act.openChat((v as JSONObject).str("id"))
            }
        }
        reload()
        s.show()
    }

    // ---- devices ----

    private fun platformName(p: String) = when (p) {
        "windows" -> "Windows"
        "android" -> "Android"
        "ios" -> "iOS"
        else -> p.ifEmpty { "Устройство" }
    }

    private fun seenText(iso: String): String {
        if (iso.isEmpty()) return ""
        return runCatching {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            SimpleDateFormat("dd.MM HH:mm", Locale("ru")).format(f.parse(iso.take(19))!!)
        }.getOrDefault("")
    }

    fun devices(act: MainActivity) {
        val s = Sheet(act, "Устройства")
        val ctx = s.ctx
        val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        s.add(rows, 0)
        s.muted("Отзыв устройства прекращает доставку новых сообщений на него. Уже скачанные на нём копии удалённо не стираются.", 8)
        s.muted("На аккаунте может быть до 10 устройств.", 4)
        s.heading("Вход на компьютере", 18)
        s.muted("Откройте NeoChat на компьютере и отсканируйте QR-код с экрана входа.", 4)
        val scan = PillButton(ctx, "Сканировать QR-код")
        s.add(scan, 12)

        lateinit var reload: () -> Unit
        reload = {
            run {
                CoreClient.call("devices") { v, e ->
                    if (e != null) return@call act.toast(e.message)
                    rows.removeAllViews()
                    for (d in (v as JSONObject).optJSONArray("devices")?.objects() ?: emptyList()) {
                        val row = ListRow(ctx, 44)
                        val name = d.str("name")
                        row.avatar.set(platformName(d.str("platform")), d.str("id"))
                        row.title.text = name
                        var sub = platformName(d.str("platform"))
                        if (d.optBoolean("current")) sub += " · это устройство"
                        else seenText(d.str("last_seen_at")).takeIf { it.isNotEmpty() }?.let { sub += " · активность $it" }
                        row.subtitle.text = sub
                        row.subtitle.maxLines = 2
                        row.contentDescription = "$name, $sub"
                        val canRevoke = !d.optBoolean("current")
                        if (canRevoke) row.setOnClickListener {
                            PopupMenu(act, row).add("Отозвать «$name»", danger = true) {
                                CoreClient.call("revoke_device", JSONObject().put("device_id", d.str("id"))) { _, re ->
                                    if (re != null) return@call act.toast(re.message)
                                    act.toast("Устройство отозвано")
                                    reload()
                                }
                            }.show()
                        } else row.isClickable = false
                        rows.addView(row, lp())
                    }
                }
            }
        }
        fun approve(qr: String) {
            scan.isEnabled = false
            CoreClient.call("qr_login_approve", JSONObject().put("qr", qr)) { _, e ->
                scan.isEnabled = true
                if (e != null) return@call act.toast(e.message)
                act.toast("Вход на компьютере выполнен")
                reload()
            }
        }
        scan.setOnClickListener {
            act.withPermission(android.Manifest.permission.CAMERA) { granted ->
                if (!granted) return@withPermission act.toast("Нет доступа к камере. Разрешите его в настройках")
                QrScanner(act, "neochat-login:") { approve(it) }.show()
            }
        }
        reload()
        s.show()
    }

    // ---- profile ----

    fun editName(act: MainActivity, current: String, saved: () -> Unit) {
        val s = Sheet(act, "Имя")
        val field = FloatingField(s.ctx, "Имя")
        field.setText(current)
        field.edit.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        val save = PillButton(s.ctx, "Сохранить")
        s.add(field, 4)
        s.muted("Имя видят ваши собеседники. Никнейм и ID не меняются.", 8)
        s.add(save, 14)
        save.setOnClickListener {
            if (field.text.isBlank()) return@setOnClickListener act.toast("Введите имя")
            save.isEnabled = false
            CoreClient.call("set_profile", JSONObject().put("display_name", field.text.trim())) { _, e ->
                save.isEnabled = true
                if (e != null) return@call act.toast(e.message)
                s.dismiss()
                saved()
            }
        }
        s.show()
    }

    // ---- settings ----

    fun settings(act: MainActivity) {
        val s = Sheet(act, "Настройки")
        val ctx = s.ctx
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        s.heading("Тема", 0)
        val slider = ThemeSlider(ctx)
        slider.setValue(Theme.mode.ordinal, false)
        slider.onChange = { Theme.setMode(ctx, Theme.Mode.entries[it]) }
        s.add(slider, 10)
        s.muted("Размер текста следует системной настройке шрифта.", 4)
        val notify = ToggleRow(ctx, "Уведомления о новых сообщениях")
        notify.setChecked(prefs.getBoolean("notifications", true), false)
        notify.onToggle = { prefs.edit().putBoolean("notifications", it).apply() }
        s.add(notify, 14)
        s.muted("В уведомлении видно только название чата, текст сообщения не показывается.", 2)
        val twoFactor = ToggleRow(ctx, "Подтверждение входа")
        s.add(twoFactor, 10)
        s.muted("При входе на новом устройстве понадобится код. Он придёт в чат «NeoChat уведомления» на ваших устройствах.", 2)
        CoreClient.call("account_settings") { v, _ -> twoFactor.setChecked((v as? JSONObject)?.optBoolean("two_factor") == true, false) }
        twoFactor.onToggle = { on ->
            CoreClient.call("set_two_factor", JSONObject().put("enabled", on)) { _, e ->
                if (e != null) {
                    act.toast(e.message)
                    twoFactor.setChecked(!on, true)
                }
            }
        }
        val logout = PillButton(ctx, "Выйти из аккаунта", primary = false)
        logout.label.role(Theme.Role.WARNING)
        logout.setOnClickListener {
            PopupMenu(act, logout).add("Выйти: ключи и история на этом телефоне будут удалены", danger = true) {
                s.dismiss()
                CoreClient.call("logout") { _, e ->
                    if (e != null) act.toast(e.message)
                    CoreClient.call("status") { sv, _ -> act.showOnboarding(sv as? JSONObject ?: JSONObject()) }
                }
            }.show()
        }
        s.add(logout, 20)
        s.show()
    }

    // ---- emoji ----

    private val EMOJI = ("😀 😃 😄 😁 😆 😅 😂 🙂 😉 😊 😍 😘 😎 🤔 😐 😴 😢 😭 😡 😱 " +
        "👍 👎 👌 🙏 👏 🙌 💪 🤝 👋 ✌️ ❤️ 🧡 💛 💚 💙 💜 🔥 ✨ 🎉 ☕").split(' ')

    fun emoji(act: MainActivity, pick: (String) -> Unit) {
        act.currentFocus?.clearFocus()
        (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(act.overlay.windowToken, 0)
        val s = Sheet(act, "Эмодзи")
        val grid = GridLayout(s.ctx).apply { columnCount = 8 }
        for (e in EMOJI) {
            val tv = TextView(s.ctx).apply {
                text = e
                textSize = 24f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = e
                background = GlassDrawable(s.ctx, GlassDrawable.Kind.NONE, s.ctx.dpf(14), rim = false)
                pressFeedback()
                setOnClickListener { pick(e); s.dismiss() }
            }
            grid.addView(tv, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0
                height = s.ctx.dp(48)
            })
        }
        s.add(grid, 0)
        s.show()
    }

    // ---- conversation info ----

    fun conversationInfo(act: MainActivity, cid: String) {
        val s = Sheet(act, "Сведения")
        val ctx = s.ctx
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        s.add(content, 0)
        lateinit var reload: () -> Unit
        reload = {
            CoreClient.call("get_conversation", JSONObject().put("conversation_id", cid)) { v, e ->
                if (e != null) return@call act.toast(e.message)
                val c = v as JSONObject
                content.removeAllViews()
                fun add(view: View, top: Int = 12) = content.addView(view, lp().apply { topMargin = ctx.dp(if (content.childCount == 0) 0 else top) })
                fun muted(t: String, top: Int = 8) = add(ctx.label(t, 13.5f, 400, Theme.Role.MUTED), top)
                val head = ListRow(ctx, 56)
                head.isClickable = false
                head.avatar.set(c.str("title"), c.str("peer_user_id").ifEmpty { cid })
                head.title.text = c.str("title")
                head.title.textSize = 18f
                val members = c.optJSONArray("members")?.objects() ?: emptyList()
                if (c.str("kind") == "direct") {
                    val peer = c.str("peer_user_id")
                    head.subtitle.text = members.firstOrNull { !it.optBoolean("me") }?.let { "@" + it.str("username") } ?: ""
                    add(head)
                    CoreClient.call("contact_info", JSONObject().put("user_id", peer)) { iv, ie ->
                        if (ie != null) return@call act.toast(ie.message)
                        val info = iv as JSONObject
                        add(ctx.label("Код безопасности", 15.5f, 600), 16)
                        muted("Сравните код с собеседником при встрече или по другому надёжному каналу. Совпадение означает, что сообщения шифруются для его настоящих ключей.")
                        val num = ctx.label(info.str("safety_number"), 17f, 600, Theme.Role.ACCENT)
                        num.typeface = android.graphics.Typeface.MONOSPACE
                        num.setLineSpacing(0f, 1.3f)
                        num.contentDescription = "Код безопасности: " + info.str("safety_number")
                        add(num, 10)
                        when {
                            info.optBoolean("key_changed") -> {
                                add(ctx.label("Ключ собеседника изменился. Сверьте код перед продолжением", 14f, 600, Theme.Role.WARNING), 12)
                                val accept = PillButton(ctx, "Код сверен, принять новый ключ")
                                accept.setOnClickListener {
                                    CoreClient.call("accept_key_change", JSONObject().put("user_id", peer)) { _, ae ->
                                        if (ae != null) return@call act.toast(ae.message)
                                        act.toast("Новый ключ принят. Сверьте код ещё раз при возможности")
                                        reload()
                                    }
                                }
                                add(accept, 10)
                            }
                            info.optBoolean("verified") -> add(ctx.label("Ключ сверен", 14f, 600, Theme.Role.ACCENT), 12)
                            else -> {
                                add(ctx.label("Ключ ещё не сверен", 14f, 400, Theme.Role.MUTED), 12)
                                val verify = PillButton(ctx, "Отметить как сверенный", primary = false)
                                verify.setOnClickListener {
                                    CoreClient.call("verify_contact", JSONObject().put("user_id", peer)) { _, ve ->
                                        if (ve != null) return@call act.toast(ve.message)
                                        reload()
                                    }
                                }
                                add(verify, 10)
                            }
                        }
                        muted("Файлы хранятся на сервере в зашифрованном виде 30 дней.", 16)
                    }
                    return@call
                }
                val owner = c.optBoolean("is_owner")
                val admin = c.optBoolean("is_admin")
                val channel = c.str("kind") == "channel"
                val gone = c.optBoolean("left") || c.optBoolean("archived")
                val ownerId = c.str("owner_id")
                val admins = c.optJSONArray("admins")?.let { a -> (0 until a.length()).map { a.optString(it) } }?.toSet() ?: emptySet()
                // Everyone except me, optionally without one person or with one more.
                fun others(without: String = "", with: String = "") =
                    JSONArray(members.filter { !it.optBoolean("me") && it.str("user_id") != without }.map { it.str("user_id") } + listOf(with).filter { it.isNotEmpty() })
                fun setMembers(p: JSONObject, done: String) {
                    CoreClient.call("group_set_members", p.put("conversation_id", cid)) { _, ge ->
                        if (ge != null) return@call act.toast(ge.message)
                        act.toast(done)
                        reload()
                    }
                }
                head.subtitle.text = if (channel) "Канал" else "Группа"
                add(head)
                if (c.str("description").isNotEmpty()) add(ctx.label(c.str("description"), 14.5f).apply { setTextIsSelectable(true) }, 10)
                if (c.optBoolean("can_edit_info") && !gone) {
                    val rename = FloatingField(ctx, if (channel) "Название канала" else "Название группы")
                    rename.setText(c.str("title"))
                    add(rename, 16)
                    val about = FloatingField(ctx, "Описание")
                    about.setText(c.str("description"))
                    add(about, 10)
                    val save = PillButton(ctx, "Сохранить название и описание", primary = false)
                    add(save, 10)
                    save.setOnClickListener {
                        if (rename.text.isBlank()) return@setOnClickListener act.toast("Введите название")
                        val p = JSONObject().put("conversation_id", cid).put("title", rename.text.trim()).put("description", about.text.trim())
                        CoreClient.call("group_set_info", p) { _, re ->
                            if (re != null) return@call act.toast(re.message)
                            act.toast("Сохранено")
                            reload()
                        }
                    }
                }
                add(ctx.label((if (channel) "Подписчики: " else "Участники: ") + c.optInt("member_count"), 15.5f, 600), 16)
                for (m in members) {
                    val uid = m.str("user_id")
                    val row = ListRow(ctx, 40)
                    row.avatar.set(m.str("display_name"), uid)
                    row.title.text = m.str("display_name")
                    if (m.optBoolean("key_changed")) {
                        row.subtitle.text = "Ключ изменился — сверьте код в личном чате"
                        row.subtitle.role(Theme.Role.WARNING)
                    } else {
                        var sub = if (m.optBoolean("me")) "это вы" else "@" + m.str("username")
                        if (uid == ownerId) sub += " · владелец" else if (uid in admins) sub += " · админ"
                        if (m.str("tag").isNotEmpty()) sub += " · " + m.str("tag")
                        row.subtitle.text = sub
                    }
                    row.contentDescription = "${row.title.text}, ${row.subtitle.text}"
                    val me = m.optBoolean("me")
                    if (admin && !gone) row.setOnClickListener {
                        val menu = PopupMenu(act, row)
                        menu.add("Тег…") { editTag(act, cid, uid, m.str("display_name"), m.str("tag")) { reload() } }
                        if (owner && !me) {
                            if (uid in admins) menu.add("Снять права админа") {
                                setMembers(JSONObject().put("member_ids", others()).put("admin_ids", JSONArray(admins.filter { it != uid })), "Права админа сняты")
                            } else menu.add("Назначить админом") {
                                setMembers(JSONObject().put("member_ids", others()).put("admin_ids", JSONArray(admins.toList() + uid)), "Назначен админ")
                            }
                        }
                        if (!me && uid != ownerId && (owner || uid !in admins)) menu.add(if (channel) "Удалить из канала" else "Исключить из группы", danger = true) {
                            setMembers(JSONObject().put("member_ids", others(without = uid)), "Исключён. Новые сообщения ему не придут")
                        }
                        menu.show()
                    } else row.isClickable = false
                    add(row, 2)
                }
                if (admin && !gone) {
                    val addBtn = PillButton(ctx, if (channel) "Добавить подписчиков" else "Добавить участников", primary = false)
                    add(addBtn, 12)
                    addBtn.setOnClickListener {
                        CoreClient.call("list_contacts") { lv, le ->
                            if (le != null) return@call act.toast(le.message)
                            val inGroup = members.map { it.str("user_id") }.toSet()
                            val candidates = (lv as JSONArray).objects().filter { it.str("user_id") !in inGroup }
                            val menu = PopupMenu(act, addBtn)
                            if (candidates.isEmpty()) menu.add("Все контакты уже здесь") {}
                            for (cand in candidates) menu.add(cand.str("display_name")) {
                                setMembers(JSONObject().put("member_ids", others(with = cand.str("user_id"))), "Добавлен. Прошлые сообщения ему не видны")
                            }
                            menu.show()
                        }
                    }
                }
                if (admin && !gone && !channel) {
                    add(ctx.label("Права участников", 15.5f, 600), 18)
                    val st = c.optJSONObject("settings") ?: JSONObject()
                    for ((key, label) in listOf(
                        "members_can_send" to "Писать сообщения",
                        "members_can_media" to "Отправлять фото и файлы",
                        "members_can_polls" to "Создавать опросы",
                        "members_can_info" to "Менять название и описание",
                    )) {
                        val t = ToggleRow(ctx, label)
                        t.setChecked(st.optBoolean(key), false)
                        t.onToggle = { on ->
                            CoreClient.call("group_set_settings", JSONObject().put("conversation_id", cid).put(key, on)) { _, se ->
                                if (se != null) {
                                    act.toast(se.message)
                                    reload()
                                }
                            }
                        }
                        add(t, 0)
                    }
                    muted("Владелец и админы могут всё. Сервер не пропускает сообщения тех, кому писать нельзя.")
                } else if (channel) {
                    muted("В канале пишут только владелец и админы. Подписчики читают и голосуют в опросах.", 12)
                }
                muted("Файлы хранятся на сервере в зашифрованном виде 30 дней.", 16)
                if (!gone) {
                    val leave = PillButton(ctx, if (channel) "Выйти из канала" else "Выйти из группы", primary = false)
                    leave.label.role(Theme.Role.WARNING)
                    leave.setOnClickListener {
                        val what = if (owner) (if (channel) "Выйти: канал станет архивным для всех" else "Выйти: группа станет архивной для всех")
                        else "Выйти: новые сообщения перестанут приходить"
                        PopupMenu(act, leave).add(what, danger = true) {
                            CoreClient.call("group_leave", JSONObject().put("conversation_id", cid)) { _, ge ->
                                if (ge != null) return@call act.toast(ge.message)
                                s.dismiss()
                            }
                        }.show()
                    }
                    add(leave, 14)
                }
            }
        }
        reload()
        s.show()
    }

    private fun editTag(act: MainActivity, cid: String, uid: String, name: String, current: String, done: () -> Unit) {
        val s = Sheet(act, "Тег: $name")
        val field = FloatingField(s.ctx, "Например, «дизайн» или «модератор»")
        field.setText(current)
        s.add(field, 4)
        s.muted("Тег виден всем рядом с именем. Пустое поле убирает тег.", 10)
        val ok = PillButton(s.ctx, "Сохранить")
        s.add(ok, 14)
        ok.setOnClickListener {
            CoreClient.call("group_set_tag", JSONObject().put("conversation_id", cid).put("user_id", uid).put("tag", field.text.trim())) { _, e ->
                if (e != null) return@call act.toast(e.message)
                s.dismiss()
                done()
            }
        }
        s.show()
    }

    // ---- polls ----

    fun newPoll(act: MainActivity, cid: String) {
        val s = Sheet(act, "Новый опрос")
        val ctx = s.ctx
        val question = FloatingField(ctx, "Вопрос")
        s.add(question, 4)
        s.heading("Варианты ответа", 14)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        s.add(box, 6)
        val options = ArrayList<FloatingField>()
        val more = PillButton(ctx, "Добавить вариант", primary = false)
        fun addOption() {
            if (options.size >= 10) return
            val f = FloatingField(ctx, "Вариант ${options.size + 1}")
            box.addView(f, lp().apply { topMargin = ctx.dp(if (options.isEmpty()) 0 else 8) })
            options += f
            more.visibility = if (options.size < 10) View.VISIBLE else View.GONE
        }
        addOption()
        addOption()
        s.add(more, 10)
        more.setOnClickListener {
            addOption()
            options.last().edit.requestFocus()
        }
        val anonymous = ToggleRow(ctx, "Анонимное голосование")
        anonymous.setChecked(true, false)
        val multiple = ToggleRow(ctx, "Несколько ответов")
        s.add(anonymous, 8)
        s.add(multiple, 0)
        val send = PillButton(ctx, "Отправить опрос")
        s.add(send, 12)
        send.setOnClickListener {
            val opts = options.map { it.text.trim() }.filter { it.isNotEmpty() }
            if (question.text.isBlank()) return@setOnClickListener act.toast("Введите вопрос")
            if (opts.size < 2) return@setOnClickListener act.toast("Нужно хотя бы два варианта")
            send.isEnabled = false
            val p = JSONObject().put("conversation_id", cid).put("question", question.text.trim()).put("options", JSONArray(opts))
                .put("anonymous", anonymous.checked).put("multiple", multiple.checked)
            CoreClient.call("send_poll", p) { _, e ->
                send.isEnabled = true
                if (e != null) return@call act.toast(e.message)
                s.dismiss()
            }
        }
        s.show()
    }
}
