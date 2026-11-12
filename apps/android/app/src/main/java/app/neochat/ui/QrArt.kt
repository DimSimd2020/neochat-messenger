package app.neochat.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * QR codes drawn the way Telegram draws them: the modules take the shape of a pattern, the three
 * finder eyes are one rounded frame each, the ink is a gradient, and the whole code arrives on a
 * wave running along its diagonal. What a person picks is saved once and used for their own code
 * and for the codes of the people they open.
 */

/** The shape of a dark module. */
enum class QrPattern(val id: String, val title: String) {
    SQUARES("squares", "Квадраты"),
    DOTS("dots", "Точки"),
    MOSAIC("mosaic", "Мозаика"),
    RHOMBUS("rhombus", "Ромбы");

    companion object {
        fun of(id: String?): QrPattern = values().firstOrNull { it.id == id } ?: DOTS
    }

    /** How round the finder eyes are, as a fraction of their side. */
    val eye: Float
        get() = when (this) {
            SQUARES -> 0.1f
            MOSAIC -> 0.22f
            RHOMBUS -> 0.26f
            DOTS -> 0.5f
        }
}

/** The ink of a code: two ends of one gradient, or the colors of the theme in use. */
class QrInk(val id: String, val title: String, val from: Int, val to: Int) {
    companion object {
        val OCEAN = QrInk("ocean", "Океан", 0xFF3B6EF5.toInt(), 0xFF7A4BE8.toInt())
        val SUNSET = QrInk("sunset", "Закат", 0xFFEF6C4D.toInt(), 0xFFCE2E63.toInt())
        val FOREST = QrInk("forest", "Лес", 0xFF159F6B.toInt(), 0xFF0F6F8C.toInt())
        val LILAC = QrInk("lilac", "Сирень", 0xFF9B4DDB.toInt(), 0xFF5C4BE0.toInt())
        val GRAPHITE = QrInk("graphite", "Графит", 0xFF1B2430.toInt(), 0xFF495869.toInt())
        val THEME = QrInk("theme", "Тема", 0, 0)
        val ALL = listOf(OCEAN, SUNSET, FOREST, LILAC, GRAPHITE, THEME)

        fun of(id: String?): QrInk = ALL.firstOrNull { it.id == id } ?: OCEAN
    }

    /**
     * Colors to draw with. A code is read from white paper, so the theme ink keeps its hues but
     * goes dark enough to hold the contrast a camera needs.
     */
    fun colors(p: Palette): IntArray = when (id) {
        THEME.id -> intArrayOf(
            blendColor(p.accent, 0xFF0E1A2E.toInt(), 0.42f),
            blendColor(p.action, 0xFF0B1424.toInt(), 0.18f),
        )
        else -> intArrayOf(from, to)
    }
}

/** The saved choice of pattern, ink and photo. */
object QrStyle {
    fun pattern(ctx: Context): QrPattern = QrPattern.of(Prefs.of(ctx).getString("qr_pattern", QrPattern.DOTS.id))

    fun ink(ctx: Context): QrInk = QrInk.of(Prefs.of(ctx).getString("qr_ink", QrInk.OCEAN.id))

    fun photo(ctx: Context): Boolean = Prefs.of(ctx).getBoolean("qr_photo", false)

    fun save(ctx: Context, pattern: QrPattern? = null, ink: QrInk? = null, photo: Boolean? = null) {
        val e = Prefs.of(ctx).edit()
        pattern?.let { e.putString("qr_pattern", it.id) }
        ink?.let { e.putString("qr_ink", it.id) }
        photo?.let { e.putBoolean("qr_photo", it) }
        e.apply()
    }
}

/**
 * One code. [setRows] lays the modules out and, unless the view is [still], runs the wave they
 * arrive on; [setHole] keeps a square in the middle empty for a photo. The quiet zone is four
 * modules wide, as the standard asks.
 */
class PrettyQrView(ctx: Context, private val still: Boolean = false) : View(ctx), Themed {
    private var rows: List<String> = emptyList()
    private var n = 0
    private var pattern = QrPattern.DOTS
    private var ink = QrInk.OCEAN
    private var hole = 0f
    private var wave = 1f
    private var arrival: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Path()
    private val ring = Path()
    private val box = RectF()
    private var shader: LinearGradient? = null
    private var shaderKey = 0

