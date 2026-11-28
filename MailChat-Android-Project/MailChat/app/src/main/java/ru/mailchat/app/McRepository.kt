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
    fun setBaseline(value: Long) { prefs.edit().putLong("baseline", value).commit() }
    private fun mailboxKey(name: String): String =
        "gmail_" + name + ":" + myEmail().trim().lowercase()
    fun historyId(): String = prefs.getString(mailboxKey("history_id"), "").orEmpty()
    fun setHistoryId(value: String) {
        if (value.isNotBlank()) check(prefs.edit().putString(mailboxKey("history_id"), value).commit()) {
            "Не удалось сохранить Gmail History cursor"
        }
    }

    // Durable per-mailbox queue: a History ID remains pending until it is processed
    // or Gmail confirms that the message has already disappeared (HTTP 404).
    private fun pendingRetryState(): JSONObject =
        try { JSONObject(prefs.getString(mailboxKey("pending_retry_state"), "{}")) } catch (_: Exception) { JSONObject() }

    fun isPendingHistoryRetryDue(id: String, now: Long = System.currentTimeMillis()): Boolean =
        pendingRetryState().optJSONObject(id)?.optLong("nextAt", 0L)?.let { it <= now } ?: true

    /** Exponential retry delay: 30 s, 60 s, 120 s ... capped at 6 hours. */
    fun schedulePendingHistoryRetry(id: String): Long {
        val key = mailboxKey("pending_retry_state")
        val state = pendingRetryState()
        val previousAttempts = state.optJSONObject(id)?.optInt("attempts", 0) ?: 0
        val exponent = previousAttempts.coerceIn(0, 10)
        val delayMs = (30_000L * (1L shl exponent)).coerceAtMost(6L * 60L * 60L * 1000L)
        state.put(id, JSONObject().put("attempts", previousAttempts + 1).put("nextAt", System.currentTimeMillis() + delayMs))
        check(prefs.edit().putString(key, state.toString()).commit()) { "Не удалось сохранить время повторной обработки Gmail ID" }
        return delayMs
    }

    fun pendingHistoryIds(): Set<String> =
        prefs.getStringSet(mailboxKey("pending_history_ids"), emptySet())?.toSet().orEmpty()

    fun addPendingHistoryIds(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val key = mailboxKey("pending_history_ids")
        val pending = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (pending.addAll(ids.filter(String::isNotBlank))) {
            check(prefs.edit().putStringSet(key, pending).commit()) {
                "Не удалось сохранить очередь Gmail History"
            }
        }
    }

    fun removePendingHistoryIds(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val pendingKey = mailboxKey("pending_history_ids")
        val pending = prefs.getStringSet(pendingKey, emptySet())?.toMutableSet() ?: mutableSetOf()
        pending.removeAll(ids.toSet())
        val retryKey = mailboxKey("pending_retry_state")
        val retryState = pendingRetryState()
        ids.forEach(retryState::remove)
        check(prefs.edit().putStringSet(pendingKey, pending).putString(retryKey, retryState.toString()).commit()) {
            "Не удалось очистить состояние обработки Gmail ID"
        }
    }

    fun mcLabelId(): String = prefs.getString(mailboxKey("mc_label_id"), "").orEmpty()
    fun setMcLabelId(value: String) { if (value.isNotBlank()) prefs.edit().putString(mailboxKey("mc_label_id"), value).commit() }
    private fun gmailThreadKey(account: String, chat: String, suffix: String): String =
        "gmail_" + suffix + ":" + account.trim().lowercase() + ":" + chat.trim().lowercase()
    fun gmailThreadId(account: String, chat: String): String =
        prefs.getString(gmailThreadKey(account, chat, "thread"), "").orEmpty()
    fun gmailLastMessageId(account: String, chat: String): String =
        prefs.getString(gmailThreadKey(account, chat, "message"), "").orEmpty()
    fun saveGmailThread(account: String, chat: String, threadId: String?, rfcMessageId: String?) {
        val edit = prefs.edit()
        if (!threadId.isNullOrBlank()) edit.putString(gmailThreadKey(account, chat, "thread"), threadId)
        if (!rfcMessageId.isNullOrBlank()) edit.putString(gmailThreadKey(account, chat, "message"), rfcMessageId)
        edit.commit()
    }
    fun myEmail(): String = prefs.getString("email", "").orEmpty()
    fun setEmail(value: String) { prefs.edit().putString("email", value).commit() }
    fun ownProfileName(): String = prefs.getString("own_profile_name", "").orEmpty()
    fun ownProfileAvatar(): String = prefs.getString("own_profile_avatar", "").orEmpty()
    fun setOwnProfile(name: String?, avatar: String?) {
        prefs.edit().putString("own_profile_name", name.orEmpty().take(200))
            .putString("own_profile_avatar", avatar.orEmpty().take(700)).apply()
    }

    fun openIntervalSeconds(): Int = prefs.getInt("sync_open_seconds", 10)
    fun minimizedIntervalSeconds(): Int = prefs.getInt("sync_minimized_seconds", 30)
    fun closedIntervalSeconds(): Int = prefs.getInt("sync_closed_seconds", 60)
    fun diagnosticClickEnabled(): Boolean = prefs.getBoolean("sync_diagnostic_click", false)
    fun showTechnicalInfoEnabled(): Boolean = prefs.getBoolean("show_technical_info_in_email", false)

    fun acquireSyncLock(): Boolean = synchronized(syncLock) {
        val now = System.currentTimeMillis()
        if (prefs.getLong("sync_lock_until", 0L) > now) return@synchronized false
        prefs.edit().putLong("sync_lock_until", now + 10 * 60 * 1000L).commit()
        true
    }

    fun releaseSyncLock() = synchronized(syncLock) {
        prefs.edit().putLong("sync_lock_until", 0L).commit()
    }

    fun saveSyncSettings(open: Int, minimized: Int, closed: Int, diagnosticClick: Boolean, showTechnicalInfo: Boolean) {
        prefs.edit()
            .putInt("sync_open_seconds", open.coerceIn(5, 86400))
            .putInt("sync_minimized_seconds", minimized.coerceIn(5, 86400))
            .putInt("sync_closed_seconds", closed.coerceIn(60, 604800))
            .putBoolean("sync_diagnostic_click", diagnosticClick)
            .putBoolean("show_technical_info_in_email", showTechnicalInfo)
            .apply()
    }

    fun isProcessed(id: String): Boolean =
        prefs.getStringSet("processed", emptySet())?.contains(id) == true

    fun processedIds(): Set<String> =
        prefs.getStringSet("processed", emptySet())?.toSet().orEmpty()

    fun markProcessed(id: String) {
        val set = prefs.getStringSet("processed", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(id)
        check(prefs.edit().putStringSet("processed", set).commit()) {
            "Не удалось сохранить статус обработки Gmail ID"
        }
    }

    fun isRejectedHistoryId(id: String): Boolean =
        prefs.getStringSet(mailboxKey("rejected_history_ids"), emptySet())?.contains(id) == true

    /**
     * Persist a terminally rejected incoming MC email as a visible chat entry before
     * acknowledging its Gmail ID. Repeated calls are idempotent.
     */
    fun recordRejectedIncoming(
        senderEmail: String?,
        gmailId: String,
        errorCode: String,
        reason: String,
        diagnostics: String
    ): Boolean {
        require(gmailId.isNotBlank()) { "Gmail ID is required for quarantine" }
        if (isRejectedHistoryId(gmailId)) {
            markProcessed(gmailId)
            removePendingHistoryIds(listOf(gmailId))
            return false
        }

        val fallbackChat = "mc-errors@local.invalid"
        val sender = senderEmail?.trim()?.lowercase()?.takeIf { it.contains("@") } ?: fallbackChat
        val now = System.currentTimeMillis() / 1000L
        val messageId = "mc-error:$gmailId"
        val current = chats().toMutableList()
        val alreadyVisible = current.any { chat -> chat.messages.any { it.id == messageId } }
        if (!alreadyVisible) {
            val errorMessage = McMessage(
                id = messageId,
                chatId = sender,
                sender = sender,
                recipient = myEmail(),
                text = reason.ifBlank { "Не удалось обработать входящее сообщение MC" },
                timestamp = now,
                incoming = true,
                state = DeliveryState.FAILED,
                isProcessingError = true,
                errorCode = errorCode.ifBlank { "MC_MESSAGE_REJECTED" },
                errorReason = reason.ifBlank { "Не удалось обработать входящее сообщение MC" },
                errorDiagnostics = diagnostics.take(6000),
                gmailId = gmailId
            )
            val index = current.indexOfFirst { it.email.equals(sender, true) }
            if (index >= 0) {
                current[index] = current[index].copy(
                    messages = orderedMessages(current[index].messages + errorMessage)
                )
            } else {
                current.add(
                    McChat(
                        email = sender,
                        title = if (sender == fallbackChat) "Ошибки обработки" else sender,
                        messages = listOf(errorMessage),
                        nameManuallySet = sender == fallbackChat
                    )
                )
            }
            save(current)
        }

        val rejectedKey = mailboxKey("rejected_history_ids")
        val rejected = prefs.getStringSet(rejectedKey, emptySet())?.toMutableSet() ?: mutableSetOf()
        rejected.add(gmailId)
        check(prefs.edit().putStringSet(rejectedKey, rejected).commit()) {
            "Не удалось сохранить карантин Gmail ID"
        }
        markProcessed(gmailId)
        removePendingHistoryIds(listOf(gmailId))
        return true
    }

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
                    m.optBoolean("spam"),
                    m.optLong("sequence", 0L),
                    m.optLong("version", 1L),
                    m.optBoolean("deleted", false),
                    m.optLong("orderTimestamp", m.optLong("timestamp")),
                    m.optBoolean("processingError", false),
                    m.optString("errorCode"),
                    m.optString("errorReason"),
                    m.optString("errorDiagnostics"),
                    m.optString("gmailId")
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
        }.map { chat -> chat.copy(messages = orderedMessages(chat.messages)) }
            .sortedByDescending { it.messages.maxOfOrNull(McMessage::timestamp) ?: 0L }
    }

    fun save(chats: List<McChat>) {
        val root = JSONArray()
        chats.forEach { c ->
            val messages = JSONArray()
            c.messages.forEach { m ->
                messages.put(
                    JSONObject()
                        .put("id", m.id).put("sender", m.sender).put("recipient", m.recipient)
                        .put("text", m.text).put("timestamp", m.timestamp).put("incoming", m.incoming)
                        .put("state", m.state.name).put("spam", m.recoveredFromSpam)
                        .put("sequence", m.sequence).put("version", m.version).put("deleted", m.deleted)
                        .put("orderTimestamp", m.orderTimestamp)
                        .put("processingError", m.isProcessingError)
                        .put("errorCode", m.errorCode).put("errorReason", m.errorReason)
                        .put("errorDiagnostics", m.errorDiagnostics).put("gmailId", m.gmailId)
                )
            }
            root.put(JSONObject().put("email", c.email).put("title", c.title).put("nameManuallySet", c.nameManuallySet).put("senderName", c.senderName).put("avatarUrl", c.avatarUrl).put("messages", messages))
        }
        // Incoming messages must be durably stored before their Gmail IDs are acknowledged.
        check(prefs.edit().putString("chats", root.toString()).commit()) {
            "Не удалось надёжно сохранить локальную базу MC"
        }
    }

    fun nextSequence(chatId: String, sender: String): Long {
        val key = "message_sequence:${chatId.lowercase()}:${sender.lowercase()}"
        val stored = prefs.getLong(key, 0L)
        val ownMessages = chats().firstOrNull { it.email.equals(chatId, true) }
            ?.messages?.filter { it.sender.equals(sender, true) }.orEmpty()
        // Legacy MC builds wrote sequence=1 to every message; count those records
        // so the first upgraded message continues after the existing history.
        val existingMax = maxOf(
            ownMessages.maxOfOrNull { it.sequence } ?: 0L,
            ownMessages.size.toLong()
        )
        val next = maxOf(stored, existingMax) + 1L
        prefs.edit().putLong(key, next).commit()
        return next
    }

    fun sequenceForMutation(chatId: String, sender: String, messageId: String, current: Long): Long {
        val messages = chats().firstOrNull { it.email.equals(chatId, true) }
            ?.messages?.filter { it.sender.equals(sender, true) }.orEmpty()
        val collision = current > 0L && messages.any { it.id != messageId && it.sequence == current }
        return if (current <= 0L || collision) nextSequence(chatId, sender) else current
    }

    fun orderedMessages(messages: List<McMessage>): List<McMessage> =
        messages.sortedWith(compareBy<McMessage> { it.orderTimestamp }.thenBy { it.sequence }.thenBy { it.id })

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
