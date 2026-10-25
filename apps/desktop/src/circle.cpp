#include "circle.h"

#include "mf.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QMetaObject>
#include <QTransform>
#include <algorithm>
#include <cmath>
#include <mutex>

#include <windows.h>

#include <codecapi.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <wrl/client.h>

using Microsoft::WRL::ComPtr;

namespace {

// The middle square of a BGRX frame, scaled to `side`. `scan0` is the top row, `pitch` may be negative.
QImage squareOf(const BYTE *scan0, LONG pitch, int w, int h, int side, int rotation)
{
    QImage img(w, h, QImage::Format_RGB32);
    for (int r = 0; r < h; ++r)
        memcpy(img.scanLine(r), scan0 + ptrdiff_t(r) * pitch, size_t(w) * 4);
    if (rotation == 90 || rotation == 180 || rotation == 270)
        img = img.transformed(QTransform().rotate(rotation));
    const int s = std::min(img.width(), img.height());
    return img.copy((img.width() - s) / 2, (img.height() - s) / 2, s, s).scaled(side, side, Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
}

// A decoded sample as a square picture; false when the buffer cannot be read.
bool sampleImage(IMFSample *sample, int w, int h, int side, int rotation, QImage *out)
{
    ComPtr<IMFMediaBuffer> buffer;
    if (FAILED(sample->ConvertToContiguousBuffer(&buffer)))
        return false;
    ComPtr<IMF2DBuffer> flat;
    if (SUCCEEDED(buffer.As(&flat))) {
        BYTE *scan0 = nullptr;
        LONG pitch = 0;
        if (SUCCEEDED(flat->Lock2D(&scan0, &pitch))) {
            *out = squareOf(scan0, pitch, w, h, side, rotation);
            flat->Unlock2D();
            return true;
        }
    }
    BYTE *data = nullptr;
    DWORD len = 0;
    if (FAILED(buffer->Lock(&data, nullptr, &len)) || len < DWORD(w) * DWORD(h) * 4)
        return false;
    // Without a stride the rows follow each other; RGB32 from Media Foundation is bottom-up.
    *out = squareOf(data + size_t(h - 1) * w * 4, -LONG(w * 4), w, h, side, rotation);
    buffer->Unlock();
    return true;
}

void frameSize(IMFMediaType *type, UINT32 *w, UINT32 *h)
{
    MFGetAttributeSize(type, MF_MT_FRAME_SIZE, w, h);
}

}

// ---- recording ----

class CameraRecorder::Capture : public QThread {
public:
    Capture(CameraRecorder *owner, ComPtr<IMFSourceReader> reader, UINT32 w, UINT32 h) : m_owner(owner), m_reader(std::move(reader)), m_w(w), m_h(h) {}
    ~Capture() override { stopAndWait(); }
    void stopAndWait()
    {
        m_stop = true;
        wait(3000);
    }

protected:
    void run() override
    {
        mf::init();
        while (!m_stop) {
            DWORD flags = 0;
            ComPtr<IMFSample> sample;
            if (FAILED(m_reader->ReadSample((DWORD)MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0, nullptr, &flags, nullptr, &sample)))
                break;
            if (flags & (MF_SOURCE_READERF_ENDOFSTREAM | MF_SOURCE_READERF_ERROR))
                break;
            QImage square;
            if (!sample || !sampleImage(sample.Get(), int(m_w), int(m_h), kSide, 0, &square))
                continue;
            m_owner->onFrame(square, m_owner->m_recording);
        }
    }

private:
    CameraRecorder *m_owner;
    ComPtr<IMFSourceReader> m_reader;
    UINT32 m_w, m_h;
    std::atomic<bool> m_stop{false};
};

struct CameraRecorder::Impl {
    QMutex lock; // the writer is fed from the camera thread and the microphone
    ComPtr<IMFMediaSource> source;
    ComPtr<IMFSinkWriter> writer;
    unsigned long videoStream = 0, audioStream = 0;
    LONGLONG audio100ns = 0;
    LONGLONG lastVideo100ns = -1;
    std::unique_ptr<Capture> capture;
    bool posterTaken = false;
};

CameraRecorder::CameraRecorder(QObject *parent) : QObject(parent), d(new Impl), m_mic(this)
{
    m_limit.setInterval(int(kMaxMs));
    m_limit.setSingleShot(true);
    connect(&m_limit, &QTimer::timeout, this, &CameraRecorder::stopRecording);
    connect(&m_mic, &MicCapture::chunk, this, &CameraRecorder::onMic);
}

CameraRecorder::~CameraRecorder()
{
    cancel();
}

bool CameraRecorder::open(QString *error)
{
    auto fail = [&](const QString &why) {
        if (error)
            *error = why;
        close();
        return false;
    };
    if (!mf::init())
        return fail(QStringLiteral("В Windows нет Media Foundation: установите Media Feature Pack"));
    ComPtr<IMFAttributes> query;
    MFCreateAttributes(&query, 1);
    query->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE, MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID);
    IMFActivate **devices = nullptr;
    UINT32 count = 0;
    if (FAILED(MFEnumDeviceSources(query.Get(), &devices, &count)) || count == 0) {
        if (devices)
            CoTaskMemFree(devices);
        return fail(QStringLiteral("Камера не найдена"));
    }
    const HRESULT hr = devices[0]->ActivateObject(IID_PPV_ARGS(&d->source));
    for (UINT32 i = 0; i < count; ++i)
        devices[i]->Release();
    CoTaskMemFree(devices);
    if (FAILED(hr))
        return fail(QStringLiteral("Нет доступа к камере. Проверьте разрешения Windows: Параметры → Конфиденциальность → Камера"));
    ComPtr<IMFAttributes> opts;
    MFCreateAttributes(&opts, 1);
    opts->SetUINT32(MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING, TRUE);
    ComPtr<IMFSourceReader> reader;
    if (FAILED(MFCreateSourceReaderFromMediaSource(d->source.Get(), opts.Get(), &reader)))
        return fail(QStringLiteral("Не удалось открыть камеру"));
    const DWORD stream = (DWORD)MF_SOURCE_READER_FIRST_VIDEO_STREAM;
    // The smallest mode that still has 480 px on its short side; the largest if none does.
    ComPtr<IMFMediaType> best;
    UINT32 bw = 0, bh = 0;
    for (DWORD i = 0;; ++i) {
        ComPtr<IMFMediaType> t;
        if (FAILED(reader->GetNativeMediaType(stream, i, &t)))
            break;
        UINT32 w = 0, h = 0;
        frameSize(t.Get(), &w, &h);
        if (w == 0 || h == 0)
            continue;
        const bool big = std::min(w, h) >= UINT32(kSide), bestBig = std::min(bw, bh) >= UINT32(kSide);
        if (!best || (big && (!bestBig || w * h < bw * bh)) || (!big && !bestBig && w * h > bw * bh)) {
            best = t;
            bw = w;
            bh = h;
        }
    }
    if (!best || FAILED(reader->SetCurrentMediaType(stream, nullptr, best.Get())))
        return fail(QStringLiteral("Камера не поддерживает нужный режим"));
    ComPtr<IMFMediaType> want;
    MFCreateMediaType(&want);
    want->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    want->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_RGB32);
    MFSetAttributeSize(want.Get(), MF_MT_FRAME_SIZE, bw, bh);
    if (FAILED(reader->SetCurrentMediaType(stream, nullptr, want.Get())))
        return fail(QStringLiteral("Не удалось получить изображение с камеры"));
    d->capture = std::make_unique<Capture>(this, reader, bw, bh);
    d->capture->start();
    return true;
}

