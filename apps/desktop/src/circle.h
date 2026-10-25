#pragma once

#include "audioio.h"

#include <QElapsedTimer>
#include <QImage>
#include <QMutex>
#include <QObject>
#include <QThread>
#include <QTimer>
#include <atomic>
#include <deque>
#include <memory>

// Recording a circle: the first camera of the system and the default microphone become a square
// H.264 + AAC MP4. The picture is cropped to a square in the middle, as the circle shows it.
class CameraRecorder : public QObject {
    Q_OBJECT
public:
    static constexpr int kSide = 480;
    static constexpr qint64 kMaxMs = 60000;
    explicit CameraRecorder(QObject *parent = nullptr);
    ~CameraRecorder() override;
    // Opens the camera and starts the live preview. False with a user-facing reason on failure.
    bool open(QString *error);
    // Starts the file; the preview goes on.
    bool startRecording(QString *error);
    // Ends the file (emits finished) and releases the camera.
    void stopRecording();
    // Releases the camera and removes a half-written file.
    void cancel();
    bool recording() const { return m_recording; }
    qint64 elapsedMs() const { return m_recording ? m_clock.elapsed() : 0; }

signals:
    void frame(const QImage &square); // preview, mirrored like a mirror
    void level(qreal volume);
    void finished(const QString &path, qint64 durationMs, const QImage &poster);
    void failed(const QString &reason);

private:
    class Capture;
    void onMic(const QByteArray &pcm);
    void onFrame(const QImage &square, bool wanted);
    void close();

    struct Impl;
    std::unique_ptr<Impl> d;
    MicCapture m_mic;
    QTimer m_limit;
    QElapsedTimer m_clock;
    std::atomic<bool> m_recording{false};
    QString m_path;
    QImage m_poster;
    qint64 m_audioSamples = 0;
    friend class Capture;
};

// Decodes the picture of a circle for playback: square frames (360 px) with their time. The sound
// is played by the caller from mf::decodeAudio, which also gives the clock.
class CircleDecoder : public QThread {
    Q_OBJECT
public:
    struct Frame {
        qint64 ms = 0;
        QImage image;
    };
    explicit CircleDecoder(const QString &path, QObject *parent = nullptr);
    ~CircleDecoder() override;
    // The newest frame that is due at `ms`, or a null image when none is yet.
    QImage frameAt(qint64 ms);
    bool failed() const { return m_failed; }
    void cancel() { m_cancel = true; }

protected:
    void run() override;

private:
    QString m_path;
    QMutex m_lock;
    std::deque<Frame> m_queue;
    std::atomic<bool> m_cancel{false};
    std::atomic<bool> m_failed{false};
};
