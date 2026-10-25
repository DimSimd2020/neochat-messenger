#pragma once

#include "mf.h"

#include <QElapsedTimer>
#include <QObject>
#include <QTimer>
#include <deque>
#include <memory>
#include <vector>

#include <windows.h>

#include <mmsystem.h>

// Plays 16-bit PCM through the default output device. Not thread-safe: use it from one thread and
// call tick() about every 25 ms to keep the device fed.
class PcmOutput {
public:
    PcmOutput() = default;
    ~PcmOutput() { stop(); }
    PcmOutput(const PcmOutput &) = delete;
    PcmOutput &operator=(const PcmOutput &) = delete;

    bool start(const mf::Pcm &pcm, qint64 fromFrame);
    void tick();
    void pause();
    void resume();
    void stop();
    bool active() const { return m_h != nullptr; }
    bool finished() const { return m_finished; }
    // Frames of the source played so far.
    qint64 positionFrame() const;

private:
    struct Block {
        WAVEHDR hdr{};
        QByteArray data;
    };
    HWAVEOUT m_h = nullptr;
    mf::Pcm m_pcm;
    qint64 m_fed = 0;
    qint64 m_base = 0;
    bool m_finished = false;
    std::deque<std::unique_ptr<Block>> m_blocks;
};

/// The microphone (the system default) as 16-bit mono PCM at 44.1 kHz, delivered in chunks of
// about 100 ms on the thread that owns this object.
class MicCapture : public QObject {
    Q_OBJECT
public:
    static constexpr int kRate = 44100;
    explicit MicCapture(QObject *parent = nullptr);
    ~MicCapture() override;
    bool start(QString *error);
    // Delivers what is still buffered, then closes the device.
    void stop();
    bool running() const { return m_in != nullptr; }

signals:
    void chunk(const QByteArray &pcm);

private:
    void poll();
    void release();

    static constexpr int kBuffers = 8;
    HWAVEIN m_in = nullptr;
    WAVEHDR m_hdr[kBuffers]{};
    QByteArray m_data[kBuffers];
    QTimer m_timer;
};

// Records the microphone as AAC in an .m4a file.
class VoiceRecorder : public QObject {
    Q_OBJECT
public:
    explicit VoiceRecorder(QObject *parent = nullptr);
    ~VoiceRecorder() override;
    // False with a user-facing reason when the microphone or the encoder is unavailable.
    bool start(QString *error);
    // Finishes the file and emits finished(); the file stays for the caller.
    void stop();
    // Drops the recording and its file.
    void cancel();
    qint64 elapsedMs() const { return m_samples * 1000 / MicCapture::kRate; }
    bool recording() const { return m_mic.running(); }
    // 8-bit loudness bars of what was recorded (valid in finished()).
    static constexpr int kBars = 64;

signals:
    void level(qreal volume); // 0..1, about every 100 ms
    void finished(const QString &path, qint64 durationMs, const QByteArray &wave);
    void failed(const QString &reason);

private:
    void onChunk(const QByteArray &pcm);

    struct Impl;
    std::unique_ptr<Impl> d;
    MicCapture m_mic;
    qint64 m_samples = 0;
    QString m_path;
    std::vector<double> m_rms; // one value per chunk of 100 ms
};

struct IMFSinkWriter;
// Adds an AAC stream (44100 or 48000 Hz) fed with 16-bit PCM to an MPEG-4 sink writer.
// False when the system lacks the encoder.
bool addAacStream(IMFSinkWriter *writer, int rate, int channels, int bytesPerSecond, unsigned long *stream);
