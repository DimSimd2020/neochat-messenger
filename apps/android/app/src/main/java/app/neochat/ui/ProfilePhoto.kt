package app.neochat.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import app.neochat.core.CoreClient
import org.json.JSONObject
import java.io.File

/**
 * Picks an image, cuts the middle square and saves it as a small JPEG in the cache.
 * [done] gets the file, or null when the image cannot be read or stays above [maxBytes].
 */
private fun pickSquareJpeg(act: MainActivity, side: Int, maxBytes: Int, done: (File?) -> Unit) {
    val intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
    act.startForResult(intent) { code, data ->
        val uri = data?.data
        if (code != Activity.RESULT_OK || uri == null) return@startForResult
        Thread {
            val tmp = File.createTempFile("avatar", ".img", act.cacheDir)
            val ok = runCatching {
                act.contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var total = 0
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= 25 * 1024 * 1024)
                            output.write(buffer, 0, n)
                        }
                    }
                }
                val source = Photos.decode(tmp.absolutePath, side) ?: error("image")
                val s = minOf(source.width, source.height)
                val crop = Bitmap.createBitmap(source, (source.width - s) / 2, (source.height - s) / 2, s, s)
                val square = Bitmap.createScaledBitmap(crop, side, side, true)
                for (quality in intArrayOf(75, 60, 45, 30)) {
                    tmp.outputStream().use { require(square.compress(Bitmap.CompressFormat.JPEG, quality, it)) }
                    if (tmp.length() <= maxBytes) return@runCatching true
                }
                false
            }.getOrDefault(false)
            act.runOnUiThread {
                if (ok) done(tmp) else {
                    tmp.delete()
                    done(null)
                }
            }
        }.start()
    }
}

fun pickProfilePhoto(act: MainActivity, done: () -> Unit) = pickSquareJpeg(act, 256, 49152) { file ->
    if (file == null) return@pickSquareJpeg act.toast("Не удалось прочитать фото. Выберите изображение до 25 МБ")
    CoreClient.call("set_avatar", JSONObject().put("path", file.absolutePath)) { _, e ->
        file.delete()
        if (e != null) act.toast(e.message) else done()
    }
}

/** The group photo travels in an end-to-end encrypted control message, so it stays under 18 KiB. */
fun pickGroupPhoto(act: MainActivity, conversationId: String, done: () -> Unit) = pickSquareJpeg(act, 128, 18432) { file ->
    if (file == null) return@pickSquareJpeg act.toast("Не удалось прочитать фото. Выберите изображение до 25 МБ")
    setGroupPhoto(act, conversationId, file.absolutePath) {
        file.delete()
        done()
    }
}

fun setGroupPhoto(act: MainActivity, conversationId: String, path: String, done: () -> Unit) {
    CoreClient.call("group_set_avatar", JSONObject().put("conversation_id", conversationId).put("path", path)) { _, e ->
        if (e != null) act.toast(e.message)
        done()
    }
}
