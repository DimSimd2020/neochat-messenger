#pragma once

#include <QString>
#include <atomic>
#include <functional>

// Shrinks a video before it is sent (docs/30-voice-circles-stt.md): small clips go untouched, others
// become H.264 + AAC in MP4, 720p at most, with a bitrate chosen so that the file stays under the
// attachment limit. The same rules are used by the phone apps.
namespace videocompress {

constexpr qint64 kLimit = 50ll << 20;  // what the core accepts
constexpr qint64 kBudget = 45ll << 20; // what compression aims at, so that the tags and headers fit

struct Result {
    bool ok = false;
    bool compressed = false; // false: `path` is the original
    QString path;
    QString error;
};

// Compresses `src` into `dst` when that is needed. `progress` gets 0..100 on the calling thread;
// set `cancel` to stop. Blocks; run it off the interface thread.
Result run(const QString &src, const QString &dst, const std::function<void(int)> &progress, const std::atomic<bool> &cancel);

}
