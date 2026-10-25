#include "audioio.h"

#include <QDateTime>
#include <QDir>
#include <QFile>
#include <algorithm>
#include <cmath>

#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <wrl/client.h>

using Microsoft::WRL::ComPtr;

// ---- playback ----

namespace {
constexpr int kBlockMs = 100;
constexpr size_t kQueued = 4;
}

bool PcmOutput::start(const mf::Pcm &pcm, qint64 fromFrame)
{
    stop();
    if (pcm.rate <= 0 || pcm.channels <= 0 || pcm.frames() == 0)
        return false;
    WAVEFORMATEX fmt{};
    fmt.wFormatTag = WAVE_FORMAT_PCM;
    fmt.nChannels = WORD(pcm.channels);
    fmt.nSamplesPerSec = DWORD(pcm.rate);
    fmt.wBitsPerSample = 16;
    fmt.nBlockAlign = WORD(fmt.nChannels * 2);
    fmt.nAvgBytesPerSec = fmt.nSamplesPerSec * fmt.nBlockAlign;
    if (waveOutOpen(&m_h, WAVE_MAPPER, &fmt, 0, 0, CALLBACK_NULL) != MMSYSERR_NOERROR) {
        m_h = nullptr;
        return false;
    }
    m_pcm = pcm;
    m_base = std::clamp<qint64>(fromFrame, 0, pcm.frames());
    m_fed = m_base;
    m_finished = false;
    tick();
    return true;
}

void PcmOutput::tick()
{
    if (!m_h)
        return;
    while (!m_blocks.empty() && (m_blocks.front()->hdr.dwFlags & WHDR_DONE)) {
        waveOutUnprepareHeader(m_h, &m_blocks.front()->hdr, sizeof(WAVEHDR));
        m_blocks.pop_front();
    }
    const qint64 total = m_pcm.frames();
    const qint64 per = qint64(m_pcm.rate) * kBlockMs / 1000;
    const int frameBytes = m_pcm.channels * 2;
    while (m_blocks.size() < kQueued && m_fed < total) {
        const qint64 n = std::min(per, total - m_fed);
        auto block = std::make_unique<Block>();
        block->data = m_pcm.data.mid(qsizetype(m_fed) * frameBytes, qsizetype(n) * frameBytes);
        block->hdr.lpData = block->data.data();
        block->hdr.dwBufferLength = DWORD(block->data.size());
        if (waveOutPrepareHeader(m_h, &block->hdr, sizeof(WAVEHDR)) != MMSYSERR_NOERROR)
            break;
        if (waveOutWrite(m_h, &block->hdr, sizeof(WAVEHDR)) != MMSYSERR_NOERROR) {
            waveOutUnprepareHeader(m_h, &block->hdr, sizeof(WAVEHDR));
            break;
        }
        m_fed += n;
        m_blocks.push_back(std::move(block));
    }
    if (m_fed >= total && m_blocks.empty())
        m_finished = true;
}

void PcmOutput::pause()
{
    if (m_h)
        waveOutPause(m_h);
}

void PcmOutput::resume()
{
    if (m_h)
        waveOutRestart(m_h);
}

void PcmOutput::stop()
{
    if (!m_h)
        return;
    waveOutReset(m_h);
    for (auto &b : m_blocks)
        waveOutUnprepareHeader(m_h, &b->hdr, sizeof(WAVEHDR));
    m_blocks.clear();
    waveOutClose(m_h);
    m_h = nullptr;
    m_pcm = {};
    m_fed = m_base = 0;
}

qint64 PcmOutput::positionFrame() const
{
    if (!m_h)
        return 0;
    MMTIME t{};
    t.wType = TIME_SAMPLES;
    if (waveOutGetPosition(m_h, &t, sizeof t) != MMSYSERR_NOERROR || t.wType != TIME_SAMPLES)
        return m_base;
    return std::min<qint64>(m_base + qint64(t.u.sample), m_pcm.frames());
}

/// ---- recording ----

MicCapture::MicCapture(QObject *parent) : QObject(parent)
{
    m_timer.setInterval(25);
    connect(&m_timer, &QTimer::timeout, this, &MicCapture::poll);
}

MicCapture::~MicCapture()
{
    release();
}

