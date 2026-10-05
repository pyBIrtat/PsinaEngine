package ru.psina.core

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logx {

    private const val TAG = "Psina"
    private const val MAX_BYTES = 512L * 1024
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)

    fun init(dir: File) {
        dir.mkdirs()
        file = File(dir, "psina.log")
        if (file!!.length() > MAX_BYTES) {
            val old = File(dir, "psina.prev.log")
            old.delete()
            file!!.renameTo(old)
        }
        append("---- запуск ${stamp.format(Date())} ----")
    }

    fun i(msg: String) = write("I", msg)

    fun e(msg: String, t: Throwable? = null) {
        write("E", if (t == null) msg else "$msg: ${t.javaClass.simpleName}: ${t.message}")
        if (t != null) Log.e(TAG, msg, t)
    }

    fun tail(lines: Int = 300): String {
        val f = file ?: return ""
        if (!f.exists()) return ""
        return f.readLines().takeLast(lines).joinToString("\n")
    }

    // Лог пишут и UI-поток, и фоновые загрузки, поэтому пишем под замком:
    // SimpleDateFormat не потокобезопасен.
    @Synchronized
    private fun write(level: String, msg: String) {
        Log.println(if (level == "E") Log.ERROR else Log.INFO, TAG, msg)
        append("$level ${fmt.format(Date())} $msg")
    }

    @Synchronized
    private fun append(line: String) {
        try {
            file?.appendText(line + "\n")
        } catch (_: Throwable) {
        }
    }
}
