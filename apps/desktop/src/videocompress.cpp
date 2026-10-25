#include "videocompress.h"

#include "audioio.h"
#include "mf.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QTransform>
#include <algorithm>
#include <cmath>

#include <windows.h>

#include <codecapi.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <wrl/client.h>

using Microsoft::WRL::ComPtr;

namespace videocompress {

namespace {

struct Rung {
    int longSide, shortSide, videoBps;
};
// From best to smallest; the first one that fits the budget is used.
constexpr Rung kLadder[] = {{1280, 720, 2500000}, {854, 480, 1200000}, {640, 360, 700000}};
constexpr int kAudioBps = 128000;
constexpr qint64 kKeepAsIs = 10ll << 20;

struct Probe {
    int width = 0, height = 0; // as shown, after the rotation
    int rotation = 0;
    double fps = 30;
    qint64 durationMs = 0;
    bool audio = false;
};

bool probe(const std::wstring &name, Probe *out)
{
    ComPtr<IMFSourceReader> reader;
    if (FAILED(MFCreateSourceReaderFromURL(name.c_str(), nullptr, &reader)))
        return false;
    ComPtr<IMFMediaType> t;
    if (FAILED(reader->GetNativeMediaType((DWORD)MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0, &t)))
        return false;
    UINT32 w = 0, h = 0, num = 0, den = 0, rot = 0;
    MFGetAttributeSize(t.Get(), MF_MT_FRAME_SIZE, &w, &h);
    if (SUCCEEDED(MFGetAttributeRatio(t.Get(), MF_MT_FRAME_RATE, &num, &den)) && den > 0 && num > 0)
        out->fps = double(num) / den;
    t->GetUINT32(MF_MT_VIDEO_ROTATION, &rot);
    out->rotation = int(rot);
    out->width = int((rot == 90 || rot == 270) ? h : w);
    out->height = int((rot == 90 || rot == 270) ? w : h);
    PROPVARIANT v;
    PropVariantInit(&v);
    if (SUCCEEDED(reader->GetPresentationAttribute((DWORD)MF_SOURCE_READER_MEDIASOURCE, MF_PD_DURATION, &v)) && v.vt == VT_UI8)
        out->durationMs = qint64(v.uhVal.QuadPart / 10000);
    PropVariantClear(&v);
    ComPtr<IMFMediaType> a;
    out->audio = SUCCEEDED(reader->GetNativeMediaType((DWORD)MF_SOURCE_READER_FIRST_AUDIO_STREAM, 0, &a));
    return out->width > 0 && out->height > 0 && out->durationMs > 0;
}

// The first rung whose estimated file fits the budget; -1 when even the smallest does not.
int chooseRung(const Probe &p)
{
    const double seconds = double(p.durationMs) / 1000.0;
    for (int i = 0; i < int(std::size(kLadder)); ++i)
        if ((kLadder[i].videoBps + kAudioBps) / 8.0 * seconds <= double(kBudget))
            return i;
    return -1;
}

QSize fitted(const Probe &p, const Rung &r)
{
    const bool landscape = p.width >= p.height;
    const double k = std::min({1.0, double(landscape ? r.longSide : r.shortSide) / p.width, double(landscape ? r.shortSide : r.longSide) / p.height});
    return QSize(std::max(2, int(p.width * k) & ~1), std::max(2, int(p.height * k) & ~1));
}

bool sampleToImage(IMFSample *sample, int w, int h, QImage *out)
{
    ComPtr<IMFMediaBuffer> buffer;
    if (FAILED(sample->ConvertToContiguousBuffer(&buffer)))
        return false;
    QImage img(w, h, QImage::Format_RGB32);
    ComPtr<IMF2DBuffer> flat;
    if (SUCCEEDED(buffer.As(&flat))) {
        BYTE *scan0 = nullptr;
        LONG pitch = 0;
        if (FAILED(flat->Lock2D(&scan0, &pitch)))
            return false;
        for (int r = 0; r < h; ++r)
            memcpy(img.scanLine(r), scan0 + ptrdiff_t(r) * pitch, size_t(w) * 4);
        flat->Unlock2D();
    } else {
        BYTE *data = nullptr;
        DWORD len = 0;
        if (FAILED(buffer->Lock(&data, nullptr, &len)) || len < DWORD(w) * DWORD(h) * 4)
            return false;
        for (int r = 0; r < h; ++r)
            memcpy(img.scanLine(r), data + size_t(h - 1 - r) * w * 4, size_t(w) * 4);
        buffer->Unlock();
    }
    *out = img;
    return true;
}

bool writeSample(IMFSinkWriter *writer, DWORD stream, const QByteArray &bytes, LONGLONG time, LONGLONG duration)
{
    ComPtr<IMFMediaBuffer> buffer;
    if (FAILED(MFCreateMemoryBuffer(DWORD(bytes.size()), &buffer)))
        return false;
    BYTE *dst = nullptr;
    if (FAILED(buffer->Lock(&dst, nullptr, nullptr)))
        return false;
    memcpy(dst, bytes.constData(), size_t(bytes.size()));
    buffer->Unlock();
    buffer->SetCurrentLength(DWORD(bytes.size()));
    ComPtr<IMFSample> sample;
    MFCreateSample(&sample);
    sample->AddBuffer(buffer.Get());
    sample->SetSampleTime(time);
    sample->SetSampleDuration(duration);
    return SUCCEEDED(writer->WriteSample(stream, sample.Get()));
}

// One pass at one rung. Returns an empty error on success.
QString transcode(const std::wstring &in, const std::wstring &out, const Probe &p, const Rung &rung, const std::function<void(int)> &progress,
                  const std::atomic<bool> &cancel)
{
    const QSize size = fitted(p, rung);
    const double fps = std::clamp(p.fps, 10.0, 30.0);
    ComPtr<IMFAttributes> opts;
    MFCreateAttributes(&opts, 1);
    opts->SetUINT32(MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING, TRUE);
    ComPtr<IMFSourceReader> video;
    if (FAILED(MFCreateSourceReaderFromURL(in.c_str(), opts.Get(), &video)))
        return QStringLiteral("Не удалось открыть видео");
    const DWORD vs = (DWORD)MF_SOURCE_READER_FIRST_VIDEO_STREAM;
    video->SetStreamSelection((DWORD)MF_SOURCE_READER_ALL_STREAMS, FALSE);
    video->SetStreamSelection(vs, TRUE);
    ComPtr<IMFMediaType> want;
    MFCreateMediaType(&want);
    want->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    want->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_RGB32);
    if (FAILED(video->SetCurrentMediaType(vs, nullptr, want.Get())))
        return QStringLiteral("Windows не умеет читать это видео");
    ComPtr<IMFMediaType> cur;
    UINT32 sw = 0, sh = 0;
    if (FAILED(video->GetCurrentMediaType(vs, &cur)))
        return QStringLiteral("Windows не умеет читать это видео");
    MFGetAttributeSize(cur.Get(), MF_MT_FRAME_SIZE, &sw, &sh);

