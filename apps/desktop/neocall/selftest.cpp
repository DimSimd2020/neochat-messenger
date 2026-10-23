// Connects two calls inside one process (offer -> answer -> connected) to check
// that neocall.dll and libwebrtc work on this machine. Exit code 0 on success.
#include "neocall.h"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>

struct Side {
    const char *name;
    neocall *call = nullptr;
    std::mutex mu;
    std::string sdp;
    bool connected = false, failed = false;
};

static std::string field(const char *json, const char *key) {
    std::string s(json), k = std::string("\"") + key + "\":\"";
    size_t i = s.find(k);
    if (i == std::string::npos) return {};
    std::string out;
    for (i += k.size(); i < s.size() && s[i] != '"'; ++i) {
        if (s[i] == '\\' && i + 1 < s.size()) {
            char n = s[++i];
            out += n == 'n' ? '\n' : n == 'r' ? '\r' : n == 't' ? '\t' : n;
        } else {
            out += s[i];
        }
    }
    return out;
}

static void onEvent(void *ctx, const char *json) {
    auto *side = static_cast<Side *>(ctx);
    std::lock_guard<std::mutex> g(side->mu);
    std::string type = field(json, "type"), state = field(json, "state");
    if (type != "local_sdp") std::printf("%s event: %s\n", side->name, json);
    if (type == "local_sdp") side->sdp = field(json, "sdp");
    if (state == "connected") side->connected = true;
    if (state == "failed") side->failed = true;
}

static void onFrame(void *, int, const uint8_t *, int, int, int) {}

template <class F> static bool waitFor(F f, int seconds) {
    auto end = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
    while (!f()) {
        if (std::chrono::steady_clock::now() > end) return false;
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    return true;
}

int main(int argc, char **argv) {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    if (argc < 2 || std::strcmp(argv[1], "--no-devices") != 0) {
        char *devices = neocall_devices();
        std::printf("devices: %s\n", devices);
        neocall_free(devices);
    }
    Side a{"a"}, b{"b"};
    neocall_config cfg{};
    a.call = neocall_create(&cfg, onEvent, onFrame, &a);
    b.call = neocall_create(&cfg, onEvent, onFrame, &b);
    if (!a.call || !b.call) return std::puts("create failed"), 1;
    neocall_offer(a.call);
    if (!waitFor([&] { std::lock_guard<std::mutex> g(a.mu); return !a.sdp.empty(); }, 10)) return std::puts("no offer"), 1;
    std::printf("offer: %zu bytes, video m-line: %s\n", a.sdp.size(), a.sdp.find("m=video") != std::string::npos ? "yes" : "no");
    for (size_t i = a.sdp.find("a=candidate"); i != std::string::npos; i = a.sdp.find("a=candidate", i + 1))
        std::printf("  %s\n", a.sdp.substr(i, a.sdp.find('\r', i) - i).c_str());
    neocall_answer(b.call, a.sdp.c_str());
    if (!waitFor([&] { std::lock_guard<std::mutex> g(b.mu); return !b.sdp.empty(); }, 10)) return std::puts("no answer"), 1;
    std::printf("answer: %zu bytes, VP8: %s\n", b.sdp.size(), b.sdp.find("VP8") != std::string::npos ? "yes" : "no");
    neocall_set_answer(a.call, b.sdp.c_str());
    bool ok = waitFor([&] {
        std::lock_guard<std::mutex> ga(a.mu);
        std::lock_guard<std::mutex> gb(b.mu);
        return (a.connected && b.connected) || a.failed || b.failed;
    }, 15);
    ok = ok && a.connected && b.connected;
    std::printf("connected: %s\n", ok ? "yes" : "no");
    // --hold N keeps the call up for N seconds, to read the diagnostics log.
    for (int i = 1; i + 1 < argc; ++i)
        if (!std::strcmp(argv[i], "--hold") && ok) std::this_thread::sleep_for(std::chrono::seconds(std::atoi(argv[i + 1])));
    neocall_set_mic(a.call, 0);
    neocall_destroy(a.call);
    neocall_destroy(b.call);
    std::puts("main done");
    return ok ? 0 : 1;
}
