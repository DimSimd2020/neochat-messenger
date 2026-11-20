package app.neochat.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

fun View.replyHaptic() = Haptics.threshold(this)

class GestureProgress(private val redraw: () -> Unit) {
    var value = 0f
    private var burstAt = -1L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    fun complete() {
        if (Theme.reducedMotion) return
        burstAt = SystemClock.uptimeMillis()
        redraw()
    }

    fun reset() { value = 0f; burstAt = -1L }

    fun draw(c: Canvas, x: Float, y: Float, radius: Float, stroke: Float, color: Int) {
        val t = if (burstAt < 0L || Theme.reducedMotion) 1f else ((SystemClock.uptimeMillis() - burstAt) / 320f).coerceIn(0f, 1f)
        if (value <= 0.02f && t >= 1f) return
        paint.color = color
        paint.strokeWidth = stroke
        if (value > 0.02f) {
            paint.alpha = 54
            c.drawCircle(x, y, radius, paint)
            paint.alpha = 255
            c.drawArc(x - radius, y - radius, x + radius, y + radius, -90f, 360f * value.coerceAtMost(1f), false, paint)
        }
        if (t < 1f) {
            paint.alpha = ((1f - t) * 220).toInt()
            val inner = radius + stroke * (1.6f + 3.8f * t)
            val outer = inner + stroke * (2.8f - 1.2f * t)
            repeat(8) { i ->
                val angle = (i + 0.5) * PI / 4
                val dx = cos(angle).toFloat()
                val dy = sin(angle).toFloat()
                c.drawLine(x + dx * inner, y + dy * inner, x + dx * outer, y + dy * outer, paint)
            }
            redraw()
        }
    }
}

/** A native vector: the lid opens and the arrow drops into the box as the row is pulled out. */
class ArchiveIcon(redraw: () -> Unit) : Drawable() {
    var progress = 0f
    val halo = GestureProgress(redraw)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val p = if (Theme.reducedMotion) 0f else progress.coerceIn(0f, 1f)
        val lift = sin(p * PI).toFloat()
        halo.draw(canvas, bounds.exactCenterX(), bounds.exactCenterY(), bounds.width() * 0.72f, bounds.width() * 0.063f, paint.color)
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 26f, bounds.height() / 26f)
        canvas.drawRoundRect(5f, 11f, 21f, 23f, 2f, 2f, paint)
        canvas.drawLine(10f, 16f, 16f, 16f, paint)
        canvas.save()
        canvas.rotate(-24f * lift, 21f, 9f)
        canvas.drawRoundRect(3f, 7f - 2f * lift, 23f, 11f - 2f * lift, 1f, 1f, paint)
        canvas.restore()
        val alpha = paint.alpha
        paint.alpha = (alpha * lift).toInt()
        val y = -3f + 15f * p
        canvas.drawLine(13f, y - 5f, 13f, y, paint)
        canvas.drawLine(10f, y - 3f, 13f, y, paint)
        canvas.drawLine(16f, y - 3f, 13f, y, paint)
        paint.alpha = alpha
        canvas.restore()
    }

    override fun setTint(tintColor: Int) { paint.color = tintColor }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Suppress("DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
