// Checks the Media Foundation paths on this machine: audio → AAC .m4a → decode → speech to text,
// and video compression + circle decoding. Usage: neochat_mediatest <speech.wav> <model dir> <big video> <out dir>
#include "audioio.h"
#include "circle.h"
#include "mf.h"
#include "speech.h"
#include "videocompress.h"

#include <QEventLoop>
#include <QGuiApplication>
#include <QCoreApplication>
#include <QTimer>
#include <QDir>
#include <QFileInfo>
#include <QTextStream>
#include <atomic>

#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <wrl/client.h>

using Microsoft::WRL::ComPtr;

static QTextStream out(stdout);

static bool encodeM4a(const mf::Pcm &mono44, const QString &path)
{
    ComPtr<IMFAttributes> attrs;
    MFCreateAttributes(&attrs, 1);
    attrs->SetGUID(MF_TRANSCODE_CONTAINERTYPE, MFTranscodeContainerType_MPEG4);
    ComPtr<IMFSinkWriter> writer;
    const std::wstring name = QDir::toNativeSeparators(path).toStdWString();
    if (FAILED(MFCreateSinkWriterFromURL(name.c_str(), nullptr, attrs.Get(), &writer)))
        return false;
    unsigned long stream = 0;
    if (!addAacStream(writer.Get(), 44100, 1, 12000, &stream) || FAILED(writer->BeginWriting()))
        return false;
    const int chunk = 4410 * 2;
    LONGLONG t = 0;
    for (qsizetype off = 0; off < mono44.data.size(); off += chunk) {
        const QByteArray part = mono44.data.mid(off, chunk);
        ComPtr<IMFMediaBuffer> b;
        MFCreateMemoryBuffer(DWORD(part.size()), &b);
        BYTE *dst = nullptr;
        b->Lock(&dst, nullptr, nullptr);
        memcpy(dst, part.constData(), size_t(part.size()));
        b->Unlock();
        b->SetCurrentLength(DWORD(part.size()));
        ComPtr<IMFSample> s;
        MFCreateSample(&s);
        s->AddBuffer(b.Get());
        const LONGLONG dur = LONGLONG(part.size() / 2) * 10000000 / 44100;
        s->SetSampleTime(t);
        s->SetSampleDuration(dur);
        t += dur;
        if (FAILED(writer->WriteSample(stream, s.Get())))
            return false;
    }
    return SUCCEEDED(writer->Finalize());
}

