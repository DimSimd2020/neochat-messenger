package app.neochat.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max

/** "Оформление интерфейса": the built-in themes, two slots for own themes and the editor of an own theme. */
object Appearance {
    fun show(host: Sheet.Host) {
        val s = Sheet(host, "Оформление интерфейса")
        val ctx = s.ctx
        val cards = ArrayList<Pair<String, ThemeCard>>()
        fun sync() = cards.forEach { (id, card) -> card.checked = id == Theme.appearance }

        fun title(text: String) = ctx.label(text, 13.5f, 600, Theme.Role.MUTED).apply { heading() }
        fun row() = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun place(row: LinearLayout, v: View, last: Boolean) = row.addView(v, lp(0, WRAP, 1f).apply { if (!last) marginEnd = ctx.dp(12) })

        s.add(title("Готовые темы"), 0)
        val dark = Theme.modeDark(ctx)
        var line = row()
        for ((i, preset) in Theme.presets.withIndex()) {
            if (i % 2 == 0) {
                line = row()
                s.add(line, 8)
            }
            // Built-in themes follow day and night, so the card shows what the screen would use now.
            val card = ThemeCard(ctx, preset.name).apply { look = Theme.paletteFor(preset.id, dark) }
            card.setOnClickListener {
                Haptics.select(card)
                Theme.setAppearance(ctx, preset.id)
                sync()
            }
            cards.add(preset.id to card)
            place(line, card, i % 2 == 1)
        }

        s.add(title("Мои темы"), 20)
        val mine = row()
        s.add(mine, 8)
        for (slot in 0 until Theme.CUSTOM_SLOTS) {
            val custom = Theme.custom(slot)
            val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val card = ThemeCard(ctx, custom?.name ?: "Новая тема")
            if (custom != null) {
                val id = Theme.customId(slot)
                card.look = Theme.paletteFor(id, true)
                card.setOnClickListener {
                    Haptics.select(card)
                    Theme.setAppearance(ctx, id)
                    sync()
                }
                cards.add(id to card)
            } else {
                card.contentDescription = "Создать тему"
                card.setOnClickListener {
                    s.dismiss()
                    edit(host, slot)
                }
            }
            column.addView(card, lp(MATCH, ctx.dp(124)))
            if (custom != null) {
                val tools = row()
                val change = textButton(ctx, "Изменить", "Изменить тему «${custom.name}»", Theme.Role.ACCENT)
                val remove = textButton(ctx, "Удалить", "Удалить тему «${custom.name}»", Theme.Role.ACCENT)
                change.setOnClickListener {
                    s.dismiss()
                    edit(host, slot)
                }
                // The first tap only asks; the second removes, so a slip never costs a theme.
                remove.setOnClickListener {
                    if (remove.text != "Точно?") {
                        remove.text = "Точно?"
                        remove.role(Theme.Role.WARNING)
                        remove.postDelayed({
                            remove.text = "Удалить"
                            remove.role(Theme.Role.ACCENT)
                        }, 3000)
                        return@setOnClickListener
                    }
                    Theme.deleteCustom(ctx, slot)
                    s.dismiss()
                    show(host)
                }
                tools.addView(change, lp(0, WRAP, 1f))
                tools.addView(remove, lp(0, WRAP, 1f))
                column.addView(tools, lp())
            }
            place(mine, column, slot == Theme.CUSTOM_SLOTS - 1)
        }
        s.add(ctx.label("Готовые темы следуют режиму «Устройство», «День» или «Ночь» из настроек. Своя тема сама задаёт светлый или тёмный вид по цвету фона. Можно сохранить до двух своих тем.", 13.5f, 400, Theme.Role.MUTED), 14)
        sync()
        s.show()
    }

    private fun textButton(ctx: Context, text: String, description: String, role: Theme.Role): TextView = ctx.label(text, 14.5f, 600, role).apply {
        gravity = Gravity.CENTER
        minimumHeight = ctx.dp(44)
        isClickable = true
        isFocusable = true
        contentDescription = description
        pressFeedback()
    }

