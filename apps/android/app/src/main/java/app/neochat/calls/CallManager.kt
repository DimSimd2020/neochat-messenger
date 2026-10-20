package app.neochat.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.neochat.App
import app.neochat.core.CoreClient
import app.neochat.ui.objects
import app.neochat.ui.str
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * One-to-one calls (core/src/calls.rs). Offer, answer and hangup travel as E2EE
 * messages through the core; media is WebRTC between the devices (DTLS-SRTP),
 * relayed through the server's TURN only when a direct path fails. Every call
 * negotiates audio and video, so the camera turns on and off without
 * renegotiation. All state lives on the main thread.
 */
object CallManager {
    enum class Phase { IDLE, STARTING, OUTGOING, INCOMING, CONNECTING, ACTIVE, ENDED }

    private val main = Handler(Looper.getMainLooper())
    private val app: Context get() = App.instance
    private val audio get() = app.getSystemService(AudioManager::class.java)

    var phase = Phase.IDLE
        private set
    var call = JSONObject()
        private set
    var micOn = true
        private set
    var cameraOn = false
        private set
    var frontCamera = true
        private set
    var speakerOn = false
        private set
    var peerCamera = false
        private set
    var reconnecting = false
        private set
    var since = 0L
        private set
    var endText = ""
        private set
    private var hangupPending = false
    private val listeners = mutableListOf<() -> Unit>()

