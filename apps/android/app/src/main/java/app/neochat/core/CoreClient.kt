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
    @Volatile var accountId = ""
        private set
    @Volatile private var accountGeneration = 0L

    val isOpen get() = handle != 0L

    fun open(dataDir: String, dbKeyHex: String, installationId: String = "") {
        if (handle != 0L) return
        val cfg = JSONObject().put("data_dir", dataDir).put("db_key", dbKeyHex).put("foreground", false)
        if (installationId.isNotEmpty()) cfg.put("installation_id", "android:$installationId")
        handle = NativeCore.open(cfg.toString()) { json ->
            val ev = JSONObject(json)
            if (ev.optString("type") == "account_selected") {
                accountGeneration++
                accountId = ev.optString("account_id")
            }
            main.post {
                val id = ev.optString("account_id")
                if (id.isEmpty() || id == accountId || ev.optString("type") == "account_notify")
                    listeners.toList().forEach { it(ev) }
            }
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
        val bound = JSONObject(params.toString())
        if (!bound.has("_account_id") && accountId.isNotEmpty()) bound.put("_account_id", accountId)
        val res = JSONObject(NativeCore.call(handle, method, bound.toString()))
        if (res.has("error")) {
            val e = res.getJSONObject("error")
            return null to CoreError(e.optString("code", "unknown"), e.optString("message"))
        }
        return res.opt("ok").takeUnless { it == JSONObject.NULL } to null
    }

    fun call(method: String, params: JSONObject = JSONObject(), cb: ((Any?, CoreError?) -> Unit)? = null) {
        val account = params.optString("_account_id").ifEmpty { accountId }
        val generation = accountGeneration
        val bound = JSONObject(params.toString())
        if (account.isNotEmpty()) bound.put("_account_id", account)
        pool.execute {
            val (ok, err) = callBlocking(method, bound)
            if (cb != null) main.post {
                // send_file callbacks also own temporary-file cleanup after a switch.
                if (account.isEmpty() || (account == accountId && generation == accountGeneration) || method == "list_accounts" || method == "set_push_token" || method == "send_file") cb(ok, err)
            }
        }
    }
}
