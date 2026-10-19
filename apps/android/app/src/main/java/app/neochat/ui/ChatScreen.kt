package app.neochat.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.neochat.FilesProvider
import app.neochat.Notifier
import app.neochat.R
import app.neochat.core.CoreClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val RU = Locale("ru")

fun dateTitle(ms: Long): String {
    val t = Calendar.getInstance().apply { timeInMillis = ms }
    val now = Calendar.getInstance()
    val sameYear = t.get(Calendar.YEAR) == now.get(Calendar.YEAR)
    val day = SimpleDateFormat(if (sameYear) "d MMMM" else "d MMMM yyyy", RU).format(Date(ms))
    val diff = (now.get(Calendar.YEAR) * 400 + now.get(Calendar.DAY_OF_YEAR)) - (t.get(Calendar.YEAR) * 400 + t.get(Calendar.DAY_OF_YEAR))
    return when (diff) {
        0 -> "Сегодня, $day"
        1 -> "Вчера, $day"
        else -> day
    }
}

fun sizeText(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> String.format(RU, "%.0f КБ", bytes / 1024.0)
    else -> String.format(RU, "%.1f МБ", bytes / 1048576.0)
}

fun kindText(mime: String, name: String): String {
    val ext = name.substringAfterLast('.', "").uppercase()
    return when {
        mime == "application/pdf" -> "PDF-документ"
        mime.startsWith("image/") -> "Изображение"
        mime.startsWith("video/") -> "Видео"
        mime.startsWith("audio/") -> "Аудио"
        ext.isNotEmpty() && ext.length <= 5 -> "Файл $ext"
        else -> "Файл"
    }
}

fun statusText(m: JSONObject) = when (m.str("status")) {
    "queued" -> "В очереди"
    "sent" -> "На сервере"
    "delivered" -> "Доставлено"
    "read" -> "Прочитано"
    "failed" -> "Не отправлено"
    else -> ""
}

private fun hhmm(ms: Long) = SimpleDateFormat("HH:mm", RU).format(Date(ms))
private fun dayKey(ms: Long) = Calendar.getInstance().run { timeInMillis = ms; get(Calendar.YEAR) * 400 + get(Calendar.DAY_OF_YEAR) }

/** One conversation: header capsule, history, composer. */
class ChatScreen(private val act: MainActivity, val conversationId: String) : Screen {
    private val ctx: Context = act
    private val root = FrameLayout(ctx)
    override val view: View = root
    private val column = LinearLayout(ctx)

    // header
    private val header = LinearLayout(ctx)
    private val avatar = AvatarView(ctx, 40)
    private val title = ctx.label("", 16.5f, 700)
    private val subtitle = ctx.label("", 12.5f, 400, Theme.Role.MUTED)
    private val searchBar = SearchBox(ctx, "Найти в этой переписке")
    private val searchWrap = FrameLayout(ctx)
    private var searchOpen = false
    private val notice = LinearLayout(ctx)
    private val noticeText = ctx.label("", 14f, 400, Theme.Role.WARNING)
    private val noticeAction = ctx.label("Сверить код", 14f, 600, Theme.Role.ACCENT)

    // history
    private val list = RecyclerView(ctx)
    private val layout = LinearLayoutManager(ctx).apply { stackFromEnd = true }
    private val adapter = MessageAdapter()
    private var messages: List<JSONObject> = emptyList()
    private val known = HashSet<String>()
    private var loading = false
    private var hasMore = true
    private var searching = false
    private var gen = 0
    private val jump = IconButton(ctx, R.drawable.ic_down, "К новым сообщениям", glass = true)
    private var conv = JSONObject()

    // composer
    private val composer = LinearLayout(ctx)
    private val replyBar = LinearLayout(ctx)
    private val replyText = ctx.label("", 13.5f, 400, Theme.Role.MUTED)
    private var replyTo: JSONObject? = null
    private val capsule = LinearLayout(ctx)
    private val edit = ThemedEdit(ctx)
    private val send = IconButton(ctx, R.drawable.ic_send, "Отправить", 44, iconRole = Theme.Role.ON_ACCENT)
    private val sendSlot = FrameLayout(ctx)
    private var sendShown = 0f
    private var sendAnim: ValueAnimator? = null
    private var blocked = false
    private val gone = ctx.label("", 14f, 400, Theme.Role.MUTED)
    private var shown = false
    private var readPending = false

    private val reload = Runnable { load(false) }

