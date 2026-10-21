package app.neochat.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.neochat.R
import app.neochat.core.CoreClient
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun formatListTime(ms: Long): String {
    if (ms <= 0) return ""
    val t = Calendar.getInstance().apply { timeInMillis = ms }
    val now = Calendar.getInstance()
    fun day(c: Calendar) = c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)
    val diff = day(now) - day(t)
    val ru = Locale("ru")
    return when {
        diff == 0 -> SimpleDateFormat("HH:mm", ru).format(Date(ms))
        diff == 1 -> "Вчера"
        diff in 2..6 -> SimpleDateFormat("EE", ru).format(Date(ms)).replaceFirstChar { it.uppercase() }
        else -> SimpleDateFormat("dd.MM.yy", ru).format(Date(ms))
    }
}

fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

/** List row: avatar, title, subtitle, optional time, unread badge and warning. */
class ListRow(ctx: Context, avatarDp: Int = 52) : LinearLayout(ctx), Themed {
    val avatar = AvatarView(ctx, avatarDp)
    val title = ctx.label("", 16f, 600)
    val subtitle = ctx.label("", 14f, 400, Theme.Role.MUTED)
    val time = ctx.label("", 12.5f, 400, Theme.Role.MUTED)
    private val badge = TextView(ctx)
    val check = ImageView(ctx)
    val pin = ImageView(ctx)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(avatarDp + 20)
        setPadding(dp(12), dp(10), dp(14), dp(10))
        isClickable = true
        isFocusable = true
        background = GlassDrawable(ctx, GlassDrawable.Kind.NONE, ctx.dpf(25), rim = false)
        pressFeedback()
        addView(avatar)
        val text = LinearLayout(ctx).apply { orientation = VERTICAL }
        val top = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        top.addView(title, lp(0, WRAP, 1f))
        pin.setImageResource(R.drawable.ic_pin)
        pin.visibility = GONE
        pin.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        top.addView(pin, lp(dp(15), dp(15)).apply { marginStart = dp(6) })
        top.addView(time, lp(WRAP, WRAP).apply { marginStart = dp(6) })
        text.addView(top, lp())
        val bottom = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        subtitle.maxLines = 1
        subtitle.ellipsize = TextUtils.TruncateAt.END
        bottom.addView(subtitle, lp(0, WRAP, 1f))
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        badge.typeface = weightFace(700)
        badge.gravity = Gravity.CENTER
        badge.minWidth = dp(20)
        badge.setPadding(dp(6), 0, dp(6), 0)
        badge.background = GlassDrawable(ctx, GlassDrawable.Kind.ACTION, ctx.dpf(10))
        bottom.addView(badge, lp(WRAP, dp(20)).apply { marginStart = dp(8) })
        text.addView(bottom, lp().apply { topMargin = dp(5) })
        addView(text, lp(0, WRAP, 1f).apply { marginStart = dp(14) })
        check.setImageResource(R.drawable.ic_tick)
        check.visibility = GONE
        addView(check, lp(dp(22), dp(22)).apply { marginStart = dp(10) })
        setBadge(0)
        applyTheme()
    }

    fun setBadge(n: Int, mentions: Int = 0) {
        badge.visibility = if (n > 0) VISIBLE else GONE
        badge.text = (if (mentions > 0) "@ " else "") + if (n > 99) "99+" else n.toString()
    }

    fun setChecked(on: Boolean?) {
        check.visibility = if (on == true) VISIBLE else if (on == false) INVISIBLE else GONE
    }

    override fun applyTheme() {
        badge.setTextColor(Theme.p.onAccent)
        pin.imageTintList = android.content.res.ColorStateList.valueOf(Theme.p.muted)
        check.imageTintList = android.content.res.ColorStateList.valueOf(Theme.p.accent)
    }
}

/** Plain adapter over JSON rows; [bind] fills a [ListRow]. */
class RowAdapter(private val avatarDp: Int = 52, private val bind: (ListRow, JSONObject) -> Unit) : RecyclerView.Adapter<RowAdapter.H>() {
    class H(val row: ListRow) : RecyclerView.ViewHolder(row)

