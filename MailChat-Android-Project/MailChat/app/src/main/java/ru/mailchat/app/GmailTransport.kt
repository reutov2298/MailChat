package ru.mailchat.app

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

class GmailTransport {
    class GmailApiException(val statusCode: Int, val responseBody: String) :
        IllegalStateException("Gmail API $statusCode: ${responseBody.take(300)}")
    companion object {
        // Serialize Gmail API calls across all transport instances and keep a
        // minimum gap between request starts to prevent bursts from sync/send/worker.
        private val requestMonitor = Any()
        private var lastRequestStartedAt = 0L
        private const val MIN_REQUEST_GAP_MS = 500L
    }

    var onRequestStarted: (() -> Unit)? = null
    var onServerRequest: ((String) -> Unit)? = null
    var onDiagnostic: ((String) -> Unit)? = null
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    data class Profile(val email: String, val historyId: String)
    data class OwnProfile(val name: String?, val photoUrl: String?)
    data class HistoryChanges(val historyId: String, val messageIds: List<String>)
    data class SendResult(val id: String, val threadId: String?, val rfcMessageId: String)
    data class RemoteMessage(val id: String, val threadId: String?, val from: String, val to: String, val subject: String, val body: String, val internalDate: Long, val spam: Boolean, val headers: Map<String, String>, val senderName: String = "")

