package app.neochat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.icu.text.BreakIterator
import android.os.SystemClock
import android.util.LruCache
import android.view.View
import org.json.JSONObject

/**
 * Animated versions of the common emoji (Animated Noto Emoji, Google, CC BY 4.0): one sprite sheet PNG per emoji in
 * assets/animated-emoji, built by scripts/build-animated-emoji.py. A message made only of such emoji plays them
 * instead of showing the font's still picture; any other emoji keeps the font.
 */
object AnimatedEmoji {
    class Sheet(val bitmap: Bitmap, val frames: Int, val ms: Int, val frame: Int, val cols: Int)

    private var index: JSONObject? = null
    private val sheets = object : LruCache<String, Sheet>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Sheet) = value.bitmap.byteCount
    }

    private fun index(ctx: Context): JSONObject? {
        index?.let { return it }
        val loaded = runCatching { ctx.assets.open("animated-emoji/index.json").use { JSONObject(it.readBytes().decodeToString()) } }.getOrNull()
        index = loaded
        return loaded
    }

    /** The emoji (grapheme clusters) of the text, spaces left out. */
    fun glyphs(text: String): List<String> {
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val out = ArrayList<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val g = text.substring(start, end)
            if (g.isNotBlank()) out += g
            start = end
            end = it.next()
        }
        return out
    }

    /** Code points in hex joined by '-', without the variation selector: the name of the sheet. */
    fun keyOf(glyph: String): String =
        glyph.codePoints().toArray().filter { it != 0xFE0F }.joinToString("-") { Integer.toHexString(it) }

    /** One sheet per emoji when every emoji of the text has an animation; null otherwise. */
    fun sheetsOf(ctx: Context, text: String): List<Sheet>? {
        val glyphs = glyphs(text)
        if (glyphs.isEmpty() || glyphs.size > 3) return null
        return glyphs.map { sheet(ctx, keyOf(it)) ?: return null }
    }

    private fun sheet(ctx: Context, key: String): Sheet? {
        sheets.get(key)?.let { return it }
        val idx = index(ctx) ?: return null
        val meta = idx.optJSONObject("emoji")?.optJSONObject(key) ?: return null
        val bmp = runCatching {
            ctx.assets.open("animated-emoji/$key.png").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) }
        }.getOrNull() ?: return null
        val s = Sheet(bmp, meta.optInt("frames", 1), meta.optInt("ms", 66).coerceAtLeast(16), idx.optInt("size", 96), idx.optInt("cols", 8))
        sheets.put(key, s)
        return s
    }
}

/** Draws the frames of one emoji: twice through, then the first frame again. Still when motion is reduced. */
class AnimatedEmojiView(ctx: Context) : View(ctx) {
    private var sheet: AnimatedEmoji.Sheet? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val from = Rect()
    private val to = Rect()
    private var started = 0L

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(s: AnimatedEmoji.Sheet) {
        if (sheet === s) return
        sheet = s
        started = SystemClock.uptimeMillis()
        invalidate()
    }

    // Scrolling the message into view plays it again.
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        started = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val s = sheet ?: return
        val played = (SystemClock.uptimeMillis() - started) / s.ms
        val playing = !Theme.reducedMotion && s.frames > 1 && played < 2L * s.frames
        val n = if (playing) (played % s.frames).toInt() else 0
        from.set(n % s.cols * s.frame, n / s.cols * s.frame, (n % s.cols + 1) * s.frame, (n / s.cols + 1) * s.frame)
        to.set(0, 0, width, height)
        c.drawBitmap(s.bitmap, from, to, paint)
        if (playing) postInvalidateDelayed(s.ms.toLong())
    }
}
