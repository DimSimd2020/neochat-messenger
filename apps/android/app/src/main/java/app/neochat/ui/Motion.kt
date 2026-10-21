package app.neochat.ui

import android.os.Build
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.PI
import kotlin.math.abs

/**
 * Damped spring stepped on display frames. [response] is the period of the undamped motion in
 * seconds and [damping] the damping ratio (1 settles without overshoot), as in SwiftUI.
 * A new target keeps the current velocity, so an interrupted motion never jumps.
 * Reduced motion puts the value at the target at once.
 */
class Spring(
    var response: Float = 0.4f,
    var damping: Float = 0.8f,
    /** Rest threshold in value units. */
    private val precision: Float = 0.002f,
    private val apply: (Float) -> Unit,
) : Choreographer.FrameCallback {
    var value = 0f
        private set
    var velocity = 0f
        private set
    var target = 0f
        private set
    var running = false
        private set
    private var last = 0L
    private var rest: (() -> Unit)? = null

    /** Jumps to [v] and stops. */
    fun snap(v: Float) {
        stop()
        sync(v)
        apply(v)
    }

    /** Takes [v] as the current state without moving anything. */
    fun sync(v: Float) {
        value = v
        target = v
        velocity = 0f
    }

    fun to(t: Float, velocity: Float = this.velocity, onRest: (() -> Unit)? = null) {
        if (Theme.reducedMotion) {
            snap(t)
            onRest?.invoke()
            return
        }
        target = t
        this.velocity = velocity
        rest = onRest
        if (!running) {
            running = true
            last = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun stop() {
        if (running) Choreographer.getInstance().removeFrameCallback(this)
        running = false
        rest = null
    }

    override fun doFrame(now: Long) {
        if (!running) return
        val dt = (if (last == 0L) 1f / 60 else (now - last) / 1e9f).coerceIn(0f, 1f / 20)
        last = now
        val w = 2 * PI.toFloat() / response
        val k = w * w
        val c = 2 * damping * w
        var left = dt
        while (left > 0f) {
            val h = minOf(left, 0.002f)
            velocity += (-k * (value - target) - c * velocity) * h
            value += velocity * h
            left -= h
        }
        if (abs(value - target) < precision && abs(velocity) < precision * 12) {
            value = target
            velocity = 0f
            running = false
            apply(value)
            val r = rest
            rest = null
            r?.invoke()
            return
        }
        apply(value)
        Choreographer.getInstance().postFrameCallback(this)
    }
}

/** A view property that its own [Spring] can drive. */
enum class Prop(val precision: Float) {
    SCALE(0.001f), X(0.5f), Y(0.5f), ALPHA(0.004f), ROTATION(0.1f);

    fun get(v: View) = when (this) {
        SCALE -> v.scaleX
        X -> v.translationX
        Y -> v.translationY
        ALPHA -> v.alpha
        ROTATION -> v.rotation
    }

    fun set(v: View, x: Float) {
        when (this) {
            SCALE -> { v.scaleX = x; v.scaleY = x }
            X -> v.translationX = x
            Y -> v.translationY = x
            ALPHA -> v.alpha = x.coerceIn(0f, 1f)
            ROTATION -> v.rotation = x
        }
    }
}

/** The spring of [prop] on this view; when idle it starts from the property's current value. */
fun View.spring(prop: Prop): Spring {
    @Suppress("UNCHECKED_CAST")
    val springs = getTag(app.neochat.R.id.motion_springs) as? HashMap<Prop, Spring>
        ?: HashMap<Prop, Spring>().also { setTag(app.neochat.R.id.motion_springs, it) }
    val s = springs.getOrPut(prop) { Spring(precision = prop.precision) { prop.set(this, it) } }
    if (!s.running) s.sync(prop.get(this))
    return s
}

fun View.springTo(prop: Prop, target: Float, response: Float = 0.4f, damping: Float = 0.8f, velocity: Float? = null, onRest: (() -> Unit)? = null) {
    val s = spring(prop)
    s.response = response
    s.damping = damping
    s.to(target, velocity ?: s.velocity, onRest)
}

/** Stops the springs of [props]; the properties keep their current values. */
fun View.stopSprings(vararg props: Prop) {
    for (p in props) spring(p).stop()
}

/** Grows from [from] to full size with a bounce. */
fun View.pop(from: Float = 0.5f, response: Float = 0.42f, damping: Float = 0.52f) {
    if (Theme.reducedMotion) {
        scaleX = 1f
        scaleY = 1f
        return
    }
    spring(Prop.SCALE).snap(from)
    springTo(Prop.SCALE, 1f, response, damping)
}

/** Pushes [prop] away from rest by about [amount] and lets it swing back. */
fun View.kick(prop: Prop, amount: Float, response: Float = 0.3f, damping: Float = 0.4f) {
    if (Theme.reducedMotion) return
    val s = spring(prop)
    springTo(prop, s.target, response, damping, s.velocity + amount * 2 * PI.toFloat() / response)
}

/** Symbol effects for icon buttons, played on a tap. A spin ends where the symbol looks the same. */
enum class IconEffect { WIGGLE, RING, SPIN, SPIN_FULL, NUDGE_START, NUDGE_END, NUDGE_DOWN }

fun View.play(effect: IconEffect) {
    if (Theme.reducedMotion) return
    when (effect) {
        IconEffect.WIGGLE -> kick(Prop.ROTATION, 14f, 0.3f, 0.3f)
        IconEffect.RING -> kick(Prop.ROTATION, 16f, 0.14f, 0.16f)
        IconEffect.SPIN, IconEffect.SPIN_FULL -> {
            val s = spring(Prop.ROTATION)
            val turn = if (effect == IconEffect.SPIN) 90f else 360f
            springTo(Prop.ROTATION, s.target + turn, 0.5f, 0.68f) { spring(Prop.ROTATION).snap(rotation % 360f) }
        }
        IconEffect.NUDGE_START -> kick(Prop.X, -dpf(5), 0.3f, 0.42f)
        IconEffect.NUDGE_END -> kick(Prop.X, dpf(5), 0.3f, 0.42f)
        IconEffect.NUDGE_DOWN -> kick(Prop.Y, dpf(5), 0.3f, 0.42f)
    }
}

fun iconEffect(icon: Int): IconEffect? = when (icon) {
    app.neochat.R.drawable.ic_compose, app.neochat.R.drawable.ic_search, app.neochat.R.drawable.ic_attach,
    app.neochat.R.drawable.ic_smile, app.neochat.R.drawable.ic_trash, app.neochat.R.drawable.ic_person_add -> IconEffect.WIGGLE
    app.neochat.R.drawable.ic_phone, app.neochat.R.drawable.ic_bell -> IconEffect.RING
    app.neochat.R.drawable.ic_settings, app.neochat.R.drawable.ic_close -> IconEffect.SPIN
    app.neochat.R.drawable.ic_camera_switch -> IconEffect.SPIN_FULL
    app.neochat.R.drawable.ic_back, app.neochat.R.drawable.ic_reply -> IconEffect.NUDGE_START
    app.neochat.R.drawable.ic_forward -> IconEffect.NUDGE_END
    app.neochat.R.drawable.ic_down, app.neochat.R.drawable.ic_download -> IconEffect.NUDGE_DOWN
    else -> null
}

/**
 * Haptics for moments that change something; plain taps stay silent. All of them follow the
 * system touch-feedback setting.
 */
object Haptics {
    /** A choice among several: tab, filter, detent. */
    fun select(v: View) = v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)

    fun toggle(v: View, on: Boolean) = v.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 34) (if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF)
        else HapticFeedbackConstants.CLOCK_TICK
    )

    /** Something went out: a message, a reaction. */
    fun confirm(v: View) = v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)

    /** A drag crossed the point where releasing acts. */
    fun threshold(v: View) = v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK)

    /** A light tick for small taps that dismiss or reveal. */
    fun tick(v: View) = v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}
