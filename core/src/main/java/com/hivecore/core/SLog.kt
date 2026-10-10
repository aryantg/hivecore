package com.hivecore.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logs to logcat AND to a private file, because the iQOO phones drop app logcat output
 * (persist.sys.log.ctrl=no). Read it from the laptop with:
 *   adb shell run-as com.hivecore cat files/hivecore.log
 * Ring: when the file passes 5 MB it is rotated once to hivecore.log.1 (the previous .1 is overwritten).
 * Before [init] (and in JVM tests) only logcat is used, which returns defaults under unit tests.
 */
object SLog {
    private const val TAG = "HiveCore"
    private const val MAX_BYTES = 5L * 1024 * 1024
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null

    /** Also receives every line (level "D" / "W" / "E", tag, message); the activity feed listens here. Never throws into the caller. */
    @Volatile var sink: ((level: String, tag: String, msg: String) -> Unit)? = null

    /** Absolute path of the log file, or null before [init]. */
    val path: String? get() = file?.absolutePath

    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "hivecore.log")
        d("SLog", "---- started ----")
    }

    fun d(tag: String, msg: String) { Log.d(TAG, "[$tag] $msg"); write("D", tag, msg) }
    fun w(tag: String, msg: String) { Log.w(TAG, "[$tag] $msg"); write("W", tag, msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(TAG, "[$tag] $msg", t)
        write("E", tag, if (t != null) "$msg: ${t.javaClass.simpleName}: ${t.message}\n${Log.getStackTraceString(t)}" else msg)
    }

    @Synchronized
    private fun write(level: String, tag: String, msg: String) {
        sink?.let { s -> try { s(level, tag, msg) } catch (_: Exception) { } }
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) {
                val old = File(f.parentFile, f.name + ".1")
                if (old.exists()) old.delete()
                f.renameTo(old)
            }
            FileWriter(f, true).use { it.write("${fmt.format(Date())} $level/$tag: $msg\n") }
        } catch (_: Exception) {
        }
    }
}
