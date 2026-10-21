package app.neochat.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.CancellationSignal
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.neochat.R
import app.neochat.core.CoreClient
import org.json.JSONObject

/** Local preferences of this device (the core keeps account data). */
/**
 * The reaction a double tap puts on a message. It is an account setting: the core
 * shares it with the user's other devices and reports changes as "settings_changed".
 */
object QuickReaction {
    /** 5 columns by 7 rows; every platform offers the same set. */
    val CHOICES = "❤️ 👍 🔥 😂 😮 😢 🙏 👏 🎉 😍 🤝 👀 👎 😡 🤔 😎 🥰 😘 🤩 😁 😭 🤯 😱 🥳 🤗 💯 ✨ ⚡ 💔 💪 🙌 👌 🤮 🥺 😴".split(' ')

    /** A sheet with the whole set; the current one is highlighted and a tap chooses it. */
    fun pick(act: MainActivity, onPick: (String) -> Unit) {
        val s = Sheet(act, "Быстрая реакция")
        val ctx = s.ctx
        val current = current(ctx)
        val grid = GridLayout(ctx).apply { columnCount = 5 }
        for (e in CHOICES) {
            val on = e == current
            val cell = TextView(ctx).apply {
                text = e
                textSize = 28f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = "Быстрая реакция $e" + if (on) ", выбрана" else ""
                background = if (on) android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = ctx.dpf(16)
                    setColor((Theme.p.accent and 0x00FFFFFF) or 0x33000000)
                    setStroke(ctx.dp(2), Theme.p.accent)
                } else GlassDrawable(ctx, GlassDrawable.Kind.NONE, ctx.dpf(16), rim = false)
                if (!on) pressFeedback()
                setOnClickListener {
                    onPick(e)
                    s.dismiss()
                }
            }
            grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0
                height = ctx.dp(58)
                setMargins(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
            })
        }
        s.add(grid, 4)
        s.add(ctx.label("Двойное касание сообщения ставит эту реакцию. Выбор общий для всех ваших устройств.", 13.5f, 400, Theme.Role.MUTED), 10)
        s.show()
    }

    fun current(ctx: Context): String = Prefs.of(ctx).getString("quick_reaction", null) ?: "❤️"

    fun load(ctx: Context, done: (() -> Unit)? = null) {
        CoreClient.call("get_settings") { v, _ ->
            val e = (v as? JSONObject)?.optString("quick_reaction").orEmpty()
            if (e.isNotEmpty()) Prefs.of(ctx).edit().putString("quick_reaction", e).apply()
            done?.invoke()
        }
    }

    fun set(ctx: Context, e: String, done: (String?) -> Unit) {
        CoreClient.call("set_settings", JSONObject().put("quick_reaction", e)) { _, err ->
            if (err == null) Prefs.of(ctx).edit().putString("quick_reaction", e).apply()
            done(err?.message)
        }
    }
}

object Prefs {
    fun of(ctx: Context): SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Message text size in sp: small, normal, large, huge. */
    val TEXT_SIZES = floatArrayOf(14f, 15.5f, 17.5f, 19.5f)
    fun textSize(ctx: Context) = TEXT_SIZES[of(ctx).getInt("text_size", 1).coerceIn(0, 3)]

    /** Photos download on their own: always, on Wi-Fi only, or only on tap. */
    fun autoloadPhotos(ctx: Context): Boolean = when (of(ctx).getString("autoload", "always")) {
        "never" -> false
        "wifi" -> {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        }
        else -> true
    }

    fun applySecure(act: MainActivity) {
        if (of(act).getBoolean("secure_screen", false)) act.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else act.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/** Wraps children onto new lines, like text. */
class FlowRow(ctx: Context, private val gap: Int = ctx.dp(6)) : ViewGroup(ctx) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec).let { if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else it }
        var x = 0
        var y = 0
        var line = 0
        var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) {
                y += line + gap
                x = 0
                line = 0
            }
            x += c.measuredWidth + gap
            widest = maxOf(widest, x - gap)
            line = maxOf(line, c.measuredHeight)
        }
        setMeasuredDimension(if (MeasureSpec.getMode(wSpec) == MeasureSpec.EXACTLY) maxW else widest, y + line)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l
        var x = 0
        var y = 0
        var line = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) {
                y += line + gap
                x = 0
                line = 0
            }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gap
            line = maxOf(line, c.measuredHeight)
        }
    }
}

