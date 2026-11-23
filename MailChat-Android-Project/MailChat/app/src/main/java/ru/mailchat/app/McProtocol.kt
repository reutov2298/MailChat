package ru.mailchat.app

import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object McProtocol {
    data class Parsed(val messageId: String, val chatId: String, val sender: String, val recipient: String, val text: String, val timestamp: Long)
    private val required = listOf("x-mc-protocol", "x-mc-message-id", "x-mc-chat-id", "x-mc-sender-id", "x-mc-sender", "x-mc-recipient", "x-mc-version", "x-mc-message-type", "x-mc-timestamp", "x-mc-sequence", "x-mc-key-id", "x-mc-public-key", "x-mc-signature-algorithm", "x-mc-signature")

    fun verify(mail: GmailTransport.RemoteMessage, ownEmail: String): Parsed? {
        return try {
        if (mail.subject != "[MC] Message") return null
        val start = mail.body.indexOf("-----BEGIN MC DATA-----")
        val end = mail.body.indexOf("-----END MC DATA-----")
        if (start < 0 || end <= start) return null
        val block = mail.body.substring(start + "-----BEGIN MC DATA-----".length, end)
        val duplicated = block.lineSequence().mapNotNull { line ->
            val idx = line.indexOf(':'); if (idx <= 0) null else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
        }.toMap()
        if (required.any { key -> duplicated[key].isNullOrBlank() || mail.headers[key] != duplicated[key] }) return null
        if (duplicated["x-mc-protocol"] != "1" || duplicated["x-mc-signature-algorithm"] != "Ed25519") return null
        val sender = duplicated.getValue("x-mc-sender")
        val fromAddress = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE).find(mail.from)?.value
        if (fromAddress == null || !fromAddress.equals(sender, true)) return null
        val recipient = duplicated.getValue("x-mc-recipient")
        if (!recipient.equals(ownEmail, true)) return null
        val publicKeyEncoded = Base64.getDecoder().decode(duplicated.getValue("x-mc-public-key"))
        val publicKey = KeyFactory.getInstance("Ed25519", McCrypto.provider).generatePublic(X509EncodedKeySpec(publicKeyEncoded))
        val text = mail.body.substring(end + "-----END MC DATA-----".length).trimStart('\r', '\n').trimEnd('\r', '\n')
        val messageId = duplicated.getValue("x-mc-message-id")
        val chatId = duplicated.getValue("x-mc-chat-id")
        val timestamp = duplicated.getValue("x-mc-timestamp").toLong()
        if (kotlin.math.abs(System.currentTimeMillis() / 1000 - timestamp) > 60L * 60L * 24L * 30L) return null
        val canonical = listOf("1", messageId, chatId, duplicated.getValue("x-mc-sender-id"), sender, recipient,
            duplicated.getValue("x-mc-version"), duplicated.getValue("x-mc-message-type"), timestamp.toString(),
            duplicated.getValue("x-mc-sequence"), duplicated.getValue("x-mc-key-id"), duplicated.getValue("x-mc-public-key"), text).joinToString("\n")
        if (!McCrypto.verify(publicKey, canonical.toByteArray(Charsets.UTF_8), duplicated.getValue("x-mc-signature"))) return null
        Parsed(messageId, chatId, sender, recipient, text, timestamp)
        } catch (_: Exception) { null }
    }
}
