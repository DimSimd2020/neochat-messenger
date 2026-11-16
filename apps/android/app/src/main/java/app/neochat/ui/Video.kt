package app.neochat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.Choreographer
import android.widget.ImageView

/** What a video message carries besides the file: its first frame, the picture size and the length. */
class VideoInfo(val thumb: String?, val width: Int, val height: Int, val durationMs: Long)

object Videos {
    /** Reads the length and size and cuts the poster frame; null when the system cannot open the video. */
    fun info(path: String): VideoInfo? = runCatching {
        read({ it.setDataSource(path) }, 320)?.let { VideoInfo(it.frame?.let { f -> Photos.encodeThumb(f) }, it.width, it.height, it.ms) }
    }.getOrNull()

    /** A larger frame for the preview in the attach window. */
    fun frame(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        read({ it.setDataSource(ctx, uri) }, maxSide)?.frame
    }.getOrNull()

    private fun read(open: (MediaMetadataRetriever) -> Unit, maxSide: Int): Clip? {
        val r = MediaMetadataRetriever()
        try {
            open(r)
            fun meta(key: Int) = r.extractMetadata(key)?.toIntOrNull() ?: 0
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val turned = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            var w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            var h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (turned == 90 || turned == 270) w = h.also { h = w }
            // A little in, so a fade from black does not become the poster; short clips use the middle.
            val at = minOf(300_000L, ms * 500)
            var frame = r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return if (w > 0 && h > 0) Clip(null, w, h, ms) else null
            // Not every device turns the frame; trust the size the file declares.
            if (turned != 0 && w > 0 && h > 0 && (frame.width > frame.height) != (w > h)) {
                frame = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(turned.toFloat()) }, true)
            }
            if (w <= 0 || h <= 0) {
                w = frame.width
                h = frame.height
            }
            val k = maxSide.toFloat() / maxOf(frame.width, frame.height)
            if (k < 1f) frame = Bitmap.createScaledBitmap(frame, maxOf(1, (frame.width * k).toInt()), maxOf(1, (frame.height * k).toInt()), true)
            return Clip(frame, w, h, ms)
        } finally {
            r.release()
        }
    }

    private class Clip(val frame: Bitmap?, val width: Int, val height: Int, val ms: Long)

    /** 0:07, 12:30, 1:02:03. */
    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }
}

/** The picture of a video in a bubble: its first frame, a play mark in the middle and the length in a corner. */
class PosterView(ctx: Context) : ImageView(ctx) {
    var video = false
        set(v) {
            field = v
            invalidate()
        }
    var length = ""
        set(v) {
            field = v
            invalidate()
        }
    /** A view-once file: nothing of it is drawn, the cover takes the whole place. */
    var once = false
        set(v) {
            field = v
            invalidate()
        }
    /** The line on the cover of a view-once file: "Фото", "Просмотрено". */
    var note = ""
        set(v) {
            field = v
            invalidate()
        }

    /**
     * The sender hid this picture. Three steps of defocus lie over the sharp frame and the dust
     * of [SpoilerFx] drifts on top; [reveal] moves the whole cover away and back.
     */
    var hidden = false
        set(v) {
            field = v
            restart()
            invalidate()
        }
    var blur: List<Bitmap>? = null
    var seed = 0
    var onReveal: ((Float) -> Unit)? = null
    var reveal = 0f
        private set

