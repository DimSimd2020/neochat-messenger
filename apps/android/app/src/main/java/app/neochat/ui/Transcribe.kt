package app.neochat.ui

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import app.neochat.core.CoreClient
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Speech to text on the phone (sherpa-onnx with the Russian Zipformer model that the core downloads).
 * Nothing leaves the device.
 */
object Speech {
    private const val RATE = 16000
    private const val CHUNK_SECONDS = 25
    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var loadedDir = ""

    class Result(val text: String?, val error: String?)

    fun transcribe(path: String, modelDir: String): Result {
        val samples = try {
            decode(path)
        } catch (_: Throwable) {
            null
        } ?: return Result(null, "Не удалось прочитать звук сообщения")
        synchronized(lock) {
            val rec = try {
                recognizer(modelDir)
            } catch (_: Throwable) {
                null
            } ?: return Result(null, "Не удалось загрузить модель распознавания. Скачайте её заново")
            val parts = ArrayList<String>()
            for ((from, to) in segments(samples)) {
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(samples.copyOfRange(from, to), RATE)
                    rec.decode(stream)
                    val text = rec.getResult(stream).text.trim()
                    if (text.isNotEmpty()) parts += text
                } finally {
                    stream.release()
                }
            }
            val joined = parts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            // The model writes in lower case without punctuation: make it read like a sentence.
            return Result(if (joined.isEmpty()) "" else joined.replaceFirstChar { it.uppercase() }, null)
        }
    }

    /** Frees the loaded model (about 150 MB); the next transcription loads it again. */
    fun release() = synchronized(lock) {
        recognizer?.release()
        recognizer = null
        loadedDir = ""
    }

    private fun recognizer(dir: String): OfflineRecognizer {
        recognizer?.let { if (loadedDir == dir) return it }
        recognizer?.release()
        recognizer = null
        val cfg = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").path,
                    decoder = File(dir, "decoder.onnx").path,
                    joiner = File(dir, "joiner.int8.onnx").path,
                ),
                tokens = File(dir, "tokens.txt").path,
                numThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4),
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
        return OfflineRecognizer(config = cfg).also {
            recognizer = it
            loadedDir = dir
        }
    }

    /** Cuts at the quietest moment near each limit so that a word is not split between two passes. */
    private fun segments(x: FloatArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        val chunk = CHUNK_SECONDS * RATE
        val slack = 3 * RATE
        val win = RATE / 10
        var start = 0
        while (start < x.size) {
            if (x.size - start <= chunk + slack) {
                out += start to x.size
                break
            }
            var best = start + chunk
            var quiet = Double.MAX_VALUE
            var at = start + chunk - slack
            while (at + win < start + chunk + slack) {
                var e = 0.0
                for (i in at until at + win) e += x[i].toDouble() * x[i]
                if (e < quiet) {
                    quiet = e
                    best = at + win / 2
                }
                at += win / 2
            }
            out += start to best
            start = best
        }
        return out
    }

    /** The first audio track of a file as mono floats at 16 kHz; null when the phone cannot decode it. */
    private fun decode(path: String): FloatArray? {
        val ex = MediaExtractor()
        ex.setDataSource(path)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floats = false
        val out = GrowableFloats()
        var resampler = Mono16k(rate)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floats = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    resampler = Mono16k(rate)
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    buf.order(ByteOrder.nativeOrder())
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    if (floats) {
                        val fb = buf.asFloatBuffer()
                        while (fb.remaining() >= channels) {
                            var sum = 0f
                            for (c in 0 until channels) sum += fb.get()
                            resampler.push(sum / channels, out)
                        }
                    } else {
                        val sb = buf.asShortBuffer()
                        while (sb.remaining() >= channels) {
                            var sum = 0
                            for (c in 0 until channels) sum += sb.get()
                            resampler.push(sum / (32768f * channels), out)
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
        return out.toArray().takeIf { it.isNotEmpty() }
    }

    private class GrowableFloats {
        private var a = FloatArray(1 shl 16)
        private var n = 0
        fun add(v: Float) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun toArray() = a.copyOf(n)
    }

    /** Averages the input over the window of one output sample, which is the low-pass that keeps the rate change clean. */
    private class Mono16k(rate: Int) {
        private val ratio = rate.toDouble() / RATE
        private var acc = 0.0
        private var phase = 0.0
        fun push(s: Float, out: GrowableFloats) {
            var remaining = 1.0
            while (remaining > 1e-9) {
                val need = ratio - phase
                if (remaining >= need) {
                    acc += s * need
                    out.add((acc / ratio).toFloat())
                    acc = 0.0
                    phase = 0.0
                    remaining -= need
                } else {
                    acc += s * remaining
                    phase += remaining
                    remaining = 0.0
                }
            }
        }
    }
}

/** What the bubbles show while a message is being turned into text, and the work itself. */
object Transcription {
    enum class Phase { MODEL, WORKING }
    class Status(val phase: Phase, val percent: Int)

    private val status = HashMap<String, Status>()
    private val listeners = LinkedHashSet<() -> Unit>()
    private var downloadFor = ""
    private var hooked = false

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    fun statusOf(id: String) = status[id]
    private fun changed() = listeners.toList().forEach { it() }

    private fun hook() {
        if (hooked) return
        hooked = true
        CoreClient.addListener { ev ->
            if (ev.optString("type") != "stt_progress" || downloadFor.isEmpty()) return@addListener
            val total = ev.optDouble("total")
            if (total <= 0) return@addListener
            status[downloadFor] = Status(Phase.MODEL, (ev.optDouble("done") * 100 / total).toInt())
            changed()
        }
    }

    private fun fail(act: MainActivity, id: String, why: String) {
        status.remove(id)
        if (downloadFor == id) downloadFor = ""
        changed()
        act.toast(why)
    }

    fun request(act: MainActivity, m: JSONObject) {
        hook()
        val id = m.optString("id")
        if (id.isEmpty() || status.containsKey(id)) return
        CoreClient.call("stt_model") { v, e ->
            if (e != null) return@call act.toast(e.message)
            val model = v as JSONObject
            if (model.optBoolean("ready")) {
                recognize(act, id, model.optString("dir"))
            } else if (model.optBoolean("downloading")) {
                act.toast("Модель уже скачивается. Попробуйте чуть позже")
            } else {
                val sheet = Sheet(act, "Расшифровка голоса")
                val mb = max(1L, model.optLong("size") shr 20)
                sheet.add(act.label("Речь распознаётся на телефоне: звук никуда не отправляется. Один раз нужно скачать модель — $mb МБ. Лучше делать это по Wi-Fi.", 14.5f), 0)
                val go = PillButton(act, "Скачать и расшифровать")
                go.setOnClickListener {
                    sheet.dismiss()
                    download(act, id)
                }
                sheet.add(go, 12)
                sheet.show()
            }
        }
    }

    private fun download(act: MainActivity, id: String) {
        if (downloadFor.isNotEmpty()) return act.toast("Модель уже скачивается. Попробуйте чуть позже")
        downloadFor = id
        status[id] = Status(Phase.MODEL, 0)
        changed()
        CoreClient.call("stt_model_download") { v, e ->
            downloadFor = ""
            if (e != null) return@call fail(act, id, e.message)
            recognize(act, id, (v as JSONObject).optString("dir"))
        }
    }

    private fun recognize(act: MainActivity, id: String, dir: String) {
        status[id] = Status(Phase.WORKING, 0)
        changed()
        CoreClient.call("download_file", JSONObject().put("message_id", id)) { v, e ->
            if (e != null) return@call fail(act, id, e.message)
            val path = (v as JSONObject).optString("path")
            Thread {
                // Medium priority: a long voice message must not make the phone sluggish.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val r = Speech.transcribe(path, dir)
                act.runOnUiThread {
                    if (r.text == null) return@runOnUiThread fail(act, id, r.error.orEmpty())
                    CoreClient.call("set_transcript", JSONObject().put("message_id", id).put("text", r.text)) { _, e2 ->
                        status.remove(id)
                        changed()
                        if (e2 != null) act.toast(e2.message)
                    }
                }
            }.start()
        }
    }

    fun clear(id: String) {
        CoreClient.call("clear_transcript", JSONObject().put("message_id", id))
    }
}
