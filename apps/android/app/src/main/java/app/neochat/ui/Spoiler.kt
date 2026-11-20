package app.neochat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.Editable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.util.TypedValue
import android.view.Choreographer
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The cover of something hidden: a field of dust that drifts and breathes while the content is
 * closed and scatters outward as it opens. One painter serves both a blurred photo and a hidden
 * piece of text, so the two read as the same gesture.
 *
 * Everything here is presentation. The picture and the words are already on this device; the
 * cover only keeps them out of sight until the reader asks for them.
 */
object SpoilerFx {
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fields = HashMap<Long, Field>()

    private var epoch = 0L
    private var scale = 0f

    /** Dust time follows the system animator scale, so developer slow motion slows the drift too. */
    fun resetClock() {
        scale = 0f
    }

    private fun clock(): Float {
        if (scale == 0f) {
            scale = animatorScale()
            epoch = AnimationUtils.currentAnimationTimeMillis()
        }
        return (AnimationUtils.currentAnimationTimeMillis() - epoch) / 1000f / scale
    }

    /** Dust of one place: same seed, same grains, so a recycled row does not reshuffle them. */
    private class Field(seed: Int, val count: Int) {
        val x = FloatArray(count)
        val y = FloatArray(count)
        val r = FloatArray(count)
        val phase = FloatArray(count)
        val speed = FloatArray(count)
        val bright = FloatArray(count)

        init {
            for (i in 0 until count) {
                x[i] = rand(seed, i, 1)
                y[i] = rand(seed, i, 2)
                r[i] = 0.35f + 1.05f * rand(seed, i, 3)
                phase[i] = rand(seed, i, 4) * 6.283f
                speed[i] = 0.35f + 1.1f * rand(seed, i, 5)
                bright[i] = 0.35f + 0.65f * rand(seed, i, 6)
            }
        }
    }

    private fun rand(seed: Int, i: Int, salt: Int): Float {
        var h = seed * 0x9E3779B1.toInt() + i * 0x85EBCA6B.toInt() + salt * 0xC2B2AE35.toInt()
        h = h xor (h ushr 15)
        h *= 0x2545F491.toInt()
        h = h xor (h ushr 13)
        return (h.toLong() and 0xFFFFFFL).toFloat() / 0xFFFFFF.toFloat()
    }

    private fun field(seed: Int, count: Int): Field {
        if (fields.size > 96) fields.clear()
        return fields.getOrPut(seed.toLong() * 1024 + count) { Field(seed, count) }
    }

    /**
     * Draws the dust over [r]. [closed] is how hidden the content still is (1 closed, 0 open)
     * and [spread] how far the grains have flown from the middle on their way out.
     */
    fun dust(c: Canvas, r: RectF, seed: Int, closed: Float, spread: Float, d: Float, density: Float = 1f, gain: Float = 1f, ox: Float = 0.5f, oy: Float = 0.5f) {
        if (closed <= 0.002f || r.width() <= 0f || r.height() <= 0f) return
        val count = ((r.width() * r.height()) / (d * d * 260f) * density).toInt().coerceIn(12, 150)
        val f = field(seed, count)
        val t = if (Theme.reducedMotion) 0f else clock()
        halo.color = Theme.p.accent
        core.color = 0xFFFFFFFF.toInt()
        c.save()
        c.clipRect(r)
        for (i in 0 until count) {
            val flow = t * (0.008f + 0.022f * f.speed[i])
            var ux = f.x[i] + 0.018f * sin(t * (0.5f + f.speed[i]) + f.phase[i])
            var uy = (f.y[i] - flow) % 1f
            if (uy < 0f) uy += 1f
            // On the way out every grain leaves the tap point, the bright ones faster.
            val push = spread * (0.5f + 1.3f * f.bright[i])
            ux += (ux - ox) * push
            uy += (uy - oy) * push
            val px = r.left + ux * r.width()
            val py = r.top + uy * r.height()
            val twinkle = 0.55f + 0.45f * sin(t * 1.6f * f.speed[i] + f.phase[i] * 2f)
            val a = (closed * f.bright[i] * twinkle * gain).coerceIn(0f, 1f)
            val rad = f.r[i] * d * (1f + spread * 0.8f)
            halo.strokeWidth = rad * 4.2f
            halo.alpha = (a * 40).toInt().coerceIn(0, 255)
            c.drawCircle(px, py, rad * 2.1f, halo)
            core.strokeWidth = rad * 2f
            core.alpha = (a * 210).toInt().coerceIn(0, 255)
            c.drawCircle(px, py, rad, core)
        }
        c.restore()
    }

