package app.melo.music.data

import android.content.Context
import android.os.Build
import app.melo.music.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash on the phone so the listener can send it with a bug report.
 * Nothing leaves the phone by itself; the report holds the app version, the Android version,
 * the device model and the error trace, no library, searches or listening data.
 */
object CrashLog {
    private const val FILE = "crash-last.txt"
    private const val MAX_CHARS = 24_000

    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText(report(thread.name, error).take(MAX_CHARS)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = runCatching { File(context.filesDir, FILE).takeIf { it.isFile }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) { runCatching { File(context.filesDir, FILE).delete() } }

    internal fun report(thread: String, error: Throwable): String = buildString {
        appendLine("Mauri ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Время: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())}, поток: $thread")
        appendLine()
        append(error.stackTraceToString())
    }
}
