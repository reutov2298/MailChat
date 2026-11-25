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
    data class ContactProfile(val name: String?, val photoUrl: String?)
    data class RemoteMessage(val id: String, val threadId: String?, val from: String, val to: String, val subject: String, val body: String, val internalDate: Long, val spam: Boolean, val headers: Map<String, String>, val senderName: String = "")

    private fun request(url: String, token: String, method: String = "GET", body: String? = null, contentType: String = "application/json; charset=utf-8"): String =
        synchronized(requestMonitor) {
            val waitMs = MIN_REQUEST_GAP_MS - (System.currentTimeMillis() - lastRequestStartedAt)
            if (waitMs > 0) Thread.sleep(waitMs)
            lastRequestStartedAt = System.currentTimeMillis()

            // The callback/sound fires only when this request is actually about to start.
            onRequestStarted?.invoke()
            val endpoint = url.substringAfter("/gmail/v1", url).substringBefore("?")
            onServerRequest?.invoke("$method $endpoint")
            val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
            when (method) {
                "POST" -> builder.post((body ?: "").toRequestBody(contentType.toMediaType()))
                "PUT" -> builder.put((body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
                else -> builder.get()
            }
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IllegalStateException("Gmail API ${response.code}: ${text.take(400)}")
                text
            }
        }

    fun profile(token: String): Profile {
        val j = JSONObject(request("${GmailConfig.GMAIL_API}/profile", token))
        return Profile(j.optString("emailAddress"), j.optString("historyId"))
    }

    fun contactProfile(token: String, email: String): ContactProfile? {
        val query = URLEncoder.encode(email.trim(), "UTF-8")
        val url = "https://people.googleapis.com/v1/people:searchContacts?query=$query&pageSize=10&readMask=names,emailAddresses,photos"
        val root = JSONObject(request(url, token))
        val results = root.optJSONArray("results") ?: return null
        for (i in 0 until results.length()) {
            val person = results.optJSONObject(i)?.optJSONObject("person") ?: continue
            val emails = person.optJSONArray("emailAddresses") ?: continue
            val exactMatch = (0 until emails.length()).any { emails.optJSONObject(it)?.optString("value").equals(email, true) }
            if (!exactMatch) continue
            val name = person.optJSONArray("names")?.optJSONObject(0)?.optString("displayName")?.trim()?.takeIf { it.isNotBlank() }
            val photo = person.optJSONArray("photos")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
            return ContactProfile(name, photo)
        }
        return null
    }

    fun send(
        token: String, from: String, to: String, text: String, chatId: String, publicKey: String,
        messageId: String = UUID.randomUUID().toString(), sequence: Long = 1L,
        messageVersion: Long = 1L, messageType: String = "text",
        messageTimestamp: Long? = null, sign: (ByteArray) -> String
    ): String {
        val id = messageId
        val ts = messageTimestamp ?: System.currentTimeMillis() / 1000
        val normalizedText = McProtocol.normalizeText(text)
        val senderId = "android-${android.os.Build.MODEL}"
        val version = "1.0.0-prototype"
        val sequenceValue = sequence.toString()
        val messageVersionValue = messageVersion.toString()
        val protocol = if (messageType == "text") "1" else "2"
        val keyId = from
        val canonicalInput = McProtocol.CanonicalInput(
            id, chatId, senderId, from, to, version, messageType, ts.toString(),
            sequenceValue, keyId, publicKey, normalizedText, messageVersionValue,
            protocol == "2", protocol
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
        if (protocol == "2") technical.add(10, "X-MC-Message-Version: $messageVersionValue")
        val mime = buildString {
            append("From: $from\r\nTo: $to\r\nSubject: [MC] Message\r\n")
            append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n")
            technical.forEach { append(it).append("\r\n") }
            append("\r\nНиже приведены технические данные MC, продублированные из заголовков электронного письма:\r\n")
            append("-----BEGIN MC DATA-----\r\n")
            technical.forEach { append(it).append("\r\n") }
            append("-----END MC DATA-----\r\n\r\n")
            append(normalizedText.replace("\n", "\r\n"))
        }
        val encoded = Base64.encodeToString(mime.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val payload = JSONObject().put("raw", encoded).toString()
        val result = JSONObject(request("${GmailConfig.GMAIL_API}/messages/send", token, "POST", payload))
        return result.optString("id", id)
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
