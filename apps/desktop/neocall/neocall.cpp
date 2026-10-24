// neocall: libwebrtc wrapper for desktop calls (see neocall.h).
#define NEOCALL_BUILD
#include "neocall.h"

#include <atomic>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "api/audio/audio_processing.h"
#include "api/audio/builtin_audio_processing_builder.h"
#include "api/audio_options.h"
#include "api/audio/create_audio_device_module.h"
#include "api/audio_codecs/builtin_audio_decoder_factory.h"
#include "api/audio_codecs/builtin_audio_encoder_factory.h"
#include "api/create_modular_peer_connection_factory.h"
#include "api/enable_media.h"
#include "api/environment/environment_factory.h"
#include "api/jsep.h"
#include "api/make_ref_counted.h"
#include "api/peer_connection_interface.h"
#include "api/set_local_description_observer_interface.h"
#include "api/set_remote_description_observer_interface.h"
#include "api/task_queue/default_task_queue_factory.h"
#include "api/video/video_broadcaster.h"
#include "api/video/i420_buffer.h"
#include "api/video/video_frame.h"
#include "api/video_codecs/video_decoder_factory_template.h"
#include "api/video_codecs/video_decoder_factory_template_libvpx_vp8_adapter.h"
#include "api/video_codecs/video_decoder_factory_template_libvpx_vp9_adapter.h"
#include "api/video_codecs/video_encoder_factory_template.h"
#include "api/video_codecs/video_encoder_factory_template_libvpx_vp8_adapter.h"
#include "api/video_codecs/video_encoder_factory_template_libvpx_vp9_adapter.h"
#include "modules/audio_device/include/audio_device.h"
#include "modules/video_capture/video_capture_factory.h"
#include "pc/video_track_source.h"
#include "rtc_base/thread.h"
#include "third_party/libyuv/include/libyuv/convert_argb.h"

#include <winsock2.h>
#include <mmdeviceapi.h>
#include <propidl.h>

#include <objbase.h>

namespace {

// NEOCALL_TRACE=1 prints the setup steps to stderr (diagnostics of device drivers).
void trace(const char *step) {
    static const bool on = getenv("NEOCALL_TRACE") != nullptr;
    if (on) fprintf(stderr, "neocall: %s\n", step);
}

std::string esc(const std::string &s) {
    std::string o;
    o.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
        case '"': o += "\\\""; break;
        case '\\': o += "\\\\"; break;
        case '\n': o += "\\n"; break;
        case '\r': o += "\\r"; break;
        case '\t': o += "\\t"; break;
        default:
            if (c < 0x20) {
                char b[8];
                snprintf(b, sizeof b, "\\u%04x", c);
                o += b;
            } else {
                o += char(c);
            }
        }
    }
    return o;
}

// One factory, audio device module and set of threads for the process.
struct Engine {
    std::unique_ptr<webrtc::Thread> network, worker, signaling;
    webrtc::scoped_refptr<webrtc::AudioDeviceModule> adm;
    webrtc::scoped_refptr<webrtc::PeerConnectionFactoryInterface> factory;