object Emoji {
    /** Quick reactions shown first on a tap. */
    val QUICK = listOf("👍", "❤️", "😂", "🔥", "😮", "😢", "🙏")

    val CATEGORIES = listOf(
        "Смайлы" to "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😮‍💨 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 🥸 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👻 👽 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾",
        "Жесты" to "👋 🤚 🖐 ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦾 👀 👁 👅 👄 🫶 🧠 🫀",
        "Сердца" to "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❤️‍🔥 ❤️‍🩹 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 💯 💢 💥 💫 💦 💨 🕳 💬 💭 💤",
        "Люди" to "👶 🧒 👦 👧 🧑 👱 👨 🧔 👩 🧓 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🧏 🙇 🤦 🤷 👮 🕵 💂 🥷 👷 🤴 👸 👳 🧕 🤵 👰 🤰 🤱 👼 🎅 🤶 🦸 🦹 🧙 🧚 🧛 🧜 🧝 🧞 🧟 💆 💇 🚶 🧍 🧎 🏃 💃 🕺 👯 🧖 🧗 🏇 ⛷ 🏂 🏌 🏄 🚣 🏊 ⛹ 🏋 🚴 🚵 🤸 🤼 🤽 🤾 🤹 🧘",
        "Животные" to "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🦂 🐢 🐍 🦎 🐙 🦑 🦐 🦀 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🦍 🐘 🦛 🦏 🐪 🦒 🦘 🐃 🐄 🐎 🐖 🐏 🐑 🦙 🐐 🦌 🐕 🐩 🐈 🐓 🦃 🦚 🦜 🦢 🕊 🐇 🦝 🦨 🦡 🦦 🦥 🐁 🐀 🐿 🦔 🌵 🎄 🌲 🌳 🌴 🌱 🌿 ☘️ 🍀 🍁 🍂 🍃 🌺 🌻 🌹 🥀 🌷 🌼 🌸 💐 🍄 🌰",
        "Еда" to "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶 🌽 🥕 🧄 🧅 🥔 🍠 🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🥙 🧆 🌮 🌯 🥗 🥘 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🍘 🍥 🥠 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🥜 🍯 🥛 ☕ 🍵 🧃 🥤 🍶 🍺 🍻 🥂 🍷 🥃 🍸 🍹 🧉 🍾 🧊",
        "Занятия" to "⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🏓 🏸 🏒 🏑 🥍 🏏 🥅 ⛳ 🏹 🎣 🥊 🥋 🎽 🛹 🛼 ⛸ 🥌 🎿 🏆 🥇 🥈 🥉 🏅 🎖 🎗 🎫 🎟 🎪 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎲 ♟ 🎯 🎳 🎮 🎰 🧩",
        "Места" to "🚗 🚕 🚙 🚌 🚎 🏎 🚓 🚑 🚒 🚐 🚚 🚛 🚜 🛴 🚲 🛵 🏍 🚨 🚔 🚍 🚘 🚖 🚡 🚠 🚟 🚃 🚋 🚞 🚝 🚄 🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🛩 💺 🛰 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥 🛳 ⛴ 🚢 ⚓ ⛽ 🚧 🚦 🚥 🗺 🗿 🗽 🗼 🏰 🏯 🏟 🎡 🎢 🎠 ⛲ ⛱ 🏖 🏝 🏜 🌋 ⛰ 🏔 🗻 🏕 ⛺ 🏠 🏡 🏘 🏚 🏗 🏭 🏢 🏬 🏣 🏤 🏥 🏦 🏨 🏪 🏫 🏩 💒 🏛 ⛪ 🕌 🕍 🛕 🕋 🌅 🌄 🌠 🎇 🎆 🌇 🌆 🏙 🌃 🌌 🌉 🌁",
        "Предметы" to "⌚ 📱 💻 ⌨️ 🖥 🖨 🖱 🕹 💽 💾 💿 📀 📷 📸 📹 🎥 📞 ☎️ 📺 📻 🎙 ⏰ ⏳ 📡 🔋 🔌 💡 🔦 🕯 🧯 💸 💵 💴 💶 💷 💰 💳 💎 ⚖️ 🔧 🔨 ⚒ 🛠 ⛏ 🔩 ⚙️ 🧱 ⛓ 🧲 🔫 💣 🧨 🪓 🔪 🗡 ⚔️ 🛡 🚬 ⚰️ 🏺 🔮 📿 🧿 💈 ⚗️ 🔭 🔬 🩹 🩺 💊 💉 🧬 🦠 🧫 🧪 🌡 🧹 🧺 🧻 🚽 🚰 🚿 🛁 🧼 🪒 🧽 🧴 🛎 🔑 🗝 🚪 🛋 🛏 🧸 🖼 🛍 🛒 🎁 🎈 🎏 🎀 🎊 🎉 🎎 🏮 🎐 🧧 ✉️ 📩 📨 📧 💌 📮 📦 🏷 📋 📅 📆 🗓 📇 📈 📉 📊 📌 📍 📎 🖇 📏 📐 ✂️ 🗃 🗄 🗑 🔒 🔓 🔏 🔐 📝 ✏️ 🔍 🔎",
        "Символы" to "✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ♾ ‼️ ⁉️ ❓ ❔ ❕ ❗ 〰️ ⚠️ 🚸 ⛔ 🚫 🔞 ☢️ ☣️ ⬆️ ↗️ ➡️ ↘️ ⬇️ ↙️ ⬅️ ↖️ ↕️ ↔️ 🔄 🔃 🔀 🔁 🔂 ▶️ ⏩ ⏭ ⏯ ◀️ ⏪ ⏮ 🔼 ⏫ 🔽 ⏬ ⏸ ⏹ ⏺ 🎦 🔅 🔆 📶 📳 📴 ♀️ ♂️ ⚧ ⚕️ ♻️ ⚜️ 🔱 📛 🔰 ⭕ 🌀 💠 🔷 🔶 🔹 🔸 🔺 🔻 ⚪ ⚫ 🔴 🟠 🟡 🟢 🔵 🟣 🟤 🟥 🟧 🟨 🟩 🟦 🟪 🟫 ⬛ ⬜ ⭐ 🌟 ✨ ⚡ 🔥 🌈 ☀️ 🌤 ⛅ 🌥 ☁️ 🌦 🌧 ⛈ 🌩 🌨 ❄️ ☃️ ⛄ 🌬 💧 🌊 🌙 🌚 🌝 🌞 🌍 🌎 🌏 🪐 💫 🎵 🎶 ➰ ➿ 🔔 🔕 📣 📢 🆗 🆕 🆒 🆓 🆙 🔝 🔜 🔛 🔚 🔙",
        "Флаги" to "🏁 🚩 🎌 🏴 🏳️ 🏳️‍🌈 🇷🇺 🇺🇦 🇧🇾 🇰🇿 🇺🇿 🇦🇲 🇬🇪 🇦🇿 🇰🇬 🇹🇯 🇲🇩 🇺🇸 🇬🇧 🇩🇪 🇫🇷 🇮🇹 🇪🇸 🇵🇹 🇳🇱 🇧🇪 🇨🇭 🇦🇹 🇵🇱 🇨🇿 🇸🇰 🇭🇺 🇷🇴 🇧🇬 🇷🇸 🇭🇷 🇬🇷 🇹🇷 🇮🇱 🇪🇬 🇦🇪 🇸🇦 🇮🇳 🇨🇳 🇯🇵 🇰🇷 🇹🇭 🇻🇳 🇮🇩 🇦🇺 🇨🇦 🇲🇽 🇧🇷 🇦🇷 🇨🇱 🇫🇮 🇸🇪 🇳🇴 🇩🇰 🇪🇪 🇱🇻 🇱🇹",
    ).map { (t, s) -> t to s.split(' ').filter { it.isNotBlank() } }