    init {
        contentDescription = if (still) null else "QR-код профиля"
        importantForAccessibility = if (still) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    fun setRows(r: List<String>) {
        rows = r
        n = r.size
        if (still) wave = 1f else replay()
        invalidate()
    }

    /** Applies a style; the modules come in again so the change is seen, not just made. */
    fun setStyle(pattern: QrPattern, ink: QrInk) {
        if (pattern == this.pattern && ink.id == this.ink.id) return
        this.pattern = pattern
        this.ink = ink
        shaderKey = 0
        if (!still) replay()
        invalidate()
    }

    /** Leaves a square of [fraction] of the side empty in the middle, where the photo sits. */
    fun setHole(fraction: Float) {
        if (fraction == hole) return
        hole = fraction
        invalidate()
    }

    /** The wave: modules start at the top left and fill the code towards the bottom right. */
    fun replay() {
        arrival?.cancel()
        if (Theme.reducedMotion || n == 0) {
            wave = 1f
            invalidate()
            return
        }
        wave = 0f
        arrival = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Theme.ms(660)
            interpolator = Theme.arriveCurve
            addUpdateListener {
                wave = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        arrival?.cancel()
        arrival = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.WHITE)
        if (n == 0) return
        val side = minOf(width, height).toFloat()
        val cell = side / (n + 2 * QUIET)
        val ox = (width - n * cell) / 2f
        val oy = (height - n * cell) / 2f
        paint.shader = ink(side)
        val halfHole = hole * side / 2f
        for (y in 0 until n) for (x in 0 until n) {
            if (rows[y][x] != '1' || inEye(x, y)) continue
            val mx = ox + x * cell + cell / 2f
            val my = oy + y * cell + cell / 2f
            if (halfHole > 0f && abs(mx - width / 2f) < halfHole && abs(my - height / 2f) < halfHole) continue
            val k = step(x + y)
            if (k <= 0f) continue
            paint.alpha = (255 * k).toInt()
            module(c, mx, my, cell, k, x + y)
        }
        eyes(c, ox, oy, cell)
    }

    private fun inEye(x: Int, y: Int) =
        (x < EYE && y < EYE) || (x >= n - EYE && y < EYE) || (x < EYE && y >= n - EYE)

    /** How far module [d] of the diagonal has arrived, 0 before its turn and 1 when it is whole. */
    private fun step(d: Int): Float {
        if (wave >= 1f) return 1f
        val start = d.toFloat() / (2 * n - 1) * TRAIL
        val t = ((wave - start) / (1f - TRAIL)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** One module at its center, grown by [k]; [d] alternates the corners of the mosaic. */
    private fun module(c: Canvas, cx: Float, cy: Float, cell: Float, k: Float, d: Int) {
        val s = cell * (0.5f + 0.5f * k)
        val half = s / 2f
        when (pattern) {
            QrPattern.SQUARES -> c.drawRect(cx - half, cy - half, cx + half, cy + half, paint)
            QrPattern.DOTS -> c.drawCircle(cx, cy, half, paint)
            QrPattern.MOSAIC -> {
                val r = if (d % 2 == 0) half * 0.95f else half * 0.28f
                c.drawRoundRect(cx - half, cy - half, cx + half, cy + half, r, r, paint)
            }
            QrPattern.RHOMBUS -> {
                val h = half * 1.18f
                shape.reset()
                shape.moveTo(cx, cy - h)
                shape.lineTo(cx + h, cy)
                shape.lineTo(cx, cy + h)
                shape.lineTo(cx - h, cy)
                shape.close()
                c.drawPath(shape, paint)
            }
        }
    }

    /**
     * The three eyes of seven modules each: a frame one module thick and a pupil of three, every
     * part rounded the way the pattern rounds a module. They grow from their own center as the
     * wave reaches them.
     */
    private fun eyes(c: Canvas, ox: Float, oy: Float, cell: Float) {
        for (i in 0 until 3) {
            val ex = if (i == 1) n - EYE else 0
            val ey = if (i == 2) n - EYE else 0
            val k = step(ex + ey + EYE)
            if (k <= 0f) continue
            paint.alpha = (255 * k).toInt()
            val x = ox + ex * cell
            val y = oy + ey * cell
            val side = EYE * cell
            ring.reset()
            eyePart(ring, x, y, side, k)
            shape.reset()
            eyePart(shape, x + cell, y + cell, side - 2 * cell, k)
            ring.op(shape, Path.Op.DIFFERENCE)
            eyePart(ring, x + 2 * cell, y + 2 * cell, side - 4 * cell, k)
            c.drawPath(ring, paint)
        }
    }

    /** One square of an eye: shrunk towards its own center by the wave, rounded by the pattern. */
    private fun eyePart(into: Path, x: Float, y: Float, side: Float, k: Float) {
        val s = side * (0.76f + 0.24f * k)
        val cx = x + side / 2f
        val cy = y + side / 2f
        box.set(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f)
        val r = (s * pattern.eye).coerceAtMost(s / 2f)
        into.addRoundRect(box, r, r, Path.Direction.CW)
    }

    private fun ink(side: Float): Shader {
        val colors = ink.colors(Theme.p)
        val key = ((width * 31 + height) * 31 + colors[0]) * 31 + colors[1]
        if (shader == null || key != shaderKey) {
            shaderKey = key
            shader = LinearGradient(0f, 0f, side, side, colors[0], colors[1], Shader.TileMode.CLAMP)
        }
        return shader!!
    }

    override fun applyTheme() {
        shaderKey = 0
        invalidate()
    }

    private companion object {
        const val QUIET = 4

        /** Share of the wave that is spent on the modules waiting for their turn. */
        const val TRAIL = 0.62f
    }
}

/**
 * The card a code is shown on: the ink of the style as a soft gradient, the code on white paper
 * and, when the style asks for it, the photo of the account in its middle. [play] runs the wave of
 * the modules and sends one highlight across the card.
 */
class QrCard(ctx: Context) : FrameLayout(ctx), Themed {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val outline = Path()
    private val tile = FrameLayout(ctx)
    private val mat = GradientDrawable().apply { setColor(Color.WHITE) }
    private val photoMat = FrameLayout(ctx)
    private val photo = AvatarView(ctx, 0)
    val qr = PrettyQrView(ctx)
    val handle = ctx.label("", 18f, 700)
    private var ink = QrInk.OCEAN
    private var sweep: ValueAnimator? = null
    private var sweepAt = -1f

    init {
        setWillNotDraw(false)
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(24), ctx.dp(24), ctx.dp(24), ctx.dp(20))
        }
        tile.background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = ctx.dpf(26) }
        tile.setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
        tile.addView(qr, LayoutParams(MATCH, WRAP))
        photoMat.background = mat
        photoMat.setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
        photoMat.addView(photo, LayoutParams(MATCH, MATCH))
        photoMat.visibility = GONE
        tile.addView(photoMat, LayoutParams(0, 0, Gravity.CENTER))
        inner.addView(tile, lp(MATCH, WRAP))
        handle.gravity = Gravity.CENTER
        handle.setTextColor(Color.WHITE)
        inner.addView(handle, lp(WRAP, WRAP).apply { topMargin = ctx.dp(16) })
        addView(inner, LayoutParams(MATCH, WRAP))
    }

