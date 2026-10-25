import AVFoundation
import SherpaOnnxC
import UIKit

extension Notification.Name {
    static let transcriptionChanged = Notification.Name("NeoChatTranscriptionChanged")
}

/// Speech to text on the phone (sherpa-onnx with the Russian Zipformer model that the core downloads).
/// Nothing leaves the device.
enum Speech {
    struct Output {
        let text: String?
        let error: String?
    }

    private static let rate = 16000
    private static let chunkSeconds = 25
    private static let lock = NSLock()

    /// Blocking: run it off the main thread. The model (about 150 MB in memory) is loaded for the call and freed after it.
    static func transcribe(_ url: URL, modelDir: String) -> Output {
        guard let samples = decode(url) else { return Output(text: nil, error: "Не удалось прочитать звук сообщения") }
        lock.lock()
        defer { lock.unlock() }
        let encoder = strdup(modelDir + "/encoder.int8.onnx"), decoder = strdup(modelDir + "/decoder.onnx")
        let joiner = strdup(modelDir + "/joiner.int8.onnx"), tokens = strdup(modelDir + "/tokens.txt")
        let provider = strdup("cpu"), method = strdup("greedy_search")
        defer { [encoder, decoder, joiner, tokens, provider, method].forEach { free($0) } }
        var config = SherpaOnnxOfflineRecognizerConfig()
        config.feat_config.sample_rate = Int32(rate)
        config.feat_config.feature_dim = 80
        config.model_config.transducer.encoder = UnsafePointer(encoder)
        config.model_config.transducer.decoder = UnsafePointer(decoder)
        config.model_config.transducer.joiner = UnsafePointer(joiner)
        config.model_config.tokens = UnsafePointer(tokens)
        config.model_config.provider = UnsafePointer(provider)
        config.model_config.num_threads = Int32(max(1, min(4, ProcessInfo.processInfo.activeProcessorCount / 2)))
        config.decoding_method = UnsafePointer(method)
        guard let recognizer = SherpaOnnxCreateOfflineRecognizer(&config) else {
            return Output(text: nil, error: "Не удалось загрузить модель распознавания. Скачайте её заново")
        }
        defer { SherpaOnnxDestroyOfflineRecognizer(recognizer) }
        var parts: [String] = []
        for (from, to) in segments(samples) {
            guard let stream = SherpaOnnxCreateOfflineStream(recognizer) else { continue }
            samples[from..<to].withUnsafeBufferPointer { buf in
                SherpaOnnxAcceptWaveformOffline(stream, Int32(rate), buf.baseAddress, Int32(to - from))
            }
            SherpaOnnxDecodeOfflineStream(recognizer, stream)
            if let result = SherpaOnnxGetOfflineStreamResult(stream) {
                if let raw = result.pointee.text {
                    let part = String(cString: raw).trimmingCharacters(in: .whitespacesAndNewlines)
                    if !part.isEmpty { parts.append(part) }
                }
                SherpaOnnxDestroyOfflineRecognizerResult(result)
            }
            SherpaOnnxDestroyOfflineStream(stream)
        }
        let joined = parts.joined(separator: " ").split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
        // The model writes in lower case without punctuation: make it read like a sentence.
        return Output(text: joined.isEmpty ? "" : joined.prefix(1).uppercased() + joined.dropFirst(), error: nil)
    }

    /// Cuts at the quietest moment near each limit so that a word is not split between two passes.
    private static func segments(_ x: [Float]) -> [(Int, Int)] {
        var out: [(Int, Int)] = []
        let chunk = chunkSeconds * rate, slack = 3 * rate, win = rate / 10
        var start = 0
        while start < x.count {
            if x.count - start <= chunk + slack {
                out.append((start, x.count))
                break
            }
            var best = start + chunk
            var quiet = Double.greatestFiniteMagnitude
            var at = start + chunk - slack
            while at + win < start + chunk + slack {
                var e = 0.0
                for i in at..<(at + win) { e += Double(x[i]) * Double(x[i]) }
                if e < quiet {
                    quiet = e
                    best = at + win / 2
                }
                at += win / 2
            }
            out.append((start, best))
            start = best
        }
        return out
    }