    ComPtr<IMFSourceReader> audio;
    DWORD audioStream = (DWORD)MF_SOURCE_READER_FIRST_AUDIO_STREAM;
    int audioRate = 0, audioChannels = 0;
    if (p.audio && SUCCEEDED(MFCreateSourceReaderFromURL(in.c_str(), nullptr, &audio))) {
        audio->SetStreamSelection((DWORD)MF_SOURCE_READER_ALL_STREAMS, FALSE);
        audio->SetStreamSelection(audioStream, TRUE);
        ComPtr<IMFMediaType> pcm;
        MFCreateMediaType(&pcm);
        pcm->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio);
        pcm->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_PCM);
        pcm->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, 16);
        pcm->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, 2);
        ComPtr<IMFMediaType> got;
        UINT32 r = 0, c = 0;
        if (SUCCEEDED(audio->SetCurrentMediaType(audioStream, nullptr, pcm.Get())) && SUCCEEDED(audio->GetCurrentMediaType(audioStream, &got))) {
            got->GetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, &r);
            got->GetUINT32(MF_MT_AUDIO_NUM_CHANNELS, &c);
        }
        if (r == 0 || c == 0 || c > 2)
            audio.Reset();
        else {
            audioRate = int(r);
            audioChannels = int(c);
        }
    }

    ComPtr<IMFAttributes> attrs;
    MFCreateAttributes(&attrs, 1);
    attrs->SetGUID(MF_TRANSCODE_CONTAINERTYPE, MFTranscodeContainerType_MPEG4);
    ComPtr<IMFSinkWriter> writer;
    if (FAILED(MFCreateSinkWriterFromURL(out.c_str(), nullptr, attrs.Get(), &writer)))
        return QStringLiteral("Не удалось создать файл");
    ComPtr<IMFMediaType> vo, vi;
    MFCreateMediaType(&vo);
    vo->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    vo->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264);
    vo->SetUINT32(MF_MT_AVG_BITRATE, UINT32(rung.videoBps));
    vo->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
    vo->SetUINT32(MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_Main);
    MFSetAttributeSize(vo.Get(), MF_MT_FRAME_SIZE, UINT32(size.width()), UINT32(size.height()));
    MFSetAttributeRatio(vo.Get(), MF_MT_FRAME_RATE, UINT32(std::lround(fps * 1000)), 1000);
    MFSetAttributeRatio(vo.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
    MFCreateMediaType(&vi);
    vi->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    vi->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
    vi->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
    MFSetAttributeSize(vi.Get(), MF_MT_FRAME_SIZE, UINT32(size.width()), UINT32(size.height()));
    MFSetAttributeRatio(vi.Get(), MF_MT_FRAME_RATE, UINT32(std::lround(fps * 1000)), 1000);
    MFSetAttributeRatio(vi.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
    DWORD vstream = 0;
    if (FAILED(writer->AddStream(vo.Get(), &vstream)) || FAILED(writer->SetInputMediaType(vstream, vi.Get(), nullptr)))
        return QStringLiteral("В Windows нет кодека H.264 для сжатия видео");
    constexpr int kOutRate = 48000;
    unsigned long astream = 0;
    if (audio && !addAacStream(writer.Get(), kOutRate, audioChannels, kAudioBps / 8, &astream))
        audio.Reset();
    if (FAILED(writer->BeginWriting()))
        return QStringLiteral("Не удалось начать запись сжатого видео");

    mf::Resampler resampler(std::max(audioRate, 1), kOutRate, std::max(audioChannels, 1));
    LONGLONG audioWritten = 0; // 100 ns
    bool audioDone = !audio;
    auto pumpAudio = [&](LONGLONG upTo) {
        while (!audioDone && audioWritten < upTo) {
            DWORD flags = 0;
            ComPtr<IMFSample> s;
            if (FAILED(audio->ReadSample(audioStream, 0, nullptr, &flags, nullptr, &s)) || (flags & (MF_SOURCE_READERF_ENDOFSTREAM | MF_SOURCE_READERF_ERROR))) {
                audioDone = true;
                break;
            }
            if (!s)
                continue;
            ComPtr<IMFMediaBuffer> b;
            if (FAILED(s->ConvertToContiguousBuffer(&b)))
                continue;
            BYTE *data = nullptr;
            DWORD len = 0;
            if (FAILED(b->Lock(&data, nullptr, &len)))
                continue;
            const QByteArray raw(reinterpret_cast<const char *>(data), qsizetype(len));
            b->Unlock();
            const QByteArray converted = audioRate == kOutRate ? raw : resampler.process(raw);
            if (converted.isEmpty())
                continue;
            const LONGLONG dur = LONGLONG(converted.size() / (2 * audioChannels)) * 10000000 / kOutRate;
            writeSample(writer.Get(), astream, converted, audioWritten, dur);
            audioWritten += dur;
        }
    };

    const LONGLONG total = LONGLONG(p.durationMs) * 10000;
    LONGLONG first = -1, lastKept = -1000000000;
    const LONGLONG minGap = LONGLONG(10000000.0 / fps * 0.9);
    int lastPercent = -1;
    for (;;) {
        if (cancel)
            return QStringLiteral("cancelled");
        DWORD flags = 0;
        LONGLONG ts = 0;
        ComPtr<IMFSample> sample;
        if (FAILED(video->ReadSample(vs, 0, nullptr, &flags, &ts, &sample)) || (flags & (MF_SOURCE_READERF_ENDOFSTREAM | MF_SOURCE_READERF_ERROR)))
            break;
        if (!sample)
            continue;
        if (first < 0)
            first = ts;
        const LONGLONG t = std::max<LONGLONG>(0, ts - first);
        if (t - lastKept < minGap)
            continue;
        QImage img;
        if (!sampleToImage(sample.Get(), int(sw), int(sh), &img))
            continue;
        if (p.rotation == 90 || p.rotation == 180 || p.rotation == 270)
            img = img.transformed(QTransform().rotate(p.rotation));
        if (img.size() != size)
            img = img.scaled(size, Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
        lastKept = t;
        writeSample(writer.Get(), vstream, mf::toNv12(img), t, LONGLONG(10000000.0 / fps));
        pumpAudio(t + 5000000);
        const int percent = int(std::min<LONGLONG>(99, t * 100 / std::max<LONGLONG>(total, 1)));
        if (percent != lastPercent) {
            lastPercent = percent;
            progress(percent);
        }
    }
    pumpAudio(std::numeric_limits<LONGLONG>::max());
    if (FAILED(writer->Finalize()))
        return QStringLiteral("Не удалось завершить сжатое видео");
    return {};
}

}

Result run(const QString &src, const QString &dst, const std::function<void(int)> &progress, const std::atomic<bool> &cancel)
{
    Result res;
    res.path = src;
    const qint64 size = QFileInfo(src).size();
    if (!mf::init()) {
        res.ok = size <= kLimit;
        if (!res.ok)
            res.error = QStringLiteral("Видео больше 50 МБ, а сжать его нечем: в Windows нет Media Foundation");
        return res;
    }
    const std::wstring in = QDir::toNativeSeparators(src).toStdWString();
    const std::wstring out = QDir::toNativeSeparators(dst).toStdWString();
    Probe p;
    const bool known = probe(in, &p);
    const int first = known ? chooseRung(p) : -1;
    if (known && first >= 0) {
        const double seconds = double(p.durationMs) / 1000.0;
        const double estimate = (kLadder[first].videoBps + kAudioBps) / 8.0 * seconds;
        const bool tiny = size <= kKeepAsIs && std::max(p.width, p.height) <= 1920;
        // A file that is already no bigger than the compressed one would be is not worth redoing.
        if (size <= kLimit && (tiny || double(size) <= estimate)) {
            res.ok = true;
            return res;
        }
    } else if (size <= kLimit) {
        // Windows cannot read the video (or it is too long for any rung): the original goes if it fits.
        res.ok = true;
        return res;
    } else if (known) {
        res.error = QStringLiteral("Видео слишком длинное: даже в сжатом виде оно больше 50 МБ. Обрежьте его");
        return res;
    } else {
        res.error = QStringLiteral("Видео больше 50 МБ, а Windows не может его прочитать для сжатия");
        return res;
    }
    for (int rung = first; rung < int(std::size(kLadder)); ++rung) {
        QFile::remove(dst);
        const QString failure = transcode(in, out, p, kLadder[rung], progress, cancel);
        if (!failure.isEmpty()) {
            QFile::remove(dst);
            res.error = failure == QLatin1String("cancelled") ? QString() : failure;
            // A failed pass is not fatal when the original fits.
            res.ok = failure != QLatin1String("cancelled") && size <= kLimit;
            return res;
        }
        if (QFileInfo(dst).size() <= kLimit) {
            progress(100);
            res.ok = true;
            res.compressed = true;
            res.path = dst;
            return res;
        }
    }
    QFile::remove(dst);
    res.error = QStringLiteral("Не удалось сжать видео до 50 МБ. Обрежьте его");
    return res;
}

}