    var rows: List<JSONObject> = emptyList()
        set(v) {
            // Rebinding during a press would cancel the tap; skip identical refreshes.
            if (v.size == field.size && v.indices.all { v[it].toString() == field[it].toString() }) return
            field = v
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        H(ListRow(parent.context, avatarDp).apply { layoutParams = RecyclerView.LayoutParams(MATCH, WRAP) })

    override fun getItemCount() = rows.size
    override fun onBindViewHolder(h: H, i: Int) = bind(h.row, rows[i])
}

/** Main screen: chats, contacts and profile behind a compact glass bottom bar. */
class HomeScreen(private val act: MainActivity) : Screen {
    private val ctx: Context = act
    private val root = FrameLayout(ctx)
    override val view: View = root
    private val column = LinearLayout(ctx)
    private val status = ctx.label("", 13.5f, 400, Theme.Role.WARNING)
    private val sections = FrameLayout(ctx)
    private val nav = BottomNav(ctx, listOf(R.drawable.ic_chat to "Чаты", R.drawable.ic_people to "Контакты", R.drawable.ic_profile to "Профиль"))
    private var tab = 0

    // chats
    private val chatsPage = LinearLayout(ctx)
    private val chatSearch = SearchBox(ctx, "Поиск чатов")
    private val filterChips = listOf(Chip(ctx, "Все"), Chip(ctx, "Личные"), Chip(ctx, "Группы"))
    private var filter = 0
    private val chatList = RecyclerView(ctx)
    private val chatEmpty = ctx.label("Чатов пока нет. Начните переписку с человеком по его имени пользователя.", 14.5f, 400, Theme.Role.MUTED)
    private var allChats: List<JSONObject> = emptyList()
    private var archiveRow: JSONObject? = null
    private var archiveShown = false
    private val archiveEntry = ListRow(ctx)
    private val archiveIcon = ArchiveIcon()
    private val archiveHeight = dp(72)
    private val archiveWrap = FrameLayout(ctx)
    private var archiveAnim: ValueAnimator? = null
    private var chatGen = 0
    private val selection = ChatSelection(act) { chatAdapter.notifyDataSetChanged() }
    private val chatAdapter: RowAdapter = RowAdapter { row, c -> bindChatRow(act, row, c); selection.bind(row, c) }
    private var presenceAt = 0L
    private val typing: (String) -> Unit = { conv -> if (allChats.any { it.str("id") == conv }) chatAdapter.notifyDataSetChanged() }

    // contacts
    private val contactsPage = LinearLayout(ctx)
    private val contactSearch = SearchBox(ctx, "Поиск контактов")
    private val contactList = RecyclerView(ctx)
    private val contactEmpty = ctx.label("", 14.5f, 400, Theme.Role.MUTED)
    private val sortLabel = ctx.label("Имя А–Я", 14f, 600)
    private var sort = "name-asc"
    private var contactGen = 0
    private val contactAdapter = RowAdapter(48) { row, c -> bindContact(row, c) }

    // profile and settings
    private val settingsPage = PersonProfilePage(act)
    private val profilePage: View = settingsPage.view

    private val refresh = Runnable { reloadChats(false); reloadContacts(false) }