    private fun defaultName(slot: Int) = "Моя тема ${slot + 1}"

    private fun edit(host: Sheet.Host, slot: Int) {
        val saved = Theme.custom(slot)
        val s = Sheet(host, if (saved != null) "Изменить тему" else "Новая тема")
        val ctx = s.ctx
        // Leaving the editor by any way returns to the list.
        s.onDismiss = { show(host) }

        val name = FloatingField(ctx, "Название")
        name.edit.filters = arrayOf(InputFilter.LengthFilter(24))
        name.edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        name.setText(saved?.name ?: "")
        s.add(name, 4)

        val canvas = BackdropCanvas(ctx)
        if (saved != null) {
            canvas.base = saved.base
            canvas.spots.addAll(saved.spots)
        } else {
            // A new theme starts from what is on screen now, with its points moved inside the screen.
            canvas.base = Theme.p.bg1 or 0xFF000000.toInt()
            for (spot in Theme.p.spots) if (Color.alpha(spot.color) > 0) {
                canvas.spots.add(Spot(spot.x.coerceIn(0.05f, 0.95f), spot.y.coerceIn(0.05f, 0.95f), spot.color or 0xFF000000.toInt()))
            }
        }
        s.add(canvas, 12)

        // One chip for the base color and one per point; the picker edits the chosen one.
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val strip = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
        s.add(strip, 10)
        val picker = ColorPickerView(ctx)
        s.add(picker, 10)
        val add = PillButton(ctx, "Добавить точку", primary = false)
        val remove = PillButton(ctx, "Удалить", primary = false)
        remove.contentDescription = "Удалить точку"
        val tools = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(add, lp(0, WRAP, 3f).apply { marginEnd = ctx.dp(10) })
        tools.addView(remove, lp(0, WRAP, 2f))
        s.add(tools, 12)
        s.add(ctx.label("Нажмите на фон, чтобы поставить точку, и перетащите её. Тёмный или светлый вид интерфейса выбирается по цвету фона.", 13.5f, 400, Theme.Role.MUTED), 12)
        val cancel = PillButton(ctx, "Отмена", primary = false)
        val save = PillButton(ctx, "Сохранить")
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(cancel, lp(0, WRAP, 1f).apply { marginEnd = ctx.dp(10) })
        buttons.addView(save, lp(0, WRAP, 1f))
        s.add(buttons, 16)

        val swatches = ArrayList<SwatchChip>()
        fun rebuild() {
            chips.removeAllViews()
            swatches.clear()
            fun chip(text: String, description: String, color: Int, index: Int) {
                val c = SwatchChip(ctx, text, description)
                c.color = color
                c.checked = canvas.selected == index
                c.setOnClickListener { canvas.select(index) }
                swatches.add(c)
                chips.addView(c, lp(WRAP, WRAP).apply { marginEnd = ctx.dp(6) })
            }
            chip("Фон", "Цвет фона", canvas.base, -1)
            canvas.spots.forEachIndexed { i, spot -> chip("${i + 1}", "Точка ${i + 1}", spot.color, i) }
            add.isEnabled = canvas.spots.size < Theme.MAX_SPOTS
            remove.isEnabled = canvas.selected >= 0
            picker.setColor(if (canvas.selected < 0) canvas.base else canvas.spots[canvas.selected].color)
        }
        // Adding, removing or selecting a point rebuilds the chips; moving and recoloring only repaint.
        canvas.onSelect = { rebuild() }
        picker.onChange = { color ->
            if (canvas.selected < 0) canvas.base = color else canvas.spots[canvas.selected] = canvas.spots[canvas.selected].copy(color = color)
            canvas.invalidate()
            swatches.getOrNull(canvas.selected + 1)?.color = color
        }
        add.setOnClickListener { canvas.add(0.5f, 0.5f) }
        remove.setOnClickListener { canvas.removeSelected() }
        cancel.setOnClickListener { s.dismiss() }
        save.setOnClickListener {
            val title = name.text.trim().ifEmpty { defaultName(slot) }
            Theme.saveCustom(ctx, slot, CustomTheme(title, canvas.base, canvas.spots.toList()))
            s.dismiss()
        }
        rebuild()
        s.show()
    }
}

