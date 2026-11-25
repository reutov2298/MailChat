package ru.mailchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class McProtocolMailBodyTest {
    private fun signedMail(text: String, senderName: String = "Тестовый отправитель", senderAvatarUrl: String = "https://example.com/avatar.jpg"): GmailTransport.RemoteMessage {
        val pair = McCrypto.generate()
        val normalized = McProtocol.normalizeText(text)
        val input = McProtocol.CanonicalInput(
            messageId = "message-1",
            chatId = "chat-1",
            senderId = "device-1",
            sender = "sender@example.com",
            recipient = "recipient@example.com",
            version = "1.1.1",
            messageType = "text",
            timestamp = (System.currentTimeMillis() / 1000).toString(),
            sequence = "1",
            keyId = "sender@example.com",
            publicKey = McCrypto.publicKey(pair),
            text = normalized,
            senderName = senderName,
            senderAvatarUrl = senderAvatarUrl
        )
        val signature = McCrypto.sign(pair, McProtocol.canonical(input).toByteArray(Charsets.UTF_8))
        val headers = listOf(
            "X-MC-Protocol: 2",
            "X-MC-Message-ID: ${input.messageId}",
            "X-MC-Chat-ID: ${input.chatId}",
            "X-MC-Sender-ID: ${input.senderId}",
            "X-MC-Sender: ${input.sender}",
            "X-MC-Recipient: ${input.recipient}",
            "X-MC-Version: ${input.version}",
            "X-MC-Message-Type: text",
            "X-MC-Timestamp: ${input.timestamp}",
            "X-MC-Sequence: 1",
            "X-MC-Key-ID: ${input.keyId}",
            "X-MC-Public-Key: ${input.publicKey}",
            "X-MC-Sender-Name: ${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(input.senderName.toByteArray(Charsets.UTF_8))}",
            "X-MC-Sender-Avatar: ${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(input.senderAvatarUrl.toByteArray(Charsets.UTF_8))}",
            "X-MC-Signature-Algorithm: Ed25519",
            "X-MC-Signature: $signature"
        )
        val body = buildString {
            append(normalized.replace("\n", "\r\n"))
            append("\r\n-----BEGIN MC DATA-----\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("-----END MC DATA-----\r\n")
        }
        return GmailTransport.RemoteMessage(
            id = "gmail-id",
            threadId = null,
            from = "Sender <sender@example.com>",
            to = "recipient@example.com",
            subject = "[MC] Message",
            body = body,
            internalDate = System.currentTimeMillis(),
            spam = false,
            headers = emptyMap()
        )
    }

    @Test fun multilineTextIsRecoveredAndSignaturePasses() {
        val original = "  first line\n\nsecond line  "
        val result = McProtocol.verifyDetailed(signedMail(original), "recipient@example.com")
        assertNull(result.reason)
        assertNotNull(result.parsed)
        assertEquals(original, result.parsed?.text)
        assertEquals("Тестовый отправитель", result.parsed?.senderName)
        assertEquals("https://example.com/avatar.jpg", result.parsed?.senderAvatarUrl)
    }

    @Test fun changedTextFailsSignatureVerification() {
        val mail = signedMail("original message")
        val tampered = mail.copy(body = mail.body.replace("original message", "changed message"))
        val result = McProtocol.verifyDetailed(tampered, "recipient@example.com")
        assertEquals("Криптографическая подпись не прошла проверку", result.reason)
    }

    @Test fun emptyProfileFieldsAreSupported() {
        val result = McProtocol.verifyDetailed(signedMail("profile unavailable", "", ""), "recipient@example.com")
        assertNull(result.reason)
        assertEquals("", result.parsed?.senderName)
        assertEquals("", result.parsed?.senderAvatarUrl)
    }

    @Test fun emptyTextIsSupported() {
        val result = McProtocol.verifyDetailed(signedMail(""), "recipient@example.com")
        assertNull(result.reason)
        assertEquals("", result.parsed?.text)
    }

    @Test fun protocolV1IsRoutedToLegacySignatureVerifier() {
        val mail = signedMail("protocol diagnostic")
        val changed = mail.copy(body = mail.body.replace("X-MC-Protocol: 2", "X-MC-Protocol: 1"))
        val result = McProtocol.verifyDetailed(changed, "recipient@example.com")
        assertNull(result.parsed)
        assertEquals("Криптографическая подпись протокола v1 не прошла проверку", result.reason)
    }
}