    init {
        column.orientation = LinearLayout.VERTICAL
        root.addView(BackgroundView(ctx), FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        column.addView(header(), lp())
        status.visibility = View.GONE
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        column.addView(status, lp().apply { setMargins(dp(20), dp(2), dp(20), dp(6)) })
        column.addView(sections, lp(MATCH, 0, 1f))
        buildChats()
        buildContacts()
        for ((i, p) in listOf(chatsPage, contactsPage, profilePage).withIndex()) {
            sections.addView(p, FrameLayout.LayoutParams(MATCH, MATCH))
            p.visibility = if (i == 0) View.VISIBLE else View.GONE
        }
        root.addView(nav, FrameLayout.LayoutParams(dp(292), dp(56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        nav.onSelect = { selectTab(it) }
        reloadChats(false)
        reloadContacts(false)
        Typing.listeners += typing
        CoreClient.call("status") { v, _ -> setConnection((v as? JSONObject)?.str("connection") ?: "") }
    }

    private fun dp(v: Int) = ctx.dp(v)

    private fun header(): View {
        val h = LinearLayout(ctx)
        h.gravity = Gravity.CENTER_VERTICAL
        h.setPadding(dp(20), dp(12), dp(12), dp(12))
        h.addView(ImageView(ctx).apply { setImageResource(R.drawable.logo); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, lp(dp(30), dp(30)))
        h.addView(ctx.label("NeoChat", 26f, 700).apply { heading() }, lp(0, WRAP, 1f).apply { marginStart = dp(12) })
        val compose = IconButton(ctx, R.drawable.ic_compose, "Новый чат", glass = true)
        compose.setOnClickListener { Dialogs.newChat(act) }
        h.addView(compose)
        val more = IconButton(ctx, R.drawable.ic_more, "Меню")
        more.setOnClickListener {
            PopupMenu(act, more)
                .add("Выбрать чаты") { allChats.firstOrNull()?.let { selection.begin(it) } }
                .add("Настройки") { nav.select(2); selectTab(2) }
                .add("Мой QR-код") { Qr.showMine(act) }
                .add("Устройства") { Dialogs.devices(act) }
                .show()
        }
        h.addView(more, lp(dp(48), dp(48)).apply { marginStart = dp(4) })
        return h
    }

    private fun list(rv: RecyclerView, adapter: RowAdapter) {
        rv.layoutManager = LinearLayoutManager(ctx)
        rv.adapter = adapter
        rv.itemAnimator = null
        rv.clipToPadding = false
        rv.isVerticalScrollBarEnabled = false
        rv.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    private fun watch(box: SearchBox, onChange: () -> Unit) {
        box.edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = onChange()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    // ---- chats ----

    private fun buildChats() {
        chatsPage.orientation = LinearLayout.VERTICAL
        chatsPage.addView(chatSearch, lp(MATCH, dp(48)).apply { setMargins(dp(16), dp(4), dp(16), 0) })
        watch(chatSearch) { reloadChats(true) }
        val chips = LinearLayout(ctx)
        chips.gravity = Gravity.CENTER_VERTICAL
        filterChips.forEachIndexed { i, c ->
            c.isSelected = i == 0
            c.setOnClickListener {
                filter = i
                filterChips.forEachIndexed { j, o -> o.isSelected = j == i }
                applyChats(true)
            }
            chips.addView(c, lp(WRAP, WRAP).apply { marginEnd = dp(4) })
        }
        chips.addView(View(ctx), lp(0, 1, 1f))
        val sortBtn = IconButton(ctx, R.drawable.ic_sort, "Сортировка чатов")
        sortBtn.setOnClickListener {
            val cur = Prefs.of(ctx).getString("chat_sort", "activity")
            val m = PopupMenu(act, sortBtn)
            for ((k, name) in CHAT_SORTS) m.add(name, checked = k == cur) {
                Prefs.of(ctx).edit().putString("chat_sort", k).apply()
                reloadChats(true)
            }
            m.show()
        }
        chips.addView(sortBtn, lp(dp(44), dp(44)))
        chatsPage.addView(chips, lp().apply { setMargins(dp(12), dp(10), dp(12), dp(4)) })
        list(chatList, chatAdapter)
        androidx.core.view.ViewCompat.addAccessibilityAction(chatList, "Показать архив") { _, _ ->
            chatList.scrollToPosition(0)
            showArchiveEntry(true)
            archiveRow != null
        }
        chatsPage.addView(selection.bar, lp())
        archiveWrap.clipChildren = true
        archiveWrap.addView(archiveEntry, FrameLayout.LayoutParams(MATCH, archiveHeight))
        archiveWrap.visibility = View.GONE
        chatsPage.addView(archiveWrap, lp(MATCH, 0).apply { setMargins(dp(8), 0, dp(8), 0) })
        chatList.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var x = 0f
            private var y = 0f
            private var fromTop = false
            private var dragging = false
            private var armed = false
            private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop

            private fun pull(e: MotionEvent) {
                val distance = ((e.rawY - y - slop).coerceAtLeast(0f) * 0.55f)
                val height = if (distance <= archiveHeight) distance else archiveHeight + (distance - archiveHeight) * 0.18f
                setArchiveHeight(height.toInt().coerceAtMost(archiveHeight + dp(24)))
                if (!armed && height >= archiveHeight * 0.85f) {
                    armed = true
                    archiveEntry.replyHaptic()
                } else if (height < archiveHeight * 0.7f) armed = false
            }

            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    x = e.rawX; y = e.rawY
                    fromTop = !rv.canScrollVertically(-1) && !archiveShown && archiveRow != null && filter == 0 && chatSearch.edit.text.isEmpty()
                    dragging = false
                    armed = false
                }
                if (e.actionMasked == MotionEvent.ACTION_MOVE && fromTop && !selection.active && e.rawY - y > slop && e.rawY - y > kotlin.math.abs(e.rawX - x) * 1.5f) {
                    dragging = true
                    archiveAnim?.cancel()
                    rv.parent.requestDisallowInterceptTouchEvent(true)
                    pull(e)
                    return true
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                if (!dragging) return
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> pull(e)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dragging = false
                        fromTop = false
                        showArchiveEntry(e.actionMasked == MotionEvent.ACTION_UP && armed)
                        rv.parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
        })
        // The archive entry belongs to the very top of the list: scrolling down puts it away.
        chatList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            private var adjusting = false
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || !archiveShown || adjusting) return
                val consumed = minOf(dy, archiveWrap.layoutParams.height)
                archiveAnim?.cancel()
                setArchiveHeight(archiveWrap.layoutParams.height - consumed)
                if (archiveWrap.layoutParams.height == 0) archiveShown = false
                // The row consumes the scroll first, just like the first item of the list.
                adjusting = true
                rv.scrollBy(0, -consumed)
                adjusting = false
            }
        })
        androidx.recyclerview.widget.ItemTouchHelper(ChatSwipe(act, false, { chatAdapter.rows.getOrNull(it) }, { selection.active }) { reloadChats(false) }).attachToRecyclerView(chatList)
        val holder = FrameLayout(ctx)
        holder.addView(chatList, FrameLayout.LayoutParams(MATCH, MATCH))
        chatEmpty.gravity = Gravity.CENTER
        holder.addView(chatEmpty, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply { setMargins(dp(32), 0, dp(32), dp(80)) })
        chatsPage.addView(holder, lp(MATCH, 0, 1f))
    }

    private fun reloadChats(animate: Boolean) {
        val gen = ++chatGen
        val q = chatSearch.edit.text.toString()
        val p = JSONObject().put("query", q).put("sort", Prefs.of(ctx).getString("chat_sort", "activity"))
        CoreClient.call("list_conversations", p) { v, e ->
            if (e != null || gen != chatGen) return@call
            allChats = (v as? JSONArray)?.objects() ?: emptyList()
            if (q.isNotEmpty()) {
                archiveRow = null
                return@call applyChats(animate)
            }
            CoreClient.call("list_conversations", JSONObject().put("archived", true)) { av, ae ->
                if (gen != chatGen) return@call
                archiveRow = if (ae == null) archiveSummary((av as? JSONArray)?.objects() ?: emptyList()) else null
                applyChats(animate)
            }
            refreshPresence()
        }
    }

    /** Presence of direct chat partners, at most once a minute; the core caches it for the list. */
    private fun refreshPresence() {
        val now = System.currentTimeMillis()
        if (now - presenceAt < 60_000) return
        presenceAt = now
        val ids = JSONArray(allChats.filter { it.str("kind") == "direct" }.map { it.str("peer_user_id") }.filter { it.isNotEmpty() })
        if (ids.length() == 0) return
        val tz = java.util.TimeZone.getDefault().getOffset(now) / 60_000
        CoreClient.call("presence", JSONObject().put("user_ids", ids).put("tz_offset", tz)) { _, e -> if (e == null) reloadChats(false) }
    }

    private fun applyChats(animate: Boolean) {
        val shown = allChats.filter {
            when (filter) {
                1 -> it.str("kind") == "direct"
                2 -> it.str("kind") == "group" || it.str("kind") == "channel"
                else -> true
            }
        }
        chatAdapter.rows = shown
        archiveRow?.let { bindArchiveRow(act, archiveEntry, archiveIcon) }
        if (filter != 0 || archiveRow == null || chatSearch.edit.text.isNotEmpty()) showArchiveEntry(false)
        val none = allChats.isEmpty() && archiveRow == null && chatSearch.edit.text.isEmpty()
        chatEmpty.visibility = if (none) View.VISIBLE else View.GONE
        if (animate) fadeIn(chatList)
    }

    // ---- contacts ----

    private fun showArchiveEntry(on: Boolean) {
        val visible = on && archiveRow != null && filter == 0 && chatSearch.edit.text.isEmpty()
        val target = if (visible) archiveHeight else 0
        if (archiveShown == visible && archiveWrap.layoutParams.height == target) return
        archiveShown = visible
        archiveAnim?.cancel()
        if (visible) archiveWrap.visibility = View.VISIBLE
        val from = archiveWrap.layoutParams.height
        archiveAnim = ValueAnimator.ofInt(from, target).apply {
            duration = if (Theme.reducedMotion) 0 else 220
            interpolator = Theme.surfaceCurve
            addUpdateListener {
                setArchiveHeight(it.animatedValue as Int)
            }
            start()
        }
    }

    private fun setArchiveHeight(height: Int) {
        archiveWrap.visibility = if (height > 0) View.VISIBLE else View.GONE
        archiveWrap.layoutParams = archiveWrap.layoutParams.apply { this.height = height }
        archiveEntry.translationY = if (Theme.reducedMotion) 0f else (height - archiveHeight) * 0.45f
        archiveIcon.progress = height.toFloat() / archiveHeight
        archiveEntry.avatar.invalidate()
    }

    fun hideArchiveEntry() = showArchiveEntry(false)

    private fun buildContacts() {
        contactsPage.orientation = LinearLayout.VERTICAL
        contactsPage.addView(contactSearch, lp(MATCH, dp(48)).apply { setMargins(dp(16), dp(4), dp(16), 0) })
        watch(contactSearch) { reloadContacts(true) }
        val scanQr = IconButton(ctx, R.drawable.ic_qr, "Добавить по QR-коду", glass = true)
        scanQr.setOnClickListener { Qr.scanContact(act) }
        val searchRow = contactsPage.getChildAt(0)
        contactsPage.removeView(searchRow)
        val sr = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        sr.addView(searchRow, lp(0, dp(48), 1f))
        sr.addView(scanQr, lp(dp(48), dp(48)).apply { marginStart = dp(8) })
        contactsPage.addView(sr, lp().apply { setMargins(dp(16), dp(4), dp(16), 0) })
        val actions = LinearLayout(ctx)
        val add = PillButton(ctx, "Новый контакт", primary = false, icon = R.drawable.ic_person_add)
        add.setOnClickListener { Dialogs.newContact(act) }
        val calls = PillButton(ctx, "Недавние звонки", primary = false, icon = R.drawable.ic_call_history)
        calls.setOnClickListener { Dialogs.recentCalls(act) }
        actions.addView(add, lp(0, WRAP, 1f).apply { marginEnd = dp(5) })
        actions.addView(calls, lp(0, WRAP, 1f).apply { marginStart = dp(5) })
        contactsPage.addView(actions, lp().apply { setMargins(dp(16), dp(14), dp(16), 0) })
        contactsPage.addView(Divider(ctx), lp(MATCH, dp(1)).apply { setMargins(dp(20), dp(18), dp(20), 0) })
        val sortRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            contentDescription = "Сортировать по"
        }
        sortRow.addView(ctx.label("Сортировать по", 14f, 400, Theme.Role.MUTED))
        sortRow.addView(sortLabel, lp(WRAP, WRAP).apply { marginStart = dp(6) })
        sortRow.addView(View(ctx), lp(0, 1, 1f))
        val dots = IconButton(ctx, R.drawable.ic_more, "Порядок сортировки").apply { setPadding(dp(15), dp(15), dp(15), dp(15)) }
        sortRow.addView(dots, lp(dp(48), dp(48)))
        val openSort = {
            val m = PopupMenu(act, dots)
            for ((k, name) in SORTS) m.add(name, checked = k == sort) {
                sort = k
                sortLabel.text = name
                reloadContacts(true)
            }
            m.show()
        }
        dots.setOnClickListener { openSort() }
        sortRow.setOnClickListener { openSort() }
        contactsPage.addView(sortRow, lp().apply { setMargins(dp(20), dp(4), dp(8), 0) })
        list(contactList, contactAdapter)
        val holder = FrameLayout(ctx)
        holder.addView(contactList, FrameLayout.LayoutParams(MATCH, MATCH))
        contactEmpty.gravity = Gravity.CENTER
        holder.addView(contactEmpty, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply { setMargins(dp(32), 0, dp(32), dp(80)) })
        contactsPage.addView(holder, lp(MATCH, 0, 1f))
    }

