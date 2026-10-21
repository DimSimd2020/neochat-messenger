package app.neochat.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.neochat.R

/**
 * Modal surface over the screen: scrim fades over 220 ms, the panel fades over 180 ms
 * and rises 28 dp over 220 ms without scaling. Back and a tap outside close it.
 */
open class Sheet(protected val host: Host, title: String) {
    /** Where overlays live and how the activity reports back presses. */
    interface Host {
        val overlay: FrameLayout
        val bottomInset: Int
        val topInset: Int
        fun pushBack(handler: () -> Boolean)
        /** Called whenever the system bars or the keyboard change size. */
        fun addInsetListener(l: () -> Unit)
        fun removeInsetListener(l: () -> Unit)
        fun popBack(handler: () -> Boolean)
    }

    val ctx: Context = host.overlay.context
    private val scrim = View(ctx)
    val panel = LinearLayout(ctx)
    val body = LinearLayout(ctx)
    val titleView = ctx.label(title, 18f, 600)
    private val scroll = ScrollView(ctx)
    private var closing = false
    private var anim: AnimatorSet? = null
    private val back: () -> Boolean = { dismiss(); true }
    // The panel stays above the keyboard: a focused field is never covered.
    private val insets: () -> Unit = { fit() }
    var onDismiss: (() -> Unit)? = null

    init {
        scrim.background = ColorDrawable(Theme.p.scrim)
        scrim.setOnClickListener { dismiss() }
        scrim.contentDescription = "Закрыть"
        panel.orientation = LinearLayout.VERTICAL
        panel.background = GlassDrawable(ctx, GlassDrawable.Kind.STRONG, ctx.dpf(30)).apply {
            corners = floatArrayOf(ctx.dpf(30), ctx.dpf(30), ctx.dpf(30), ctx.dpf(30))
        }
        panel.isClickable = true
        panel.setPadding(ctx.dp(20), ctx.dp(10), ctx.dp(20), ctx.dp(16))
        val head = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(titleView, lp(0, WRAP, 1f))
            addView(IconButton(ctx, R.drawable.ic_close, "Закрыть").apply { setOnClickListener { dismiss() } })
        }
        titleView.isFocusable = true
        titleView.heading()
        panel.addView(head, lp())
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(0, ctx.dp(6), 0, ctx.dp(4))
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        panel.addView(scroll, lp())
    }

    fun setTitle(t: String) {
        titleView.text = t
    }

    fun add(v: View, topDp: Int = 12): View {
        body.addView(v, lp().apply { topMargin = ctx.dp(if (body.childCount == 0) 0 else topDp) })
        return v
    }

    fun show(): Sheet {
        val ov = host.overlay
        ov.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        ov.addView(panel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
            setMargins(ctx.dp(8), 0, ctx.dp(8), ctx.dp(8) + host.bottomInset)
        })
        panel.post { fit() }
        host.addInsetListener(insets)
        host.pushBack(back)
        scrim.alpha = 0f
        panel.alpha = 0f
        panel.translationY = ctx.dpf(28)
        run(true)
        titleView.post { titleView.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED) }
        return this
    }

    private fun fit() {
        val ov = host.overlay
        val lp = panel.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.bottomMargin = ctx.dp(8) + host.bottomInset
        val room = ov.height - host.bottomInset - maxOf(host.topInset, ctx.dp(24)) - ctx.dp(16)
        val maxH = minOf((ov.height * 0.88f).toInt(), room)
        // Measure the natural height again; the scroll view takes what does not fit.
        lp.height = WRAP
        panel.measure(View.MeasureSpec.makeMeasureSpec(ov.width - ctx.dp(16), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        if (maxH > 0 && panel.measuredHeight > maxH) lp.height = maxH
        panel.layoutParams = lp
        panel.findFocus()?.let { f -> panel.post { f.requestRectangleOnScreen(android.graphics.Rect(0, 0, f.width, f.height)) } }
    }

    fun dismiss() {
        if (closing) return
        closing = true
        host.removeInsetListener(insets)
        host.popBack(back)
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(panel.windowToken, 0)
        // The overlay stops taking input at once; only the fade is animated.
        scrim.isClickable = false
        panel.isEnabled = false
        run(false)
        onDismiss?.invoke()
    }

    private fun run(open: Boolean) {
        anim?.cancel()
        val finish = {
            if (!open) {
                host.overlay.removeView(scrim)
                host.overlay.removeView(panel)
            }
        }
        if (Theme.reducedMotion) {
            scrim.alpha = if (open) 1f else 0f
            panel.alpha = if (open) 1f else 0f
            panel.translationY = 0f
            finish()
            return
        }
        anim = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(scrim, View.ALPHA, if (open) 1f else 0f).setDuration(220),
                ObjectAnimator.ofFloat(panel, View.ALPHA, if (open) 1f else 0f).setDuration(180),
                ObjectAnimator.ofFloat(panel, View.TRANSLATION_Y, if (open) 0f else ctx.dpf(28)).setDuration(220),
            )
            interpolator = Theme.surfaceCurve
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) = finish()
            })
            start()
        }
    }
}

