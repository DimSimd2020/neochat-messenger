package app.neochat.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.pow

/**
 * A soft blob of the backdrop. [x] and [y] are fractions of the screen and may lie outside it;
 * [radius] is a fraction of the longer side. Alpha 0 marks a slot that is not in use.
 */
data class Spot(val x: Float, val y: Float, val color: Int, val radius: Float = 0.7f)

/** A theme made in "Оформление интерфейса": one base color and up to [Theme.MAX_SPOTS] blobs. */
class CustomTheme(val name: String, val base: Int, val spots: List<Spot>)

/** Android liquid-glass palette (docs/design/tokens.json "androidGlass" + base roles). */
data class Palette(
    val text: Int, val muted: Int, val accent: Int, val action: Int, val onAccent: Int,
    val glass: Int, val glassStrong: Int, val rim: Int, val incoming: Int, val outgoing: Int,
    val outgoingMuted: Int, val warning: Int, val warningBg: Int, val scrim: Int,
    // background: linear gradient + soft radial spots
    val bg0: Int, val bg1: Int, val bg2: Int, val spots: List<Spot>,
    val dark: Boolean,
) {
    companion object {
        val DARK = Palette(
            text = 0xFFF2F7FF.toInt(), muted = 0xFFBBCBE3.toInt(), accent = 0xFF98B9FF.toInt(), action = 0xFF4169CF.toInt(),
            onAccent = Color.WHITE, glass = Color.argb(174, 23, 38, 62), glassStrong = Color.argb(235, 26, 42, 68),
            rim = Color.argb(56, 222, 238, 255), incoming = Color.argb(214, 28, 44, 70), outgoing = Color.argb(240, 49, 89, 184),
            outgoingMuted = 0xFFD6E3FF.toInt(), warning = 0xFFF2C989.toInt(), warningBg = Color.argb(225, 56, 47, 36),
            scrim = Color.argb(120, 0, 0, 0),
            bg0 = 0xFF12213B.toInt(), bg1 = 0xFF101A2E.toInt(), bg2 = 0xFF132C42.toInt(),
            spots = listOf(Spot(1f, 0f, 0xFF294E86.toInt()), Spot(-0.35f, 0.95f, 0xFF215868.toInt(), 0.75f)), dark = true,
        )
        val LIGHT = Palette(
            text = 0xFF182C46.toInt(), muted = 0xFF4A607C.toInt(), accent = 0xFF2456B8.toInt(), action = 0xFF315DCF.toInt(),
            onAccent = Color.WHITE, glass = Color.argb(168, 248, 252, 255), glassStrong = Color.argb(240, 250, 253, 255),
            rim = Color.argb(222, 255, 255, 255), incoming = Color.argb(199, 249, 252, 255), outgoing = Color.argb(242, 49, 89, 184),
            outgoingMuted = 0xFFD8E4FF.toInt(), warning = 0xFF805015.toInt(), warningBg = Color.argb(235, 255, 243, 221),
            scrim = Color.argb(70, 10, 20, 40),
            bg0 = 0xFFE9F0FC.toInt(), bg1 = 0xFFDEE8F5.toInt(), bg2 = 0xFFD7E8F0.toInt(),
            spots = listOf(Spot(1f, 0f, 0xFFB6CDF7.toInt()), Spot(-0.35f, 0.95f, 0xFFB6E1DD.toInt(), 0.75f)), dark = false,
        )

        private fun mix(a: Int, b: Int, t: Float): Int = Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )

        private fun withAlpha(c: Int, alpha: Int) = (c and 0x00FFFFFF) or (alpha shl 24)

        /**
         * The dark or light template with its own backdrop. The glass surfaces take the middle
         * stop lifted toward white, the way the original navy and blue-grey ones relate to theirs.
         */
        fun themed(dark: Boolean, c0: Int, c1: Int, c2: Int, spots: List<Spot>): Palette {
            fun glass(alpha: Int, white: Float) = withAlpha(mix(c1, Color.WHITE, white), alpha)
            val base = if (dark) DARK else LIGHT
            return if (dark) base.copy(
                bg0 = c0, bg1 = c1, bg2 = c2, spots = spots,
                glass = glass(174, 0.05f), glassStrong = glass(235, 0.075f), incoming = glass(214, 0.085f),
            ) else base.copy(
                bg0 = c0, bg1 = c1, bg2 = c2, spots = spots,
                glass = glass(168, 0.82f), glassStrong = glass(240, 0.88f), incoming = glass(199, 0.85f),
            )
        }

        /**
         * A slot that is unused on one side grows out of the other side's blob instead of
         * drifting through grey; blobs that exist on both sides glide to their new place.
         */
        private fun mix(a: List<Spot>, b: List<Spot>, t: Float): List<Spot> = List(max(a.size, b.size)) { i ->
            val x = a.getOrNull(i)
            val y = b.getOrNull(i)
            val from = if (x == null || Color.alpha(x.color) == 0) (y ?: x!!).let { it.copy(color = withAlpha(it.color, 0)) } else x
            val to = if (y == null || Color.alpha(y.color) == 0) from.copy(color = withAlpha(from.color, 0)) else y
            Spot(
                from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t,
                mix(from.color, to.color, t), from.radius + (to.radius - from.radius) * t,
            )
        }

        fun mix(a: Palette, b: Palette, t: Float) = Palette(
            mix(a.text, b.text, t), mix(a.muted, b.muted, t), mix(a.accent, b.accent, t), mix(a.action, b.action, t),
            mix(a.onAccent, b.onAccent, t), mix(a.glass, b.glass, t), mix(a.glassStrong, b.glassStrong, t), mix(a.rim, b.rim, t),
            mix(a.incoming, b.incoming, t), mix(a.outgoing, b.outgoing, t), mix(a.outgoingMuted, b.outgoingMuted, t),
            mix(a.warning, b.warning, t), mix(a.warningBg, b.warningBg, t), mix(a.scrim, b.scrim, t),
            mix(a.bg0, b.bg0, t), mix(a.bg1, b.bg1, t), mix(a.bg2, b.bg2, t), mix(a.spots, b.spots, t),
            if (t < 0.5f) a.dark else b.dark,
        )
    }
}