    private fun reloadContacts(animate: Boolean) {
        val gen = ++contactGen
        val q = contactSearch.edit.text.toString()
        CoreClient.call("list_contacts", JSONObject().put("query", q).put("sort", sort)) { v, e ->
            if (e != null || gen != contactGen) return@call
            val rows = (v as? JSONArray)?.objects() ?: emptyList()
            contactAdapter.rows = rows
            contactEmpty.text = if (q.isEmpty()) "Контактов пока нет. Добавьте человека по его точному имени пользователя." else "Никого не нашли по этому запросу."
            contactEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            if (animate) fadeIn(contactList)
        }
    }

    private fun bindContact(row: ListRow, c: JSONObject) {
        val name = c.str("display_name")
        row.avatar.set(name, c.str("user_id"), c.str("avatar_path"))
        row.title.text = name
        if (c.optBoolean("key_changed")) {
            row.subtitle.text = "Ключ изменился — сверьте код"
            row.subtitle.role(Theme.Role.WARNING)
        } else {
            row.subtitle.text = "@" + c.str("username")
            row.subtitle.role(Theme.Role.MUTED)
        }
        row.time.text = ""
        row.setBadge(0)
        row.contentDescription = "$name, ${row.subtitle.text}"
        row.setOnClickListener { openDirect(c.str("user_id")) }
    }