    Engine() {
        // libwebrtc leaves Winsock to the application; without it no UDP or
        // listening sockets open and only active TCP candidates appear.
        WSADATA wsa;
        WSAStartup(MAKEWORD(2, 2), &wsa);
        trace("threads");
        network = webrtc::Thread::CreateWithSocketServer();
        worker = webrtc::Thread::Create();
        signaling = webrtc::Thread::Create();
        network->SetName("neocall-net", nullptr);
        worker->SetName("neocall-worker", nullptr);
        signaling->SetName("neocall-signal", nullptr);
        network->Start();
        worker->Start();
        signaling->Start();
        webrtc::Environment env = webrtc::CreateEnvironment();
        // The audio device module lives on the worker thread.
        adm = worker->BlockingCall([&] {
            trace("adm create");
            auto m = webrtc::CreateAudioDeviceModule(env, webrtc::AudioDeviceModule::kPlatformDefaultAudio);
            trace("adm init");
            if (m) m->Init();
            return m;
        });
        trace("factory");
        webrtc::PeerConnectionFactoryDependencies deps;
        deps.network_thread = network.get();
        deps.worker_thread = worker.get();
        deps.signaling_thread = signaling.get();
        deps.env = env;
        deps.adm = adm;
        deps.audio_encoder_factory = webrtc::CreateBuiltinAudioEncoderFactory();
        deps.audio_decoder_factory = webrtc::CreateBuiltinAudioDecoderFactory();
        // Analog gain control drives the Windows mic volume. After about two
        // seconds it decides the level is wrong and turns the mic down to silence.
        webrtc::AudioProcessing::Config apm;
        apm.pipeline.multi_channel_render = false;
        apm.pipeline.multi_channel_capture = false;
        apm.high_pass_filter.enabled = true;
        apm.echo_canceller.enabled = true;
        apm.noise_suppression.enabled = true;
        apm.noise_suppression.level = webrtc::AudioProcessing::Config::NoiseSuppression::kModerate;
        apm.gain_controller1.enabled = false;
        apm.gain_controller2.enabled = true;
        apm.gain_controller2.input_volume_controller.enabled = false;
        apm.gain_controller2.adaptive_digital.enabled = true;
        deps.audio_processing_builder = std::make_unique<webrtc::BuiltinAudioProcessingBuilder>(apm);
        deps.video_encoder_factory = std::make_unique<webrtc::VideoEncoderFactoryTemplate<
            webrtc::LibvpxVp8EncoderTemplateAdapter, webrtc::LibvpxVp9EncoderTemplateAdapter>>();
        deps.video_decoder_factory = std::make_unique<webrtc::VideoDecoderFactoryTemplate<
            webrtc::LibvpxVp8DecoderTemplateAdapter, webrtc::LibvpxVp9DecoderTemplateAdapter>>();
        webrtc::EnableMedia(deps);
        factory = webrtc::CreateModularPeerConnectionFactory(std::move(deps));
        trace("ready");
    }

    // Never destroyed: stopping WebRTC threads from DLL_PROCESS_DETACH (under
    // the loader lock) leaves the process hanging on exit.
    static Engine &get() {
        static Engine *e = new Engine;
        return *e;
    }

    // Audio device ids are "<name>\t<guid>"-free: the GUID alone when Windows gives one.
    int findDevice(bool input, const std::string &id) {
        if (id.empty() || !adm) return -1;
        return worker->BlockingCall([&] {
            int n = input ? adm->RecordingDevices() : adm->PlayoutDevices();
            for (int i = 0; i < n; ++i) {
                char name[webrtc::kAdmMaxDeviceNameSize] = {}, guid[webrtc::kAdmMaxGuidSize] = {};
                int r = input ? adm->RecordingDeviceName(i, name, guid) : adm->PlayoutDeviceName(i, name, guid);
                if (r == 0 && (id == guid || id == name)) return i;
            }
            return -1;
        });
    }

    // Selects the devices for the next call; only possible while the device is idle.
    // The Windows default (console) device is what the volume mixer plays. The
    // communications default is often a leftover headset, so the mic works and
    // the other person is silent.
    void useDevices(const std::string &input, const std::string &output) {
        int in = findDevice(true, input), out = findDevice(false, output);
        worker->BlockingCall([&] {
            if (!adm) return;
            // A previous call can leave playout initialized in stereo. Voice is
            // mono; the stereo session plays a burst of noise and then stops.
            if (adm->Playing()) adm->StopPlayout();
            if (adm->Recording()) adm->StopRecording();
            adm->EnableBuiltInAEC(false);
            adm->SetStereoPlayout(false);
            adm->SetStereoRecording(false);
            if (in >= 0) adm->SetRecordingDevice(uint16_t(in));
            else adm->SetRecordingDevice(webrtc::AudioDeviceModule::kDefaultDevice);
            if (out >= 0) adm->SetPlayoutDevice(uint16_t(out));
            else adm->SetPlayoutDevice(webrtc::AudioDeviceModule::kDefaultDevice);
            bool muted = false;
            if (adm->SpeakerMute(&muted) == 0 && muted)
                adm->SetSpeakerMute(false);
        });
    }
};