    /// The first audio track as mono floats at 16 kHz; nil when the system cannot decode it.
    private static func decode(_ url: URL) -> [Float]? {
        let asset = AVURLAsset(url: url)
        guard let track = asset.tracks(withMediaType: .audio).first, let reader = try? AVAssetReader(asset: asset) else { return nil }
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: rate, AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 32, AVLinearPCMIsFloatKey: true, AVLinearPCMIsBigEndianKey: false, AVLinearPCMIsNonInterleaved: false,
        ]
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: settings)
        guard reader.canAdd(output) else { return nil }
        reader.add(output)
        guard reader.startReading() else { return nil }
        var samples: [Float] = []
        while let buffer = output.copyNextSampleBuffer() {
            guard let block = CMSampleBufferGetDataBuffer(buffer) else { continue }
            let count = CMBlockBufferGetDataLength(block) / MemoryLayout<Float>.size
            guard count > 0 else { continue }
            let at = samples.count
            samples.append(contentsOf: repeatElement(0, count: count))
            samples.withUnsafeMutableBytes { raw in
                _ = CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: count * MemoryLayout<Float>.size, destination: raw.baseAddress! + at * MemoryLayout<Float>.size)
            }
        }
        return reader.status == .completed && !samples.isEmpty ? samples : nil
    }
}

/// What the bubbles show while a message is being turned into text, and the work itself.
final class Transcription {
    static let shared = Transcription()

    enum Phase: Equatable {
        case model(Int)
        case working
    }

    private var status: [String: Phase] = [:]
    private var downloadFor = ""

    private init() {
        NotificationCenter.default.addObserver(forName: .coreEvent, object: nil, queue: .main) { [weak self] note in
            guard let self, let ev = note.userInfo as? JSON, ev.str("type") == "stt_progress", !self.downloadFor.isEmpty else { return }
            let total = (ev["total"] as? NSNumber)?.doubleValue ?? 0
            guard total > 0 else { return }
            let done = (ev["done"] as? NSNumber)?.doubleValue ?? 0
            self.status[self.downloadFor] = .model(Int(done * 100 / total))
            self.changed()
        }
    }

    func status(of id: String) -> Phase? { status[id] }

    private func changed() { NotificationCenter.default.post(name: .transcriptionChanged, object: nil) }

    private func fail(_ host: UIViewController?, _ id: String, _ why: String) {
        status[id] = nil
        if downloadFor == id { downloadFor = "" }
        changed()
        host?.toast(why)
    }

    func request(_ m: JSON, from host: UIViewController) {
        let id = m.str("id")
        guard !id.isEmpty, status[id] == nil else { return }
        Core.shared.call("stt_model") { [weak self, weak host] r in
            guard let self else { return }
            switch r {
            case .failure(let e): host?.toast(e.message)
            case .success(let v):
                let model = v as? JSON ?? [:]
                if model.bool("ready") {
                    self.recognize(id, dir: model.str("dir"), host: host)
                } else if model.bool("downloading") {
                    host?.toast("Модель уже скачивается. Попробуйте чуть позже")
                } else {
                    let mb = max(1, model.int64("size") >> 20)
                    let a = UIAlertController(title: "Расшифровка голоса",
                                              message: "Речь распознаётся на телефоне: звук никуда не отправляется. Один раз нужно скачать модель — \(mb) МБ. Лучше делать это по Wi-Fi.",
                                              preferredStyle: .alert)
                    a.addAction(UIAlertAction(title: "Скачать и расшифровать", style: .default) { _ in self.download(id, host: host) })
                    a.addAction(UIAlertAction(title: "Отмена", style: .cancel))
                    host?.present(a, animated: !Theme.reducedMotion)
                }
            }
        }
    }

    private func download(_ id: String, host: UIViewController?) {
        guard downloadFor.isEmpty else { return host?.toast("Модель уже скачивается. Попробуйте чуть позже") ?? () }
        downloadFor = id
        status[id] = .model(0)
        changed()
        Core.shared.call("stt_model_download") { [weak self, weak host] r in
            guard let self else { return }
            self.downloadFor = ""
            switch r {
            case .failure(let e): self.fail(host, id, e.message)
            case .success(let v): self.recognize(id, dir: (v as? JSON)?.str("dir") ?? "", host: host)
            }
        }
    }

    private func recognize(_ id: String, dir: String, host: UIViewController?) {
        status[id] = .working
        changed()
        Core.shared.call("download_file", ["message_id": id]) { [weak self, weak host] r in
            guard let self else { return }
            switch r {
            case .failure(let e): self.fail(host, id, e.message)
            case .success(let v):
                let path = (v as? JSON)?.str("path") ?? ""
                DispatchQueue.global(qos: .utility).async {
                    let out = Speech.transcribe(URL(fileURLWithPath: path), modelDir: dir)
                    DispatchQueue.main.async {
                        guard let text = out.text else { return self.fail(host, id, out.error ?? "") }
                        Core.shared.call("set_transcript", ["message_id": id, "text": text]) { r2 in
                            self.status[id] = nil
                            self.changed()
                            if case .failure(let e) = r2 { host?.toast(e.message) }
                        }
                    }
                }
            }
        }
    }

    func clear(_ id: String) {
        Core.shared.call("clear_transcript", ["message_id": id])
    }
}
