package ru.mailchat.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class McLogger(context: Context) {
    private val file = File(context.filesDir, "mc.log")

    @Synchronized
    fun add(message: String) = add("INFO", message)

    @Synchronized
    fun debug(message: String) = add("DEBUG", message)

    @Synchronized
    fun info(message: String) = add("INFO", message)

    @Synchronized
    fun warn(message: String) = add("WARN", message)

    @Synchronized
    fun error(message: String) = add("ERROR", message)

    @Synchronized
    fun add(level: String, message: String) {
        val safeLevel = level.uppercase(Locale.ROOT).takeIf {
            it in setOf("DEBUG", "INFO", "WARN", "ERROR")
        } ?: "INFO"
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        file.appendText("$stamp [$safeLevel] $message\n", Charsets.UTF_8)

        if (file.length() > 512 * 1024) {
            val tail = file.readLines().takeLast(1500).joinToString("\n", postfix = "\n")
            file.writeText(tail, Charsets.UTF_8)
        }
    }

    @Synchronized
    fun read(): String = if (file.exists()) file.readText(Charsets.UTF_8) else ""

    @Synchronized
    fun clear() {
        file.writeText("", Charsets.UTF_8)
        add("INFO", "Журнал очищен")
    }

    fun sizeBytes(): Long = file.length()
}
