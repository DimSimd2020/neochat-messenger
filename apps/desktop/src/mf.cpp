#include "mf.h"

#include <QBuffer>
#include <QDir>
#include <algorithm>
#include <cmath>
#include <mutex>
#include <string>

#include <windows.h>

#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <wrl/client.h>

using Microsoft::WRL::ComPtr;

namespace mf {

namespace {
// COM for every thread that asks for Media Foundation; released with the thread.
struct ComGuard {
    bool ok;
    ComGuard() : ok(SUCCEEDED(CoInitializeEx(nullptr, COINIT_MULTITHREADED))) {}
    ~ComGuard()
    {
        if (ok)
            CoUninitialize();
    }
};

constexpr qint64 kMaxPcmBytes = 600ll << 20;
}

bool init()
{
    thread_local ComGuard com;
    static std::once_flag once;
    static bool started = false;
    std::call_once(once, [] { started = SUCCEEDED(MFStartup(MF_VERSION, MFSTARTUP_FULL)); });
    return started;
}

bool decodeAudio(const QString &path, Pcm *out)
{
    if (!init())
        return false;
    const std::wstring name = QDir::toNativeSeparators(path).toStdWString();
    ComPtr<IMFSourceReader> reader;
    if (FAILED(MFCreateSourceReaderFromURL(name.c_str(), nullptr, &reader)))
        return false;
    const DWORD stream = (DWORD)MF_SOURCE_READER_FIRST_AUDIO_STREAM;
    reader->SetStreamSelection((DWORD)MF_SOURCE_READER_ALL_STREAMS, FALSE);
    if (FAILED(reader->SetStreamSelection(stream, TRUE)))
        return false;
    ComPtr<IMFMediaType> want;
    MFCreateMediaType(&want);
    want->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio);
    want->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_PCM);
    want->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, 16);
    ComPtr<IMFMediaType> native;
    UINT32 nativeChannels = 0;
    if (SUCCEEDED(reader->GetNativeMediaType(stream, 0, &native)))
        native->GetUINT32(MF_MT_AUDIO_NUM_CHANNELS, &nativeChannels);
    if (nativeChannels > 2)
        want->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, 2);
    if (FAILED(reader->SetCurrentMediaType(stream, nullptr, want.Get())))
        return false;
    ComPtr<IMFMediaType> cur;
    if (FAILED(reader->GetCurrentMediaType(stream, &cur)))
        return false;
    UINT32 rate = 0, channels = 0;
    cur->GetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, &rate);
    cur->GetUINT32(MF_MT_AUDIO_NUM_CHANNELS, &channels);
    if (rate == 0 || channels == 0 || channels > 2)
        return false;
    out->rate = int(rate);
    out->channels = int(channels);
    out->data.clear();
    for (;;) {
        DWORD flags = 0;
        ComPtr<IMFSample> sample;
        if (FAILED(reader->ReadSample(stream, 0, nullptr, &flags, nullptr, &sample)))
            return !out->data.isEmpty();
        if (flags & (MF_SOURCE_READERF_ENDOFSTREAM | MF_SOURCE_READERF_ERROR))
            break;
        if (!sample)
            continue;
        ComPtr<IMFMediaBuffer> buffer;
        if (FAILED(sample->ConvertToContiguousBuffer(&buffer)))
            continue;
        BYTE *bytes = nullptr;
        DWORD len = 0;
        if (SUCCEEDED(buffer->Lock(&bytes, nullptr, &len))) {
            out->data.append(reinterpret_cast<const char *>(bytes), qsizetype(len));
            buffer->Unlock();
        }
        if (out->data.size() > kMaxPcmBytes)
            break;
    }
    return !out->data.isEmpty();
}

std::vector<float> toMonoFloat(const Pcm &pcm, int rate)
{
    const int16_t *s = reinterpret_cast<const int16_t *>(pcm.data.constData());
    const qint64 n = pcm.frames();
    std::vector<float> mono(static_cast<size_t>(n));
    for (qint64 i = 0; i < n; ++i) {
        float sum = 0;
        for (int c = 0; c < pcm.channels; ++c)
            sum += s[i * pcm.channels + c];
        mono[size_t(i)] = sum / (32768.f * pcm.channels);
    }
    if (pcm.rate == rate || pcm.rate <= 0)
        return mono;
    const double ratio = double(pcm.rate) / rate; // input samples per output sample
    const size_t m = size_t(double(n) / ratio);
    std::vector<float> out(m);
    if (ratio > 1) {
        // Averaging over the window of one output sample is the low-pass that keeps the rate change clean.
        std::vector<double> prefix(size_t(n) + 1, 0.0);
        for (qint64 i = 0; i < n; ++i)
            prefix[size_t(i) + 1] = prefix[size_t(i)] + mono[size_t(i)];
        for (size_t i = 0; i < m; ++i) {
            const size_t a = size_t(double(i) * ratio);
            size_t b = size_t(double(i + 1) * ratio);
            b = std::min(std::max(b, a + 1), size_t(n));
            out[i] = float((prefix[b] - prefix[a]) / double(b - a));
        }
    } else {
        for (size_t i = 0; i < m; ++i) {
            const double pos = double(i) * ratio;
            const size_t a = size_t(pos);
            const size_t b = std::min(a + 1, size_t(n) - 1);
            const double f = pos - double(a);
            out[i] = float(mono[a] * (1 - f) + mono[b] * f);
        }
    }
    return out;
}