    private val sweep = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sweepKey = 0L
    private val sweepPath = Path()

    /** A soft band of light crossing the picture once while it opens. */
    fun sweep(c: Canvas, r: RectF, p: Float) {
        if (p <= 0.02f || p >= 0.98f) return
        val band = r.width() * 0.55f
        val key = (r.width().toInt().toLong() shl 32) or r.height().toInt().toLong()
        if (key != sweepKey || sweep.shader == null) {
            sweepKey = key
            sweep.shader = LinearGradient(0f, 0f, band, 0f, intArrayOf(0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        val x = r.left - band + p * (r.width() + band * 2)
        c.save()
        c.clipRect(r)
        c.translate(x, r.top)
        c.rotate(-12f, band / 2, r.height() / 2)
        sweepPath.rewind()
        sweepPath.addRect(0f, -r.height(), band, r.height() * 2, Path.Direction.CW)
        c.drawPath(sweepPath, sweep)
        c.restore()
    }

    private val plate = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val block = Path()

    /** The block a hidden piece of text sits on: frosted, dusted, with a light edge. */
    fun textCover(c: Canvas, r: RectF, radius: Float, color: Int, closed: Float, spread: Float, seed: Int, d: Float) {
        if (closed <= 0.002f) return
        c.save()
        block.rewind()
        block.addRoundRect(r, radius, radius, Path.Direction.CW)
        c.clipPath(block)
        plate.color = color
        plate.alpha = (255 * closed).toInt().coerceIn(0, 255)
        c.drawPath(block, plate)
        dust(c, r, seed, closed, spread, d, density = 1.5f, gain = 1.3f)
        edge.color = 0xFFFFFFFF.toInt()
        edge.strokeWidth = d
        edge.alpha = (46 * closed).toInt().coerceIn(0, 255)
        c.drawPath(block, edge)
        c.restore()
    }
}

/** How far the hidden parts of one message are open. Shared by its spans and its view. */
class SpoilerInk {
    var progress = 0f
        private set
    var host: View? = null
    private val spring = Spring(0.46f, 0.88f, 0.0015f) { progress = it; host?.invalidate() }

    /** Where the cover is heading: 0 closed, 1 open. */
    val target: Float get() = spring.target

    fun snap(v: Float) = spring.snap(v)

    fun to(v: Float) = spring.to(v)
}

/** A run of hidden text: invisible while closed, fading in as the cover scatters. */
class SpoilerSpan(private val ink: SpoilerInk) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {
        if (ink.progress < 1f) tp.alpha = (tp.alpha * ink.progress).toInt()
    }
}

/**
 * Message text that can carry hidden runs. The runs keep their place in the line, so wrapping
 * and selection work as usual; the cover is drawn over them and scattered on a tap.
 */
class MessageText(ctx: Context) : TextView(ctx) {
    var ink: SpoilerInk? = null
        set(v) {
            field?.host = null
            field = v
            v?.host = this
            restart()
            invalidate()
        }

    /** The colour of the block over a hidden run: the bubble it sits on, a step lighter. */
    var plate = 0xFF2A3346.toInt()

    /** The message this text belongs to: its dust keeps the same grains across rebinds. */
    var seed = 0

    private var ticking = false
    private val frames = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!ticking) return
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun restart() {
        val want = ink != null && ink!!.progress < 0.999f && isAttachedToWindow && !Theme.reducedMotion
        if (want == ticking) return
        ticking = want
        if (want) {
            SpoilerFx.resetClock()
            Choreographer.getInstance().postFrameCallback(frames)
        } else Choreographer.getInstance().removeFrameCallback(frames)
    }

    /** Called by the row when the reveal moved, so the dust starts or stops with it. */
    fun sync() = restart()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restart()
    }

