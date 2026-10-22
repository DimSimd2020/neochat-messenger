package app.neochat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.neochat.R
import app.neochat.core.CoreClient
import org.json.JSONArray
import org.json.JSONObject

/** Who is typing where, for a few seconds after each hint from the core. */
object Typing {
    private const val SHOW_MS = 6000L
    private val until = HashMap<String, Pair<Long, LinkedHashMap<String, String>>>()
    private val main = Handler(Looper.getMainLooper())
    val listeners = ArrayList<(String) -> Unit>()
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            for (id in until.keys.toList()) {
                if (until[id]!!.first <= now) until.remove(id)
                listeners.toList().forEach { it(id) }
            }
            if (until.isNotEmpty()) main.postDelayed(this, 400)
        }
    }

    fun onEvent(ev: JSONObject) {
        val conv = ev.str("conversation_id")
        val user = ev.str("user_id")
        val now = System.currentTimeMillis()
        val entry = until[conv]?.takeIf { it.first > now } ?: (0L to LinkedHashMap())
        entry.second[user] = ev.str("name")
        until[conv] = (now + SHOW_MS) to entry.second
        listeners.toList().forEach { it(conv) }
        main.removeCallbacks(tick)
        main.postDelayed(tick, 400)
    }

    /** A message from someone ends their typing at once. */
    fun clear(conv: String) {
        if (until.remove(conv) != null) listeners.toList().forEach { it(conv) }
    }

    /** "печатает…" for a direct chat, "Имя печатает…" in groups, or null. */
    fun text(conv: String, group: Boolean): String? {
        val e = until[conv] ?: return null
        if (e.first < System.currentTimeMillis()) return null
        val dots = if (Theme.reducedMotion) "…" else ".".repeat((System.currentTimeMillis() / 400 % 3).toInt() + 1).padEnd(3)
        if (!group) return "печатает$dots"
        val names = e.second.values.map { it.substringBefore(' ').ifEmpty { "Кто-то" } }
        return when (names.size) {
            0 -> null
            1 -> "${names[0]} печатает$dots"
            else -> "${names[0]} и ещё ${names.size - 1} печатают$dots"
        }
    }
}

/** Fills a chat list row: title, preview or typing, pin, online dot, unread. */
fun bindChatRow(act: MainActivity, row: ListRow, c: JSONObject) {
    val title = c.str("title")
    row.avatar.set(title, c.str("peer_user_id").ifEmpty { c.str("id") }, c.str("avatar_path"))
    row.avatar.online = c.optJSONObject("presence")?.optBoolean("online") == true
    row.title.text = title
    row.groupIcon.visibility = if (c.str("kind") == "group") View.VISIBLE else View.GONE
    row.time.text = formatListTime(c.optLong("last_activity"))
    row.pin.visibility = if (c.optBoolean("pinned")) View.VISIBLE else View.GONE
    val unread = c.optInt("unread")
    row.bound = c.str("id")
    row.setBadge(unread, c.optInt("mention_count"))
    var preview = c.str("last_preview")
    var role = Theme.Role.MUTED
    val draft = c.str("draft").trim().replace('\n', ' ')
    val typing = Typing.text(c.str("id"), c.str("kind") != "direct")
    when {
        (c.optJSONArray("key_changed_users")?.length() ?: 0) > 0 -> {
            preview = "Ключ собеседника изменился"
            role = Theme.Role.WARNING
        }
        typing != null -> {
            preview = typing
            role = Theme.Role.ACCENT
        }
        draft.isNotEmpty() -> {
            preview = "Черновик: $draft"
            role = Theme.Role.ACCENT
        }
    }
    row.subtitle.text = preview.replace('\n', ' ')
    row.subtitle.role(role)
    row.contentDescription = buildString {
        append(title)
        if (c.optBoolean("pinned")) append(", закреплён")
        if (row.avatar.online) append(", в сети")
        if (unread > 0) append(", непрочитанных: $unread")
        if (preview.isNotEmpty()) append(", ").append(preview)
        if (row.time.text.isNotEmpty()) append(", ").append(row.time.text)
    }
    row.setOnClickListener { act.openChat(c.str("id")) }
}

/** The "Архив" entry at the top of the main list. */
fun bindArchiveRow(act: MainActivity, row: ListRow, icon: ArchiveIcon) {
    row.avatar.setIcon(icon)
    row.avatar.online = false
    row.title.text = "Архив"
    row.time.text = ""
    row.pin.visibility = View.GONE
    (row.subtitle.parent as View).visibility = View.GONE
    row.time.visibility = View.GONE
    row.setBadge(0)
    row.setChecked(null)
    row.setOnLongClickListener(null)
    row.contentDescription = "Архив"
    row.setOnClickListener {
        if (AppLock.archiveLock(act)) AppLock.prompt(act, "Вход в архив", cancellable = true) { act.openArchive() }
        else act.openArchive()
    }
}

