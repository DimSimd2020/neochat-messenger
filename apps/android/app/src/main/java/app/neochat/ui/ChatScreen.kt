package app.neochat.ui

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
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
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
        if (s.has("mention")) {
            val name = s.str("mention")
            val broadcast = name.equals("everyone", true) || name.equals("here", true)
            // Outgoing bubbles are blue, so a blue tag disappears into them. Warm sand stays readable.
            val mention = if (m.optBoolean("outgoing")) 0xFFFFE3A1.toInt() else Theme.p.accent
            set(ForegroundColorSpan(mention))
            set(StyleSpan(Typeface.BOLD))
            if (!broadcast) {
                set(UnderlineSpan())
                set(MentionSpan(name))
            }
        }
        if (pre) b.append('\n')
    }
    while (b.isNotEmpty() && b[b.length - 1] == '\n') b.delete(b.length - 1, b.length)
    return b
}

/** A tappable @name. @everyone and @here are not profiles. */
private class MentionSpan(val name: String) : ClickableSpan() {
    override fun onClick(widget: View) {}
    override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = true }
}

fun isImage(m: JSONObject) = m.str("kind") == "file" && m.optJSONObject("file")?.optBoolean("image") == true

fun isVideo(m: JSONObject) = m.str("kind") == "file" && m.optJSONObject("file")?.optBoolean("video") == true

fun isVoice(m: JSONObject) = m.str("kind") == "file" && m.optJSONObject("file")?.optBoolean("voice") == true

fun isCircle(m: JSONObject) = m.str("kind") == "file" && m.optJSONObject("file")?.optBoolean("circle") == true

