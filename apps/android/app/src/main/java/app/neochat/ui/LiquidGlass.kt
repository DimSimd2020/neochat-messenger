package app.neochat.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.RequiresApi

/**
 * Content that glass surfaces look through. A surface that [watch]es it repaints whenever anything
 * inside changes, e.g. while a list scrolls under it.
 */
class BackdropSource(ctx: Context) : FrameLayout(ctx) {
    private val watchers = ArrayList<View>()

    fun watch(v: View) {
        if (v !in watchers) watchers += v
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        for (v in watchers) v.invalidate()
    }
}

/**
 * What lies behind a glass surface, blurred and painted inside the surface's shape. From Android 12
 * the content of [source] is recorded into a render node with a blur effect; from 13 a small shader
 * also bends it along the rim, the way thick glass does. Older systems, weak devices and any failure
 * return false, and the caller paints its denser fill instead.
 */
class BackdropBlur(private val source: BackdropSource, private val blurDp: Float = 12f) {
    private val here = IntArray(2)
    private val there = IntArray(2)
    private var layer: Any? = null
    private var off = Build.VERSION.SDK_INT < 31 ||
        source.context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true

    fun draw(c: Canvas, host: View, bounds: Rect, path: Path, radius: Float): Boolean {
        if (off || !c.isHardwareAccelerated || source.width == 0 || bounds.isEmpty) return false
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val l = layer as? Layer ?: Layer(host.context, blurDp).also { layer = it }
                host.getLocationOnScreen(here)
                source.getLocationOnScreen(there)
                l.draw(c, source, here[0] - there[0] + bounds.left, here[1] - there[1] + bounds.top, bounds, path, radius)
                return true
            } catch (e: Throwable) {
                off = true
            }
        }
        return false
    }
}

@RequiresApi(31)
private class Layer(ctx: Context, blurDp: Float) {
    private val node = RenderNode("glass-backdrop")
    private val blur = ctx.dpf(blurDp)
    /** Room around the surface, so that the blur and the bend at the rim have something to read. */
    private val margin = ctx.dp(18)
    private val depth = ctx.dpf(14)
    private var shapeW = 0
    private var shapeH = 0
    private var shapeR = -1f

    /** [x] and [y] are where the surface starts in the coordinates of [source]. */
    fun draw(c: Canvas, source: View, x: Int, y: Int, bounds: Rect, path: Path, radius: Float) {
        val w = bounds.width() + 2 * margin
        val h = bounds.height() + 2 * margin
        node.setPosition(0, 0, w, h)
        if (w != shapeW || h != shapeH || radius != shapeR) {
            shapeW = w
            shapeH = h
            shapeR = radius
            node.setRenderEffect(effect(bounds.width(), bounds.height(), radius))
        }
        val rec = node.beginRecording(w, h)
        try {
            rec.translate((margin - x).toFloat(), (margin - y).toFloat())
            source.draw(rec)
        } finally {
            node.endRecording()
        }
        c.save()
        c.clipPath(path)
        c.translate((bounds.left - margin).toFloat(), (bounds.top - margin).toFloat())
        c.drawRenderNode(node)
        c.restore()
    }

    private fun effect(w: Int, h: Int, radius: Float): RenderEffect {
        val blurred = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.MIRROR)
        if (Build.VERSION.SDK_INT >= 33) lens(w, h, radius, blurred)?.let { return it }
        val boost = ColorMatrix().apply { setSaturation(1.5f) }
        return RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(boost), blurred)
    }

    @RequiresApi(33)
    private fun lens(w: Int, h: Int, radius: Float, blurred: RenderEffect): RenderEffect? = try {
        val shader = RuntimeShader(LENS)
        shader.setFloatUniform("size", w.toFloat(), h.toFloat())
        shader.setFloatUniform("inset", margin.toFloat(), margin.toFloat())
        shader.setFloatUniform("radius", radius.coerceAtMost(minOf(w, h) / 2f))
        shader.setFloatUniform("depth", minOf(depth, minOf(w, h) / 2f))
        RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"), blurred)
    } catch (e: Throwable) {
        null
    }

    private companion object {
        /**
         * Inside a band along the rim the picture is read from further in, more so the closer to the
         * edge, which shows as the bend of a glass lip; colours are lifted a little at the same time.
         */
        const val LENS = """
            uniform shader content;
            uniform float2 size;
            uniform float2 inset;
            uniform float radius;
            uniform float depth;

            float roundBox(float2 p, float2 hs, float r) {
                float2 q = abs(p) - hs + r;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
            }

            half4 main(float2 xy) {
                float2 hs = size * 0.5;
                float2 p = xy - inset - hs;
                float d = roundBox(p, hs, radius);
                float2 n = float2(
                    roundBox(p + float2(1.0, 0.0), hs, radius) - roundBox(p - float2(1.0, 0.0), hs, radius),
                    roundBox(p + float2(0.0, 1.0), hs, radius) - roundBox(p - float2(0.0, 1.0), hs, radius));
                n = n / max(length(n), 0.001);
                float edge = clamp(1.0 + d / depth, 0.0, 1.0);
                float bend = edge * edge * edge * depth * 0.7;
                half4 c = content.eval(xy - n * bend);
                half l = dot(c.rgb, half3(0.2126, 0.7152, 0.0722));
                c.rgb = clamp(half3(l) + (c.rgb - half3(l)) * 1.5, half3(0.0), half3(c.a));
                return c;
            }
        """
    }
}