    /** The code, the handle under it and the photo in its middle. */
    fun show(rows: List<String>, username: String, name: String, seed: String, avatarPath: String) {
        handle.text = if (username.isEmpty()) "" else "@$username"
        photo.set(name, seed, avatarPath)
        readStyle()
        qr.setRows(rows)
        shine()
    }

    /** Puts the saved style on the card without running the animation again. */
    fun readStyle(): QrCard {
        ink = QrStyle.ink(context)
        qr.setStyle(QrStyle.pattern(context), ink)
        photoMat.visibility = if (QrStyle.photo(context)) View.VISIBLE else View.GONE
        invalidate()
        return this
    }

    /** The wave of the modules and the highlight crossing the card. */
    fun play() {
        qr.replay()
        shine()
    }

    /** One highlight crossing the card. */
    fun shine(): QrCard {
        sweep?.cancel()
        if (Theme.reducedMotion) {
            sweepAt = -1f
            invalidate()
            return this
        }
        sweepAt = -1f
        sweep = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Theme.ms(760)
            startDelay = Theme.ms(160)
            interpolator = Theme.arriveCurve
            addUpdateListener {
                sweepAt = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        return this
    }

    /**
     * Sits the photo over the middle of the code and keeps that square of modules empty. The plate
     * is measured and placed here rather than through its layout params: the card is laid out by
     * the sheet it lives in, and a second pass asked for from inside that one comes a frame late.
     */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val side = tile.width - tile.paddingLeft - tile.paddingRight
        if (side <= 0) return
        val d = (side * PHOTO).roundToInt()
        val exact = View.MeasureSpec.makeMeasureSpec(d, View.MeasureSpec.EXACTLY)
        photoMat.measure(exact, exact)
        val cx = tile.width / 2
        val cy = tile.height / 2
        photoMat.layout(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2)
        mat.cornerRadius = d * 0.32f
        qr.setHole(if (photoMat.visibility == View.VISIBLE) PHOTO * HOLE else 0f)
    }