// PKEY_Device_FriendlyName. Listed here so the device menu does not go through
// the call's audio module: that enumeration comes back empty while a call holds it.
static const PROPERTYKEY kFriendlyName = {{0xa45c254e, 0xdf1c, 0x4efd, {0x80, 0x20, 0x67, 0xd1, 0x46, 0xa8, 0x50, 0xe0}}, 14};

std::string wideToUtf8(LPCWSTR w) {
    if (!w || !*w) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    if (n <= 1) return {};
    std::string s(size_t(n - 1), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, -1, s.data(), n, nullptr, nullptr);
    return s;
}

struct AudioEndpoint {
    std::string id, name;
};

std::vector<AudioEndpoint> endpoints(EDataFlow flow) {
    std::vector<AudioEndpoint> out;
    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    const bool uninit = hr == S_OK;
    IMMDeviceEnumerator *en = nullptr;
    if (FAILED(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, __uuidof(IMMDeviceEnumerator), reinterpret_cast<void **>(&en))) || !en) {
        if (uninit) CoUninitialize();
        return out;
    }
    IMMDeviceCollection *col = nullptr;
    if (SUCCEEDED(en->EnumAudioEndpoints(flow, DEVICE_STATE_ACTIVE, &col)) && col) {
        UINT n = 0;
        col->GetCount(&n);
        for (UINT i = 0; i < n; ++i) {
            IMMDevice *dev = nullptr;
            if (FAILED(col->Item(i, &dev)) || !dev) continue;
            LPWSTR wid = nullptr;
            dev->GetId(&wid);
            std::string id = wideToUtf8(wid);
            if (wid) CoTaskMemFree(wid);
            IPropertyStore *ps = nullptr;
            std::string name;
            if (SUCCEEDED(dev->OpenPropertyStore(STGM_READ, &ps)) && ps) {
                PROPVARIANT v;
                PropVariantInit(&v);
                if (SUCCEEDED(ps->GetValue(kFriendlyName, &v)) && v.vt == VT_LPWSTR)
                    name = wideToUtf8(v.pwszVal);
                PropVariantClear(&v);
                ps->Release();
            }
            dev->Release();
            if (name.empty()) continue;
            if (id.empty()) id = name;
            out.push_back({std::move(id), std::move(name)});
        }
        col->Release();
    }
    en->Release();
    if (uninit) CoUninitialize();
    return out;
}

// Converts frames to upright BGRA for the UI.
class FrameSink : public webrtc::VideoSinkInterface<webrtc::VideoFrame> {
public:
    FrameSink(neocall_frame_fn fn, void *ctx, int remote) : fn_(fn), ctx_(ctx), remote_(remote) {}
    void stop() {
        std::lock_guard<std::mutex> g(mu_);
        fn_ = nullptr;
    }
    void OnFrame(const webrtc::VideoFrame &frame) override {
        std::lock_guard<std::mutex> g(mu_);
        if (!fn_) return;
        webrtc::scoped_refptr<webrtc::I420BufferInterface> buf = frame.video_frame_buffer()->ToI420();
        if (!buf) return;
        if (frame.rotation() != webrtc::kVideoRotation_0) buf = webrtc::I420Buffer::Rotate(*buf, frame.rotation());
        int w = buf->width(), h = buf->height();
        bgra_.resize(size_t(w) * h * 4);
        libyuv::I420ToARGB(buf->DataY(), buf->StrideY(), buf->DataU(), buf->StrideU(), buf->DataV(), buf->StrideV(),
                           bgra_.data(), w * 4, w, h);
        fn_(ctx_, remote_, bgra_.data(), w, h, w * 4);
    }

private:
    std::mutex mu_;
    neocall_frame_fn fn_;
    void *ctx_;
    int remote_;
    std::vector<uint8_t> bgra_;
};

