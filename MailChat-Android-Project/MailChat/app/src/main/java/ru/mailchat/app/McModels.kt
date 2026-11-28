package ru.mailchat.app

import java.util.UUID

enum class DeliveryState { QUEUED, SENT_TO_GMAIL, DELIVERED, FAILED }

data class McMessage(
    val id: String = UUID.randomUUID().toString(),
    val chatId: String,
    val sender: String,
    val recipient: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis() / 1000,
    val incoming: Boolean,
    val state: DeliveryState = DeliveryState.QUEUED,
    val recoveredFromSpam: Boolean = false,
    val sequence: Long = 0L,
    val version: Long = 1L,
    val deleted: Boolean = false,
    // Stable chronological position; timestamp may change when an existing message is edited.
    val orderTimestamp: Long = timestamp,
    val isProcessingError: Boolean = false,
    val errorCode: String = "",
    val errorReason: String = "",
    val errorDiagnostics: String = "",
    val gmailId: String = ""
) {
    fun supportDiagnosticText(): String = buildList {
        add("MC: ошибка обработки входящего сообщения")
        add("Код: ${errorCode.ifBlank { "MC_MESSAGE_REJECTED" }}")
        if (errorReason.isNotBlank()) add("Причина: $errorReason")
        if (gmailId.isNotBlank()) add("Gmail ID: $gmailId")
        if (errorDiagnostics.isNotBlank()) {
            add("")
            add("Техническая диагностика:")
            add(errorDiagnostics)
        }
    }.joinToString("\n")
}

data class McChat(
    val email: String,
    val title: String = email,
    val messages: List<McMessage> = emptyList(),
    val nameManuallySet: Boolean = false,
    val senderName: String? = null,
    val avatarUrl: String? = null
)
