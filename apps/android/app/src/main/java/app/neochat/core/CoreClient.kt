package app.neochat.core

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Executors

/** Error from the core: user-facing Russian message, never secrets. */
data class CoreError(val code: String, val message: String)

/**
 * Asynchronous access to the core. Calls run on a small worker pool so the main
 * thread never waits for storage, crypto or network; results and events are
 * delivered on the main thread.
 */
object CoreClient {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4)
    private val listeners = mutableListOf<(JSONObject) -> Unit>()
    @Volatile private var handle = 0L

    val isOpen get() = handle != 0L

    fun open(dataDir: String, dbKeyHex: String) {
        if (handle != 0L) return
        val cfg = JSONObject().put("data_dir", dataDir).put("db_key", dbKeyHex).put("foreground", false)
        handle = NativeCore.open(cfg.toString()) { json ->
            val ev = JSONObject(json)
            main.post { listeners.toList().forEach { it(ev) } }
        }
    }

    fun addListener(l: (JSONObject) -> Unit) = listeners.add(l)
    fun removeListener(l: (JSONObject) -> Unit) = listeners.remove(l)

    /** Delivers an app-side event (e.g. a changed local setting) like a core event. */
    fun emitLocal(type: String) {
        val ev = JSONObject().put("type", type)
        main.post { listeners.toList().forEach { it(ev) } }
    }

    /** Synchronous call for background jobs; never use it on the main thread. */
    fun callBlocking(method: String, params: JSONObject = JSONObject()): Pair<Any?, CoreError?> {
        val res = JSONObject(NativeCore.call(handle, method, params.toString()))
        if (res.has("error")) {
            val e = res.getJSONObject("error")
            return null to CoreError(e.optString("code", "unknown"), e.optString("message"))
        }
        return res.opt("ok").takeUnless { it == JSONObject.NULL } to null
    }

    fun call(method: String, params: JSONObject = JSONObject(), cb: ((Any?, CoreError?) -> Unit)? = null) {
        pool.execute {
            val (ok, err) = callBlocking(method, params)
            if (cb != null) main.post { cb(ok, err) }
        }
    }
}
