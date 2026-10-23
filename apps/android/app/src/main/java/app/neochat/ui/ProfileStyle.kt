package app.neochat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import app.neochat.core.CoreClient
import org.json.JSONObject

/** How a profile looks to others: the colour of the name and the gradient of the header. */
object ProfileStyle {
    /** Name colours on offer; an empty string is the default colour of the theme. */
    val NAME_COLORS = listOf("#ff6b6b", "#ff9f43", "#f7c948", "#32d583", "#2ec4b6", "#4dabf7", "#845ef7", "#f06595", "#adb5bd")

    /** Header gradients on offer, two colours each. A profile without its own gets one by its ID. */
    val HEADERS = listOf(
        "#2b5876,#4e4376", "#f2709c,#ff9472", "#134e5e,#71b280", "#8e2de2,#4a00e0",
        "#cb2d3e,#ef473a", "#f7971e,#ffd200", "#11998e,#38ef7d", "#232526,#414345",
    )

    fun parse(hex: String): Int? =
        if (hex.length == 7 && hex[0] == '#') runCatching { Color.parseColor(hex) }.getOrNull() else null

    fun hex(color: Int) = String.format("#%06x", color and 0xFFFFFF)

    /** [color] moved toward white or black until it can be read on [ground]. */
    fun readable(color: Int, ground: Int): Int {
        val target = if (ColorUtils.calculateLuminance(ground) < 0.5) Color.WHITE else Color.BLACK
        var c = color or 0xFF000000.toInt()
        for (step in 0..10) {
            c = ColorUtils.blendARGB(color or 0xFF000000.toInt(), target, step / 10f)
            if (ColorUtils.calculateContrast(c, ground or 0xFF000000.toInt()) >= 4.0) break
        }
        return c
    }

    /** The colour of the name on the chat background, or null for the theme's own. */
    fun nameOn(hex: String, ground: Int = Theme.p.bg1): Int? = parse(hex)?.let { readable(it, ground) }

    /** The colours of a header: the chosen ones, or a pair picked by the user's ID. */
    fun colors(header: String, userId: String): IntArray {
        val own = header.split(',').mapNotNull { parse(it.trim()) }
        if (own.isNotEmpty()) return if (own.size == 1) intArrayOf(own[0], ColorUtils.blendARGB(own[0], Color.BLACK, 0.35f)) else own.toIntArray()
        val pick = (userId.take(2).toIntOrNull(16) ?: 0) % HEADERS.size
        return HEADERS[pick].split(',').map { Color.parseColor(it) }.toIntArray()
    }
}

/** Colours a name: the person's own choice when there is one, otherwise [role]. */
fun TextView.personColor(hex: String, role: Theme.Role, ground: Int = Theme.p.bg1) {
    val c = ProfileStyle.nameOn(hex, ground)
    if (c == null) {
        role(role)
    } else {
        setTag(Theme.TAG_ROLE, null)
        setTextColor(c)
    }
}

/** The cover of a profile: a gradient with rounded bottom corners and a soft light in the corner. */
class ProfileHeaderView(ctx: Context) : View(ctx) {
    var colors: IntArray = ProfileStyle.colors("", "")
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = context.dpf(32)
        rect.set(0f, -r, w, h)
        path.rewind()
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        c.save()
        c.clipPath(path)
        paint.shader = LinearGradient(0f, 0f, w, h, colors, null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = RadialGradient(w * 0.9f, h * 0.1f, w * 0.7f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        c.restore()
    }
}

/** A round swatch: a colour, a gradient or the "default" mark; the chosen one has a ring. */
private class ColorDot(ctx: Context, description: String) : View(ctx), Themed {
    var fill: IntArray? = null
        set(v) { field = v; invalidate() }
    var mark = ""
    var checked = false
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        contentDescription = description
        isClickable = true
        isFocusable = true
        minimumWidth = ctx.dp(44)
        minimumHeight = ctx.dp(44)
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(context.dp(44), context.dp(44))

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = context.dpf(16)
        val colors = fill
        if (colors == null) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = context.dpf(1.5f)
            paint.color = Theme.p.muted
            paint.shader = null
            c.drawCircle(cx, cy, r, paint)
        } else {
            paint.style = Paint.Style.FILL
            paint.shader = if (colors.size == 1) null else LinearGradient(cx - r, cy - r, cx + r, cy + r, colors, null, Shader.TileMode.CLAMP)
            paint.color = colors[0]
            c.drawCircle(cx, cy, r, paint)
            paint.shader = null
        }
        if (mark.isNotEmpty()) {
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = context.dpf(13)
            paint.color = if (colors == null) Theme.p.muted else Color.WHITE
            paint.typeface = weightFace(700)
            c.drawText(mark, cx, cy + paint.textSize * 0.35f, paint)
        }
        if (checked) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = context.dpf(2.5f)
            paint.color = Theme.p.accent
            c.drawCircle(cx, cy, r + context.dpf(4), paint)
        }
    }

    override fun applyTheme() = invalidate()
}

