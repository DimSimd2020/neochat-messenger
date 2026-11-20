package app.neochat.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider

private const val GESTURE_TRAVEL = 0.66f

/** Slide the screens together over the stationary activity backdrop. */
internal fun animateChatOpen(previous: Screen, chat: Screen, width: Float, done: () -> Unit): ValueAnimator {
    previous.pageBackground?.visibility = View.INVISIBLE
    chat.pageBackground?.visibility = View.INVISIBLE
    return animateChat(0f, 1f, 280L, { progress ->
        val edge = width * (1f - progress)
        previous.view.translationX = edge - width
        chat.view.translationX = edge
    }) {
        previous.view.translationX = 0f
        chat.view.translationX = 0f
        previous.pageBackground?.visibility = View.VISIBLE
        chat.pageBackground?.visibility = View.VISIBLE
        done()
    }
}

internal class ChatExitMotion(private val chat: View, private val direction: Float) {
    private val drop = chat.context.dpf(22)
    private val radius = chat.context.dpf(32)
    private val originalOutline = chat.outlineProvider
    private val originallyClipped = chat.clipToOutline
    private val roundedOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val corners = radius * (progress / GESTURE_TRAVEL).coerceAtMost(1f)
            outline.setRoundRect(0, 0, view.width, view.height, corners)
        }
    }

    var progress = 0f
        private set

    fun followGesture(value: Float) = place(value * GESTURE_TRAVEL)

    private fun place(value: Float) {
        progress = value.coerceIn(0f, 1f)
        val scale = 1f - 0.15f * progress
        chat.scaleX = scale
        chat.scaleY = scale
        chat.translationX = direction * chat.width * 0.04f * progress
        chat.translationY = minOf(drop, chat.height * 0.06f) * progress
        chat.alpha = ((1f - progress) / (1f - GESTURE_TRAVEL)).coerceIn(0f, 1f)
        if (progress == 0f) {
            if (chat.outlineProvider === roundedOutline) {
                chat.clipToOutline = originallyClipped
                chat.outlineProvider = originalOutline
            }
        } else if (chat.outlineProvider !== roundedOutline) {
            chat.outlineProvider = roundedOutline
            chat.clipToOutline = true
        } else {
            chat.invalidateOutline()
        }
    }

    fun settle(target: Float, done: () -> Unit): ValueAnimator =
        animateChat(progress, target, if (target == 0f) 180L else 240L, ::place, done)

    fun reset() = place(0f)
}

private fun animateChat(
    from: Float,
    to: Float,
    durationMs: Long,
    frame: (Float) -> Unit,
    done: () -> Unit,
): ValueAnimator {
    frame(from)
    return ValueAnimator.ofFloat(0f, 1f).apply {
        duration = durationMs
        interpolator = Theme.surfaceCurve
        addUpdateListener { frame(from + (to - from) * it.animatedFraction) }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = done()
        })
        start()
    }
}