    /** Full emoji sheet with categories; [pick] gets the chosen emoji. */
    fun sheet(act: MainActivity, title: String = "Эмодзи", pick: (String) -> Unit) {
        val s = Sheet(act, title)
        val ctx = s.ctx
        for ((name, list) in CATEGORIES) {
            s.add(ctx.label(name, 13.5f, 600, Theme.Role.MUTED).apply { heading() }, 14)
            val grid = GridLayout(ctx).apply { columnCount = 8 }
            for (e in list) {
                val tv = TextView(ctx).apply {
                    text = e
                    textSize = 24f
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    contentDescription = e
                    background = GlassDrawable(ctx, GlassDrawable.Kind.NONE, ctx.dpf(14), rim = false)
                    pressFeedback()
                    setOnClickListener { pick(e); s.dismiss() }
                }
                grid.addView(tv, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                    width = 0
                    height = ctx.dp(46)
                })
            }
            s.add(grid, 4)
        }
        s.show()
    }
}

/**
 * Telegram-style contact QR: rounded dots, rounded finder eyes and the avatar
 * in the middle (the code uses error correction Q, the center is left empty).
 */
class PrettyQrView(ctx: Context) : View(ctx) {
    private var rows: List<String> = emptyList()
    private var name = ""
    private var seed = ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val avatar = AvatarView(ctx, 56)
    var dark = 0xFF16233C.toInt()