    init {
        column.orientation = LinearLayout.VERTICAL
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        buildHeader()
        column.addView(header, lp().apply { setMargins(dp(12), dp(8), dp(12), 0) })
        searchWrap.addView(searchBar, FrameLayout.LayoutParams(MATCH, dp(48)))
        searchWrap.visibility = View.GONE
        column.addView(searchWrap, lp().apply { setMargins(dp(12), dp(8), dp(12), 0) })
        buildNotice()
        column.addView(notice, lp().apply { setMargins(dp(12), dp(8), dp(12), 0) })

        list.layoutManager = layout
        list.adapter = adapter
        list.itemAnimator = null
        list.clipToPadding = false
        list.isVerticalScrollBarEnabled = false
        list.setPadding(dp(8), dp(8), dp(8), dp(8))
        list.contentDescription = "Сообщения"
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!searching && layout.findFirstVisibleItemPosition() <= 3 && dy < 0) load(true)
                if (atBottom()) {
                    jump.visibility = View.GONE
                    markRead()
                }
            }
        })
        val listHolder = FrameLayout(ctx)
        listHolder.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        jump.visibility = View.GONE
        jump.setOnClickListener { scrollToBottom(true) }
        listHolder.addView(jump, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(12)) })
        column.addView(listHolder, lp(MATCH, 0, 1f))

        buildComposer()
        column.addView(composer, lp().apply { setMargins(dp(10), 0, dp(10), dp(8)) })
        gone.gravity = Gravity.CENTER
        gone.visibility = View.GONE
        column.addView(gone, lp().apply { setMargins(dp(24), dp(8), dp(24), dp(16)) })

        refreshConversation(true)
        load(false)
    }

    private fun dp(v: Int) = ctx.dp(v)

    // ---- header ----

    private fun buildHeader() {
        header.gravity = Gravity.CENTER_VERTICAL
        header.background = GlassDrawable(ctx, radius = ctx.dpf(32))
        header.setPadding(dp(4), dp(6), dp(4), dp(6))
        val back = IconButton(ctx, R.drawable.ic_back, "Вернуться к чатам")
        back.setOnClickListener { act.closeChat() }
        header.addView(back)
        val who = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            minimumHeight = dp(48)
            setOnClickListener { Dialogs.conversationInfo(act, conversationId) }
        }
        who.addView(avatar)
        val names = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        subtitle.maxLines = 1
        subtitle.ellipsize = TextUtils.TruncateAt.END
        names.addView(title)
        names.addView(subtitle, lp().apply { topMargin = dp(2) })
        who.addView(names, lp(0, WRAP, 1f).apply { marginStart = dp(10) })
        header.addView(who, lp(0, WRAP, 1f))
        val search = IconButton(ctx, R.drawable.ic_search, "Поиск в переписке")
        search.setOnClickListener { toggleSearch() }
        header.addView(search)
        val more = IconButton(ctx, R.drawable.ic_more, "Меню диалога")
        more.setOnClickListener {
            PopupMenu(act, more)
                .add("Сведения") { Dialogs.conversationInfo(act, conversationId) }
                .add("Поиск в переписке") { if (!searchOpen) toggleSearch() }
                .show()
        }
        header.addView(more)
        searchBar.edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = search(s.toString())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun toggleSearch() {
        searchOpen = !searchOpen
        expand(searchWrap, searchOpen)
        if (searchOpen) {
            searchBar.edit.requestFocus()
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchBar.edit, 0)
        } else {
            searchBar.edit.setText("")
            hideKeyboard()
        }
    }

    private fun buildNotice() {
        notice.gravity = Gravity.CENTER_VERTICAL
        notice.background = GlassDrawable(ctx, GlassDrawable.Kind.WARNING, ctx.dpf(20))
        notice.setPadding(dp(16), dp(10), dp(8), dp(10))
        notice.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        notice.addView(noticeText, lp(0, WRAP, 1f))
        noticeAction.minHeight = dp(44)
        noticeAction.gravity = Gravity.CENTER
        noticeAction.setPadding(dp(10), 0, dp(10), 0)
        noticeAction.isClickable = true
        noticeAction.isFocusable = true
        noticeAction.setOnClickListener { Dialogs.conversationInfo(act, conversationId) }
        notice.addView(noticeAction)
        notice.visibility = View.GONE
    }

    /** Reveal or fold a strip over 180 ms; the state applies immediately. */
    private fun expand(v: View, open: Boolean) {
        v.animate().cancel()
        if (Theme.reducedMotion) {
            v.visibility = if (open) View.VISIBLE else View.GONE
            v.alpha = 1f
            v.translationY = 0f
            return
        }
        if (open) {
            if (v.visibility == View.VISIBLE && v.alpha == 1f) return
            v.visibility = View.VISIBLE
            v.alpha = 0f
            v.translationY = -ctx.dpf(8)
            v.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(Theme.surfaceCurve).withEndAction(null).start()
        } else if (v.visibility == View.VISIBLE) {
            v.animate().alpha(0f).translationY(-ctx.dpf(8)).setDuration(180).withEndAction {
                v.visibility = View.GONE
                v.alpha = 1f
                v.translationY = 0f
            }.start()
        }
    }

    private fun refreshConversation(first: Boolean) {
        CoreClient.call("get_conversation", JSONObject().put("conversation_id", conversationId)) { v, e ->
            if (e != null) return@call act.toast(e.message)
            conv = v as JSONObject
            title.text = conv.str("title")
            avatar.set(conv.str("title"), conv.str("peer_user_id").ifEmpty { conversationId })
            val members = conv.optJSONArray("members") ?: JSONArray()
            subtitle.text = if (conv.str("kind") == "group") memberCount(conv.optInt("member_count"))
            else members.objects().firstOrNull { !it.optBoolean("me") }?.let { "@" + it.str("username") } ?: ""
            val left = conv.optBoolean("left") || conv.optBoolean("archived")
            composer.visibility = if (left) View.GONE else View.VISIBLE
            gone.visibility = if (left) View.VISIBLE else View.GONE
            gone.text = if (conv.optBoolean("archived")) "Группа в архиве. Новые сообщения недоступны" else "Вы вышли из этой группы"
            blocked = (conv.optJSONArray("key_changed_users")?.length() ?: 0) > 0 || !conv.optBoolean("can_send", true)
            updateSend(false)
            val n = conv.str("notice").takeIf { conv.opt("notice") is String }
            noticeText.text = n ?: ""
            noticeAction.visibility = if ((conv.optJSONArray("key_changed_users")?.length() ?: 0) > 0) View.VISIBLE else View.GONE
            expand(notice, !n.isNullOrEmpty())
            if (first) {
                val d = conv.str("draft")
                if (d.isNotEmpty()) {
                    edit.setText(d)
                    edit.setSelection(d.length)
                }
            }
        }
    }

    private fun memberCount(n: Int): String {
        val m10 = n % 10
        val m100 = n % 100
        val w = when {
            m10 == 1 && m100 != 11 -> "участник"
            m10 in 2..4 && m100 !in 12..14 -> "участника"
            else -> "участников"
        }
        return "$n $w"
    }

    // ---- history ----

    private fun atBottom() = !list.canScrollVertically(1)

    private fun scrollToBottom(smooth: Boolean) {
        jump.visibility = View.GONE
        if (adapter.itemCount == 0) return
        if (smooth && !Theme.reducedMotion) list.smoothScrollToPosition(adapter.itemCount - 1)
        else list.scrollToPosition(adapter.itemCount - 1)
    }

    private fun load(older: Boolean) {
        if (searching) return
        if (older && (loading || !hasMore)) return
        loading = true
        val g = gen
        val p = JSONObject().put("conversation_id", conversationId)
        if (older) {
            p.put("before", messages.firstOrNull()?.optLong("local_id") ?: 0L).put("limit", 50)
        } else {
            p.put("limit", (messages.size + 20).coerceIn(50, 200))
        }
        CoreClient.call("list_messages", p) { v, e ->
            if (g != gen) return@call
            loading = false
            if (e != null) return@call act.toast(e.message)
            val page = (v as? JSONArray)?.objects() ?: emptyList()
            if (older && page.size < 50) hasMore = false
            apply(page, older)
        }
    }

    private fun apply(page: List<JSONObject>, older: Boolean) {
        val first = messages.isEmpty()
        val wasBottom = first || atBottom()
        val merged = LinkedHashMap<String, JSONObject>()
        if (older) {
            for (m in page) merged[m.str("id")] = m
            for (m in messages) merged[m.str("id")] = m
        } else {
            // A fresh page replaces its range; older loaded messages stay above it.
            val oldest = page.firstOrNull()?.optLong("local_id") ?: Long.MAX_VALUE
            for (m in messages) if (m.optLong("local_id") < oldest) merged[m.str("id")] = m
            for (m in page) merged[m.str("id")] = m
        }
        val appended = merged.keys.filter { it !in known }
        val anchorPos = layout.findFirstVisibleItemPosition()
        val anchorTop = list.getChildAt(0)?.top ?: 0
        val before = adapter.itemCount
        messages = merged.values.toList()
        known.clear()
        known.addAll(merged.keys)
        adapter.animate = if (!older && !first) appended.toHashSet() else emptySet()
        adapter.setItems(build(messages))
        if (older) {
            layout.scrollToPositionWithOffset(anchorPos + (adapter.itemCount - before), anchorTop)
            return
        }
        val newMsgs = page.filter { it.str("id") in appended }
        val own = newMsgs.any { it.optBoolean("outgoing") }
        if (wasBottom || own) {
            list.post { scrollToBottom(!first) }
        } else if (newMsgs.any { !it.optBoolean("outgoing") }) {
            jump.visibility = View.VISIBLE
        }
        list.post { if (atBottom()) markRead() }
    }

    private fun build(ms: List<JSONObject>): List<Any> {
        val out = ArrayList<Any>()
        var lastDay = -1
        for ((i, m) in ms.withIndex()) {
            val at = m.optLong("sent_at")
            val day = dayKey(at)
            if (!searching && day != lastDay) {
                out += dateTitle(at)
                lastDay = day
            }
            val prev = ms.getOrNull(i - 1)
            val next = ms.getOrNull(i + 1)
            val sameAsPrev = prev != null && prev.str("sender_user_id") == m.str("sender_user_id") && dayKey(prev.optLong("sent_at")) == day
            val sameAsNext = next != null && next.str("sender_user_id") == m.str("sender_user_id") && dayKey(next.optLong("sent_at")) == day
            out += Item(m, firstInRun = !sameAsPrev, lastInRun = !sameAsNext)
        }
        return out
    }

    private fun search(q: String) {
        gen++
        if (q.isBlank()) {
            if (searching) {
                searching = false
                messages = emptyList()
                known.clear()
                hasMore = true
                load(false)
            }
            return
        }
        searching = true
        val g = gen
        CoreClient.call("search_messages", JSONObject().put("conversation_id", conversationId).put("query", q)) { v, e ->
            if (g != gen) return@call
            if (e != null) return@call act.toast(e.message)
            val found = (v as? JSONArray)?.objects() ?: emptyList()
            adapter.animate = emptySet()
            adapter.setItems(if (found.isEmpty()) listOf("Ничего не нашли") else build(found))
            list.post { scrollToBottom(false) }
        }
    }

    private fun markRead() {
        if (!shown || readPending || searching || !atBottom() || !App.foreground) return
        if (conv.optInt("unread") == 0 && messages.none { !it.optBoolean("outgoing") && it.str("status") != "read" }) return
        readPending = true
        CoreClient.call("mark_read", JSONObject().put("conversation_id", conversationId)) { _, _ -> readPending = false }
    }

    private data class Item(val m: JSONObject, val firstInRun: Boolean, val lastInRun: Boolean)

    private inner class MessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<Any> = emptyList()
        var animate: Set<String> = emptySet()

        fun setItems(v: List<Any>) {
            items = v
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int) = if (items[position] is String) 0 else 1
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 0) object : RecyclerView.ViewHolder(DateRow(ctx)) {}
            else object : RecyclerView.ViewHolder(BubbleRow(ctx)) {}

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val it = items[position]
            if (it is String) return (h.itemView as DateRow).set(it)
            val item = it as Item
            val row = h.itemView as BubbleRow
            row.bind(item)
            val id = item.m.str("id")
            if (id in animate && !Theme.reducedMotion) {
                animate = animate - id
                row.alpha = 0f
                row.translationY = ctx.dpf(12)
                row.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(Theme.surfaceCurve).start()
            } else {
                row.animate().cancel()
                row.alpha = 1f
                row.translationY = 0f
            }
        }
    }

    private inner class DateRow(ctx: Context) : LinearLayout(ctx) {
        private val text = ctx.label("", 12.5f, 500, Theme.Role.MUTED)

        init {
            gravity = Gravity.CENTER
            layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            setPadding(0, dp(16), 0, dp(10))
            addView(Divider(ctx), LayoutParams(dp(40), dp(1)))
            addView(text, LayoutParams(WRAP, WRAP).apply { setMargins(dp(14), 0, dp(14), 0) })
            addView(Divider(ctx), LayoutParams(dp(40), dp(1)))
            isFocusable = true
            text.heading()
        }

        fun set(t: String) {
            text.text = t
            contentDescription = t
        }
    }

    private inner class BubbleRow(ctx: Context) : LinearLayout(ctx) {
        private val av = AvatarView(ctx, 30)
        private val col = LinearLayout(ctx)
        private val sender = ctx.label("", 12.5f, 600, Theme.Role.MUTED)
        private val bubble = LinearLayout(ctx)
        private val bg = GlassDrawable(ctx, GlassDrawable.Kind.INCOMING, ctx.dpf(23))
        private val quote = LinearLayout(ctx)
        private val quoteName = ctx.label("", 12.5f, 600)
        private val quoteText = ctx.label("", 13f, 400)
        private val file = LinearLayout(ctx)
        private val fileIcon = FrameLayout(ctx)
        private val fileName = ctx.label("", 14.5f, 600)
        private val fileMeta = ctx.label("", 12.5f, 400)
        private val fileAction = ImageView(ctx)
        private val text = ctx.label("", 15.5f)
        private val time = ctx.label("", 11.5f)
        private val status = ImageView(ctx)
        private val retry = ctx.label("Не отправлено · Повторить", 12.5f, 600, Theme.Role.WARNING)
        private var m = JSONObject()

        init {
            orientation = HORIZONTAL
            layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            gravity = Gravity.BOTTOM
            addView(av, LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(8) })
            col.orientation = VERTICAL
            sender.setPadding(dp(14), 0, 0, dp(4))
            col.addView(sender, LayoutParams(WRAP, WRAP))
            bubble.orientation = VERTICAL
            bubble.background = bg
            bubble.setPadding(dp(15), dp(9), dp(14), dp(7))
            quote.orientation = VERTICAL
            quote.background = GlassDrawable(ctx, GlassDrawable.Kind.NONE, ctx.dpf(12)).apply { rim = true }
            quote.setPadding(dp(10), dp(6), dp(10), dp(6))
            quote.addView(quoteName, LayoutParams(WRAP, WRAP))
            quoteText.maxLines = 2
            quoteText.ellipsize = TextUtils.TruncateAt.END
            quote.addView(quoteText, LayoutParams(WRAP, WRAP))
            bubble.addView(quote, LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(6) })
            file.gravity = Gravity.CENTER_VERTICAL
            fileIcon.background = GlassDrawable(ctx, GlassDrawable.Kind.STRONG, ctx.dpf(12))
            fileIcon.addView(TintedIcon(ctx, R.drawable.ic_file, Theme.Role.ACCENT), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            file.addView(fileIcon, LayoutParams(dp(46), dp(52)))
            val fcol = LinearLayout(ctx).apply { orientation = VERTICAL }
            fileName.maxLines = 2
            fileName.ellipsize = TextUtils.TruncateAt.MIDDLE
            fcol.addView(fileName)
            fcol.addView(fileMeta, LayoutParams(WRAP, WRAP).apply { topMargin = dp(3) })
            file.addView(fcol, LayoutParams(0, WRAP, 1f).apply { setMargins(dp(12), 0, dp(8), 0) })
            fileAction.setImageResource(R.drawable.ic_download)
            fileAction.setPadding(dp(8), dp(8), dp(8), dp(8))
            fileAction.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            file.addView(fileAction, LayoutParams(dp(40), dp(40)))
            file.minimumWidth = dp(220)
            bubble.addView(file, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) })
            // Bubbles take at most ~78% of the screen width.
            val maxText = (ctx.resources.displayMetrics.widthPixels * 0.78f).toInt() - dp(29 + 38)
            text.maxWidth = maxText
            quoteText.maxWidth = maxText
            fileName.maxWidth = maxText - dp(46 + 60)
            text.setTextIsSelectable(false)
            text.setLineSpacing(0f, 1.2f)
            bubble.addView(text, LayoutParams(WRAP, WRAP))
            val meta = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
            meta.addView(time)
            meta.addView(status, LayoutParams(dp(16), dp(16)).apply { marginStart = dp(4) })
            bubble.addView(meta, LayoutParams(WRAP, WRAP).apply { gravity = Gravity.END; topMargin = dp(2) })
            col.addView(bubble, LayoutParams(WRAP, WRAP))
            retry.minHeight = dp(40)
            retry.gravity = Gravity.CENTER_VERTICAL
            retry.isClickable = true
            retry.setOnClickListener { retry(m) }
            col.addView(retry, LayoutParams(WRAP, WRAP).apply { setMargins(dp(6), dp(2), dp(6), 0) })
            addView(col, LayoutParams(WRAP, WRAP))
            bubble.isClickable = true
            bubble.isLongClickable = true
            bubble.setOnLongClickListener { menu(m, bubble); true }
            bubble.setOnClickListener { if (m.str("kind") == "file") openFile(m) }
        }

        fun bind(item: Item) {
            m = item.m
            val out = m.optBoolean("outgoing")
            val group = conv.str("kind") == "group"
            gravity = (if (out) Gravity.END else Gravity.START) or Gravity.BOTTOM
            setPadding(if (out) dp(48) else dp(4), if (item.firstInRun) dp(8) else dp(3), if (out) dp(4) else dp(32), 0)
            av.visibility = if (group && !out) (if (item.lastInRun) VISIBLE else INVISIBLE) else GONE
            av.set(m.str("sender_name"), m.str("sender_user_id"))
            sender.visibility = if (group && !out && item.firstInRun) VISIBLE else GONE
            sender.text = m.str("sender_name")
            (col.layoutParams as LayoutParams).gravity = if (out) Gravity.END else Gravity.START
            col.gravity = if (out) Gravity.END else Gravity.START
            val undecryptable = m.str("kind") == "undecryptable"
            bg.kind = if (out) GlassDrawable.Kind.OUTGOING else GlassDrawable.Kind.INCOMING
            val fg = if (out) Theme.p.onAccent else Theme.p.text
            val muted = if (out) Theme.p.outgoingMuted else Theme.p.muted
            text.setTextColor(fg)
            text.setTag(Theme.TAG_ROLE, null)
            time.setTextColor(muted)
            quoteName.setTextColor(fg)
            quoteText.setTextColor(muted)
            fileName.setTextColor(fg)
            fileMeta.setTextColor(muted)
            fileAction.imageTintList = android.content.res.ColorStateList.valueOf(fg)

            val rep = m.optJSONObject("reply_to")
            quote.visibility = if (rep != null) VISIBLE else GONE
            if (rep != null) {
                quoteName.text = rep.str("sender_name")
                quoteText.text = if (rep.str("kind") == "file") "Файл" else rep.str("text").replace('\n', ' ')
            }
            val f = m.optJSONObject("file")
            file.visibility = if (f != null) VISIBLE else GONE
            if (f != null) {
                fileName.text = f.str("name")
                fileMeta.text = sizeText(f.optLong("size")) + " · " + kindText(f.str("mime"), f.str("name"))
                fileAction.setImageResource(if (f.optBoolean("downloaded") || out) R.drawable.ic_file else R.drawable.ic_download)
            }
            val body = when {
                undecryptable -> "Сообщение не удалось расшифровать"
                else -> m.str("text")
            }
            text.visibility = if (body.isEmpty()) GONE else VISIBLE
            text.text = body
            if (undecryptable) text.setTextColor(Theme.p.warning)
            time.text = hhmm(m.optLong("sent_at"))
            val st = m.str("status")
            status.visibility = if (out) VISIBLE else GONE
            when (st) {
                "queued" -> status.setImageResource(R.drawable.ic_clock)
                "sent" -> status.setImageResource(R.drawable.ic_tick)
                "delivered", "read" -> status.setImageResource(R.drawable.ic_check)
                "failed" -> status.setImageResource(R.drawable.ic_alert)
            }
            status.imageTintList = android.content.res.ColorStateList.valueOf(
                when (st) {
                    "read" -> Theme.p.onAccent
                    "failed" -> Theme.p.warning
                    else -> muted
                }
            )
            status.alpha = if (st == "delivered") 0.75f else 1f
            retry.visibility = if (out && st == "failed") VISIBLE else GONE
            bubble.contentDescription = buildString {
                append(m.str("sender_name")).append(", ").append(time.text).append(": ")
                if (f != null) append("файл ").append(f.str("name")).append(". ")
                append(body)
                if (out && statusText(m).isNotEmpty()) append(". ").append(statusText(m))
                if (group && out && m.optInt("delivered_count") > 0) append(", доставлено: ").append(m.optInt("delivered_count"))
            }
            invalidate()
        }
    }

    private fun menu(m: JSONObject, anchor: View) {
        val pm = PopupMenu(act, anchor)
        val ok = m.str("kind") != "undecryptable"
        val gone = composer.visibility != View.VISIBLE
        if (ok && !gone) pm.add("Ответить") { setReply(m) }
        if (ok && m.str("text").isNotEmpty()) pm.add("Копировать текст") {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("NeoChat", m.str("text")))
            if (android.os.Build.VERSION.SDK_INT < 33) act.toast("Текст скопирован")
        }
        if (m.str("kind") == "file") {
            pm.add("Открыть файл") { openFile(m) }
            pm.add("Сохранить как…") { saveFile(m) }
        }
        if (m.str("status") == "failed") pm.add("Повторить отправку") { retry(m) }
        pm.add("Удалить у себя", danger = true) {
            CoreClient.call("delete_local_message", JSONObject().put("message_id", m.str("id"))) { _, e ->
                if (e != null) act.toast(e.message)
            }
        }
        pm.show()
    }

    private fun retry(m: JSONObject) {
        CoreClient.call("retry_message", JSONObject().put("message_id", m.str("id"))) { _, e -> if (e != null) act.toast(e.message) }
    }

    private fun openFile(m: JSONObject) {
        act.toast("Расшифровываем файл…")
        CoreClient.call("download_file", JSONObject().put("message_id", m.str("id"))) { v, e ->
            if (e != null) return@call act.toast(e.message)
            val path = (v as JSONObject).str("path")
            val f = m.optJSONObject("file") ?: JSONObject()
            val uri = FilesProvider.uriFor(ctx, File(path))
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, f.str("mime").ifEmpty { "application/octet-stream" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                act.startActivity(Intent.createChooser(intent, f.str("name")))
            } catch (_: Exception) {
                act.toast("Нет приложения, чтобы открыть этот файл")
            }
        }
    }

    private fun saveFile(m: JSONObject) {
        val f = m.optJSONObject("file") ?: return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(f.str("mime").ifEmpty { "application/octet-stream" })
            .putExtra(Intent.EXTRA_TITLE, f.str("name"))
        act.startForResult(intent) { code, data ->
            val dest = data?.data
            if (code != Activity.RESULT_OK || dest == null) return@startForResult
            CoreClient.call("download_file", JSONObject().put("message_id", m.str("id"))) { v, e ->
                if (e != null) return@call act.toast(e.message)
                val src = File((v as JSONObject).str("path"))
                Thread {
                    val ok = runCatching {
                        ctx.contentResolver.openOutputStream(dest)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
                    }.isSuccess
                    act.runOnUiThread { act.toast(if (ok) "Файл сохранён" else "Не удалось сохранить файл") }
                }.start()
            }
        }
    }

    // ---- composer ----

    private fun buildComposer() {
        composer.orientation = LinearLayout.VERTICAL
        replyBar.gravity = Gravity.CENTER_VERTICAL
        replyBar.background = GlassDrawable(ctx, radius = ctx.dpf(20))
        replyBar.setPadding(dp(16), dp(4), dp(4), dp(4))
        replyText.maxLines = 1
        replyText.ellipsize = TextUtils.TruncateAt.END
        replyBar.addView(replyText, lp(0, WRAP, 1f))
        val cancel = IconButton(ctx, R.drawable.ic_close, "Отменить ответ")
        cancel.setOnClickListener { setReply(null) }
        replyBar.addView(cancel)
        replyBar.visibility = View.GONE
        composer.addView(replyBar, lp().apply { bottomMargin = dp(6) })

        capsule.gravity = Gravity.BOTTOM
        capsule.background = GlassDrawable(ctx, radius = ctx.dpf(31))
        capsule.setPadding(dp(4), dp(4), dp(4), dp(4))
        val attach = IconButton(ctx, R.drawable.ic_attach, "Прикрепить файл")
        attach.setOnClickListener { pickFile() }
        capsule.addView(attach)
        edit.hint = "Написать сообщение…"
        edit.contentDescription = "Текст сообщения"
        edit.background = null
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        // Return inserts a line break on the phone; sending is the separate button.
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        edit.maxLines = 5
        edit.minHeight = dp(48)
        edit.setPadding(dp(8), dp(12), dp(8), dp(12))
        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updateSend(true)
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        capsule.addView(edit, lp(0, WRAP, 1f))
        val emoji = IconButton(ctx, R.drawable.ic_smile, "Выбрать эмодзи")
        emoji.setOnClickListener { Dialogs.emoji(act) { insert(it) } }
        capsule.addView(emoji)
        send.background = GlassDrawable(ctx, GlassDrawable.Kind.ACTION, ctx.dpf(22))
        send.setOnClickListener { sendText() }
        sendSlot.clipChildren = false
        sendSlot.addView(send, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, dp(2), dp(2), dp(2)) })
        capsule.addView(sendSlot, lp(0, dp(48)))
        composer.addView(capsule, lp())
        updateSend(false)
    }

    private fun insert(s: String) {
        val st = maxOf(edit.selectionStart, 0)
        val en = maxOf(edit.selectionEnd, 0)
        edit.text.replace(minOf(st, en), maxOf(st, en), s)
    }

    /** The send button slides in 48 dp over 240 ms when there is text; reduced motion jumps. */
    private fun updateSend(animated: Boolean) {
        val target = if (edit.text.isNotBlank()) 1f else 0f
        send.isEnabled = !blocked
        send.alpha = if (blocked) 0.5f else 1f
        send.contentDescription = "Отправить"
        send.stateDescription = if (blocked) "Отправка приостановлена: ключ собеседника изменился" else null
        if (target == sendShown && sendAnim == null) return applySend()
        sendAnim?.cancel()
        val d = if (animated) Theme.ms(240) else 0L
        if (d == 0L) {
            sendAnim = null
            sendShown = target
            return applySend()
        }
        sendAnim = ValueAnimator.ofFloat(sendShown, target).apply {
            duration = d
            interpolator = Theme.surfaceCurve
            addUpdateListener { sendShown = it.animatedValue as Float; applySend() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (sendAnim === a) sendAnim = null
                }
            })
            start()
        }
    }

    private fun applySend() {
        val lpSlot = sendSlot.layoutParams
        lpSlot.width = (dp(48) * sendShown).toInt()
        sendSlot.layoutParams = lpSlot
        send.translationX = ctx.dpf(48) * (1 - sendShown)
        sendSlot.visibility = if (sendShown == 0f) View.GONE else View.VISIBLE
        send.importantForAccessibility = if (sendShown < 1f && edit.text.isBlank()) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun setReply(m: JSONObject?) {
        replyTo = m
        if (m != null) {
            val t = if (m.str("kind") == "file") "Файл" else m.str("text").replace('\n', ' ')
            replyText.text = "Ответ: ${m.str("sender_name")} — $t"
            edit.requestFocus()
        }
        expand(replyBar, m != null)
    }

    private fun sendText() {
        val text = edit.text.toString().trim()
        if (text.isEmpty() || blocked) return
        val p = JSONObject().put("conversation_id", conversationId).put("text", text)
        replyTo?.let { p.put("reply_to", it.str("id")) }
        // Stored locally first: the field clears at once and the queue delivers it.
        edit.setText("")
        setReply(null)
        CoreClient.call("send_text", p) { _, e ->
            if (e == null) {
                CoreClient.call("set_draft", JSONObject().put("conversation_id", conversationId).put("text", ""))
                return@call
            }
            act.toast(e.message)
            if (edit.text.isEmpty()) edit.setText(text)
            refreshConversation(false)
        }
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        act.startForResult(intent) { code, data ->
            if (code != Activity.RESULT_OK || data == null) return@startForResult
            val uris = ArrayList<Uri>()
            data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
            data.data?.let { if (uris.isEmpty()) uris += it }
            for (u in uris) sendUri(u)
        }
    }

    /** Copies the picked document to app storage, hands it to the core, then removes the copy. */
    private fun sendUri(uri: Uri) {
        val cr = ctx.contentResolver
        var name = "file"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > 25L * 1024 * 1024) return act.toast("Файл больше 25 МБ")
        val mime = cr.getType(uri) ?: "application/octet-stream"
        Thread {
            val dir = File(ctx.cacheDir, "outgoing").apply { mkdirs() }
            val tmp = File.createTempFile("send", ".bin", dir)
            val copied = runCatching { cr.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } } }.isSuccess
            act.runOnUiThread {
                if (!copied) {
                    tmp.delete()
                    return@runOnUiThread act.toast("Не удалось прочитать файл")
                }
                val p = JSONObject().put("conversation_id", conversationId).put("path", tmp.absolutePath).put("mime", mime).put("name", name)
                CoreClient.call("send_file", p) { _, e ->
                    tmp.delete()
                    if (e != null) act.toast(e.message)
                }
            }
        }.start()
    }

    private fun hideKeyboard() {
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun saveDraft() {
        CoreClient.call("set_draft", JSONObject().put("conversation_id", conversationId).put("text", edit.text.toString()))
    }

    override fun applyInsets(top: Int, bottom: Int, ime: Int) {
        column.setPadding(0, top, 0, maxOf(bottom, ime))
        if (ime > 0 && atBottom()) list.post { scrollToBottom(false) }
    }

    override fun onBack(): Boolean {
        if (searchOpen) {
            toggleSearch()
            return true
        }
        return false
    }

    override fun onShown() {
        shown = true
        Notifier.visibleConversation = conversationId
        list.post { markRead() }
    }

    override fun onHidden() {
        shown = false
        if (Notifier.visibleConversation == conversationId) Notifier.visibleConversation = null
        hideKeyboard()
        saveDraft()
    }

    override fun onEvent(ev: JSONObject) {
        when (ev.str("type")) {
            "messages_changed" -> if (ev.str("conversation_id") == conversationId) {
                root.removeCallbacks(reload)
                root.postDelayed(reload, 60)
            }
            "sync_reset" -> {
                root.removeCallbacks(reload)
                root.postDelayed(reload, 60)
                refreshConversation(false)
            }
            "conversations_changed", "contacts_changed", "identity_changed" -> refreshConversation(false)
            "connection" -> refreshConversation(false)
        }
    }

    /** Called by the activity when it returns to the foreground. */
    fun onResumed() = markRead()

    private object App {
        val foreground get() = (app.neochat.App.instance).inForeground
    }
}
