package app.neochat.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import app.neochat.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlin.math.abs
import kotlin.math.min

/**
 * Full-screen camera view that reads a QR code with the given prefix and hands its text back.
 * Frames are decoded on a background thread and never leave the device.
 */
class QrScanner(
    private val act: MainActivity,
    private val prefix: String,
    hintText: String = "Наведите камеру на QR-код на экране входа компьютера",
    private val wrongText: String = "Это не QR-код входа NeoChat",
    private val onResult: (String) -> Unit,
) {
    private val ctx: Context = act
    private val root = FrameLayout(ctx)
    private val preview = TextureView(ctx)
    private val hint = ctx.label(hintText, 15f, 500)
    private val worker = HandlerThread("qr").apply { start() }
    private val bg = Handler(worker.looper)
    private val main = Handler(Looper.getMainLooper())
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var bufferSize = Size(1280, 720)
    private var sensorQuarterTurns = 1
    private var done = false
    private var closed = false
    private val back: () -> Boolean = { dismiss(); true }
    private val decoder = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }

    init {
        root.setBackgroundColor(Color.BLACK)
        root.isClickable = true
        root.addView(preview, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(Viewfinder(ctx), FrameLayout.LayoutParams(MATCH, MATCH))
        hint.setTextColor(Color.WHITE)
        hint.gravity = Gravity.CENTER
        root.addView(hint, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
            setMargins(ctx.dp(32), 0, ctx.dp(32), act.bottomInset + ctx.dp(72))
        })
        val close = IconButton(ctx, R.drawable.ic_close, "Закрыть сканер", glass = true).apply { setOnClickListener { dismiss() } }
        root.addView(close, FrameLayout.LayoutParams(ctx.dp(48), ctx.dp(48), Gravity.TOP or Gravity.END).apply {
            setMargins(0, act.topInset + ctx.dp(12), ctx.dp(16), 0)
        })
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
        act.scanner = this
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(Theme.ms(180)).start()
        root.announceForAccessibility(hint.text)
    }

    fun dismiss() {
        if (closed) return
        closed = true
        act.popBack(back)
        if (act.scanner === this) act.scanner = null
        release()
        root.animate().alpha(0f).setDuration(Theme.ms(180)).withEndAction { act.overlay.removeView(root) }.start()
    }

    private fun release() {
        try { session?.close() } catch (_: Exception) {}
        camera?.close()
        session = null
        camera = null
        bg.post {
            reader?.close()
            reader = null
            worker.quitSafely()
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(st: SurfaceTexture) {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = try {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: cm.cameraIdList.firstOrNull()
        } catch (_: Exception) { null }
        if (id == null) return fail("Камера недоступна")
        val ch = cm.getCameraCharacteristics(id)
        sensorQuarterTurns = (ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) / 90
        val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
        // About 720p: enough detail for a phone-screen QR without slowing decoding down.
        bufferSize = sizes.minByOrNull { abs(it.width * it.height - 1280 * 720) } ?: Size(1280, 720)
        st.setDefaultBufferSize(bufferSize.width, bufferSize.height)
        fitPreview()
        val r = ImageReader.newInstance(bufferSize.width, bufferSize.height, ImageFormat.YUV_420_888, 2)
        r.setOnImageAvailableListener({ decode(it) }, bg)
        reader = r
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
    private fun startSession(d: CameraDevice, view: Surface, frames: Surface) {
        d.createCaptureSession(listOf(view, frames), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (closed) return s.close()
                session = s
                val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(view)
                    addTarget(frames)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
                try { s.setRepeatingRequest(req.build(), null, main) } catch (_: Exception) { fail("Не удалось открыть камеру") }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) = fail("Не удалось открыть камеру")
        }, main)
    }

    private fun decode(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            if (done) return
            val plane = img.planes[0]
            val stride = plane.rowStride
            val buf = plane.buffer
            val data = ByteArray(stride * img.height)
            buf.get(data, 0, min(buf.remaining(), data.size))
            val src = PlanarYUVLuminanceSource(data, stride, img.height, 0, 0, img.width, img.height, false)
            val text = try {
                decoder.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text
            } catch (_: NotFoundException) { null } finally { decoder.reset() }
            if (text != null && text.startsWith(prefix)) {
                done = true
                main.post {
                    if (closed) return@post
                    root.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                    dismiss()
                    onResult(text)
                }
            } else if (text != null) {
                main.post { hint.text = wrongText }
            }
        } catch (_: Exception) {
        } finally {
            img.close()
        }
    }

    // The camera buffer arrives landscape and TextureView stretches it; crop it to fill instead.
    private fun fitPreview() {
        val w = preview.width.toFloat()
        val h = preview.height.toFloat()
        if (w == 0f || h == 0f) return
        val portrait = sensorQuarterTurns % 2 == 1
        val content = if (portrait) bufferSize.height.toFloat() / bufferSize.width else bufferSize.width.toFloat() / bufferSize.height
        val m = Matrix()
        if (w / h > content) m.setScale(1f, (w / content) / h, w / 2, h / 2)
        else m.setScale((h * content) / w, 1f, w / 2, h / 2)
        preview.setTransform(m)
    }

    private fun fail(text: String) {
        if (closed) return
        act.toast(text)
        dismiss()
    }

    /** Dims everything outside a rounded square so people know where to aim. */
    private class Viewfinder(ctx: Context) : View(ctx) {
        private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 0, 0, 0) }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = ctx.dpf(3)
        }
        private val hole = Path()
        private val rect = RectF()

        override fun onDraw(c: Canvas) {
            val side = min(width, height) * 0.68f
            rect.set((width - side) / 2, (height - side) / 2 - dpf(24), (width + side) / 2, (height + side) / 2 - dpf(24))
            hole.reset()
            hole.fillType = Path.FillType.EVEN_ODD
            hole.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            hole.addRoundRect(rect, dpf(28), dpf(28), Path.Direction.CW)
            c.drawPath(hole, dim)
            c.drawRoundRect(rect, dpf(28), dpf(28), edge)
        }
    }
}
