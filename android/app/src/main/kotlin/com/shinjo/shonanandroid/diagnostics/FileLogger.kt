package com.shinjo.shonanandroid.diagnostics

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * インターネット未接続の現地(Pluto運用先)ではMacとadb接続できないことが多い。
 * クラッシュ/送受信エラーをファイルへ残しておき、後でUSB接続時にadb pull、または
 * ファイルマネージャ(Android/data/com.shinjo.shonanandroid/files/logs/)で回収する。
 */
object FileLogger {
    private var logFile: File? = null

    fun init(context: Context) {
        if (logFile != null) return
        val dir = (context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs"))
        dir.mkdirs()
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "shonan_$name.log")
        logFile = file

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { log("FATAL", "Uncaught exception on ${thread.name}: ${throwable.stackTraceToString()}") }
            previousHandler?.uncaughtException(thread, throwable)
        }
        log("INFO", "FileLogger started: ${file.absolutePath}")
    }

    fun log(tag: String, message: String) {
        val file = logFile ?: return
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        runCatching { FileWriter(file, true).use { it.write("$ts [$tag] $message\n") } }
    }
}
