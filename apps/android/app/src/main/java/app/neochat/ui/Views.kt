package app.neochat.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).roundToInt()
fun View.dp(v: Number): Int = context.dp(v)
fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density
fun View.dpf(v: Number): Float = context.dpf(v)

/** Screen reader heading; the flag exists from API 28. */
fun View.heading() {
    if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
}

/** String field; JSON null and missing keys read as empty. */
fun org.json.JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
fun flp(w: Int = MATCH, h: Int = WRAP, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, gravity)

fun <T : View> T.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): T {
    (layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(dp(l), dp(t), dp(r), dp(b))
    return this
}

/** Text with a palette role that follows theme changes. */
fun Context.label(s: CharSequence, sp: Float, weight: Int = 400, role: Theme.Role = Theme.Role.TEXT): TextView = TextView(this).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    typeface = Typeface.create(Typeface.DEFAULT, weight, false)
    setTag(Theme.TAG_ROLE, role)
    setTextColor(role.color())
    includeFontPadding = false
    setLineSpacing(0f, 1.12f)
}

fun TextView.role(r: Theme.Role) {
    setTag(Theme.TAG_ROLE, r)
    setTextColor(r.color())
}

/** Rounded surface whose colors come from the palette at draw time. */
class GlassDrawable(
    private val ctx: Context,
    var kind: Kind = Kind.GLASS,
    var radius: Float = ctx.dpf(25),
    var rim: Boolean = true,
) : Drawable() {
    enum class Kind { GLASS, STRONG, INCOMING, OUTGOING, ACTION, WARNING, NONE }

    /** Optional per-corner radii (tl, tr, br, bl); null = uniform. */
    var corners: FloatArray? = null
    var pressed = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1) }
    private val path = Path()
    private val rect = RectF()

    override fun draw(c: Canvas) {
        val p = Theme.p
        val color = when (kind) {
            Kind.GLASS -> p.glass
            Kind.STRONG -> p.glassStrong
            Kind.INCOMING -> p.incoming
            Kind.OUTGOING -> p.outgoing
            Kind.ACTION -> p.action
            Kind.WARNING -> p.warningBg
            Kind.NONE -> 0
        }
        rect.set(bounds)
        rect.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2)
        path.reset()
        val cr = corners
        if (cr == null) path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        else path.addRoundRect(rect, floatArrayOf(cr[0], cr[0], cr[1], cr[1], cr[2], cr[2], cr[3], cr[3]), Path.Direction.CW)
        if (color != 0) {
            fill.color = color
            c.drawPath(path, fill)
        }
        if (pressed > 0f) {
            fill.color = if (p.dark) 0x22FFFFFF else 0x14000000
            fill.alpha = (fill.alpha * pressed).toInt()
            c.drawPath(path, fill)
        }
        if (rim && kind != Kind.ACTION && kind != Kind.OUTGOING) {
            stroke.color = p.rim
            c.drawPath(path, stroke)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Short press feedback (140 ms) for any view with a GlassDrawable background. */
@SuppressLint("ClickableViewAccessibility")
fun View.pressFeedback() {
    var anim: ValueAnimator? = null
    setOnTouchListener { v, e ->
        val g = v.background as? GlassDrawable
        val to = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> 1f
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 0f
            else -> null
        }
        if (to != null && g != null) {
            anim?.cancel()
            anim = ValueAnimator.ofFloat(g.pressed, to).apply {
                duration = Theme.ms(140)
                addUpdateListener { g.pressed = it.animatedValue as Float; v.invalidate() }
                start()
            }
        }
        false
    }
}