Resampler::Resampler(int fromRate, int toRate, int channels)
    : m_step(double(fromRate) / toRate), m_channels(channels), m_tail(size_t(channels), 0)
{
}

QByteArray Resampler::process(const QByteArray &in)
{
    const int ch = m_channels;
    const qint64 n = in.size() / (2 * ch);
    const int16_t *src = reinterpret_cast<const int16_t *>(in.constData());
    QByteArray out;
    out.reserve(qsizetype(double(in.size()) / m_step) + 16);
    auto at = [&](qint64 i, int c) -> double { return i < 0 ? m_tail[size_t(c)] : src[i * ch + c]; };
    while (m_pos + 1 < double(n)) {
        const qint64 i0 = qint64(std::floor(m_pos));
        const double f = m_pos - double(i0);
        for (int c = 0; c < ch; ++c) {
            const int16_t v = int16_t(std::lround(at(i0, c) * (1 - f) + at(i0 + 1, c) * f));
            out.append(reinterpret_cast<const char *>(&v), 2);
        }
        m_pos += m_step;
    }
    m_pos -= double(n);
    if (n > 0)
        for (int c = 0; c < ch; ++c)
            m_tail[size_t(c)] = src[(n - 1) * ch + c];
    return out;
}

Pcm stretch(const Pcm &in, double speed)
{
    const qint64 n = in.frames();
    if (speed <= 1.001 || n < 8192 || in.rate <= 0)
        return in;
    const int ch = in.channels;
    const int16_t *x = reinterpret_cast<const int16_t *>(in.data.constData());
    const int N = (in.rate * 30 / 1000) & ~1; // window of about 30 ms
    const int Hs = N / 2;
    const int radius = in.rate * 12 / 1000;
    std::vector<float> mono(static_cast<size_t>(n));
    for (qint64 i = 0; i < n; ++i) {
        float s = 0;
        for (int c = 0; c < ch; ++c)
            s += x[i * ch + c];
        mono[size_t(i)] = s / ch;
    }
    std::vector<float> hann(static_cast<size_t>(N));
    for (int i = 0; i < N; ++i)
        hann[size_t(i)] = 0.5f * (1.f - std::cos(2.f * float(M_PI) * float(i) / float(N)));
    const qint64 outFrames = qint64(double(n) / speed) + 2 * N;
    std::vector<float> acc(size_t(outFrames) * ch, 0.f), weight(size_t(outFrames), 0.f);
    auto place = [&](qint64 from, qint64 to) {
        for (int i = 0; i < N && to + i < outFrames && from + i < n; ++i) {
            const float w = hann[size_t(i)];
            weight[size_t(to + i)] += w;
            for (int c = 0; c < ch; ++c)
                acc[size_t(to + i) * ch + c] += x[(from + i) * ch + c] * w;
        }
    };
    // Compares every fourth sample first, then settles the exact offset.
    auto score = [&](qint64 cand, qint64 ref, int stride) {
        double dot = 0, energy = 1e-9;
        for (int i = 0; i < N; i += stride) {
            dot += double(mono[size_t(cand + i)]) * mono[size_t(ref + i)];
            energy += double(mono[size_t(cand + i)]) * mono[size_t(cand + i)];
        }
        return dot / std::sqrt(energy);
    };
    place(0, 0);
    qint64 prev = 0;
    qint64 last = 0;
    for (qint64 k = 1;; ++k) {
        const qint64 nominal = qint64(double(k) * Hs * speed);
        const qint64 natural = prev + Hs;
        if (natural + N >= n || nominal + N >= n || k * Hs >= outFrames)
            break;
        const qint64 lo = std::max<qint64>(0, nominal - radius);
        const qint64 hi = std::min<qint64>(n - N - 1, nominal + radius);
        qint64 best = std::clamp<qint64>(nominal, lo, hi);
        double top = -1e30;
        for (qint64 cand = lo; cand <= hi; cand += 4) {
            const double s = score(cand, natural, 4);
            if (s > top) {
                top = s;
                best = cand;
            }
        }
        const qint64 from = std::max(lo, best - 4), to = std::min(hi, best + 4);
        top = -1e30;
        for (qint64 cand = from; cand <= to; ++cand) {
            const double s = score(cand, natural, 1);
            if (s > top) {
                top = s;
                best = cand;
            }
        }
        place(best, k * Hs);
        prev = best;
        last = k * Hs + N;
    }
    last = std::min(last, outFrames);
    Pcm out;
    out.rate = in.rate;
    out.channels = ch;
    out.data.resize(qsizetype(last) * ch * 2);
    int16_t *dst = reinterpret_cast<int16_t *>(out.data.data());
    for (qint64 i = 0; i < last; ++i) {
        const float w = weight[size_t(i)] > 1e-3f ? weight[size_t(i)] : 1.f;
        for (int c = 0; c < ch; ++c)
            dst[i * ch + c] = int16_t(std::clamp(std::lround(acc[size_t(i) * ch + c] / w), -32768l, 32767l));
    }
    return out;
}

