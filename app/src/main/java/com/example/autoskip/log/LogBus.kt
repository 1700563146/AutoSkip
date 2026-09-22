package com.example.autoskip.log

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel {
    /** Ordinary progress, e.g. a scan that found nothing. */
    INFO,

    /** A rule matched and the click was performed. */
    HIT,

    /** A rule matched but a guard rejected the click. */
    REJECT,

    /** Something went wrong. */
    ERROR,
}

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val message: String,
)

/**
 * In-memory ring buffer of recent activity, plus the counters shown on the home
 * screen.
 *
 * Writes come from the accessibility worker thread while readers live on the
 * main thread, so every mutation is guarded and listeners are always notified
 * on the main looper — callers never have to think about which thread they are
 * on.
 */
object LogBus {

    private const val MAX_ENTRIES = 200
    private const val LOGCAT_TAG = "AutoSkip"

    private val lock = Any()
    private val entries = ArrayDeque<LogEntry>()
    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    var scanCount: Long = 0
        private set

    @Volatile
    var clickCount: Long = 0
        private set

    fun log(message: String, level: LogLevel = LogLevel.INFO) {
        synchronized(lock) {
            entries.addLast(LogEntry(System.currentTimeMillis(), level, message))
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        // Mirror to logcat so `adb logcat -s AutoSkip` works; the in-app list is
        // not reachable when the service misbehaves before the UI can open.
        Log.println(level.toPriority(), LOGCAT_TAG, message)
        notifyChanged()
    }

    private fun LogLevel.toPriority(): Int = when (this) {
        LogLevel.INFO -> Log.INFO
        LogLevel.HIT -> Log.INFO
        LogLevel.REJECT -> Log.WARN
        LogLevel.ERROR -> Log.ERROR
    }

    fun countScan() {
        scanCount++
        notifyChanged()
    }

    fun countClick() {
        clickCount++
        notifyChanged()
    }

    fun snapshot(): List<LogEntry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            scanCount = 0
            clickCount = 0
        }
        notifyChanged()
    }

    fun format(entry: LogEntry): String =
        "${timeFormat.format(Date(entry.timestamp))}  ${entry.message}"

    fun addListener(listener: () -> Unit) {
        synchronized(lock) { listeners.add(listener) }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(lock) { listeners.remove(listener) }
    }

    /**
     * Hops to the main thread when needed so UI listeners can touch views
     * directly.
     */
    private fun notifyChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatch()
        } else {
            main.post { dispatch() }
        }
    }

    private fun dispatch() {
        val current = synchronized(lock) { listeners.toList() }
        current.forEach { it() }
    }
}