/** Views that repaint themselves from [Theme.p] when the palette changes. */
interface Themed {
    fun applyTheme()
}

/** The window backdrop: a three-stop gradient with soft radial blobs, shared by the screen and the previews. */
object Backdrop {
    private val paint = Paint()

    /** Paints into the rectangle at the origin of [c]; callers translate and clip. */
    fun draw(c: Canvas, w: Float, h: Float, p: Palette) {
        paint.shader = LinearGradient(0f, 0f, w * 0.35f, h, intArrayOf(p.bg0, p.bg1, p.bg2), floatArrayOf(0f, 0.56f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        for (s in p.spots) {
            if (Color.alpha(s.color) == 0) continue
            paint.shader = RadialGradient(s.x * w, s.y * h, max(w, h) * s.radius, s.color, s.color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, paint)
        }
        paint.shader = null
    }
}

object Theme {
    enum class Mode { SYSTEM, LIGHT, DARK }

    const val MAX_SPOTS = 5
    const val CUSTOM_SLOTS = 2

    class Preset(val id: String, val name: String)

    /** Built-in themes follow the day/night mode; a custom theme sets light or dark by its base color. */
    val presets = listOf(Preset("ocean", "Океан"), Preset("sunset", "Закат"), Preset("forest", "Лес"), Preset("lilac", "Сирень"))

    var p: Palette = Palette.DARK
        private set
    var mode = Mode.SYSTEM
        private set
    var appearance = "ocean"
        private set
    private var prefs: SharedPreferences? = null
    private var target = Palette.DARK
    private var anim: ValueAnimator? = null
    private var root: View? = null
    var onModeApplied: ((Boolean) -> Unit)? = null

    /** cubic-bezier(.22,.8,.25,1) from the motion spec. */
    val surfaceCurve = PathInterpolator(0.22f, 0.8f, 0.25f, 1f)

    /** Reduced motion: the system animator scale is off or the app setting is on. Read live. */
    var forceReduced = false
    val reducedMotion get() = forceReduced || !ValueAnimator.areAnimatorsEnabled()
    fun ms(d: Long) = if (reducedMotion) 0L else d

    fun init(ctx: Context, root: View) {
        this.root = root
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        this.prefs = prefs
        mode = Mode.entries[prefs.getInt("theme", 0).coerceIn(0, 2)]
        forceReduced = prefs.getBoolean("reduce_motion", false)
        val saved = prefs.getString("appearance", "ocean") ?: "ocean"
        appearance = if (known(saved)) saved else "ocean"
        target = resolve(ctx)
        p = target
        apply()
    }

    fun setMode(ctx: Context, m: Mode) {
        mode = m
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("theme", m.ordinal).apply()
        retarget(ctx)
    }

    /** System dark mode changed while running: animate like a manual switch. */
    fun onConfigurationChanged(ctx: Context) {
        if (mode == Mode.SYSTEM) retarget(ctx)
    }

    /** What day/night mode alone asks for, whatever the chosen theme does with it. */
    fun modeDark(ctx: Context): Boolean {
        val sysDark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return mode == Mode.DARK || (mode == Mode.SYSTEM && sysDark)
    }

    fun custom(slot: Int): CustomTheme? {
        if (slot !in 0 until CUSTOM_SLOTS) return null
        val text = prefs?.getString("appearance_${customId(slot)}", null) ?: return null
        return try {
            val o = JSONObject(text)
            val base = Color.parseColor(o.getString("base"))
            val spots = ArrayList<Spot>()
            val array = o.optJSONArray("spots") ?: JSONArray()
            for (i in 0 until minOf(array.length(), MAX_SPOTS)) {
                val s = array.getJSONObject(i)
                spots.add(Spot(s.getDouble("x").toFloat(), s.getDouble("y").toFloat(), Color.parseColor(s.getString("color"))))
            }
            CustomTheme(o.optString("name"), base, spots)
        } catch (e: Exception) {
            null
        }
    }

    fun customId(slot: Int) = "custom$slot"

    private fun known(id: String) = presets.any { it.id == id } || (0 until CUSTOM_SLOTS).any { id == customId(it) && custom(it) != null }

    fun setAppearance(ctx: Context, id: String) {
        if (!known(id) || id == appearance) return
        appearance = id
        prefs?.edit()?.putString("appearance", id)?.apply()
        retarget(ctx)
    }

    /** Stores the theme in the slot and switches to it. */
    fun saveCustom(ctx: Context, slot: Int, theme: CustomTheme) {
        if (slot !in 0 until CUSTOM_SLOTS) return
        val spots = JSONArray()
        for (s in theme.spots.take(MAX_SPOTS)) spots.put(JSONObject().put("x", s.x.toDouble()).put("y", s.y.toDouble()).put("color", hex(s.color)))
        val o = JSONObject().put("name", theme.name.take(24)).put("base", hex(theme.base)).put("spots", spots)
        appearance = customId(slot)
        prefs?.edit()?.putString("appearance_${customId(slot)}", o.toString())?.putString("appearance", appearance)?.apply()
        retarget(ctx)
    }

    fun deleteCustom(ctx: Context, slot: Int) {
        if (slot !in 0 until CUSTOM_SLOTS) return
        val edit = prefs?.edit()?.remove("appearance_${customId(slot)}")
        if (appearance == customId(slot)) {
            appearance = "ocean"
            edit?.putString("appearance", appearance)
        }
        edit?.apply()
        retarget(ctx)
    }

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    /** Whether light text reads better on [base] (WCAG contrast against the two text colors). */
    fun darkBase(base: Int): Boolean {
        fun lin(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        fun lum(c: Int) = 0.2126 * lin(Color.red(c)) + 0.7152 * lin(Color.green(c)) + 0.0722 * lin(Color.blue(c))
        val l = lum(base)
        return (lum(Palette.DARK.text) + 0.05) / (l + 0.05) >= (l + 0.05) / (lum(Palette.LIGHT.text) + 0.05)
    }

    /** Palette of an appearance as it would look now, for previews; [dark] matters for built-in themes. */
    fun paletteFor(id: String, dark: Boolean): Palette {
        for (slot in 0 until CUSTOM_SLOTS) {
            val theme = custom(slot)
            if (id == customId(slot) && theme != null) return Palette.themed(darkBase(theme.base), theme.base, theme.base, theme.base, theme.spots)
        }
        fun make(c0: Long, c1: Long, c2: Long, vararg spots: Long) = Palette.themed(
            dark, c0.toInt(), c1.toInt(), c2.toInt(),
            spots.mapIndexed { i, color -> if (i == 0) Spot(1f, 0f, color.toInt()) else Spot(-0.35f, 0.95f, color.toInt()) },
        )
        return when (id) {
            "sunset" -> if (dark) make(0xFF2A1626, 0xFF1D1021, 0xFF2B1A24, 0xFF8A3A5E, 0xFF8A5420) else make(0xFFFFF3EA, 0xFFFBE7DE, 0xFFF8E4EA, 0xFFF9B9A5, 0xFFF3C3D6)
            "forest" -> if (dark) make(0xFF10241D, 0xFF0C1814, 0xFF112A21, 0xFF1F6048, 0xFF3F6A2C) else make(0xFFEAF6EE, 0xFFDFF0E5, 0xFFD9EDE6, 0xFFAEE0C4, 0xFFD3E8A6)
            "lilac" -> if (dark) make(0xFF201B3A, 0xFF15112B, 0xFF1E1B40, 0xFF54409A, 0xFF2F5C94) else make(0xFFF1EDFC, 0xFFE7E1F7, 0xFFE1E8F9, 0xFFCDBFF6, 0xFFBBD4F6)
            else -> if (dark) Palette.DARK else Palette.LIGHT
        }
    }

    private fun resolve(ctx: Context): Palette {
        var dark = modeDark(ctx)
        val theme = (0 until CUSTOM_SLOTS).firstOrNull { appearance == customId(it) }?.let { custom(it) }
        if (theme != null) dark = darkBase(theme.base)
        return paletteFor(appearance, dark)
    }

    private fun retarget(ctx: Context) {
        val next = resolve(ctx)
        if (next == target && anim == null) return
        target = next
        anim?.cancel()
        val from = p
        val d = ms(420)
        if (d == 0L) {
            p = next
            apply()
            return
        }
        // A quick repeat continues from the colors on screen; nothing is queued.
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = d
            addUpdateListener {
                p = Palette.mix(from, next, it.animatedValue as Float)
                apply()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (anim === a) anim = null
                }
            })
            start()
        }
    }

    fun apply() {
        root?.let { walk(it) }
        onModeApplied?.invoke(p.dark)
    }

    private fun walk(v: View) {
        if (v is Themed) v.applyTheme()
        // Backgrounds are GlassDrawables that read the palette when drawn.
        v.invalidate()
        (v.getTag(TAG_ROLE) as? Role)?.let { role -> (v as? android.widget.TextView)?.setTextColor(role.color()) }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }

    enum class Role { TEXT, MUTED, ACCENT, WARNING, ON_ACCENT;
        fun color() = when (this) {
            TEXT -> p.text
            MUTED -> p.muted
            ACCENT -> p.accent
            WARNING -> p.warning
            ON_ACCENT -> p.onAccent
        }
    }

    val TAG_ROLE = app.neochat.R.id.theme_role
}