/**
 * Compact glass menu anchored to a view. The checked item is marked; the menu closes
 * on choice, back and a tap outside. Opens with 180 ms fade and a 0.975 -> 1 scale.
 * Placement stays in the visible area above the IME / navigation bar: a composer
 * menu never opens into the keyboard.
 */
class PopupMenu(private val host: Sheet.Host, private val anchor: View) {
    private data class Item(val text: String, val checked: Boolean, val danger: Boolean, val action: () -> Unit)

    private val items = mutableListOf<Item>()
    private val ctx = anchor.context
    private val catcher = View(ctx)
    private val box = LinearLayout(ctx)
    private val back: () -> Boolean = { dismiss(); true }
    private val insets: () -> Unit = { place() }
    private var closed = false
    private var preferAbove = false
    private var pivotSet = false
    private var header: View? = null
    private val width get() = ctx.dp(if (header != null) 312 else 200)

    /** A row above the items, e.g. quick reactions; the menu gets wider. */
    fun header(v: View): PopupMenu {
        header = v
        return this
    }

    fun add(text: String, checked: Boolean = false, danger: Boolean = false, action: () -> Unit): PopupMenu {
        items += Item(text, checked, danger, action)
        return this
    }

    fun show(above: Boolean = false) {
        preferAbove = above
        val ov = host.overlay
        catcher.setOnClickListener { dismiss() }
        catcher.contentDescription = "Закрыть меню"
        box.orientation = LinearLayout.VERTICAL
        box.background = GlassDrawable(ctx, GlassDrawable.Kind.STRONG, ctx.dpf(20))
        box.elevation = ctx.dpf(8)
        box.setPadding(0, ctx.dp(6), 0, ctx.dp(6))
        header?.let { box.addView(it, lp()) }
        for (it in items) {
            val row = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ctx.dp(48)
                setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
                isClickable = true
                isFocusable = true
                background = GlassDrawable(ctx, GlassDrawable.Kind.NONE, ctx.dpf(12), rim = false)
                pressFeedback()
                contentDescription = it.text + if (it.checked) ", выбрано" else ""
                addView(ctx.label(it.text, 15f, 400, if (it.danger) Theme.Role.WARNING else Theme.Role.TEXT), lp(0, WRAP, 1f))
                val tick = ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_tick)
                    imageTintList = android.content.res.ColorStateList.valueOf(Theme.p.accent)
                    visibility = if (it.checked) View.VISIBLE else View.INVISIBLE
                }
                addView(tick, lp(ctx.dp(20), ctx.dp(20)).apply { marginStart = ctx.dp(12) })
                setOnClickListener { _ -> dismiss(); it.action() }
            }
            box.addView(row, lp())
        }
        ov.addView(catcher, FrameLayout.LayoutParams(MATCH, MATCH))
        ov.addView(box, FrameLayout.LayoutParams(width, WRAP))
        host.pushBack(back)
        host.addInsetListener(insets)
        place()
        if (!Theme.reducedMotion) {
            box.alpha = 0f
            box.scaleX = 0.975f
            box.scaleY = 0.975f
            box.animate().alpha(1f).setDuration(180).start()
            box.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(Theme.surfaceCurve).start()
        }
        box.post { box.getChildAt(0)?.requestFocus() }
    }

    /** Keep the menu in the overlay's visible rect (window minus status / nav / IME). */
    private fun place() {
        if (closed) return
        val ov = host.overlay
        if (ov.width == 0 || ov.height == 0) {
            ov.post { place() }
            return
        }
        val loc = IntArray(2)
        val ovLoc = IntArray(2)
        anchor.getLocationInWindow(loc)
        ov.getLocationInWindow(ovLoc)
        box.measure(View.MeasureSpec.makeMeasureSpec(maxOf(ctx.dp(280), width), View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val w = maxOf(box.measuredWidth, width)
        val h = box.measuredHeight
        val pad = ctx.dp(8)
        val visibleTop = maxOf(host.topInset, pad)
        val visibleBottom = ov.height - host.bottomInset - pad
        var x = loc[0] - ovLoc[0] + anchor.width - w
        val maxX = (ov.width - w - pad).coerceAtLeast(pad)
        x = x.coerceIn(pad, maxX)
        val ay = loc[1] - ovLoc[1]
        val below = ay + anchor.height
        val above = ay - h
        val placeAbove = preferAbove || below + h > visibleBottom
        var y = if (placeAbove) above else below
        val maxY = (visibleBottom - h).coerceAtLeast(visibleTop)
        y = y.coerceIn(visibleTop, maxY)
        val lp = box.layoutParams as FrameLayout.LayoutParams
        lp.width = w
        lp.height = WRAP
        lp.leftMargin = x
        lp.topMargin = y
        box.layoutParams = lp
        if (!pivotSet) {
            box.pivotX = w.toFloat()
            box.pivotY = if (placeAbove) h.toFloat() else 0f
            pivotSet = true
        }
    }

    fun dismiss() {
        if (closed) return
        closed = true
        host.removeInsetListener(insets)
        host.popBack(back)
        val ov = host.overlay
        ov.removeView(catcher)
        if (Theme.reducedMotion) ov.removeView(box)
        else box.animate().alpha(0f).setDuration(180).withEndAction { ov.removeView(box) }.start()
    }
}

/** Short message near the bottom; fades out after a few seconds. */
object Toast {
    fun show(host: Sheet.Host, text: String, actionTitle: String? = null, action: (() -> Unit)? = null) {
        val ctx = host.overlay.context
        host.overlay.findViewWithTag<View>("toast")?.let { host.overlay.removeView(it) }
        val box = LinearLayout(ctx).apply {
            tag = "toast"
            gravity = Gravity.CENTER_VERTICAL
            background = GlassDrawable(ctx, GlassDrawable.Kind.STRONG, ctx.dpf(20))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(ctx.dp(6), 0, ctx.dp(6), 0)
        }
        val tv = TextView(ctx).apply {
            this.text = text
            setTextColor(Theme.p.text)
            textSize = 14.5f
            maxWidth = ctx.resources.displayMetrics.widthPixels - ctx.dp(if (action != null) 164 else 60)
            setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
        }
        box.addView(tv)
        val hide = Runnable {
            box.isEnabled = false
            box.animate().alpha(0f).setDuration(Theme.ms(180)).withEndAction { host.overlay.removeView(box) }.start()
        }
        if (actionTitle != null && action != null) {
            var handled = false
            box.addView(TextView(ctx).apply {
                this.text = actionTitle
                textSize = 14.5f
                typeface = weightFace(600)
                setTextColor(Theme.p.accent)
                gravity = Gravity.CENTER
                minimumHeight = ctx.dp(48)
                setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
                isFocusable = true
                setOnClickListener {
                    if (handled || box.parent == null) return@setOnClickListener
                    handled = true
                    isEnabled = false
                    box.removeCallbacks(hide)
                    host.overlay.removeView(box)
                    action()
                }
                pressFeedback()
            })
        }
        host.overlay.addView(box, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(ctx.dp(24), 0, ctx.dp(24), ctx.dp(96) + host.bottomInset)
        })
        box.alpha = 0f
        box.animate().alpha(1f).setDuration(Theme.ms(180)).start()
        box.postDelayed(hide, if (action != null) 5000 else 3200)
    }
}
