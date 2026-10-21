import AudioToolbox
import AVFoundation
import UIKit
import UserNotifications
import WebRTC

/// One-to-one calls (core/src/calls.rs). Offer, answer and hangup travel as E2EE
/// messages through the core; media is WebRTC between the devices (DTLS-SRTP),
/// relayed through the server's TURN only when a direct path fails. Every call
/// negotiates audio and video, so the camera turns on and off without
/// renegotiation. All state lives on the main thread.
final class CallManager {
    enum Phase { case idle, starting, outgoing, incoming, connecting, active, ended }

    static let shared = CallManager()
    static let changed = Notification.Name("NeoChatCallChanged")

    private(set) var phase = Phase.idle
    var minimized = false
    private(set) var call: JSON = [:]
    private(set) var micOn = true
    private(set) var cameraOn = false
    private(set) var frontCamera = true
    private(set) var speakerOn = false
    private(set) var peerCamera = false
    private(set) var reconnecting = false
    private(set) var since = Date()
    private(set) var endText = ""
    private var hangupPending = false

    var callId: String { call.str("call_id") }
    var live: Bool { [.starting, .outgoing, .connecting, .active].contains(phase) }

    private init() {
        NotificationCenter.default.addObserver(forName: .coreEvent, object: nil, queue: .main) { [weak self] n in
            guard let ev = n.userInfo as? JSON else { return }
            switch ev.str("type") {
            case "call": self?.onCallEvent(ev)
            case "call_media" where ev.str("call_id") == self?.callId:
                self?.peerCamera = ev.bool("video")
                self?.notify()
            default: break
            }
        }
        NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.notify()
        }
        warmUp()
    }

    private func notify() {
        UIDevice.current.isProximityMonitoringEnabled = [.outgoing, .connecting, .active].contains(phase) && !speakerOn && !cameraOn
        UIApplication.shared.isIdleTimerDisabled = live && (cameraOn || peerCamera)
        NotificationCenter.default.post(name: Self.changed, object: nil)
    }

    private func setPhase(_ p: Phase) {
        phase = p
        notify()
    }

    // MARK: WebRTC objects

    /// Creating the factory loads the codecs and takes a noticeable time: it is made once, off the main thread, ahead of the first call.
    private static let sharedFactory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        return RTCPeerConnectionFactory(encoderFactory: RTCDefaultVideoEncoderFactory(), decoderFactory: RTCDefaultVideoDecoderFactory())
    }()
    private var factory: RTCPeerConnectionFactory { Self.sharedFactory }

    func warmUp() {
        DispatchQueue.global(qos: .userInitiated).async { _ = Self.sharedFactory }
    }
    private var pc: RTCPeerConnection?
    private var observer: Observer?
    private var audioTrack: RTCAudioTrack?
    private var videoSource: RTCVideoSource?
    private var videoTrack: RTCVideoTrack?
    private var capturer: RTCCameraVideoCapturer?
    private var videoSender: RTCRtpSender?
    private var sentSdp = false
    private var gen = 0
    private var dropTimer: Timer?

    /// Renderers attach here; the call keeps running while no screen shows it.
    final class ProxyRenderer: NSObject, RTCVideoRenderer {
        weak var target: RTCMTLVideoView?
        private let lock = NSLock()
        private var last = Date.distantPast
        var lastFrame: Date {
            lock.lock(); defer { lock.unlock() }
            return last
        }
        func reset() {
            lock.lock(); last = .distantPast; lock.unlock()
        }
        func setSize(_ size: CGSize) {
            DispatchQueue.main.async { self.target?.setSize(size) }
        }
        func renderFrame(_ frame: RTCVideoFrame?) {
            lock.lock(); last = Date(); lock.unlock()
            target?.renderFrame(frame)
        }
    }

    let localRenderer = ProxyRenderer()
    let remoteRenderer = ProxyRenderer()

    /// The peer's camera is on and frames are arriving.
    var remoteVideo: Bool { peerCamera && Date().timeIntervalSince(remoteRenderer.lastFrame) < 1.5 }

    private final class Observer: NSObject, RTCPeerConnectionDelegate {
        let gen: Int
        init(gen: Int) { self.gen = gen }
        private func post(_ f: @escaping (CallManager) -> Void) {
            DispatchQueue.main.async {
                let m = CallManager.shared
                if m.gen == self.gen { f(m) }
            }
        }
        func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
        func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
        func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
        func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
        func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
        func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
            if newState == .complete { post { $0.sendLocal() } }
        }
        func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
            // The relay candidate comes last: with it the description is complete enough to send.
            if candidate.sdp.contains(" typ relay") { post { $0.relayGathered($0.gen) } }
        }
        func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
        func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
        func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
            post { $0.connectionChanged(newState) }
        }
        func peerConnection(_ peerConnection: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams mediaStreams: [RTCMediaStream]) {
            if let v = rtpReceiver.track as? RTCVideoTrack { v.add(CallManager.shared.remoteRenderer) }
        }
    }

    private static let noConstraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)

    private func createMedia(_ ice: [JSON], video: Bool, offer: String?) {
        destroyMedia()
        gen += 1
        let g = gen
        let rtc = RTCConfiguration()
        rtc.iceServers = ice.map { s in
            let urls = (s["urls"] as? [String]) ?? [s.str("urls")]
            return RTCIceServer(urlStrings: urls, username: s.str("username"), credential: s.str("credential"))
        }
        rtc.sdpSemantics = .unifiedPlan
        rtc.bundlePolicy = .maxBundle
        rtc.rtcpMuxPolicy = .require
        rtc.continualGatheringPolicy = .gatherOnce
        let obs = Observer(gen: g)
        guard let conn = factory.peerConnection(with: rtc, constraints: Self.noConstraints, delegate: obs) else { return mediaFailed() }
        observer = obs
        pc = conn
        sentSdp = false
        startAudio(video: video)
        let track = factory.audioTrack(with: factory.audioSource(with: Self.noConstraints), trackId: "audio")
        track.isEnabled = micOn
        audioTrack = track
        let initSend = RTCRtpTransceiverInit()
        initSend.direction = .sendRecv
        initSend.streamIds = ["neocall"]
        if let offer {
            conn.setRemoteDescription(RTCSessionDescription(type: .offer, sdp: offer)) { [weak self] err in
                DispatchQueue.main.async {
                    guard let self, self.gen == g else { return }
                    if err != nil { return self.mediaFailed() }
                    self.attachAndAnswer(g)
                }
            }
        } else {
            conn.addTransceiver(with: track, init: initSend)
            videoSender = conn.addTransceiver(of: .video, init: initSend)?.sender
            conn.offer(for: Self.noConstraints) { [weak self] sdp, _ in
                DispatchQueue.main.async {
                    guard let self, self.gen == g else { return }
                    guard let sdp else { return self.mediaFailed() }
                    self.setLocal(g, sdp)
                }
            }
        }
        if video { setCamera(true) }
    }

    private func attachAndAnswer(_ g: Int) {
        guard let conn = pc else { return }
        for t in conn.transceivers {
            switch t.mediaType {
            case .audio: t.sender.track = audioTrack
            case .video:
                videoSender = t.sender
                if let v = videoTrack { t.sender.track = v }
            default: break
            }
            t.setDirection(.sendRecv, error: nil)
        }
        conn.answer(for: Self.noConstraints) { [weak self] sdp, _ in
            DispatchQueue.main.async {
                guard let self, self.gen == g else { return }
                guard let sdp else { return self.mediaFailed() }
                self.setLocal(g, sdp)
            }
        }
    }

    /// A short pause lets the remaining candidates of the same round in.
    private func relayGathered(_ g: Int) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
            if self?.gen == g { self?.sendLocal() }
        }
    }

    // Non-trickle: the description goes once gathering completes, once the relay
    // candidate is in, or after 2 s with whatever candidates were found by then.
    private func setLocal(_ g: Int, _ d: RTCSessionDescription) {
        guard let conn = pc else { return }
        conn.setLocalDescription(d) { [weak self] err in
            DispatchQueue.main.async {
                guard let self, self.gen == g else { return }
                if err != nil { return self.mediaFailed() }
                if conn.iceGatheringState == .complete { self.sendLocal() }
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
            if self?.gen == g { self?.sendLocal() }
        }
    }

    private func sendLocal() {
        guard !sentSdp, let d = pc?.localDescription else { return }
        sentSdp = true
        let id = callId
        let outgoing = call.bool("outgoing")
        Core.shared.call(outgoing ? "call_offer" : "call_answer", ["call_id": id, "sdp": d.sdp]) { [weak self] r in
            guard let self, case .failure(let e) = r, self.callId == id, self.phase != .ended, self.phase != .idle else { return }
            self.say(e.message)
            if outgoing { self.finish("failed") } else { self.hangup() }
        }
    }

    private func connectionChanged(_ s: RTCPeerConnectionState) {
        switch s {
        case .connected:
            dropTimer?.invalidate()
            reconnecting = false
            if phase == .connecting { Core.shared.call("call_connected", ["call_id": callId]) }
            notify()
        case .disconnected where phase == .active:
            reconnecting = true
            dropTimer?.invalidate()
            // ICE stayed disconnected (network switch): give up as a failed call.
            dropTimer = Timer.scheduledTimer(withTimeInterval: 12, repeats: false) { [weak self] _ in
                if self?.phase == .active || self?.phase == .connecting { self?.mediaFailed() }
            }
            notify()
        case .failed: mediaFailed()
        default: break
        }
    }

    private func mediaFailed() {
        let id = callId
        if phase == .idle || phase == .ended { return }
        Core.shared.call("call_hangup", ["call_id": id, "reason": "failed"]) { [weak self] _ in
            guard let self, self.callId == id, self.phase != .ended, self.phase != .idle else { return }
            self.finish("failed")
        }
    }

    private func destroyMedia() {
        gen += 1
        dropTimer?.invalidate()
        stopCamera()
        videoSender = nil
        pc?.close()
        pc = nil
        observer = nil
        audioTrack = nil
        remoteRenderer.reset()
        stopAudio()
    }

    // MARK: camera

    func setCamera(_ on: Bool) {
        if on == cameraOn { return }
        if on {
            guard pc != nil else { return }
            guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else { return say("Нет доступа к камере") }
            let devices = RTCCameraVideoCapturer.captureDevices()
            guard let device = devices.first(where: { $0.position == .front }) ?? devices.first else { return say("Камера не найдена") }
            let src = factory.videoSource()
            let cap = RTCCameraVideoCapturer(delegate: src)
            startCapture(cap, device)
            let track = factory.videoTrack(with: src, trackId: "video")
            track.add(localRenderer)
            frontCamera = device.position != .back
            capturer = cap
            videoSource = src
            videoTrack = track
            videoSender?.track = track
            cameraOn = true
            if !speakerOn && !headsetConnected { setSpeaker(true) }
        } else {
            stopCamera()
        }
        sendCameraState()
        notify()
    }

    /// The format closest to 640x480 at up to 30 fps.
    private func startCapture(_ cap: RTCCameraVideoCapturer, _ device: AVCaptureDevice) {
        let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
        let best = formats.min { a, b in
            let da = CMVideoFormatDescriptionGetDimensions(a.formatDescription)
            let db = CMVideoFormatDescriptionGetDimensions(b.formatDescription)
            return abs(Int(da.width) * Int(da.height) - 640 * 480) < abs(Int(db.width) * Int(db.height) - 640 * 480)
        }
        guard let format = best ?? formats.first else { return }
        let fps = min(30, Int(format.videoSupportedFrameRateRanges.map(\.maxFrameRate).max() ?? 30))
        cap.startCapture(with: device, format: format, fps: fps)
    }

    func switchCamera() {
        guard let cap = capturer else { return }
        let want: AVCaptureDevice.Position = frontCamera ? .back : .front
        guard let device = RTCCameraVideoCapturer.captureDevices().first(where: { $0.position == want }) else { return }
        cap.stopCapture { [weak self] in
            DispatchQueue.main.async {
                guard let self, self.capturer === cap else { return }
                self.startCapture(cap, device)
                self.frontCamera = want == .front
                self.notify()
            }
        }
    }

    private func stopCamera() {
        videoSender?.track = nil
        videoTrack?.remove(localRenderer)
        capturer?.stopCapture()
        capturer = nil
        videoTrack = nil
        videoSource = nil
        localRenderer.reset()
        cameraOn = false
    }

    private func sendCameraState() {
        guard phase == .active || phase == .connecting else { return }
        Core.shared.call("call_media", ["call_id": callId, "video": cameraOn])
    }

    func setMic(_ on: Bool) {
        micOn = on
        audioTrack?.isEnabled = on
        notify()
    }

    // MARK: audio routing and sounds

    private var headsetConnected: Bool {
        AVAudioSession.sharedInstance().currentRoute.outputs.contains {
            [.headphones, .bluetoothHFP, .bluetoothA2DP, .bluetoothLE, .usbAudio, .carAudio].contains($0.portType)
        }
    }

    private func startAudio(video: Bool) {
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playAndRecord, mode: video ? .videoChat : .voiceChat, options: [.allowBluetooth])
        try? s.setActive(true)
        setSpeaker(video && !headsetConnected)
    }

    func setSpeaker(_ on: Bool) {
        speakerOn = on
        try? AVAudioSession.sharedInstance().overrideOutputAudioPort(on ? .speaker : .none)
        notify()
    }

    private func stopAudio() {
        speakerOn = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private var player: AVAudioPlayer?
    private var vibration: Timer?

    /// Incoming: a two-tone ring that follows the silent switch, plus vibration.
    private func ring() {
        stopSounds()
        try? AVAudioSession.sharedInstance().setCategory(.soloAmbient)
        try? AVAudioSession.sharedInstance().setActive(true)
        play(Tone.ring)
        AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
        vibration = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { _ in AudioServicesPlaySystemSound(kSystemSoundID_Vibrate) }
    }

    private func ringback() {
        stopSounds()
        play(Tone.ringback)
    }

    private func play(_ wav: Data) {
        player = try? AVAudioPlayer(data: wav)
        player?.numberOfLoops = -1
        player?.volume = 0.6
        player?.play()
    }

    private func stopSounds() {
        player?.stop()
        player = nil
        vibration?.invalidate()
        vibration = nil
    }

    // MARK: actions

    private func say(_ text: String) {
        topController()?.toast(text)
    }

    private func topController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        var top = scenes.flatMap(\.windows).first { $0.isKeyWindow }?.rootViewController
        while let p = top?.presentedViewController { top = p }
        return top
    }

    func openScreen(retries: Int = 8) {
        minimized = false
        guard let top = topController(), !(top is CallViewController) else { return }
        // A screen that is still sliding in or out cannot present another one: try again shortly.
        if top.isBeingPresented || top.isBeingDismissed || top.isMovingToParent || top.isMovingFromParent {
            guard retries > 0 else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
                if let self, self.live || self.phase == .incoming { self.openScreen(retries: retries - 1) }
            }
            return
        }
        let vc = CallViewController()
        vc.modalPresentationStyle = .fullScreen
        vc.modalTransitionStyle = .crossDissolve
        top.present(vc, animated: !Theme.reducedMotion)
    }

    /// Asks for the microphone (and the camera for video), then runs `go` with whether video is allowed.
    static func withPermissions(video: Bool, from vc: UIViewController?, _ go: @escaping (Bool) -> Void) {
        AVCaptureDevice.requestAccess(for: .audio) { mic in
            DispatchQueue.main.async {
                guard mic else { vc?.toast("Для звонка нужен доступ к микрофону"); return }
                guard video else { return go(false) }
                AVCaptureDevice.requestAccess(for: .video) { cam in DispatchQueue.main.async { go(cam) } }
            }
        }
    }

    /// `conversation` is the direct chat from get_conversation.
    func start(_ conversation: JSON, video: Bool) {
        if phase == .ended { phase = .idle }
        if phase != .idle {
            say("Уже идёт звонок")
            return openScreen()
        }
        call = ["conversation_id": conversation.str("id"), "peer_name": conversation.str("title"), "peer_user_id": conversation.str("peer_user_id"),
                "avatar_path": conversation.str("avatar_path"), "video": video, "outgoing": true]
        reset(video)
        setPhase(.starting)
        openScreen()
        Core.shared.call("call_start", ["conversation_id": conversation.str("id"), "video": video]) { [weak self] r in
            guard let self else { return }
            switch r {
            case .failure(let e):
                if self.phase == .starting {
                    self.endText = e.message
                    self.finish("")
                }
            case .success(let v):
                let res = v as? JSON ?? [:]
                let c = res.obj("call") ?? [:]
                if self.hangupPending || self.phase != .starting {
                    Core.shared.call("call_hangup", ["call_id": c.str("call_id")])
                    return
                }
                self.call = c
                self.createMedia(res.arr("ice_servers"), video: video, offer: nil)
                self.notify()
            }
        }
    }

    private func reset(_ video: Bool) {
        minimized = false
        micOn = true
        cameraOn = false
        peerCamera = video
        reconnecting = false
        hangupPending = false
        endText = ""
    }

    func accept(video: Bool) {
        guard phase == .incoming else { return }
        stopSounds()
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["call"])
        setPhase(.connecting)
        let id = callId
        Core.shared.call("call_accept", ["call_id": id]) { [weak self] r in
            guard let self, self.callId == id, self.phase == .connecting else { return }
            switch r {
            case .failure(let e):
                self.endText = e.message
                self.finish("")
            case .success(let v):
                let res = v as? JSON ?? [:]
                self.createMedia(res.arr("ice_servers"), video: video, offer: res.str("sdp"))
            }
        }
    }

    func hangup() {
        switch phase {
        case .idle, .ended: return
        case .starting:
            hangupPending = true
            return finish("cancelled")
        default: break
        }
        let id = callId
        Core.shared.call("call_hangup", ["call_id": id]) { [weak self] _ in
            // The core reports the end as an event; this covers a call it no longer had.
            guard let self, self.callId == id, self.phase != .ended, self.phase != .idle else { return }
            self.finish("hangup")
        }
    }

    // MARK: core events

    private func onCallEvent(_ ev: JSON) {
        let state = ev.str("state")
        if state == "incoming" {
            guard phase == .idle || phase == .ended else { return }
            call = ev
            reset(ev.bool("video"))
            setPhase(.incoming)
            if UIApplication.shared.applicationState == .active {
                ring()
                openScreen()
            } else {
                postIncoming()
            }
            return
        }
        guard ev.str("call_id") == callId, phase != .idle, phase != .ended else { return }
        switch state {
        case "outgoing":
            setPhase(.outgoing)
            ringback()
        case "answered":
            stopSounds()
            setPhase(.connecting)
            let g = gen
            pc?.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: ev.str("sdp"))) { [weak self] err in
                DispatchQueue.main.async { if err != nil, self?.gen == g { self?.mediaFailed() } }
            }
        case "connecting": setPhase(.connecting)
        case "active":
            let ms = ev.int64("since")
            since = ms > 0 ? Date(timeIntervalSince1970: TimeInterval(ms) / 1000) : Date()
            setPhase(.active)
            sendCameraState()
        case "ended": finish(ev.str("reason"))
        default: break
        }
    }

    /// While NeoChat is in the background the ring is a notification; tapping it opens the call screen.
    private func postIncoming() {
        let c = UNMutableNotificationContent()
        c.title = call.str("peer_name").isEmpty ? "NeoChat" : call.str("peer_name")
        c.body = call.bool("video") ? "Входящий видеозвонок" : "Входящий звонок"
        c.sound = .default
        c.userInfo = ["call": true]
        c.interruptionLevel = .timeSensitive
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "call", content: c, trigger: nil))
    }

    /// The app came to the foreground: show a call that is still ringing or running.
    func resume() {
        if phase == .incoming {
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["call"])
            if player == nil { ring() }
            openScreen()
        } else if live && !minimized {
            openScreen()
        }
    }

    private func finish(_ reason: String) {
        stopSounds()
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["call"])
        destroyMedia()
        let out = call.bool("outgoing")
        switch reason {
        case "": break
        case "declined": endText = out ? "Собеседник отклонил звонок" : "Звонок отклонён"
        case "busy": endText = "Абонент занят"
        case "cancelled": endText = "Звонок отменён"
        case "no_answer": endText = "Нет ответа"
        case "missed": endText = "Пропущенный звонок"
        case "failed": endText = "Не удалось соединиться"
        case "answered_elsewhere": endText = "Принят на другом устройстве"
        case "declined_elsewhere": endText = "Отклонён на другом устройстве"
        default: endText = "Звонок завершён"
        }
        setPhase(.ended)
        // The result stays on screen briefly, then the call screen closes.
        let id = callId
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { [weak self] in
            guard let self, self.phase == .ended, self.callId == id else { return }
            self.setPhase(.idle)
        }
    }
}

