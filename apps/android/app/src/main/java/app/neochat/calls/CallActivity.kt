package app.neochat.calls

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.neochat.ui.weightFace
import app.neochat.R
import app.neochat.ui.AvatarView
import app.neochat.ui.Palette
import app.neochat.ui.Theme
import app.neochat.ui.dp
import app.neochat.ui.dpf
import app.neochat.ui.flp
import app.neochat.ui.lp
import app.neochat.ui.str
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/** Full-screen call: always dark, the peer's video covers it when their camera is on. */
class CallActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val dark = Palette.DARK
    private lateinit var remote: SurfaceViewRenderer
    private lateinit var local: SurfaceViewRenderer
    private lateinit var avatar: AvatarView
    private lateinit var pulse: PulseView
    private lateinit var center: LinearLayout
    private lateinit var name: TextView
    private lateinit var status: TextView
    private lateinit var top: LinearLayout
    private lateinit var topName: TextView
    private lateinit var topStatus: TextView
    private lateinit var controls: LinearLayout
    private lateinit var flip: ImageView
    private lateinit var mic: CallButton
    private lateinit var camera: CallButton
    private lateinit var speaker: CallButton
    private lateinit var end: CallButton
    private lateinit var decline: CallButton
    private lateinit var accept: CallButton
    private lateinit var acceptVideo: CallButton
    private var pendingVideo = false
    private val listener: () -> Unit = { sync() }
    private val tick = object : Runnable {
        override fun run() {
            sync()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        build()
        handle(intent)
    }

    private fun build() {
        val root = FrameLayout(this)
        root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(dark.bg0, dark.bg1))
        remote = SurfaceViewRenderer(this).apply {
            init(CallManager.egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            visibility = View.GONE
        }
        root.addView(remote, flp(MATCH, MATCH))

        center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val avatarBox = FrameLayout(this)
        pulse = PulseView(this, dark.accent)
        avatarBox.addView(pulse, flp(dp(220), dp(220), Gravity.CENTER))
        avatar = AvatarView(this, 120)
        avatarBox.addView(avatar, flp(dp(120), dp(120), Gravity.CENTER))
        center.addView(avatarBox, lp(dp(220), dp(220)))
        name = text(26f, true, dark.text).apply { gravity = Gravity.CENTER }
        center.addView(name, lp(MATCH, WRAP).apply { setMargins(dp(24), dp(4), dp(24), 0) })
        status = text(16f, false, dark.muted).apply { gravity = Gravity.CENTER }
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        center.addView(status, lp(MATCH, WRAP).apply { setMargins(dp(24), dp(6), dp(24), 0) })
        root.addView(center, flp(MATCH, WRAP, Gravity.CENTER).apply { bottomMargin = dp(120) })

        top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(150, 0, 0, 0), Color.TRANSPARENT))
            setPadding(dp(20), dp(16), dp(72), dp(36))
            visibility = View.GONE
        }
        topName = text(19f, true, Color.WHITE)
        topStatus = text(14f, false, 0xFFD3DEF0.toInt())
        top.addView(topName)
        top.addView(topStatus)
        root.addView(top, flp(MATCH, WRAP, Gravity.TOP))

        flip = ImageView(this).apply {
            setImageResource(R.drawable.ic_camera_switch)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(70, 0, 0, 0)) }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "Сменить камеру"
            setOnClickListener { CallManager.switchCamera() }
            visibility = View.GONE
        }
        root.addView(flip, flp(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(16), dp(16), 0) })

        local = SurfaceViewRenderer(this).apply {
            init(CallManager.egl.eglBaseContext, null)
            setZOrderMediaOverlay(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            visibility = View.GONE
        }
        root.addView(local, flp(dp(108), dp(144), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(150)) })

        controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        mic = CallButton(this, R.drawable.ic_mic, "Микрофон", CallButton.Kind.PLAIN) { CallManager.setMic(!CallManager.micOn) }
        camera = CallButton(this, R.drawable.ic_video_off, "Камера", CallButton.Kind.PLAIN) { toggleCamera() }
        speaker = CallButton(this, R.drawable.ic_speaker, "Динамик", CallButton.Kind.PLAIN) { CallManager.setSpeaker(!CallManager.speakerOn) }
        end = CallButton(this, R.drawable.ic_hangup, "Завершить", CallButton.Kind.DANGER) { CallManager.hangup() }
        decline = CallButton(this, R.drawable.ic_hangup, "Отклонить", CallButton.Kind.DANGER) { CallManager.hangup() }
        accept = CallButton(this, R.drawable.ic_phone, "Ответить", CallButton.Kind.ACCEPT) { answer(false) }
        acceptVideo = CallButton(this, R.drawable.ic_video, "С видео", CallButton.Kind.ACCEPT) { answer(true) }
        for (b in listOf(mic, camera, speaker, end, decline, acceptVideo, accept)) controls.addView(b, lp(0, WRAP, 1f))
        root.addView(controls, flp(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(36)) })

        root.setOnApplyWindowInsetsListener { _, ins ->
            val (t, b) = bars(ins)
            top.setPadding(dp(20), dp(16) + t, dp(72), dp(36))
            (flip.layoutParams as FrameLayout.LayoutParams).topMargin = dp(16) + t
            (controls.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(36) + b
            (local.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(150) + b
            root.requestLayout()
            ins
        }
        setContentView(root)
    }

    private fun text(sp: Float, bold: Boolean, color: Int) = TextView(this).apply {
        textSize = sp
        setTextColor(color)
        if (bold) typeface = weightFace(600)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ANSWER, false) == true) answer(false)
    }

    // ---- permissions ----

    private fun answer(video: Boolean) {
        pendingVideo = video
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (video) need += Manifest.permission.CAMERA
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) CallManager.accept(video) else requestPermissions(missing.toTypedArray(), REQ_ANSWER)
    }

    private fun toggleCamera() {
        if (CallManager.cameraOn) return CallManager.setCamera(false)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) CallManager.setCamera(true)
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val granted = permissions.indices.associate { permissions[it] to (grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED) }
        when (requestCode) {
            REQ_ANSWER -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    return android.widget.Toast.makeText(this, "Чтобы ответить, разрешите доступ к микрофону", android.widget.Toast.LENGTH_LONG).show()
                CallManager.accept(pendingVideo && granted[Manifest.permission.CAMERA] != false)
            }
            REQ_CAMERA -> if (granted[Manifest.permission.CAMERA] == true) CallManager.setCamera(true)
        }
    }

    // ---- state ----

    override fun onStart() {
        super.onStart()
        CallManager.addListener(listener)
        CallManager.remoteSink.target = remote
        CallManager.localSink.target = local
        CallNotifications.cancelIncoming(this)
        main.post(tick)
    }

    override fun onStop() {
        CallManager.removeListener(listener)
        CallManager.remoteSink.target = null
        CallManager.localSink.target = null
        main.removeCallbacks(tick)
        pulse.stop()
        // Leaving a ringing call: the notification keeps "Ответить" reachable.
        if (CallManager.phase == CallManager.Phase.INCOMING && !isFinishing) CallNotifications.showIncoming(this)
        super.onStop()
    }

    override fun onDestroy() {
        if (CallManager.phase == CallManager.Phase.INCOMING) CallNotifications.showIncoming(this)
        remote.release()
        local.release()
        super.onDestroy()
    }

    private fun statusText(): String {
        val video = CallManager.call.optBoolean("video")
        return when (CallManager.phase) {
            CallManager.Phase.STARTING -> "Подготовка звонка…"
            CallManager.Phase.OUTGOING -> "Вызов…"
            CallManager.Phase.INCOMING -> if (video) "Входящий видеозвонок" else "Входящий звонок"
            CallManager.Phase.CONNECTING -> "Соединение…"
            CallManager.Phase.ACTIVE -> if (CallManager.reconnecting) "Восстанавливаем связь…" else clock((System.currentTimeMillis() - CallManager.since) / 1000)
            CallManager.Phase.ENDED -> CallManager.endText
            CallManager.Phase.IDLE -> ""
        }
    }

    private fun clock(sec: Long): String {
        val s = sec.coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    private fun sync() {
        val ph = CallManager.phase
        if (ph == CallManager.Phase.IDLE) return finish()
        val call = CallManager.call
        val peer = call.str("peer_name")
        avatar.set(peer, call.str("peer_user_id"), call.str("avatar_path"))
        name.text = peer
        topName.text = peer
        val st = statusText()
        status.text = st
        topStatus.text = st
        val video = CallManager.remoteVideo
        remote.visibility = if (video) View.VISIBLE else View.GONE
        top.visibility = if (video) View.VISIBLE else View.GONE
        center.visibility = if (video) View.GONE else View.VISIBLE
        local.visibility = if (CallManager.cameraOn) View.VISIBLE else View.GONE
        local.setMirror(CallManager.frontCamera)
        flip.visibility = if (CallManager.cameraOn) View.VISIBLE else View.GONE
        val ringing = ph == CallManager.Phase.INCOMING
        val live = CallManager.live
        for (b in listOf(mic, camera, speaker, end)) b.visibility = if (live) View.VISIBLE else View.GONE
        decline.visibility = if (ringing) View.VISIBLE else View.GONE
        accept.visibility = if (ringing) View.VISIBLE else View.GONE
        acceptVideo.visibility = if (ringing && call.optBoolean("video")) View.VISIBLE else View.GONE
        mic.set(if (CallManager.micOn) R.drawable.ic_mic else R.drawable.ic_mic_off, if (CallManager.micOn) "Микрофон" else "Без звука",
            if (CallManager.micOn) "Выключить микрофон" else "Включить микрофон", !CallManager.micOn)
        camera.set(if (CallManager.cameraOn) R.drawable.ic_video else R.drawable.ic_video_off, "Камера",
            if (CallManager.cameraOn) "Выключить камеру" else "Включить камеру", CallManager.cameraOn)
        speaker.set(R.drawable.ic_speaker, "Динамик", if (CallManager.speakerOn) "Выключить динамик" else "Включить динамик", CallManager.speakerOn)
        val ring = (ringing || ph == CallManager.Phase.OUTGOING) && !Theme.reducedMotion
        if (ring) pulse.start() else pulse.stop()
    }

    /** Round button with a caption; a filled white circle marks a non-default state. */
    class CallButton(ctx: Context, icon: Int, caption: String, private val kind: Kind, onClick: () -> Unit) : LinearLayout(ctx) {
        enum class Kind { PLAIN, DANGER, ACCEPT }

        private val circle = ImageView(ctx)
        private val label = TextView(ctx)
        private val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            circle.background = bg
            circle.setPadding(dp(19), dp(19), dp(19), dp(19))
            addView(circle, LayoutParams(dp(64), dp(64)))
            label.textSize = 12.5f
            label.setTextColor(0xFFD3DEF0.toInt())
            label.gravity = Gravity.CENTER
            label.maxLines = 1
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            setOnClickListener { onClick() }
            set(icon, caption, caption, false)
        }

        fun set(icon: Int, caption: String, accessible: String, on: Boolean) {
            circle.setImageResource(icon)
            label.text = caption
            contentDescription = accessible
            bg.setColor(when (kind) {
                Kind.DANGER -> 0xFFE5484D.toInt()
                Kind.ACCEPT -> 0xFF2FA86A.toInt()
                Kind.PLAIN -> if (on) 0xFFF2F7FF.toInt() else Color.argb(46, 255, 255, 255)
            })
            circle.imageTintList = android.content.res.ColorStateList.valueOf(if (kind == Kind.PLAIN && on) 0xFF0F1A2E.toInt() else Color.WHITE)
        }
    }

    /** Two rings spreading from the avatar while the call rings (off with reduced motion). */
    class PulseView(ctx: Context, private val color: Int) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(2) }
        private var t = 0f
        private var anim: ValueAnimator? = null

        fun start() {
            if (anim != null) return
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1600
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun stop() {
            anim?.cancel()
            anim = null
            t = 0f
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            if (anim == null) return
            val base = dpf(60)
            for (off in floatArrayOf(0f, 0.5f)) {
                val p = (t + off) % 1f
                paint.color = color
                paint.alpha = (90 * (1 - p)).toInt()
                c.drawCircle(width / 2f, height / 2f, base + p * dpf(48), paint)
            }
        }
    }

    companion object {
        const val EXTRA_ANSWER = "answer"
        private const val REQ_ANSWER = 11
        private const val REQ_CAMERA = 12
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT

        /** Top and bottom system bar heights. */
        @Suppress("DEPRECATION")
        private fun bars(ins: android.view.WindowInsets): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= 30) {
            val s = ins.getInsets(android.view.WindowInsets.Type.systemBars())
            s.top to s.bottom
        } else ins.systemWindowInsetTop to ins.systemWindowInsetBottom
    }
}
