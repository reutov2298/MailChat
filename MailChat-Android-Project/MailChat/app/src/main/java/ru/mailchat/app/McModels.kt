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
    val recoveredFromSpam: Boolean = false
)

data class McChat(
    val email: String,
    val title: String = email,
    val messages: List<McMessage> = emptyList(),
    val nameManuallySet: Boolean = false,
    val senderName: String? = null
)