    private fun openDirect(uid: String) {
        CoreClient.call("open_direct", JSONObject().put("user_id", uid)) { v, e ->
            if (e != null) return@call act.toast(e.message)
            act.openChat((v as JSONObject).str("id"))
        }
    }

    // ---- navigation ----

    private fun selectTab(i: Int) {
        if (i == tab) return
        val pages = listOf(chatsPage, contactsPage, profilePage)
        val from = pages[tab]
        val to = pages[i]
        val dir = if (i > tab) 1 else -1
        tab = i
        (act.currentFocus)?.clearFocus()
        from.animate().cancel()
        to.animate().cancel()
        if (Theme.reducedMotion) {
            from.visibility = View.GONE
            to.visibility = View.VISIBLE
            to.alpha = 1f
            to.translationX = 0f
            return
        }
        val shift = ctx.dpf(14) * dir
        from.animate().alpha(0f).translationX(-shift).setDuration(220).setInterpolator(Theme.surfaceCurve).withEndAction {
            from.visibility = View.GONE
            from.alpha = 1f
            from.translationX = 0f
        }.start()
        to.visibility = View.VISIBLE
        to.alpha = 0f
        to.translationX = shift
        to.animate().alpha(1f).translationX(0f).setDuration(220).setInterpolator(Theme.surfaceCurve).withEndAction(null).start()
    }