    init {
        contentDescription = "QR-код профиля"
    }

    fun set(r: List<String>, name: String, seed: String) {
        rows = r
        this.name = name
        this.seed = seed
        avatar.set(name, seed)
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val n = rows.size
        if (n == 0) return
        val cell = width.toFloat() / n
        val hole = (n * 0.22f).toInt().let { if ((n - it) % 2 == 1) it + 1 else it }
        val h0 = (n - hole) / 2
        val h1 = h0 + hole
        fun eye(x: Int, y: Int) = x < 7 && y < 7 || x >= n - 7 && y < 7 || x < 7 && y >= n - 7
        paint.color = dark
        for (y in 0 until n) for (x in 0 until n) {
            if (rows[y][x] != '1' || eye(x, y)) continue
            if (x in h0 until h1 && y in h0 until h1) continue
            val cx = (x + 0.5f) * cell
            val cy = (y + 0.5f) * cell
            c.drawCircle(cx, cy, cell * 0.45f, paint)
        }
        for ((ex, ey) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
            val l = ex * cell
            val t = ey * cell
            // Only slightly rounded finder eyes: strongly rounded ones break many decoders.
            val ring = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRoundRect(RectF(l, t, l + 7 * cell, t + 7 * cell), cell * 0.35f, cell * 0.35f, Path.Direction.CW)
                addRoundRect(RectF(l + cell, t + cell, l + 6 * cell, t + 6 * cell), cell * 0.15f, cell * 0.15f, Path.Direction.CW)
            }
            c.drawPath(ring, paint)
            c.drawRoundRect(RectF(l + 2 * cell, t + 2 * cell, l + 5 * cell, t + 5 * cell), cell * 0.3f, cell * 0.3f, paint)
        }
        // Avatar in the middle.
        val size = (hole - 1) * cell
        val left = (width - size) / 2
        c.save()
        c.translate(left, left)
        avatar.measure(MeasureSpec.makeMeasureSpec(size.toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size.toInt(), MeasureSpec.EXACTLY))
        avatar.layout(0, 0, size.toInt(), size.toInt())
        avatar.draw(c)
        c.restore()
    }
}

/** Card with a soft accent gradient behind the QR, like Telegram's. */
class QrCard(ctx: Context) : FrameLayout(ctx), Themed {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val qr = PrettyQrView(ctx)
    val handle = ctx.label("", 18f, 700)

    init {
        setWillNotDraw(false)
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(24), ctx.dp(24), ctx.dp(24), ctx.dp(20))
        }
        val white = FrameLayout(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = ctx.dpf(28)
            }
            setPadding(ctx.dp(18), ctx.dp(18), ctx.dp(18), ctx.dp(18))
            addView(qr, LayoutParams(MATCH, WRAP))
        }
        inner.addView(white, lp(ctx.dp(260), WRAP))
        handle.gravity = Gravity.CENTER
        handle.setTextColor(android.graphics.Color.WHITE)
        inner.addView(handle, lp(WRAP, WRAP).apply { topMargin = ctx.dp(16) })
        addView(inner, LayoutParams(MATCH, WRAP))
    }

    override fun onDraw(c: Canvas) {
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), 0xFF3B6EF5.toInt(), 0xFF7A4BE8.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dpf(32), dpf(32), paint)
    }

    override fun applyTheme() = invalidate()
}

object Qr {
    const val CONTACT_PREFIX = "neochat://u/"

