package app.neochat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import app.neochat.core.CoreClient
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Records the microphone into an AAC .m4a file and keeps the loudness for the wave of the message. */
class VoiceRecorder(private val ctx: Context) {
    class Result(val file: File, val durationMs: Long, val wave: ByteArray)

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private val levels = ArrayList<Int>()
    private val main = Handler(Looper.getMainLooper())

    /** 0..1 about every 60 ms while recording. */
    var onLevel: ((Float) -> Unit)? = null

    val recording get() = recorder != null
    val elapsedMs get() = if (recorder == null) 0L else android.os.SystemClock.elapsedRealtime() - startedAt

    private val poll = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
            levels += min(255, amp * 255 / 14000)
            onLevel?.invoke(min(1f, (amp / 14000f).pow(0.6f)))
            main.postDelayed(this, 60)
        }
    }

    fun start(): Boolean {
        if (recorder != null) return false
        val dir = File(ctx.cacheDir, "voice").apply { mkdirs() }
        val out = File.createTempFile("voice", ".m4a", dir)
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(64000)
            r.setMaxDuration(10 * 60 * 1000)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = out
            levels.clear()
            startedAt = android.os.SystemClock.elapsedRealtime()
            main.postDelayed(poll, 60)
            true
        } catch (_: Exception) {
            runCatching { r.release() }
            out.delete()
            false
        }
    }

    /** Ends the recording; null when the system produced nothing usable. */
    fun stop(): Result? {
        val r = recorder ?: return null
        val out = file
        val ms = elapsedMs
        recorder = null
        file = null
        main.removeCallbacks(poll)
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (!ok || out == null || out.length() == 0L) {
            out?.delete()
            return null
        }
        return Result(out, ms, wave())
    }

    fun cancel() {
        val r = recorder ?: return
        recorder = null
        main.removeCallbacks(poll)
        runCatching { r.stop() }
        runCatching { r.release() }
        file?.delete()
        file = null
    }

    /** 64 bars of 8-bit loudness, scaled so that the loudest one is full. */
    private fun wave(): ByteArray {
        val bars = 64
        if (levels.isEmpty()) return ByteArray(bars) { 20 }
        val out = IntArray(bars)
        for (i in 0 until bars) {
            val a = (i.toLong() * levels.size / bars).toInt()
            val b = max(a + 1, ((i + 1).toLong() * levels.size / bars).toInt())
            var v = 0
            for (k in a until min(b, levels.size)) v = max(v, levels[k])
            out[i] = v
        }
        val peak = max(1, out.max())
        return ByteArray(bars) { (max(14, out[it] * 255 / peak)).toByte() }
    }
}

/** The bars of a voice message; the part already played is drawn in the accent colour. */
class WaveView(ctx: Context) : View(ctx) {
    var bars: ByteArray = ByteArray(0)
        set(v) {
            field = v
            invalidate()
        }
    var progress = 0f
        set(v) {
            field = v
            invalidate()
        }
    var played = Color.WHITE
    var rest = Color.GRAY
    /** Called with 0..1 when the wave is tapped. */
    var onSeek: ((Float) -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && width > 0) {
            onSeek?.invoke((e.x / width).coerceIn(0f, 1f))
            performClick()
        }
        return super.onTouchEvent(e) || e.actionMasked == MotionEvent.ACTION_DOWN
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val step = 5 * d
        val n = max(8, (width / step).toInt())
        val h = height.toFloat()
        for (i in 0 until n) {
            var v = 40
            if (bars.isNotEmpty()) {
                val a = (i.toLong() * bars.size / n).toInt()
                val b = max(a + 1, ((i + 1).toLong() * bars.size / n).toInt())
                v = 0
                for (k in a until min(b, bars.size)) v = max(v, bars[k].toInt() and 0xFF)
            }
            val bh = 4 * d + v / 255f * (h - 4 * d)
            paint.color = if ((i + 0.5f) / n <= progress) played else (rest and 0x00FFFFFF) or (0x96 shl 24)
            val x = i * step
            c.drawRoundRect(RectF(x, (h - bh) / 2, x + 3 * d, (h + bh) / 2), 1.5f * d, 1.5f * d, paint)
        }
    }
}

