package app.neochat.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import app.neochat.core.CoreClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

fun pickProfilePhoto(act: MainActivity, done: () -> Unit) {
    val intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
    act.startForResult(intent) { code, data ->
        val uri = data?.data
        if (code != Activity.RESULT_OK || uri == null) return@startForResult
        Thread {
            val tmp = File.createTempFile("avatar", ".img", act.cacheDir)
            val avatar = runCatching {
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
                val source = Photos.decode(tmp.absolutePath, 256) ?: error("image")
                val side = minOf(source.width, source.height)
                val crop = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
                val square = Bitmap.createScaledBitmap(crop, 256, 256, true)
                val out = ByteArrayOutputStream()
                square.compress(Bitmap.CompressFormat.JPEG, 75, out)
                require(out.size() <= 49152)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }.getOrNull()
            tmp.delete()
            act.runOnUiThread {
                if (avatar == null) { act.toast("Не удалось прочитать фото. Выберите изображение до 25 МБ"); return@runOnUiThread }
                CoreClient.call("set_profile", JSONObject().put("avatar", avatar)) { _, e ->
                    if (e != null) act.toast(e.message) else done()
                }
            }
        }.start()
    }
}