    fun showMine(act: MainActivity) {
        val s = Sheet(act, "Мой QR-код")
        val ctx = s.ctx
        val card = QrCard(ctx)
        s.add(card, 4)
        s.add(ctx.label("Покажите код собеседнику: он отсканирует его в NeoChat и сразу добавит вас в контакты. Совпадение кода с ключом заодно сверяет шифрование.", 13.5f, 400, Theme.Role.MUTED), 12)
        val row = LinearLayout(ctx)
        val share = PillButton(ctx, "Поделиться", primary = false)
        val scan = PillButton(ctx, "Сканировать")
        row.addView(share, lp(0, WRAP, 1f).apply { marginEnd = ctx.dp(5) })
        row.addView(scan, lp(0, WRAP, 1f).apply { marginStart = ctx.dp(5) })
        s.add(row, 14)
        var link = ""
        CoreClient.call("my_qr") { v, e ->
            if (e != null) return@call act.toast(e.message)
            val o = v as JSONObject
            link = o.str("link")
            val rows = o.optJSONArray("qr_rows")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            CoreClient.call("status") { sv, _ ->
                val st = sv as? JSONObject ?: JSONObject()
                card.qr.set(rows, st.str("display_name").ifEmpty { o.str("username") }, st.str("user_id"))
            }
            card.handle.text = "@" + o.str("username")
        }
        share.setOnClickListener {
            if (link.isEmpty()) return@setOnClickListener
            act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), "Поделиться"))
        }
        scan.setOnClickListener { s.dismiss(); scanContact(act) }
        s.show()
    }

    fun scanContact(act: MainActivity) {
        act.withPermission(android.Manifest.permission.CAMERA) { granted ->
            if (!granted) return@withPermission act.toast("Нет доступа к камере. Разрешите его в настройках")
            QrScanner(act, CONTACT_PREFIX, "Наведите камеру на QR-код профиля NeoChat", "Это не QR-код контакта NeoChat") { link -> addByLink(act, link) }.show()
        }
    }

    fun addByLink(act: MainActivity, link: String) {
        CoreClient.call("add_contact_qr", JSONObject().put("link", link)) { v, e ->
            if (e != null) return@call act.toast(e.message)
            val c = v as JSONObject
            act.toast("${c.str("display_name")} добавлен в контакты")
            CoreClient.call("open_direct", JSONObject().put("user_id", c.str("user_id"))) { cv, ce ->
                if (ce == null) act.openChat((cv as JSONObject).str("id"))
            }
        }
    }
}

/**
 * Lock for the app and the archive through the system: fingerprint, face or the phone's own
 * screen lock (BiometricPrompt). NeoChat keeps no passcode of its own.
 */
object AppLock {
    private var backgroundAt = 0L
    private var unlockedAt = 0L
    private var authenticating = false
    var locked = false
        private set
    private var overlay: View? = null