    private fun fadeIn(v: View) {
        if (Theme.reducedMotion) return
        v.animate().cancel()
        v.alpha = 0.55f
        v.animate().alpha(1f).setDuration(140).start()
    }

    private fun setConnection(state: String) {
        when (state) {
            "online", "" -> status.visibility = View.GONE
            "connecting" -> {
                status.text = "Подключение…"
                status.role(Theme.Role.MUTED)
                status.visibility = View.VISIBLE
            }
            else -> {
                status.text = "Нет сети. Сообщения отправятся после подключения"
                status.role(Theme.Role.WARNING)
                status.visibility = View.VISIBLE
            }
        }
    }

    override fun applyInsets(top: Int, bottom: Int, ime: Int) {
        column.setPadding(0, top, 0, 0)
        (nav.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom + dp(8)
        nav.requestLayout()
        val listPad = bottom + dp(80)
        for (rv in listOf(chatList, contactList)) rv.setPadding(dp(8), dp(4), dp(8), listPad)
        settingsPage.setBottomPadding(listPad)
        // The bar stays put while the keyboard is open; lists end above the keyboard.
        nav.visibility = if (ime > 0) View.INVISIBLE else View.VISIBLE
        sections.setPadding(0, 0, 0, if (ime > 0) maxOf(0, ime - bottom - dp(72)) else 0)
    }

    override fun onBack(): Boolean {
        if (selection.clear()) return true
        if (archiveShown) { showArchiveEntry(false); return true }
        if (tab != 0) {
            nav.select(0)
            selectTab(0)
            return true
        }
        return false
    }

    override fun onShown() {
        showArchiveEntry(false)
        root.removeCallbacks(refresh)
        root.post(refresh)
    }

    override fun onEvent(ev: JSONObject) {
        when (ev.str("type")) {
            "connection" -> setConnection(ev.str("state"))
            "conversations_changed", "contacts_changed", "identity_changed", "sync_reset", "messages_changed" -> {
                root.removeCallbacks(refresh)
                root.postDelayed(refresh, 120)
            }
            "account_changed", "settings_changed" -> settingsPage.load()
            "presence" -> {
                root.removeCallbacks(refresh)
                root.postDelayed(refresh, 120)
            }
        }
    }

    companion object {
        val SORTS = listOf("name-asc" to "Имя А–Я", "name-desc" to "Имя Я–А", "recent" to "Дата добавления")
    }
}

class Divider(ctx: Context) : View(ctx), Themed {
    override fun onDraw(c: Canvas) = c.drawColor(Theme.p.rim)
    override fun applyTheme() = invalidate()
}

class TintedIcon(ctx: Context, icon: Int, private val role: Theme.Role = Theme.Role.TEXT) : ImageView(ctx), Themed {
    init {
        setImageResource(icon)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        applyTheme()
    }

