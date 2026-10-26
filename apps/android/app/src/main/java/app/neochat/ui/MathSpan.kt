package app.neochat.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.style.ReplacementSpan
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A LaTeX formula typeset by the core (the "math" key of a markdown span), drawn in place of one
 * placeholder character in the color of the text around it. All sizes are in em of the text.
 */
class MathSpan(math: JSONObject) : ReplacementSpan() {
    private val width = math.optDouble("w").toFloat()
    private val above = math.optDouble("h").toFloat()
    private val below = math.optDouble("d").toFloat()
    private val glyphs = HashMap<Int, Path>()
    private val placed = math.optJSONArray("g") ?: JSONArray()
    private val rules = math.optJSONArray("r") ?: JSONArray()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        val all = math.optJSONObject("glyphs")
        if (all != null) for (key in all.keys()) glyphs[key.toInt()] = path(all.getJSONArray(key))
    }

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val em = paint.textSize
        if (fm != null) {
            // The line grows to hold a tall formula; a small one keeps the line as it was.
            fm.ascent = min(fm.ascent, -ceil(above * em).toInt())
            fm.top = min(fm.top, fm.ascent)
            fm.descent = max(fm.descent, ceil(below * em).toInt())
            fm.bottom = max(fm.bottom, fm.descent)
        }
        return ceil(width * em).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val em = paint.textSize
        fill.color = paint.color
        canvas.save()
        canvas.translate(x, y.toFloat())
        for (i in 0 until placed.length()) {
            val g = placed.getJSONArray(i)
            val outline = glyphs[g.getInt(0)] ?: continue
            canvas.save()
            canvas.translate(g.getDouble(1).toFloat() * em, g.getDouble(2).toFloat() * em)
            val scale = g.getDouble(3).toFloat() * em
            canvas.scale(scale, scale)
            canvas.drawPath(outline, fill)
            canvas.restore()
        }
        for (i in 0 until rules.length()) {
            val r = rules.getJSONArray(i)
            val left = r.getDouble(0).toFloat() * em
            val topEdge = r.getDouble(1).toFloat() * em
            canvas.drawRect(left, topEdge, left + r.getDouble(2).toFloat() * em, topEdge + r.getDouble(3).toFloat() * em, fill)
        }
        canvas.restore()
    }

    /** Path commands of the core: 0 M, 1 L, 2 Q, 3 C, 4 Z, each followed by its coordinates. */
    private fun path(c: JSONArray): Path {
        val p = Path()
        var i = 0
        fun v(k: Int) = c.getDouble(i + k).toFloat()
        while (i < c.length()) {
            when (c.getInt(i)) {
                0 -> { p.moveTo(v(1), v(2)); i += 3 }
                1 -> { p.lineTo(v(1), v(2)); i += 3 }
                2 -> { p.quadTo(v(1), v(2), v(3), v(4)); i += 5 }
                3 -> { p.cubicTo(v(1), v(2), v(3), v(4), v(5), v(6)); i += 7 }
                else -> { p.close(); i += 1 }
            }
        }
        return p
    }
}
