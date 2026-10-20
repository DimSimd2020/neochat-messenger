package app.neochat.ui

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/** Real Android touch dispatch, without a network account or additional test libraries. */
class InteractionInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        try {
            runOnMainSync {
                val ctx = targetContext
                val parent = FrameLayout(ctx)
                var replies = 0
                val row = SwipeToReplyLayout(ctx, { true }, { replies++ })
                val hitbox = FrameLayout(ctx).apply { isClickable = true }
                hitbox.addView(View(ctx).apply { isClickable = true }, FrameLayout.LayoutParams(ctx.dp(80), ctx.dp(48)))
                row.attach(hitbox)
                parent.addView(row, FrameLayout.LayoutParams(ctx.dp(360), ctx.dp(96)))
                parent.measure(View.MeasureSpec.makeMeasureSpec(ctx.dp(360), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ctx.dp(96), View.MeasureSpec.EXACTLY))
                parent.layout(0, 0, ctx.dp(360), ctx.dp(96))
                val oldReduced = Theme.forceReduced
                Theme.forceReduced = true
                fun gesture(x: Int, dx: Int, dy: Int = 0, cancel: Boolean = false) {
                    val start = SystemClock.uptimeMillis()
                    fun send(action: Int, px: Int, py: Int, delay: Long) {
                        val ev = MotionEvent.obtain(start, start + delay, action, ctx.dpf(px), ctx.dpf(py), 0)
                        row.dispatchTouchEvent(ev)
                        ev.recycle()
                    }
                    send(MotionEvent.ACTION_DOWN, x, 48, 0)
                    send(MotionEvent.ACTION_MOVE, x + dx / 2, 48 + dy / 2, 30)
                    send(MotionEvent.ACTION_MOVE, x + dx, 48 + dy, 60)
                    send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, x + dx, 48 + dy, 90)
                }
                try {
                    // The bubble ends at x=80; the gesture starts in empty space.
                    gesture(220, 90)
                    check(replies == 1) { "Reply did not start outside the bubble" }
                    check(hitbox.translationX == 0f)
                    gesture(220, -90)
                    check(replies == 2) { "Opposite swipe did not reply" }
                    gesture(220, 12, 70)
                    check(replies == 2) { "Vertical scroll triggered a reply" }
                    gesture(8, 90)
                    check(replies == 2) { "System back edge triggered a reply" }
                    gesture(220, 90, cancel = true)
                    check(replies == 2) { "Cancelled gesture triggered a reply" }
                } finally { Theme.forceReduced = oldReduced; row.reset() }
            }
            result.putString("stream", "OK: whitespace swipes in both directions, vertical scroll, back edge, cancellation\n")
            finish(-1, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.message}\n")
            finish(0, result)
        }
    }
}