private fun Context.sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/** Black or white, whichever reads on [color]. */
private fun readableOn(color: Int): Int {
    val l = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
    return if (l > 0.6) 0xFF101820.toInt() else Color.WHITE
}

/** A theme in the list: its backdrop, two mock bubbles and the name. Without [look] it is an empty slot. */
private class ThemeCard(ctx: Context, private val label: String) : View(ctx), Themed {
    var look: Palette? = null
    var checked = false
        set(value) {
            field = value
            describeState(if (value) "Выбрана" else null)
            invalidate()
        }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = weightFace(600); textSize = ctx.sp(14f) }
    private val rect = RectF()
    private val clip = Path()

    init {
        isClickable = true
        isFocusable = true
        contentDescription = label
        pressFeedback()
    }

    // A bare View measures to zero height under wrap_content, so the card names its own.
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthSpec), resolveSize(dp(124), heightSpec))
    }

    override fun onDraw(c: Canvas) {
        val p = Theme.p
        val inset = dpf(2)
        rect.set(inset, inset, width - inset, height - inset)
        val radius = dpf(16)
        val look = look
        if (look == null) {
            line.color = (p.muted and 0x00FFFFFF) or 0x80000000.toInt()
            line.strokeWidth = dpf(1.5f)
            line.pathEffect = DashPathEffect(floatArrayOf(dpf(6), dpf(5)), 0f)
            c.drawRoundRect(rect, radius, radius, line)
            line.pathEffect = null
            line.color = p.accent
            line.strokeWidth = dpf(2.2f)
            line.strokeCap = Paint.Cap.ROUND
            val cx = rect.centerX()
            val cy = rect.centerY() - dpf(12)
            c.drawLine(cx - dpf(9), cy, cx + dpf(9), cy, line)
            c.drawLine(cx, cy - dpf(9), cx, cy + dpf(9), line)
            text.color = p.text
            text.textAlign = Paint.Align.CENTER
            c.drawText(label, rect.centerX(), rect.centerY() + dpf(22), text)
            return
        }
        clip.rewind()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        c.translate(rect.left, rect.top)
        Backdrop.draw(c, rect.width(), rect.height(), look)
        c.translate(-rect.left, -rect.top)
        // Two mock bubbles show how text and the accent sit on this backdrop.
        fill.color = look.incoming
        c.drawRoundRect(rect.left + dpf(12), rect.top + dpf(12), rect.left + dpf(12) + rect.width() * 0.52f, rect.top + dpf(30), dpf(9), dpf(9), fill)
        fill.color = look.outgoing
        c.drawRoundRect(rect.right - dpf(12) - rect.width() * 0.4f, rect.top + dpf(36), rect.right - dpf(12), rect.top + dpf(54), dpf(9), dpf(9), fill)
        fill.color = look.glassStrong
        c.drawRect(rect.left, rect.bottom - dpf(34), rect.right, rect.bottom, fill)
        c.restore()
        text.color = look.text
        text.textAlign = Paint.Align.LEFT
        val room = rect.width() - dpf(48)
        val shown = TextUtils.ellipsize(label, text, room, TextUtils.TruncateAt.END).toString()
        c.drawText(shown, rect.left + dpf(12), rect.bottom - dpf(17) - (text.descent() + text.ascent()) / 2, text)
        line.color = if (checked) p.accent else p.rim
        line.strokeWidth = if (checked) dpf(2.5f) else dpf(1)
        c.drawRoundRect(rect, radius, radius, line)
        if (checked) {
            val cx = rect.right - dpf(19)
            val cy = rect.bottom - dpf(17)
            fill.color = p.action
            c.drawCircle(cx, cy, dpf(11), fill)
            line.color = p.onAccent
            line.strokeWidth = dpf(2.2f)
            line.strokeCap = Paint.Cap.ROUND
            line.strokeJoin = Paint.Join.ROUND
            clip.rewind()
            clip.moveTo(cx - dpf(4.5f), cy + dpf(0.5f))
            clip.lineTo(cx - dpf(1.5f), cy + dpf(3.5f))
            clip.lineTo(cx + dpf(4.5f), cy - dpf(3.5f))
            c.drawPath(clip, line)
        }
    }

    override fun applyTheme() = invalidate()
}

