package app.neochat.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
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
        val first = FloatingField(ctx, "Имя (обязательно)")
        val last = FloatingField(ctx, "Фамилия (необязательно)")
        val user = FloatingField(ctx, "Username")
        user.edit.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val err = errorLabel(ctx)
        val submit = PillButton(ctx, "Добавить")
        s.add(first, 4)
        s.add(last, 10)
        s.add(user, 10)
        s.muted("Поиск идёт только по точному username: публичного каталога нет.", 10)
        s.add(err, 8)
        s.add(submit, 14)
        submit.setOnClickListener {
            if (first.text.isBlank()) return@setOnClickListener err.show("Введите имя")
            if (user.text.isBlank()) return@setOnClickListener err.show("Введите username")
            err.show("")
            submit.isEnabled = false
            val p = JSONObject().put("username", user.text.trim().removePrefix("@")).put("first_name", first.text.trim()).put("last_name", last.text.trim())
            CoreClient.call("resolve_contact", p) { v, e ->
                submit.isEnabled = true
                if (e != null) return@call err.show(if (e.code == "not_found") "Пользователь с таким username не найден" else e.message)
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
        var group = false
        val selected = LinkedHashSet<String>()
        val modes = LinearLayout(ctx)
        val direct = Chip(ctx, "Личный").apply { isSelected = true }
        val grp = Chip(ctx, "Группа")
        modes.addView(direct)
        modes.addView(grp, lp(WRAP, WRAP).apply { marginStart = ctx.dp(4) })
        modes.addView(View(ctx), lp(0, 1, 1f))
        val addContact = Chip(ctx, "Новый контакт")
        modes.addView(addContact)
        s.add(modes, 0)
        val title = FloatingField(ctx, "Название группы")
        title.visibility = View.GONE
        s.add(title, 10)
        val search = SearchBox(ctx, "Поиск контактов")
        s.add(search, 10)
        val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        s.add(rows, 6)
        val empty = ctx.label("Контактов пока нет. Добавьте человека по username.", 14f, 400, Theme.Role.MUTED)
        s.add(empty, 6)
        val create = PillButton(ctx, "Создать группу")
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
                        else if (selected.size >= 15) return@setOnClickListener act.toast("В группе может быть до 15 других участников")
                        else selected.add(uid)
                        row.setChecked(uid in selected)
                        create.setText(if (selected.isEmpty()) "Создать группу" else "Создать группу (${selected.size})")
                    }
                    rows.addView(row, lp())
                }
            }
        }
        val setGroup = { g: Boolean ->
            group = g
            direct.isSelected = !g
            grp.isSelected = g
            title.visibility = if (g) View.VISIBLE else View.GONE
            create.visibility = if (g) View.VISIBLE else View.GONE
            s.setTitle(if (g) "Новая группа" else "Новый чат")
            reload()
        }
        direct.setOnClickListener { setGroup(false) }
        grp.setOnClickListener { setGroup(true) }
        addContact.setOnClickListener { newContact(act) { reload() } }
        search.edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) = reload()
            override fun beforeTextChanged(e: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(e: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        create.setOnClickListener {
            if (title.text.isBlank()) return@setOnClickListener act.toast("Введите название группы")
            if (selected.isEmpty()) return@setOnClickListener act.toast("Выберите участников")
            create.isEnabled = false
            CoreClient.call("create_group", JSONObject().put("title", title.text.trim()).put("member_ids", JSONArray(selected.toList()))) { v, e ->
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
        val linkBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        linkBox.addView(ctx.label("Привязать новое устройство", 15.5f, 600), lp())
        linkBox.addView(
            ctx.label("На новом устройстве выберите «Привязать», затем вставьте сюда скопированный код привязки.", 13.5f, 400, Theme.Role.MUTED),
            lp().apply { topMargin = ctx.dp(6) }
        )
        val code = FloatingField(ctx, "Код привязки")
        linkBox.addView(code, lp().apply { topMargin = ctx.dp(10) })
        val approve = PillButton(ctx, "Подтвердить привязку")
        linkBox.addView(approve, lp().apply { topMargin = ctx.dp(12) })
        s.add(linkBox, 18)

        lateinit var reload: () -> Unit
        reload = {
            CoreClient.call("status") { sv, _ ->
                val primary = (sv as? JSONObject)?.optBoolean("primary") == true
                linkBox.visibility = if (primary) View.VISIBLE else View.GONE
                CoreClient.call("devices") { v, e ->
                    if (e != null) return@call act.toast(e.message)
                    rows.removeAllViews()
                    for (d in (v as JSONObject).optJSONArray("devices")?.objects() ?: emptyList()) {
                        val row = ListRow(ctx, 44)
                        val name = d.str("name")
                        row.avatar.set(platformName(d.str("platform")), d.str("id"))
                        row.title.text = name
                        var sub = platformName(d.str("platform"))
                        if (d.optBoolean("primary")) sub += " · основное"
                        if (d.optBoolean("current")) sub += " · это устройство"
                        else seenText(d.str("last_seen_at")).takeIf { it.isNotEmpty() }?.let { sub += " · активность $it" }
                        row.subtitle.text = sub
                        row.subtitle.maxLines = 2
                        row.contentDescription = "$name, $sub"
                        val canRevoke = primary && !d.optBoolean("current")
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
        approve.setOnClickListener {
            val qr = code.text.trim()
            if (!qr.startsWith("neochat-link:")) return@setOnClickListener act.toast("Это не код привязки NeoChat")
            approve.isEnabled = false
            CoreClient.call("link_approve", JSONObject().put("qr", qr)) { _, e ->
                approve.isEnabled = true
                if (e != null) return@call act.toast(e.message)
                code.setText("")
                act.toast("Устройство привязано")
                reload()
            }
        }
        reload()
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
        val receipts = ToggleRow(ctx, "Отчёты о прочтении")
        s.add(receipts, 10)
        s.muted("Если выключить, собеседники не увидят, что вы прочитали сообщение.", 2)
        CoreClient.call("get_settings") { v, _ -> receipts.setChecked((v as? JSONObject)?.optBoolean("read_receipts", true) != false, false) }
        receipts.onToggle = { on ->
            CoreClient.call("set_settings", JSONObject().put("read_receipts", on)) { _, e ->
                if (e != null) {
                    act.toast(e.message)
                    receipts.setChecked(!on, true)
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
                val gone = c.optBoolean("left") || c.optBoolean("archived")
                head.subtitle.text = "Группа"
                add(head)
                add(ctx.label("Участники: ${c.optInt("member_count")}", 15.5f, 600), 16)
                for (m in members) {
                    val row = ListRow(ctx, 40)
                    row.avatar.set(m.str("display_name"), m.str("user_id"))
                    row.title.text = m.str("display_name")
                    if (m.optBoolean("key_changed")) {
                        row.subtitle.text = "Ключ изменился — сверьте код в личном чате"
                        row.subtitle.role(Theme.Role.WARNING)
                    } else row.subtitle.text = if (m.optBoolean("me")) "это вы" else "@" + m.str("username")
                    row.contentDescription = "${row.title.text}, ${row.subtitle.text}"
                    if (owner && !gone && !m.optBoolean("me")) row.setOnClickListener {
                        PopupMenu(act, row).add("Исключить из группы", danger = true) {
                            val ids = JSONArray(members.filter { !it.optBoolean("me") && it.str("user_id") != m.str("user_id") }.map { it.str("user_id") })
                            CoreClient.call("group_set_members", JSONObject().put("conversation_id", cid).put("member_ids", ids)) { _, ge ->
                                if (ge != null) return@call act.toast(ge.message)
                                act.toast("Участник исключён. Новые сообщения ему не придут")
                                reload()
                            }
                        }.show()
                    } else row.isClickable = false
                    add(row, 2)
                }
                if (owner && !gone) {
                    val rename = FloatingField(ctx, "Название группы")
                    rename.setText(c.str("title"))
                    add(rename, 16)
                    val buttons = LinearLayout(ctx)
                    val save = PillButton(ctx, "Переименовать", primary = false)
                    val addBtn = PillButton(ctx, "Добавить участников", primary = false)
                    buttons.addView(save, lp(0, WRAP, 1f).apply { marginEnd = ctx.dp(5) })
                    buttons.addView(addBtn, lp(0, WRAP, 1f).apply { marginStart = ctx.dp(5) })
                    add(buttons, 10)
                    save.setOnClickListener {
                        if (rename.text.isBlank()) return@setOnClickListener act.toast("Введите название группы")
                        CoreClient.call("group_rename", JSONObject().put("conversation_id", cid).put("title", rename.text.trim())) { _, re ->
                            if (re != null) return@call act.toast(re.message)
                            act.toast("Название изменено")
                            reload()
                        }
                    }
                    addBtn.setOnClickListener {
                        CoreClient.call("list_contacts") { lv, le ->
                            if (le != null) return@call act.toast(le.message)
                            val inGroup = members.map { it.str("user_id") }.toSet()
                            val candidates = (lv as JSONArray).objects().filter { it.str("user_id") !in inGroup }
                            val menu = PopupMenu(act, addBtn)
                            if (candidates.isEmpty()) menu.add("Все контакты уже в группе") {}
                            for (cand in candidates) menu.add(cand.str("display_name")) {
                                val ids = JSONArray(members.filter { !it.optBoolean("me") }.map { it.str("user_id") } + cand.str("user_id"))
                                CoreClient.call("group_set_members", JSONObject().put("conversation_id", cid).put("member_ids", ids)) { _, ge ->
                                    if (ge != null) return@call act.toast(ge.message)
                                    act.toast("Участник добавлен. Прошлые сообщения ему не видны")
                                    reload()
                                }
                            }
                            menu.show()
                        }
                    }
                }
                muted("Файлы хранятся на сервере в зашифрованном виде 30 дней.", 16)
                if (!gone) {
                    val leave = PillButton(ctx, "Выйти из группы", primary = false)
                    leave.label.role(Theme.Role.WARNING)
                    leave.setOnClickListener {
                        val what = if (owner) "Выйти: группа станет архивной для всех" else "Выйти: новые сообщения перестанут приходить"
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
}