void CameraRecorder::onFrame(const QImage &square, bool wanted)
{
    // Runs on the camera thread.
    QMetaObject::invokeMethod(this, [this, mirrored = square.mirrored(true, false)] { emit frame(mirrored); }, Qt::QueuedConnection);
    if (!wanted)
        return;
    QMutexLocker lock(&d->lock);
    if (!d->writer)
        return;
    LONGLONG t = m_clock.nsecsElapsed() / 100;
    if (t <= d->lastVideo100ns)
        t = d->lastVideo100ns + 1;
    d->lastVideo100ns = t;
    if (!d->posterTaken) {
        d->posterTaken = true;
        m_poster = square;
    }
    const QByteArray nv12 = mf::toNv12(square);
    ComPtr<IMFMediaBuffer> buffer;
    ComPtr<IMFSample> sample;
    if (FAILED(MFCreateMemoryBuffer(DWORD(nv12.size()), &buffer)))
        return;
    BYTE *dst = nullptr;
    if (FAILED(buffer->Lock(&dst, nullptr, nullptr)))
        return;
    memcpy(dst, nv12.constData(), size_t(nv12.size()));
    buffer->Unlock();
    buffer->SetCurrentLength(DWORD(nv12.size()));
    MFCreateSample(&sample);
    sample->AddBuffer(buffer.Get());
    sample->SetSampleTime(t);
    sample->SetSampleDuration(333333);
    d->writer->WriteSample(d->videoStream, sample.Get());
}