// A camera through the platform capture module, as a WebRTC track source.
// (The prebuilt library has no AdaptedVideoTrackSource; frames go out as
// captured, so the capture size is kept modest.)
class CameraSource : public webrtc::VideoTrackSource {
public:
    CameraSource() : webrtc::VideoTrackSource(false) {}
    bool start(const std::string &id) {
        std::string unique = id;
        std::unique_ptr<webrtc::VideoCaptureModule::DeviceInfo> info(webrtc::VideoCaptureFactory::CreateDeviceInfo());
        if (!info || info->NumberOfDevices() == 0) return false;
        if (unique.empty() || !hasDevice(info.get(), unique)) {
            char name[256] = {}, uid[256] = {};
            if (info->GetDeviceName(0, name, sizeof name, uid, sizeof uid) != 0) return false;
            unique = uid;
        }
        module_ = webrtc::VideoCaptureFactory::Create(unique.c_str());
        if (!module_) return false;
        webrtc::VideoCaptureCapability want, got;
        want.width = 640;
        want.height = 480;
        want.maxFPS = 30;
        if (info->GetBestMatchedCapability(unique.c_str(), want, got) < 0) got = want;
        module_->RegisterCaptureDataCallback(&broadcaster_);
        if (module_->StartCapture(got) != 0) {
            module_->DeRegisterCaptureDataCallback();
            module_ = nullptr;
            return false;
        }
        return true;
    }
    void stop() {
        if (!module_) return;
        module_->StopCapture();
        module_->DeRegisterCaptureDataCallback();
        module_ = nullptr;
    }
    ~CameraSource() override { stop(); }

protected:
    webrtc::VideoSourceInterface<webrtc::VideoFrame> *source() override { return &broadcaster_; }

private:
    static bool hasDevice(webrtc::VideoCaptureModule::DeviceInfo *info, const std::string &id) {
        for (uint32_t i = 0; i < info->NumberOfDevices(); ++i) {
            char name[256] = {}, uid[256] = {};
            if (info->GetDeviceName(i, name, sizeof name, uid, sizeof uid) == 0 && id == uid) return true;
        }
        return false;
    }
    webrtc::VideoBroadcaster broadcaster_;
    webrtc::scoped_refptr<webrtc::VideoCaptureModule> module_;
};

} // namespace

struct neocall : public webrtc::PeerConnectionObserver {
    neocall_event_fn onEvent;
    void *ctx;
    FrameSink local, remote;
    std::mutex mu;
    bool closed = false;
    bool sentSdp = false;
    webrtc::scoped_refptr<webrtc::PeerConnectionInterface> pc;
    webrtc::scoped_refptr<webrtc::AudioTrackInterface> audio;
    webrtc::scoped_refptr<CameraSource> camera;
    webrtc::scoped_refptr<webrtc::VideoTrackInterface> video;
    webrtc::scoped_refptr<webrtc::RtpSenderInterface> videoSender;
    std::string cameraId;

    neocall(neocall_event_fn e, neocall_frame_fn f, void *c) : onEvent(e), ctx(c), local(f, c, 0), remote(f, c, 1) {}

    void emit(const std::string &json) {
        std::lock_guard<std::mutex> g(mu);
        if (!closed && onEvent) onEvent(ctx, json.c_str());
    }

    // Non-trickle: the description goes out once gathering completes, or after
    // a deadline with whatever candidates were found by then.
    void sendLocalDescription() {
        if (sentSdp || !pc) return;
        const webrtc::SessionDescriptionInterface *d = pc->local_description();
        if (!d) return;
        std::string sdp;
        d->ToString(&sdp);
        sentSdp = true;
        emit("{\"type\":\"local_sdp\",\"sdp\":\"" + esc(sdp) + "\"}");
    }

