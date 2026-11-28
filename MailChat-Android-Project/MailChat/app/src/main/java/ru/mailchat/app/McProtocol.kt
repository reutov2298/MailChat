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
        val timestamp: Long,
        val sequence: Long = 0L,
        val messageVersion: Long = 1L,
        val messageType: String = "text",
        val senderName: String = "",
        val senderAvatarUrl: String = ""
    )

    data class Verification(
        val parsed: Parsed?,
        val reason: String?,
        val diagnostics: List<String> = emptyList(),
        val errorCode: String = "MC_MESSAGE_REJECTED",
        val errorField: String? = null
    )

    private class InvalidBase64FieldException(val fieldName: String) :
        IllegalArgumentException("Некорректное Base64 поле $fieldName")

    internal fun normalizeBase64Transport(value: String, fieldName: String, urlSafe: Boolean): String {
        val compact = buildString(value.length) {
            value.forEach { ch ->
                if (ch != ' ' && ch != '\t' && ch != '\r' && ch != '\n') append(ch)
            }
        }
        val alphabet = if (urlSafe) Regex("^[A-Za-z0-9_-]+={0,2}$")
            else Regex("^[A-Za-z0-9+/]+={0,2}$")
        val paddingIndex = compact.indexOf('=')
        if (!alphabet.matches(compact) || compact.length % 4 == 1 ||
            (paddingIndex >= 0 && compact.length % 4 != 0)
        ) throw InvalidBase64FieldException(fieldName)
        try {
            if (urlSafe) Base64.getUrlDecoder().decode(compact)
            else Base64.getDecoder().decode(compact)
        } catch (_: IllegalArgumentException) {
            throw InvalidBase64FieldException(fieldName)
        }
        return compact
    }

    fun senderAddress(mail: GmailTransport.RemoteMessage): String? =
        Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
            .find(mail.from)?.value?.lowercase(Locale.ROOT)

    data class CanonicalInput(
        val messageId: String, val chatId: String, val senderId: String,
        val sender: String, val recipient: String, val version: String,
        val messageType: String, val timestamp: String, val sequence: String,
        val keyId: String, val publicKey: String, val text: String,
        val messageVersion: String = "1", val extended: Boolean = false,
        val protocol: String = "1",
        val senderName: String = "",
        val senderAvatarUrl: String = ""
    )

    fun normalizeText(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')

    fun canonical(input: CanonicalInput): String = listOf(
        input.protocol, input.messageId, input.chatId, input.senderId, input.sender,
        input.recipient, input.version, input.messageType, input.timestamp,
        input.sequence, input.keyId, input.publicKey, normalizeText(input.text)
    ).let { fields -> (if (input.extended) fields + input.messageVersion else fields).let { base -> if (input.protocol == "3") base + input.senderName + input.senderAvatarUrl else base }.joinToString("\n") }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    fun signatureDiagnostics(role: String, input: CanonicalInput, signature: String): List<String> {
        val fields = linkedMapOf(
            "protocol" to input.protocol, "messageId" to input.messageId, "chatId" to input.chatId,
            "senderId" to input.senderId, "sender" to input.sender, "recipient" to input.recipient,
            "version" to input.version, "messageType" to input.messageType, "timestamp" to input.timestamp,
            "sequence" to input.sequence, "keyId" to input.keyId, "publicKey" to input.publicKey,
            "text" to normalizeText(input.text), "senderName" to input.senderName, "senderAvatarUrl" to input.senderAvatarUrl
        )
        val bytes = canonical(input).toByteArray(Charsets.UTF_8)
        val signatureBytes = runCatching { Base64.getDecoder().decode(signature) }.getOrNull()
        val lines = mutableListOf(
            "MC_SIG_DIAG role=$role messageId=${input.messageId} fields=${fields.size} textBytes=${normalizeText(input.text).toByteArray(Charsets.UTF_8).size} canonicalBytes=${bytes.size} canonicalSha256=${sha256(bytes)}",
            "MC_SIG_DIAG signatureBytes=${signatureBytes?.size ?: -1} signatureSha256=${signatureBytes?.let { sha256(it) } ?: "invalid"} publicKeySha256=${sha256(input.publicKey.toByteArray(Charsets.UTF_8))}"
        )
        fields.forEach { (name, value) ->
            val fieldBytes = value.toByteArray(Charsets.UTF_8)
            lines += "MC_SIG_FIELD role=$role messageId=${input.messageId} name=$name bytes=${fieldBytes.size} sha256=${sha256(fieldBytes)}"
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

    fun isMcCandidate(mail: GmailTransport.RemoteMessage): Boolean =
        mail.subject.equals("[MC] Message", ignoreCase = true) ||
            mail.headers.keys.any { it.startsWith("x-mc-", ignoreCase = true) } ||
            mail.body.contains("-----BEGIN MC DATA-----") ||
            mail.body.contains("X-MC-Message-ID:", ignoreCase = true)

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
            val hasBodyBlock = start >= 0 && end > start
            val bodyValues = if (hasBodyBlock) {
                parseBlock(mail.body.substring(start + begin.length, end))
            } else emptyMap()

            // RFC/MIME headers are the authoritative transport for protocol data.
            // The visible body block is optional and retained for compatibility/debugging.
            val values = linkedMapOf<String, String>()
            for ((key, value) in mail.headers) {
                if (key.lowercase().startsWith("x-mc-") && value.isNotBlank()) {
                    values[key.lowercase()] = value.trim()
                }
            }
            // Legacy messages may carry X-MC-Text-Base64 only inside the visible body block.
            for ((key, value) in bodyValues) {
                val headerValue = values[key]
                if (headerValue != null && headerValue != value) {
                    return Verification(null, "Значение $key в теле не совпадает с MIME-заголовком")
                }
                if (headerValue == null) values[key] = value
            }
            if (values.isEmpty()) return Verification(null, "Не найдены служебные данные MC в MIME-заголовках или теле письма")

            for (key in required) {
                if (values[key].isNullOrBlank()) {
                    return Verification(null, "Отсутствует заголовок $key")
                }
            }

            val protocol = values.getValue("x-mc-protocol")
            if (protocol !in setOf("1", "2", "3")) {
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
                    Base64.getDecoder().decode(
                        normalizeBase64Transport(values.getValue("x-mc-public-key"), "x-mc-public-key", false)
                    )
                )
            )

            // New messages always put user text first. If the optional block exists, strip it;
            // otherwise the complete MIME body is the visible message text. Legacy messages
            // with text after END MC DATA remain supported.
            val rawText = if (hasBodyBlock) {
                val textBeforeBlock = mail.body.substring(0, start)
                val textAfterBlock = mail.body.substring(end + finish.length)
                if (textBeforeBlock.isNotEmpty()) {
                    textBeforeBlock.removeSuffix("\r\n").removeSuffix("\n").removeSuffix("\r")
                } else {
                    when {
                        textAfterBlock.startsWith("\r\n\r\n") -> textAfterBlock.substring(4)
                        textAfterBlock.startsWith("\n\n") -> textAfterBlock.substring(2)
                        textAfterBlock.startsWith("\r\n") -> textAfterBlock.substring(2)
                        textAfterBlock.startsWith("\n") -> textAfterBlock.substring(1)
                        else -> textAfterBlock
                    }
                }
            } else mail.body.removeSuffix("\r\n").removeSuffix("\n").removeSuffix("\r")
            // Protocol v3 senders include the exact UTF-8 text bytes as URL-safe Base64.
            // Prefer this signed representation over the MIME display body, which Gmail
            // is allowed to normalize (line endings, transfer encoding, etc.).
            val text = values["x-mc-text-base64"]?.takeIf { it.isNotBlank() }?.let { encoded ->
                require(encoded.length <= 2_800_000) { "Подписанный текст слишком длинный" }
                val normalized = normalizeBase64Transport(encoded, "x-mc-text-base64", true)
                normalizeText(String(Base64.getUrlDecoder().decode(normalized), Charsets.UTF_8))
            } ?: normalizeText(rawText)

            val messageId = values.getValue("x-mc-message-id")
            val chatId = values.getValue("x-mc-chat-id")
            val timestamp = values.getValue("x-mc-timestamp").toLong()
            val messageType = values.getValue("x-mc-message-type")
            if (messageType !in setOf("text", "edit", "delete")) {
                return Verification(null, "Неизвестный тип события MC")
            }
            if (protocol == "1" && messageType != "text") return Verification(null, "Событие требует протокол MC 2")
            if (protocol in setOf("2", "3") && ((protocol == "2" && messageType == "text") || !values.containsKey("x-mc-message-version"))) {
                return Verification(null, "Некорректное событие протокола MC 2")
            }
            val allowedAgeSeconds = if (messageType == "text") 60L * 60L * 24L * 30L else 60L * 60L * 24L * 3650L
            if (kotlin.math.abs(System.currentTimeMillis() / 1000 - timestamp) > allowedAgeSeconds) {
                return Verification(null, "Время сообщения выходит за допустимый диапазон")
            }

            fun decodeProfileHeader(key: String, maxBytes: Int): String {
                val encoded = values[key].orEmpty()
                if (encoded.isBlank()) return ""
                if (encoded.length > maxBytes * 2) throw IllegalArgumentException("Профильное поле слишком длинное")
                val normalized = normalizeBase64Transport(encoded, key, true)
                val decoded = String(Base64.getUrlDecoder().decode(normalized), Charsets.UTF_8)
                if (decoded.toByteArray(Charsets.UTF_8).size > maxBytes) throw IllegalArgumentException("Профильное поле слишком длинное")
                return decoded
            }
            val senderName = if (protocol == "3") decodeProfileHeader("x-mc-sender-name", 800).take(200) else ""
            val senderAvatar = if (protocol == "3") decodeProfileHeader("x-mc-sender-avatar", 700).also {
                if (it.isNotBlank() && !it.startsWith("https://")) throw IllegalArgumentException("Недопустимый URL аватара")
            } else ""
            val input = CanonicalInput(
                messageId, chatId, values.getValue("x-mc-sender-id"), sender, recipient,
                values.getValue("x-mc-version"), messageType,
                timestamp.toString(), values.getValue("x-mc-sequence"),
                values.getValue("x-mc-key-id"), values.getValue("x-mc-public-key"), text,
                values["x-mc-message-version"] ?: "1", protocol in setOf("2", "3"), protocol,
                senderName, senderAvatar
            )
            val canonicalBytes = canonical(input).toByteArray(Charsets.UTF_8)
            val signature = normalizeBase64Transport(
                values.getValue("x-mc-signature"), "x-mc-signature", false
            )
            val diagnostics = signatureDiagnostics("receiver", input, signature)
            if (!McCrypto.verify(publicKey, canonicalBytes, signature)) {
                return Verification(
                    null, "Криптографическая подпись не прошла проверку", diagnostics,
                    "MC_SIGNATURE_INVALID", "x-mc-signature"
                )
            }

            val sequence = values.getValue("x-mc-sequence").toLong()
            if (sequence <= 0L) return Verification(null, "Некорректный порядковый номер сообщения")
            val messageVersion = (values["x-mc-message-version"] ?: "1").toLong()
            if (messageVersion <= 0L) return Verification(null, "Некорректная версия сообщения")
            if (messageType == "text" && messageVersion != 1L) return Verification(null, "Некорректная версия нового сообщения")
            if (messageType != "text" && messageVersion < 2L) return Verification(null, "Некорректная версия изменения сообщения")
            return Verification(Parsed(messageId, chatId, sender, recipient, text, timestamp, sequence, messageVersion, messageType, senderName, senderAvatar), null, diagnostics)
        } catch (e: Exception) {
            val stack = e.stackTrace.take(12).joinToString(" <- ") {
                "${it.className}.${it.methodName}:${it.lineNumber}"
            }
            val detail = e.message.orEmpty().replace(Regex("[\\r\\n]+"), " ").take(160)
            val invalidBase64 = e as? InvalidBase64FieldException
            val errorCode = if (invalidBase64 != null) "MC_BASE64_INVALID" else "MC_MESSAGE_MALFORMED"
            val errorField = invalidBase64?.fieldName
            val diagnostic = "MC_VERIFY_EXCEPTION code=$errorCode field=${errorField ?: "unknown"} type=${e.javaClass.simpleName} detail=$detail stack=$stack"
            return Verification(
                null,
                "Ошибка проверки: " + (errorField?.let { "некорректное поле $it" } ?: e.javaClass.simpleName) +
                    if (detail.isBlank()) "" else ": $detail",
                listOf(diagnostic),
                errorCode,
                errorField
            )
        }
    }

}
