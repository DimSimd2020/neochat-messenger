package app.neochat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/** One filmstrip with a start handle, an end handle and a seek position. */
internal class StoryTimeline(ctx: Context, private val duration: Int) : View(ctx) {
    private enum class Thumb { START, END, POSITION }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val strip = RectF()
    private val tile = RectF()
    private val crop = Rect()
    private val gutter = ctx.dpf(12)
    private val handle = ctx.dpf(5)
    private val hit = ctx.dpf(22)
    private var target: Thumb? = null
    private var offset = 0f
    private var selected = Thumb.POSITION
    private val frames = arrayOfNulls<Bitmap>(STORY_THUMBNAILS)
    private var firstLabel = ""
    private var lastLabel = ""
    private val shortest = duration.coerceAtMost(500)
    var first = 0
        private set
    var last = minOf(60_000, duration)
        private set
    private var playhead = 0
    var position: Int
        get() = playhead
        set(value) {
            if (playhead == value || target != null) return
            playhead = value.coerceIn(0, duration)
            invalidate()
        }
    var onSeek: (Int) -> Unit = {}

    init {
        isFocusable = true
        isClickable = true
        contentDescription = "Обрезка видео"
        updateDescription()
        for ((label, thumb, delta) in listOf(
            Triple("Начало на секунду раньше", Thumb.START, -1000),
            Triple("Начало на секунду позже", Thumb.START, 1000),
            Triple("Конец на секунду раньше", Thumb.END, -1000),
            Triple("Конец на секунду позже", Thumb.END, 1000),
        )) {
            ViewCompat.addAccessibilityAction(this, label) { _, _ ->
                move(thumb, value(thumb) + delta)
                true
            }
        }
    }

    fun thumbnail(index: Int, image: Bitmap) {
        frames[index] = image
        invalidate()
    }

    private fun x(time: Int): Float = gutter + (width - 2 * gutter).coerceAtLeast(1f) * time / duration.coerceAtLeast(1)
    private fun time(x: Float): Int =
        ((x - gutter) * duration / (width - 2 * gutter).coerceAtLeast(1f)).roundToInt().coerceIn(0, duration)
    private fun value(thumb: Thumb) = when (thumb) {
        Thumb.START -> first
        Thumb.END -> last
        Thumb.POSITION -> position
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 2 * gutter || height == 0) return
        val pad = context.dpf(8)
        strip.set(gutter, context.dpf(23), width - gutter, height - pad)
        if (strip.height() <= 0f) return
        paint.color = 0xFFBBCADC.toInt()
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(firstLabel, strip.left, context.dpf(13), paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(lastLabel, strip.right, context.dpf(13), paint)
        paint.color = 0xFF2B3441.toInt()
        canvas.drawRect(strip, paint)
        val w = strip.width() / frames.size
        frames.forEachIndexed { i, image ->
            val bitmap = image ?: return@forEachIndexed
            tile.set(strip.left + i * w, strip.top, strip.left + (i + 1) * w, strip.bottom)
            val cropWidth = (bitmap.height * w / strip.height()).roundToInt().coerceIn(1, bitmap.width)
            val cropHeight = (bitmap.width * strip.height() / w).roundToInt().coerceIn(1, bitmap.height)
            val left = (bitmap.width - cropWidth) / 2
            val top = (bitmap.height - cropHeight) / 2
            crop.set(left, top, left + cropWidth, top + cropHeight)
            canvas.drawBitmap(bitmap, crop, tile, paint)
        }
        val a = x(first)
        val b = x(last)
        paint.color = 0x99101926.toInt()
        canvas.drawRect(strip.left, strip.top, a, strip.bottom, paint)
        canvas.drawRect(b, strip.top, strip.right, strip.bottom, paint)
        val edge = context.dpf(2)
        paint.color = 0xFFE2EDFA.toInt()
        canvas.drawRect(a, strip.top, b, strip.top + edge, paint)
        canvas.drawRect(a, strip.bottom - edge, b, strip.bottom, paint)
        canvas.drawRoundRect(a - handle, strip.top - edge, a + handle, strip.bottom + edge, edge, edge, paint)
        canvas.drawRoundRect(b - handle, strip.top - edge, b + handle, strip.bottom + edge, edge, edge, paint)
        paint.color = 0xFF91BAFF.toInt()
        val cursor = x(position)
        canvas.drawRoundRect(cursor - edge / 2, strip.top - pad / 2, cursor + edge / 2,
            strip.bottom + pad / 2, edge, edge, paint)
    }

    private fun move(thumb: Thumb, next: Int) {
        when (thumb) {
            Thumb.START -> {
                first = next.coerceIn(0, duration - shortest)
                last = last.coerceIn(first + shortest, minOf(first + 60_000, duration))
            }
            Thumb.END -> {
                last = next.coerceIn(shortest, duration)
                first = first.coerceIn(maxOf(0, last - 60_000), last - shortest)
            }
            Thumb.POSITION -> Unit
        }
        // The held thumb owns the playhead until playback is resumed.
        playhead = if (thumb == Thumb.POSITION) next.coerceIn(0, duration) else value(thumb)
        onSeek(playhead)
        if (thumb != Thumb.POSITION) updateDescription()
        invalidate()
    }

    private fun updateDescription() {
        firstLabel = Videos.clock(first.toLong())
        lastLabel = Videos.clock(last.toLong())
        describeState("$firstLabel – $lastLabel")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val from = abs(event.x - x(first))
                val to = abs(event.x - x(last))
                selected = when {
                    from <= hit && (from < to || (from == to && event.x < x(first))) -> Thumb.START
                    to <= hit -> Thumb.END
                    else -> Thumb.POSITION
                }
                target = selected
                offset = if (selected == Thumb.POSITION) 0f else event.x - x(value(selected))
                move(selected, time(event.x - offset))
            }
            MotionEvent.ACTION_MOVE -> target?.let { move(it, time(event.x - offset)) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                target = null
                parent.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            selected = Thumb.entries[(selected.ordinal + 1) % Thumb.entries.size]
            announceForAccessibility(when (selected) {
                Thumb.START -> "Начало"
                Thumb.END -> "Конец"
                Thumb.POSITION -> "Позиция"
            })
            return true
        }
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1_000
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1_000
            else -> return super.onKeyDown(keyCode, event)
        }
        move(selected, value(selected) + delta)
        return true
    }
}
