package app.neochat.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Variable font weights exist from Android 9; earlier versions get regular or bold. */
fun weightFace(weight: Int): Typeface =
    if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, weight, false)
    else if (weight >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

/** State descriptions for TalkBack exist from Android 11. */
fun View.describeState(text: CharSequence?) {
    if (Build.VERSION.SDK_INT >= 30) stateDescription = text
}

/** Screen coordinates of one pointer; the indexed getRawX/getRawY need Android 10. */
fun MotionEvent.rawXOf(i: Int): Float = getX(i) + (rawX - x)
fun MotionEvent.rawYOf(i: Int): Float = getY(i) + (rawY - y)

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
    typeface = weightFace(weight)
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
    /** How much of the fill and rim is drawn, for surfaces that fade in and out. */
    var level = 1f
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
        if (color != 0 && level > 0f) {
            fill.color = color
            fill.alpha = (fill.alpha * level.coerceAtMost(1f)).toInt()
            c.drawPath(path, fill)
        }
        if (pressed > 0f) {
            fill.color = if (p.dark) 0x22FFFFFF else 0x14000000
            fill.alpha = (fill.alpha * pressed).toInt()
            c.drawPath(path, fill)
        }
        if (rim && kind != Kind.ACTION && kind != Kind.OUTGOING && level > 0f) {
            stroke.color = p.rim
            stroke.alpha = (stroke.alpha * level.coerceAtMost(1f)).toInt()
            c.drawPath(path, stroke)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Press response for any tappable view: a GlassDrawable surface darkens and the view sinks
 * about 5 dp on a stiff spring, then comes back on a soft one with a small bounce. Inside
 * scrolling containers the press waits for the tap timeout, so a scroll does not flash
 * every row it starts on; a quicker tap still shows a short press.
 */
@SuppressLint("ClickableViewAccessibility")
fun View.pressFeedback() {
    var anim: ValueAnimator? = null
    var pending: Runnable? = null
    var down = false
    var touches = 0
    val slop = ViewConfiguration.get(context).scaledTouchSlop
    fun press(on: Boolean) {
        if (on == down) return
        down = on
        (background as? GlassDrawable)?.let { g ->
            anim?.cancel()
            anim = ValueAnimator.ofFloat(g.pressed, if (on) 1f else 0f).apply {
                duration = Theme.ms(if (on) 90 else 240)
                addUpdateListener { g.pressed = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        val sink = 1f - (dpf(5) / maxOf(width, height, 1)).coerceAtMost(0.1f)
        if (on) springTo(Prop.SCALE, sink, 0.2f, 1f) else springTo(Prop.SCALE, 1f, 0.42f, 0.5f)
    }
    fun cancelPending() {
        pending?.let { removeCallbacks(it) }
        pending = null
    }
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (v.isEnabled) {
                cancelPending()
                touches++
                if (v.inScrollingContainer()) {
                    val r = Runnable { pending = null; press(true) }
                    pending = r
                    v.postDelayed(r, ViewConfiguration.getTapTimeout().toLong())
                } else press(true)
            }
            MotionEvent.ACTION_MOVE -> if (e.x < -slop || e.y < -slop || e.x > v.width + slop || e.y > v.height + slop) {
                cancelPending()
                press(false)
            }
            MotionEvent.ACTION_UP -> if (pending != null) {
                cancelPending()
                press(true)
                val touch = touches
                v.postDelayed({ if (touch == touches) press(false) }, 90)
            } else press(false)
            MotionEvent.ACTION_CANCEL -> {
                cancelPending()
                press(false)
            }
        }
        false
    }
}

private fun View.inScrollingContainer(): Boolean {
    var p = parent
    while (p is ViewGroup) {
        if (p.shouldDelayChildPressedState()) return true
        p = p.parent
    }
    return false
}

/** Round icon button with a 48 dp touch target; the icon follows a palette role. */
class IconButton(ctx: Context, icon: Int, label: String, sizeDp: Int = 48, glass: Boolean = false, private var iconRole: Theme.Role = Theme.Role.TEXT) :
    ImageView(ctx), Themed {
    /** Played on every tap; chosen from the icon. */
    var effect: IconEffect? = iconEffect(icon)

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
        effect = iconEffect(icon)
    }

    override fun performClick(): Boolean {
        effect?.let { play(it) }
        return super.performClick()
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

/** Small selectable chip ("Все / Личные / Группы"); the glass fades in on a spring and the chip pops. */
class Chip(ctx: Context, text: String) : TextView(ctx), Themed {
    private val glass = GlassDrawable(ctx, GlassDrawable.Kind.GLASS, dpf(22)).apply { level = 0f }
    private val shown = Spring(0.32f, 0.9f) { glass.level = it; applyTheme() }

    init {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = weightFace(600)
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(16), 0, dp(16), 0)
        isClickable = true
        isFocusable = true
        background = glass
        pressFeedback()
        applyTheme()
    }

    override fun setSelected(selected: Boolean) {
        val changed = selected != isSelected
        super.setSelected(selected)
        // Before the first layout the chip simply starts in its state.
        if (!isLaidOut) shown.snap(if (selected) 1f else 0f)
        else if (changed) {
            shown.to(if (selected) 1f else 0f)
            if (selected) pop(0.9f, 0.36f, 0.5f)
        }
    }

    override fun performClick(): Boolean {
        Haptics.select(this)
        return super.performClick()
    }

    override fun applyTheme() {
        setTextColor(blendColor(Theme.p.muted, Theme.p.text, glass.level.coerceIn(0f, 1f)))
        invalidate()
    }
}

fun blendColor(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = (((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t)).toInt() shl s
    return ch(24) or ch(16) or ch(8) or ch(0)
}

/** Edit field whose text and hint follow the palette. */
class ThemedEdit(ctx: Context) : EditText(ctx), Themed {
    /** Pictures pasted into the field (Paste menu, keyboard); text still pastes as usual. */
    var onImages: ((List<android.net.Uri>) -> Unit)? = null

    init {
        applyTheme()
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            setOnReceiveContentListener(arrayOf("image/*", "text/*"), android.view.OnReceiveContentListener { _: View, payload: android.view.ContentInfo ->
                val clip = payload.clip
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.filter { isImage(it) }
                if (uris.isEmpty() || onImages == null) payload else { onImages?.invoke(uris); null }
            })
        }
    }

    private fun isImage(uri: android.net.Uri) = context.contentResolver.getType(uri)?.startsWith("image/") == true

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
            val clip = context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
            val uris = (0 until (clip?.itemCount ?: 0)).mapNotNull { clip!!.getItemAt(it).uri }.filter { isImage(it) }
            if (uris.isNotEmpty() && onImages != null) {
                onImages?.invoke(uris)
                return true
            }
        }
        return super.onTextContextMenuItem(id)
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
        // Containers that pass WRAP height must not shrink it to a single text line.
        minimumHeight = dp(48)
        applyTheme()
    }

    override fun applyTheme() {
        edit.setTextColor(Theme.p.text)
        edit.setHintTextColor(Theme.p.muted)
        icon.imageTintList = ColorStateList.valueOf(Theme.p.muted)
    }
}

/** Field with a floating label: inside when empty, above when focused or filled, on a spring. */
class FloatingField(ctx: Context, labelText: String, password: Boolean = false) : FrameLayout(ctx), Themed {
    val edit = EditText(ctx)
    private val labelView = ctx.label(labelText, 15f, 400, Theme.Role.MUTED)
    private var up = 0f
    private val lift = Spring(0.34f, 0.82f) { set(it) }
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
        if (target == lift.target && (lift.running || target == up)) return
        if (animated) lift.to(target) else lift.snap(target)
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
        typeface = weightFace(600)
    }
    private val r = RectF()
    private var photo: android.graphics.Bitmap? = null
    private var icon: Drawable? = null

    init {
        layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Green dot in the corner while the person is online. */
    var online = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    fun set(name: String, seed: String, avatar: String = "") {
        this.name = name
        this.seed = seed
        icon = null
        photo = if (avatar.isEmpty()) null else images.get(avatar) ?: runCatching {
            android.graphics.BitmapFactory.decodeFile(avatar)?.takeIf { it.width <= 512 && it.height <= 512 }?.also { images.put(avatar, it) }
        }.getOrNull()
        invalidate()
    }

    fun setIcon(resource: Int) {
        setIcon(context.getDrawable(resource)!!.mutate())
    }

    fun setIcon(drawable: Drawable) {
        set("", "archive")
        icon = drawable.apply { setTint(android.graphics.Color.WHITE) }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val hues = floatArrayOf(214f, 190f, 160f, 32f, 268f, 340f, 8f, 120f)
        val h = hues[abs(seed.hashCode()) % hues.size]
        val dark = Theme.p.dark
        paint.color = if (icon != null) Theme.p.action else android.graphics.Color.HSVToColor(floatArrayOf(h, if (dark) 0.42f else 0.22f, if (dark) 0.5f else 0.9f))
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        val rad = width * 18f / 48f
        c.drawRoundRect(r, rad, rad, paint)
        textPaint.color = if (dark) 0xFFF2F5FB.toInt() else 0xFF1C2638.toInt()
        textPaint.textSize = height * 0.36f
        val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        if (photo != null) {
            val image = photo!!
            val shader = android.graphics.BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = maxOf(width.toFloat() / image.width, height.toFloat() / image.height)
            shader.setLocalMatrix(android.graphics.Matrix().apply { setScale(scale, scale); postTranslate((width - image.width * scale) / 2, (height - image.height * scale) / 2) })
            paint.shader = shader
            c.drawRoundRect(r, rad, rad, paint)
            paint.shader = null
        } else if (icon != null) {
            val inset = width / 4
            icon!!.setBounds(inset, inset, width - inset, height - inset)
            icon!!.draw(c)
        } else c.drawText(initials(name), width / 2f, y, textPaint)
        if (online) {
            val d = width * 0.30f
            paint.color = Theme.p.bg0
            c.drawCircle(width - d / 2, height - d / 2, d / 2, paint)
            paint.color = 0xFF3CC56B.toInt()
            c.drawCircle(width - d / 2, height - d / 2, d / 2 - dpf(2), paint)
        }
    }

    override fun applyTheme() = invalidate()

    companion object {
        private val images = android.util.LruCache<String, android.graphics.Bitmap>(48)
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

/**
 * Settings slider: device -> day -> night. Drag, tap, D-pad, Home/End. The thumb lands on a
 * spring that keeps the speed of the throw, grows under the finger and ticks on every detent.
 */
class ThemeSlider(ctx: Context) : View(ctx), Themed {
    var value = 0
        private set
    var onChange: ((Int) -> Unit)? = null
    private var pos = 0f
    private var dragging = false
    private val slide = Spring(0.4f, 0.74f, 0.001f) { pos = it; invalidate() }
    private var grab = 0f
    private val grabSpring = Spring(0.3f, 0.7f) { grab = it; invalidate() }
    private var lastX = 0f
    private var lastT = 0L
    private var speed = 0f
    private var detent = 0
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

    fun setValue(v: Int, animated: Boolean, velocity: Float = 0f) {
        val nv = v.coerceIn(0, 2)
        val changed = nv != value
        value = nv
        describeState(names[nv])
        slide.sync(pos)
        if (animated) slide.to(nv.toFloat(), velocity) else slide.snap(nv.toFloat())
        if (changed) {
            if (animated && nv != detent) Haptics.select(this)
            detent = nv
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
            onChange?.invoke(nv)
        }
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(82))

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
        // The ends of the track stop the bounce; the middle detent keeps it.
        val cx = xFor(pos.coerceIn(0f, 2f))
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
        c.drawCircle(cx, cy, dpf(23) * (1f + 0.12f * grab), paint)
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
                lastX = e.x
                lastT = e.eventTime
                speed = 0f
                if (dragging) {
                    slide.stop()
                    grabSpring.to(1f)
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val unit = (width - trackH) / 2
                pos = ((e.x - trackH / 2) / unit).coerceIn(0f, 2f)
                val dt = (e.eventTime - lastT).coerceAtLeast(1L) / 1000f
                speed = 0.6f * speed + 0.4f * ((e.x - lastX) / unit / dt)
                lastX = e.x
                lastT = e.eventTime
                val near = pos.roundToInt()
                if (near != detent) {
                    detent = near
                    Haptics.select(this)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val thrown = dragging
                dragging = false
                grabSpring.to(0f)
                // A throw carries the thumb on toward the next detent.
                val landing = if (thrown) (pos + speed * 0.12f).roundToInt() else valueAt(e.x)
                setValue(landing, true, if (thrown) speed else 0f)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                grabSpring.to(0f)
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

/**
 * Switch row: label and a 46x26 dp toggle. The knob travels on a spring with a little
 * overshoot and stretches toward the middle while the row is held, as on iOS.
 */
class ToggleRow(ctx: Context, text: String) : LinearLayout(ctx), Themed {
    var checked = false
        private set
    var onToggle: ((Boolean) -> Unit)? = null
    private val knob = object : View(ctx) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val off = Theme.p.rim
            val on = 0xFF367FF4.toInt()
            val k = t.coerceIn(0f, 1f)
            paint.color = blendColor(off, on, k)
            c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
            paint.color = android.graphics.Color.WHITE
            val r = height / 2f - dpf(3)
            val cx = height / 2f + t * (width - height)
            val stretch = dpf(6) * held
            c.drawRoundRect(cx - r - stretch * k, height / 2f - r, cx + r + stretch * (1 - k), height / 2f + r, r, r, paint)
        }
    }
    private var t = 0f
    private val travel = Spring(0.36f, 0.68f, 0.001f) { t = it; knob.invalidate() }
    private var held = 0f
    private val hold = Spring(0.26f, 0.9f) { held = it; knob.invalidate() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        isClickable = true
        isFocusable = true
        contentDescription = text
        addView(ctx.label(text, 15.5f), LayoutParams(0, WRAP, 1f))
        addView(knob, LayoutParams(dp(46), dp(26)))
        setOnClickListener {
            setChecked(!checked, true)
            Haptics.toggle(this, checked)
            onToggle?.invoke(checked)
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        hold.to(if (pressed) 1f else 0f)
    }

    fun setChecked(on: Boolean, animated: Boolean) {
        checked = on
        describeState(if (on) "включено" else "выключено")
        if (animated) travel.to(if (on) 1f else 0f) else travel.snap(if (on) 1f else 0f)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = checked
    }

    override fun applyTheme() = knob.invalidate()
}

/** QR code from the core's module rows ('1' = dark). */
class QrView(ctx: Context) : View(ctx) {
    private var rows: List<String> = emptyList()
    private val paint = Paint()

    init {
        layoutParams = ViewGroup.LayoutParams(dp(240), dp(240))
        contentDescription = "QR-код"
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

/**
 * Telegram-like swipe-to-reply over a message row. Horizontal drag (either
 * direction) slides the bubble and reveals a reply arrow; vertical motion is
 * left to the parent so the history still scrolls. Touches that begin in the
 * system back-edge zone do not claim the inward swipe.
 */
class SwipeToReplyLayout(
    ctx: Context,
    private val canReply: () -> Boolean,
    private val onReply: () -> Unit,
) : FrameLayout(ctx), Themed {
    private val icon = ImageView(ctx).apply {
        setImageResource(app.neochat.R.drawable.ic_reply)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 0f
        scaleX = 0.6f
        scaleY = 0.6f
    }
    private var content: View? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val threshold = ctx.dpf(64)
    private val maxShift = ctx.dpf(96)
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var capturedTx = 0f
    private var tracking = false
    private var dragging = false
    private var armed = false
    private var iconOnStart = true
    private var anim: ValueAnimator? = null

    init {
        clipChildren = false
        clipToPadding = false
        isMotionEventSplittingEnabled = false
        addView(icon, LayoutParams(dp(24), dp(24), Gravity.CENTER_VERTICAL or Gravity.START).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        })
        applyTheme()
    }

    fun attach(child: View) {
        content?.let { removeView(it) }
        content = child
        addView(child, LayoutParams(MATCH, WRAP))
    }

    fun reset() {
        val running = anim
        anim = null
        running?.cancel()
        icon.animate().cancel()
        content?.translationX = 0f
        applyIcon(0f)
        dragging = false
        tracking = false
        armed = false
        pointerId = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun applyTheme() {
        icon.imageTintList = ColorStateList.valueOf(Theme.p.accent)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = e.getPointerId(0)
                downX = e.rawX
                downY = e.rawY
                dragging = false
                tracking = canReply()
                if (tracking) {
                    val running = anim
                    anim = null
                    running?.cancel()
                }
                capturedTx = content?.translationX ?: 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking || dragging) return dragging
                val idx = e.findPointerIndex(pointerId)
                if (idx < 0) return false
                val dx = e.rawXOf(idx) - downX
                val dy = e.rawYOf(idx) - downY
                if (abs(dy) > slop && abs(dy) >= abs(dx)) {
                    tracking = false
                    return false
                }
                if (abs(dx) > slop && abs(dx) > abs(dy)) {
                    if (blockedByBackEdge(dx)) {
                        tracking = false
                        return false
                    }
                    dragging = true
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                dragging = false
                pointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
        return dragging
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging && !tracking) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val idx = e.findPointerIndex(pointerId)
                if (idx < 0) return true
                val dx = e.rawXOf(idx) - downX
                if (!dragging) {
                    val dy = e.rawYOf(idx) - downY
                    if (abs(dx) > slop && abs(dx) > abs(dy) && !blockedByBackEdge(dx)) {
                        dragging = true
                        parent.requestDisallowInterceptTouchEvent(true)
                    } else if (abs(dy) > slop && abs(dy) >= abs(dx)) {
                        tracking = false
                        return false
                    }
                }
                if (dragging) applyShift(shiftFor(capturedTx + dx))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && e.getPointerId(e.actionIndex) != pointerId) return true
                val trigger = dragging && abs(content?.translationX ?: 0f) >= threshold
                parent.requestDisallowInterceptTouchEvent(false)
                settle(trigger)
            }
            MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                settle(false)
            }
        }
        return true
    }

    private fun blockedByBackEdge(dx: Float): Boolean {
        val w = resources.displayMetrics.widthPixels.toFloat()
        if (dx > 0f && downX <= edgeStart()) return true
        if (dx < 0f && downX >= w - edgeEnd()) return true
        return false
    }

    private fun edgeStart(): Float {
        val ins = rootWindowInsets ?: return dpf(24)
        return if (Build.VERSION.SDK_INT >= 30) {
            ins.getInsets(WindowInsets.Type.systemGestures()).left.toFloat().coerceAtLeast(dpf(20))
        } else {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 29) ins.systemGestureInsets.left.toFloat().coerceAtLeast(dpf(20)) else dpf(24)
        }
    }

    private fun edgeEnd(): Float {
        val ins = rootWindowInsets ?: return dpf(24)
        return if (Build.VERSION.SDK_INT >= 30) {
            ins.getInsets(WindowInsets.Type.systemGestures()).right.toFloat().coerceAtLeast(dpf(20))
        } else {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 29) ins.systemGestureInsets.right.toFloat().coerceAtLeast(dpf(20)) else dpf(24)
        }
    }

    private fun shiftFor(dx: Float): Float {
        val sign = if (dx >= 0f) 1f else -1f
        val a = abs(dx)
        val raw = if (a <= threshold) a else threshold + (a - threshold) * 0.4f
        return sign * raw.coerceAtMost(maxShift)
    }

    private fun applyShift(tx: Float) {
        content?.translationX = tx
        val nowArmed = abs(tx) >= threshold
        if (nowArmed && !armed) {
            armed = true
            replyHaptic()
        } else if (!nowArmed) {
            armed = false
        }
        applyIcon(tx)
    }

    private fun applyIcon(tx: Float) {
        val p = (abs(tx) / threshold).coerceIn(0f, 1f)
        icon.alpha = p
        val mag = 0.6f + 0.4f * p
        // Arrow points toward the bubble on the side being revealed.
        icon.scaleX = if (tx >= 0f) -mag else mag
        icon.scaleY = mag
        val start = tx >= 0f
        if (start != iconOnStart && tx != 0f) {
            iconOnStart = start
            val lp = icon.layoutParams as LayoutParams
            lp.gravity = Gravity.CENTER_VERTICAL or if (start) Gravity.START else Gravity.END
            icon.layoutParams = lp
        }
        icon.visibility = if (p == 0f) INVISIBLE else VISIBLE
    }

    private fun settle(trigger: Boolean) {
        val child = content
        dragging = false
        tracking = false
        armed = false
        pointerId = MotionEvent.INVALID_POINTER_ID
        if (child == null) return
        val start = child.translationX
        val running = anim
        anim = null
        running?.cancel()
        icon.animate().cancel()
        icon.animate().alpha(0f).setDuration(Theme.ms(120)).withEndAction { applyIcon(0f) }.start()
        if (Theme.reducedMotion || start == 0f) {
            child.translationX = 0f
            applyIcon(0f)
            if (trigger) onReply()
            return
        }
        anim = ValueAnimator.ofFloat(start, 0f).apply {
            duration = Theme.ms(280)
            interpolator = OvershootInterpolator(1.35f)
            addUpdateListener { child.translationX = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    if (anim === a) {
                        anim = null
                        child.translationX = 0f
                    }
                }
            })
            start()
        }
        if (trigger) onReply()
    }
}
