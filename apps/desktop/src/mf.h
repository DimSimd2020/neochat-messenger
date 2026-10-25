#pragma once

#include <QByteArray>
#include <QImage>
#include <QString>
#include <vector>

// Windows Media Foundation helpers shared by voice messages, circles, video compression and
// speech recognition. Nothing here touches the interface; run it on worker threads.
namespace mf {

// Starts Media Foundation (once per process) and COM on the calling thread. False when the
// system has no Media Foundation (N editions of Windows without the Media Feature Pack).
bool init();

// Interleaved 16-bit samples.
struct Pcm {
    int rate = 0;
    int channels = 0;
    QByteArray data;
    qint64 frames() const { return channels > 0 ? data.size() / (2 * channels) : 0; }
    qint64 ms() const { return rate > 0 ? frames() * 1000 / rate : 0; }
};

// Decodes the first audio track of a file in its own rate and channel count (at most two).
// False when there is none or the system cannot decode it.
bool decodeAudio(const QString &path, Pcm *out);

// Mono floats in [-1, 1] at `rate`, for the speech recognizer.
std::vector<float> toMonoFloat(const Pcm &pcm, int rate);

// Linear-interpolation rate converter that keeps its place between chunks.
class Resampler {
public:
    Resampler(int fromRate, int toRate, int channels);
    QByteArray process(const QByteArray &interleaved16);

private:
    double m_step;
    double m_pos = 0;
    int m_channels;
    std::vector<int16_t> m_tail; // the last frame of the previous chunk
};

// Same speech at `speed` times the tempo and the same pitch (waveform-similarity overlap-add).
Pcm stretch(const Pcm &in, double speed);

// 8-bit bars (0..255) of the loudness of 16-bit mono samples, for the waveform of a voice message.
QByteArray waveform(const QByteArray &mono16, int bars);

// NV12 (Y plane then interleaved UV) from a BGRA image whose side lengths are even.
QByteArray toNv12(const QImage &bgra);

// A small JPEG as base64 that fits the encrypted envelope; empty if the frame is null.
QString thumbBase64(const QImage &frame);

}