    private val spring = Spring(0.5f, 0.86f, 0.0015f) {
        reveal = it
        onReveal?.invoke(it)
        restart()
        invalidate()
    }
    private val cover = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val box = RectF()
    private var ticking = false
    private val frames = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!ticking) return
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Takes the stored motion of this message and carries it on to where the message is now. */
    fun bindReveal(start: Float, target: Float) {
        if (spring.running && spring.target == target) {
            restart()
            return
        }
        spring.snap(start)
        if (start != target) spring.to(target)
        restart()
    }

    fun revealTo(target: Float) {
        spring.to(target)
        restart()
    }

    private fun restart() {
        val want = hidden && reveal < 0.999f && isAttachedToWindow && !Theme.reducedMotion
        if (want == ticking) return
        ticking = want
        if (want) Choreographer.getInstance().postFrameCallback(frames)
        else Choreographer.getInstance().removeFrameCallback(frames)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restart()
    }

    override fun onDetachedFromWindow() {
        ticking = false
        Choreographer.getInstance().removeFrameCallback(frames)
        spring.stop()
        super.onDetachedFromWindow()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG)
    private val triangle = Path()

    init {
        label.color = Color.WHITE
        label.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (once) {
            drawCover(c)
            return
        }
        val d = resources.displayMetrics.density
        if (hidden) drawHidden(c, d)
        if (note.isNotEmpty() && !hidden) drawNote(c)
        if (!video) return
        // The play mark and the length belong to the picture: they come out with it.
        val vis = if (hidden) reveal else 1f
        if (vis <= 0.02f) return
        val cx = width / 2f
        val cy = height / 2f
        fill.color = 0x99000000.toInt()
        fill.alpha = (fill.alpha * vis).toInt()
        c.drawCircle(cx, cy, 26 * d, fill)
        fill.color = Color.WHITE
        fill.alpha = (255 * vis).toInt()
        triangle.rewind()
        triangle.moveTo(cx - 8 * d, cy - 12 * d)
        triangle.lineTo(cx + 13 * d, cy)
        triangle.lineTo(cx - 8 * d, cy + 12 * d)
        triangle.close()
        c.drawPath(triangle, fill)
        if (length.isEmpty()) return
        label.textSize = 12 * d
        label.alpha = (255 * vis).toInt()
        val w = label.measureText(length)
        val left = 10 * d
        val top = 10 * d
        fill.color = 0x99000000.toInt()
        fill.alpha = (fill.alpha * vis).toInt()
        c.drawRoundRect(left, top, left + w + 16 * d, top + 22 * d, 11 * d, 11 * d, fill)
        c.drawText(length, left + 8 * d, top + 22 * d / 2 + (label.descent() - label.ascent()) / 2 - label.descent(), label)
    }

    /** The defocus ladder, the dust and the light that crosses while the picture opens. */
    private fun drawHidden(c: Canvas, d: Float) {
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        val p = reveal
        val depth = (1f - p) * 3f
        blur?.let { steps ->
            for (i in 2 downTo 0) {
                val a = (depth - (2 - i)).coerceIn(0f, 1f)
                if (a <= 0.004f || i >= steps.size) continue
                drawScaled(c, steps[i], 1f + 0.05f * (1f - p), (a * 255).toInt())
            }
        }
        fill.color = 0xFF05070C.toInt()
        fill.alpha = (52 * (1f - p)).toInt()
        c.drawRect(box, fill)
        SpoilerFx.dust(c, box, seed, 1f - p, p, d)
        SpoilerFx.sweep(c, box, p)
    }

    /** One step of the blur, cropped like the picture itself and slightly larger while closed. */
    private fun drawScaled(c: Canvas, b: Bitmap, zoom: Float, alpha: Int) {
        if (b.isRecycled || alpha <= 0) return
        val k = maxOf(width.toFloat() / b.width, height.toFloat() / b.height) * zoom
        val w = b.width * k
        val h = b.height * k
        cover.alpha = alpha
        c.drawBitmap(b, null, RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2), cover)
    }

    /** The face of a view-once file: a dark plate, a round "1" and what became of it. */
    private fun drawCover(c: Canvas) {
        val d = resources.displayMetrics.density
        fill.color = 0xFF101722.toInt()
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val cx = width / 2f
        val cy = height / 2f
        fill.color = 0x33FFFFFF
        c.drawCircle(cx, cy - 16 * d, 20 * d, fill)
        val one = "1"
        label.textSize = 17 * d
        label.color = Color.WHITE
        c.drawText(one, cx - label.measureText(one) / 2, cy - 16 * d - (label.descent() + label.ascent()) / 2, label)
        if (note.isEmpty()) return
        label.textSize = 13 * d
        c.drawText(note, cx - label.measureText(note) / 2, cy + 20 * d, label)
    }

    /** A short line in a corner over the picture. */
    private fun drawNote(c: Canvas) {
        val d = resources.displayMetrics.density
        label.textSize = 12 * d
        val w = label.measureText(note)
        val left = 10 * d
        val top = 10 * d
        fill.color = 0x99000000.toInt()
        c.drawRoundRect(left, top, left + w + 16 * d, top + 22 * d, 11 * d, 11 * d, fill)
        label.color = Color.WHITE
        c.drawText(note, left + 8 * d, top + 22 * d / 2 + (label.descent() - label.ascent()) / 2 - label.descent(), label)
    }
}