bool MicCapture::start(QString *error)
{
    WAVEFORMATEX fmt{};
    fmt.wFormatTag = WAVE_FORMAT_PCM;
    fmt.nChannels = 1;
    fmt.nSamplesPerSec = kRate;
    fmt.wBitsPerSample = 16;
    fmt.nBlockAlign = 2;
    fmt.nAvgBytesPerSec = kRate * 2;
    if (waveInOpen(&m_in, WAVE_MAPPER, &fmt, 0, 0, CALLBACK_NULL) != MMSYSERR_NOERROR) {
        m_in = nullptr;
        if (error)
            *error = QStringLiteral("Нет доступа к микрофону. Проверьте разрешения Windows и подключение");
        return false;
    }
    for (int i = 0; i < kBuffers; ++i) {
        m_data[i].resize(kRate / 10 * 2);
        m_hdr[i] = {};
        m_hdr[i].lpData = m_data[i].data();
        m_hdr[i].dwBufferLength = DWORD(m_data[i].size());
        waveInPrepareHeader(m_in, &m_hdr[i], sizeof(WAVEHDR));
        waveInAddBuffer(m_in, &m_hdr[i], sizeof(WAVEHDR));
    }
    waveInStart(m_in);
    m_timer.start();
    return true;
}

void MicCapture::poll()
{
    if (!m_in)
        return;
    for (int i = 0; i < kBuffers; ++i) {
        WAVEHDR &h = m_hdr[i];
        if (!(h.dwFlags & WHDR_DONE))
            continue;
        const DWORD got = h.dwBytesRecorded;
        h.dwFlags &= ~DWORD(WHDR_DONE);
        h.dwBytesRecorded = 0;
        if (got >= 2)
            emit chunk(QByteArray(h.lpData, qsizetype(got & ~DWORD(1))));
        if (m_in)
            waveInAddBuffer(m_in, &h, sizeof(WAVEHDR));
    }
}

void MicCapture::stop()
{
    if (!m_in)
        return;
    waveInStop(m_in);
    waveInReset(m_in); // hands back the partly filled buffers
    // Deliver them in the order they were added, without refilling.
    for (int k = 0; k < kBuffers; ++k) {
        WAVEHDR &h = m_hdr[k];
        if ((h.dwFlags & WHDR_DONE) && h.dwBytesRecorded >= 2) {
            const DWORD got = h.dwBytesRecorded;
            h.dwFlags &= ~DWORD(WHDR_DONE);
            h.dwBytesRecorded = 0;
            emit chunk(QByteArray(h.lpData, qsizetype(got & ~DWORD(1))));
        }
    }
    release();
}

void MicCapture::release()
{
    m_timer.stop();
    if (!m_in)
        return;
    waveInStop(m_in);
    waveInReset(m_in);
    for (int i = 0; i < kBuffers; ++i)
        waveInUnprepareHeader(m_in, &m_hdr[i], sizeof(WAVEHDR));
    waveInClose(m_in);
    m_in = nullptr;
}

bool addAacStream(IMFSinkWriter *writer, int rate, int channels, int bytesPerSecond, unsigned long *stream)
{
    ComPtr<IMFMediaType> out, in;
    MFCreateMediaType(&out);
    out->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio);
    out->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_AAC);
    out->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, 16);
    out->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, UINT32(rate));
    out->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, UINT32(channels));
    out->SetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, UINT32(bytesPerSecond));
    out->SetUINT32(MF_MT_AUDIO_BLOCK_ALIGNMENT, 1);
    out->SetUINT32(MF_MT_AAC_AUDIO_PROFILE_LEVEL_INDICATION, 0x29);
    MFCreateMediaType(&in);
    in->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio);
    in->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_PCM);
    in->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, 16);
    in->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, UINT32(rate));
    in->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, UINT32(channels));
    in->SetUINT32(MF_MT_AUDIO_BLOCK_ALIGNMENT, UINT32(2 * channels));
    in->SetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, UINT32(rate * 2 * channels));
    DWORD index = 0;
    if (FAILED(writer->AddStream(out.Get(), &index)) || FAILED(writer->SetInputMediaType(index, in.Get(), nullptr)))
        return false;
    *stream = index;
    return true;
}

