package dev.lelonio.square.desktop

import java.io.File

/** Where the Rust engine lives, and loading it once. */
object NativeLib {
    @Volatile
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        val file = candidates().firstOrNull { it.isFile }
            ?: error("libsquarecore not found; looked in:\n" + candidates().joinToString("\n") { "  $it" })
        System.load(file.absolutePath)
        loaded = true
        Log.i("Native", "loaded ${file.absolutePath}")
    }

    private fun candidates(): List<File> {
        val name = System.mapLibraryName("squarecore")
        val dirs = buildList {
            System.getenv("SQUARE_NATIVE_DIR")?.let(::add)
            // Set by Compose for packaged apps and for `gradlew run`.
            System.getProperty("compose.application.resources.dir")?.let(::add)
            add("resources/macos-arm64")
            add("resources/macos-x64")
            add("desktop/resources/macos-arm64")
            add("../native/target/release")
            add("native/target/release")
        }
        return dirs.map { File(it, name).absoluteFile }
    }
}

/** Minimal logging to standard error; Console.app shows it for packaged builds. */
object Log {
    var verbose = System.getenv("SQUARE_VERBOSE") != null

    fun d(tag: String, message: String) {
        if (verbose) System.err.println("D/$tag: $message")
    }

    fun i(tag: String, message: String) = System.err.println("I/$tag: $message")
    fun w(tag: String, message: String, t: Throwable? = null) =
        System.err.println("W/$tag: $message${t?.let { " — $it" }.orEmpty()}")

    fun e(tag: String, message: String, t: Throwable? = null) {
        System.err.println("E/$tag: $message${t?.let { " — $it" }.orEmpty()}")
        if (verbose) t?.printStackTrace()
    }
}