    /** Short system toast: the call can run without any NeoChat screen open. */
    private fun say(text: String) = android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_SHORT).show()

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    val callId get() = call.str("call_id")
    val live get() = phase in setOf(Phase.STARTING, Phase.OUTGOING, Phase.CONNECTING, Phase.ACTIVE)

    private fun changed() {
        listeners.toList().forEach { it() }
        CallNotifications.update(app)
        updateProximity()
    }

    private fun setPhase(p: Phase) {
        phase = p
        if (live) CallService.start(app) else CallService.stop(app)
        changed()
    }

    // ---- WebRTC objects ----

    val egl: EglBase by lazy { EglBase.create() }
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var textures: SurfaceTextureHelper? = null
    private var videoSender: RtpSender? = null
    private var sentSdp = false
    private var gen = 0

    /** Renderers attach here; the call keeps running while no screen shows it. */
    class ProxySink : VideoSink {
        @Volatile var target: VideoSink? = null
        @Volatile var lastFrame = 0L
        override fun onFrame(frame: VideoFrame) {
            lastFrame = System.currentTimeMillis()
            target?.onFrame(frame)
        }
    }

    val localSink = ProxySink()
    val remoteSink = ProxySink()

    /** The peer's camera is on and frames are arriving. */
    val remoteVideo get() = peerCamera && System.currentTimeMillis() - remoteSink.lastFrame < 1500

    private fun factory(): PeerConnectionFactory = factory ?: run {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        val adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
            .also { factory = it }
    }

    private open class Sdp(private val gen: Int, private val created: ((SessionDescription) -> Unit)? = null, private val set: (() -> Unit)? = null) : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) { main.post { if (gen == CallManager.gen) created?.invoke(d) } }
        override fun onSetSuccess() { main.post { if (gen == CallManager.gen) set?.invoke() } }
        override fun onCreateFailure(e: String?) { main.post { if (gen == CallManager.gen) mediaFailed() } }
        override fun onSetFailure(e: String?) { main.post { if (gen == CallManager.gen) mediaFailed() } }
    }

    private class Observer(private val gen: Int) : PeerConnection.Observer {
        private fun post(f: () -> Unit) = main.post { if (gen == CallManager.gen) f() }
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
            if (s == PeerConnection.IceGatheringState.COMPLETE) post { sendLocal() }
        }
        override fun onIceCandidate(c: IceCandidate) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(d: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
        override fun onTrack(t: RtpTransceiver) {
            (t.receiver.track() as? VideoTrack)?.addSink(remoteSink)
        }
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
            post { connectionChanged(s) }
        }
    }

    private fun createMedia(ice: JSONArray, video: Boolean, offer: String?) {
        destroyMedia()
        val g = ++gen
        val servers = ice.objects().map { s ->
            val urls = s.optJSONArray("urls")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: listOf(s.str("urls"))
            PeerConnection.IceServer.builder(urls).setUsername(s.str("username")).setPassword(s.str("credential")).createIceServer()
        }
        val rtc = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val f = factory()
        val conn = f.createPeerConnection(rtc, Observer(g)) ?: return mediaFailed()
        pc = conn
        sentSdp = false
        audioSource = f.createAudioSource(MediaConstraints())
        audioTrack = f.createAudioTrack("audio", audioSource).apply { setEnabled(micOn) }
        startAudio()
        val init = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("neocall"))
        if (offer == null) {
            conn.addTransceiver(audioTrack, init)
            videoSender = conn.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, init).sender
            conn.createOffer(Sdp(g, created = { setLocal(g, it) }), MediaConstraints())
        } else {
            conn.setRemoteDescription(Sdp(g, set = { attachAndAnswer(g) }), SessionDescription(SessionDescription.Type.OFFER, offer))
        }
        if (video) setCamera(true)
    }

    private fun attachAndAnswer(g: Int) {
        val conn = pc ?: return
        for (t in conn.transceivers) {
            when (t.mediaType) {
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> t.sender.setTrack(audioTrack, false)
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> {
                    videoSender = t.sender
                    videoTrack?.let { t.sender.setTrack(it, false) }
                }
                else -> {}
            }
            t.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
        }
        conn.createAnswer(Sdp(g, created = { setLocal(g, it) }), MediaConstraints())
    }

    // Non-trickle: the description goes once gathering completes, or after
    // 4 s with whatever candidates were found by then.
    private fun setLocal(g: Int, d: SessionDescription) {
        val conn = pc ?: return
        conn.setLocalDescription(Sdp(g, set = {
            if (conn.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) sendLocal()
        }), d)
        main.postDelayed({ if (g == gen) sendLocal() }, 4000)
    }

    private fun sendLocal() {
        if (sentSdp) return
        val d = pc?.localDescription ?: return
        sentSdp = true
        val id = callId
        val outgoing = call.optBoolean("outgoing")
        CoreClient.call(if (outgoing) "call_offer" else "call_answer", JSONObject().put("call_id", id).put("sdp", d.description)) { _, e ->
            if (e == null || callId != id || phase == Phase.ENDED || phase == Phase.IDLE) return@call
            say(e.message)
            if (outgoing) finish("failed") else hangup()
        }
    }

    private fun connectionChanged(s: PeerConnection.PeerConnectionState) {
        when (s) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                main.removeCallbacks(dropTimer)
                reconnecting = false
                if (phase == Phase.CONNECTING) CoreClient.call("call_connected", JSONObject().put("call_id", callId))
                changed()
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> if (phase == Phase.ACTIVE) {
                reconnecting = true
                main.removeCallbacks(dropTimer)
                main.postDelayed(dropTimer, 12_000)
                changed()
            }
            PeerConnection.PeerConnectionState.FAILED -> mediaFailed()
            else -> {}
        }
    }

    /** ICE stayed disconnected (network switch): give up as a failed call. */
    private val dropTimer = Runnable { if (phase == Phase.ACTIVE || phase == Phase.CONNECTING) mediaFailed() }

    private fun mediaFailed() {
        val id = callId
        if (phase == Phase.IDLE || phase == Phase.ENDED) return
        CoreClient.call("call_hangup", JSONObject().put("call_id", id).put("reason", "failed")) { _, _ ->
            if (callId == id && phase != Phase.ENDED && phase != Phase.IDLE) finish("failed")
        }
    }

    private fun destroyMedia() {
        gen++
        main.removeCallbacks(dropTimer)
        stopCamera()
        videoSender = null
        pc?.dispose()
        pc = null
        audioTrack?.dispose()
        audioTrack = null
        audioSource?.dispose()
        audioSource = null
        remoteSink.lastFrame = 0
        stopAudio()
    }

    // ---- camera ----

    fun setCamera(on: Boolean) {
        if (on == cameraOn) return
        if (on) {
            if (pc == null) return
            if (app.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return say("Нет доступа к камере")
            val en = Camera2Enumerator(app)
            val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) } ?: en.deviceNames.firstOrNull()
            val cap = name?.let { en.createCapturer(it, null) } ?: return say("Камера не найдена")
            frontCamera = en.isFrontFacing(name)
            val helper = SurfaceTextureHelper.create("neochat-camera", egl.eglBaseContext)
            val src = factory().createVideoSource(false)
            cap.initialize(helper, app, src.capturerObserver)
            cap.startCapture(640, 480, 30)
            val track = factory().createVideoTrack("video", src)
            track.addSink(localSink)
            capturer = cap
            textures = helper
            videoSource = src
            videoTrack = track
            videoSender?.setTrack(track, false)
            cameraOn = true
            if (!speakerOn && !headsetConnected()) setSpeaker(true)
        } else {
            stopCamera()
        }
        sendCameraState()
        changed()
    }

    fun switchCamera() {
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) = main.post { frontCamera = front; changed() }.let {}
            override fun onCameraSwitchError(e: String?) {}
        })
    }

    private fun stopCamera() {
        videoSender?.setTrack(null, false)
        videoTrack?.removeSink(localSink)
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        capturer = null
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        textures?.dispose()
        textures = null
        cameraOn = false
    }

    private fun sendCameraState() {
        if (phase != Phase.ACTIVE && phase != Phase.CONNECTING) return
        CoreClient.call("call_media", JSONObject().put("call_id", callId).put("video", cameraOn))
    }

    fun setMic(on: Boolean) {
        micOn = on
        audioTrack?.setEnabled(on)
        changed()
    }

    // ---- audio routing and sounds ----

    private val headsets = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET,
    )

    private fun headsetConnected() = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headsets }

    private fun startAudio() {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(call.optBoolean("video") && !headsetConnected())
    }

    @Suppress("DEPRECATION")
    fun setSpeaker(on: Boolean) {
        speakerOn = on
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = audio.availableCommunicationDevices
            val pick = if (on) devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            else devices.firstOrNull { it.type in headsets } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            if (pick != null) audio.setCommunicationDevice(pick)
        } else {
            audio.isSpeakerphoneOn = on
        }
        changed()
    }

    @Suppress("DEPRECATION")
    private fun stopAudio() {
        if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice() else audio.isSpeakerphoneOn = false
        audio.mode = AudioManager.MODE_NORMAL
    }

    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null
    private val vibrator: Vibrator
        get() = if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") app.getSystemService(Vibrator::class.java)

    private fun ring() {
        stopSounds()
        val mode = audio.ringerMode
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            ringtone = RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }
        if (mode != AudioManager.RINGER_MODE_SILENT)
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 1300), 0))
    }

    private fun ringback() {
        stopSounds()
        tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) } }.getOrNull()
    }

    private fun stopSounds() {
        ringtone?.stop()
        ringtone = null
        tone?.release()
        tone = null
        vibrator.cancel()
    }

    // Screen off at the ear during voice calls.
    private var proximity: PowerManager.WakeLock? = null

    private fun updateProximity() {
        val want = (phase == Phase.OUTGOING || phase == Phase.CONNECTING || phase == Phase.ACTIVE) && !speakerOn && !cameraOn
        val pm = app.getSystemService(PowerManager::class.java)
        if (want && proximity == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "neochat:call").apply { acquire(4 * 60 * 60 * 1000L) }
        } else if (!want && proximity != null) {
            proximity?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            proximity = null
        }
    }

    // ---- actions ----

    fun openScreen() {
        app.startActivity(Intent(app, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** `conversation` is the direct chat from get_conversation. Permissions are checked by the caller. */
    fun start(conversation: JSONObject, video: Boolean) {
        if (phase == Phase.ENDED) phase = Phase.IDLE
        if (phase != Phase.IDLE) {
            say("Уже идёт звонок")
            return openScreen()
        }
        call = JSONObject().put("conversation_id", conversation.str("id")).put("peer_name", conversation.str("title"))
            .put("peer_user_id", conversation.str("peer_user_id")).put("avatar_path", conversation.str("avatar_path"))
            .put("video", video).put("outgoing", true)
        reset(video)
        setPhase(Phase.STARTING)
        openScreen()
        CoreClient.call("call_start", JSONObject().put("conversation_id", conversation.str("id")).put("video", video)) { v, e ->
            if (e != null) {
                if (phase == Phase.STARTING) {
                    endText = e.message
                    finish("")
                }
                return@call
            }
            val res = v as JSONObject
            val c = res.optJSONObject("call") ?: JSONObject()
            if (hangupPending || phase != Phase.STARTING) {
                CoreClient.call("call_hangup", JSONObject().put("call_id", c.str("call_id")))
                return@call
            }
            call = c
            createMedia(res.optJSONArray("ice_servers") ?: JSONArray(), video, null)
            changed()
        }
    }

    private fun reset(video: Boolean) {
        micOn = true
        cameraOn = false
        peerCamera = video
        reconnecting = false
        hangupPending = false
        endText = ""
    }

    fun accept(video: Boolean) {
        if (phase != Phase.INCOMING) return
        stopSounds()
        CallNotifications.cancelIncoming(app)
        setPhase(Phase.CONNECTING)
        val id = callId
        CoreClient.call("call_accept", JSONObject().put("call_id", id)) { v, e ->
            if (callId != id || phase != Phase.CONNECTING) return@call
            if (e != null) {
                endText = e.message
                return@call finish("")
            }
            val res = v as JSONObject
            createMedia(res.optJSONArray("ice_servers") ?: JSONArray(), video, res.str("sdp"))
        }
    }

    fun hangup() {
        when (phase) {
            Phase.IDLE, Phase.ENDED -> return
            Phase.STARTING -> {
                hangupPending = true
                return finish("cancelled")
            }
            else -> {}
        }
        val id = callId
        CoreClient.call("call_hangup", JSONObject().put("call_id", id)) { _, _ ->
            // The core reports the end as an event; this covers a call it no longer had.
            if (callId == id && phase != Phase.ENDED && phase != Phase.IDLE) finish("hangup")
        }
    }

    // ---- core events ----

    fun onCoreEvent(ev: JSONObject) {
        when (ev.str("type")) {
            "call_media" -> if (ev.str("call_id") == callId) {
                peerCamera = ev.optBoolean("video")
                changed()
            }
            "call" -> onCallEvent(ev)
        }
    }

    private fun onCallEvent(ev: JSONObject) {
        val state = ev.str("state")
        if (state == "incoming") {
            if (phase != Phase.IDLE && phase != Phase.ENDED) return
            call = ev
            reset(ev.optBoolean("video"))
            setPhase(Phase.INCOMING)
            ring()
            if ((app as App).inForeground) openScreen() else CallNotifications.showIncoming(app)
            return
        }
        if (ev.str("call_id") != callId || phase == Phase.IDLE || phase == Phase.ENDED) return
        when (state) {
            "outgoing" -> {
                setPhase(Phase.OUTGOING)
                ringback()
            }
            "answered" -> {
                stopSounds()
                setPhase(Phase.CONNECTING)
                pc?.setRemoteDescription(Sdp(gen), SessionDescription(SessionDescription.Type.ANSWER, ev.str("sdp")))
            }
            "connecting" -> setPhase(Phase.CONNECTING)
            "active" -> {
                since = ev.optLong("since").takeIf { it > 0 } ?: System.currentTimeMillis()
                setPhase(Phase.ACTIVE)
                sendCameraState()
            }
            "ended" -> finish(ev.str("reason"))
        }
    }

    private fun finish(reason: String) {
        stopSounds()
        CallNotifications.cancelIncoming(app)
        destroyMedia()
        val out = call.optBoolean("outgoing")
        endText = when (reason) {
            "" -> endText
            "declined" -> if (out) "Собеседник отклонил звонок" else "Звонок отклонён"
            "busy" -> "Абонент занят"
            "cancelled" -> "Звонок отменён"
            "no_answer" -> "Нет ответа"
            "missed" -> "Пропущенный звонок"
            "failed" -> "Не удалось соединиться"
            "answered_elsewhere" -> "Принят на другом устройстве"
            "declined_elsewhere" -> "Отклонён на другом устройстве"
            else -> "Звонок завершён"
        }
        setPhase(Phase.ENDED)
        // The result stays on screen briefly, then the call screen closes.
        val id = callId
        main.postDelayed({
            if (phase == Phase.ENDED && callId == id) {
                phase = Phase.IDLE
                changed()
            }
        }, 1600)
    }
}