int main(int argc, char **argv)
{
    QGuiApplication app(argc, argv);
    if (argc < 5)
        return 2;
    const QString wav = argv[1], model = argv[2], video = argv[3], dir = argv[4];
    QDir().mkpath(dir);
    int bad = 0;
    auto check = [&](bool ok, const char *what) {
        out << (ok ? "ok   " : "FAIL ") << what << Qt::endl;
        bad += !ok;
    };

    check(mf::init(), "mf init");
    mf::Pcm src;
    check(mf::decodeAudio(wav, &src), "decode wav");
    out << "  wav: " << src.rate << " Hz, " << src.channels << " ch, " << src.ms() << " ms" << Qt::endl;

    const std::vector<float> f = mf::toMonoFloat(src, 44100);
    mf::Pcm mono44;
    mono44.rate = 44100;
    mono44.channels = 1;
    mono44.data.resize(qsizetype(f.size() * 2));
    for (size_t i = 0; i < f.size(); ++i)
        reinterpret_cast<int16_t *>(mono44.data.data())[i] = int16_t(std::clamp(f[i], -1.f, 1.f) * 32767.f);
    const QString m4a = dir + "/voice.m4a";
    QFile::remove(m4a);
    check(encodeM4a(mono44, m4a), "encode m4a");
    out << "  m4a: " << QFileInfo(m4a).size() << " bytes" << Qt::endl;

    mf::Pcm back;
    check(mf::decodeAudio(m4a, &back), "decode m4a");
    out << "  back: " << back.rate << " Hz, " << back.channels << " ch, " << back.ms() << " ms" << Qt::endl;
    check(std::abs(back.ms() - src.ms()) < 300, "duration survives the codec");
    const QByteArray wave = mf::waveform(mono44.data, 64);
    check(wave.size() == 64, "waveform has 64 bars");

    mf::Pcm fast = mf::stretch(back, 2.0);
    out << "  2x: " << fast.ms() << " ms (from " << back.ms() << ")" << Qt::endl;
    check(std::abs(fast.ms() * 2 - back.ms()) < 400, "2x speed halves the length");

    check(speech::available(), "recognizer runtime present");
    QElapsedTimer clock;
    clock.start();
    const speech::Result r = speech::transcribe(m4a, model);
    out << "  stt (" << clock.elapsed() << " ms): " << r.text << " " << r.error << Qt::endl;
    check(r.ok && !r.text.isEmpty(), "transcribe m4a");
    const speech::Result r2 = speech::transcribe(wav, model);
    out << "  stt wav: " << r2.text << Qt::endl;

    PcmOutput player;
    check(player.start(back, 0), "playback device opens");
    player.stop();

    const QString shrunk = dir + "/shrunk.mp4";
    QFile::remove(shrunk);
    std::atomic<bool> cancel{false};
    int last = -1;
    clock.restart();
    const videocompress::Result vr = videocompress::run(video, shrunk, [&](int p) { last = p; }, cancel);
    out << "  compress (" << clock.elapsed() << " ms): ok=" << vr.ok << " compressed=" << vr.compressed << " " << vr.error << " "
        << QFileInfo(vr.path).size() << " bytes from " << QFileInfo(video).size() << ", progress " << last << Qt::endl;
    check(vr.ok && vr.compressed && QFileInfo(vr.path).size() < videocompress::kLimit, "video compressed under the limit");

    if (vr.compressed) {
        mf::Pcm vsound;
        check(mf::decodeAudio(vr.path, &vsound), "compressed video keeps its sound");
        CircleDecoder dec(vr.path);
        dec.start();
        QThread::msleep(1500);
        QImage frame = dec.frameAt(500);
        out << "  frame " << frame.width() << "x" << frame.height() << Qt::endl;
        check(!frame.isNull() && frame.width() == frame.height(), "decoder gives square frames");
        if (!frame.isNull())
            frame.save(dir + "/frame.png");
        dec.cancel();
        dec.wait(5000);
    }
    if (argc > 5 && QString(argv[5]) == "camera") {
        CameraRecorder cam;
        QString err;
        if (!cam.open(&err)) {
            out << "  no camera: " << err << Qt::endl;
        } else {
            int frames = 0;
            QObject::connect(&cam, &CameraRecorder::frame, [&](const QImage &f) { if (++frames == 40) f.save(dir + "/preview.png"); });
            QString path;
            qint64 ms = 0;
            QImage poster;
            QEventLoop loop;
            QObject::connect(&cam, &CameraRecorder::finished, [&](const QString &p, qint64 d, const QImage &img) { path = p; ms = d; poster = img; loop.quit(); });
            QObject::connect(&cam, &CameraRecorder::failed, [&](const QString &why) { out << "  camera failed: " << why << Qt::endl; loop.quit(); });
            QTimer::singleShot(1000, &loop, [&] {
                QString e2;
                if (!cam.startRecording(&e2)) { out << "  start failed: " << e2 << Qt::endl; loop.quit(); }
                QTimer::singleShot(3000, &loop, [&] { cam.stopRecording(); });
            });
            QTimer::singleShot(15000, &loop, &QEventLoop::quit);
            loop.exec();
            out << "  camera frames " << frames << ", file " << path << " " << QFileInfo(path).size() << " bytes, " << ms << " ms" << Qt::endl;
            check(!path.isEmpty() && QFileInfo(path).size() > 10000, "circle recorded from the camera");
            if (!path.isEmpty()) {
                mf::Pcm snd;
                check(mf::decodeAudio(path, &snd), "circle has a sound track");
                out << "  circle audio " << snd.ms() << " ms" << Qt::endl;
                poster.save(dir + "/poster.png");
            }
        }
    }
    out << (bad ? "FAILED" : "ALL OK") << Qt::endl;
    return bad ? 1 : 0;
}