/** Round icon button with a 48 dp touch target; the icon follows a palette role. */
class IconButton(ctx: Context, icon: Int, label: String, sizeDp: Int = 48, glass: Boolean = false, private var iconRole: Theme.Role = Theme.Role.TEXT) :
    ImageView(ctx), Themed {
    init {
        setImageResource(icon)
        contentDescription = label
        tooltipText = label
        val pad = dp((sizeDp - 24) / 2)
        setPadding(pad, pad, pad, pad)
        layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
        isClickable = true
        isFocusable = true
        if (glass) background = GlassDrawable(ctx, radius = dpf(sizeDp / 2f))
        else background = GlassDrawable(ctx, GlassDrawable.Kind.NONE, dpf(sizeDp / 2f), rim = false)
        pressFeedback()
        applyTheme()
    }

    fun setIcon(icon: Int, label: String) {
        setImageResource(icon)
        contentDescription = label
        tooltipText = label
    }

    fun setRole(r: Theme.Role) {
        iconRole = r
        applyTheme()
    }

    override fun applyTheme() {
        imageTintList = ColorStateList.valueOf(iconRole.color())
    }
}

/** Filled or glass button, 48 dp high. */
class PillButton(ctx: Context, text: String, private var primary: Boolean = true, icon: Int = 0) : LinearLayout(ctx), Themed {
    val label = ctx.label(text, 15f, 600, if (primary) Theme.Role.ON_ACCENT else Theme.Role.TEXT)
    private val iconView = ImageView(ctx)

    init {
        orientation = if (icon != 0) VERTICAL else HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = dp(48)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        isClickable = true
        isFocusable = true
        contentDescription = text
        background = GlassDrawable(ctx, if (primary) GlassDrawable.Kind.ACTION else GlassDrawable.Kind.GLASS, dpf(18))
        if (icon != 0) {
            iconView.setImageResource(icon)
            addView(iconView, LayoutParams(dp(24), dp(24)).apply { bottomMargin = dp(6) })
        }
        label.gravity = Gravity.CENTER
        addView(label, LayoutParams(WRAP, WRAP))
        pressFeedback()
        applyTheme()
    }

    fun setText(t: String) {
        label.text = t
        contentDescription = t
    }

    fun setPrimary(on: Boolean) {
        primary = on
        (background as GlassDrawable).kind = if (on) GlassDrawable.Kind.ACTION else GlassDrawable.Kind.GLASS
        label.role(if (on) Theme.Role.ON_ACCENT else Theme.Role.TEXT)
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.5f
    }

    override fun applyTheme() {
        iconView.imageTintList = ColorStateList.valueOf(Theme.p.text)
    }
}

/** Small selectable chip ("Все / Личные / Группы"). */
class Chip(ctx: Context, text: String) : TextView(ctx), Themed {
    init {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(16), 0, dp(16), 0)
        isClickable = true
        isFocusable = true
        background = GlassDrawable(ctx, GlassDrawable.Kind.NONE, dpf(22))
        applyTheme()
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        (background as? GlassDrawable)?.let {
            it.kind = if (selected) GlassDrawable.Kind.GLASS else GlassDrawable.Kind.NONE
            it.rim = selected
        }
        applyTheme()
    }

    override fun applyTheme() {
        setTextColor(if (isSelected) Theme.p.text else Theme.p.muted)
        invalidate()
    }
}

/** Edit field whose text and hint follow the palette. */
class ThemedEdit(ctx: Context) : EditText(ctx), Themed {
    init {
        applyTheme()
    }

    override fun applyTheme() {
        setTextColor(Theme.p.text)
        setHintTextColor(Theme.p.muted)
    }
}

/** Search box, 48 dp, glass. */
class SearchBox(ctx: Context, hint: String) : LinearLayout(ctx), Themed {
    val edit = EditText(ctx)
    private val icon = ImageView(ctx)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GlassDrawable(ctx, radius = dpf(24))
        setPadding(dp(16), 0, dp(12), 0)
        icon.setImageResource(app.neochat.R.drawable.ic_search)
        addView(icon, LayoutParams(dp(20), dp(20)))
        edit.hint = hint
        edit.contentDescription = hint
        edit.background = null
        edit.setSingleLine()
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        edit.setPadding(dp(12), 0, 0, 0)
        edit.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        addView(edit, LayoutParams(0, MATCH, 1f))
        layoutParams = LayoutParams(MATCH, dp(48))
        applyTheme()
    }

    override fun applyTheme() {
        edit.setTextColor(Theme.p.text)
        edit.setHintTextColor(Theme.p.muted)
        icon.imageTintList = ColorStateList.valueOf(Theme.p.muted)
    }
}