    override fun applyTheme() {
        imageTintList = android.content.res.ColorStateList.valueOf(role.color())
    }
}

/** Three equal items, icons above labels; the highlight moves over 280 ms. */
class BottomNav(ctx: Context, items: List<Pair<Int, String>>) : LinearLayout(ctx), Themed {
    var onSelect: ((Int) -> Unit)? = null
    private var selected = 0
    private var pos = 0f
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1) }
    private val icons = mutableListOf<ImageView>()
    private val labels = mutableListOf<TextView>()

    init {
        orientation = HORIZONTAL
        background = GlassDrawable(ctx, radius = ctx.dpf(28))
        setPadding(dp(4), dp(4), dp(4), dp(4))
        setWillNotDraw(false)
        items.forEachIndexed { i, (icon, text) ->
            val cell = LinearLayout(ctx).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = text
                setOnClickListener { select(i); onSelect?.invoke(i) }
            }
            val iv = ImageView(ctx).apply { setImageResource(icon) }
            cell.addView(iv, LayoutParams(dp(22), dp(22)))
            val tv = TextView(ctx).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = weightFace(600)
                maxLines = 1
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            cell.addView(tv, LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
            icons += iv
            labels += tv
            addView(cell, LayoutParams(0, MATCH, 1f))
        }
        updateState()
        applyTheme()
    }

    fun select(i: Int) {
        if (i == selected) return
        selected = i
        updateState()
        anim?.cancel()
        val d = Theme.ms(280)
        if (d == 0L) {
            pos = i.toFloat()
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(pos, i.toFloat()).apply {
            duration = d
            interpolator = Theme.surfaceCurve
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun updateState() {
        for (i in 0 until childCount) getChildAt(i).isSelected = i == selected
        applyTheme()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cw = (width - paddingLeft - paddingRight) / childCount.toFloat()
        val l = paddingLeft + cw * pos
        val r = ctx().dpf(24)
        paint.color = Theme.p.glassStrong
        c.drawRoundRect(l, paddingTop.toFloat(), l + cw, (height - paddingBottom).toFloat(), r, r, paint)
        strokePaint.color = Theme.p.rim
        c.drawRoundRect(l, paddingTop.toFloat(), l + cw, (height - paddingBottom).toFloat(), r, r, strokePaint)
    }

    private fun ctx() = context

    override fun applyTheme() {
        for (i in icons.indices) {
            val col = if (i == selected) Theme.p.text else Theme.p.muted
            icons[i].imageTintList = android.content.res.ColorStateList.valueOf(col)
            labels[i].setTextColor(col)
        }
        invalidate()
    }
}
