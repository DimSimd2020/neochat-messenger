package app.neochat.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import app.neochat.R
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen camera for photos: the picture is the whole screen, two fingers spread or pinch
 * to zoom, a tap focuses, and the only control is the shutter. A taken photo is shown for a
 * look; "Отправить" hands the file back, "Переснять" returns to the viewfinder. Location and
 * other metadata are not kept: the saved JPEG is upright and plain.
 */
class PhotoCamera(private val act: MainActivity, private val onPhoto: (File) -> Unit) {
    private val ctx: Context = act
    private val root = FrameLayout(ctx)
    private val preview = TextureView(ctx)
    private val focusRing = FocusRing(ctx)
    private val zoomLabel = ctx.label("1×", 15f, 600)
    private val shutter = ShutterButton(ctx)
    private val review = ImageView(ctx)
    private val reviewBar = LinearLayout(ctx)
    private val worker = HandlerThread("photo-camera").apply { start() }
    private val bg = Handler(worker.looper)
    private val main = Handler(Looper.getMainLooper())

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewRequest: CaptureRequest.Builder? = null
    private var jpeg: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null
    private var previewSize = android.util.Size(1280, 960)
    private var sensorOrientation = 90
    private var active = Rect()
    private var zoom = 1f
    private var maxZoom = 1f
    private var viewScaleX = 1f
    private var viewScaleY = 1f
    private var shot: File? = null
    private var closed = false
    private val back: () -> Boolean = { if (review.visibility == View.VISIBLE) retake() else dismiss(); true }
    private val hideZoom = Runnable { zoomLabel.animate().alpha(0f).setDuration(Theme.ms(240)).start() }