/** The window where the colour of the name and the header gradient are chosen. */
fun editProfileStyle(act: MainActivity, profile: JSONObject, done: () -> Unit) {
    val s = Sheet(act, "Оформление профиля")
    val ctx = s.ctx
    val uid = profile.str("user_id")
    var nameHex = profile.str("name_color")
    val header = ArrayList<String>(profile.str("header").split(',').map { it.trim() }.filter { ProfileStyle.parse(it) != null })
    // What the picker edits: -1 nothing, 0 the name, 1..3 the header colours.
    var target = -1

    // Preview: the same cover and name as on the profile page.
    val preview = FrameLayout(ctx)
    val cover = ProfileHeaderView(ctx)
    preview.addView(cover, FrameLayout.LayoutParams(MATCH, ctx.dp(110)))
    val avatar = AvatarView(ctx, 72)
    val shown = profile.str("display_name").ifEmpty { "@" + profile.str("username") }
    avatar.set(shown, uid, profile.str("avatar_path"))
    preview.addView(avatar, FrameLayout.LayoutParams(ctx.dp(72), ctx.dp(72), Gravity.CENTER_HORIZONTAL).apply { topMargin = ctx.dp(74) })
    val name = ctx.label(shown, 22f, 700)
    name.gravity = Gravity.CENTER
    preview.addView(name, FrameLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dp(156) })
    preview.minimumHeight = ctx.dp(190)
    s.add(preview, 0)

    val picker = ColorPickerView(ctx)
    picker.visibility = View.GONE

    fun refreshPreview() {
        cover.colors = ProfileStyle.colors(header.joinToString(","), uid)
        name.personColor(nameHex, Theme.Role.TEXT)
    }

    // --- name colour ---
    s.add(ctx.label("Цвет имени", 16f, 600).apply { heading() }, 14)
    val nameRow = LinearLayout(ctx)
    s.add(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(nameRow) }, 2)

    // --- header ---
    s.add(ctx.label("Шапка", 16f, 600).apply { heading() }, 14)
    val headRow = LinearLayout(ctx)
    s.add(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(headRow) }, 2)
    val chipRow = LinearLayout(ctx)
    s.add(chipRow, 2)
    val addColor = PillButton(ctx, "Добавить цвет", primary = false)
    s.add(addColor, 4)
    s.add(picker, 8)
    s.add(ctx.label("Цвет имени виден в чатах, ответах, группах и профиле. На тёмном или светлом фоне он при необходимости подстраивается, чтобы читаться.", 13.5f, 400, Theme.Role.MUTED), 6)

    // The rows are rebuilt as a whole; a small local recursion keeps the closures simple.
    lateinit var rebuildRows: () -> Unit
    fun pickTarget(t: Int, current: Int) {
        target = t
        picker.visibility = View.VISIBLE
        picker.setColor(current)
        rebuildRows()
    }
    rebuildRows = {
        nameRow.removeAllViews()
        val none = ColorDot(ctx, "Цвет имени по умолчанию").apply { mark = "A"; checked = nameHex.isEmpty() }
        none.setOnClickListener { nameHex = ""; target = -1; picker.visibility = View.GONE; refreshPreview(); rebuildRows() }
        nameRow.addView(none)
        for (hex in ProfileStyle.NAME_COLORS) {
            val d = ColorDot(ctx, "Цвет имени $hex")
            d.fill = intArrayOf(Color.parseColor(hex))
            d.checked = nameHex == hex
            d.setOnClickListener { nameHex = hex; target = -1; picker.visibility = View.GONE; refreshPreview(); rebuildRows() }
            nameRow.addView(d)
        }
        val custom = ColorDot(ctx, "Свой цвет имени").apply {
            mark = "+"
            fill = ProfileStyle.parse(nameHex)?.takeIf { nameHex !in ProfileStyle.NAME_COLORS }?.let { intArrayOf(it) }
            checked = target == 0
        }
        custom.setOnClickListener { pickTarget(0, ProfileStyle.parse(nameHex) ?: Color.parseColor("#4dabf7")) }
        nameRow.addView(custom)

        headRow.removeAllViews()
        val own = header.joinToString(",")
        val auto = ColorDot(ctx, "Шапка по умолчанию").apply { mark = "A"; checked = header.isEmpty() }
        auto.setOnClickListener { header.clear(); target = -1; picker.visibility = View.GONE; refreshPreview(); rebuildRows() }
        headRow.addView(auto)
        for (preset in ProfileStyle.HEADERS) {
            val d = ColorDot(ctx, "Шапка $preset")
            d.fill = preset.split(',').map { Color.parseColor(it) }.toIntArray()
            d.checked = own == preset
            d.setOnClickListener {
                header.clear()
                header.addAll(preset.split(','))
                target = -1
                picker.visibility = View.GONE
                refreshPreview()
                rebuildRows()
            }
            headRow.addView(d)
        }

        chipRow.removeAllViews()
        header.forEachIndexed { i, hex ->
            val d = ColorDot(ctx, "Цвет шапки ${i + 1}")
            d.fill = intArrayOf(ProfileStyle.parse(hex) ?: Color.GRAY)
            d.mark = "${i + 1}"
            d.checked = target == i + 1
            d.setOnClickListener { pickTarget(i + 1, ProfileStyle.parse(hex) ?: Color.GRAY) }
            chipRow.addView(d)
        }
        addColor.visibility = if (header.size in 0..2) View.VISIBLE else View.GONE
        addColor.setText(if (header.isEmpty()) "Свои цвета шапки" else "Добавить цвет")
    }
    addColor.setOnClickListener {
        if (header.isEmpty()) {
            header.addAll(ProfileStyle.colors("", uid).map { ProfileStyle.hex(it) })
        } else {
            header.add(header.last())
        }
        refreshPreview()
        pickTarget(header.size, ProfileStyle.parse(header.last()) ?: Color.GRAY)
    }
    picker.onChange = { color ->
        val hex = ProfileStyle.hex(color)
        if (target == 0) nameHex = hex else if (target in 1..header.size) header[target - 1] = hex
        refreshPreview()
        // The dots only repaint; the picker keeps its place.
        if (target == 0) {
            (nameRow.getChildAt(nameRow.childCount - 1) as? ColorDot)?.fill = intArrayOf(color)
        } else {
            (chipRow.getChildAt(target - 1) as? ColorDot)?.fill = intArrayOf(color)
        }
    }

    val cancel = PillButton(ctx, "Отмена", primary = false)
    val save = PillButton(ctx, "Сохранить")
    val buttons = LinearLayout(ctx)
    buttons.addView(cancel, lp(0, WRAP, 1f).apply { marginEnd = ctx.dp(10) })
    buttons.addView(save, lp(0, WRAP, 1f))
    s.add(buttons, 16)
    cancel.setOnClickListener { s.dismiss() }
    save.setOnClickListener {
        save.isEnabled = false
        val body = JSONObject().put("name_color", nameHex).put("header", header.joinToString(","))
        CoreClient.call("set_profile", body) { _, e ->
            save.isEnabled = true
            if (e != null) act.toast(e.message) else {
                s.dismiss()
                done()
            }
        }
    }
    refreshPreview()
    rebuildRows()
    s.show()
}
