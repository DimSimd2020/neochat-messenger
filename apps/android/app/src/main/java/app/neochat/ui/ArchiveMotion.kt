package app.neochat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/** The same short tick for a reply, an archive action and the pull threshold. */
fun View.replyHaptic() {
    val vibrator = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
    else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
    if (vibrator == null || !vibrator.hasVibrator()) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        return
    }
    vibrator.vibrate(
        if (Build.VERSION.SDK_INT >= 29) android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK)
        else android.os.VibrationEffect.createOneShot(12, 90)
    )
}

/** A native vector: the lid opens and the arrow drops into the box as the row is pulled out. */
class ArchiveIcon(private val ring: Boolean = false) : Drawable() {
    var progress = 0f
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
        if (ring && p > 0.02f && p < 1f) {
            val savedWidth = paint.strokeWidth
            paint.strokeWidth = bounds.width() / 26f * 1.3f
            val radius = bounds.width() * 0.9f
            canvas.drawArc(bounds.exactCenterX() - radius, bounds.exactCenterY() - radius,
                bounds.exactCenterX() + radius, bounds.exactCenterY() + radius,
                -90f, p * 360f, false, paint)
            paint.strokeWidth = savedWidth
        }
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