QByteArray waveform(const QByteArray &mono16, int bars)
{
    const int16_t *s = reinterpret_cast<const int16_t *>(mono16.constData());
    const qint64 n = mono16.size() / 2;
    QByteArray out(bars, 0);
    if (n == 0 || bars <= 0)
        return out;
    std::vector<double> level(static_cast<size_t>(bars), 0.0);
    double top = 1.0;
    for (int b = 0; b < bars; ++b) {
        const qint64 from = n * b / bars, to = std::max(from + 1, n * (b + 1) / bars);
        double sum = 0;
        for (qint64 i = from; i < to; ++i)
            sum += double(s[i]) * s[i];
        level[size_t(b)] = std::sqrt(sum / double(to - from));
        top = std::max(top, level[size_t(b)]);
    }
    for (int b = 0; b < bars; ++b)
        out[b] = char(std::clamp(int(std::pow(level[size_t(b)] / top, 0.75) * 255.0), 6, 255));
    return out;
}

QByteArray toNv12(const QImage &bgra)
{
    const QImage img = bgra.format() == QImage::Format_ARGB32 || bgra.format() == QImage::Format_RGB32 ? bgra : bgra.convertToFormat(QImage::Format_RGB32);
    const int w = img.width() & ~1, h = img.height() & ~1;
    QByteArray out(w * h * 3 / 2, 0);
    uint8_t *y = reinterpret_cast<uint8_t *>(out.data());
    uint8_t *uv = y + w * h;
    for (int row = 0; row < h; ++row) {
        const QRgb *line = reinterpret_cast<const QRgb *>(img.constScanLine(row));
        for (int col = 0; col < w; ++col) {
            const int r = qRed(line[col]), g = qGreen(line[col]), b = qBlue(line[col]);
            y[row * w + col] = uint8_t(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
        }
    }
    for (int row = 0; row < h; row += 2) {
        const QRgb *a = reinterpret_cast<const QRgb *>(img.constScanLine(row));
        const QRgb *b = reinterpret_cast<const QRgb *>(img.constScanLine(row + 1));
        for (int col = 0; col < w; col += 2) {
            const QRgb p[4] = {a[col], a[col + 1], b[col], b[col + 1]};
            int r = 0, g = 0, bl = 0;
            for (QRgb q : p) {
                r += qRed(q);
                g += qGreen(q);
                bl += qBlue(q);
            }
            r /= 4;
            g /= 4;
            bl /= 4;
            uv[(row / 2) * w + col] = uint8_t(std::clamp(((-38 * r - 74 * g + 112 * bl + 128) >> 8) + 128, 0, 255));
            uv[(row / 2) * w + col + 1] = uint8_t(std::clamp(((112 * r - 94 * g - 18 * bl + 128) >> 8) + 128, 0, 255));
        }
    }
    return out;
}

QString thumbBase64(const QImage &frame)
{
    if (frame.isNull())
        return {};
    for (int side : {160, 120, 90}) {
        QByteArray jpeg;
        QBuffer buf(&jpeg);
        buf.open(QIODevice::WriteOnly);
        frame.scaled(side, side, Qt::KeepAspectRatio, Qt::SmoothTransformation).save(&buf, "JPEG", 55);
        const QByteArray b64 = jpeg.toBase64();
        if (b64.size() <= 12000)
            return QString::fromLatin1(b64);
    }
    return {};
}

}
