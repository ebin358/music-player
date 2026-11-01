package timber.log

/**
 * The slice of Timber the vendored InnerTube client calls.
 *
 * Timber itself is an Android library, so on the desktop its calls land here:
 * debug lines are dropped, warnings and errors go to standard error.
 */
object Timber {
    @JvmStatic fun d(message: String?, vararg args: Any?) = Unit
    @JvmStatic fun d(t: Throwable?, message: String?, vararg args: Any?) = Unit
    @JvmStatic fun d(t: Throwable?) = Unit
    @JvmStatic fun i(message: String?, vararg args: Any?) = Unit
    @JvmStatic fun i(t: Throwable?, message: String?, vararg args: Any?) = Unit
    @JvmStatic fun v(message: String?, vararg args: Any?) = Unit
    @JvmStatic fun w(message: String?, vararg args: Any?) = log("W", null, message)
    @JvmStatic fun w(t: Throwable?, message: String?, vararg args: Any?) = log("W", t, message)
    @JvmStatic fun w(t: Throwable?) = log("W", t, null)
    @JvmStatic fun e(message: String?, vararg args: Any?) = log("E", null, message)
    @JvmStatic fun e(t: Throwable?, message: String?, vararg args: Any?) = log("E", t, message)
    @JvmStatic fun e(t: Throwable?) = log("E", t, null)

    @JvmStatic fun tag(tag: String): Timber = this

    private fun log(level: String, t: Throwable?, message: String?) {
        System.err.println("$level/InnerTube: ${message.orEmpty()}${t?.let { " — $it" }.orEmpty()}")
    }
}