/** Field with a floating label: inside when empty, above when focused or filled (180 ms). */
class FloatingField(ctx: Context, labelText: String, password: Boolean = false) : FrameLayout(ctx), Themed {
    val edit = EditText(ctx)
    private val labelView = ctx.label(labelText, 15f, 400, Theme.Role.MUTED)
    private var up = 0f
    private var anim: ValueAnimator? = null
    val text get() = edit.text.toString()

    init {
        background = GlassDrawable(ctx, radius = dpf(18))
        minimumHeight = dp(56)
        edit.background = null
        edit.setSingleLine()
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        edit.setPadding(dp(16), dp(22), dp(16), dp(8))
        edit.contentDescription = labelText
        if (password) edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        addView(edit, LayoutParams(MATCH, dp(56)))
        labelView.pivotX = 0f
        labelView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(labelView, LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dp(16) })
        edit.setOnFocusChangeListener { _, _ -> update(true); applyTheme() }
        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = update(true)
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        setOnClickListener { edit.requestFocus() }
        applyTheme()
    }

    fun setLabel(t: String) {
        labelView.text = t
        edit.contentDescription = t
    }

    fun setText(t: String) {
        edit.setText(t)
        update(false)
    }

    private fun update(animated: Boolean) {
        val target = if (edit.hasFocus() || edit.text.isNotEmpty()) 1f else 0f
        if (target == up && anim == null) return
        anim?.cancel()
        val from = up
        val d = if (animated) Theme.ms(180) else 0
        if (d == 0L) return set(target)
        anim = ValueAnimator.ofFloat(from, target).apply {
            duration = d
            addUpdateListener { set(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    anim = null
                }
            })
            start()
        }
    }

    private fun set(t: Float) {
        up = t
        val s = 1f - 0.15f * t
        labelView.scaleX = s
        labelView.scaleY = s
        labelView.translationY = -dpf(11) * t
        applyTheme()
    }

    override fun applyTheme() {
        edit.setTextColor(Theme.p.text)
        labelView.setTextColor(if (edit.hasFocus()) Theme.p.accent else Theme.p.muted)
        (background as GlassDrawable).let { it.rim = true }
    }
}

/** Initials avatar; hue comes from the seed so one person keeps one color. */
class AvatarView(ctx: Context, private val sizeDp: Int) : View(ctx), Themed {
    private var name = ""
    private var seed = ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
    }
    private val r = RectF()

    init {
        layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun set(name: String, seed: String) {
        this.name = name
        this.seed = seed
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val hues = floatArrayOf(214f, 190f, 160f, 32f, 268f, 340f, 8f, 120f)
        val h = hues[abs(seed.hashCode()) % hues.size]
        val dark = Theme.p.dark
        paint.color = android.graphics.Color.HSVToColor(floatArrayOf(h, if (dark) 0.42f else 0.22f, if (dark) 0.5f else 0.9f))
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        val rad = width * 18f / 48f
        c.drawRoundRect(r, rad, rad, paint)
        textPaint.color = if (dark) 0xFFF2F5FB.toInt() else 0xFF1C2638.toInt()
        textPaint.textSize = height * 0.36f
        val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        c.drawText(initials(name), width / 2f, y, textPaint)
    }

    override fun applyTheme() = invalidate()

    companion object {
        fun initials(name: String): String {
            val out = StringBuilder()
            for (part in name.split(' ')) {
                val ch = part.firstOrNull { it.isLetterOrDigit() } ?: continue
                out.append(ch.uppercaseChar())
                if (out.length == 2) break
            }
            return out.ifEmpty { "?" }.toString()
        }
    }
}

/** Static background: linear gradient with two soft radial spots (no motion). */
class BackgroundView(ctx: Context) : View(ctx), Themed {
    private val paint = Paint()

