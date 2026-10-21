package app.neochat.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.util.Base64
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.recyclerview.widget.DiffUtil
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

/** Telegram-like formatting from the core's markdown spans. */
fun spanned(m: JSONObject): CharSequence {
    val spans = m.optJSONArray("spans")
    if (spans == null || spans.length() == 0) return m.str("text")
    val b = SpannableStringBuilder()
    for (s in spans.objects()) {
        val pre = s.optBoolean("pre")
        // A code block always stands on its own lines.
        if (pre && b.isNotEmpty() && b[b.length - 1] != '\n') b.append('\n')
        val st = b.length
        b.append(s.str("text"))
        val en = b.length
        fun set(span: Any) = b.setSpan(span, st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (s.optBoolean("bold")) set(StyleSpan(Typeface.BOLD))
        if (s.optBoolean("italic")) set(StyleSpan(Typeface.ITALIC))
        if (s.optBoolean("strike")) set(StrikethroughSpan())
        if (pre || s.optBoolean("code")) {
            set(TypefaceSpan("monospace"))
            set(BackgroundColorSpan(0x30808080))
        }
        if (s.has("link")) set(URLSpan(s.str("link")))
        if (pre) b.append('\n')
    }
    while (b.isNotEmpty() && b[b.length - 1] == '\n') b.delete(b.length - 1, b.length)
    return b
}

fun isImage(m: JSONObject) = m.str("kind") == "file" && m.optJSONObject("file")?.optBoolean("image") == true

private fun votesText(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    return "$n " + when {
        m10 == 1 && m100 != 11 -> "голос"
        m10 in 2..4 && m100 !in 12..14 -> "голоса"
        else -> "голосов"
    }
}

/** Decoded photos: thumbnails from messages and downscaled originals. */
object Photos {
    val full = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val thumbs = LruCache<String, Bitmap>(300)
    private val pending = HashMap<String, Long>()

    fun thumb(m: JSONObject): Bitmap? {
        val id = m.str("id")
        thumbs.get(id)?.let { return it }
        val b64 = m.optJSONObject("file")?.str("thumb").orEmpty()
        if (b64.isEmpty()) return null
        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        thumbs.put(id, bmp)
        return bmp
    }

    /** Downloads (once per 30 s on failure) and decodes the original in the background. */
    fun load(m: JSONObject, done: (Bitmap) -> Unit) {
        val id = m.str("id")
        val now = System.currentTimeMillis()
        if ((m.optJSONObject("file")?.optLong("size") ?: 0) > 25_000_000 || now - (pending[id] ?: 0) < 30_000) return
        pending[id] = now
        CoreClient.call("download_file", JSONObject().put("message_id", id)) { v, e ->
            if (e != null) return@call
            val path = (v as JSONObject).str("path")
            Thread {
                val bmp = decode(path, 1280)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (bmp == null) return@post
                    pending.remove(id)
                    full.put(id, bmp)
                    done(bmp)
                }
            }.start()
        }
    }

    /** Decodes with subsampling to about `maxSide` and applies the EXIF rotation. */
    fun decode(path: String, maxSide: Int): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val deg = rotation(path)
        if (deg == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
    }.getOrNull()

    fun rotation(path: String): Int = runCatching {
        when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    /** Small JPEG preview sent inside the message; kept tiny so it fits the encrypted envelope. */
    fun makeThumb(path: String): Triple<String, Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val deg = rotation(path)
        val (w, h) = if (deg == 90 || deg == 270) o.outHeight to o.outWidth else o.outWidth to o.outHeight
        val src = decode(path, 320) ?: return null
        for (side in intArrayOf(160, 120, 90)) {
            val k = side.toFloat() / maxOf(src.width, src.height)
            val small = Bitmap.createScaledBitmap(src, maxOf(1, (src.width * k).toInt()), maxOf(1, (src.height * k).toInt()), true)
            val out = java.io.ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 55, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            if (b64.length <= 12000) return Triple(b64, w, h)
        }
        return null
    }
}