    void setLocal() {
        struct Obs : webrtc::SetLocalDescriptionObserverInterface {
            neocall *c;
            explicit Obs(neocall *c) : c(c) {}
            void OnSetLocalDescriptionComplete(webrtc::RTCError e) override {
                if (!e.ok()) return c->emit("{\"type\":\"state\",\"state\":\"failed\"}");
                if (c->pc && c->pc->ice_gathering_state() == webrtc::PeerConnectionInterface::kIceGatheringComplete) c->sendLocalDescription();
            }
        };
        pc->SetLocalDescription(webrtc::make_ref_counted<Obs>(this));
        Engine::get().signaling->PostDelayedTask([this] { sendLocalDescription(); }, webrtc::TimeDelta::Seconds(4));
    }

    void setRemote(webrtc::SdpType type, const char *sdp, bool thenAnswer) {
        auto desc = webrtc::CreateSessionDescription(type, sdp ? sdp : "");
        if (!desc) return emit("{\"type\":\"state\",\"state\":\"failed\"}");
        struct Obs : webrtc::SetRemoteDescriptionObserverInterface {
            neocall *c;
            bool answer;
            Obs(neocall *c, bool a) : c(c), answer(a) {}
            void OnSetRemoteDescriptionComplete(webrtc::RTCError e) override {
                if (!e.ok()) return c->emit("{\"type\":\"state\",\"state\":\"failed\"}");
                if (answer) c->attachTracksAndAnswer();
            }
        };
        pc->SetRemoteDescription(std::move(desc), webrtc::make_ref_counted<Obs>(this, thenAnswer));
    }

    // Callee: the offer created both transceivers; send our tracks on them.
    void attachTracksAndAnswer() {
        for (auto &t : pc->GetTransceivers()) {
            if (t->media_type() == webrtc::MediaType::AUDIO) {
                t->sender()->SetTrack(audio.get());
            } else if (t->media_type() == webrtc::MediaType::VIDEO) {
                videoSender = t->sender();
                if (video) videoSender->SetTrack(video.get());
            }
            t->SetDirectionWithError(webrtc::RtpTransceiverDirection::kSendRecv);
        }
        setLocal();
    }

    bool startCamera() {
        if (video) return true;
        auto src = webrtc::make_ref_counted<CameraSource>();
        if (!src->start(cameraId)) return false;
        camera = src;
        video = Engine::get().factory->CreateVideoTrack(camera, "video");
        video->AddOrUpdateSink(&local, webrtc::VideoSinkWants());
        if (videoSender) videoSender->SetTrack(video.get());
        return true;
    }

    void stopCamera() {
        if (!video) return;
        if (videoSender) videoSender->SetTrack(nullptr);
        video->RemoveSink(&local);
        video = nullptr;
        if (camera) camera->stop();
        camera = nullptr;
    }