bool CameraRecorder::startRecording(QString *error)
{
    auto fail = [&](const QString &why) {
        if (error)
            *error = why;
        QMutexLocker lock(&d->lock);
        d->writer.Reset();
        return false;
    };
    if (!d->capture)
        return fail(QStringLiteral("Камера не открыта"));
    m_path = QDir::temp().filePath(QStringLiteral("neochat-circle-%1.mp4").arg(QDateTime::currentMSecsSinceEpoch()));
    ComPtr<IMFAttributes> attrs;
    MFCreateAttributes(&attrs, 1);
    attrs->SetGUID(MF_TRANSCODE_CONTAINERTYPE, MFTranscodeContainerType_MPEG4);
    const std::wstring name = QDir::toNativeSeparators(m_path).toStdWString();
    ComPtr<IMFSinkWriter> writer;
    if (FAILED(MFCreateSinkWriterFromURL(name.c_str(), nullptr, attrs.Get(), &writer)))
        return fail(QStringLiteral("Не удалось создать файл записи"));
    ComPtr<IMFMediaType> out, in;
    MFCreateMediaType(&out);
    out->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    out->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264);
    out->SetUINT32(MF_MT_AVG_BITRATE, 1500000);
    out->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
    out->SetUINT32(MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_Main);
    MFSetAttributeSize(out.Get(), MF_MT_FRAME_SIZE, kSide, kSide);
    MFSetAttributeRatio(out.Get(), MF_MT_FRAME_RATE, 30, 1);
    MFSetAttributeRatio(out.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
    MFCreateMediaType(&in);
    in->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    in->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
    in->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
    MFSetAttributeSize(in.Get(), MF_MT_FRAME_SIZE, kSide, kSide);
    MFSetAttributeRatio(in.Get(), MF_MT_FRAME_RATE, 30, 1);
    MFSetAttributeRatio(in.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
    DWORD video = 0;
    if (FAILED(writer->AddStream(out.Get(), &video)) || FAILED(writer->SetInputMediaType(video, in.Get(), nullptr)))
        return fail(QStringLiteral("В Windows нет кодека H.264 для записи видео"));
    unsigned long audio = 0;
    if (!addAacStream(writer.Get(), MicCapture::kRate, 1, 12000, &audio) || FAILED(writer->BeginWriting()))
        return fail(QStringLiteral("В Windows нет кодека AAC для записи звука"));
    {
        QMutexLocker lock(&d->lock);
        d->writer = writer;
        d->videoStream = video;
        d->audioStream = audio;
        d->audio100ns = 0;
        d->lastVideo100ns = -1;
        d->posterTaken = false;
        m_audioSamples = 0;
        m_poster = {};
    }
    QString why;
    if (!m_mic.start(&why))
        return fail(why);
    m_clock.start();
    m_recording = true;
    m_limit.start();
    return true;
}

void CameraRecorder::onMic(const QByteArray &pcm)
{
    const int n = int(pcm.size() / 2);
    const int16_t *s = reinterpret_cast<const int16_t *>(pcm.constData());
    double sum = 0;
    for (int k = 0; k < n; ++k)
        sum += double(s[k]) * s[k];
    emit level(std::min(1.0, std::sqrt(sum / std::max(n, 1)) / 9000.0));
    QMutexLocker lock(&d->lock);
    if (!d->writer)
        return;
    ComPtr<IMFMediaBuffer> buffer;
    ComPtr<IMFSample> sample;
    if (FAILED(MFCreateMemoryBuffer(DWORD(pcm.size()), &buffer)))
        return;
    BYTE *dst = nullptr;
    if (FAILED(buffer->Lock(&dst, nullptr, nullptr)))
        return;
    memcpy(dst, pcm.constData(), size_t(pcm.size()));
    buffer->Unlock();
    buffer->SetCurrentLength(DWORD(pcm.size()));
    MFCreateSample(&sample);
    sample->AddBuffer(buffer.Get());
    const LONGLONG dur = LONGLONG(n) * 10000000 / MicCapture::kRate;
    sample->SetSampleTime(d->audio100ns);
    sample->SetSampleDuration(dur);
    d->audio100ns += dur;
    d->writer->WriteSample(d->audioStream, sample.Get());
    m_audioSamples += n;
}

void CameraRecorder::stopRecording()
{
    if (!m_recording)
        return;
    m_recording = false;
    m_limit.stop();
    const qint64 ms = m_clock.elapsed();
    m_mic.stop();
    bool wrote = false;
    {
        QMutexLocker lock(&d->lock);
        wrote = d->writer && SUCCEEDED(d->writer->Finalize());
        d->writer.Reset();
    }
    close();
    if (!wrote) {
        QFile::remove(m_path);
        emit failed(QStringLiteral("Не удалось сохранить запись"));
        return;
    }
    emit finished(m_path, ms, m_poster);
}

void CameraRecorder::close()
{
    if (d->capture) {
        d->capture->stopAndWait();
        d->capture.reset();
    }
    if (d->source) {
        d->source->Shutdown();
        d->source.Reset();
    }
}

void CameraRecorder::cancel()
{
    const bool had = m_recording || d->writer;
    m_recording = false;
    m_limit.stop();
    m_mic.stop();
    {
        QMutexLocker lock(&d->lock);
        d->writer.Reset();
    }
    close();
    if (had && !m_path.isEmpty())
        QFile::remove(m_path);
}

// ---- playback ----

CircleDecoder::CircleDecoder(const QString &path, QObject *parent) : QThread(parent), m_path(path) {}

CircleDecoder::~CircleDecoder()
{
    m_cancel = true;
    wait(3000);
}

QImage CircleDecoder::frameAt(qint64 ms)
{
    QMutexLocker lock(&m_lock);
    QImage out;
    while (!m_queue.empty() && m_queue.front().ms <= ms) {
        out = std::move(m_queue.front().image);
        m_queue.pop_front();
    }
    return out;
}

void CircleDecoder::run()
{
    if (!mf::init()) {
        m_failed = true;
        return;
    }
    const std::wstring name = QDir::toNativeSeparators(m_path).toStdWString();
    ComPtr<IMFAttributes> opts;
    MFCreateAttributes(&opts, 1);
    opts->SetUINT32(MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING, TRUE);
    ComPtr<IMFSourceReader> reader;
    const DWORD stream = (DWORD)MF_SOURCE_READER_FIRST_VIDEO_STREAM;
    if (FAILED(MFCreateSourceReaderFromURL(name.c_str(), opts.Get(), &reader))) {
        m_failed = true;
        return;
    }
    reader->SetStreamSelection((DWORD)MF_SOURCE_READER_ALL_STREAMS, FALSE);
    reader->SetStreamSelection(stream, TRUE);
    UINT32 rotation = 0;
    ComPtr<IMFMediaType> native;
    if (SUCCEEDED(reader->GetNativeMediaType(stream, 0, &native)))
        native->GetUINT32(MF_MT_VIDEO_ROTATION, &rotation);
    ComPtr<IMFMediaType> want;
    MFCreateMediaType(&want);
    want->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    want->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_RGB32);
    if (FAILED(reader->SetCurrentMediaType(stream, nullptr, want.Get()))) {
        m_failed = true;
        return;
    }
    ComPtr<IMFMediaType> cur;
    UINT32 w = 0, h = 0;
    if (FAILED(reader->GetCurrentMediaType(stream, &cur))) {
        m_failed = true;
        return;
    }
    frameSize(cur.Get(), &w, &h);
    if (w == 0 || h == 0) {
        m_failed = true;
        return;
    }
    while (!m_cancel) {
        {
            QMutexLocker lock(&m_lock);
            if (m_queue.size() >= 10) {
                lock.unlock();
                msleep(5);
                continue;
            }
        }
        DWORD flags = 0;
        LONGLONG ts = 0;
        ComPtr<IMFSample> sample;
        if (FAILED(reader->ReadSample(stream, 0, nullptr, &flags, &ts, &sample)))
            break;
        if (flags & (MF_SOURCE_READERF_ENDOFSTREAM | MF_SOURCE_READERF_ERROR))
            break;
        QImage img;
        if (!sample || !sampleImage(sample.Get(), int(w), int(h), 360, int(rotation), &img))
            continue;
        QMutexLocker lock(&m_lock);
        m_queue.push_back({ts / 10000, std::move(img)});
    }
}