    private fun request(url: String, token: String, method: String = "GET", body: String? = null, contentType: String = "application/json; charset=utf-8"): String {
        var lastError: GmailApiException? = null
        for (attempt in 0..3) {
            try {
                return synchronized(requestMonitor) {
                    val waitMs = MIN_REQUEST_GAP_MS - (System.currentTimeMillis() - lastRequestStartedAt)
                    if (waitMs > 0) Thread.sleep(waitMs)
                    lastRequestStartedAt = System.currentTimeMillis()
                    onRequestStarted?.invoke()
                    val endpoint = url.substringAfter("/gmail/v1", url).substringBefore("?")
                    onServerRequest?.invoke(method + " " + endpoint)
                    val builder = Request.Builder().url(url).header("Authorization", "Bearer " + token)
                    when (method) {
                        "POST" -> builder.post((body ?: "").toRequestBody(contentType.toMediaType()))
                        "PUT" -> builder.put((body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
                        else -> builder.get()
                    }
                    http.newCall(builder.build()).execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw GmailApiException(response.code, text)
                        text
                    }
                }
            } catch (e: GmailApiException) {
                lastError = e
                val reason = e.responseBody.lowercase()
                val retryable = e.statusCode == 429 ||
                    (e.statusCode == 403 && listOf("ratelimitexceeded", "userratelimitexceeded", "quotaexceeded", "too many concurrent").any(reason::contains))
                if (!retryable || attempt == 3) throw e
                val retryAfter = Regex("(?i)retry-after\\s*[:=]\\s*(\\d+)").find(e.responseBody)?.groupValues?.get(1)?.toLongOrNull()
                val backoff = (retryAfter?.times(1000L) ?: (1000L shl attempt)).coerceAtMost(30_000L)
                val jitter = kotlin.random.Random.nextLong(100L, 500L)
                onDiagnostic?.invoke("Gmail API HTTP " + e.statusCode + "; повтор через " + ((backoff + jitter) / 1000.0) + " с")
                Thread.sleep(backoff + jitter)
            }
        }
        throw lastError ?: IllegalStateException("Gmail request failed")
    }

    fun profile(token: String): Profile {
        val j = JSONObject(request("${GmailConfig.GMAIL_API}/profile", token))
        return Profile(j.optString("emailAddress"), j.optString("historyId"))
    }

    fun ownProfile(token: String): OwnProfile {
        val root = JSONObject(request("https://openidconnect.googleapis.com/v1/userinfo", token))
        val name = root.optString("name").trim().take(200).takeIf { it.isNotBlank() }
        val photo = root.optString("picture").trim().takeIf { it.startsWith("https://") && it.length <= 700 }
        onDiagnostic?.invoke("Google profile: namePresent=${!name.isNullOrBlank()}, avatarPresent=${!photo.isNullOrBlank()}")
        return OwnProfile(name, photo)
    }

    fun send(
        token: String, from: String, to: String, text: String, chatId: String, publicKey: String,
        messageId: String = UUID.randomUUID().toString(), sequence: Long = 1L,
        messageVersion: Long = 1L, messageType: String = "text",
        messageTimestamp: Long? = null, senderName: String? = null, senderAvatarUrl: String? = null,
        threadId: String? = null, previousRfcMessageId: String? = null,
        sign: (ByteArray) -> String
    ): SendResult {
        val id = messageId
        val ts = messageTimestamp ?: System.currentTimeMillis() / 1000
        val normalizedText = McProtocol.normalizeText(text)
        val senderId = "android-${android.os.Build.MODEL}"
        val version = "1.0.0-prototype"
        val sequenceValue = sequence.toString()
        val messageVersionValue = messageVersion.toString()
        val protocol = "3"
        val keyId = from
        val canonicalInput = McProtocol.CanonicalInput(
            id, chatId, senderId, from, to, version, messageType, ts.toString(),
            sequenceValue, keyId, publicKey, normalizedText, messageVersionValue,
            protocol in setOf("2", "3"), protocol, senderName.orEmpty().take(200), senderAvatarUrl.orEmpty().take(700)
        )
        val canonical = McProtocol.canonical(canonicalInput)
        val signature = sign(canonical.toByteArray(StandardCharsets.UTF_8))
        McProtocol.signatureDiagnostics("sender", canonicalInput, signature).forEach { onDiagnostic?.invoke(it) }
        val technical = mutableListOf(
            "X-MC-Protocol: $protocol", "X-MC-Message-ID: $id", "X-MC-Chat-ID: $chatId",
            "X-MC-Sender-ID: $senderId", "X-MC-Sender: $from", "X-MC-Recipient: $to",
            "X-MC-Version: $version", "X-MC-Message-Type: $messageType", "X-MC-Timestamp: $ts",
            "X-MC-Sequence: $sequenceValue", "X-MC-Key-ID: $keyId", "X-MC-Public-Key: $publicKey",
            "X-MC-Signature-Algorithm: Ed25519", "X-MC-Signature: $signature"
        )
        technical.add(10, "X-MC-Message-Version: $messageVersionValue")
        val nameHeader = Base64.encodeToString(senderName.orEmpty().take(200).toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val avatarHeader = Base64.encodeToString(senderAvatarUrl.orEmpty().take(700).toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        technical.add("X-MC-Sender-Name: $nameHeader")
        technical.add("X-MC-Sender-Avatar: $avatarHeader")
        // Preserve the exact signed UTF-8 text independently of Gmail MIME body normalization.
        val textBase64 = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(normalizedText.toByteArray(StandardCharsets.UTF_8))
        val dataTechnical = technical.toMutableList()
        dataTechnical.add("X-MC-Text-Base64: " + textBase64.chunked(72).joinToString("\r\n "))
        val rfcMessageId = "<" + UUID.randomUUID().toString() + "@mc.local>"
        val mime = buildString {
            append("From: $from\r\nTo: $to\r\nSubject: [MC] Message\r\n")
            append("Message-ID: " + rfcMessageId + "\r\n")
            if (!previousRfcMessageId.isNullOrBlank()) {
                append("In-Reply-To: " + previousRfcMessageId + "\r\n")
                append("References: " + previousRfcMessageId + "\r\n")
            }
            append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n")
            technical.forEach { append(it).append("\r\n") }
            append("\r\n")
            append(normalizedText.replace("\n", "\r\n"))
            append("\r\n")
            append("-----BEGIN MC DATA-----\r\n")
            dataTechnical.forEach { append(it).append("\r\n") }
            append("-----END MC DATA-----\r\n")
        }
        val encoded = Base64.encodeToString(mime.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val payloadObject = JSONObject().put("raw", encoded)
        if (!threadId.isNullOrBlank()) payloadObject.put("threadId", threadId)
        val result = JSONObject(request(GmailConfig.GMAIL_API + "/messages/send", token, "POST", payloadObject.toString()))
        return SendResult(result.optString("id", id), result.optString("threadId").takeIf { it.isNotBlank() }, rfcMessageId)
    }

    fun list(token: String, query: String, max: Int = 50): List<RemoteMessage> {
        val q = URLEncoder.encode(query, "UTF-8")
        val root = JSONObject(request("${GmailConfig.GMAIL_API}/messages?maxResults=$max&includeSpamTrash=true&q=$q", token))
        val ids = root.optJSONArray("messages") ?: return emptyList()
        if (ids.length() == 0) return emptyList()
        val messageIds = (0 until ids.length()).mapNotNull { ids.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
        val boundary = "mc_batch_${UUID.randomUUID().toString().replace("-", "")}"
        val batchBody = buildString {
            messageIds.forEachIndexed { index, id ->
                append("--").append(boundary).append("\r\nContent-Type: application/http\r\n")
                append("Content-ID: <mc-").append(index).append(">\r\n\r\n")
                append("GET /gmail/v1/users/me/messages/").append(id).append("?format=full HTTP/1.1\r\n\r\n")
            }
            append("--").append(boundary).append("--\r\n")
        }
        val response = request("https://www.googleapis.com/batch/gmail/v1", token, "POST", batchBody, "multipart/mixed; boundary=$boundary")
        val responseBoundary = Regex("(?m)^--([^\\r\\n]+)").find(response.trimStart())?.groupValues?.get(1)?.removeSuffix("--")
            ?: throw IllegalStateException("Gmail batch response has no boundary")
        val result = mutableListOf<RemoteMessage>()
        response.split("--$responseBoundary").forEach { part ->
            val httpStart = part.indexOf("HTTP/1.")
            if (httpStart < 0) return@forEach
            val innerStatus = Regex("HTTP/1\\.[01]\\s+(\\d{3})").find(part.substring(httpStart))
                ?.groupValues?.get(1)?.toIntOrNull()
            if (innerStatus != null && innerStatus !in 200..299) {
                throw GmailApiException(innerStatus, part.substring(httpStart).take(300))
            }
            val jsonStart = part.indexOf("{", httpStart)
            val jsonEnd = part.lastIndexOf("}")
            if (jsonStart < 0 || jsonEnd < jsonStart) return@forEach
            val j = JSONObject(part.substring(jsonStart, jsonEnd + 1))
            val payload = j.optJSONObject("payload")
            val headers = payload?.optJSONArray("headers")
            var from = ""; var to = ""; var subject = ""
            val headerMap = linkedMapOf<String, String>()
            if (headers != null) for (i in 0 until headers.length()) {
                val h = headers.getJSONObject(i); val hn = h.optString("name"); val hv = h.optString("value")
                headerMap[hn.lowercase()] = hv
                when (hn.lowercase()) { "from" -> from = hv; "to" -> to = hv; "subject" -> subject = hv }
            }
            val labels = j.optJSONArray("labelIds")
            val spam = (0 until (labels?.length() ?: 0)).any { labels!!.optString(it) == "SPAM" }
            result += RemoteMessage(j.optString("id"), j.optString("threadId"), from, to, subject,
                extractBody(payload), j.optString("internalDate").toLongOrNull() ?: 0L, spam, headerMap,
                parseSenderDisplayName(from))
        }
        onDiagnostic?.invoke("Gmail batch: запрошено писем=${messageIds.size}, получено=${result.size}")
        if (result.size != messageIds.size) throw IllegalStateException("Gmail batch вернул неполный набор писем")
        return result
    }


    /** Returns only message IDs added after the durable Gmail history cursor. */
    fun history(token: String, startHistoryId: String, maxResults: Int = 100): HistoryChanges {
        require(startHistoryId.isNotBlank()) { "Gmail history cursor is empty" }
        var pageToken: String? = null
        var latestHistoryId = startHistoryId
        val ids = linkedSetOf<String>()
        do {
            val query = buildString {
                append("startHistoryId=").append(URLEncoder.encode(startHistoryId, "UTF-8"))
                append("&historyTypes=messageAdded&maxResults=").append(maxResults.coerceIn(1, 500))
                if (!pageToken.isNullOrBlank()) append("&pageToken=").append(URLEncoder.encode(pageToken, "UTF-8"))
            }
            val root = JSONObject(request(GmailConfig.GMAIL_API + "/history?" + query, token))
            root.optString("historyId").takeIf { it.isNotBlank() }?.let { latestHistoryId = it }
            val history = root.optJSONArray("history")
            if (history != null) for (i in 0 until history.length()) {
                val entry = history.optJSONObject(i) ?: continue
                val added = entry.optJSONArray("messagesAdded") ?: continue
                for (j in 0 until added.length()) {
                    val message = added.optJSONObject(j)?.optJSONObject("message") ?: continue
                    message.optString("id").takeIf { it.isNotBlank() }?.let(ids::add)
                }
            }
            pageToken = root.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)
        onDiagnostic?.invoke("Gmail History: новых ID=" + ids.size + "; historyId=" + latestHistoryId)
        return HistoryChanges(latestHistoryId, ids.toList())
    }

    /** Fetches only the supplied, already deduplicated IDs, in small batches. */
    fun getMessages(token: String, ids: List<String>, batchSize: Int = 10): List<RemoteMessage> {
        if (ids.isEmpty()) return emptyList()
        val result = mutableListOf<RemoteMessage>()
        ids.distinct().chunked(batchSize.coerceIn(1, 20)).forEach { batchIds ->
            val boundary = "mc_batch_" + UUID.randomUUID().toString().replace("-", "")
            val body = buildString {
                batchIds.forEachIndexed { index, id ->
                    append("--").append(boundary).append("\r\nContent-Type: application/http\r\n")
                    append("Content-ID: <mc-").append(index).append(">\r\n\r\n")
                    append("GET /gmail/v1/users/me/messages/").append(id).append("?format=full HTTP/1.1\r\n\r\n")
                }
                append("--").append(boundary).append("--\r\n")
            }
            val response = request("https://www.googleapis.com/batch/gmail/v1", token, "POST", body, "multipart/mixed; boundary=" + boundary)
            val responseBoundary = Regex("(?m)^--([^\r\n]+)").find(response.trimStart())?.groupValues?.get(1)?.removeSuffix("--")
                ?: throw IllegalStateException("Gmail batch response has no boundary")
            response.split("--" + responseBoundary).forEach { part ->
                val httpStart = part.indexOf("HTTP/1.")
                if (httpStart < 0) return@forEach
                val status = Regex("HTTP/1\\.[01]\\s+(\\d{3})").find(part.substring(httpStart))?.groupValues?.get(1)?.toIntOrNull()
                if (status != null && status !in 200..299) throw GmailApiException(status, part.substring(httpStart).take(300))
                val jsonStart = part.indexOf("{", httpStart)
                val jsonEnd = part.lastIndexOf("}")
                if (jsonStart < 0 || jsonEnd < jsonStart) return@forEach
                val j = JSONObject(part.substring(jsonStart, jsonEnd + 1))
                val payload = j.optJSONObject("payload")
                val headers = payload?.optJSONArray("headers")
                var from = ""; var to = ""; var subject = ""
                val headerMap = linkedMapOf<String, String>()
                if (headers != null) for (i in 0 until headers.length()) {
                    val h = headers.getJSONObject(i); val name = h.optString("name"); val value = h.optString("value")
                    headerMap[name.lowercase()] = value
                    when (name.lowercase()) { "from" -> from = value; "to" -> to = value; "subject" -> subject = value }
                }
                val labels = j.optJSONArray("labelIds")
                val spam = (0 until (labels?.length() ?: 0)).any { labels!!.optString(it) == "SPAM" }
                result += RemoteMessage(j.optString("id"), j.optString("threadId"), from, to, subject,
                    extractBody(payload), j.optString("internalDate").toLongOrNull() ?: 0L, spam, headerMap, parseSenderDisplayName(from))
            }
        }
        onDiagnostic?.invoke("Gmail batch: запрошено новых писем=" + ids.distinct().size + ", получено=" + result.size)
        if (result.size != ids.distinct().size) throw IllegalStateException("Gmail batch вернул неполный набор писем")
        return result
    }

    /** Creates the user's MC label once and returns its mailbox-local ID. */
    fun ensureMcLabel(token: String): String {
        val root = JSONObject(request(GmailConfig.GMAIL_API + "/labels", token))
        val labels = root.optJSONArray("labels")
        if (labels != null) for (i in 0 until labels.length()) {
            val label = labels.optJSONObject(i) ?: continue
            if (label.optString("name").equals("MC", true)) return label.optString("id")
        }
        val created = JSONObject(request(GmailConfig.GMAIL_API + "/labels", token, "POST",
            JSONObject().put("name", "MC").put("labelListVisibility", "labelShow").put("messageListVisibility", "show").toString()))
        val id = created.optString("id")
        require(id.isNotBlank()) { "Gmail не вернул ID метки MC" }
        onDiagnostic?.invoke("Создана метка Gmail MC")
        return id
    }

    /** Marks a verified and locally persisted incoming message as MC and archives it. */
    fun labelIncoming(token: String, messageId: String, labelId: String): String {
        fun modify(id: String) {
            val body = JSONObject()
                .put("addLabelIds", org.json.JSONArray().put(id))
                .put("removeLabelIds", org.json.JSONArray().put("INBOX").put("SPAM"))
                .toString()
            request(GmailConfig.GMAIL_API + "/messages/" + messageId + "/modify", token, "POST", body)
        }
        return try {
            modify(labelId)
            labelId
        } catch (e: GmailApiException) {
            if (e.statusCode != 400 || !e.responseBody.contains("label", true)) throw e
            val replacement = ensureMcLabel(token)
            modify(replacement)
            onDiagnostic?.invoke("Метка MC была удалена в Gmail и создана повторно")
            replacement
        }
    }

    /** Adds the MC label to a sent message without removing SENT. */
    fun labelSent(token: String, messageId: String, labelId: String): String {
        fun modify(id: String) {
            val body = JSONObject().put("addLabelIds", org.json.JSONArray().put(id)).toString()
            request(GmailConfig.GMAIL_API + "/messages/" + messageId + "/modify", token, "POST", body)
        }
        return try {
            modify(labelId)
            labelId
        } catch (e: GmailApiException) {
            if (e.statusCode != 400 || !e.responseBody.contains("label", true)) throw e
            val replacement = ensureMcLabel(token)
            modify(replacement)
            onDiagnostic?.invoke("Метка MC была удалена в Gmail и создана повторно")
            replacement
        }
    }


    private fun parseSenderDisplayName(from: String): String {
        val normalized = from.replace(Regex("\\s+"), " ").trim()
        val angleStart = normalized.lastIndexOf('<')
        val angleEnd = normalized.lastIndexOf('>')
        val raw = when {
            angleStart > 0 && angleEnd > angleStart -> normalized.substring(0, angleStart).trim()
            angleStart < 0 && angleEnd < 0 -> normalized.substringBeforeLast(" (", normalized).takeIf { it != normalized }?.trim().orEmpty()
            else -> ""
        }.trim().trim('"', '\'')
        if (raw.isBlank()) return ""
        val decoded = decodeEncodedWords(raw).replace(Regex("\\s+"), " ").trim().trim('"', '\'')
        return decoded.takeIf { it.isNotBlank() && !it.contains('@') }.orEmpty()
    }

    private fun decodeEncodedWords(value: String): String {
        val pattern = Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]+)\\?=")
        return pattern.replace(value) { match ->
            runCatching {
                val charset = java.nio.charset.Charset.forName(match.groupValues[1])
                val payload = match.groupValues[3]
                val bytes = if (match.groupValues[2].equals("B", true)) {
                    Base64.decode(payload, Base64.DEFAULT)
                } else {
                    val output = java.io.ByteArrayOutputStream()
                    var i = 0
                    while (i < payload.length) {
                        when {
                            payload[i] == '_' -> { output.write(32); i++ }
                            payload[i] == '=' && i + 2 < payload.length -> {
                                output.write(payload.substring(i + 1, i + 3).toInt(16)); i += 3
                            }
                            else -> { output.write(payload[i].toString().toByteArray(StandardCharsets.ISO_8859_1)); i++ }
                        }
                    }
                    output.toByteArray()
                }
                String(bytes, charset)
            }.getOrDefault(match.value)
        }
    }

    private fun extractBody(payload: JSONObject?): String {
        if (payload == null) return ""

        val plainParts = mutableListOf<String>()

        fun collect(part: JSONObject) {
            if (part.optString("mimeType").equals("text/plain", true)) {
                val encoded = part.optJSONObject("body")?.optString("data").orEmpty()
                if (encoded.isNotBlank()) {
                    val decoded = decode(encoded)
                    if (decoded.isNotBlank()) plainParts.add(decoded)
                }
            }

            val parts = part.optJSONArray("parts") ?: return
            for (i in 0 until parts.length()) {
                val child = parts.optJSONObject(i) ?: continue
                collect(child)
            }
        }

        collect(payload)

        return plainParts.firstOrNull {
            it.contains("-----BEGIN MC DATA-----")
        } ?: plainParts.firstOrNull().orEmpty()
    }

    private fun decode(data: String): String = try {
        String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP), StandardCharsets.UTF_8)
    } catch (_: Exception) { "" }

    fun moveOutOfSpam(token: String, id: String) {
        val body = JSONObject().put("removeLabelIds", org.json.JSONArray().put("SPAM")).put("addLabelIds", org.json.JSONArray().put("INBOX")).toString()
        request("${GmailConfig.GMAIL_API}/messages/$id/modify", token, "POST", body)
    }
}