    // ---- PeerConnectionObserver ----
    void OnSignalingChange(webrtc::PeerConnectionInterface::SignalingState) override {}
    void OnDataChannel(webrtc::scoped_refptr<webrtc::DataChannelInterface>) override {}
    void OnIceCandidate(const webrtc::IceCandidate *) override {}
    void OnIceConnectionChange(webrtc::PeerConnectionInterface::IceConnectionState s) override {
        static const char *names[] = {"new", "checking", "connected", "completed", "failed", "disconnected", "closed"};
        if (s >= 0 && s < 7) trace(names[s]);
    }
    void OnIceGatheringChange(webrtc::PeerConnectionInterface::IceGatheringState s) override {
        if (s == webrtc::PeerConnectionInterface::kIceGatheringComplete && pc && pc->local_description()) sendLocalDescription();
    }
    void OnConnectionChange(webrtc::PeerConnectionInterface::PeerConnectionState s) override {
        using S = webrtc::PeerConnectionInterface::PeerConnectionState;
        if (s == S::kConnected) emit("{\"type\":\"state\",\"state\":\"connected\"}");
        else if (s == S::kDisconnected) emit("{\"type\":\"state\",\"state\":\"disconnected\"}");
        else if (s == S::kFailed) emit("{\"type\":\"state\",\"state\":\"failed\"}");
    }
    void OnTrack(webrtc::scoped_refptr<webrtc::RtpTransceiverInterface> t) override {
        auto track = t->receiver()->track();
        if (!track) return;
        if (track->kind() == webrtc::MediaStreamTrackInterface::kVideoKind)
            static_cast<webrtc::VideoTrackInterface *>(track.get())->AddOrUpdateSink(&remote, webrtc::VideoSinkWants());
        if (track->kind() == webrtc::MediaStreamTrackInterface::kAudioKind) {
            track->set_enabled(true);
            auto adm = Engine::get().adm;
            Engine::get().worker->BlockingCall([adm] {
                if (!adm) return;
                if (!adm->PlayoutIsInitialized()) adm->InitPlayout();
                if (!adm->Playing()) adm->StartPlayout();
                bool muted = false;
                if (adm->SpeakerMute(&muted) == 0 && muted) adm->SetSpeakerMute(false);
            });
        }
    }
};

NEOCALL_API char *neocall_devices(void) {
    // A failed refresh must not wipe a list Windows still has: the in-call audio
    // module sometimes reports zero devices until the call ends.
    static std::mutex cacheMu;
    static std::vector<AudioEndpoint> cacheIn, cacheOut;
    auto list = [&](EDataFlow flow, std::vector<AudioEndpoint> &cache) {
        auto fresh = endpoints(flow);
        std::lock_guard<std::mutex> g(cacheMu);
        if (!fresh.empty()) cache = std::move(fresh);
        return cache;
    };
    const auto inputs = list(eCapture, cacheIn);
    const auto outputs = list(eRender, cacheOut);
    std::ostringstream o;
    auto write = [&](const std::vector<AudioEndpoint> &devs) {
        for (size_t i = 0; i < devs.size(); ++i)
            o << (i ? "," : "") << "{\"id\":\"" << esc(devs[i].id) << "\",\"name\":\"" << esc(devs[i].name) << "\"}";
    };
    o << "{\"inputs\":[";
    write(inputs);
    o << "],\"outputs\":[";
    write(outputs);
    o << "],\"cameras\":[";
    trace("cameras");
    std::unique_ptr<webrtc::VideoCaptureModule::DeviceInfo> info(webrtc::VideoCaptureFactory::CreateDeviceInfo());
    for (uint32_t i = 0; info && i < info->NumberOfDevices(); ++i) {
        char name[256] = {}, uid[256] = {};
        if (info->GetDeviceName(i, name, sizeof name, uid, sizeof uid) != 0) continue;
        o << (i ? "," : "") << "{\"id\":\"" << esc(uid) << "\",\"name\":\"" << esc(name) << "\"}";
    }
    o << "]}";
    std::string s = o.str();
    char *out = static_cast<char *>(malloc(s.size() + 1));
    memcpy(out, s.c_str(), s.size() + 1);
    return out;
}

NEOCALL_API void neocall_free(char *s) { free(s); }

