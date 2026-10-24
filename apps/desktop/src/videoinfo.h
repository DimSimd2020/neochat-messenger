#pragma once
#include <QSize>
#include <QString>

// What a video message carries besides the file: its first frame, the picture size and the length.
namespace videoinfo {
struct Info {
    // A small JPEG as base64, empty when the system has no picture of the video.
    QString thumb;
    QSize size;
    qint64 durationMs = 0;
};

// Asks the system (the Windows shell, the same source as the Explorer thumbnails) about a video file.
// Everything it cannot tell stays empty. Run it off the interface thread.
Info read(const QString &path);
}
