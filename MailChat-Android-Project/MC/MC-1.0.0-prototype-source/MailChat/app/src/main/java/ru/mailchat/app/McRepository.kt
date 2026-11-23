package ru.mailchat.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class McRepository(context: Context) {
    private val prefs = context.getSharedPreferences("mc_local", Context.MODE_PRIVATE)
    fun baseline(): Long = prefs.getLong("baseline", 0L)
    fun setBaseline(value: Long) { prefs.edit().putLong("baseline", value).apply() }
    fun myEmail(): String = prefs.getString("email", "").orEmpty()
    fun setEmail(value: String) { prefs.edit().putString("email", value).apply() }
    fun isProcessed(id: String): Boolean = prefs.getStringSet("processed", emptySet())?.contains(id) == true
    fun markProcessed(id: String) { val set = prefs.getStringSet("processed", emptySet())?.toMutableSet() ?: mutableSetOf(); set.add(id); prefs.edit().putStringSet("processed", set).apply() }
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
    fun deleteChat(email: String) { save(chats().filterNot { it.email.equals(email, true) }) }
}