    /** Needs Android 10: the system prompt can fall back to the screen lock only from there on. */
    fun available(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        // A lock that cannot be asked for is treated as absent rather than allowed to crash the app.
        return runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                val bm = ctx.getSystemService(android.hardware.biometrics.BiometricManager::class.java) ?: return@runCatching false
                val auth = android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                bm.canAuthenticate(auth) == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
            } else ctx.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true
        }.getOrDefault(false)
    }

    // The saved choice is read first: the system is asked only when a lock was switched on.
    fun appLock(ctx: Context) = Prefs.of(ctx).getBoolean("lock_app", false) && available(ctx)
    fun archiveLock(ctx: Context) = Prefs.of(ctx).getBoolean("lock_archive", false) && available(ctx)

    /** The passcode of 0.6.3 is gone: its hash is wiped and the switches now mean the system lock. */
    fun migrate(ctx: Context) {
        val prefs = Prefs.of(ctx)
        if (!prefs.contains("has_passcode")) return
        prefs.edit().remove("has_passcode").remove("lock_bio").apply()
        CoreClient.call("lock_clear")
    }

    fun onBackground() {
        if (!authenticating) backgroundAt = System.currentTimeMillis()
    }

    /** On start and on return from the background after the auto-lock delay. */
    fun maybeLock(act: MainActivity) {
        if (!appLock(act) || locked || authenticating) return
        val now = System.currentTimeMillis()
        if (now - unlockedAt < 3000) return // returning from the system lock screen
        val after = Prefs.of(act).getInt("lock_after", 0) * 1000L
        if (backgroundAt != 0L && now - backgroundAt < after) return
        locked = true
        prompt(act, "NeoChat заблокирован", cancellable = false) { locked = false }
    }

    fun forceLockOnStart() {
        backgroundAt = 0L
    }

    /** Asks the system once; no screen of our own. [done] gets whether the user passed. */
    fun confirm(act: MainActivity, title: String, done: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 29 || !available(act) || authenticating) return done(false)
        authenticating = true
        val exec = act.mainExecutor
        val b = android.hardware.biometrics.BiometricPrompt.Builder(act).setTitle(title).setSubtitle("NeoChat")
        if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(
            android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
        fun finish(ok: Boolean) {
            authenticating = false
            if (ok) unlockedAt = System.currentTimeMillis()
            done(ok)
        }
        b.build().authenticate(CancellationSignal(), exec, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) = finish(true)
            override fun onAuthenticationError(code: Int, msg: CharSequence?) = finish(false)
        })
    }

    /** Full-screen lock; the system prompt opens at once and again from the button. */
    fun prompt(act: MainActivity, title: String, cancellable: Boolean, ok: () -> Unit) {
        overlay?.let { act.overlay.removeView(it) }
        val ctx = act as Context
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            setBackgroundColor(Theme.p.bg0)
            setPadding(ctx.dp(32), act.topInset + ctx.dp(120), ctx.dp(32), ctx.dp(32))
        }
        v.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_lock); imageTintList = android.content.res.ColorStateList.valueOf(Theme.p.accent) }, lp(ctx.dp(44), ctx.dp(44)))
        v.addView(ctx.label(title, 20f, 700).apply { gravity = Gravity.CENTER; heading() }, lp(WRAP, WRAP).apply { topMargin = ctx.dp(18) })
        v.addView(ctx.label("Подтвердите отпечатком, лицом или блокировкой экрана телефона", 14.5f, 400, Theme.Role.MUTED).apply { gravity = Gravity.CENTER },
            lp(WRAP, WRAP).apply { topMargin = ctx.dp(8) })
        val unlock = PillButton(ctx, "Разблокировать", icon = R.drawable.ic_fingerprint)
        v.addView(unlock, lp().apply { topMargin = ctx.dp(32) })
        if (cancellable) {
            val cancel = PillButton(ctx, "Отмена", primary = false)
            v.addView(cancel, lp().apply { topMargin = ctx.dp(10) })
            cancel.setOnClickListener { close(act) }
        }
        val back: () -> Boolean = { if (cancellable) close(act) else act.moveTaskToBack(true); true }
        fun ask() = confirm(act, title) { passed ->
            if (passed) {
                close(act)
                ok()
            }
        }
        unlock.setOnClickListener { ask() }
        act.overlay.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        act.pushBack(back)
        overlay = v
        v.tag = back
        v.post { ask() }
    }

    private fun close(act: MainActivity) {
        val v = overlay ?: return
        overlay = null
        @Suppress("UNCHECKED_CAST")
        (v.tag as? () -> Boolean)?.let { act.popBack(it) }
        act.overlay.removeView(v)
    }
}

/** Russian plural of members / subscribers, e.g. "5 участников". */
fun memberCount(n: Int, channel: Boolean = false): String {
    val m10 = n % 10
    val m100 = n % 100
    val w = when {
        m10 == 1 && m100 != 11 -> if (channel) "подписчик" else "участник"
        m10 in 2..4 && m100 !in 12..14 -> if (channel) "подписчика" else "участника"
        else -> if (channel) "подписчиков" else "участников"
    }
    return "$n $w"
}

/** Builds a reaction chip "🔥 2"; mine are highlighted. */
fun reactionChip(ctx: Context, r: JSONObject, outgoing: Boolean, onClick: () -> Unit): TextView = TextView(ctx).apply {
    val mine = r.optBoolean("mine")
    text = r.str("emoji") + " " + r.optInt("count")
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
    typeface = weightFace(600)
    gravity = Gravity.CENTER
    setPadding(ctx.dp(9), ctx.dp(3), ctx.dp(10), ctx.dp(3))
    minHeight = ctx.dp(28)
    val bgColor = when {
        mine && outgoing -> 0x55FFFFFF
        mine -> (Theme.p.accent and 0x00FFFFFF) or 0x40000000
        outgoing -> 0x26FFFFFF
        else -> (Theme.p.muted and 0x00FFFFFF) or 0x22000000
    }
    background = android.graphics.drawable.GradientDrawable().apply {
        setColor(bgColor)
        cornerRadius = ctx.dpf(14)
        if (mine) setStroke(ctx.dp(1), if (outgoing) 0xCCFFFFFF.toInt() else Theme.p.accent)
    }
    setTextColor(if (outgoing) Theme.p.onAccent else Theme.p.text)
    contentDescription = "Реакция ${r.str("emoji")}, ${r.optInt("count")}" + (if (mine) ", ваша" else "") + r.str("names").takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
    isClickable = true
    setOnClickListener { onClick() }
    setOnLongClickListener { if (r.str("names").isNotEmpty()) Toast.show(ctx as MainActivity, r.str("emoji") + " " + r.str("names")); true }
}
