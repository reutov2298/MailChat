package ru.mailchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class McProtocolVerificationTest {
    private val sender = "sender@example.com"
    private val recipient = "me@example.com"

    private fun validMail(
        publicKeyHeader: String? = null,
        signatureHeader: String? = null,
        textBase64Header: String? = null
    ): GmailTransport.RemoteMessage {
        val pair = McCrypto.generate()
        val publicKey = McCrypto.publicKey(pair)
        val messageId = "message-1"
        val chatId = "chat-1"
        val senderId = "sender-device"
        val version = "1.0"
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val sequence = "1"
        val keyId = "key-1"
        val text = "Hello"
        val input = McProtocol.CanonicalInput(
            messageId = messageId,
            chatId = chatId,
            senderId = senderId,
            sender = sender,
            recipient = recipient,
            version = version,
            messageType = "text",
            timestamp = timestamp,
            sequence = sequence,
            keyId = keyId,
            publicKey = publicKey,
            text = text
        )
        val signature = McCrypto.sign(pair, McProtocol.canonical(input).toByteArray(Charsets.UTF_8))
        val encodedText = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(text.toByteArray(Charsets.UTF_8))
        val headers = linkedMapOf(
            "x-mc-protocol" to "1",
            "x-mc-message-id" to messageId,
            "x-mc-chat-id" to chatId,
            "x-mc-sender-id" to senderId,
            "x-mc-sender" to sender,
            "x-mc-recipient" to recipient,
            "x-mc-version" to version,
            "x-mc-message-type" to "text",
            "x-mc-timestamp" to timestamp,
            "x-mc-sequence" to sequence,
            "x-mc-key-id" to keyId,
            "x-mc-public-key" to (publicKeyHeader ?: publicKey),
            "x-mc-signature-algorithm" to "Ed25519",
            "x-mc-signature" to (signatureHeader ?: signature),
            "x-mc-text-base64" to (textBase64Header ?: encodedText)
        )
        return GmailTransport.RemoteMessage(
            id = "gmail-1",
            threadId = null,
            from = "Sender <$sender>",
            to = recipient,
            subject = "[MC] Message",
            body = "Hello",
            internalDate = System.currentTimeMillis(),
            spam = false,
            headers = headers
        )
    }

    @Test
    fun validMessageVerifies() {
        val result = McProtocol.verifyDetailed(validMail(), recipient)
        assertNotNull(result.parsed)
        assertNull(result.reason)
    }

    @Test
    fun mimeFoldingInPublicKeySignatureAndTextFieldsDoesNotBreakVerification() {
        val pair = McCrypto.generate()
        val publicKey = McCrypto.publicKey(pair)
        // The helper builds a matching signature for the key; insert MIME folding
        // in transport values only, preserving the original canonical representation.
        val foldedPublicKey = publicKey.chunked(24).joinToString("\\r\\n \\t")
        val mail = validMail(publicKeyHeader = foldedPublicKey)
        val headers = mail.headers.toMutableMap()
        val signature = headers.getValue("x-mc-signature")
        headers["x-mc-signature"] = signature.chunked(24).joinToString("\\r\\n \\t")
        val textBase64 = headers.getValue("x-mc-text-base64")
        headers["x-mc-text-base64"] = textBase64.chunked(4).joinToString("\\n ")
        val adjusted = mail.copy(headers = headers)
        val result = McProtocol.verifyDetailed(adjusted, recipient)
        assertNotNull(result.parsed)
    }

    @Test
    fun invalidBase64ReportsExactField() {
        val mail = validMail()
        val headers = mail.headers.toMutableMap()
        headers["x-mc-text-base64"] = "SGVsbG8%"
        val result = McProtocol.verifyDetailed(mail.copy(headers = headers), recipient)
        assertNull(result.parsed)
        assertEquals("MC_BASE64_INVALID", result.errorCode)
        assertEquals("x-mc-text-base64", result.errorField)
    }

    @Test
    fun invalidSignatureIsTerminalCryptographicRejection() {
        val mail = validMail()
        val headers = mail.headers.toMutableMap()
        headers["x-mc-signature"] = Base64.getEncoder().encodeToString(ByteArray(64) { 1 })
        val result = McProtocol.verifyDetailed(mail.copy(headers = headers), recipient)
        assertNull(result.parsed)
        assertEquals("MC_SIGNATURE_INVALID", result.errorCode)
    }
}