/** Builds the synthetic archive row from the archived chats, or null when empty. */
fun archiveSummary(archived: List<JSONObject>): JSONObject? {
    if (archived.isEmpty()) return null
    return JSONObject().put("_archive", true).put("count", archived.size)
        .put("unread", archived.sumOf { it.optInt("unread") })
}

val CHAT_SORTS = listOf("activity" to "По активности", "unread" to "Сначала непрочитанные", "name" to "По названию")

/**
 * Swipes on a chat row: right to left archives (or returns from the archive),
 * left to right pins or unpins. The row springs back; the list then reloads.
 */
class ChatSwipe(
    private val act: MainActivity,
    private val archive: Boolean,
    private val rowAt: (Int) -> JSONObject?,
    private val selectionActive: () -> Boolean = { false },
    private val done: () -> Unit,
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = act.dpf(13)
        typeface = weightFace(600)
    }
    /** The row whose swipe is past the threshold; releasing it acts. */
    private var armed: RecyclerView.ViewHolder? = null
    private val grow = android.view.animation.OvershootInterpolator(2.2f)

    override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
        if (selectionActive()) return 0
        val c = rowAt(vh.bindingAdapterPosition) ?: return 0
        if (c.optBoolean("_archive") || c.str("kind") == "system") return 0
        return if (archive) ItemTouchHelper.LEFT else ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
    }

    override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder) = false
    override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = 0.33f
    override fun getSwipeEscapeVelocity(defaultValue: Float) = defaultValue * 4

    override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
        val pos = vh.bindingAdapterPosition
        val c = rowAt(pos) ?: return
        vh.bindingAdapter?.notifyItemChanged(pos)
        val id = c.str("id")
        if (direction == ItemTouchHelper.LEFT) {
            val toArchive = !archive
            CoreClient.call("set_archived", JSONObject().put("conversation_id", id).put("archived", toArchive)) { _, e ->
                if (e != null) return@call act.toast(e.message)
                act.overlay.replyHaptic()
                if (toArchive) archiveUndo(act, listOf(id), done)
                else act.toast("Чат возвращён из архива")
                done()
            }
        } else {
            val pin = !c.optBoolean("pinned")
            CoreClient.call("set_pinned", JSONObject().put("conversation_id", id).put("pinned", pin)) { _, e ->
                if (e != null) return@call act.toast(e.message)
                Haptics.confirm(act.overlay)
                done()
            }
        }
    }

    override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder, dx: Float, dy: Float, state: Int, active: Boolean) {
        val v = vh.itemView
        if (active) {
            val past = kotlin.math.abs(dx) >= v.width * getSwipeThreshold(vh)
            if (past && armed !== vh) Haptics.threshold(v)
            armed = if (past) vh else if (armed === vh) null else armed
        }
        if (dx != 0f) {
            val left = dx > 0
            val item = rowAt(vh.bindingAdapterPosition)
            val label = when {
                !left && archive -> "Вернуть"
                !left -> "В архив"
                item?.optBoolean("pinned") == true -> "Открепить"
                else -> "Закрепить"
            }
            val icon = if (left) R.drawable.ic_pin else R.drawable.ic_archive
            paint.color = if (left) Theme.p.action else if (Theme.p.dark) 0xFF4A5A75.toInt() else 0xFF8193AE.toInt()
            val r = RectF(if (left) v.left.toFloat() + act.dpf(8) else v.right + dx, v.top + act.dpf(4), if (left) v.left + dx else v.right - act.dpf(8), v.bottom - act.dpf(4))
            c.drawRoundRect(r, act.dpf(22), act.dpf(22), paint)
            val d = act.getDrawable(icon)!!.mutate()
            d.setTint(android.graphics.Color.WHITE)
            val s = act.dp(24)
            val cy = (v.top + v.bottom) / 2
            val tw = text.measureText(label)
            val x = if (left) v.left + act.dp(28) else v.right - act.dp(28) - maxOf(s, tw.toInt())
            val ix = x + ((maxOf(s.toFloat(), tw) - s) / 2).toInt()
            // The icon grows with the finger and overshoots a little as it arrives.
            val k = ((kotlin.math.abs(dx) - act.dpf(56)) / act.dpf(44)).coerceIn(0f, 1f)
            if (k > 0f) {
                val size = s * (if (Theme.reducedMotion) 1f else grow.getInterpolation(k))
                val icx = ix + s / 2f
                val icy = cy - s / 2f + act.dp(2)
                d.setBounds((icx - size / 2).toInt(), (icy - size / 2).toInt(), (icx + size / 2).toInt(), (icy + size / 2).toInt())
                d.draw(c)
                text.color = android.graphics.Color.WHITE
                text.alpha = (255 * k).toInt()
                c.drawText(label, x + (maxOf(s.toFloat(), tw) - tw) / 2, cy + act.dpf(20), text)
            }
        }
        super.onChildDraw(c, rv, vh, dx, dy, state, active)
    }
}