struct VoiceRecorder::Impl {
    ComPtr<IMFSinkWriter> writer;
    unsigned long stream = 0;
    LONGLONG next100ns = 0;
};

VoiceRecorder::VoiceRecorder(QObject *parent) : QObject(parent), d(new Impl), m_mic(this)
{
    connect(&m_mic, &MicCapture::chunk, this, &VoiceRecorder::onChunk);
}

VoiceRecorder::~VoiceRecorder()
{
    cancel();
}

bool VoiceRecorder::start(QString *error)
{
    auto fail = [&](const QString &why) {
        if (error)
            *error = why;
        d->writer.Reset();
        return false;
    };
    if (!mf::init())
        return fail(QStringLiteral("В Windows нет Media Foundation: установите Media Feature Pack"));
    m_path = QDir::temp().filePath(QStringLiteral("neochat-voice-%1.m4a").arg(QDateTime::currentMSecsSinceEpoch()));
    ComPtr<IMFAttributes> attrs;
    MFCreateAttributes(&attrs, 1);
    attrs->SetGUID(MF_TRANSCODE_CONTAINERTYPE, MFTranscodeContainerType_MPEG4);
    const std::wstring name = QDir::toNativeSeparators(m_path).toStdWString();
    if (FAILED(MFCreateSinkWriterFromURL(name.c_str(), nullptr, attrs.Get(), &d->writer)))
        return fail(QStringLiteral("Не удалось создать файл записи"));
    if (!addAacStream(d->writer.Get(), MicCapture::kRate, 1, 12000, &d->stream) || FAILED(d->writer->BeginWriting()))
        return fail(QStringLiteral("В Windows нет кодека AAC для записи голоса"));
    m_samples = 0;
    d->next100ns = 0;
    m_rms.clear();
    QString why;
    if (!m_mic.start(&why)) {
        QFile::remove(m_path);
        return fail(why);
    }
    return true;
}

void VoiceRecorder::onChunk(const QByteArray &pcm)
{
    if (!d->writer)
        return;
    const int16_t *s = reinterpret_cast<const int16_t *>(pcm.constData());
    const int n = int(pcm.size() / 2);
    double sum = 0;
    for (int k = 0; k < n; ++k)
        sum += double(s[k]) * s[k];
    const double rms = std::sqrt(sum / std::max(n, 1));
    m_rms.push_back(rms);
    emit level(std::min(1.0, rms / 9000.0));
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
    sample->SetSampleTime(d->next100ns);
    sample->SetSampleDuration(dur);
    d->next100ns += dur;
    d->writer->WriteSample(d->stream, sample.Get());
    m_samples += n;
}

void VoiceRecorder::stop()
{
    if (!m_mic.running())
        return;
    m_mic.stop();
    const bool wrote = d->writer && SUCCEEDED(d->writer->Finalize());
    const qint64 ms = elapsedMs();
    const std::vector<double> rms = m_rms;
    d->writer.Reset();
    if (!wrote || ms <= 0) {
        QFile::remove(m_path);
        emit failed(QStringLiteral("Не удалось сохранить запись"));
        return;
    }
    // Bars: the loudest of the chunks that fall on each bar, then relative to the loudest bar.
    QByteArray wave(kBars, 0);
    std::vector<double> bar(kBars, 0.0);
    double top = 1.0;
    for (int b = 0; b < kBars; ++b) {
        const size_t from = rms.size() * size_t(b) / kBars;
        const size_t to = std::max(from + 1, rms.size() * size_t(b + 1) / kBars);
        for (size_t i = from; i < to && i < rms.size(); ++i)
            bar[size_t(b)] = std::max(bar[size_t(b)], rms[i]);
        top = std::max(top, bar[size_t(b)]);
    }
    for (int b = 0; b < kBars; ++b)
        wave[b] = char(std::clamp(int(std::pow(bar[size_t(b)] / top, 0.75) * 255.0), 8, 255));
    emit finished(m_path, ms, wave);
}

void VoiceRecorder::cancel()
{
    const bool had = m_mic.running();
    m_mic.stop();
    d->writer.Reset();
    if (had && !m_path.isEmpty())
        QFile::remove(m_path);
}