/// Generated call tones (16-bit mono WAV), so no audio assets ship with the app.
private enum Tone {
    static let ring = wav(seconds: 3) { t in
        let on = t.truncatingRemainder(dividingBy: 3) < 0.4 || (t >= 0.6 && t < 1.0)
        return on ? 0.5 * sin(2 * .pi * 440 * t) + 0.5 * sin(2 * .pi * 480 * t) : 0
    }
    static let ringback = wav(seconds: 4) { t in t < 1 ? sin(2 * .pi * 425 * t) : 0 }

    private static func wav(seconds: Double, _ f: (Double) -> Double) -> Data {
        let rate = 16000
        let n = Int(Double(rate) * seconds)
        var d = Data()
        func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        d.append(contentsOf: Array("RIFF".utf8)); u32(UInt32(36 + n * 2))
        d.append(contentsOf: Array("WAVEfmt ".utf8)); u32(16); u16(1); u16(1); u32(UInt32(rate)); u32(UInt32(rate * 2)); u16(2); u16(16)
        d.append(contentsOf: Array("data".utf8)); u32(UInt32(n * 2))
        for i in 0..<n {
            let s = max(-1, min(1, f(Double(i) / Double(rate)) * 0.35))
            u16(UInt16(bitPattern: Int16(s * 32767)))
        }
        return d
    }
}
