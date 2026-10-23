package app.neochat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
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
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG)
    private val triangle = Path()

    init {
        label.color = Color.WHITE
        label.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (!video) return
        val d = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        fill.color = 0x99000000.toInt()
        c.drawCircle(cx, cy, 26 * d, fill)
        fill.color = Color.WHITE
        triangle.rewind()
        triangle.moveTo(cx - 8 * d, cy - 12 * d)
        triangle.lineTo(cx + 13 * d, cy)
        triangle.lineTo(cx - 8 * d, cy + 12 * d)
        triangle.close()
        c.drawPath(triangle, fill)
        if (length.isEmpty()) return
        label.textSize = 12 * d
        val w = label.measureText(length)
        val left = 10 * d
        val top = 10 * d
        fill.color = 0x99000000.toInt()
        c.drawRoundRect(left, top, left + w + 16 * d, top + 22 * d, 11 * d, 11 * d, fill)
        c.drawText(length, left + 8 * d, top + 22 * d / 2 + (label.descent() - label.ascent()) / 2 - label.descent(), label)
    }
}