/** A target of the color picker: the base color or one point. */
private class SwatchChip(ctx: Context, private val text: String, description: String) : View(ctx), Themed {
    var color = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }
    var checked = false
        set(value) {
            field = value
            describeState(if (value) "Выбрано" else null)
            invalidate()
        }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = weightFace(600); textSize = ctx.sp(14f) }
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
        contentDescription = description
        pressFeedback()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(dp(40) + label.measureText(text).toInt() + dp(16), dp(44))
    }

    override fun onDraw(c: Canvas) {
        val p = Theme.p
        rect.set(dpf(1), dpf(4), width - dpf(1), height - dpf(4))
        if (checked) {
            fill.color = p.glassStrong
            c.drawRoundRect(rect, dpf(14), dpf(14), fill)
        }
        line.color = if (checked || isFocused) p.accent else p.rim
        line.strokeWidth = if (checked || isFocused) dpf(2) else dpf(1)
        c.drawRoundRect(rect, dpf(14), dpf(14), line)
        val cy = height / 2f
        fill.color = color
        c.drawCircle(dpf(22), cy, dpf(9), fill)
        line.color = p.rim
        line.strokeWidth = dpf(1)
        c.drawCircle(dpf(22), cy, dpf(9), line)
        label.color = p.text
        c.drawText(text, dpf(38), cy - (label.descent() + label.ascent()) / 2, label)
    }

    override fun applyTheme() = invalidate()
}

/**
 * The preview of an own theme: a flat base color with its points. Touch a free place to add a
 * point and drag it; touch a point to select it. Arrow keys move the selected point.
 */
@SuppressLint("ClickableViewAccessibility")
private class BackdropCanvas(ctx: Context) : View(ctx), Themed {
    var base = 0xFF101A2E.toInt()
    val spots = ArrayList<Spot>()
    var selected = -1
        private set
    var onSelect: ((Int) -> Unit)? = null
    private var drag = -1
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val number = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = weightFace(700); textSize = ctx.sp(12f); textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val clip = Path()

    init {
        isFocusable = true
        contentDescription = "Фон: нажмите, чтобы добавить точку, перетащите, чтобы передвинуть"
    }

    fun select(i: Int) {
        selected = i
        invalidate()
        onSelect?.invoke(i)
    }

    fun add(x: Float, y: Float) {
        if (spots.size >= Theme.MAX_SPOTS) return
        spots.add(Spot(x, y, freshColor()))
        select(spots.size - 1)
    }

    fun removeSelected() {
        if (selected !in spots.indices) return
        spots.removeAt(selected)
        select(if (spots.isEmpty()) -1 else minOf(selected, spots.size - 1))
    }