    override fun onDetachedFromWindow() {
        ticking = false
        Choreographer.getInstance().removeFrameCallback(frames)
        if (ink?.host === this) ink?.host = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val ink = this.ink ?: return
        if (ink.progress >= 0.999f) return
        val s = text as? Spanned ?: return
        val spans = s.getSpans(0, s.length, SpoilerSpan::class.java)
        if (spans.isEmpty()) return
        val lay = layout ?: return
        val d = resources.displayMetrics.density
        val radius = 6f * d
        val pad = 1.5f * d
        c.save()
        c.translate(totalPaddingLeft.toFloat() - scrollX, totalPaddingTop.toFloat() - scrollY)
        for (sp in spans) {
            val a = s.getSpanStart(sp)
            val b = s.getSpanEnd(sp)
            if (a < 0 || b <= a) continue
            val first = lay.getLineForOffset(a)
            val last = lay.getLineForOffset(b - 1)
            for (line in first..last) {
                val left = if (line == first) lay.getPrimaryHorizontal(a) else lay.getLineLeft(line)
                val right = if (line == last) lay.getPrimaryHorizontal(b) else lay.getLineRight(line)
                if (right - left < d) continue
                val r = RectF(left - pad, lay.getLineTop(line) + pad, right + pad, lay.getLineBottom(line) - pad)
                SpoilerFx.textCover(c, r, radius, plate, 1f - ink.progress, ink.progress, seed * 31 + a, d)
            }
        }
        c.restore()
    }
}

/** The bubble's text field: a label that can hide runs of text. */
fun Context.messageLabel(sp: Float, weight: Int = 400, role: Theme.Role = Theme.Role.TEXT): MessageText =
    MessageText(this).styled(sp, weight, role)

/** The block over hidden text: a step away from the bubble it lies on, so it reads as a cover. */
fun coverPlate(out: Boolean): Int {
    val p = Theme.p
    val base = if (out) p.outgoing else p.incoming
    val toward = if (p.dark || out) android.graphics.Color.WHITE else 0xFF16283F.toInt()
    val t = if (out) 0.34f else if (p.dark) 0.30f else 0.28f
    fun ch(a: Int, b: Int) = (a + (b - a) * t).toInt().coerceIn(0, 255)
    return android.graphics.Color.rgb(
        ch(android.graphics.Color.red(base), android.graphics.Color.red(toward)),
        ch(android.graphics.Color.green(base), android.graphics.Color.green(toward)),
        ch(android.graphics.Color.blue(base), android.graphics.Color.blue(toward)),
    )
}

/** The marks the composer wraps a selection in; the core parses every one of them. */
enum class Mark(val title: String, val icon: Int, val open: String, val close: String = open) {
    BOLD("Жирный", app.neochat.R.drawable.ic_bold, "**"),
    ITALIC("Курсив", app.neochat.R.drawable.ic_italic, "*"),
    STRIKE("Зачёркнутый", app.neochat.R.drawable.ic_strike, "~~"),
    MONO("Моноширинный", app.neochat.R.drawable.ic_mono, "`"),
    SPOILER("Скрытый", app.neochat.R.drawable.ic_spoiler, "||"),
    BLOCK("Блок кода", app.neochat.R.drawable.ic_codeblock, "```\n", "\n```");

    companion object {
        /** Wraps the selection, or takes the marks away when they are already there. */
        fun toggle(text: Editable, from: Int, to: Int, mark: Mark): IntArray {
            val a = min(from, to)
            val b = max(from, to)
            if (a == b) return intArrayOf(a, b)
            val s = text.toString()
            val o = mark.open
            val cl = mark.close
            if (b - a >= o.length + cl.length && s.regionMatches(a, o, 0, o.length) && s.regionMatches(b - cl.length, cl, 0, cl.length)) {
                text.delete(b - cl.length, b)
                text.delete(a, a + o.length)
                return intArrayOf(a, b - o.length - cl.length)
            }
            if (a >= o.length && b + cl.length <= s.length && s.regionMatches(a - o.length, o, 0, o.length) && s.regionMatches(b, cl, 0, cl.length)) {
                text.delete(b, b + cl.length)
                text.delete(a - o.length, a)
                return intArrayOf(a - o.length, b - o.length)
            }
            text.insert(b, cl)
            text.insert(a, o)
            return intArrayOf(a + o.length, b + o.length)
        }
    }
}

/** Three steps of defocus of one poster, from unreadable to nearly sharp. */
object Blur {
    private val cache = android.util.LruCache<String, List<Bitmap>>(240)

