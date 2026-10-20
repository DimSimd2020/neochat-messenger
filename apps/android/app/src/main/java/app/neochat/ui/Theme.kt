package app.neochat.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/** Android liquid-glass palette (docs/design/tokens.json "androidGlass" + base roles). */
data class Palette(
    val text: Int, val muted: Int, val accent: Int, val action: Int, val onAccent: Int,
    val glass: Int, val glassStrong: Int, val rim: Int, val incoming: Int, val outgoing: Int,
    val outgoingMuted: Int, val warning: Int, val warningBg: Int, val scrim: Int,
    // background: linear gradient + two soft radial spots
    val bg0: Int, val bg1: Int, val bg2: Int, val spotTop: Int, val spotBottom: Int,
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
            spotTop = 0xFF294E86.toInt(), spotBottom = 0xFF215868.toInt(), dark = true,
        )
        val LIGHT = Palette(
            text = 0xFF182C46.toInt(), muted = 0xFF4A607C.toInt(), accent = 0xFF2456B8.toInt(), action = 0xFF315DCF.toInt(),
            onAccent = Color.WHITE, glass = Color.argb(168, 248, 252, 255), glassStrong = Color.argb(240, 250, 253, 255),
            rim = Color.argb(222, 255, 255, 255), incoming = Color.argb(199, 249, 252, 255), outgoing = Color.argb(242, 49, 89, 184),
            outgoingMuted = 0xFFD8E4FF.toInt(), warning = 0xFF805015.toInt(), warningBg = Color.argb(235, 255, 243, 221),
            scrim = Color.argb(70, 10, 20, 40),
            bg0 = 0xFFE9F0FC.toInt(), bg1 = 0xFFDEE8F5.toInt(), bg2 = 0xFFD7E8F0.toInt(),
            spotTop = 0xFFB6CDF7.toInt(), spotBottom = 0xFFB6E1DD.toInt(), dark = false,
        )

        private fun mix(a: Int, b: Int, t: Float): Int = Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )

        fun mix(a: Palette, b: Palette, t: Float) = Palette(
            mix(a.text, b.text, t), mix(a.muted, b.muted, t), mix(a.accent, b.accent, t), mix(a.action, b.action, t),
            mix(a.onAccent, b.onAccent, t), mix(a.glass, b.glass, t), mix(a.glassStrong, b.glassStrong, t), mix(a.rim, b.rim, t),
            mix(a.incoming, b.incoming, t), mix(a.outgoing, b.outgoing, t), mix(a.outgoingMuted, b.outgoingMuted, t),
            mix(a.warning, b.warning, t), mix(a.warningBg, b.warningBg, t), mix(a.scrim, b.scrim, t),
            mix(a.bg0, b.bg0, t), mix(a.bg1, b.bg1, t), mix(a.bg2, b.bg2, t), mix(a.spotTop, b.spotTop, t),
            mix(a.spotBottom, b.spotBottom, t), if (t < 0.5f) a.dark else b.dark,
        )
    }
}

/** Views that repaint themselves from [Theme.p] when the palette changes. */
interface Themed {
    fun applyTheme()
}

object Theme {
    enum class Mode { SYSTEM, LIGHT, DARK }

    var p: Palette = Palette.DARK
        private set
    var mode = Mode.SYSTEM
        private set
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
        mode = Mode.entries[ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("theme", 0).coerceIn(0, 2)]
        forceReduced = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("reduce_motion", false)
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

    private fun resolve(ctx: Context): Palette {
        val sysDark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = mode == Mode.DARK || (mode == Mode.SYSTEM && sysDark)
        return if (dark) Palette.DARK else Palette.LIGHT
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
