package ru.mailchat.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class McLogger(context: Context) {
    private val file = File(context.filesDir, "mc.log")
    @Synchronized fun add(message: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        file.appendText("$stamp  $message\n", Charsets.UTF_8)
        if (file.length() > 512 * 1024) {
            val tail = file.readLines().takeLast(1500).joinToString("\n", postfix = "\n")
            file.writeText(tail, Charsets.UTF_8)
        }
    }
    @Synchronized fun read(): String = if (file.exists()) file.readText(Charsets.UTF_8) else ""
    @Synchronized fun clear() { file.writeText("", Charsets.UTF_8); add("Журнал очищен") }
    fun sizeBytes(): Long = file.length()
}
