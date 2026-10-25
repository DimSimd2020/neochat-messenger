package app.neochat.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Shrinks a video before it is sent: small clips go as they are, the rest become H.264 + AAC in MP4,
 * 720p at most, with a bitrate chosen so that the file stays under the attachment limit. The desktop
 * and iOS apps follow the same rules (docs/30-voice-circles-stt.md).
 */
@UnstableApi
object VideoCompress {
    const val LIMIT = 50L shl 20
    private const val BUDGET = 45L shl 20
    private const val KEEP = 10L shl 20
    private const val AUDIO_BPS = 128_000

    private class Rung(val longSide: Int, val shortSide: Int, val videoBps: Int)

    private val ladder = listOf(Rung(1280, 720, 2_500_000), Rung(854, 480, 1_200_000), Rung(640, 360, 700_000))

    class Result(val file: File?, val compressed: Boolean, val error: String?)

    class Job {
        internal var transformer: Transformer? = null
        @Volatile internal var cancelled = false
        fun cancel() {
            cancelled = true
            transformer?.cancel()
        }
    }

    private class Probe(val width: Int, val height: Int, val durationMs: Long)

    private fun probe(path: String): Probe? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            fun meta(key: Int) = r.extractMetadata(key)?.toIntOrNull() ?: 0
            val turned = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            var w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            var h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (turned == 90 || turned == 270) w = h.also { h = w }
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (w > 0 && h > 0 && ms > 0) Probe(w, h, ms) else null
        } catch (_: Exception) {
            null
        } finally {
            r.release()
        }
    }

    private fun firstRung(durationMs: Long): Int {
        val seconds = durationMs / 1000.0
        return ladder.indexOfFirst { (it.videoBps + AUDIO_BPS) / 8.0 * seconds <= BUDGET }
    }

    private fun fitted(p: Probe, r: Rung): Pair<Int, Int> {
        val landscape = p.width >= p.height
        val k = min(1.0, min((if (landscape) r.longSide else r.shortSide).toDouble() / p.width, (if (landscape) r.shortSide else r.longSide).toDouble() / p.height))
        return max(2, (p.width * k).toInt() and 1.inv()) to max(2, (p.height * k).toInt() and 1.inv())
    }

    /**
     * Decides and, when needed, compresses [src] into a new file in [dir]. [onDone] runs on the main
     * thread; a result with `compressed == false` and no error means the original goes as it is.
     */
    fun run(ctx: Context, src: File, dir: File, onProgress: (Int) -> Unit, onDone: (Result) -> Unit): Job {
        val job = Job()
        val main = Handler(Looper.getMainLooper())
        val size = src.length()
        val p = probe(src.absolutePath)
        val first = if (p != null) firstRung(p.durationMs) else -1
        fun original(): Result = if (size <= LIMIT) Result(null, false, null)
        else Result(null, false, if (p == null) "Видео больше 50 МБ, а прочитать его для сжатия не удалось" else "Видео слишком длинное: даже в сжатом виде оно больше 50 МБ. Обрежьте его")
        if (p == null || first < 0) {
            main.post { onDone(original()) }
            return job
        }
        val estimate = (ladder[first].videoBps + AUDIO_BPS) / 8.0 * (p.durationMs / 1000.0)
        if (size <= LIMIT && ((size <= KEEP && max(p.width, p.height) <= 1920) || size <= estimate)) {
            main.post { onDone(Result(null, false, null)) }
            return job
        }
        fun attempt(rung: Int) {
            if (job.cancelled) return onDone(Result(null, false, null))
            if (rung >= ladder.size) return onDone(Result(null, false, "Не удалось сжать видео до 50 МБ. Обрежьте его"))
            val out = File.createTempFile("packed", ".mp4", dir)
            val (w, h) = fitted(p, ladder[rung])
            val encoder = DefaultEncoderFactory.Builder(ctx)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(ladder[rung].videoBps).build())
                .build()
            val transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoder)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        job.transformer = null
                        if (job.cancelled) { out.delete(); return onDone(Result(null, false, null)) }
                        if (out.length() in 1..LIMIT) {
                            onProgress(100)
                            onDone(Result(out, true, null))
                        } else {
                            out.delete()
                            attempt(rung + 1)
                        }
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        job.transformer = null
                        out.delete()
                        // A failed pass is not fatal when the original fits.
                        onDone(if (job.cancelled || size <= LIMIT) Result(null, false, null) else Result(null, false, "Не удалось сжать видео: ${exportException.errorCodeName}"))
                    }
                })
                .build()
            job.transformer = transformer
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(src)))
                .setEffects(Effects(emptyList(), listOf(FrameDropEffect.createDefaultFrameDropEffect(30f), Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT))))
                .build()
            transformer.start(edited, out.absolutePath)
            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    val t = job.transformer ?: return
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(min(99, holder.progress))
                    main.postDelayed(this, 400)
                }
            }
            main.postDelayed(poll, 400)
        }
        main.post { attempt(first) }
        return job
    }
}
