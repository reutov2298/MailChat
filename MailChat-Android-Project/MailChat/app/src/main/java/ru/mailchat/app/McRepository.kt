package ru.mailchat.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class McRepository(context: Context) {
    companion object { private val syncLock = Any() }
    private val prefs = context.getSharedPreferences("mc_local", Context.MODE_PRIVATE)

    init { prefs.edit().remove("deleted_chats").apply() }

    fun pendingChatDeletions(): Map<String, Long> {
        val json = try { JSONObject(prefs.getString("pending_chat_deletions", "{}")) } catch (_: Exception) { JSONObject() }
        return json.keys().asSequence().mapNotNull { email -> json.optLong(email, 0L).takeIf { it > 0L }?.let { email to it } }.toMap()
    }

    private fun savePendingChatDeletions(items: Map<String, Long>) {
        val json = JSONObject()
        items.forEach { (email, deadline) -> json.put(email, deadline) }
        prefs.edit().putString("pending_chat_deletions", json.toString()).apply()
    }

    fun scheduleChatDeletion(email: String, deadline: Long) {
        val pending = pendingChatDeletions().toMutableMap()
        pending[email.trim().lowercase()] = deadline
        savePendingChatDeletions(pending)
    }

    fun cancelChatDeletion(email: String) {
        val pending = pendingChatDeletions().toMutableMap()
        pending.remove(email.trim().lowercase())
        savePendingChatDeletions(pending)
    }

    fun finalizeExpiredChatDeletions(now: Long = System.currentTimeMillis()) {
        pendingChatDeletions().filterValues { it <= now }.keys.toList().forEach { deleteChat(it) }
    }

    fun baseline(): Long = prefs.getLong("baseline", 0L)
    fun setBaseline(value: Long) { prefs.edit().putLong("baseline", value).apply() }
    fun myEmail(): String = prefs.getString("email", "").orEmpty()
    fun setEmail(value: String) {
        val previous = prefs.getString("email", "").orEmpty()
        if (previous.isNotBlank() && !previous.equals(value, true)) {
            prefs.edit()
                .remove("gmail_mc_label_id")
                .remove("gmail_mc_migration_done")
                .remove("gmail_mc_migration_page")
                .remove("gmail_mc_migration_processed")
                .remove("gmail_mc_migration_estimate")
                .remove("gmail_mc_migration_retry_after")
                .remove("gmail_mc_migration_failures")
                .remove("gmail_mc_delete_ids")
                .remove("gmail_mc_delete_index")
                .remove("gmail_mc_pending_outgoing_labels")
                .remove("processed")
                .putBoolean("gmail_mc_delete_confirmed", false)
                .putLong("baseline", 0L)
                .apply()
        }
        prefs.edit().putString("email", value).apply()
    }

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

    fun isProcessed(id: String): Boolean =
        prefs.getStringSet("processed", emptySet())?.contains(id) == true

    fun markProcessed(id: String) {
        val set = prefs.getStringSet("processed", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(id)
        prefs.edit().putStringSet("processed", set).apply()
    }

    fun mcLabelId(): String = prefs.getString("gmail_mc_label_id", "").orEmpty()
    fun setMcLabelId(value: String) { prefs.edit().putString("gmail_mc_label_id", value).commit() }

    fun pendingOutgoingLabels(): Set<String> =
        prefs.getStringSet("gmail_mc_pending_outgoing_labels", emptySet())?.toSet() ?: emptySet()
    fun queueOutgoingLabel(id: String) {
        val ids = pendingOutgoingLabels().toMutableSet()
        ids.add(id)
        prefs.edit().putStringSet("gmail_mc_pending_outgoing_labels", ids).commit()
    }
    fun completeOutgoingLabels(ids: Collection<String>) {
        val pending = pendingOutgoingLabels().toMutableSet()
        pending.removeAll(ids.toSet())
        prefs.edit().putStringSet("gmail_mc_pending_outgoing_labels", pending).commit()
    }

    fun migrationDone(): Boolean = prefs.getBoolean("gmail_mc_migration_done", false)
    fun migrationPageToken(): String? = prefs.getString("gmail_mc_migration_page", null)
    fun migrationProcessed(): Int = prefs.getInt("gmail_mc_migration_processed", 0)
    fun migrationEstimate(): Int = prefs.getInt("gmail_mc_migration_estimate", 0)
    fun migrationRetryAfter(): Long = prefs.getLong("gmail_mc_migration_retry_after", 0L)
    fun recordMigrationFailure(now: Long = System.currentTimeMillis()) {
        val failures = (prefs.getInt("gmail_mc_migration_failures", 0) + 1).coerceAtMost(7)
        val delay = minOf(3_600_000L, 60_000L shl (failures - 1))
        prefs.edit().putInt("gmail_mc_migration_failures", failures)
            .putLong("gmail_mc_migration_retry_after", now + delay).commit()
    }
    fun clearMigrationRetry() {
        prefs.edit().remove("gmail_mc_migration_retry_after")
            .remove("gmail_mc_migration_failures").commit()
    }
    fun saveMigrationCheckpoint(pageToken: String?, processed: Int, estimate: Int) {
        prefs.edit().putString("gmail_mc_migration_page", pageToken)
            .putInt("gmail_mc_migration_processed", processed)
            .putInt("gmail_mc_migration_estimate", estimate).commit()
    }
    fun finishMigration() {
        prefs.edit().putBoolean("gmail_mc_migration_done", true)
            .remove("gmail_mc_migration_page").commit()
    }

    fun saveDeleteJob(ids: List<String>, nextIndex: Int = 0) {
        prefs.edit().putString("gmail_mc_delete_ids", JSONArray(ids.distinct()).toString())
            .putInt("gmail_mc_delete_index", nextIndex).putBoolean("gmail_mc_delete_confirmed", true).commit()
    }
    fun deleteJobIds(): List<String> = runCatching {
        val array = JSONArray(prefs.getString("gmail_mc_delete_ids", "[]"))
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())
    fun deleteJobIndex(): Int = prefs.getInt("gmail_mc_delete_index", 0)
    fun deleteJobConfirmed(): Boolean = prefs.getBoolean("gmail_mc_delete_confirmed", false)
    fun updateDeleteJobIndex(index: Int) { prefs.edit().putInt("gmail_mc_delete_index", index).commit() }
    fun clearDeleteJob() { prefs.edit().remove("gmail_mc_delete_ids").remove("gmail_mc_delete_index").putBoolean("gmail_mc_delete_confirmed", false).commit() }

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
                McMessage(
                    m.optString("id"), j.optString("email"), m.optString("sender"),
                    m.optString("recipient"), m.optString("text"), m.optLong("timestamp"),
                    m.optBoolean("incoming"),
                    runCatching { DeliveryState.valueOf(m.optString("state")) }.getOrDefault(DeliveryState.QUEUED),
                    m.optBoolean("spam")
                )
            }
            val email = j.optString("email")
            val storedTitle = j.optString("title", email.substringBefore('@'))
            val manuallyNamed = j.optBoolean("nameManuallySet",
                storedTitle.isNotBlank() &&
                    !storedTitle.equals(email.substringBefore('@'), true) &&
                    !storedTitle.equals(email, true))
            val senderName = j.optString("senderName").takeIf { it.isNotBlank() }
            val displayTitle = when {
                manuallyNamed -> storedTitle
                senderName != null -> senderName
                else -> email
            }
            McChat(email, displayTitle, parsed, manuallyNamed, senderName, j.optString("avatarUrl").takeIf { it.isNotBlank() })
        }.sortedByDescending { it.messages.maxOfOrNull(McMessage::timestamp) ?: 0L }
    }

    private fun serializeChats(chats: List<McChat>): String {
        val root = JSONArray()
        chats.forEach { c ->
            val messages = JSONArray()
            c.messages.forEach { m ->
                messages.put(
                    JSONObject()
                        .put("id", m.id).put("sender", m.sender).put("recipient", m.recipient)
                        .put("text", m.text).put("timestamp", m.timestamp).put("incoming", m.incoming)
                        .put("state", m.state.name).put("spam", m.recoveredFromSpam)
                )
            }
            root.put(JSONObject().put("email", c.email).put("title", c.title).put("nameManuallySet", c.nameManuallySet).put("senderName", c.senderName).put("avatarUrl", c.avatarUrl).put("messages", messages))
        }
        return root.toString()
    }

    fun save(chats: List<McChat>) {
        prefs.edit().putString("chats", serializeChats(chats)).apply()
    }

    fun saveDurably(chats: List<McChat>): Boolean =
        prefs.edit().putString("chats", serializeChats(chats)).commit()

    fun lastChat(): String = prefs.getString("last_chat", "").orEmpty()

    fun setLastChat(email: String?) {
        prefs.edit().putString("last_chat", email.orEmpty()).apply()
    }

    fun deleteChat(email: String) {
        val normalized = email.trim().lowercase()
        save(chats().filterNot { it.email.equals(normalized, true) })
        cancelChatDeletion(normalized)
        if (lastChat().equals(normalized, true)) setLastChat(null)
    }
}
