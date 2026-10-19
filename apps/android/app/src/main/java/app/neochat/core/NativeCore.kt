package app.neochat.core

/** JNI surface of the Rust core (core/src/jni_bridge.rs). JSON in, JSON out. */
object NativeCore {
    init {
        System.loadLibrary("neocore")
    }

    fun interface Listener {
        fun onEvent(json: String)
    }

    @JvmStatic external fun open(configJson: String, listener: Listener): Long
    @JvmStatic external fun call(handle: Long, method: String, paramsJson: String): String
    @JvmStatic external fun close(handle: Long)
}