    /** A color no point uses yet. */
    private fun freshColor(): Int {
        val colors = intArrayOf(0xFF6D8DFF.toInt(), 0xFFFF7A9C.toInt(), 0xFF5FD4B5.toInt(), 0xFFFFC76B.toInt(), 0xFFB48CFF.toInt())
        return colors.firstOrNull { c -> spots.none { it.color == c } } ?: colors[0]
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthSpec), dp(190))
    }

    // A point on the very edge is drawn inside the canvas, so its handle is never cut off.
    private fun xOf(s: Spot) = (s.x * width).coerceIn(dpf(17), max(dpf(17), width - dpf(17)))
    private fun yOf(s: Spot) = (s.y * height).coerceIn(dpf(17), max(dpf(17), height - dpf(17)))

    override fun onDraw(c: Canvas) {
        val p = Theme.p
        val look = Palette.themed(Theme.darkBase(base), base, base, base, spots)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.rewind()
        clip.addRoundRect(rect, dpf(18), dpf(18), Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        Backdrop.draw(c, width.toFloat(), height.toFloat(), look)
        c.restore()
        line.color = p.rim
        line.strokeWidth = dpf(1)
        c.drawRoundRect(rect.apply { inset(dpf(0.5f), dpf(0.5f)) }, dpf(18), dpf(18), line)
        for ((i, spot) in spots.withIndex()) {
            val on = i == selected
            val r = dpf(if (on) 15 else 12)
            val x = xOf(spot)
            val y = yOf(spot)
            line.color = Color.argb(80, 0, 0, 0)
            line.strokeWidth = dpf(if (on) 5.5f else 4f)
            c.drawCircle(x, y, r, line)
            line.color = Color.WHITE
            line.strokeWidth = dpf(if (on) 3.5f else 2.5f)
            c.drawCircle(x, y, r, line)
            fill.color = spot.color
            c.drawCircle(x, y, r, fill)
            number.color = readableOn(spot.color)
            c.drawText("${i + 1}", x, y - (number.descent() + number.ascent()) / 2, number)
        }
        if (isFocused) {
            line.color = p.accent
            line.strokeWidth = dpf(2)
            c.drawRoundRect(rect, dpf(18), dpf(18), line)
        }
    }

    private fun hit(x: Float, y: Float): Int {
        for (i in spots.indices.reversed()) if (hypot(x - xOf(spots[i]), y - yOf(spots[i])) <= dpf(24)) return i
        return -1
    }

    private fun moveTo(i: Int, x: Float, y: Float) {
        if (i !in spots.indices) return
        spots[i] = spots[i].copy(x = (x / max(1, width)).coerceIn(0f, 1f), y = (y / max(1, height)).coerceIn(0f, 1f))
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                requestFocus()
                drag = hit(e.x, e.y)
                if (drag >= 0) select(drag)
                else if (spots.size < Theme.MAX_SPOTS) {
                    // A new point follows the finger at once.
                    add(e.x / max(1, width), e.y / max(1, height))
                    drag = selected
                }
            }
            MotionEvent.ACTION_MOVE -> moveTo(drag, e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = -1
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        val step = if (e.isShiftPressed) 0.05f else 0.01f
        val s = spots.getOrNull(selected) ?: return super.onKeyDown(keyCode, e)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> moveTo(selected, (s.x - step) * width, s.y * height)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveTo(selected, (s.x + step) * width, s.y * height)
            KeyEvent.KEYCODE_DPAD_UP -> moveTo(selected, s.x * width, (s.y - step) * height)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveTo(selected, s.x * width, (s.y + step) * height)
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> removeSelected()
            else -> return super.onKeyDown(keyCode, e)
        }
        return true
    }

    override fun applyTheme() = invalidate()
}

/** Saturation and brightness square, hue strip and a hex field for one color. */
internal class ColorPickerView(ctx: Context) : LinearLayout(ctx) {
    var onChange: ((Int) -> Unit)? = null
    private var hue = 220f
    private var sat = 0.5f
    private var value = 0.8f
    private var syncing = false
    val color get() = Color.HSVToColor(floatArrayOf(hue, sat, value))
    private val square = SquareView(ctx)
    private val strip = HueView(ctx)
    private val hex = FloatingField(ctx, "Цвет, например #6D8DFF")

