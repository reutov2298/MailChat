package ru.mailchat.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class McRepository(context: Context) {
    companion object { private val syncLock = Any() }
    private val prefs = context.getSharedPreferences("mc_local", Context.MODE_PRIVATE)
    fun baseline(): Long = prefs.getLong("baseline", 0L)
    fun setBaseline(value: Long) { prefs.edit().putLong("baseline", value).apply() }
    fun myEmail(): String = prefs.getString("email", "").orEmpty()
    fun setEmail(value: String) { prefs.edit().putString("email", value).apply() }

    fun openIntervalSeconds(): Int = prefs.getInt("sync_open_seconds", 60)
    fun minimizedIntervalSeconds(): Int = prefs.getInt("sync_minimized_seconds", 180)
    fun closedIntervalSeconds(): Int = prefs.getInt("sync_closed_seconds", 900)
    fun diagnosticClickEnabled(): Boolean = prefs.getBoolean("sync_diagnostic_click", false)

    fun acquireSyncLock(): Boolean = synchronized(syncLock) {
        val now = System.currentTimeMillis()
        if (prefs.getLong("sync_lock_until", 0L) > now) return@synchronized false
        prefs.edit().putLong("sync_lock_until", now + 10 * 60 * 1000L).apply()
        true
    }

    fun releaseSyncLock() = synchronized(syncLock) {
        prefs.edit().putLong("sync_lock_until", 0L).apply()
    }
    fun saveSyncSettings(open: Int, minimized: Int, closed: Int, diagnosticClick: Boolean) {
        prefs.edit()
            .putInt("sync_open_seconds", open.coerceIn(5, 86400))
            .putInt("sync_minimized_seconds", minimized.coerceIn(5, 86400))
            .putInt("sync_closed_seconds", closed.coerceIn(60, 604800))
            .putBoolean("sync_diagnostic_click", diagnosticClick)
            .apply()
    }
    fun isProcessed(id: String): Boolean = prefs.getStringSet("processed", emptySet())?.contains(id) == true
    fun markProcessed(id: String) { val set = prefs.getStringSet("processed", emptySet())?.toMutableSet() ?: mutableSetOf(); set.add(id); prefs.edit().putStringSet("processed", set).apply() }
    fun isChatDeleted(email: String): Boolean =
        prefs.getStringSet("deleted_chats", emptySet())?.contains(email.trim().lowercase()) == true

    fun restoreChat(email: String) {
        val set = prefs.getStringSet("deleted_chats", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.remove(email.trim().lowercase())
        prefs.edit().putStringSet("deleted_chats", set).apply()
    }

    fun chats(): List<McChat> {
        val root = try { JSONArray(prefs.getString("chats", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until root.length()).mapNotNull { i ->
            val j = root.optJSONObject(i) ?: return@mapNotNull null
            val messages = j.optJSONArray("messages") ?: JSONArray()
            val parsed = (0 until messages.length()).mapNotNull { k ->
                val m = messages.optJSONObject(k) ?: return@mapNotNull null
                McMessage(m.optString("id"), j.optString("email"), m.optString("sender"), m.optString("recipient"), m.optString("text"), m.optLong("timestamp"), m.optBoolean("incoming"), runCatching { DeliveryState.valueOf(m.optString("state")) }.getOrDefault(DeliveryState.QUEUED), m.optBoolean("spam"))
            }
            McChat(j.optString("email"), j.optString("title"), parsed)
        }
    }
    fun save(chats: List<McChat>) {
        val root = JSONArray()
        chats.forEach { c ->
            val messages = JSONArray()
            c.messages.forEach { m -> messages.put(JSONObject().put("id",m.id).put("sender",m.sender).put("recipient",m.recipient).put("text",m.text).put("timestamp",m.timestamp).put("incoming",m.incoming).put("state",m.state.name).put("spam",m.recoveredFromSpam)) }
            root.put(JSONObject().put("email",c.email).put("title",c.title).put("messages",messages))
        }
        prefs.edit().putString("chats", root.toString()).apply()
    }
    fun lastChat(): String = prefs.getString("last_chat", "").orEmpty()

    fun setLastChat(email: String?) {
        prefs.edit().putString("last_chat", email.orEmpty()).apply()
    }

    fun deleteChat(email: String) {
        save(chats().filterNot { it.email.equals(email, true) })
        if (lastChat().equals(email, true)) setLastChat(null)
    }
}
