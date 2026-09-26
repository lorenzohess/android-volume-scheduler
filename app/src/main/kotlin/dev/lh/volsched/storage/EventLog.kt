package dev.lh.volsched.storage

import android.content.Context
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Append-only diagnostic log.
 *
 * The point of this app failing quietly is that you can't tell the difference
 * between "the alarm never fired" and "the alarm fired and set the wrong
 * value". Every applied change records intended time, actual time, stream and
 * level so a misfire can be diagnosed after the fact instead of reproduced.
 *
 * Lives in device-protected storage so pre-unlock boot activity is captured too.
 */
class EventLog(context: Context) {

    private val file = File(
        context.applicationContext.createDeviceProtectedStorageContext().filesDir,
        FILE_NAME,
    )

    fun append(message: String) {
        val line = "${LocalDateTime.now().format(STAMP)}  $message"
        synchronized(LOCK) {
            runCatching {
                file.appendText(line + "\n")
                trimIfOversized()
            }
        }
    }

    /** Newest first, because that's what you want to see on the debug screen. */
    fun recent(limit: Int = 200): List<String> = synchronized(LOCK) {
        runCatching { file.readLines().takeLast(limit).asReversed() }.getOrDefault(emptyList())
    }

    fun clear() {
        synchronized(LOCK) { runCatching { file.writeText("") } }
    }

    private fun trimIfOversized() {
        val lines = file.readLines()
        if (lines.size > MAX_LINES) {
            file.writeText(lines.takeLast(KEEP_LINES).joinToString("\n", postfix = "\n"))
        }
    }

    companion object {
        private const val FILE_NAME = "events.log"
        private const val MAX_LINES = 2000
        private const val KEEP_LINES = 1000
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val LOCK = Any()
    }
}
