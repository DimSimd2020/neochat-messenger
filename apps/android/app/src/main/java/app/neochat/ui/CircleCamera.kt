package app.neochat.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.neochat.R
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/**
 * Records a circle: a full-screen window with a round viewfinder (the front camera first), one button
 * that starts and stops, a ring that fills up to 60 seconds. The file is H.264 + AAC in MP4; receivers
 * show its middle as a circle.
 */
class CircleCamera(private val act: MainActivity, private val onCircle: (File, Long, VideoInfo?) -> Unit) {
    private val ctx: Context = act
    private val root = FrameLayout(ctx)
    private val preview = TextureView(ctx)
    private val ring = RingView(ctx)
    private val time = ctx.label("0:00", 18f, 600)
    private val record = RecordButton(ctx)
    private val main = Handler(Looper.getMainLooper())

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var size = Size(640, 480)
    private var sensorOrientation = 270
    private var front = true
    private var recording = false
    private var startedAt = 0L
    private var closed = false
    private var finished = false
    private val back: () -> Boolean = { dismiss(); true }

    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            val ms = android.os.SystemClock.elapsedRealtime() - startedAt
            ring.progress = min(1f, ms / MAX_MS.toFloat())
            time.text = Videos.clock(ms)
            main.postDelayed(this, 50)
        }
    }

    init {
        root.setBackgroundColor(0xF2000000.toInt())
        root.isClickable = true
        val side = min(ctx.resources.displayMetrics.widthPixels - ctx.dp(48), ctx.dp(340))
        val round = FrameLayout(ctx)
        round.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height)
        }
        round.clipToOutline = true
        round.setBackgroundColor(0xFF101722.toInt())
        round.addView(preview, FrameLayout.LayoutParams(MATCH, MATCH))
        val holder = FrameLayout(ctx)
        holder.addView(round, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        holder.addView(ring, FrameLayout.LayoutParams(side + ctx.dp(16), side + ctx.dp(16), Gravity.CENTER))
        preview.contentDescription = "Изображение с камеры"
        root.addView(holder, FrameLayout.LayoutParams(side + ctx.dp(16), side + ctx.dp(16), Gravity.CENTER).apply { bottomMargin = ctx.dp(40) })
        time.setTextColor(Color.WHITE)
        root.addView(time, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = act.topInset + ctx.dp(28) })
        record.contentDescription = "Начать запись"
        record.setOnClickListener { if (recording) stop() else start() }
        root.addView(record, FrameLayout.LayoutParams(ctx.dp(84), ctx.dp(84), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = act.bottomInset + ctx.dp(32)
        })
        val close = IconButton(ctx, R.drawable.ic_close, "Закрыть камеру", glass = true).apply { setOnClickListener { dismiss() } }
        root.addView(close, FrameLayout.LayoutParams(ctx.dp(48), ctx.dp(48), Gravity.TOP or Gravity.START).apply {
            setMargins(ctx.dp(16), act.topInset + ctx.dp(12), 0, 0)
        })
        val flip = IconButton(ctx, R.drawable.ic_camera_switch, "Переключить камеру", glass = true).apply { setOnClickListener { switchCamera() } }
        root.addView(flip, FrameLayout.LayoutParams(ctx.dp(48), ctx.dp(48), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, ctx.dp(24), act.bottomInset + ctx.dp(50))
        })
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surfaceTexture = st
                open()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = fitPreview()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    fun show() {
        act.overlay.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        act.pushBack(back)
        act.circleCamera = this
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(Theme.ms(180)).start()
    }

    fun dismiss() {
        if (closed) return
        closed = true
        act.popBack(back)
        if (act.circleCamera === this) act.circleCamera = null
        main.removeCallbacks(tick)
        releaseAll(deleteFile = !finished)
        root.animate().alpha(0f).setDuration(Theme.ms(180)).withEndAction { act.overlay.removeView(root) }.start()
    }

    private fun fail(text: String) {
        if (closed) return
        act.toast(text)
        dismiss()
    }

    // ---- camera ----

    private fun cameraId(cm: CameraManager): String? = try {
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want } ?: cm.cameraIdList.firstOrNull()
    } catch (_: Exception) {
        null
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        val st = surfaceTexture ?: return
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cameraId(cm) ?: return fail("Камера недоступна")
        val ch = cm.getCameraCharacteristics(id)
        sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
        front = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return fail("Камера недоступна")
        // The smallest 4:3 size whose short side is at least 480 and that both the viewfinder and the recorder offer.
        val views = map.getOutputSizes(SurfaceTexture::class.java).toSet()
        val films = map.getOutputSizes(MediaRecorder::class.java).filter { it in views && min(it.width, it.height) >= 480 && it.width <= 1280 }
        size = films.filter { abs(it.width / it.height.toFloat() - 4f / 3) < 0.02f }.minByOrNull { it.width * it.height }
            ?: films.minByOrNull { it.width * it.height } ?: return fail("Эта камера не умеет снимать видео")
        st.setDefaultBufferSize(size.width, size.height)
        fitPreview()
        val out = File.createTempFile("circle", ".mp4", File(ctx.cacheDir, "voice").apply { mkdirs() })
        file = out
        val r = try {
            prepareRecorder(out)
        } catch (_: Exception) {
            return fail("Не удалось подготовить запись")
        }
        recorder = r
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (closed) return d.close()
                    camera = d
                    startSession(d, Surface(st), r.surface)
                }
                override fun onDisconnected(d: CameraDevice) = d.close()
                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    main.post { fail("Не удалось открыть камеру") }
                }
            }, main)
        } catch (_: Exception) {
            fail("Не удалось открыть камеру")
        }
    }

    private fun displayRotation(): Int {
        @Suppress("DEPRECATION")
        val r = (act.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        return when (r) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun prepareRecorder(out: File): MediaRecorder {
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setOutputFile(out.absolutePath)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(size.width, size.height)
        r.setVideoFrameRate(30)
        r.setVideoEncodingBitRate(1_500_000)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(44100)
        r.setAudioEncodingBitRate(64000)
        r.setMaxDuration(MAX_MS)
        r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) main.post { if (recording) stop() } }
        val hint = if (front) (sensorOrientation + displayRotation()) % 360 else (sensorOrientation - displayRotation() + 360) % 360
        r.setOrientationHint(hint)
        r.prepare()
        return r
    }

    @Suppress("DEPRECATION")
    private fun startSession(d: CameraDevice, view: Surface, film: Surface) {
        d.createCaptureSession(listOf(view, film), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (closed) return s.close()
                session = s
                val req = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(view)
                    addTarget(film)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
                try { s.setRepeatingRequest(req.build(), null, main) } catch (_: Exception) { fail("Не удалось открыть камеру") }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) = fail("Не удалось открыть камеру")
        }, main)
    }

    private fun switchCamera() {
        if (recording || closed) return
        front = !front
        releaseAll(deleteFile = true)
        open()
    }

    private fun releaseAll(deleteFile: Boolean) {
        try { session?.close() } catch (_: Exception) {}
        camera?.close()
        session = null
        camera = null
        recorder?.let { r ->
            if (recording) runCatching { r.stop() }
            runCatching { r.release() }
        }
        recorder = null
        recording = false
        if (deleteFile) file?.delete()
        file = null
    }

    // ---- recording ----

    private fun start() {
        val r = recorder ?: return
        if (session == null) return
        try {
            r.start()
        } catch (_: Exception) {
            return act.toast("Не удалось начать запись")
        }
        recording = true
        startedAt = android.os.SystemClock.elapsedRealtime()
        record.recording = true
        record.contentDescription = "Остановить и отправить"
        record.announceForAccessibility("Запись идёт")
        main.post(tick)
    }

    private fun stop() {
        val r = recorder ?: return
        val out = file ?: return
        val ms = android.os.SystemClock.elapsedRealtime() - startedAt
        recording = false
        main.removeCallbacks(tick)
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        recorder = null
        if (!ok || ms < 600) {
            out.delete()
            file = null
            act.toast("Видеосообщение слишком короткое")
            dismiss()
            return
        }
        finished = true
        file = null
        dismiss()
        Thread {
            val info = Videos.info(out.absolutePath)
            act.runOnUiThread { onCircle(out, ms, info) }
        }.start()
    }

    // The camera buffer is landscape; cover the round viewfinder however the phone is held, mirrored for the front camera.
    private fun fitPreview() {
        val w = preview.width.toFloat()
        val h = preview.height.toFloat()
        if (w == 0f || h == 0f) return
        val upright = (sensorOrientation - displayRotation() + 360) % 180 == 90
        val content = if (upright) size.height.toFloat() / size.width else size.width.toFloat() / size.height
        val m = Matrix()
        val sx: Float
        val sy: Float
        if (w / h > content) {
            sx = 1f
            sy = (w / content) / h
        } else {
            sx = (h * content) / w
            sy = 1f
        }
        m.setScale(if (front) -sx else sx, sy, w / 2, h / 2)
        preview.setTransform(m)
    }

    private class RingView(ctx: Context) : View(ctx) {
        var progress = 0f
            set(v) {
                field = v
                invalidate()
            }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = ctx.dpf(4)
            color = 0xFFE04040.toInt()
        }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ctx.dpf(2)
            color = 0x40FFFFFF
        }

        init {
            isClickable = false
        }

        override fun onDraw(c: Canvas) {
            val pad = ctx().dpf(6)
            val r = RectF(pad, pad, width - pad, height - pad)
            c.drawOval(r, track)
            if (progress > 0f) c.drawArc(r, -90f, progress * 360f, false, paint)
        }

        private fun ctx() = context
    }

    /** A white ring with a red disc that turns into a rounded square while recording. */
    private class RecordButton(ctx: Context) : View(ctx) {
        var recording = false
            set(v) {
                field = v
                invalidate()
            }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = ctx.dpf(4)
        }
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE04040.toInt() }

        init {
            isClickable = true
            isFocusable = true
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            c.drawCircle(cx, cy, width / 2f - ring.strokeWidth / 2, ring)
            if (recording) {
                val half = dpf(16)
                c.drawRoundRect(cx - half, cy - half, cx + half, cy + half, dpf(6), dpf(6), disc)
            } else {
                c.drawCircle(cx, cy, width / 2f - dpf(10), disc)
            }
        }
    }

    companion object {
        const val MAX_MS = 60_000
    }
}