    override fun onDetachedFromWindow() {
        sweep?.cancel()
        sweep = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        outline.reset()
        outline.addRoundRect(0f, 0f, w, h, dpf(32), dpf(32), Path.Direction.CW)
        val colors = ink.colors(Theme.p)
        paint.shader = LinearGradient(0f, 0f, w, h, colors[0], colors[1], Shader.TileMode.CLAMP)
        c.drawPath(outline, paint)
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        val a = sweepAt
        if (a <= 0f || a >= 1f || width == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val band = w * 0.4f
        val x = a * (w + 2 * band) - band
        paint.shader = LinearGradient(
            x, 0f, x + band, h, intArrayOf(0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        c.save()
        c.clipPath(outline)
        c.drawRect(0f, 0f, w, h, paint)
        c.restore()
    }

    override fun applyTheme() = invalidate()

    private companion object {
        /** Side of the photo, as a fraction of the paper. */
        const val PHOTO = 0.22f

        /** How much wider than the photo the empty square of the code is. */
        const val HOLE = 1.25f
    }
}

/** A code-shaped grid for the previews: three eyes and modules that stand for the data. */
internal fun demoRows(n: Int = 21): List<String> {
    val rnd = java.util.Random(4242L)
    val g = Array(n) { CharArray(n) { if (rnd.nextInt(100) < 46) '1' else '0' } }
    for (i in 0 until 3) {
        val ex = if (i == 1) n - EYE else 0
        val ey = if (i == 2) n - EYE else 0
        for (y in 0 until EYE) for (x in 0 until EYE) {
            val frame = x == 0 || x == EYE - 1 || y == 0 || y == EYE - 1
            val pupil = x in 2..4 && y in 2..4
            g[ey + y][ex + x] = if (frame || pupil) '1' else '0'
        }
        for (j in 0 until EYE + 1) {
            // The separator: the row and the column of modules next to an eye stay light.
            if (ey + j < n && ex + EYE < n) g[ey + j][ex + EYE] = '0'
            if (ey + EYE < n && ex + j < n) g[ey + EYE][ex + j] = '0'
        }
    }
    return g.map { String(it) }
}

private const val EYE = 7

/** The preview of one pattern, drawn on a code-shaped grid of its own. */
private class QrPatternTile(ctx: Context, val pattern: QrPattern) : LinearLayout(ctx), Themed {
    private val code = PrettyQrView(ctx, still = true)
    private val ring = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = ctx.dpf(16)
    }

    var checked = false
        set(value) {
            field = value
            ring.setStroke(if (value) dp(2) else 0, Theme.p.accent)
            describeState(if (value) "Выбрано" else null)
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        isFocusable = true
        contentDescription = pattern.title
        tooltipText = pattern.title
        setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(2))
        background = GlassDrawable(ctx, radius = ctx.dpf(16))
        foreground = ring
        code.setRows(demoRows())
        addView(code, lp(MATCH, WRAP))
        addView(ctx.label(pattern.title, 11.5f, 600, Theme.Role.MUTED), lp().apply { topMargin = ctx.dp(4) })
        pressFeedback()
        applyTheme()
    }

    fun ink(ink: QrInk) = code.setStyle(pattern, ink)

    override fun applyTheme() {
        code.applyTheme()
        ring.setStroke(if (checked) context.dp(2) else 0, Theme.p.accent)
    }
}

/** One ink, shown as a dot of its own gradient. */
private class QrInkSwatch(ctx: Context, val ink: QrInk) : View(ctx), Themed {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    var checked = false
        set(value) {
            field = value
            describeState(if (value) "Выбрано" else null)
            invalidate()
        }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = ink.title
        tooltipText = ink.title
        pressFeedback()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dp(38), dp(38))

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = dpf(13)
        paint.style = Paint.Style.FILL
        val colors = ink.colors(Theme.p)
        paint.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, colors[0], colors[1], Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (checked || isFocused) dpf(2.5f) else dpf(1f)
        paint.color = if (checked || isFocused) Theme.p.accent else Theme.p.rim
        c.drawCircle(cx, cy, r + dpf(4), paint)
    }

    override fun applyTheme() = invalidate()
}