fun archiveUndo(act: MainActivity, ids: List<String>, done: () -> Unit) {
    if (ids.isEmpty()) return
    act.toast(if (ids.size == 1) "Чат в архиве" else "Чаты в архиве", "Отмена") {
        fun restore(i: Int) {
            if (i == ids.size) { act.overlay.replyHaptic(); done(); return }
            CoreClient.call("set_archived", JSONObject().put("conversation_id", ids[i]).put("archived", false)) { _, e ->
                if (e != null) { act.toast(e.message); done(); return@call }
                restore(i + 1)
            }
        }
        restore(0)
    }
}

/** Archived chats with their own sort order; swipe left returns a chat. */
class ArchiveScreen(private val act: MainActivity) : Screen {
    private val ctx: Context = act
    private val root = FrameLayout(ctx)
    override val view: View = root
    private val column = LinearLayout(ctx)
    private val list = RecyclerView(ctx)
    private val empty = ctx.label("В архиве пусто. Смахните чат влево, чтобы убрать его сюда.", 14.5f, 400, Theme.Role.MUTED)
    private var rows: List<JSONObject> = emptyList()
    private val selection = ChatSelection(act) { adapter.notifyDataSetChanged() }
    private val adapter: RowAdapter = RowAdapter { row, c -> bindChatRow(act, row, c); selection.bind(row, c) }
    private var gen = 0
    private val refresh = Runnable { reload() }
    private val typing: (String) -> Unit = { conv -> if (rows.any { it.str("id") == conv }) adapter.notifyDataSetChanged() }

    init {
        column.orientation = LinearLayout.VERTICAL
        root.addView(BackgroundView(ctx), FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        val h = LinearLayout(ctx)
        h.gravity = Gravity.CENTER_VERTICAL
        h.setPadding(act.dp(8), act.dp(8), act.dp(8), act.dp(8))
        val back = IconButton(ctx, R.drawable.ic_back, "Назад к чатам")
        back.setOnClickListener { act.closeArchive() }
        h.addView(back)
        h.addView(ctx.label("Архив", 22f, 700).apply { heading() }, lp(0, WRAP, 1f).apply { marginStart = act.dp(8) })
        val sort = IconButton(ctx, R.drawable.ic_sort, "Сортировка архива")
        sort.setOnClickListener {
            val cur = Prefs.of(ctx).getString("archive_sort", "activity")
            val m = PopupMenu(act, sort)
            for ((k, name) in CHAT_SORTS) m.add(name, checked = k == cur) {
                Prefs.of(ctx).edit().putString("archive_sort", k).apply()
                reload()
            }
            m.show()
        }
        h.addView(sort)
        column.addView(h, lp())
        column.addView(selection.bar, lp())
        list.layoutManager = LinearLayoutManager(ctx)
        list.adapter = adapter
        list.itemAnimator = null
        list.clipToPadding = false
        ItemTouchHelper(ChatSwipe(act, true, { rows.getOrNull(it) }, { selection.active }) { reload() }).attachToRecyclerView(list)
        val holder = FrameLayout(ctx)
        holder.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        empty.gravity = Gravity.CENTER
        holder.addView(empty, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER).apply { setMargins(act.dp(32), 0, act.dp(32), act.dp(80)) })
        column.addView(holder, lp(MATCH, 0, 1f))
        reload()
    }

    private fun reload() {
        val g = ++gen
        val p = JSONObject().put("archived", true).put("sort", Prefs.of(ctx).getString("archive_sort", "activity"))
        CoreClient.call("list_conversations", p) { v, e ->
            if (e != null || g != gen) return@call
            rows = (v as? JSONArray)?.objects() ?: emptyList()
            adapter.rows = rows
            empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun applyInsets(top: Int, bottom: Int, ime: Int) {
        column.setPadding(0, top, 0, 0)
        list.setPadding(act.dp(8), act.dp(4), act.dp(8), bottom + act.dp(16))
    }

    override fun onBack(): Boolean {
        if (selection.clear()) return true
        act.closeArchive()
        return true
    }

    override fun onShown() {
        Typing.listeners += typing
        root.post(refresh)
    }

    override fun onHidden() {
        Typing.listeners -= typing
    }

    override fun onEvent(ev: JSONObject) {
        when (ev.str("type")) {
            "conversations_changed", "contacts_changed", "messages_changed", "presence" -> {
                root.removeCallbacks(refresh)
                root.postDelayed(refresh, 120)
            }
        }
    }
}
