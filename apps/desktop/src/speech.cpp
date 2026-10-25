#include "speech.h"

#include "mf.h"

#include <QDir>
#include <QThread>
#include <algorithm>
#include <cmath>
#include <mutex>
#include <string>

#ifdef NEOCHAT_STT
#include <windows.h>

#include "sherpa-onnx/c-api/c-api.h"
#endif

namespace speech {

#ifndef NEOCHAT_STT

bool available() { return false; }
Result transcribe(const QString &, const QString &) { return {false, {}, QStringLiteral("В эту сборку не входит распознавание речи")}; }
void release() {}

#else

namespace {
constexpr int kRate = 16000;
constexpr int kChunkSeconds = 25;

std::mutex g_lock;
const SherpaOnnxOfflineRecognizer *g_recognizer = nullptr;
std::string g_modelDir;

// The DLLs are delay-loaded: ask the loader first, so a missing file is an answer and not a crash.
bool runtime()
{
    static const bool ok = [] {
        HMODULE onnx = LoadLibraryExW(L"onnxruntime.dll", nullptr, LOAD_LIBRARY_SEARCH_APPLICATION_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32);
        HMODULE api = LoadLibraryExW(L"sherpa-onnx-c-api.dll", nullptr, LOAD_LIBRARY_SEARCH_APPLICATION_DIR | LOAD_LIBRARY_SEARCH_SYSTEM32);
        return onnx && api;
    }();
    return ok;
}

// Callers hold g_lock.
const SherpaOnnxOfflineRecognizer *recognizer(const QString &modelDir)
{
    const std::string dir = QDir::toNativeSeparators(modelDir).toStdString();
    if (g_recognizer && g_modelDir == dir)
        return g_recognizer;
    if (g_recognizer) {
        SherpaOnnxDestroyOfflineRecognizer(g_recognizer);
        g_recognizer = nullptr;
    }
    const std::string encoder = dir + "\\encoder.int8.onnx", decoder = dir + "\\decoder.onnx", joiner = dir + "\\joiner.int8.onnx", tokens = dir + "\\tokens.txt";
    SherpaOnnxOfflineRecognizerConfig cfg;
    memset(&cfg, 0, sizeof cfg);
    cfg.feat_config.sample_rate = kRate;
    cfg.feat_config.feature_dim = 80;
    cfg.model_config.transducer.encoder = encoder.c_str();
    cfg.model_config.transducer.decoder = decoder.c_str();
    cfg.model_config.transducer.joiner = joiner.c_str();
    cfg.model_config.tokens = tokens.c_str();
    cfg.model_config.num_threads = std::clamp(QThread::idealThreadCount() / 2, 1, 4);
    cfg.model_config.provider = "cpu";
    cfg.model_config.debug = 0;
    cfg.decoding_method = "greedy_search";
    g_recognizer = SherpaOnnxCreateOfflineRecognizer(&cfg);
    g_modelDir = g_recognizer ? dir : std::string();
    return g_recognizer;
}

// Cuts at the quietest moment near each limit so that a word is not split between two passes.
std::vector<std::pair<size_t, size_t>> segments(const std::vector<float> &x)
{
    std::vector<std::pair<size_t, size_t>> out;
    const size_t chunk = size_t(kChunkSeconds) * kRate, slack = size_t(3) * kRate, win = kRate / 10;
    size_t start = 0;
    while (start < x.size()) {
        if (x.size() - start <= chunk + slack) {
            out.emplace_back(start, x.size());
            break;
        }
        size_t best = start + chunk;
        double quiet = 1e30;
        for (size_t at = start + chunk - slack; at + win < start + chunk + slack; at += win / 2) {
            double e = 0;
            for (size_t i = at; i < at + win; ++i)
                e += double(x[i]) * x[i];
            if (e < quiet) {
                quiet = e;
                best = at + win / 2;
            }
        }
        out.emplace_back(start, best);
        start = best;
    }
    return out;
}
}

bool available()
{
    return runtime();
}

Result transcribe(const QString &mediaPath, const QString &modelDir)
{
    if (!runtime())
        return {false, {}, QStringLiteral("Не найдены библиотеки распознавания речи. Переустановите NeoChat")};
    mf::Pcm pcm;
    if (!mf::decodeAudio(mediaPath, &pcm))
        return {false, {}, QStringLiteral("Не удалось прочитать звук сообщения")};
    const std::vector<float> samples = mf::toMonoFloat(pcm, kRate);
    pcm = {};
    std::lock_guard<std::mutex> lock(g_lock);
    const SherpaOnnxOfflineRecognizer *rec = recognizer(modelDir);
    if (!rec)
        return {false, {}, QStringLiteral("Не удалось загрузить модель распознавания. Скачайте её заново")};
    QStringList parts;
    for (const auto &[from, to] : segments(samples)) {
        const SherpaOnnxOfflineStream *stream = SherpaOnnxCreateOfflineStream(rec);
        SherpaOnnxAcceptWaveformOffline(stream, kRate, samples.data() + from, int32_t(to - from));
        SherpaOnnxDecodeOfflineStream(rec, stream);
        const SherpaOnnxOfflineRecognizerResult *r = SherpaOnnxGetOfflineStreamResult(stream);
        const QString text = r && r->text ? QString::fromUtf8(r->text).trimmed() : QString();
        if (!text.isEmpty())
            parts << text;
        if (r)
            SherpaOnnxDestroyOfflineRecognizerResult(r);
        SherpaOnnxDestroyOfflineStream(stream);
    }
    QString text = parts.join(QLatin1Char(' ')).simplified();
    // The model writes in lower case without punctuation: make it read like a sentence.
    if (!text.isEmpty())
        text[0] = text[0].toUpper();
    return {true, text, {}};
}

void release()
{
    std::lock_guard<std::mutex> lock(g_lock);
    if (g_recognizer) {
        SherpaOnnxDestroyOfflineRecognizer(g_recognizer);
        g_recognizer = nullptr;
        g_modelDir.clear();
    }
}

#endif

}
