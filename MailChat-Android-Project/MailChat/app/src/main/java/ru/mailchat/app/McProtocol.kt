package ru.mailchat.app

import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale

object McProtocol {
    data class Parsed(
        val messageId: String,
        val chatId: String,
        val sender: String,
        val recipient: String,
        val text: String,
        val timestamp: Long
    )

    data class Verification(
        val parsed: Parsed?,
        val reason: String?,
        val diagnostics: List<String> = emptyList()
    )

    data class CanonicalInput(
        val messageId: String, val chatId: String, val senderId: String,
        val sender: String, val recipient: String, val version: String,
        val messageType: String, val timestamp: String, val sequence: String,
        val keyId: String, val publicKey: String, val text: String
    )

    fun normalizeText(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')

    fun canonical(input: CanonicalInput): String = listOf(
        "1", input.messageId, input.chatId, input.senderId, input.sender,
        input.recipient, input.version, input.messageType, input.timestamp,
        input.sequence, input.keyId, input.publicKey, normalizeText(input.text)
    ).joinToString("\n")

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    fun signatureDiagnostics(role: String, input: CanonicalInput, signature: String): List<String> {
        val fields = linkedMapOf(
            "protocol" to "1", "messageId" to input.messageId, "chatId" to input.chatId,
            "senderId" to input.senderId, "sender" to input.sender, "recipient" to input.recipient,
            "version" to input.version, "messageType" to input.messageType, "timestamp" to input.timestamp,
            "sequence" to input.sequence, "keyId" to input.keyId, "publicKey" to input.publicKey,
            "text" to normalizeText(input.text)
        )
        val bytes = canonical(input).toByteArray(Charsets.UTF_8)
        val signatureBytes = runCatching { Base64.getDecoder().decode(signature) }.getOrNull()
        val lines = mutableListOf(
            "MC_SIG_DIAG role=$role messageId=${input.messageId} fields=${fields.size} textBytes=${normalizeText(input.text).toByteArray(Charsets.UTF_8).size} canonicalBytes=${bytes.size} canonicalSha256=${sha256(bytes)}",
            "MC_SIG_DIAG signatureBytes=${signatureBytes?.size ?: -1} signatureSha256=${signatureBytes?.let { sha256(it) } ?: "invalid"} publicKeySha256=${sha256(input.publicKey.toByteArray(Charsets.UTF_8))}"
        )
        fields.forEach { (name, value) ->
            val fieldBytes = value.toByteArray(Charsets.UTF_8)
            lines += "MC_SIG_FIELD name=$name bytes=${fieldBytes.size} sha256=${sha256(fieldBytes)}"
        }
        return lines
    }

    private val required = listOf(
        "x-mc-protocol", "x-mc-message-id", "x-mc-chat-id",
        "x-mc-sender-id", "x-mc-sender", "x-mc-recipient",
        "x-mc-version", "x-mc-message-type", "x-mc-timestamp",
        "x-mc-sequence", "x-mc-key-id", "x-mc-public-key",
        "x-mc-signature-algorithm", "x-mc-signature"
    )

    fun verify(mail: GmailTransport.RemoteMessage, ownEmail: String): Parsed? {
        return verifyDetailed(mail, ownEmail).parsed
    }

    private fun parseBlock(block: String): MutableMap<String, String> {
        val values = linkedMapOf<String, StringBuilder>()
        var currentKey: String? = null
        val headerPattern = Regex("^([A-Za-z0-9-]+):\\s*(.*)$")
        val base64Pattern = Regex("^[A-Za-z0-9+/=_-]+$")

        for (raw in block.replace("\r\n", "\n").replace('\r', '\n').lines()) {
            if (raw.isBlank()) continue

            val line = raw.trimEnd()
            val match = headerPattern.matchEntire(line)

            if (match != null) {
                currentKey = match.groupValues[1].lowercase(Locale.ROOT)
                values.getOrPut(currentKey) { StringBuilder() }
                    .append(match.groupValues[2].trim())
                continue
            }

            val key = currentKey ?: continue
            val continuation = line.trim()

            if (line.firstOrNull()?.isWhitespace() == true) {
                values.getOrPut(key) { StringBuilder() }.append(continuation)
            } else if (
                key == "x-mc-signature" &&
                base64Pattern.matches(continuation)
            ) {
                values.getOrPut(key) { StringBuilder() }.append(continuation)
            } else {
                currentKey = null
            }
        }

        return values.mapValuesTo(linkedMapOf()) { it.value.toString().trim() }
    }


    fun verifyDetailed(
        mail: GmailTransport.RemoteMessage,
        ownEmail: String
    ): Verification {
        try {
            if (mail.subject != "[MC] Message") {
                return Verification(null, "Тема письма не соответствует MC")
            }

            val begin = "-----BEGIN MC DATA-----"
            val finish = "-----END MC DATA-----"
            val start = mail.body.indexOf(begin)
            val end = mail.body.indexOf(finish)

            if (start < 0 || end <= start) {
                return Verification(null, "В теле письма не найдены маркеры MC DATA")
            }

            val block = mail.body.substring(start + begin.length, end)
            val values = parseBlock(block)

            // Заголовки Gmail — резервный источник недостающих значений.
            for ((key, value) in mail.headers) {
                val normalized = key.lowercase()
                if (values[normalized].isNullOrBlank() && value.isNotBlank()) {
                    values[normalized] = value.trim()
                }
            }

            for (key in required) {
                if (values[key].isNullOrBlank()) {
                    return Verification(null, "Отсутствует заголовок $key")
                }
            }

            if (values["x-mc-protocol"] != "1") {
                return Verification(null, "Неподдерживаемая версия протокола")
            }

            if (values["x-mc-signature-algorithm"] != "Ed25519") {
                return Verification(null, "Неподдерживаемый алгоритм подписи")
            }

            val sender = values.getValue("x-mc-sender")
            val fromAddress = Regex(
                "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
                RegexOption.IGNORE_CASE
            ).find(mail.from)?.value

            if (fromAddress == null || !fromAddress.equals(sender, true)) {
                return Verification(null, "Адрес отправителя не совпадает")
            }

            val recipient = values.getValue("x-mc-recipient")
            if (!recipient.equals(ownEmail, true)) {
                return Verification(null, "Получатель не совпадает с аккаунтом MC")
            }

            val publicKey = KeyFactory.getInstance(
                "Ed25519", McCrypto.provider
            ).generatePublic(
                X509EncodedKeySpec(
                    Base64.getDecoder().decode(values.getValue("x-mc-public-key"))
                )
            )

            // Strip only the MIME separator; preserve leading user newlines.
            var rawText = mail.body.substring(end + finish.length)
            rawText = when {
                rawText.startsWith("\r\n\r\n") -> rawText.substring(4)
                rawText.startsWith("\n\n") -> rawText.substring(2)
                rawText.startsWith("\r\n") -> rawText.substring(2)
                rawText.startsWith("\n") -> rawText.substring(1)
                else -> rawText
            }
            val text = normalizeText(rawText)

            val messageId = values.getValue("x-mc-message-id")
            val chatId = values.getValue("x-mc-chat-id")
            val timestamp = values.getValue("x-mc-timestamp").toLong()
            if (kotlin.math.abs(System.currentTimeMillis() / 1000 - timestamp) > 60L * 60L * 24L * 30L) {
                return Verification(null, "Время сообщения выходит за допустимый диапазон")
            }

            val input = CanonicalInput(
                messageId, chatId, values.getValue("x-mc-sender-id"), sender, recipient,
                values.getValue("x-mc-version"), values.getValue("x-mc-message-type"),
                timestamp.toString(), values.getValue("x-mc-sequence"),
                values.getValue("x-mc-key-id"), values.getValue("x-mc-public-key"), text
            )
            val canonicalBytes = canonical(input).toByteArray(Charsets.UTF_8)
            val signature = values.getValue("x-mc-signature")
            val diagnostics = signatureDiagnostics("receiver", input, signature)
            if (!McCrypto.verify(publicKey, canonicalBytes, signature)) {
                return Verification(null, "Криптографическая подпись не прошла проверку", diagnostics)
            }

            return Verification(Parsed(messageId, chatId, sender, recipient, text, timestamp), null, diagnostics)
        } catch (e: Exception) {
            return Verification(null, "Ошибка проверки: ${e.javaClass.simpleName}")
        }
    }

}