/**
 * A circle in a bubble: the first picture of the video cut to a round, a play mark and the length;
 * while it plays, the picture comes from a texture and a ring runs round the edge.
 */
class CircleView(ctx: Context) : FrameLayout(ctx) {
    private val poster = ImageView(ctx)
    private var texture: TextureView? = null
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val triangle = Path()
    var boundId = ""
    var ringColor = Color.WHITE
    var lengthMs = 0L
    var state = Playback.State.IDLE
        set(v) {
            field = v
            if (v == Playback.State.IDLE) releaseTexture()
            invalidate()
        }
    var progress = 0f
        set(v) {
            field = v
            invalidate()
        }
    var positionMs = 0L

    init {
        poster.scaleType = ImageView.ScaleType.CENTER_CROP
        poster.setBackgroundColor(0xFF101722.toInt())
        addView(poster, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height)
        }
        clipToOutline = true
        setWillNotDraw(false)
        isClickable = true
    }

    fun setPoster(bmp: Bitmap?) = poster.setImageBitmap(bmp)

    /** Gives the player a surface to draw on. */
    fun surface(ready: (Surface) -> Unit) {
        val t = texture ?: TextureView(context).also {
            texture = it
            addView(it, 1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        if (t.isAvailable) return ready(Surface(t.surfaceTexture!!))
        t.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = ready(Surface(st))
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    /** The video is cut to a square in the middle: scale the texture so that it covers the circle. */
    fun fitVideo(videoWidth: Int, videoHeight: Int) {
        val t = texture ?: return
        if (videoWidth <= 0 || videoHeight <= 0 || width == 0) return
        val m = android.graphics.Matrix()
        val sx = if (videoWidth > videoHeight) videoWidth.toFloat() / videoHeight else 1f
        val sy = if (videoHeight > videoWidth) videoHeight.toFloat() / videoWidth else 1f
        m.setScale(sx, sy, width / 2f, height / 2f)
        t.setTransform(m)
    }

    private fun releaseTexture() {
        texture?.let { removeView(it) }
        texture = null
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        val d = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        if (state != Playback.State.PLAYING) {
            fill.color = 0x82000000.toInt()
            c.drawCircle(cx, cy, 26 * d, fill)
            fill.color = Color.WHITE
            if (state == Playback.State.LOADING) {
                ring.color = Color.WHITE
                ring.strokeWidth = 2.6f * d
                val sweep = (System.currentTimeMillis() % 900) / 900f * 360f
                c.drawArc(RectF(cx - 11 * d, cy - 11 * d, cx + 11 * d, cy + 11 * d), sweep, 100f, false, ring)
                postInvalidateOnAnimation()
            } else {
                triangle.rewind()
                triangle.moveTo(cx - 7 * d, cy - 12 * d)
                triangle.lineTo(cx + 13 * d, cy)
                triangle.lineTo(cx - 7 * d, cy + 12 * d)
                triangle.close()
                c.drawPath(triangle, fill)
            }
        }
        if (state == Playback.State.PLAYING || state == Playback.State.PAUSED) {
            ring.color = ringColor
            ring.strokeWidth = 3 * d
            c.drawArc(RectF(2 * d, 2 * d, width - 2 * d, height - 2 * d), -90f, progress * 360f, false, ring)
        }
        val shown = if (state == Playback.State.PLAYING || state == Playback.State.PAUSED) positionMs else lengthMs
        if (shown > 0 || lengthMs > 0) {
            val text = Videos.clock(shown)
            label.textSize = 12 * d
            val w = label.measureText(text)
            val left = 14 * d
            val top = height - 38 * d
            fill.color = 0x99000000.toInt()
            c.drawRoundRect(left, top, left + w + 16 * d, top + 22 * d, 11 * d, 11 * d, fill)
            c.drawText(text, left + 8 * d, top + 11 * d + (label.descent() - label.ascent()) / 2 - label.descent(), label)
        }
    }
}

/** One voice message or circle plays at a time; a voice runs at 1×, 1.5× or 2× with its pitch kept. */
object Playback {
    enum class State { IDLE, LOADING, PLAYING, PAUSED }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    private var player: MediaPlayer? = null
    private var circle: CircleView? = null
    private var token = 0
    var id = ""
        private set
    var state = State.IDLE
        private set
    var durationMs = 0
        private set
    var speed = 1f
        private set
    private var prefsLoaded = false

    private val tick = object : Runnable {
        override fun run() {
            if (state != State.PLAYING) return
            notifyChanged()
            main.postDelayed(this, 50)
        }
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    private fun notifyChanged() = listeners.toList().forEach { it() }

    fun stateOf(messageId: String) = if (messageId == id) state else State.IDLE
    val positionMs get() = if (player != null && state != State.LOADING) runCatching { player!!.currentPosition }.getOrDefault(0) else 0
    fun progressOf(messageId: String) = if (messageId == id && durationMs > 0 && state != State.IDLE) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    private fun loadSpeed(ctx: Context) {
        if (prefsLoaded) return
        prefsLoaded = true
        val s = ctx.getSharedPreferences("playback", Context.MODE_PRIVATE).getFloat("speed", 1f)
        speed = if (s >= 1.9f) 2f else if (s >= 1.4f) 1.5f else 1f
    }

    /** Starts, pauses or resumes a message. [target] is the round view of a circle. */
    fun toggle(ctx: Context, m: JSONObject, target: CircleView?, failed: (String) -> Unit) {
        loadSpeed(ctx)
        val mid = m.optString("id")
        if (mid == id && state == State.PLAYING) {
            player?.pause()
            state = State.PAUSED
            notifyChanged()
            return
        }
        if (mid == id && state == State.PAUSED) {
            player?.start()
            applySpeed()
            state = State.PLAYING
            main.post(tick)
            notifyChanged()
            return
        }
        if (mid == id && state == State.LOADING) return
        stop()
        id = mid
        state = State.LOADING
        circle = target
        durationMs = m.optJSONObject("file")?.optInt("duration") ?: 0
        val mine = ++token
        notifyChanged()
        CoreClient.call("download_file", JSONObject().put("message_id", mid)) { v, e ->
            if (mine != token) return@call
            if (e != null) {
                stop()
                return@call failed(e.message)
            }
            start(ctx, (v as JSONObject).optString("path"), mine, failed)
        }
    }

    private fun start(ctx: Context, path: String, mine: Int, failed: (String) -> Unit) {
        val mp = MediaPlayer()
        player = mp
        val fail = {
            if (mine == token) {
                stop()
                failed("Не удалось воспроизвести: на этом телефоне нет кодека для этого файла")
            }
        }
        try {
            mp.setDataSource(path)
            mp.setOnErrorListener { _, _, _ -> fail(); true }
            mp.setOnCompletionListener {
                if (mine == token) {
                    // A finished message is ready to be played again from the start.
                    stop()
                }
            }
            mp.setOnPreparedListener {
                if (mine != token) return@setOnPreparedListener
                durationMs = max(durationMs, mp.duration)
                mp.start()
                applySpeed()
                state = State.PLAYING
                circle?.fitVideo(mp.videoWidth, mp.videoHeight)
                main.post(tick)
                notifyChanged()
            }
            val target = circle
            if (target != null) target.surface { s -> if (mine == token) { mp.setSurface(s); mp.prepareAsync() } } else mp.prepareAsync()
        } catch (_: Exception) {
            fail()
        }
    }

    private fun applySpeed() {
        val mp = player ?: return
        if (circle != null || Build.VERSION.SDK_INT < 23) return
        runCatching { mp.playbackParams = PlaybackParams().setSpeed(speed) }
    }

    fun seek(messageId: String, fraction: Float) {
        if (messageId != id || state == State.LOADING || state == State.IDLE) return
        player?.seekTo((fraction.coerceIn(0f, 0.999f) * durationMs).toInt())
        notifyChanged()
    }

    fun cycleSpeed(ctx: Context) {
        loadSpeed(ctx)
        speed = if (speed < 1.4f) 1.5f else if (speed < 1.9f) 2f else 1f
        ctx.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().putFloat("speed", speed).apply()
        if (state == State.PLAYING) applySpeed()
        notifyChanged()
    }

    fun stop() {
        token++
        main.removeCallbacks(tick)
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        state = State.IDLE
        val c = circle
        circle = null
        id = ""
        c?.state = State.IDLE
        notifyChanged()
    }
}