    init {
        root.setBackgroundColor(Color.BLACK)
        root.isClickable = true
        root.addView(preview, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(focusRing, FrameLayout.LayoutParams(ctx.dp(72), ctx.dp(72)))
        zoomLabel.setTextColor(Color.WHITE)
        zoomLabel.alpha = 0f
        zoomLabel.setPadding(ctx.dp(14), ctx.dp(6), ctx.dp(14), ctx.dp(6))
        zoomLabel.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.argb(110, 0, 0, 0))
            cornerRadius = ctx.dpf(16)
        }
        root.addView(zoomLabel, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = act.bottomInset + ctx.dp(132)
        })
        shutter.contentDescription = "Сделать снимок"
        shutter.setOnClickListener { shoot() }
        root.addView(shutter, FrameLayout.LayoutParams(ctx.dp(84), ctx.dp(84), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = act.bottomInset + ctx.dp(32)
        })
        val close = IconButton(ctx, R.drawable.ic_close, "Закрыть камеру", glass = true).apply { setOnClickListener { dismiss() } }
        root.addView(close, FrameLayout.LayoutParams(ctx.dp(48), ctx.dp(48), Gravity.TOP or Gravity.START).apply {
            setMargins(ctx.dp(16), act.topInset + ctx.dp(12), 0, 0)
        })
        review.setBackgroundColor(Color.BLACK)
        review.scaleType = ImageView.ScaleType.FIT_CENTER
        review.visibility = View.GONE
        review.isClickable = true
        root.addView(review, FrameLayout.LayoutParams(MATCH, MATCH))
        reviewBar.gravity = Gravity.CENTER
        reviewBar.visibility = View.GONE
        val again = PillButton(ctx, "Переснять", primary = false)
        again.setOnClickListener { retake() }
        val send = PillButton(ctx, "Отправить")
        send.setOnClickListener { send() }
        reviewBar.addView(again, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = ctx.dp(10) })
        reviewBar.addView(send, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(reviewBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
            setMargins(ctx.dp(20), 0, ctx.dp(20), act.bottomInset + ctx.dp(28))
        })

        val pinch = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                setZoom(zoom * d.scaleFactor)
                return true
            }
        })
        val tap = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }
        })
        preview.setOnTouchListener { _, e ->
            pinch.onTouchEvent(e)
            if (!pinch.isInProgress) tap.onTouchEvent(e)
            true
        }
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = open(st)
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = fitPreview()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    fun show() {
        act.overlay.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        act.pushBack(back)
        act.camera = this
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(Theme.ms(180)).start()
    }

    fun dismiss() {
        if (closed) return
        closed = true
        act.popBack(back)
        if (act.camera === this) act.camera = null
        shot?.delete()
        release()
        main.removeCallbacks(hideZoom)
        root.animate().alpha(0f).setDuration(Theme.ms(180)).withEndAction { act.overlay.removeView(root) }.start()
    }

    private fun release() {
        try { session?.close() } catch (_: Exception) {}
        camera?.close()
        session = null
        camera = null
        bg.post {
            jpeg?.close()
            jpeg = null
            worker.quitSafely()
        }
    }

    private fun fail(text: String) {
        if (closed) return
        act.toast(text)
        dismiss()
    }

    // ---- camera ----

    @SuppressLint("MissingPermission")
    private fun open(st: SurfaceTexture) {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = try {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: cm.cameraIdList.firstOrNull()
        } catch (_: Exception) { null }
        if (id == null) return fail("Камера недоступна")
        val ch = cm.getCameraCharacteristics(id)
        characteristics = ch
        sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return fail("Камера недоступна")
        // 4:3 up to 12 megapixels for the photo; the viewfinder matches its proportions.
        val photos = map.getOutputSizes(ImageFormat.JPEG).filter { it.width.toLong() * it.height <= 12_000_000L }
        val photo = photos.filter { abs(it.width / it.height.toFloat() - 4f / 3) < 0.02f }.maxByOrNull { it.width * it.height }
            ?: photos.maxByOrNull { it.width * it.height } ?: return fail("Камера недоступна")
        val aspect = photo.width / photo.height.toFloat()
        val views = map.getOutputSizes(SurfaceTexture::class.java)
        previewSize = views.filter { abs(it.width / it.height.toFloat() - aspect) < 0.02f && it.width <= 1920 }.maxByOrNull { it.width * it.height }
            ?: views.minByOrNull { abs(it.width * it.height - 1280 * 720) } ?: android.util.Size(1280, 720)
        maxZoom = if (Build.VERSION.SDK_INT >= 30) ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.upper ?: 1f
        else ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        maxZoom = maxZoom.coerceIn(1f, 10f)
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        fitPreview()
        val r = ImageReader.newInstance(photo.width, photo.height, ImageFormat.JPEG, 2)
        r.setOnImageAvailableListener({ saved(it) }, bg)
        jpeg = r
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

    @Suppress("DEPRECATION")
    private fun startSession(d: CameraDevice, view: Surface, still: Surface) {
        d.createCaptureSession(listOf(view, still), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (closed) return s.close()
                session = s
                previewRequest = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(view)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
                repeat()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) = fail("Не удалось открыть камеру")
        }, main)
    }

    private fun repeat() {
        val b = previewRequest ?: return
        val s = session ?: return
        applyZoom(b)
        try { s.setRepeatingRequest(b.build(), null, main) } catch (_: Exception) {}
    }

    // ---- zoom and focus ----

    private fun cropFor(z: Float): Rect {
        val w = (active.width() / z).toInt()
        val h = (active.height() / z).toInt()
        val l = active.centerX() - w / 2
        val t = active.centerY() - h / 2
        return Rect(l, t, l + w, t + h)
    }

    private fun applyZoom(b: CaptureRequest.Builder) {
        if (Build.VERSION.SDK_INT >= 30) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        else b.set(CaptureRequest.SCALER_CROP_REGION, cropFor(zoom))
    }

    private fun setZoom(z: Float) {
        val next = z.coerceIn(1f, maxZoom)
        if (next == zoom) return
        zoom = next
        repeat()
        zoomLabel.text = String.format(java.util.Locale.US, "%.1f×", zoom)
        zoomLabel.animate().cancel()
        zoomLabel.alpha = 1f
        main.removeCallbacks(hideZoom)
        main.postDelayed(hideZoom, 900)
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

    /** A tap in the view, in the sensor's active array while it shows the cropped, rotated frame. */
    private fun focusAt(x: Float, y: Float) {
        val b = previewRequest ?: return
        val s = session ?: return
        if (preview.width == 0 || preview.height == 0 || review.visibility == View.VISIBLE) return
        focusRing.show(x, y)
        val u = (0.5f + (x / preview.width - 0.5f) / viewScaleX).coerceIn(0f, 1f)
        val v = (0.5f + (y / preview.height - 0.5f) / viewScaleY).coerceIn(0f, 1f)
        val (px, py) = when ((sensorOrientation - displayRotation() + 360) % 360) {
            90 -> v to 1 - u
            180 -> 1 - u to 1 - v
            270 -> 1 - v to u
            else -> u to v
        }
        val crop = cropFor(zoom)
        val cx = crop.left + (px * crop.width()).toInt()
        val cy = crop.top + (py * crop.height()).toInt()
        val half = max(active.width(), active.height()) / 14
        val area = Rect(max(active.left, cx - half), max(active.top, cy - half), min(active.right, cx + half), min(active.bottom, cy + half))
        val region = arrayOf(MeteringRectangle(area, MeteringRectangle.METERING_WEIGHT_MAX - 1))
        try {
            b.set(CaptureRequest.CONTROL_AF_REGIONS, region)
            b.set(CaptureRequest.CONTROL_AE_REGIONS, region)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            s.capture(b.build(), null, main)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            s.setRepeatingRequest(b.build(), null, main)
        } catch (_: Exception) {}
    }

    // ---- shooting ----

    private fun shoot() {
        val d = camera ?: return
        val s = session ?: return
        val r = jpeg ?: return
        shutter.isEnabled = false
        shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        try {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(r.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                applyZoom(this)
                set(CaptureRequest.JPEG_QUALITY, 92.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, (sensorOrientation - displayRotation() + 360) % 360)
            }
            s.capture(req.build(), null, main)
        } catch (_: Exception) {
            shutter.isEnabled = true
            act.toast("Не удалось сделать снимок")
        }
    }

    /** Runs on the camera thread: save the frame, make it upright and small enough to send. */
    private fun saved(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        val raw = File.createTempFile("shot", ".jpg", ctx.cacheDir)
        val result = try {
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            raw.writeBytes(bytes)
            val bmp = Photos.decode(raw.absolutePath, 2560) ?: error("decode")
            val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
            val out = File.createTempFile("photo", ".jpg", dir)
            out.outputStream().use { require(bmp.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
            out to bmp
        } catch (_: Exception) {
            null
        } finally {
            img.close()
            raw.delete()
        }
        main.post {
            if (closed) {
                result?.first?.delete()
                return@post
            }
            if (result == null) {
                shutter.isEnabled = true
                return@post act.toast("Не удалось сохранить снимок")
            }
            shot = result.first
            review.setImageBitmap(result.second)
            review.visibility = View.VISIBLE
            reviewBar.visibility = View.VISIBLE
            shutter.visibility = View.GONE
            zoomLabel.alpha = 0f
            review.announceForAccessibility("Снимок готов. Отправить или переснять")
        }
    }

    private fun retake() {
        shot?.delete()
        shot = null
        review.setImageDrawable(null)
        review.visibility = View.GONE
        reviewBar.visibility = View.GONE
        shutter.visibility = View.VISIBLE
        shutter.isEnabled = true
    }

    private fun send() {
        val f = shot ?: return
        shot = null
        dismiss()
        onPhoto(f)
    }

    // The camera buffer is landscape; crop it to fill the screen however the phone is held.
    private fun fitPreview() {
        val w = preview.width.toFloat()
        val h = preview.height.toFloat()
        if (w == 0f || h == 0f) return
        val upright = (sensorOrientation - displayRotation() + 360) % 180 == 90
        val content = if (upright) previewSize.height.toFloat() / previewSize.width else previewSize.width.toFloat() / previewSize.height
        val m = Matrix()
        if (w / h > content) {
            viewScaleX = 1f
            viewScaleY = (w / content) / h
        } else {
            viewScaleX = (h * content) / w
            viewScaleY = 1f
        }
        m.setScale(viewScaleX, viewScaleY, w / 2, h / 2)
        preview.setTransform(m)
    }

    /** Round shutter: a white ring with a disc that sinks a little while pressed. */
    private class ShutterButton(ctx: Context) : View(ctx) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = ctx.dpf(4)
        }
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private var press = 1f

        init {
            isClickable = true
            isFocusable = true
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> animatePress(0.86f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animatePress(1f)
            }
            return super.onTouchEvent(e)
        }

        private fun animatePress(to: Float) {
            if (Theme.reducedMotion) {
                press = to
                invalidate()
                return
            }
            android.animation.ValueAnimator.ofFloat(press, to).apply {
                duration = 90
                addUpdateListener { press = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            alpha = if (enabled) 1f else 0.5f
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            c.drawCircle(cx, cy, width / 2f - ring.strokeWidth / 2, ring)
            c.drawCircle(cx, cy, (width / 2f - dpf(10)) * press, disc)
        }
    }

    /** A ring that appears where the camera was told to focus, then fades. */
    private class FocusRing(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = ctx.dpf(2)
        }

        init {
            alpha = 0f
        }

        fun show(x: Float, y: Float) {
            translationX = x - width / 2f - left
            translationY = y - height / 2f - top
            animate().cancel()
            alpha = 1f
            scaleX = 1.3f
            scaleY = 1.3f
            val d = Theme.ms(220)
            animate().scaleX(1f).scaleY(1f).setDuration(d).withEndAction {
                animate().alpha(0f).setStartDelay(500).setDuration(Theme.ms(240)).start()
            }.start()
        }

        override fun onDraw(c: Canvas) {
            c.drawCircle(width / 2f, height / 2f, width / 2f - paint.strokeWidth, paint)
        }
    }
}
