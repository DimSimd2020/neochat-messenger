// neocall: WebRTC media for NeoChat desktop calls behind a plain C ABI.
//
// libwebrtc is linked with the static CRT (/MT) and its own abseil, while Qt
// uses /MD, so the two never share a module: the app loads neocall.dll and
// passes only C strings and pixel buffers across. Signaling (offer/answer
// inside E2EE messages) stays in the core; this library never sees keys.
#pragma once

#include <stdint.h>

#ifdef NEOCALL_BUILD
#define NEOCALL_API extern "C" __declspec(dllexport)
#else
#define NEOCALL_API extern "C" __declspec(dllimport)
#endif

typedef struct neocall neocall;

// JSON events, called on a WebRTC thread:
//   {"type":"local_sdp","sdp":"..."}   offer or answer with every ICE candidate
//   {"type":"state","state":"connected|disconnected|failed"}
typedef void (*neocall_event_fn)(void *ctx, const char *json);
// A video frame as 32-bit BGRA (QImage::Format_RGB32 on little-endian),
// rotated upright. `remote` is 1 for the peer, 0 for the local preview.
typedef void (*neocall_frame_fn)(void *ctx, int remote, const uint8_t *bgra, int width, int height, int stride);

// {"inputs":[{"id","name"}],"outputs":[...],"cameras":[...]}; free with neocall_free.
NEOCALL_API char *neocall_devices(void);
NEOCALL_API void neocall_free(char *s);

typedef struct neocall_ice {
    const char *urls; // space-separated, e.g. "turn:host:3478?transport=udp turn:host:3478?transport=tcp"
    const char *username;
    const char *credential;
} neocall_ice;

// Device ids come from neocall_devices(); empty or NULL means the system default.
typedef struct neocall_config {
    const neocall_ice *ice;
    int ice_count;
    int video; // start with the camera on
    const char *input;
    const char *output;
    const char *camera;
} neocall_config;

NEOCALL_API neocall *neocall_create(const neocall_config *cfg, neocall_event_fn on_event, neocall_frame_fn on_frame, void *ctx);
// Caller: creates the offer; the result arrives as "local_sdp".
NEOCALL_API void neocall_offer(neocall *c);
// Callee: applies the peer's offer and creates the answer ("local_sdp").
NEOCALL_API void neocall_answer(neocall *c, const char *offer_sdp);
// Caller: applies the peer's answer.
NEOCALL_API void neocall_set_answer(neocall *c, const char *answer_sdp);
NEOCALL_API void neocall_set_mic(neocall *c, int enabled);
// Starts or stops the camera without renegotiation (the video m-line always exists).
// Returns 1 while the camera captures, 0 when it is off or could not start.
NEOCALL_API int neocall_set_camera(neocall *c, int enabled);
// Stops media and frees the call. No callbacks run after it returns.
NEOCALL_API void neocall_destroy(neocall *c);