/** The sheet where the pattern, the ink and the photo of a code are chosen. */
object QrStyles {
    /**
     * Opens it over [host]. [rows] and the account behind them are shown live on the preview, and
     * [onChange] lets the sheet that opened this one redraw the code it shows.
     */
    fun show(
        host: Sheet.Host,
        rows: List<String>,
        username: String,
        name: String,
        seed: String,
        avatarPath: String,
        onChange: () -> Unit = {},
    ) {
        val s = Sheet(host, "Оформление QR-кода")
        val ctx = s.ctx
        val preview = QrCard(ctx)
        s.add(preview, 4)
        val tiles = QrPattern.values().map { QrPatternTile(ctx, it) }
        val swatches = QrInk.ALL.map { QrInkSwatch(ctx, it) }
        val photoRow = ToggleRow(ctx, "Фото в центре")

        /** Marks what is chosen now. */
        fun sync() {
            val p = QrStyle.pattern(ctx)
            val ink = QrStyle.ink(ctx)
            tiles.forEach { it.checked = it.pattern == p; it.ink(ink) }
            swatches.forEach { it.checked = it.ink == ink }
            photoRow.setChecked(QrStyle.photo(ctx), false)
        }

        /** Marks the choice and runs it over the preview. */
        fun refresh() {
            sync()
            preview.readStyle().play()
        }

        val patterns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        tiles.forEachIndexed { i, t ->
            patterns.addView(t, lp(0, WRAP, 1f).apply { if (i < tiles.size - 1) marginEnd = ctx.dp(8) })
            t.setOnClickListener {
                Haptics.select(t)
                QrStyle.save(ctx, pattern = t.pattern)
                refresh()
            }
        }
        val inks = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        swatches.forEachIndexed { i, sw ->
            inks.addView(sw, lp(0, WRAP, 1f).apply { if (i < swatches.size - 1) marginEnd = ctx.dp(4) })
            sw.setOnClickListener {
                Haptics.select(sw)
                QrStyle.save(ctx, ink = sw.ink)
                refresh()
            }
        }
        photoRow.onToggle = { on ->
            QrStyle.save(ctx, photo = on)
            refresh()
        }

        s.add(ctx.label("Узор", 13.5f, 600, Theme.Role.MUTED).apply { heading() }, 16)
        s.add(patterns, 6)
        s.add(ctx.label("Цвет", 13.5f, 600, Theme.Role.MUTED).apply { heading() }, 14)
        s.add(inks, 6)
        s.add(photoRow, 12)
        s.add(
            ctx.label(
                "Фото закрывает середину кода. Отсканировать его по-прежнему можно, но с длинного расстояния — хуже.",
                13f, 400, Theme.Role.MUTED
            ), 6
        )
        s.add(PillButton(ctx, "Готово").apply { setOnClickListener { s.dismiss() } }, 16)
        preview.show(rows, username, name, seed, avatarPath)
        sync()
        s.onDismiss = onChange
        s.show()
    }
}