    init {
        orientation = VERTICAL
        square.contentDescription = "Насыщенность и яркость"
        strip.contentDescription = "Оттенок"
        addView(square, lp(MATCH, dp(130)))
        addView(strip, lp(MATCH, dp(32)).apply { topMargin = dp(10) })
        hex.edit.filters = arrayOf(InputFilter.LengthFilter(7))
        hex.edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        addView(hex, lp().apply { topMargin = dp(10) })
        square.onPick = { s, v ->
            sat = s
            value = v
            changed()
        }
        strip.onPick = { h ->
            hue = h
            changed()
        }
        hex.edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) {
                if (syncing) return
                val t = e.toString().trim().removePrefix("#")
                if (t.length != 6 || !t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return
                setColor(0xFF000000.toInt() or t.toInt(16), report = true, showText = false)
            }
            override fun beforeTextChanged(e: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(e: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    fun setColor(c: Int, report: Boolean = false, showText: Boolean = true) {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        // Grey and black have no hue of their own; the strip keeps where it was.
        if (hsv[1] > 0.001f && hsv[2] > 0.001f) hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
        push(showText)
        if (report) onChange?.invoke(color)
    }

    private fun changed() {
        push(true)
        onChange?.invoke(color)
    }

    private fun push(showText: Boolean) {
        square.hue = hue
        square.sat = sat
        square.value = value
        strip.hue = hue
        if (showText) {
            syncing = true
            hex.setText(String.format("#%06X", color and 0xFFFFFF))
            syncing = false
        }
    }

    private class SquareView(ctx: Context) : View(ctx) {
        var hue = 0f
            set(v) { field = v; invalidate() }
        var sat = 0f
            set(v) { field = v; invalidate() }
        var value = 0f
            set(v) { field = v; invalidate() }
        var onPick: ((Float, Float) -> Unit)? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()
        private val clip = Path()

        override fun onDraw(c: Canvas) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            clip.rewind()
            clip.addRoundRect(rect, dpf(14), dpf(14), Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, Color.WHITE, Color.HSVToColor(floatArrayOf(hue, 1f, 1f)), Shader.TileMode.CLAMP)
            c.drawRect(rect, paint)
            paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), 0x00000000, Color.BLACK, Shader.TileMode.CLAMP)
            c.drawRect(rect, paint)
            paint.shader = null
            c.restore()
            handle(c, line, paint, (sat * width).coerceIn(dpf(11), width - dpf(11)), ((1 - value) * height).coerceIn(dpf(11), height - dpf(11)), Color.HSVToColor(floatArrayOf(hue, sat, value)))
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) parent.requestDisallowInterceptTouchEvent(true)
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
                onPick?.invoke((e.x / max(1, width)).coerceIn(0f, 1f), 1f - (e.y / max(1, height)).coerceIn(0f, 1f))
            }
            return true
        }
    }

    private class HueView(ctx: Context) : View(ctx) {
        var hue = 0f
            set(v) { field = v; invalidate() }
        var onPick: ((Float) -> Unit)? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()
        private val stops = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }

        override fun onDraw(c: Canvas) {
            rect.set(0f, dpf(6), width.toFloat(), height - dpf(6))
            paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, stops, null, Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, dpf(10), dpf(10), paint)
            paint.shader = null
            handle(c, line, paint, (hue / 360f * width).coerceIn(dpf(11), width - dpf(11)), height / 2f, Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) parent.requestDisallowInterceptTouchEvent(true)
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
                onPick?.invoke((e.x / max(1, width)).coerceIn(0f, 1f) * 359.99f)
            }
            return true
        }
    }
}

private fun View.handle(c: Canvas, line: Paint, fill: Paint, x: Float, y: Float, color: Int) {
    line.color = Color.argb(80, 0, 0, 0)
    line.strokeWidth = dpf(4.5f)
    c.drawCircle(x, y, dpf(9), line)
    line.color = Color.WHITE
    line.strokeWidth = dpf(2.5f)
    c.drawCircle(x, y, dpf(9), line)
    fill.color = color
    c.drawCircle(x, y, dpf(9), fill)
}
