package ru.mailchat.app

import java.util.UUID

/**
 * Message envelope for MailChat emails.
 *
 * Proposed headers:
 * X-MailChat-Version: 1
 * X-MailChat-Chat-ID: stable conversation identifier
 * X-MailChat-Message-ID: unique message identifier
 *
 * This is a protocol draft only. The actual SMTP/IMAP transport is not wired yet.
 */
data class MailChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val chatId: String,
    val from: String,
    val to: String,
    val sentAtEpochMillis: Long,
    val text: String,
    val isMailChatMessage: Boolean = true
)