    override fun onDraw(c: Canvas) {
        val p = Theme.p
        val w = width.toFloat()
        val h = height.toFloat()
        paint.shader = LinearGradient(0f, 0f, w * 0.35f, h, intArrayOf(p.bg0, p.bg1, p.bg2), floatArrayOf(0f, 0.56f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = RadialGradient(w, 0f, maxOf(w, h) * 0.7f, intArrayOf(p.spotTop, p.spotTop and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = RadialGradient(-w * 0.35f, h * 0.95f, maxOf(w, h) * 0.75f, intArrayOf(p.spotBottom, p.spotBottom and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
    }

    override fun applyTheme() = invalidate()
}

/** Settings slider: device -> day -> night. Drag, tap, D-pad, Home/End; 220 ms thumb. */
class ThemeSlider(ctx: Context) : View(ctx), Themed {
    var value = 0
        private set
    var onChange: ((Int) -> Unit)? = null
    private var pos = 0f
    private var dragging = false
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.dpf(13) }
    private val icons = intArrayOf(app.neochat.R.drawable.ic_theme_device, app.neochat.R.drawable.ic_sun, app.neochat.R.drawable.ic_moon)
    private val names = arrayOf("Устройство", "День", "Ночь")
    private val drawables = icons.map { ctx.getDrawable(it)!!.mutate() }
    private val trackH = ctx.dpf(48)

    init {
        isFocusable = true
        isClickable = true
        layoutParams = ViewGroup.LayoutParams(MATCH, dp(82))
        contentDescription = "Тема"
    }

    fun setValue(v: Int, animated: Boolean) {
        val nv = v.coerceIn(0, 2)
        val changed = nv != value
        value = nv
        stateDescription = names[nv]
        anim?.cancel()
        val d = if (animated) Theme.ms(220) else 0L
        if (d == 0L) {
            pos = nv.toFloat()
            invalidate()
        } else {
            anim = ValueAnimator.ofFloat(pos, nv.toFloat()).apply {
                duration = d
                interpolator = Theme.surfaceCurve
                addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        if (changed) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
            onChange?.invoke(nv)
        }
    }

    private fun xFor(p: Float) = trackH / 2 + p * (width - trackH) / 2
    private fun valueAt(x: Float) = ((x - trackH / 2) / ((width - trackH) / 2)).roundToInt().coerceIn(0, 2)

    override fun onDraw(c: Canvas) {
        val p = Theme.p
        val cy = trackH / 2
        paint.color = p.glass
        c.drawRoundRect(0f, 0f, width.toFloat(), trackH, trackH / 2, trackH / 2, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dpf(1)
        paint.color = p.rim
        c.drawRoundRect(0f, 0f, width.toFloat(), trackH, trackH / 2, trackH / 2, paint)
        paint.style = Paint.Style.FILL
        val cx = xFor(pos)
        // The fill continues under the whole thumb so no gap shows between roundings.
        paint.color = 0xFF367FF4.toInt()
        c.drawRoundRect(0f, 0f, cx + trackH / 2, trackH, trackH / 2, trackH / 2, paint)
        val isz = dp(22)
        for (i in 0..2) {
            val x = xFor(i.toFloat())
            val d = drawables[i]
            d.setTint(if (x <= cx + 1) android.graphics.Color.WHITE else p.muted)
            d.setBounds((x - isz / 2).toInt(), (cy - isz / 2).toInt(), (x + isz / 2).toInt(), (cy + isz / 2).toInt())
            d.draw(c)
        }
        paint.color = android.graphics.Color.WHITE
        c.drawCircle(cx, cy, dpf(23), paint)
        val sel = drawables[pos.roundToInt().coerceIn(0, 2)]
        sel.setTint(0xFF367FF4.toInt())
        sel.setBounds((cx - isz / 2).toInt(), (cy - isz / 2).toInt(), (cx + isz / 2).toInt(), (cy + isz / 2).toInt())
        sel.draw(c)
        for (i in 0..2) {
            textPaint.color = if (i == value) p.text else p.muted
            val tw = textPaint.measureText(names[i])
            val x = when (i) {
                0 -> 0f
                2 -> width - tw
                else -> xFor(1f) - tw / 2
            }
            c.drawText(names[i], x, trackH + dpf(24), textPaint)
        }
        if (isFocused) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dpf(2)
            paint.color = p.accent
            c.drawRoundRect(dpf(1), dpf(1), width - dpf(1), trackH - dpf(1), trackH / 2, trackH / 2, paint)
            paint.style = Paint.Style.FILL
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                dragging = abs(e.x - xFor(pos)) < trackH
                if (dragging) anim?.cancel()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                pos = ((e.x - trackH / 2) / ((width - trackH) / 2)).coerceIn(0f, 2f)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                setValue(valueAt(e.x), true)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                setValue(value, true)
            }
        }
        return true
    }

    override fun performClick() = super.performClick()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> setValue(value - 1, true)
            KeyEvent.KEYCODE_DPAD_RIGHT -> setValue(value + 1, true)
            KeyEvent.KEYCODE_MOVE_HOME -> setValue(0, true)
            KeyEvent.KEYCODE_MOVE_END -> setValue(2, true)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0f, 2f, value.toFloat())
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, args: android.os.Bundle?): Boolean {
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> setValue(value + 1, true)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> setValue(value - 1, true)
            else -> return super.performAccessibilityAction(action, args)
        }
        return true
    }

    override fun applyTheme() = invalidate()
}

/** Switch row: label and a 46x26 dp toggle animating over 220 ms. */
class ToggleRow(ctx: Context, text: String) : LinearLayout(ctx), Themed {
    var checked = false
        private set
    var onToggle: ((Boolean) -> Unit)? = null
    private val knob = object : View(ctx) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val off = Theme.p.rim
            val on = 0xFF367FF4.toInt()
            paint.color = if (t >= 1f) on else if (t <= 0f) off else blend(off, on, t)
            c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
            paint.color = android.graphics.Color.WHITE
            c.drawCircle(height / 2f + t * (width - height), height / 2f, height / 2f - dpf(3), paint)
        }
    }
    private var t = 0f
    private var anim: ValueAnimator? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        isClickable = true
        isFocusable = true
        contentDescription = text
        addView(ctx.label(text, 15.5f), LayoutParams(0, WRAP, 1f))
        addView(knob, LayoutParams(dp(46), dp(26)))
        setOnClickListener { setChecked(!checked, true); onToggle?.invoke(checked) }
    }

    fun setChecked(on: Boolean, animated: Boolean) {
        checked = on
        stateDescription = if (on) "включено" else "выключено"
        anim?.cancel()
        val d = if (animated) Theme.ms(220) else 0L
        if (d == 0L) {
            t = if (on) 1f else 0f
            knob.invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(t, if (on) 1f else 0f).apply {
            duration = d
            interpolator = Theme.surfaceCurve
            addUpdateListener { t = it.animatedValue as Float; knob.invalidate() }
            start()
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = checked
    }

    override fun applyTheme() = knob.invalidate()

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t)).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }
}

/** QR code from the core's module rows ('1' = dark). */
class QrView(ctx: Context) : View(ctx) {
    private var rows: List<String> = emptyList()
    private val paint = Paint()

    init {
        layoutParams = ViewGroup.LayoutParams(dp(240), dp(240))
        contentDescription = "QR-код привязки"
    }

    fun setRows(r: List<String>) {
        rows = r
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        paint.color = android.graphics.Color.WHITE
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dpf(18), dpf(18), paint)
        if (rows.isEmpty()) return
        val n = rows.size
        val quiet = 4
        val cell = (width - dpf(16)) / (n + 2 * quiet)
        val off = dpf(8) + quiet * cell
        paint.color = android.graphics.Color.BLACK
        for (y in 0 until n) for (x in rows[y].indices) if (rows[y][x] == '1')
            c.drawRect(off + x * cell, off + y * cell, off + (x + 1) * cell + 0.5f, off + (y + 1) * cell + 0.5f, paint)
    }
}