/** The recognized text of a voice message or circle; null while it has none, "" when no speech was found. */
fun transcriptOf(m: JSONObject): String? = m.optJSONObject("file")?.takeIf { it.has("transcript") && !it.isNull("transcript") }?.optString("transcript")

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
        return encodeThumb(src)?.let { Triple(it, w, h) }
    }

    /** Shrinks the picture until its JPEG, as base64, is small enough for the encrypted envelope. */
    fun encodeThumb(src: Bitmap): String? {
        for (side in intArrayOf(160, 120, 90)) {
            val k = side.toFloat() / maxOf(src.width, src.height)
            val small = Bitmap.createScaledBitmap(src, maxOf(1, (src.width * k).toInt()), maxOf(1, (src.height * k).toInt()), true)
            val out = java.io.ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 55, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            if (b64.length <= 12000) return b64
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
    private val accountId = CoreClient.accountId
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
    // Pinned messages: the bar shows one at a time, a tap goes to it and on to the next older one.
    private val pinBar = LinearLayout(ctx)
    private val pinHead = ctx.label("", 12.5f, 600, Theme.Role.ACCENT)
    private val pinText = ctx.label("", 14f, 400)
    private val pinList = IconButton(ctx, R.drawable.ic_more, "Все закреплённые", 40)
    private val pinOff = IconButton(ctx, R.drawable.ic_close, "Открепить", 40)
    private var pins: List<JSONObject> = emptyList()
    private var pinIndex = 0
    private var jumpId: String? = null

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
    private var cancelReply: View? = null
    private val replyText = ctx.label("", 13.5f, 400, Theme.Role.MUTED)
    private var replyTo: JSONObject? = null
    // Edit mode: the field holds the message text; the unsent draft waits in savedDraft.
    private var editing: JSONObject? = null
    private var savedDraft = ""
    private val capsule = LinearLayout(ctx)
    private val edit = ThemedEdit(ctx)
    private val send = IconButton(ctx, R.drawable.ic_send, "Отправить", 44, iconRole = Theme.Role.ON_ACCENT)
    private val sendSlot = FrameLayout(ctx)
    // Voice messages and circles: two buttons where the arrow will be, and the bar that replaces the field while recording.
    private val mic = IconButton(ctx, R.drawable.ic_mic, "Записать голосовое сообщение")
    private val circleBtn = IconButton(ctx, R.drawable.ic_record, "Записать кружок")
    private val recBar = LinearLayout(ctx)
    private val recTime = ctx.label("0:00", 16f, 600)
    private val recDot = View(ctx)
    private val recWave = WaveView(ctx)
    private var recorder: VoiceRecorder? = null
    private val recLevels = ArrayList<Byte>()
    private var recordHidden: List<View> = emptyList()
    private val recTick = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            recTime.text = Videos.clock(r.elapsedMs)
            recDot.alpha = if ((r.elapsedMs / 500) % 2 == 0L) 1f else 0.35f
            if (r.elapsedMs >= 10 * 60 * 1000) return finishVoice()
            root.postDelayed(this, 100)
        }
    }
    private var sendShown = 0f
    private val sendSpring = Spring(0.38f, 0.62f, 0.001f) { sendShown = it; applySend() }
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
    private val animatedEmoji = Prefs.of(ctx).getBoolean("animated_emoji", true)
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
        buildPinBar()
        column.addView(pinBar, lp().apply { setMargins(dp(12), dp(8), dp(12), 0) })

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
            title.personColor(conv.str("title_color"), Theme.Role.TEXT)
            avatar.set(conv.str("title"), conv.str("peer_user_id").ifEmpty { conversationId }, conv.str("avatar_path"))
            val members = conv.optJSONArray("members") ?: JSONArray()
            val channel = conv.str("kind") == "channel"
            baseSubtitle = when (conv.str("kind")) {
                "group" -> memberCount(conv.optInt("member_count")) + conv.optInt("online_count").takeIf { it > 0 }?.let { ", $it в сети" }.orEmpty()
                "channel" -> if (conv.optBoolean("saved")) "Заметки для себя" else memberCount(conv.optInt("member_count"), true)
                else -> conv.optJSONObject("presence")?.str("text")?.takeIf { it.isNotEmpty() }
                    ?: members.objects().firstOrNull { !it.optBoolean("me") }?.let { "@" + it.str("username") } ?: ""
            }
            avatar.online = conv.optJSONObject("presence")?.optBoolean("online") == true
            val system = conv.str("kind") == "system"
            if (system) baseSubtitle = "Системные сообщения"
            updateSubtitle()
            val left = conv.optBoolean("left") || conv.optBoolean("archived") || system
            val muted = conv.optLong("muted") != 0L
            val muteComposer = left || muted || !conv.optBoolean("can_post", true)
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
                muted -> "Вам запретили писать в этой группе"
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
            refreshPins()
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
            if (jump.visibility != View.VISIBLE) {
                jump.visibility = View.VISIBLE
                jump.alpha = 0f
                jump.spring(Prop.SCALE).snap(0.5f)
            }
            jump.animate().alpha(1f).setDuration(140).start()
            jump.springTo(Prop.SCALE, 1f, 0.4f, 0.55f)
        } else {
            jump.springTo(Prop.SCALE, 0.6f, 0.26f, 1f)
            jump.animate().alpha(0f).setDuration(150).withEndAction { jump.visibility = View.GONE }.start()
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

    // ---- pinned messages ----

    private fun buildPinBar() {
        pinBar.gravity = Gravity.CENTER_VERTICAL
        pinBar.background = GlassDrawable(ctx, GlassDrawable.Kind.NONE, ctx.dpf(20)).apply { rim = true }
        pinBar.setPadding(dp(14), dp(4), dp(4), dp(4))
        pinBar.isClickable = true
        pinBar.isFocusable = true
        pinBar.addView(TintedIcon(ctx, R.drawable.ic_pin, Theme.Role.ACCENT), lp(dp(20), dp(20)).apply { marginEnd = dp(10) })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        pinHead.maxLines = 1
        pinHead.ellipsize = TextUtils.TruncateAt.END
        pinText.maxLines = 1
        pinText.ellipsize = TextUtils.TruncateAt.END
        texts.addView(pinHead)
        texts.addView(pinText, lp().apply { topMargin = dp(2) })
        pinBar.addView(texts, lp(0, WRAP, 1f))
        pinBar.addView(pinList, lp(dp(40), dp(40)))
        pinBar.addView(pinOff, lp(dp(40), dp(40)))
        pinBar.setOnClickListener {
            val m = pins.getOrNull(pinIndex) ?: return@setOnClickListener
            pinIndex = (pinIndex + 1) % pins.size
            showPins()
            jumpTo(m.str("id"))
        }
        pinList.setOnClickListener {
            val pm = PopupMenu(act, pinList)
            for (m in pins) pm.add(pinPreview(m)) { jumpTo(m.str("id")) }
            pm.show()
        }
        pinOff.setOnClickListener {
            val m = pins.getOrNull(pinIndex) ?: return@setOnClickListener
            CoreClient.call("pin_message", JSONObject().put("message_id", m.str("id")).put("on", false)) { _, e -> if (e != null) act.toast(e.message) }
        }
        pinBar.visibility = View.GONE
    }

    private fun pinPreview(m: JSONObject): String {
        val t = m.str("text").replace('\n', ' ')
        val f = m.optJSONObject("file")
        val s = when {
            m.str("kind") == "poll" -> "Опрос: $t"
            f != null -> (if (isImage(m)) "Фото" else if (isVideo(m)) "Видео" else if (isVoice(m)) "Голосовое сообщение" else if (isCircle(m)) "Видеосообщение" else "Файл " + f.str("name")) + if (t.isEmpty()) "" else ": $t"
            else -> t
        }
        return if (s.length > 120) s.take(119) + "…" else s
    }

    private fun refreshPins() {
        if (conv.optInt("pin_count") == 0) {
            pins = emptyList()
            pinIndex = 0
            return showPins()
        }
        CoreClient.call("list_pins", JSONObject().put("conversation_id", conversationId)) { v, e ->
            if (e != null) return@call
            val next = (v as? JSONArray)?.objects() ?: emptyList()
            if (next.size != pins.size) pinIndex = 0
            pins = next
            if (pinIndex >= pins.size) pinIndex = 0
            showPins()
        }
    }

    private fun showPins() {
        val m = pins.getOrNull(pinIndex)
        if (m != null) {
            pinHead.text = if (pins.size > 1) "Закреплённое сообщение · ${pinIndex + 1} из ${pins.size}" else "Закреплённое сообщение"
            pinText.text = pinPreview(m)
            pinBar.contentDescription = "${pinHead.text}: ${pinText.text}. Нажмите, чтобы перейти к сообщению"
        }
        pinList.visibility = if (pins.size > 1) View.VISIBLE else View.GONE
        pinOff.visibility = if (conv.optBoolean("can_pin")) View.VISIBLE else View.GONE
        expand(pinBar, m != null)
    }

    /** Scrolls to a message, paging older history in until it is loaded. */
    private fun jumpTo(id: String) {
        if (searching || id.isEmpty()) return
        val pos = adapter.positionOf(id)
        if (pos >= 0) {
            jumpId = null
            showJump(false)
            layout.scrollToPositionWithOffset(pos, list.height / 3)
            list.postDelayed({ flash(id) }, 80)
            return
        }
        if (!hasMore) return act.toast("Сообщение не найдено в истории")
        jumpId = id
        load(true)
    }

    /** A short pulse on the message that was jumped to. */
    private fun flash(id: String) {
        if (Theme.reducedMotion) return
        val v = list.findViewHolderForAdapterPosition(adapter.positionOf(id))?.itemView ?: return
        v.animate().cancel()
        v.animate().alpha(0.35f).setDuration(220).withEndAction { v.animate().alpha(1f).setDuration(380).start() }.start()
    }

    private fun savedCopy(m: JSONObject) {
        CoreClient.call("save_message", JSONObject().put("message_id", m.str("id"))) { _, e ->
            act.toast(if (e != null) e.message else "Сохранено в избранное")
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
            if (e != null) {
                jumpId = null
                return@call act.toast(e.message)
            }
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
            jumpId?.let { jumpTo(it) }
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
            // A service line (who added whom) is not part of anyone's run of messages.
            val plain = m.str("kind") != "service"
            val sameAsPrev = plain && prev != null && prev.str("kind") != "service" && prev.str("sender_user_id") == m.str("sender_user_id") && dayKey(prev.optLong("sent_at")) == day
            val sameAsNext = plain && next != null && next.str("kind") != "service" && next.str("sender_user_id") == m.str("sender_user_id") && dayKey(next.optLong("sent_at")) == day
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

        fun positionOf(id: String) = items.indexOfFirst { it is Item && it.m.str("id") == id }

        override fun getItemViewType(position: Int): Int {
            val it = items[position]
            return if (it is String) 0 else if ((it as Item).m.str("kind") == "service") 2 else 1
        }
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 0) object : RecyclerView.ViewHolder(DateRow(ctx)) {}
            else if (viewType == 2) object : RecyclerView.ViewHolder(ServiceRow(ctx)) {}
            else {
                val row = BubbleRow(ctx)
                val swipe = SwipeToReplyLayout(
                    ctx,
                    canReply = { canSwipeReply(row.message) },
                    onReply = { startSwipeReply(row.message) },
                )
                swipe.attach(row)
                swipe.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                // A new bubble grows out of its own bottom corner.
                swipe.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                    v.pivotX = if (row.message.optBoolean("outgoing")) (r - l).toFloat() else 0f
                    v.pivotY = (b - t).toFloat()
                }
                MessageHolder(swipe, row)
            }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val it = items[position]
            if (it is String) return (h.itemView as DateRow).set(it)
            if (h.itemViewType == 2) return (h.itemView as ServiceRow).set((it as Item).m.str("text"))
            val holder = h as MessageHolder
            val item = it as Item
            holder.swipe.reset()
            holder.row.bind(item)
            val appear = holder.itemView
            val id = item.m.str("id")
            if (id in animate && !Theme.reducedMotion) {
                animate = animate - id
                appear.pivotX = if (item.m.optBoolean("outgoing")) appear.width.toFloat() else 0f
                appear.pivotY = appear.height.toFloat()
                appear.alpha = 0f
                appear.animate().alpha(1f).setDuration(120).start()
                appear.spring(Prop.Y).snap(ctx.dpf(22))
                appear.spring(Prop.SCALE).snap(0.82f)
                appear.springTo(Prop.Y, 0f, 0.46f, 0.74f)
                appear.springTo(Prop.SCALE, 1f, 0.42f, 0.62f)
            } else {
                appear.animate().cancel()
                appear.stopSprings(Prop.Y, Prop.SCALE)
                appear.alpha = 1f
                appear.translationY = 0f
                appear.scaleX = 1f
                appear.scaleY = 1f
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

    /** "ner1s был добавлен владельцем DimSimd": a centered line, not a bubble. */
    private inner class ServiceRow(ctx: Context) : LinearLayout(ctx) {
        private val text = ctx.label("", 12.5f, 500, Theme.Role.MUTED)

        init {
            gravity = Gravity.CENTER
            layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            setPadding(dp(24), dp(6), dp(24), dp(6))
            text.gravity = Gravity.CENTER
            text.setPadding(dp(14), dp(5), dp(14), dp(5))
            addView(text, LayoutParams(WRAP, WRAP))
        }

        fun set(t: String) {
            text.text = t
            text.background = GradientDrawable().apply { setColor(Theme.p.glass); cornerRadius = ctx.dpf(14) }
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
        private val emojiRow = LinearLayout(ctx)
        private val photo = PosterView(ctx)
        private val voice = LinearLayout(ctx)
        private val playBtn = ImageView(ctx)
        private val wave = WaveView(ctx)
        private val voiceTime = ctx.label("", 12f)
        private val speedChip = ctx.label("1×", 12f, 600)
        private val circle = CircleView(ctx)
        private val transBtn = ctx.label("Расшифровать", 13.5f, 600)
        private val transText = ctx.label("", 14.5f)
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
        private var statusId = ""
        private var statusSig = ""
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
                Haptics.confirm(this)
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
            fileName.setHorizontallyScrolling(false)
            fcol.addView(fileName, LayoutParams(MATCH, WRAP))
            fcol.addView(fileMeta, LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) })
            file.addView(fcol, LayoutParams(0, WRAP, 1f).apply { setMargins(dp(12), 0, dp(8), 0) })
            fileAction.setImageResource(R.drawable.ic_download)
            fileAction.setPadding(dp(8), dp(8), dp(8), dp(8))
            fileAction.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            file.addView(fileAction, LayoutParams(dp(40), dp(40)))
            bubble.addView(file, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) })
            photo.scaleType = ImageView.ScaleType.CENTER_CROP
            photo.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, ctx.dpf(19))
            }
            photo.clipToOutline = true
            photo.contentDescription = "Фото"
            photo.isClickable = true
            photo.setOnClickListener { if (isVideo(m)) openVideo(m) else openPhoto(m) }
            photo.setOnLongClickListener { menu(m, bubble); true }
            fileIcon.setOnClickListener { openFile(m) }
            fileAction.setOnClickListener { openFile(m) }
            bubble.addView(photo, LayoutParams(dp(200), dp(150)))
            // Voice message: a round play button, the wave with the time under it and, while it plays, the speed.
            voice.orientation = HORIZONTAL
            voice.gravity = Gravity.CENTER_VERTICAL
            playBtn.setPadding(dp(8), dp(8), dp(8), dp(8))
            playBtn.setImageResource(R.drawable.ic_play)
            playBtn.isClickable = true
            playBtn.contentDescription = "Воспроизвести"
            voice.addView(playBtn, LayoutParams(dp(40), dp(40)))
            val vcol = LinearLayout(ctx).apply { orientation = VERTICAL }
            vcol.addView(wave, LayoutParams(MATCH, dp(28)))
            vcol.addView(voiceTime, LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
            val waveWidth = (((ctx.resources.displayMetrics.widthPixels * 0.78f).toInt() - dp(29 + 38 + 40 + 10 + 52)).coerceIn(dp(96), dp(176)))
            voice.addView(vcol, LayoutParams(waveWidth, WRAP).apply { marginStart = dp(10) })
            speedChip.gravity = Gravity.CENTER
            speedChip.setPadding(dp(8), 0, dp(8), 0)
            speedChip.isClickable = true
            voice.addView(speedChip, LayoutParams(WRAP, dp(24)).apply { marginStart = dp(8) })
            bubble.addView(voice, LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(2) })
            playBtn.setOnClickListener { Playback.toggle(ctx, m, null) { act.toast(it) } }
            wave.onSeek = { f -> if (Playback.stateOf(m.str("id")) == Playback.State.IDLE) Playback.toggle(ctx, m, null) { act.toast(it) } else Playback.seek(m.str("id"), f) }
            wave.setOnLongClickListener { menu(m, bubble); true }
            speedChip.setOnClickListener { Playback.cycleSpeed(ctx) }
            // Circle: the picture of the video in a round; a tap plays it right here.
            circle.setOnClickListener { Playback.toggle(ctx, m, circle) { act.toast(it) } }
            circle.setOnLongClickListener { menu(m, bubble); true }
            bubble.addView(circle, LayoutParams(dp(220), dp(220)))
            transBtn.minHeight = dp(40)
            transBtn.gravity = Gravity.CENTER_VERTICAL
            transBtn.compoundDrawablePadding = dp(8)
            transBtn.isClickable = true
            transBtn.setOnClickListener { Transcription.request(act, m) }
            bubble.addView(transBtn, LayoutParams(WRAP, WRAP))
            transText.setLineSpacing(0f, 1.2f)
            transText.setOnClickListener { tap { menu(m, bubble) } }
            transText.setOnLongClickListener { menu(m, bubble); true }
            bubble.addView(transText, LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
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
            transText.maxWidth = maxText
            quoteText.maxWidth = maxText
            fwd.maxWidth = maxText
            text.setTextIsSelectable(false)
            text.setLineSpacing(0f, 1.2f)
            text.setOnLongClickListener { menu(m, bubble); true }
            text.setOnClickListener { tap { menu(m, bubble) } }
            text.setOnTouchListener { v, e -> openLinkAt(v as TextView, e) }
            pollQuestion.maxWidth = maxText
            bubble.addView(emojiRow, LayoutParams(WRAP, WRAP))
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
            bubble.setOnClickListener { tap { if (isVideo(m)) openVideo(m) else if (isImage(m)) openPhoto(m) else menu(m, bubble) } }
            setOnClickListener { tap { menu(m, bubble) } }
            setOnLongClickListener { menu(m, bubble); true }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // MATCH_PARENT inside a WRAP_CONTENT bubble collapses the filename to nothing.
            // Give the card a real width that still fits the row, including the bubble padding.
            val avatarWidth = if (av.visibility != GONE) dp(38) else 0
            val rowInner = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight - avatarWidth
            val pad = bubble.paddingLeft + bubble.paddingRight
            val cap = minOf((ctx.resources.displayMetrics.widthPixels * 0.78f).toInt(), rowInner) - pad
            file.layoutParams.width = cap.coerceAtLeast(0)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
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
            av.setOnClickListener { act.openPage(PersonProfilePage(act,m.str("sender_user_id"))) }
            sender.setOnClickListener { act.openPage(PersonProfilePage(act,m.str("sender_user_id"))) }
            sender.visibility = if (group && !out && item.firstInRun) VISIBLE else GONE
            sender.text = m.str("sender_name") + m.str("sender_tag").let { if (it.isEmpty()) "" else " · $it" }
            sender.personColor(m.str("sender_color"), Theme.Role.MUTED)
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
            // The quoted person's own colour, read against the bubble it sits in.
            m.optJSONObject("reply_to")?.str("sender_color")?.let { c ->
                ProfileStyle.nameOn(c, if (out) Theme.p.outgoing else Theme.p.bg1)?.let { quoteName.setTextColor(it) }
            }
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
            val video = isVideo(m)
            val image = isImage(m) || video
            val voiceMsg = isVoice(m)
            val circleMsg = isCircle(m)
            val tight = image || circleMsg
            file.visibility = if (f != null && !image && !voiceMsg && !circleMsg) VISIBLE else GONE
            photo.visibility = if (image) VISIBLE else GONE
            voice.visibility = if (voiceMsg) VISIBLE else GONE
            circle.visibility = if (circleMsg) VISIBLE else GONE
            bubble.setPadding(if (tight) dp(4) else dp(15), if (tight) dp(4) else dp(9), if (tight) dp(4) else dp(14), dp(7))
            text.setPadding(if (tight) dp(11) else 0, if (tight) dp(6) else 0, if (tight) dp(10) else 0, 0)
            meta.setPadding(0, 0, if (tight) dp(10) else 0, 0)
            transBtn.setPadding(if (tight) dp(11) else 0, 0, 0, 0)
            transText.setPadding(if (tight) dp(11) else 0, 0, if (tight) dp(10) else 0, 0)
            val strong = if (out) Theme.p.onAccent else Theme.p.accent
            if (voiceMsg && f != null) {
                wave.bars = runCatching { android.util.Base64.decode(f.str("wave"), android.util.Base64.DEFAULT) }.getOrDefault(ByteArray(0))
                wave.played = strong
                wave.rest = muted
                playBtn.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(strong) }
                playBtn.imageTintList = android.content.res.ColorStateList.valueOf(if (out) Theme.p.accent else Theme.p.onAccent)
                voiceTime.setTextColor(muted)
                speedChip.setTextColor(strong)
                speedChip.background = GradientDrawable().apply { cornerRadius = ctx.dpf(12); setColor((strong and 0x00FFFFFF) or (if (out) 0x3C000000 else 0x22000000)) }
            }
            if (circleMsg && f != null) {
                circle.boundId = m.str("id")
                circle.ringColor = strong
                circle.lengthMs = f.optLong("duration")
                circle.setPoster(Photos.thumb(m))
            }
            if (voiceMsg || circleMsg) {
                transBtn.setTextColor(strong)
                transBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(ctx.getDrawable(R.drawable.ic_transcript)?.mutate()?.also { it.setTint(strong); it.setBounds(0, 0, dp(18), dp(18)) }, null, null, null)
                transText.setTextColor(fg)
            }
            bindPlayback()
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
                val length = if (video && f.optLong("duration") > 0) Videos.clock(f.optLong("duration")) else ""
                photo.video = video
                photo.length = length
                photo.contentDescription = if (video) "Видео" + if (length.isEmpty()) "" else ", $length" else "Фото"
                if (video) {
                    // Only the first frame is shown; the video itself is fetched when it is played.
                    photo.setBackgroundColor(0xFF101722.toInt())
                    photo.setImageBitmap(Photos.thumb(m))
                } else {
                    photo.background = null
                    val cached = Photos.full.get(id)
                    photo.setImageBitmap(cached ?: Photos.thumb(m))
                    if (cached == null && Prefs.autoloadPhotos(ctx)) Photos.load(m) { bmp -> if (m.str("id") == id) photo.setImageBitmap(bmp) }
                }
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
            // Where every emoji has an animation, they play in place of the text.
            val moving = if (emoji > 0 && animatedEmoji) AnimatedEmoji.sheetsOf(ctx, body) else null
            bindEmojiRow(moving)
            if (moving != null) text.visibility = GONE
            if (emoji > 0) {
                bubble.background = null
                bubble.setPadding(dp(2), 0, dp(2), dp(2))
                text.setTextColor(Theme.p.text)
                time.setTextColor(Theme.p.muted)
            } else {
                bubble.background = bg
            }
            bindReactions(m, out && emoji == 0)
            time.text = if (m.optBoolean("edited")) "изменено " + hhmm(m.optLong("sent_at")) else hhmm(m.optLong("sent_at"))
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
            // The mark hops when this message moves on: sent, delivered, read.
            if (out && statusId == m.str("id") && statusSig != st && st != "queued") status.pop(0.4f, 0.36f, 0.42f)
            statusId = m.str("id")
            statusSig = st
            retry.visibility = if (out && st == "failed") VISIBLE else GONE
            bubble.contentDescription = buildString {
                append(m.str("sender_name")).append(", ").append(time.text).append(": ")
                if (video) append("видео. ") else if (image) append("фото. ")
                else if (voiceMsg) append("голосовое сообщение, ").append(Videos.clock(f?.optLong("duration") ?: 0)).append(". ")
                else if (circleMsg) append("видеосообщение, ").append(Videos.clock(f?.optLong("duration") ?: 0)).append(". ")
                else if (f != null) append("файл ").append(f.str("name")).append(". ")
                transcriptOf(m)?.takeIf { it.isNotEmpty() }?.let { append("Текст: ").append(it).append(". ") }
                if (kind == "poll") append("опрос. ")
                append(body)
                if (out && statusText(m).isNotEmpty()) append(". ").append(statusText(m))
                if (group && out && m.optInt("delivered_count") > 0) append(", доставлено: ").append(m.optInt("delivered_count"))
                for (r in m.optJSONArray("reactions")?.objects() ?: emptyList()) append(", ").append(r.str("emoji")).append(" ").append(r.optInt("count"))
            }
            invalidate()
        }

        private val onPlayback: () -> Unit = { bindPlayback() }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            Playback.addListener(onPlayback)
            Transcription.addListener(onPlayback)
            bindPlayback()
        }

        override fun onDetachedFromWindow() {
            Playback.removeListener(onPlayback)
            Transcription.removeListener(onPlayback)
            // A circle plays on this view's texture: leaving the screen ends it.
            if (isCircle(m) && Playback.stateOf(m.str("id")) != Playback.State.IDLE) Playback.stop()
            super.onDetachedFromWindow()
        }

        /** What changes while a message plays or is turned into text: the button, the wave, the time, the status line. */
        private fun bindPlayback() {
            val id = m.str("id")
            val f = m.optJSONObject("file") ?: return
            val st = Playback.stateOf(id)
            val active = st == Playback.State.PLAYING || st == Playback.State.PAUSED
            if (isVoice(m)) {
                playBtn.setImageResource(if (st == Playback.State.PLAYING) R.drawable.ic_pause else R.drawable.ic_play)
                playBtn.contentDescription = if (st == Playback.State.PLAYING) "Пауза" else "Воспроизвести"
                wave.progress = Playback.progressOf(id)
                val total = f.optLong("duration")
                voiceTime.text = if (active) Videos.clock(Playback.positionMs.toLong()) + " / " + Videos.clock(total) else Videos.clock(total)
                speedChip.visibility = if (active) VISIBLE else INVISIBLE
                speedChip.text = when (Playback.speed) { 1.5f -> "1,5×"; 2f -> "2×"; else -> "1×" }
                speedChip.contentDescription = "Скорость " + speedChip.text
            } else if (isCircle(m)) {
                circle.boundId = id
                circle.state = st
                circle.progress = Playback.progressOf(id)
                circle.positionMs = if (active) Playback.positionMs.toLong() else 0
                circle.lengthMs = f.optLong("duration")
                circle.contentDescription = "Видеосообщение, " + Videos.clock(f.optLong("duration"))
            }
            if (isVoice(m) || isCircle(m)) {
                val text = transcriptOf(m)
                val status = Transcription.statusOf(id)
                transText.visibility = if (text != null) VISIBLE else GONE
                transText.text = if (text.isNullOrEmpty()) "Речь не найдена" else text
                transBtn.visibility = if (text == null) VISIBLE else GONE
                transBtn.text = when {
                    status?.phase == Transcription.Phase.MODEL -> "Скачиваем модель… ${status.percent}%"
                    status?.phase == Transcription.Phase.WORKING -> "Расшифровываем…"
                    else -> "Расшифровать"
                }
                transBtn.isEnabled = status == null
            } else {
                transText.visibility = GONE
                transBtn.visibility = GONE
            }
        }

        private fun bindEmojiRow(sheets: List<AnimatedEmoji.Sheet>?) {
            emojiRow.visibility = if (sheets == null) GONE else VISIBLE
            if (sheets == null) return
            val size = dp(when (sheets.size) { 1 -> 64; 2 -> 52; else -> 42 })
            while (emojiRow.childCount > sheets.size) emojiRow.removeViewAt(emojiRow.childCount - 1)
            while (emojiRow.childCount < sheets.size) emojiRow.addView(AnimatedEmojiView(ctx), LayoutParams(size, size))
            for ((i, s) in sheets.withIndex()) {
                val v = emojiRow.getChildAt(i) as AnimatedEmojiView
                v.layoutParams = LayoutParams(size, size)
                v.show(s)
            }
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
            for (r in list) reactRow.addView(reactionChip(ctx, r, onAccent) { Haptics.tick(reactRow); react(m, r.str("emoji")) }.apply {
                if (pop && !Theme.reducedMotion) {
                    alpha = 0f
                    animate().alpha(1f).setDuration(120).start()
                    pop(0.3f, 0.42f, 0.45f)
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
    private var viewerStop: (() -> Unit)? = null

    /** Opens the photo among all pictures of the chat; swiping left and right moves between them. */
    private fun openPhoto(m: JSONObject) {
        val photos = messages.filter { isImage(it) }
        val at = photos.indexOfFirst { it.str("id") == m.str("id") }
        if (at < 0) showViewer(listOf(m), 0) else showViewer(photos, at)
    }

    private class PhotoPage(val img: ZoomImageView) : RecyclerView.ViewHolder(img) {
        var id = ""
    }

    /** Full-screen photos over the chat: tap or Back closes; fades unless motion is reduced. */
    private fun showViewer(photos: List<JSONObject>, start: Int) {
        closeViewer()
        hideKeyboard()
        val v = FrameLayout(ctx)
        v.setBackgroundColor(0xF2040914.toInt())
        v.isClickable = true
        val pager = RecyclerView(ctx)
        val lm = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
        pager.layoutManager = lm
        androidx.recyclerview.widget.PagerSnapHelper().attachToRecyclerView(pager)
        pager.itemAnimator = null
        pager.adapter = object : RecyclerView.Adapter<PhotoPage>() {
            override fun getItemCount() = photos.size
            override fun onCreateViewHolder(parent: ViewGroup, type: Int): PhotoPage {
                val img = ZoomImageView(ctx)
                img.scaleType = ImageView.ScaleType.FIT_CENTER
                img.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                img.setPadding(dp(8), column.paddingTop + dp(8), dp(8), column.paddingBottom + dp(8))
                img.layoutParams = RecyclerView.LayoutParams(MATCH, MATCH)
                img.onTap = { closeViewer() }
                return PhotoPage(img)
            }
            override fun onBindViewHolder(h: PhotoPage, pos: Int) {
                val m = photos[pos]
                val id = m.str("id")
                h.id = id
                h.img.reset()
                h.img.setImageBitmap(Photos.full.get(id) ?: Photos.thumb(m))
                if (Photos.full.get(id) != null) return
                CoreClient.call("download_file", JSONObject().put("message_id", id)) { r, e ->
                    if (e != null) return@call if (pos == start) act.toast("Не удалось открыть фото") else Unit
                    val path = (r as JSONObject).str("path")
                    val side = maxOf(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
                    Thread {
                        val bmp = Photos.decode(path, side)
                        act.runOnUiThread { if (bmp != null && h.id == id) h.img.setImageBitmap(bmp) }
                    }.start()
                }
            }
        }
        lm.scrollToPositionWithOffset(start, 0)
        v.addView(pager, flp(MATCH, MATCH))
        val counter = TextView(ctx)
        counter.setTextColor(Theme.p.text)
        counter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        counter.typeface = weightFace(600)
        counter.setPadding(dp(14), dp(6), dp(14), dp(6))
        counter.background = GradientDrawable().apply { setColor(0x66000000); cornerRadius = ctx.dpf(16) }
        fun describe(pos: Int) {
            counter.text = "${pos + 1} из ${photos.size}"
            v.contentDescription = if (photos.size > 1) "Просмотр фото ${pos + 1} из ${photos.size}. Листайте влево и вправо, двойное касание увеличивает, нажмите, чтобы закрыть" else "Просмотр фото. Нажмите, чтобы закрыть"
        }
        describe(start)
        if (photos.size > 1) {
            v.addView(counter, flp(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = column.paddingTop + dp(12) })
            pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val pos = lm.findFirstCompletelyVisibleItemPosition()
                    if (pos >= 0) describe(pos)
                }
            })
        }
        root.addView(v, flp(MATCH, MATCH))
        viewer = v
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(Theme.ms(180)).start()
    }

    private fun closeViewer() {
        val v = viewer ?: return
        viewer = null
        viewerStop?.invoke()
        viewerStop = null
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(Theme.ms(180)).withEndAction { root.removeView(v) }.start()
    }

    /** Fetches and decrypts the video, then plays it over the chat. */
    private fun openVideo(m: JSONObject) {
        if (m.optJSONObject("file")?.optBoolean("downloaded") != true) act.toast("Загружаем видео…")
        CoreClient.call("download_file", JSONObject().put("message_id", m.str("id"))) { v, e ->
            if (e != null) return@call act.toast(e.message)
            showVideo(m, (v as JSONObject).str("path"))
        }
    }

    /** The player over the chat: a tap pauses and resumes, the cross or Back closes it. */
    private fun showVideo(m: JSONObject, path: String) {
        closeViewer()
        hideKeyboard()
        val v = FrameLayout(ctx)
        v.setBackgroundColor(0xF2040914.toInt())
        v.isClickable = true
        v.contentDescription = "Видео. Нажмите, чтобы остановить или продолжить"
        val player = android.widget.VideoView(ctx)
        player.keepScreenOn = true
        v.addView(player, flp(MATCH, MATCH, Gravity.CENTER))

        fun round(icon: Int, label: String, size: Int) = ImageView(ctx).apply {
            setImageResource(icon)
            setColorFilter(0xFFFFFFFF.toInt())
            contentDescription = label
            val pad = dp((size - 24) / 2)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66000000) }
            isClickable = true
            isFocusable = true
        }
        val toggle = round(R.drawable.ic_pause, "Пауза", 44)
        val seek = android.widget.SeekBar(ctx)
        seek.progressTintList = android.content.res.ColorStateList.valueOf(Theme.p.accent)
        seek.thumbTintList = android.content.res.ColorStateList.valueOf(Theme.p.accent)
        seek.contentDescription = "Перемотка"
        val time = TextView(ctx)
        time.setTextColor(0xFFFFFFFF.toInt())
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        time.typeface = weightFace(500)
        val total = m.optJSONObject("file")?.optLong("duration") ?: 0L
        time.text = Videos.clock(0) + " / " + Videos.clock(total)
        val bar = LinearLayout(ctx)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(6), dp(6), dp(16), dp(6))
        bar.background = GradientDrawable().apply { setColor(0x80000000.toInt()); cornerRadius = ctx.dpf(28) }
        bar.addView(toggle, lp(dp(44), dp(44)))
        bar.addView(seek, lp(0, WRAP, 1f).apply { marginStart = dp(6); marginEnd = dp(10) })
        bar.addView(time, lp(WRAP, WRAP))
        v.addView(bar, flp(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), column.paddingBottom + dp(14)) })
        val close = round(R.drawable.ic_close, "Закрыть", 44)
        close.setOnClickListener { closeViewer() }
        v.addView(close, flp(dp(44), dp(44), Gravity.TOP or Gravity.END).apply { setMargins(0, column.paddingTop + dp(10), dp(12), 0) })

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var dragging = false
        var length = 0
        val tick = object : Runnable {
            override fun run() {
                if (!dragging && length > 0) {
                    seek.progress = player.currentPosition
                    time.text = Videos.clock(player.currentPosition.toLong()) + " / " + Videos.clock(length.toLong())
                }
                handler.postDelayed(this, 250)
            }
        }
        fun playing(on: Boolean) = toggle.apply {
            setImageResource(if (on) R.drawable.ic_pause else R.drawable.ic_play)
            contentDescription = if (on) "Пауза" else "Играть"
        }
        fun flip() {
            if (player.isPlaying) {
                player.pause()
                playing(false)
            } else {
                if (length > 0 && player.currentPosition >= length - 100) player.seekTo(0)
                player.start()
                playing(true)
            }
        }
        player.setOnPreparedListener { mp ->
            length = mp.duration
            seek.max = length
            player.start()
            handler.post(tick)
        }
        player.setOnCompletionListener {
            playing(false)
            seek.progress = seek.max
        }
        player.setOnErrorListener { _, _, _ ->
            act.toast("Не удалось воспроизвести видео")
            closeViewer()
            true
        }
        player.setOnClickListener { flip() }
        toggle.setOnClickListener { flip() }
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                player.seekTo(progress)
                time.text = Videos.clock(progress.toLong()) + " / " + Videos.clock(length.toLong())
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) { dragging = true }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) { dragging = false }
        })
        player.setVideoPath(path)
        viewerStop = {
            handler.removeCallbacks(tick)
            player.stopPlayback()
        }
        root.addView(v, flp(MATCH, MATCH))
        viewer = v
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(Theme.ms(180)).start()
    }

    /** Opens a link or a person's tag under the finger; any other tap falls through to the bubble. */
    private fun openLinkAt(tv: TextView, e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP && e.action != MotionEvent.ACTION_DOWN) return false
        val text = tv.text as? Spanned ?: return false
        val layout = tv.layout ?: return false
        val x = e.x.toInt() - tv.totalPaddingLeft + tv.scrollX
        val y = e.y.toInt() - tv.totalPaddingTop + tv.scrollY
        val line = layout.getLineForVertical(y)
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return false
        val off = layout.getOffsetForHorizontal(line, x.toFloat())
        val mention = text.getSpans(off, off, MentionSpan::class.java).firstOrNull()
        val link = text.getSpans(off, off, URLSpan::class.java).firstOrNull()
        if (mention == null && link == null) return false
        if (e.action == MotionEvent.ACTION_UP) {
            if (mention != null) openMention(mention.name)
            else if (link != null) {
                try {
                    act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)))
                } catch (_: Exception) {
                    act.toast("Не удалось открыть ссылку")
                }
            }
        }
        return true
    }

    private fun openMention(name: String) {
        CoreClient.call("lookup_user", JSONObject().put("username", name).put("conversation_id", conversationId)) { v, e ->
            if (e != null) act.toast(e.message)
            else act.openPage(PersonProfilePage(act, (v as JSONObject).str("user_id")))
        }
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
                setOnClickListener { Haptics.confirm(this); close(); react(m, e) }
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
        if (isVideo(m)) pm.add("Открыть видео") { openVideo(m) }
        val postable = ok && m.str("status") != "failed" && m.str("kind") in setOf("text", "file", "poll")
        if (postable && conv.optBoolean("can_pin")) {
            val on = !m.optBoolean("pinned")
            pm.add(if (on) "Закрепить" else "Открепить") {
                CoreClient.call("pin_message", JSONObject().put("message_id", m.str("id")).put("on", on)) { _, e -> if (e != null) act.toast(e.message) }
            }
        }
        if (postable && !conv.optBoolean("saved")) pm.add("В избранное") { savedCopy(m) }
        if (!gone && m.optBoolean("outgoing") && m.str("status") in setOf("sent", "delivered", "read") && m.str("kind") in setOf("text", "file") && !isVoice(m) && !isCircle(m))
            pm.add("Изменить") { setEdit(m) }
        if (m.str("kind") == "poll" && m.optBoolean("outgoing") && m.optJSONObject("poll")?.optBoolean("closed") != true) pm.add("Завершить опрос") {
            CoreClient.call("close_poll", JSONObject().put("message_id", m.str("id"))) { _, e -> if (e != null) act.toast(e.message) }
        }
        if (isVoice(m) || isCircle(m)) {
            if (transcriptOf(m) != null) pm.add("Убрать расшифровку") { Transcription.clear(m.str("id")) }
            else pm.add("Расшифровать") { Transcription.request(act, m) }
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
        if (!ok) pm.add("Повторить расшифровку") { retryDecrypt(m) }
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

    private fun retryDecrypt(m: JSONObject) {
        CoreClient.call("retry_decrypt", JSONObject().put("message_id", m.str("id"))) { v, e ->
            if (e != null) act.toast(e.message)
            else if ((v as? JSONObject)?.optBoolean("decrypted") != true) act.toast((v as? JSONObject)?.str("message").orEmpty())
        }
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
        val mentionRow = LinearLayout(ctx)
        val mentionScroll = android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled=false; visibility=View.GONE; addView(mentionRow) }
        composer.addView(mentionScroll,lp())
        var mentionGeneration=0
        edit.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int) {}
            override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int) {}
            override fun afterTextChanged(s:Editable?) {
                val cursor=edit.selectionStart.coerceAtLeast(0)
                val prefix=s.toString().take(cursor)
                val match=Regex("(?:^|\\s)@([A-Za-z0-9_]*)$").find(prefix)
                val gen=++mentionGeneration
                mentionScroll.visibility=View.GONE
                if(match==null || blocked)return
                val start=prefix.lastIndexOf('@')
                CoreClient.call("mention_candidates",JSONObject().put("conversation_id",conversationId).put("query",match.groupValues[1])) { v,e ->
                    if(gen!=mentionGeneration || e!=null)return@call
                    val choices=(v as? JSONArray)?.objects().orEmpty();mentionRow.removeAllViews()
                    choices.take(6).forEach { candidate ->
                        val b=PillButton(ctx,"@"+candidate.str("username"),primary=false)
                        b.contentDescription="Упомянуть "+candidate.str("display_name")
                        b.setOnClickListener { if(edit.text.toString().take(edit.selectionStart.coerceAtLeast(0))==prefix) edit.text.replace(start,cursor,"@"+candidate.str("username")+" ") }
                        mentionRow.addView(b,lp(WRAP,dp(44)))
                    }
                    mentionScroll.visibility=if(choices.isEmpty())View.GONE else View.VISIBLE
                }
            }
        })
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
        cancelReply = cancel
        cancel.setOnClickListener { if (editing != null) cancelEdit() else setReply(null) }
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
            val pm = PopupMenu(act, attach).add("Фото") { pickPhotos() }.add("Видео") { pickVideos() }.add("Камера") { takePhoto() }.add("Файл") { pickFile() }
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
        edit.onImages = { pastedImages(it) }
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
        circleBtn.setOnClickListener { startCircle() }
        mic.setOnClickListener { startVoice() }
        capsule.addView(circleBtn)
        capsule.addView(mic)
        recBar.gravity = Gravity.CENTER_VERTICAL
        recBar.visibility = View.GONE
        val trash = IconButton(ctx, R.drawable.ic_trash, "Удалить запись", 48).apply { setOnClickListener { cancelVoice() } }
        recBar.addView(trash)
        recDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE04040.toInt()) }
        recBar.addView(recDot, lp(dp(10), dp(10)).apply { marginStart = dp(4) })
        recBar.addView(recTime, lp(WRAP, WRAP).apply { marginStart = dp(10) })
        recWave.played = Theme.p.accent
        recWave.rest = Theme.p.accent
        recBar.addView(recWave, lp(0, dp(28), 1f).apply { setMargins(dp(12), 0, dp(8), 0) })
        val recSend = IconButton(ctx, R.drawable.ic_send, "Отправить запись", 44, iconRole = Theme.Role.ON_ACCENT)
        recSend.background = GlassDrawable(ctx, GlassDrawable.Kind.ACTION, ctx.dpf(22))
        recSend.setOnClickListener { finishVoice() }
        recBar.addView(recSend, lp(dp(44), dp(44)).apply { setMargins(0, dp(2), dp(2), dp(2)) })
        capsule.addView(recBar, lp(0, dp(48), 1f))
        recordHidden = listOf(attach, edit, emoji, circleBtn, mic, sendSlot)
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

    /**
     * With text the send button springs in: the slot opens, the arrow grows and turns into
     * place with a little overshoot. Emptied, it folds away without bouncing.
     */
    private fun updateSend(animated: Boolean) {
        // A caption may be emptied, so editing a file keeps the button.
        val target = if (edit.text.isNotBlank() || editing?.str("kind") == "file") 1f else 0f
        send.isEnabled = !blocked
        send.alpha = if (blocked) 0.5f else 1f
        send.contentDescription = "Отправить"
        send.describeState(if (blocked) "Отправка приостановлена: ключ собеседника изменился" else null)
        if (target == sendSpring.target && (sendSpring.running || target == sendShown)) return applySend()
        sendSpring.damping = if (target > 0f) 0.62f else 1f
        if (animated) sendSpring.to(target) else sendSpring.snap(target)
    }

    private fun applySend() {
        val idle = sendShown <= 0.02f && editing == null && recorder == null
        mic.visibility = if (idle) View.VISIBLE else View.GONE
        circleBtn.visibility = if (idle) View.VISIBLE else View.GONE
        mic.isEnabled = !blocked
        circleBtn.isEnabled = !blocked
        mic.alpha = if (blocked) 0.5f else 1f
        circleBtn.alpha = if (blocked) 0.5f else 1f
        if (recorder != null) return
        val open = sendShown.coerceIn(0f, 1f)
        val lpSlot = sendSlot.layoutParams
        lpSlot.width = (dp(48) * open).toInt()
        sendSlot.layoutParams = lpSlot
        send.translationX = ctx.dpf(20) * (1 - open)
        val grow = 0.4f + 0.6f * sendShown
        send.scaleX = grow
        send.scaleY = grow
        send.rotation = -70f * (1 - sendShown)
        sendSlot.visibility = if (open == 0f) View.GONE else View.VISIBLE
        send.importantForAccessibility = if (sendShown < 1f && edit.text.isBlank()) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun canSwipeReply(m: JSONObject) =
        m.str("kind") != "undecryptable" && composer.visibility == View.VISIBLE

    private fun startSwipeReply(m: JSONObject) {
        if (!canSwipeReply(m)) return
        setReply(m)
    }

    private fun setEdit(m: JSONObject) {
        if (editing == null) savedDraft = edit.text.toString()
        editing = m
        replyTo = null
        composerBg.radius = ctx.dpf(24)
        composerBox.invalidate()
        (replyLine.background as GradientDrawable).setColor(Theme.p.accent)
        val t = m.str("text").replace("\n", " ").ifEmpty { if (isImage(m)) "Фото" else if (isVideo(m)) "Видео" else "Файл" }
        replyName.text = "Изменение сообщения"
        replyText.text = t
        replyBar.contentDescription = "Изменение сообщения: $t"
        cancelReply?.contentDescription = "Отменить изменение"
        edit.setText(m.str("text"))
        edit.setSelection(edit.text.length)
        edit.requestFocus()
        edit.post { (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(edit, 0) }
        updateSend(true)
        expand(replyBar, true)
    }

    private fun leaveEdit() {
        if (editing == null) return
        editing = null
        edit.setText(savedDraft)
        savedDraft = ""
        cancelReply?.contentDescription = "Отменить ответ"
    }

    private fun cancelEdit() {
        leaveEdit()
        composerBg.radius = ctx.dpf(31)
        composerBox.invalidate()
        expand(replyBar, false)
        updateSend(true)
    }

    private fun setReply(m: JSONObject?) {
        leaveEdit()
        replyTo = m
        composerBg.radius = ctx.dpf(if (m != null) 24 else 31)
        composerBox.invalidate()
        (replyLine.background as GradientDrawable).setColor(Theme.p.accent)
        if (m != null) {
            val t = when {
                isImage(m) -> "Фото"
                isVideo(m) -> "Видео"
                isVoice(m) -> "Голосовое сообщение"
                isCircle(m) -> "Видеосообщение"
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

    // ---- voice messages and circles ----

    private fun startVoice() {
        if (blocked || editing != null || recorder != null) return
        act.withPermission(android.Manifest.permission.RECORD_AUDIO) { granted ->
            if (!granted) return@withPermission act.toast("Для записи нужен доступ к микрофону")
            if (recorder != null) return@withPermission
            hideKeyboard()
            val r = VoiceRecorder(ctx)
            if (!r.start()) return@withPermission act.toast("Не удалось начать запись")
            recorder = r
            recLevels.clear()
            r.onLevel = { v ->
                recLevels.add((v * 255).toInt().coerceIn(14, 255).toByte())
                if (recLevels.size > 90) recLevels.removeAt(0)
                recWave.bars = recLevels.toByteArray()
                recWave.progress = 1f
            }
            showRecorder(true)
            root.removeCallbacks(recTick)
            root.post(recTick)
        }
    }

    private fun showRecorder(on: Boolean) {
        for (v in recordHidden) v.visibility = if (on) View.GONE else View.VISIBLE
        recBar.visibility = if (on) View.VISIBLE else View.GONE
        recTime.text = "0:00"
        recWave.bars = ByteArray(0)
        recDot.alpha = 1f
        if (!on) {
            sendSlot.visibility = View.VISIBLE
            applySend()
        }
    }

    private fun finishVoice() {
        val r = recorder ?: return
        root.removeCallbacks(recTick)
        val res = r.stop()
        recorder = null
        showRecorder(false)
        if (res == null) return act.toast("Не удалось сохранить запись")
        if (res.durationMs < 600) {
            res.file.delete()
            return act.toast("Сообщение слишком короткое")
        }
        sendVoice(res)
    }

    private fun cancelVoice() {
        val r = recorder ?: return
        root.removeCallbacks(recTick)
        r.cancel()
        recorder = null
        showRecorder(false)
    }

    private fun sendVoice(res: VoiceRecorder.Result) {
        val p = JSONObject().put("_account_id", accountId).put("conversation_id", conversationId).put("path", res.file.absolutePath)
            .put("mime", "audio/mp4").put("kind", "voice").put("name", "voice.m4a").put("duration", res.durationMs)
            .put("wave", android.util.Base64.encodeToString(res.wave, android.util.Base64.NO_WRAP))
        replyTo?.let { p.put("reply_to", it.str("id")) }
        setReply(null)
        CoreClient.call("send_file", p) { _, e ->
            res.file.delete()
            if (e != null && accountId == CoreClient.accountId) act.toast(e.message)
        }
    }

    private fun startCircle() {
        if (blocked || editing != null || recorder != null) return
        if (!conv.optBoolean("can_media", true)) return act.toast("Здесь нельзя отправлять видеосообщения")
        act.withPermission(android.Manifest.permission.CAMERA) { cam ->
            if (!cam) return@withPermission act.toast("Для съёмки нужен доступ к камере")
            act.withPermission(android.Manifest.permission.RECORD_AUDIO) { mic ->
                if (!mic) return@withPermission act.toast("Для записи нужен доступ к микрофону")
                hideKeyboard()
                CircleCamera(act) { file, ms, info -> sendCircle(file, ms, info) }.show()
            }
        }
    }

    private fun sendCircle(file: File, ms: Long, info: VideoInfo?) {
        if (accountId != CoreClient.accountId) { file.delete(); return }
        val p = JSONObject().put("_account_id", accountId).put("conversation_id", conversationId).put("path", file.absolutePath)
            .put("mime", "video/mp4").put("kind", "circle").put("name", "circle.mp4").put("duration", ms)
        if (info != null) {
            info.thumb?.let { p.put("thumb", it) }
            val side = minOf(info.width, info.height)
            if (side > 0) p.put("width", side).put("height", side)
        }
        replyTo?.let { p.put("reply_to", it.str("id")) }
        setReply(null)
        CoreClient.call("send_file", p) { _, e ->
            file.delete()
            if (e != null && accountId == CoreClient.accountId) act.toast(e.message)
        }
    }

    private fun sendText() {
        editing?.let { m ->
            val changed = edit.text.toString().trim()
            if (blocked || (m.str("kind") == "text" && changed.isEmpty())) return
            cancelEdit()
            if (changed != m.str("text")) CoreClient.call("edit_message", JSONObject().put("message_id", m.str("id")).put("text", changed)) { _, e -> if (e != null) act.toast(e.message) }
            return
        }
        val text = edit.text.toString().trim()
        if (text.isEmpty() || blocked) return
        val p = JSONObject().put("conversation_id", conversationId).put("text", text)
        replyTo?.let { p.put("reply_to", it.str("id")) }
        Haptics.confirm(send)
        // The arrow leaps up as the button folds away; the fold owns its scale from here.
        send.stopSprings(Prop.SCALE)
        send.kick(Prop.Y, -ctx.dpf(9), 0.32f, 0.55f)
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

    private enum class Kind { PHOTO, VIDEO, FILE }

    private fun pickUris(kind: Kind, got: (List<Pick>) -> Unit) {
        val intent = when (kind) {
            Kind.PHOTO ->
                if (android.os.Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10)
                else Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            Kind.VIDEO ->
                if (android.os.Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("video/*").putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 5)
                else Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            Kind.FILE ->
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        act.startForResult(intent) { code, data ->
            if (code != Activity.RESULT_OK || data == null) return@startForResult
            val uris = ArrayList<Uri>()
            data.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { uris += it } }
            data.data?.let { if (uris.isEmpty()) uris += it }
            got(uris.map { ctx.pickOf(it) })
        }
    }

    private fun pickPhotos() = pickUris(Kind.PHOTO) { attach(it, true) }

    private fun pickVideos() = pickUris(Kind.VIDEO) { attach(it, true) }

    private fun pickFile() = pickUris(Kind.FILE) { attach(it, false) }

    /** Everything that is about to be sent waits in the attach window: preview, "as a file", caption. */
    private fun attach(picks: List<Pick>, photos: Boolean) {
        if (picks.isEmpty() || composer.visibility != View.VISIBLE) return
        // A video is shrunk before it is sent, so it may be big; everything else goes as it is.
        val sendsAsVideo = { p: Pick -> p.video && (photos || picks.all { it.video }) }
        val big = picks.firstOrNull { if (sendsAsVideo(it)) it.size > 600L * 1024 * 1024 else it.size > 50L * 1024 * 1024 }
        if (big != null) return act.toast(if (sendsAsVideo(big)) "Видео больше 600 МБ: ${big.name}. Обрежьте его" else "Файл больше 50 МБ: ${big.name}")
        hideKeyboard()
        // A video picked as a document still goes as a video unless "as a file" is switched on.
        val media = photos || picks.all { it.video }
        val again = when {
            picks.all { it.video } -> Kind.VIDEO
            photos && picks.all { it.image } -> Kind.PHOTO
            else -> Kind.FILE
        }
        AttachSheet(act, picks, media, { added -> pickUris(again) { added(it) } }) { chosen, asPhotos, caption ->
            chosen.forEachIndexed { n, p -> sendPick(p, asPhotos, if (n == 0) caption else "") }
        }.show()
    }

    /** Pictures pasted into the field (the Paste menu or a keyboard) wait in the same window. */
    private fun pastedImages(uris: List<Uri>) {
        val picks = uris.map { ctx.pickOf(it) }.filter { it.image }
        if (picks.isNotEmpty()) attach(picks, true)
    }

    /** Copies the picked document to app storage, hands it to the core, then removes the copy. */
    private fun sendPick(p: Pick, photo: Boolean, caption: String) {
        val cr = ctx.contentResolver
        Thread {
            val dir = File(ctx.cacheDir, "outgoing").apply { mkdirs() }
            val tmp = File.createTempFile("send", ".bin", dir)
            val copied = runCatching { cr.openInputStream(p.uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } } }.isSuccess
            val thumb = if (copied && photo && p.image) Photos.makeThumb(tmp.absolutePath) else null
            act.runOnUiThread {
                if (!copied) {
                    tmp.delete()
                    return@runOnUiThread act.toast("Не удалось прочитать файл")
                }
                if (photo && p.video) return@runOnUiThread shrinkAndSend(tmp, p, caption)
                dispatchFile(tmp, p.mime, p.name, thumb, caption, null)
            }
        }.start()
    }

    /** A video sent as video is shrunk to H.264 + AAC when that is needed to fit the limit or to spare the traffic. */
    private fun shrinkAndSend(tmp: File, p: Pick, caption: String) {
        var shown = -10
        VideoCompress.run(ctx, tmp, tmp.parentFile!!, onProgress = { pct ->
            if (pct - shown >= 10 || pct == 100) {
                shown = pct
                act.toast("Сжимаем видео… $pct%")
            }
        }) { r ->
            if (r.error != null) {
                tmp.delete()
                return@run act.toast(r.error)
            }
            val out = r.file ?: tmp
            if (out.length() > VideoCompress.LIMIT) {
                out.delete()
                tmp.delete()
                return@run act.toast("Видео больше 50 МБ, а сжать его не удалось. Обрежьте его")
            }
            if (r.file != null) tmp.delete()
            val name = if (r.file != null) p.name.substringBeforeLast('.', p.name) + ".mp4" else p.name
            val mime = if (r.file != null) "video/mp4" else p.mime
            Thread {
                val clip = Videos.info(out.absolutePath)
                act.runOnUiThread { dispatchFile(out, mime, name, null, caption, clip) }
            }.start()
        }
    }

    /** Hands a prepared file to the core and removes it afterwards. */
    private fun dispatchFile(tmp: File, mime: String, name: String, thumb: Triple<String, Int, Int>?, caption: String = "", clip: VideoInfo? = null) {
        if (accountId != CoreClient.accountId) { tmp.delete(); return }
        val p = JSONObject().put("_account_id", accountId).put("conversation_id", conversationId).put("path", tmp.absolutePath).put("mime", mime).put("name", name)
        if (caption.isNotEmpty()) p.put("caption", caption)
        if (thumb != null) p.put("thumb", thumb.first).put("width", thumb.second).put("height", thumb.third)
        if (clip != null) {
            clip.thumb?.let { p.put("thumb", it) }
            p.put("width", clip.width).put("height", clip.height).put("duration", clip.durationMs)
        }
        CoreClient.call("send_file", p) { _, e ->
            tmp.delete()
            if (e != null && accountId == CoreClient.accountId) act.toast(e.message)
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
        CoreClient.call("set_draft", JSONObject().put("_account_id", accountId).put("conversation_id", conversationId).put("text", if (editing != null) savedDraft else edit.text.toString()))
    }

    override fun applyInsets(top: Int, bottom: Int, ime: Int) {
        val pad = maxOf(bottom, ime)
        if (column.paddingTop == top && column.paddingBottom == pad) return
        // Same layout pass as the resize: a posted scroll draws the old positions for one frame.
        val stick = atBottom()
        column.setPadding(0, top, 0, pad)
        if (stick) scrollToBottom(false)
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
        if (recorder != null) {
            cancelVoice()
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
        cancelVoice()
        Playback.stop()
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
