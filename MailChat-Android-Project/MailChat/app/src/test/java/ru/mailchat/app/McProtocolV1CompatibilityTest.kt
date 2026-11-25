package ru.mailchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class McProtocolV1CompatibilityTest {
    private fun signedMail(text: String): GmailTransport.RemoteMessage {
        val pair = McCrypto.generate()
        val messageId = "legacy-message-1"
        val chatId = "sender@example.com"
        val senderId = "android-legacy-device"
        val sender = "sender@example.com"
        val recipient = "recipient@example.com"
        val version = "1.0.0-prototype"
        val messageType = "text"
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val sequence = "1"
        val keyId = sender
        val publicKey = McCrypto.publicKey(pair)
        val normalizedText = McProtocol.normalizeText(text)
        val canonical = listOf(
            "1", messageId, chatId, senderId, sender, recipient, version,
            messageType, timestamp, sequence, keyId, publicKey, normalizedText
        ).joinToString("\n")
        val signature = McCrypto.sign(pair, canonical.toByteArray(Charsets.UTF_8))
        val headers = listOf(
            "X-MC-Protocol: 1",
            "X-MC-Message-ID: $messageId",
            "X-MC-Chat-ID: $chatId",
            "X-MC-Sender-ID: $senderId",
            "X-MC-Sender: $sender",
            "X-MC-Recipient: $recipient",
            "X-MC-Version: $version",
            "X-MC-Message-Type: $messageType",
            "X-MC-Timestamp: $timestamp",
            "X-MC-Sequence: $sequence",
            "X-MC-Key-ID: $keyId",
            "X-MC-Public-Key: $publicKey",
            "X-MC-Signature-Algorithm: Ed25519",
            "X-MC-Signature: $signature"
        )
        val body = buildString {
            append("Ниже приведены технические данные MC:\r\n")
            append("-----BEGIN MC DATA-----\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("-----END MC DATA-----\r\n\r\n")
            append(normalizedText.replace("\n", "\r\n"))
        }
        return GmailTransport.RemoteMessage(
            id = "gmail-id", threadId = null,
            from = "Legacy sender <$sender>", to = recipient,
            subject = "[MC] Message", body = body,
            internalDate = System.currentTimeMillis(), spam = false,
            headers = emptyMap()
        )
    }

    @Test fun validLegacyV1MessagePassesEd25519Verification() {
        val result = McProtocol.verifyDetailed(signedMail("first line\nsecond line"), "recipient@example.com")
        assertNull(result.reason)
        assertNotNull(result.parsed)
        assertEquals("first line\nsecond line", result.parsed?.text)
        assertEquals("", result.parsed?.senderName)
        assertEquals("", result.parsed?.senderAvatarUrl)
    }

    @Test fun modifiedLegacyV1TextFailsSignatureVerification() {
        val mail = signedMail("original")
        val tampered = mail.copy(body = mail.body.replace("original", "tampered"))
        val result = McProtocol.verifyDetailed(tampered, "recipient@example.com")
        assertNull(result.parsed)
        assertEquals("Криптографическая подпись протокола v1 не прошла проверку", result.reason)
    }

    @Test fun wrongRecipientIsRejectedForLegacyV1() {
        val result = McProtocol.verifyDetailed(signedMail("hello"), "other@example.com")
        assertNull(result.parsed)
        assertEquals("Получатель не совпадает с аккаунтом MC", result.reason)
    }
}