NEOCALL_API neocall *neocall_create(const neocall_config *cfg, neocall_event_fn on_event, neocall_frame_fn on_frame, void *ctx) {
    Engine &e = Engine::get();
    if (!e.factory || !cfg) return nullptr;
    e.useDevices(cfg->input ? cfg->input : "", cfg->output ? cfg->output : "");
    webrtc::PeerConnectionInterface::RTCConfiguration rc;
    rc.sdp_semantics = webrtc::SdpSemantics::kUnifiedPlan;
    rc.bundle_policy = webrtc::PeerConnectionInterface::kBundlePolicyMaxBundle;
    rc.rtcp_mux_policy = webrtc::PeerConnectionInterface::kRtcpMuxPolicyRequire;
    for (int i = 0; i < cfg->ice_count; ++i) {
        webrtc::PeerConnectionInterface::IceServer s;
        std::istringstream urls(cfg->ice[i].urls ? cfg->ice[i].urls : "");
        for (std::string u; urls >> u;) s.urls.push_back(u);
        s.username = cfg->ice[i].username ? cfg->ice[i].username : "";
        s.password = cfg->ice[i].credential ? cfg->ice[i].credential : "";
        if (!s.urls.empty()) rc.servers.push_back(s);
    }
    auto *c = new neocall(on_event, on_frame, ctx);
    c->cameraId = cfg->camera ? cfg->camera : "";
    webrtc::PeerConnectionDependencies deps(c);
    auto pc = e.factory->CreatePeerConnectionOrError(rc, std::move(deps));
    if (!pc.ok()) {
        delete c;
        return nullptr;
    }
    c->pc = pc.MoveValue();
    webrtc::AudioOptions ao;
    // Keep echo cancellation. Do not let the engine turn analog mic-gain back on:
    // that is the control which silences the Windows microphone after a moment.
    ao.echo_cancellation = true;
    ao.auto_gain_control = false;
    ao.noise_suppression = true;
    ao.highpass_filter = true;
    c->audio = e.factory->CreateAudioTrack("audio", e.factory->CreateAudioSource(ao).get());
    if (cfg->video) c->startCamera();
    return c;
}

NEOCALL_API void neocall_offer(neocall *c) {
    if (!c) return;
    Engine::get().signaling->BlockingCall([c] {
        webrtc::RtpTransceiverInit init;
        init.stream_ids = {"neocall"};
        c->pc->AddTransceiver(c->audio, init);
        auto vt = c->video ? c->pc->AddTransceiver(c->video, init) : c->pc->AddTransceiver(webrtc::MediaType::VIDEO, init);
        if (vt.ok()) c->videoSender = vt.value()->sender();
        c->setLocal();
    });
}

NEOCALL_API void neocall_answer(neocall *c, const char *offer_sdp) {
    if (!c) return;
    std::string sdp = offer_sdp ? offer_sdp : "";
    Engine::get().signaling->BlockingCall([c, &sdp] { c->setRemote(webrtc::SdpType::kOffer, sdp.c_str(), true); });
}

NEOCALL_API void neocall_set_answer(neocall *c, const char *answer_sdp) {
    if (!c) return;
    std::string sdp = answer_sdp ? answer_sdp : "";
    Engine::get().signaling->BlockingCall([c, &sdp] { c->setRemote(webrtc::SdpType::kAnswer, sdp.c_str(), false); });
}

NEOCALL_API void neocall_set_mic(neocall *c, int enabled) {
    if (c && c->audio) c->audio->set_enabled(enabled != 0);
}

NEOCALL_API int neocall_set_camera(neocall *c, int enabled) {
    if (!c) return 0;
    return Engine::get().signaling->BlockingCall([c, enabled] {
        if (enabled) return c->startCamera() ? 1 : 0;
        c->stopCamera();
        return 0;
    });
}

NEOCALL_API void neocall_destroy(neocall *c) {
    if (!c) return;
    {
        std::lock_guard<std::mutex> g(c->mu);
        c->closed = true;
    }
    c->local.stop();
    c->remote.stop();
    Engine::get().signaling->BlockingCall([c] {
        c->stopCamera();
        if (c->pc) c->pc->Close();
        c->pc = nullptr;
        c->audio = nullptr;
    });
    // Delayed tasks posted for this call run on the signaling thread; delete there after them.
    Engine::get().signaling->PostDelayedTask([c] { delete c; }, webrtc::TimeDelta::Seconds(5));
}