/** Thin result bar of a poll answer. */
private class PollBar(ctx: Context) : View(ctx) {
    var fraction = 0f
    var track = 0
    var fill = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val r = height / 2f
        paint.color = track
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
        if (fraction > 0f) {
            paint.color = fill
            c.drawRoundRect(0f, 0f, maxOf(height.toFloat(), width * fraction), height.toFloat(), r, r, paint)
        }
    }
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
    private val audioCall = IconButton(ctx, R.drawable.ic_phone, "Аудиозвонок")
    private val videoCall = IconButton(ctx, R.drawable.ic_video, "Видеозвонок")
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
    private val jump = IconButton(ctx, R.drawable.ic_down, "В самый низ", glass = true, iconRole = Theme.Role.ACCENT)
    private var jumpShown = false
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
    private val replyName = ctx.label("", 13.5f, 600, Theme.Role.ACCENT)
    private val replyLine = View(ctx)
    private val composerBox = LinearLayout(ctx)
    private val composerBg = GlassDrawable(ctx, radius = ctx.dpf(31))
    private val textSp = Prefs.textSize(ctx)
    private val bigEmoji = Prefs.of(ctx).getBoolean("big_emoji", true)
    private val enterSends = Prefs.of(ctx).getBoolean("enter_send", false)
    private var baseSubtitle = ""

    private val reload = Runnable { load(false) }
    private val typingChanged: (String) -> Unit = { if (it == conversationId) updateSubtitle() }
    // Presence of the other person while the chat is open.
    private val presenceTick = object : Runnable {
        override fun run() {
            requestPresence()
            root.postDelayed(this, 30_000)
        }
    }

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
                    showJump(false)
                    markRead()
                } else if (!searching && farFromBottom()) {
                    showJump(true)
                }
            }
        })
        val listHolder = FrameLayout(ctx)
        listHolder.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        jump.visibility = View.GONE
        jump.elevation = ctx.dpf(4)
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
        audioCall.setOnClickListener { startCall(false) }
        videoCall.setOnClickListener { startCall(true) }
        audioCall.visibility = View.GONE
        videoCall.visibility = View.GONE
        header.addView(audioCall)
        header.addView(videoCall)
        val search = IconButton(ctx, R.drawable.ic_search, "Поиск в переписке")
        search.setOnClickListener { toggleSearch() }
        header.addView(search)
        val more = IconButton(ctx, R.drawable.ic_more, "Меню диалога")
        more.setOnClickListener {
            PopupMenu(act, more)
                .add("Сведения") { Dialogs.conversationInfo(act, conversationId) }
                .add("Поиск в переписке") { if (!searchOpen) toggleSearch() }
                .add("Очистить чат…") { confirmClearChat(act, conversationId) }
                .show()
        }
        header.addView(more)
        searchBar.edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = search(s.toString())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    /** Microphone first, then the camera for a video call (a refusal still allows a voice call). */
    private fun startCall(video: Boolean) {
        act.withPermission(android.Manifest.permission.RECORD_AUDIO) { mic ->
            if (!mic) return@withPermission act.toast("Для звонка нужен доступ к микрофону")
            if (!video) return@withPermission app.neochat.calls.CallManager.start(conv, false)
            act.withPermission(android.Manifest.permission.CAMERA) { cam -> app.neochat.calls.CallManager.start(conv, cam) }
        }
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
            avatar.set(conv.str("title"), conv.str("peer_user_id").ifEmpty { conversationId }, conv.str("avatar_path"))
            val members = conv.optJSONArray("members") ?: JSONArray()
            val channel = conv.str("kind") == "channel"
            baseSubtitle = when (conv.str("kind")) {
                "group" -> memberCount(conv.optInt("member_count")) + conv.optInt("online_count").takeIf { it > 0 }?.let { ", $it в сети" }.orEmpty()
                "channel" -> memberCount(conv.optInt("member_count"), true)
                else -> conv.optJSONObject("presence")?.str("text")?.takeIf { it.isNotEmpty() }
                    ?: members.objects().firstOrNull { !it.optBoolean("me") }?.let { "@" + it.str("username") } ?: ""
            }
            avatar.online = conv.optJSONObject("presence")?.optBoolean("online") == true
            val system = conv.str("kind") == "system"
            if (system) baseSubtitle = "Системные сообщения"
            updateSubtitle()
            val left = conv.optBoolean("left") || conv.optBoolean("archived") || system
            val muteComposer = left || !conv.optBoolean("can_post", true)
            // Calls are one-to-one, through a direct chat the peer can receive in.
            val callable = conv.str("kind") == "direct" && !left && conv.optBoolean("can_post", true) && conv.str("peer_user_id").isNotEmpty()
            if (callable) app.neochat.calls.CallManager.warmUp()
            audioCall.visibility = if (callable) View.VISIBLE else View.GONE
            videoCall.visibility = if (callable) View.VISIBLE else View.GONE
            composer.visibility = if (muteComposer) View.GONE else View.VISIBLE
            gone.visibility = if (muteComposer) View.VISIBLE else View.GONE
            gone.text = when {
                system -> "Сюда приходят коды входа и сообщения о новых устройствах"
                conv.optBoolean("archived") -> if (channel) "Канал в архиве. Новые публикации недоступны" else "Группа в архиве. Новые сообщения недоступны"
                conv.optBoolean("left") -> if (channel) "Вы вышли из канала" else "Вы вышли из этой группы"
                channel -> "Публикуют только владелец и админы канала"
                else -> "Писать здесь могут только владелец и админы"
            }
            if (muteComposer) hideKeyboard()
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

    /** "печатает…" wins over presence and the member count for a few seconds. */
    private fun updateSubtitle() {
        val typing = Typing.text(conversationId, conv.str("kind") != "direct")
        subtitle.text = typing ?: baseSubtitle
        subtitle.role(if (typing != null || avatar.online) Theme.Role.ACCENT else Theme.Role.MUTED)
    }

    /** The other person of a direct chat, or the other members of a group ("N участников, X в сети"). */
    private fun presenceIds(): JSONArray {
        val ids = JSONArray()
        val peer = conv.str("peer_user_id")
        if (peer.isNotEmpty()) ids.put(peer)
        if (conv.str("kind") == "group")
            for (m in (conv.optJSONArray("members") ?: JSONArray()).objects()) if (!m.optBoolean("me")) ids.put(m.str("user_id"))
        return ids
    }

    private fun requestPresence() {
        val ids = presenceIds()
        if (ids.length() == 0 || !shown) return
        val tz = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        CoreClient.call("presence", JSONObject().put("user_ids", ids).put("tz_offset", tz)) { _, e ->
            if (e == null) refreshConversation(false)
        }
    }

    // ---- history ----

    private fun atBottom() = !list.canScrollVertically(1)

    /** More than one screen of history below the viewport. */
    private fun farFromBottom() =
        list.computeVerticalScrollRange() - list.computeVerticalScrollOffset() - list.computeVerticalScrollExtent() > list.height

    private fun showJump(on: Boolean) {
        if (on == jumpShown) return
        jumpShown = on
        jump.animate().cancel()
        if (Theme.reducedMotion) {
            jump.alpha = 1f
            jump.scaleX = 1f
            jump.scaleY = 1f
            jump.visibility = if (on) View.VISIBLE else View.GONE
            return
        }
        if (on) {
            jump.visibility = View.VISIBLE
            jump.alpha = 0f
            jump.scaleX = 0.7f
            jump.scaleY = 0.7f
            jump.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(Theme.surfaceCurve).start()
        } else {
            jump.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(150).withEndAction { jump.visibility = View.GONE }.start()
        }
    }

    private fun scrollToBottom(smooth: Boolean) {
        showJump(false)
        if (adapter.itemCount == 0) return
        val last = adapter.itemCount - 1
        if (smooth && !Theme.reducedMotion) {
            // A long way off: jump close first so the smooth part stays short.
            if (last - layout.findLastVisibleItemPosition() > 12) list.scrollToPosition(last - 12)
            list.smoothScrollToPosition(last)
        } else {
            list.scrollToPosition(last)
        }
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
            val oldest = page.firstOrNull()?.optLong("local_id") ?: 0L
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
            showJump(true)
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

        // Diffed updates keep the scroll anchor: a full rebind made the list jump while sending.
        fun setItems(v: List<Any>) {
            val old = items
            items = v
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = v.size
                override fun areItemsTheSame(o: Int, n: Int) = key(old[o]) == key(v[n])
                override fun areContentsTheSame(o: Int, n: Int): Boolean {
                    val a = old[o]
                    val b = v[n]
                    if (a is Item && b is Item) return a.firstInRun == b.firstInRun && a.lastInRun == b.lastInRun && a.m.toString() == b.m.toString()
                    return a == b
                }
            }, false).dispatchUpdatesTo(this)
        }

        private fun key(x: Any) = if (x is Item) "m:" + x.m.str("id") else "d:$x"

        override fun getItemViewType(position: Int) = if (items[position] is String) 0 else 1
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 0) object : RecyclerView.ViewHolder(DateRow(ctx)) {}
            else {
                val row = BubbleRow(ctx)
                val swipe = SwipeToReplyLayout(
                    ctx,
                    canReply = { canSwipeReply(row.message) },
                    onReply = { startSwipeReply(row.message) },
                )
                swipe.attach(row)
                swipe.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                MessageHolder(swipe, row)
            }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val it = items[position]
            if (it is String) return (h.itemView as DateRow).set(it)
            val holder = h as MessageHolder
            val item = it as Item
            holder.swipe.reset()
            holder.row.bind(item)
            val appear = holder.itemView
            val id = item.m.str("id")
            if (id in animate && !Theme.reducedMotion) {
                animate = animate - id
                appear.alpha = 0f
                appear.translationY = ctx.dpf(12)
                appear.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(Theme.surfaceCurve).start()
            } else {
                appear.animate().cancel()
                appear.alpha = 1f
                appear.translationY = 0f
            }
        }

        override fun onViewRecycled(h: RecyclerView.ViewHolder) {
            (h as? MessageHolder)?.swipe?.reset()
        }
    }

    private class MessageHolder(val swipe: SwipeToReplyLayout, val row: BubbleRow) : RecyclerView.ViewHolder(swipe)

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
        val message: JSONObject get() = m

        private val av = AvatarView(ctx, 30)
        private val col = LinearLayout(ctx)
        private val sender = ctx.label("", 12.5f, 600, Theme.Role.MUTED)
        private val bubble = LinearLayout(ctx)
        private val bg = GlassDrawable(ctx, GlassDrawable.Kind.INCOMING, ctx.dpf(23))
        private val fwd = ctx.label("", 12.5f, 600)
        private val quote = LinearLayout(ctx)
        private val quoteName = ctx.label("", 12.5f, 600)
        private val quoteText = ctx.label("", 13f, 400)
        private val file = LinearLayout(ctx)
        private val fileIcon = FrameLayout(ctx)
        private val fileName = ctx.label("", 14.5f, 600)
        private val fileMeta = ctx.label("", 12.5f, 400)
        private val fileAction = ImageView(ctx)
        private val text = ctx.label("", 15.5f)
        private val photo = ImageView(ctx)
        private val poll = LinearLayout(ctx)
        private val pollQuestion = ctx.label("", 16f, 700)
        private val pollInfo = ctx.label("", 12.5f)
        private val pollOptions = LinearLayout(ctx)
        private val reactRow = FlowRow(ctx)
        private val meta = LinearLayout(ctx)
        private val time = ctx.label("", 11.5f)
        private val status = ImageView(ctx)
        private val retry = ctx.label("Не отправлено · Повторить", 12.5f, 600, Theme.Role.WARNING)
        private var m = JSONObject()
        private var reactsId = ""
        private var reactsSig = ""
        private var pendingTap: Runnable? = null

        /**
         * A tap opens the menu; a second tap within the double-tap time puts the quick
         * reaction instead. Where reactions are not allowed the menu opens at once.
         */
        private fun tap(single: () -> Unit) {
            val p = pendingTap
            if (!canReact(m)) {
                single()
                return
            }
            if (p != null) {
                removeCallbacks(p)
                pendingTap = null
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                react(m, QuickReaction.current(ctx))
                return
            }
            val r = Runnable { pendingTap = null; single() }
            pendingTap = r
            postDelayed(r, android.view.ViewConfiguration.getDoubleTapTimeout().toLong())
        }

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
            fwd.maxLines = 1
            fwd.ellipsize = TextUtils.TruncateAt.END
            bubble.addView(fwd, LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(4) })
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
            photo.scaleType = ImageView.ScaleType.CENTER_CROP
            photo.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, ctx.dpf(19))
            }
            photo.clipToOutline = true
            photo.contentDescription = "Фото"
            photo.isClickable = true
            photo.setOnClickListener { openPhoto(m) }
            photo.setOnLongClickListener { menu(m, bubble); true }
            fileIcon.setOnClickListener { openFile(m) }
            fileAction.setOnClickListener { openFile(m) }
            bubble.addView(photo, LayoutParams(dp(200), dp(150)))
            poll.orientation = VERTICAL
            poll.minimumWidth = dp(240)
            poll.addView(pollQuestion)
            poll.addView(pollInfo, LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
            pollOptions.orientation = VERTICAL
            poll.addView(pollOptions, LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
            bubble.addView(poll, LayoutParams(WRAP, WRAP))
            // Bubbles take at most ~78% of the screen width.
            val maxText = (ctx.resources.displayMetrics.widthPixels * 0.78f).toInt() - dp(29 + 38)
            text.maxWidth = maxText
            quoteText.maxWidth = maxText
            fwd.maxWidth = maxText
            fileName.maxWidth = maxText - dp(46 + 60)
            text.setTextIsSelectable(false)
            text.setLineSpacing(0f, 1.2f)
            text.setOnLongClickListener { menu(m, bubble); true }
            text.setOnClickListener { tap { menu(m, bubble) } }
            text.setOnTouchListener { v, e -> openLinkAt(v as TextView, e) }
            pollQuestion.maxWidth = maxText
            bubble.addView(text, LayoutParams(WRAP, WRAP))
            bubble.addView(reactRow, LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
            meta.gravity = Gravity.CENTER_VERTICAL or Gravity.END
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
            // A tap opens reactions and actions, like a long press; photos open the viewer.
            bubble.setOnClickListener { tap { if (isImage(m)) openPhoto(m) else menu(m, bubble) } }
            setOnClickListener { tap { menu(m, bubble) } }
            setOnLongClickListener { menu(m, bubble); true }
        }

        fun bind(item: Item) {
            if (item.m.str("id") != m.str("id")) pendingTap?.let { removeCallbacks(it); pendingTap = null }
            m = item.m
            val out = m.optBoolean("outgoing")
            val group = conv.str("kind") == "group"
            val kind = m.str("kind")
            gravity = (if (out) Gravity.END else Gravity.START) or Gravity.BOTTOM
            setPadding(if (out) dp(48) else dp(4), if (item.firstInRun) dp(8) else dp(3), if (out) dp(4) else dp(32), 0)
            av.visibility = if (group && !out) (if (item.lastInRun) VISIBLE else INVISIBLE) else GONE
            av.set(m.str("sender_name"), m.str("sender_user_id"), m.str("sender_avatar_path"))
            sender.visibility = if (group && !out && item.firstInRun) VISIBLE else GONE
            sender.text = m.str("sender_name") + m.str("sender_tag").let { if (it.isEmpty()) "" else " · $it" }
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

            val fw = m.optJSONObject("fwd")?.str("name").orEmpty()
            fwd.visibility = if (fw.isEmpty()) GONE else VISIBLE
            if (fw.isNotEmpty()) {
                fwd.text = "Переслано: $fw"
                fwd.setTextColor(if (out) Theme.p.onAccent else Theme.p.accent)
            }
            val rep = m.optJSONObject("reply_to")
            quote.visibility = if (rep != null) VISIBLE else GONE
            if (rep != null) {
                quoteName.text = rep.str("sender_name")
                quoteText.text = if (rep.str("kind") == "file") "Файл" else rep.str("text").replace('\n', ' ')
            }
            val f = m.optJSONObject("file")
            val image = isImage(m)
            file.visibility = if (f != null && !image) VISIBLE else GONE
            photo.visibility = if (image) VISIBLE else GONE
            bubble.setPadding(if (image) dp(4) else dp(15), if (image) dp(4) else dp(9), if (image) dp(4) else dp(14), dp(7))
            text.setPadding(if (image) dp(11) else 0, if (image) dp(6) else 0, if (image) dp(10) else 0, 0)
            meta.setPadding(0, 0, if (image) dp(10) else 0, 0)
            if (image && f != null) {
                val maxW = minOf(text.maxWidth + dp(29), dp(300)).toFloat()
                var iw = f.optDouble("width", 0.0).toFloat()
                var ih = f.optDouble("height", 0.0).toFloat()
                if (iw <= 0f || ih <= 0f) { iw = 4f; ih = 3f }
                val k = minOf(maxW / iw, dp(340) / ih)
                val plp = photo.layoutParams
                plp.width = maxOf(dp(140), (iw * k).toInt())
                plp.height = maxOf(dp(90), (ih * k).toInt())
                photo.layoutParams = plp
                val id = m.str("id")
                val cached = Photos.full.get(id)
                photo.setImageBitmap(cached ?: Photos.thumb(m))
                if (cached == null && Prefs.autoloadPhotos(ctx)) Photos.load(m) { bmp -> if (m.str("id") == id) photo.setImageBitmap(bmp) }
            }
            poll.visibility = if (kind == "poll") VISIBLE else GONE
            if (kind == "poll") bindPoll(m, fg, muted)
            if (f != null) {
                fileName.text = f.str("name")
                fileMeta.text = sizeText(f.optLong("size")) + " · " + kindText(f.str("mime"), f.str("name"))
                fileAction.setImageResource(if (f.optBoolean("downloaded") || out) R.drawable.ic_file else R.drawable.ic_download)
            }
            val body = when {
                undecryptable -> "Сообщение не удалось расшифровать"
                else -> m.str("text")
            }
            text.visibility = if (body.isEmpty() || kind == "poll") GONE else VISIBLE
            val rich = if (undecryptable) body else spanned(m)
            text.text = rich
            text.setLinkTextColor(fg)
            if (undecryptable) text.setTextColor(Theme.p.warning)
            if (kind == "call") {
                val c = m.optJSONObject("call")
                val missed = !out && c?.str("status") == "missed"
                val icon = ctx.getDrawable(if (c?.optBoolean("video") == true) R.drawable.ic_video else R.drawable.ic_phone)?.mutate()
                icon?.setTint(if (missed) Theme.p.warning else if (out) fg else Theme.p.accent)
                icon?.setBounds(0, 0, dp(20), dp(20))
                text.setCompoundDrawablesRelative(icon, null, null, null)
                text.compoundDrawablePadding = dp(10)
                if (missed) text.setTextColor(Theme.p.warning)
            } else if (text.compoundDrawablesRelative[0] != null) {
                text.setCompoundDrawablesRelative(null, null, null, null)
            }
            // One to three emoji without anything else are shown large and without a bubble.
            val emoji = if (bigEmoji && rep == null && f == null) m.optInt("emoji_only") else 0
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, when (emoji) { 1 -> 52f; 2 -> 42f; 3 -> 34f; else -> textSp })
            if (emoji > 0) {
                bubble.background = null
                bubble.setPadding(dp(2), 0, dp(2), dp(2))
                text.setTextColor(Theme.p.text)
                time.setTextColor(Theme.p.muted)
            } else {
                bubble.background = bg
            }
            bindReactions(m, out && emoji == 0)
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
            if (emoji > 0 && st != "failed") status.imageTintList = android.content.res.ColorStateList.valueOf(Theme.p.muted)
            retry.visibility = if (out && st == "failed") VISIBLE else GONE
            bubble.contentDescription = buildString {
                append(m.str("sender_name")).append(", ").append(time.text).append(": ")
                if (image) append("фото. ") else if (f != null) append("файл ").append(f.str("name")).append(". ")
                if (kind == "poll") append("опрос. ")
                append(body)
                if (out && statusText(m).isNotEmpty()) append(". ").append(statusText(m))
                if (group && out && m.optInt("delivered_count") > 0) append(", доставлено: ").append(m.optInt("delivered_count"))
                for (r in m.optJSONArray("reactions")?.objects() ?: emptyList()) append(", ").append(r.str("emoji")).append(" ").append(r.optInt("count"))
            }
            invalidate()
        }

        private fun bindReactions(m: JSONObject, onAccent: Boolean) {
            reactRow.removeAllViews()
            val list = m.optJSONArray("reactions")?.objects() ?: emptyList()
            reactRow.visibility = if (list.isEmpty()) GONE else VISIBLE
            // Chips pop in when the reactions of the bound message changed.
            val sig = list.joinToString(" ") { it.str("emoji") + it.optInt("count") }
            val pop = reactsId == m.str("id") && reactsSig != sig
            reactsId = m.str("id")
            reactsSig = sig
            for (r in list) reactRow.addView(reactionChip(ctx, r, onAccent) { react(m, r.str("emoji")) }.apply {
                if (pop && Theme.ms(200) > 0) {
                    alpha = 0f
                    scaleX = 0.4f
                    scaleY = 0.4f
                    animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Theme.ms(200)).start()
                }
            })
        }

        private fun bindPoll(m: JSONObject, fg: Int, muted: Int) {
            val p = m.optJSONObject("poll") ?: JSONObject()
            val opts = p.optJSONArray("options")?.objects() ?: emptyList()
            val my = p.optJSONArray("my_choices")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList()
            val closed = p.optBoolean("closed")
            val multiple = p.optBoolean("multiple")
            val total = p.optInt("voters")
            val results = closed || my.isNotEmpty()
            pollQuestion.text = m.str("text")
            pollQuestion.setTextColor(fg)
            pollInfo.setTextColor(muted)
            pollInfo.text = (if (closed) "Опрос завершён" else if (p.optBoolean("anonymous", true)) "Анонимный опрос" else "Открытый опрос") +
                (if (multiple) " · несколько ответов" else "") + " · " + votesText(total)
            val strong = if (m.optBoolean("outgoing")) Theme.p.onAccent else Theme.p.accent
            pollOptions.removeAllViews()
            for ((i, o) in opts.withIndex()) {
                val votes = o.optInt("votes")
                val chosen = i in my
                val pct = if (total > 0) Math.round(100f * votes / total) else 0
                val row = LinearLayout(ctx).apply { orientation = VERTICAL; minimumHeight = dp(44); gravity = Gravity.CENTER_VERTICAL }
                val line = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
                if (results) {
                    line.addView(ctx.label("$pct%", 13f, 700).apply { setTextColor(if (chosen) strong else muted) }, lp(dp(46), WRAP))
                } else {
                    val mark = View(ctx)
                    mark.background = GradientDrawable().apply {
                        if (multiple) cornerRadius = ctx.dpf(5) else shape = GradientDrawable.OVAL
                        setStroke(dp(2), muted)
                    }
                    line.addView(mark, lp(dp(18), dp(18)).apply { marginEnd = dp(12) })
                }
                line.addView(ctx.label(o.str("text"), 15f, if (chosen) 600 else 400).apply { setTextColor(fg) }, lp(0, WRAP, 1f))
                row.addView(line, lp())
                if (results) {
                    val bar = PollBar(ctx)
                    bar.fraction = if (total > 0) votes.toFloat() / total else 0f
                    bar.fill = strong
                    bar.track = (muted and 0x00FFFFFF) or 0x40000000
                    row.addView(bar, lp(MATCH, dp(4)).apply { setMargins(dp(46), dp(5), 0, 0) })
                }
                val voters = o.optJSONArray("voters")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                row.contentDescription = o.str("text") + (if (results) ", $pct%" else "") + (if (chosen) ", ваш выбор" else "") +
                    (if (voters.isNotEmpty()) ", " + voters.joinToString() else "")
                if (!closed) {
                    row.isClickable = true
                    row.setOnClickListener { vote(m, i) }
                }
                if (voters.isNotEmpty()) row.setOnLongClickListener { act.toast(voters.joinToString()); true }
                pollOptions.addView(row, lp())
            }
        }
    }

    private fun vote(m: JSONObject, option: Int) {
        val p = m.optJSONObject("poll") ?: return
        if (p.optBoolean("closed")) return
        val my = p.optJSONArray("my_choices")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList()
        // Multiple choice: a tap toggles the answer.
        val choices = if (p.optBoolean("multiple")) (if (option in my) my - option else my + option) else listOf(option)
        CoreClient.call("vote_poll", JSONObject().put("message_id", m.str("id")).put("choices", JSONArray(choices))) { _, e ->
            if (e != null) act.toast(e.message)
        }
    }

    // ---- photo viewer ----

    private var viewer: View? = null

    private fun openPhoto(m: JSONObject) {
        CoreClient.call("download_file", JSONObject().put("message_id", m.str("id"))) { v, e ->
            if (e != null) return@call act.toast(e.message)
            val path = (v as JSONObject).str("path")
            val side = maxOf(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
            Thread {
                val bmp = Photos.decode(path, side)
                act.runOnUiThread { if (bmp == null) act.toast("Не удалось открыть фото") else showViewer(bmp) }
            }.start()
        }
    }

    /** Full-screen photo over the chat: tap or Back closes; fades unless motion is reduced. */
    private fun showViewer(bmp: Bitmap) {
        closeViewer()
        hideKeyboard()
        val v = FrameLayout(ctx)
        v.setBackgroundColor(0xF2040914.toInt())
        v.isClickable = true
        v.contentDescription = "Просмотр фото. Нажмите, чтобы закрыть"
        val img = ImageView(ctx)
        img.setImageBitmap(bmp)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        img.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        v.addView(img, flp(MATCH, MATCH).apply { setMargins(dp(8), column.paddingTop + dp(8), dp(8), column.paddingBottom + dp(8)) })
        v.setOnClickListener { closeViewer() }
        root.addView(v, flp(MATCH, MATCH))
        viewer = v
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(Theme.ms(180)).start()
    }

    private fun closeViewer() {
        val v = viewer ?: return
        viewer = null
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(Theme.ms(180)).withEndAction { root.removeView(v) }.start()
    }

    /** Opens a link under the finger; any other tap falls through to the bubble's actions. */
    private fun openLinkAt(tv: TextView, e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return false
        val text = tv.text as? Spanned ?: return false
        val layout = tv.layout ?: return false
        val x = e.x.toInt() - tv.totalPaddingLeft + tv.scrollX
        val y = e.y.toInt() - tv.totalPaddingTop + tv.scrollY
        val line = layout.getLineForVertical(y)
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return false
        val off = layout.getOffsetForHorizontal(line, x.toFloat())
        val link = text.getSpans(off, off, URLSpan::class.java).firstOrNull() ?: return false
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)))
        } catch (_: Exception) {
            act.toast("Не удалось открыть ссылку")
        }
        return true
    }

    private fun canReact(m: JSONObject) = m.str("kind") in setOf("text", "file", "poll") &&
        conv.str("kind") != "system" && !conv.optBoolean("left") && !conv.optBoolean("archived") &&
        (conv.optJSONArray("key_changed_users")?.length() ?: 0) == 0

    /** A tap on my current reaction removes it; another emoji replaces it. */
    private fun react(m: JSONObject, emoji: String) {
        val mine = m.optJSONArray("reactions")?.objects()?.firstOrNull { it.optBoolean("mine") }?.str("emoji")
        val next = if (mine == emoji) "" else emoji
        CoreClient.call("react", JSONObject().put("message_id", m.str("id")).put("emoji", next)) { _, e -> if (e != null) act.toast(e.message) }
    }

    private fun quickReactions(m: JSONObject, close: () -> Unit): View {
        val mine = m.optJSONArray("reactions")?.objects()?.firstOrNull { it.optBoolean("mine") }?.str("emoji")
        val row = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), dp(4))
        }
        for (e in Emoji.QUICK) {
            val tv = TextView(ctx).apply {
                text = e
                textSize = 23f
                gravity = Gravity.CENTER
                contentDescription = "Реакция $e" + if (e == mine) ", выбрана" else ""
                isClickable = true
                background = if (e == mine) GradientDrawable().apply { setColor((Theme.p.accent and 0x00FFFFFF) or 0x40000000); cornerRadius = ctx.dpf(18) }
                else GlassDrawable(ctx, GlassDrawable.Kind.NONE, ctx.dpf(18), rim = false)
                pressFeedback()
                setOnClickListener { close(); react(m, e) }
            }
            row.addView(tv, lp(0, dp(42), 1f))
        }
        val more = IconButton(ctx, R.drawable.ic_smile, "Другие реакции", 40)
        more.setOnClickListener {
            close()
            Emoji.sheet(act, "Реакция") { react(m, it) }
        }
        row.addView(more, lp(dp(40), dp(40)))
        return row
    }

    private fun menu(m: JSONObject, anchor: View) {
        val pm = PopupMenu(act, anchor)
        if (canReact(m)) pm.header(quickReactions(m) { pm.dismiss() })
        val ok = m.str("kind") != "undecryptable"
        val gone = composer.visibility != View.VISIBLE
        if (ok && !gone && m.str("kind") != "call") pm.add("Ответить") { setReply(m) }
        if (ok && m.str("text").isNotEmpty()) pm.add("Копировать текст") {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("NeoChat", m.str("text")))
            if (android.os.Build.VERSION.SDK_INT < 33) act.toast("Текст скопирован")
        }
        if (isImage(m)) pm.add("Открыть фото") { openPhoto(m) }
        if (m.str("kind") == "poll" && m.optBoolean("outgoing") && m.optJSONObject("poll")?.optBoolean("closed") != true) pm.add("Завершить опрос") {
            CoreClient.call("close_poll", JSONObject().put("message_id", m.str("id"))) { _, e -> if (e != null) act.toast(e.message) }
        }
        if (m.str("kind") == "file") {
            pm.add("Открыть файл") { openFile(m) }
            pm.add("Сохранить как…") { saveFile(m) }
        }
        if (m.str("status") == "failed") pm.add("Повторить отправку") { retry(m) }
        if (ok && m.str("status") != "failed" && m.str("kind") in setOf("text", "file", "poll"))
            pm.add("Переслать") { Dialogs.forward(act, listOf(m)) }
        if (m.optBoolean("outgoing") && m.str("status") != "failed" && m.str("kind") in setOf("text", "file", "poll"))
            pm.add("Удалить у всех", danger = true) {
                CoreClient.call("delete_everywhere", JSONObject().put("message_id", m.str("id"))) { _, e ->
                    if (e != null) act.toast(e.message)
                }
            }
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
        // The reply preview is the top part of the same glass field, not a separate strip.
        composerBox.orientation = LinearLayout.VERTICAL
        composerBox.background = composerBg
        replyBar.gravity = Gravity.CENTER_VERTICAL
        replyBar.setPadding(dp(18), dp(8), dp(4), 0)
        replyLine.background = GradientDrawable().apply { cornerRadius = ctx.dpf(2) }
        replyBar.addView(replyLine, lp(dp(3), dp(34)))
        val replyCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        replyName.maxLines = 1
        replyName.ellipsize = TextUtils.TruncateAt.END
        replyText.maxLines = 1
        replyText.ellipsize = TextUtils.TruncateAt.END
        replyCol.addView(replyName)
        replyCol.addView(replyText)
        replyBar.addView(replyCol, lp(0, WRAP, 1f).apply { marginStart = dp(10) })
        val cancel = IconButton(ctx, R.drawable.ic_close, "Отменить ответ", 40)
        cancel.setOnClickListener { setReply(null) }
        replyBar.addView(cancel, lp(dp(40), dp(40)))
        replyBar.visibility = View.GONE
        composerBox.addView(replyBar, lp())

        capsule.gravity = Gravity.BOTTOM
        capsule.setPadding(dp(4), dp(4), dp(4), dp(4))
        val attach = IconButton(ctx, R.drawable.ic_attach, "Прикрепить")
        // Ask what is attached first: photos open the gallery and arrive as photos.
        // Hide the IME so the chooser sits above the composer instead of behind the keyboard.
        attach.setOnClickListener {
            edit.clearFocus()
            hideKeyboard()
            val pm = PopupMenu(act, attach).add("Фото") { pickPhotos() }.add("Камера") { takePhoto() }.add("Файл") { pickFile() }
            if (conv.optBoolean("can_polls") && conv.str("kind") != "direct") pm.add("Опрос") { Dialogs.newPoll(act, conversationId) }
            pm.show(above = true)
        }
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
        if (enterSends) {
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            edit.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            edit.setOnEditorActionListener { _, _, _ -> sendText(); true }
        }
        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                updateSend(true)
                if (!s.isNullOrBlank() && !blocked && shown) CoreClient.call("send_typing", JSONObject().put("conversation_id", conversationId))
            }
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
        composerBox.addView(capsule, lp())
        composer.addView(composerBox, lp())
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

    private fun canSwipeReply(m: JSONObject) =
        m.str("kind") != "undecryptable" && composer.visibility == View.VISIBLE

    private fun startSwipeReply(m: JSONObject) {
        if (!canSwipeReply(m)) return
        setReply(m)
    }

    private fun setReply(m: JSONObject?) {
        replyTo = m
        composerBg.radius = ctx.dpf(if (m != null) 24 else 31)
        composerBox.invalidate()
        (replyLine.background as GradientDrawable).setColor(Theme.p.accent)
        if (m != null) {
            val t = when {
                isImage(m) -> "Фото"
                m.str("kind") == "file" -> "Файл"
                m.str("kind") == "poll" -> "Опрос: " + m.str("text")
                else -> m.str("text").replace('\n', ' ')
            }
            replyName.text = "В ответ ${m.str("sender_name")}"
            replyText.text = t
            replyBar.contentDescription = "Ответ на сообщение ${m.str("sender_name")}: $t"
            edit.requestFocus()
            edit.post {
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(edit, 0)
            }
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

    private fun pickPhotos() {
        val intent = if (android.os.Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10)
        else Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        act.startForResult(intent) { code, data ->
            if (code != Activity.RESULT_OK || data == null) return@startForResult
            val uris = ArrayList<Uri>()
            data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
            data.data?.let { if (uris.isEmpty()) uris += it }
            for (u in uris) sendUri(u, photo = true)
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
    private fun sendUri(uri: Uri, photo: Boolean = false) {
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
            val thumb = if (copied && photo && mime.startsWith("image/")) Photos.makeThumb(tmp.absolutePath) else null
            act.runOnUiThread {
                if (!copied) {
                    tmp.delete()
                    return@runOnUiThread act.toast("Не удалось прочитать файл")
                }
                dispatchFile(tmp, mime, name, thumb)
            }
        }.start()
    }

    /** Hands a prepared file to the core and removes it afterwards. */
    private fun dispatchFile(tmp: File, mime: String, name: String, thumb: Triple<String, Int, Int>?) {
        val p = JSONObject().put("conversation_id", conversationId).put("path", tmp.absolutePath).put("mime", mime).put("name", name)
        if (thumb != null) p.put("thumb", thumb.first).put("width", thumb.second).put("height", thumb.third)
        CoreClient.call("send_file", p) { _, e ->
            tmp.delete()
            if (e != null) act.toast(e.message)
        }
    }

    /** The in-app camera: zoom by pinching, one shutter button. */
    private fun takePhoto() {
        act.withPermission(android.Manifest.permission.CAMERA) { granted ->
            if (!granted) return@withPermission act.toast("Для съёмки нужен доступ к камере")
            PhotoCamera(act) { file ->
                Thread {
                    val thumb = Photos.makeThumb(file.absolutePath)
                    val name = "photo_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".jpg"
                    act.runOnUiThread { dispatchFile(file, "image/jpeg", name, thumb) }
                }.start()
            }.show()
        }
    }

    private fun hideKeyboard() {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val token = act.currentFocus?.windowToken ?: edit.windowToken ?: root.windowToken
        imm.hideSoftInputFromWindow(token, 0)
    }

    private fun saveDraft() {
        CoreClient.call("set_draft", JSONObject().put("conversation_id", conversationId).put("text", edit.text.toString()))
    }

    override fun applyInsets(top: Int, bottom: Int, ime: Int) {
        column.setPadding(0, top, 0, maxOf(bottom, ime))
        if (ime > 0 && atBottom()) list.post { scrollToBottom(false) }
    }

    override fun onBack(): Boolean {
        if (viewer != null) {
            closeViewer()
            return true
        }
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
        Typing.listeners += typingChanged
        root.removeCallbacks(presenceTick)
        root.postDelayed(presenceTick, 300)
    }

    override fun onHidden() {
        shown = false
        Typing.listeners -= typingChanged
        root.removeCallbacks(presenceTick)
        if (Notifier.visibleConversation == conversationId) Notifier.visibleConversation = null
        hideKeyboard()
        saveDraft()
    }

    override fun onEvent(ev: JSONObject) {
        when (ev.str("type")) {
            "history_cleared" -> if (ev.str("conversation_id") == conversationId) {
                gen++
                messages = emptyList()
                known.clear()
                loading = false
                hasMore = true
                searching = false
                searchBar.edit.setText("")
                adapter.setItems(emptyList())
                edit.setText("")
                setReply(null)
                showJump(false)
                if (ev.optBoolean("hidden")) act.closeChat() else load(false)
            }
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
            "presence" -> {
                val who = ev.str("user_id")
                if (who == conv.str("peer_user_id") || (conv.optJSONArray("members") ?: JSONArray()).objects().any { it.str("user_id") == who }) refreshConversation(false)
            }
            "connection" -> refreshConversation(false)
        }
    }

    /** Called by the activity when it returns to the foreground. */
    fun onResumed() = markRead()

    private object App {
        val foreground get() = (app.neochat.App.instance).inForeground
    }
}
