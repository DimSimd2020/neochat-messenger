package app.neochat.ui

import android.animation.ValueAnimator
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ImageView
import kotlin.math.hypot

/**
 * A picture that zooms with a pinch (1x to 4x) or a double tap and pans while zoomed.
 * At 1x it lets the parent pager take horizontal swipes; a single tap calls [onTap].
 *
 * Every gesture is measured in screen coordinates: coordinates inside this view move with the
 * picture while it is scaled, which made pinching drift and refuse to zoom back out.
 */
class ZoomImageView(ctx: Context) : ImageView(ctx) {
    var onTap: (() -> Unit)? = null
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastDist = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var anim: ValueAnimator? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1f) animateTo(1f, 0f, 0f) else zoomAt(2.5f, e.rawX, e.rawY, animated = true)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap?.invoke()
            return true
        }
    })

    fun reset() {
        anim?.cancel()
        zoom = 1f
        panX = 0f
        panY = 0f
        apply()
    }

    /** Screen position of the view's own center, which does not move with scaling or panning. */
    private fun centerX(): Float {
        val at = IntArray(2)
        (parent as? android.view.View)?.getLocationOnScreen(at)
        return at[0] + left + width / 2f
    }

    private fun centerY(): Float {
        val at = IntArray(2)
        (parent as? android.view.View)?.getLocationOnScreen(at)
        return at[1] + top + height / 2f
    }

    /** Scales about a screen point so that the point under the fingers stays put. */
    private fun zoomAt(target: Float, fx: Float, fy: Float, animated: Boolean = false) {
        val next = target.coerceIn(1f, 4f)
        val dx = fx - centerX()
        val dy = fy - centerY()
        val k = next / zoom
        val x = dx - k * (dx - panX)
        val y = dy - k * (dy - panY)
        if (animated) animateTo(next, x, y) else {
            zoom = next
            panX = x
            panY = y
            apply()
        }
    }

    private fun animateTo(z: Float, x: Float, y: Float) {
        anim?.cancel()
        val z0 = zoom
        val x0 = panX
        val y0 = panY
        val d = Theme.ms(180)
        if (d == 0L) {
            zoom = z; panX = x; panY = y
            return apply()
        }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = d
            interpolator = Theme.surfaceCurve
            addUpdateListener {
                val t = it.animatedValue as Float
                zoom = z0 + (z - z0) * t
                panX = x0 + (x - x0) * t
                panY = y0 + (y - y0) * t
                apply()
            }
            start()
        }
    }

    private fun apply() {
        val maxX = (zoom - 1f) * width / 2f
        val maxY = (zoom - 1f) * height / 2f
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        scaleX = zoom
        scaleY = zoom
        translationX = panX
        translationY = panY
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Zoomed or pinching: the picture owns the gesture; otherwise the pager may take the swipe.
        parent?.requestDisallowInterceptTouchEvent(zoom > 1f || e.pointerCount > 1)
        taps.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.cancel()
                lastX = e.rawX
                lastY = e.rawY
                lastDist = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // Restart the measurement so lifting one finger does not jump the picture.
                val skip = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
                val ids = (0 until e.pointerCount).filter { it != skip }
                lastDist = 0f
                if (ids.size >= 2) lastDist = dist(e, ids[0], ids[1])
                if (ids.isNotEmpty()) {
                    lastX = ids.map { sx(e, it) }.average().toFloat()
                    lastY = ids.map { sy(e, it) }.average().toFloat()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val cx = (sx(e, 0) + sx(e, 1)) / 2
                    val cy = (sy(e, 0) + sy(e, 1)) / 2
                    val d = dist(e, 0, 1)
                    if (lastDist > 0f && d > 0f) {
                        // The pinch scales about the fingers' middle; the middle moving drags the picture.
                        zoomAt(zoom * d / lastDist, cx, cy)
                        panX += cx - lastX
                        panY += cy - lastY
                        apply()
                    }
                    lastDist = d
                    lastX = cx
                    lastY = cy
                } else if (zoom > 1f) {
                    panX += e.rawX - lastX
                    panY += e.rawY - lastY
                    apply()
                    lastX = e.rawX
                    lastY = e.rawY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastDist = 0f
                // A pinch that ends slightly under 1x or over 4x has already been clamped; settle the pan.
                if (zoom <= 1.02f && (panX != 0f || panY != 0f || zoom != 1f)) animateTo(1f, 0f, 0f)
            }
        }
        return true
    }

    // Screen position of a pointer: raw coordinates exist per pointer from API 29; before that the offset
    // from the first pointer is scaled by this view's own scale.
    private fun sx(e: MotionEvent, i: Int) = if (android.os.Build.VERSION.SDK_INT >= 29) e.getRawX(i) else e.rawX + (e.getX(i) - e.getX(0)) * scaleX

    private fun sy(e: MotionEvent, i: Int) = if (android.os.Build.VERSION.SDK_INT >= 29) e.getRawY(i) else e.rawY + (e.getY(i) - e.getY(0)) * scaleY

    private fun dist(e: MotionEvent, a: Int, b: Int) = hypot(sx(e, a) - sx(e, b), sy(e, a) - sy(e, b)).coerceAtLeast(slop.toFloat() / 4)
}