    fun ladder(m: org.json.JSONObject): List<Bitmap>? {
        val id = m.str("id")
        cache.get(id)?.let { return it }
        val src = Photos.thumb(m) ?: return null
        val out = ArrayList<Bitmap>(3)
        for (side in intArrayOf(12, 26, 56)) out += shrink(src, side)
        cache.put(id, out)
        return out
    }

    fun forget(id: String) {
        cache.remove(id)
    }

    /** Halving on the way down keeps the blur smooth instead of speckled. */
    private fun shrink(src: Bitmap, side: Int): Bitmap {
        var cur = src
        while (max(cur.width, cur.height) / 2 >= side) {
            cur = Bitmap.createScaledBitmap(cur, max(1, cur.width / 2), max(1, cur.height / 2), true)
        }
        val k = side.toFloat() / max(1, max(cur.width, cur.height))
        if (k >= 1f) return cur
        return Bitmap.createScaledBitmap(cur, max(1, (cur.width * k).toInt()), max(1, (cur.height * k).toInt()), true)
    }
}

/** A short burst of dust over a fixed rectangle, drawn above whatever it is added to. */
class DustBurst(ctx: Context, private val box: RectF, private val seed: Int) : View(ctx) {
    private var t = 0f
    private var last = 0L
    private val frames = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            val dt = (if (last == 0L) 1f / 60 else (now - last) / 1e9f).coerceIn(0f, 1f / 20)
            last = now
            t += dt / animatorScale() / 0.42f
            if (t >= 1f) {
                (parent as? android.view.ViewGroup)?.removeView(this@DustBurst)
                return
            }
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        isClickable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(frames)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frames)
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        SpoilerFx.dust(c, box, seed, (1f - t) * 0.85f, t, resources.displayMetrics.density, density = 0.6f)
    }
}

/** How far a hidden picture is open, kept per message so a recycled row keeps the motion. */
class RevealStore {
    private val values = HashMap<String, Float>()

    fun of(id: String, closed: Boolean): Float = values[id] ?: if (closed) 0f else 1f

    fun put(id: String, v: Float) {
        values[id] = v
    }

    fun clear() = values.clear()
}

/** The label setup every text of the app shares, on any TextView subclass. */
fun <T : TextView> T.styled(sp: Float, weight: Int = 400, role: Theme.Role = Theme.Role.TEXT): T {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    typeface = weightFace(weight)
    setTag(Theme.TAG_ROLE, role)
    setTextColor(role.color())
    includeFontPadding = false
    setLineSpacing(0f, 1.12f)
    return this
}

/**
 * The selection menu of a field with the formatting marks added to the system items. The system
 * part is kept whole: cut, copy, paste and the rest behave exactly as they did.
 */
class FormatMode(private val base: android.view.ActionMode.Callback?, private val edit: android.widget.EditText) : android.view.ActionMode.Callback2() {
    private val first = 0x0F00

    override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
        val ok = base?.onCreateActionMode(mode, menu) ?: true
        for ((i, mark) in Mark.entries.withIndex()) {
            val icon = edit.context.getDrawable(mark.icon)?.mutate()
            icon?.setTint(0xFFEDEFF5.toInt())
            menu.add(android.view.Menu.NONE, first + i, 100 + i, mark.title).setIcon(icon)
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        return ok
    }

    override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean =
        base?.onPrepareActionMode(mode, menu) ?: false

    override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
        val i = item.itemId - first
        if (i in Mark.entries.indices) {
            val mark = Mark.entries[i]
            val range = Mark.toggle(edit.text, edit.selectionStart, edit.selectionEnd, mark)
            edit.setSelection(range[0], range[1])
            Haptics.select(edit)
            // The selection stays: another mark can be put on the same words.
            mode.invalidate()
            return true
        }
        return base?.onActionItemClicked(mode, item) ?: false
    }

    override fun onDestroyActionMode(mode: android.view.ActionMode) {
        base?.onDestroyActionMode(mode)
    }

    override fun onGetContentRect(mode: android.view.ActionMode, view: View, outRect: android.graphics.Rect) {
        val two = base as? android.view.ActionMode.Callback2
        if (two != null) two.onGetContentRect(mode, view, outRect) else super.onGetContentRect(mode, view, outRect)
    }
}