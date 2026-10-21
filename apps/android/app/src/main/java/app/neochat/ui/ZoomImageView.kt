package app.neochat.ui

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/**
 * A picture that zooms with a pinch (1x to 4x) or a double tap and pans while zoomed.
 * At 1x it lets the parent pager take horizontal swipes; a single tap calls [onTap].
 */
class ZoomImageView(ctx: Context) : ImageView(ctx) {
    var onTap: (() -> Unit)? = null
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastX = 0f
    private var lastY = 0f

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomTo(zoom * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1f) reset() else zoomTo(2.5f, e.x, e.y)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap?.invoke()
            return true
        }
    })

    fun reset() {
        zoom = 1f
        panX = 0f
        panY = 0f
        apply()
    }

    /** Keeps the point under the fingers where it is while the scale changes. */
    private fun zoomTo(target: Float, fx: Float, fy: Float) {
        val next = target.coerceIn(1f, 4f)
        panX += (fx - width / 2f) * (zoom - next)
        panY += (fy - height / 2f) * (zoom - next)
        zoom = next
        apply()
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
        scaler.onTouchEvent(e)
        gestures.onTouchEvent(e)
        // Panning follows the raw screen position: view-local coordinates move with the picture.
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastX = e.rawX
                lastY = e.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                if (zoom > 1f && e.pointerCount == 1 && !scaler.isInProgress) {
                    panX += e.rawX - lastX
                    panY += e.rawY - lastY
                    apply()
                }
                lastX = e.rawX
                lastY = e.rawY
            }
        }
        return true
    }
}
