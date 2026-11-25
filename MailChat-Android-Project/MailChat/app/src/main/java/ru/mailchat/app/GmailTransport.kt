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
    var onRequestStarted: (() -> Unit)? = null
    var onServerRequest: ((String) -> Unit)? = null
    var onDiagnostic: ((String) -> Unit)? = null
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    data class Profile(val email: String, val historyId: String)
    data class ContactProfile(val name: String?, val photoUrl: String?)
    data class OwnProfile(val name: String?, val photoUrl: String?)
    data class RemoteMessage(val id: String, val threadId: String?, val from: String, val to: String, val subject: String, val body: String, val internalDate: Long, val spam: Boolean, val headers: Map<String, String>, val senderName: String = "")

    private fun request(url: String, token: String, method: String = "GET", body: String? = null, contentType: String = "application/json; charset=utf-8"): String =
        McGmailRequestGate.execute(500L) {

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
                val isPeopleApi = url.startsWith("https://people.googleapis.com/")
                if (isPeopleApi) {
                    onDiagnostic?.invoke("People API HTTP: code=${response.code}, successful=${response.isSuccessful}, responseBytes=${text.toByteArray(StandardCharsets.UTF_8).size}")
                    if (!response.isSuccessful) onDiagnostic?.invoke("People API error response: ${text.take(800)}")
                }
                if (!response.isSuccessful) {
                    val apiName = if (isPeopleApi) "People API" else "Gmail API"
                    throw IllegalStateException("$apiName ${response.code}: ${text.take(800)}")
                }
                text
            }
        }

    fun profile(token: String): Profile {
        val j = JSONObject(request("${GmailConfig.GMAIL_API}/profile", token))
        return Profile(j.optString("emailAddress"), j.optString("historyId"))
    }

    fun ownProfile(token: String): OwnProfile {
        val root = JSONObject(request("https://openidconnect.googleapis.com/v1/userinfo", token))
        val name = root.optString("name").trim().takeIf { it.isNotBlank() }
        val photo = root.optString("picture").trim().takeIf { it.startsWith("https://") }
        onDiagnostic?.invoke("Google profile: namePresent=${!name.isNullOrBlank()}, avatarPresent=${!photo.isNullOrBlank()}")
        return OwnProfile(name, photo)
    }

    fun contactProfile(token: String, email: String): ContactProfile? {
        onDiagnostic?.invoke("People API: поиск контакта начат; emailProvided=${email.isNotBlank()}")
        val query = URLEncoder.encode(email.trim(), "UTF-8")
        val url = "https://people.googleapis.com/v1/people:searchContacts?query=$query&pageSize=10&readMask=names,emailAddresses,photos"
        val root = JSONObject(request(url, token))
        val results = root.optJSONArray("results")
        val resultCount = results?.length() ?: 0
        onDiagnostic?.invoke("People API: resultsCount=$resultCount")
        if (results == null || resultCount == 0) {
            onDiagnostic?.invoke("People API: контакты не найдены или поле results отсутствует")
            return null
        }
        for (i in 0 until results.length()) {
            val person = results.optJSONObject(i)?.optJSONObject("person")
            if (person == null) {
                onDiagnostic?.invoke("People API: result[$i] не содержит person")
                continue
            }
            val emails = person.optJSONArray("emailAddresses")
            val emailCount = emails?.length() ?: 0
            val exactMatch = emails != null && (0 until emails.length()).any { emails.optJSONObject(it)?.optString("value").equals(email, true) }
            onDiagnostic?.invoke("People API: result[$i] emailCount=$emailCount exactEmailMatch=$exactMatch")
            if (!exactMatch) continue
            val name = person.optJSONArray("names")?.optJSONObject(0)?.optString("displayName")?.trim()?.takeIf { it.isNotBlank() }
            val photo = person.optJSONArray("photos")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
            onDiagnostic?.invoke("People API: exact match; namePresent=${!name.isNullOrBlank()}, photoPresent=${!photo.isNullOrBlank()}")
            return ContactProfile(name, photo)
        }
        onDiagnostic?.invoke("People API: точного совпадения email среди результатов нет")
        return null
    }

    fun send(token: String, from: String, to: String, text: String, chatId: String, publicKey: String, senderName: String?, senderAvatarUrl: String?, sign: (ByteArray) -> String): String {
        val id = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis() / 1000
        val normalizedText = McProtocol.normalizeText(text)
        val senderId = "android-${android.os.Build.MODEL}"
        val version = "1.1.1"
        val safeName = senderName.orEmpty().replace(Regex("[\\r\\n]+"), " ").trim().take(200)
        val safeAvatar = senderAvatarUrl.orEmpty().trim().takeIf { it.startsWith("https://") }?.take(700).orEmpty()
        val nameEncoded = Base64.encodeToString(safeName.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val avatarEncoded = Base64.encodeToString(safeAvatar.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val sequence = "1"
        val keyId = from
        val canonicalInput = McProtocol.CanonicalInput(
            id, chatId, senderId, from, to, version, "text", ts.toString(),
            sequence, keyId, publicKey, normalizedText, safeName, safeAvatar
        )
        val canonical = McProtocol.canonical(canonicalInput)
        val signature = sign(canonical.toByteArray(StandardCharsets.UTF_8))
        McProtocol.signatureDiagnostics("sender", canonicalInput, signature).forEach { onDiagnostic?.invoke(it) }
        val technical = listOf(
            "X-MC-Protocol: 2", "X-MC-Message-ID: $id", "X-MC-Chat-ID: $chatId",
            "X-MC-Sender-ID: $senderId", "X-MC-Sender: $from", "X-MC-Recipient: $to",
            "X-MC-Version: $version", "X-MC-Message-Type: text", "X-MC-Timestamp: $ts",
            "X-MC-Sequence: $sequence", "X-MC-Key-ID: $keyId", "X-MC-Public-Key: $publicKey",
            "X-MC-Sender-Name: $nameEncoded", "X-MC-Sender-Avatar: $avatarEncoded",
            "X-MC-Signature-Algorithm: Ed25519", "X-MC-Signature: $signature"
        )
        val mime = buildString {
            val displayName = if (safeName.isBlank()) from else "=?UTF-8?B?${Base64.encodeToString(safeName.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)}?= <$from>"
            append("From: $displayName\r\nTo: $to\r\nSubject: [MC] Message\r\n")
            append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n")
            technical.forEach { append(it).append("\r\n") }
            append("\r\n")
            append(normalizedText.replace("\n", "\r\n"))
            append("\r\n")
            append("-----BEGIN MC DATA-----\r\n")
            technical.forEach { append(it).append("\r\n") }
            append("-----END MC DATA-----\r\n")
        }
        val encoded = Base64.encodeToString(mime.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val payload = JSONObject().put("raw", encoded).toString()
        val result = JSONObject(request("${GmailConfig.GMAIL_API}/messages/send", token, "POST", payload))
        return result.optString("id", id)
    }

    fun list(token: String, query: String, max: Int = 50): List<RemoteMessage> {
        var attempt = 0
        while (true) {
            try {
                return listOnce(token, query, max)
            } catch (error: IllegalStateException) {
                val status = Regex("Gmail batch: письмо \\d+ вернуло HTTP (\\d{3})")
                    .find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                if (!McGmailBatchError.isRetryableException(error.message) || attempt >= 3) throw error
                val delayMs = McGmailBatchError.retryDelayMsException(error.message, attempt)
                onDiagnostic?.invoke("Gmail batch: временная ошибка HTTP ${status ?: "?"}; повтор после паузы ${delayMs / 1000} сек.")
                McGmailRequestGate.deferFor(delayMs)
                Thread.sleep(delayMs)
                attempt++
            }
        }
        throw IllegalStateException("Gmail batch retry loop ended unexpectedly")
    }

    private fun listOnce(token: String, query: String, max: Int): List<RemoteMessage> {
        val q = URLEncoder.encode(query, "UTF-8")
        val root = JSONObject(request("${GmailConfig.GMAIL_API}/messages?maxResults=$max&includeSpamTrash=true&q=$q", token))
        val ids = root.optJSONArray("messages") ?: return emptyList()
        val messageIds = (0 until ids.length()).mapNotNull { ids.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
        if (messageIds.isEmpty()) return emptyList()

        // First read headers and labels only. Full bodies are fetched only for probable MC messages.
        val metadata = linkedMapOf<Int, JSONObject>()
        messageIds.indices.chunked(10).forEach { indexes ->
            val chunk = indexes.map { messageIds[it] }
            batchGetMessages(token, chunk, "metadata", indexes.first()).forEach { (localIndex, item) -> metadata[indexes[localIndex]] = item }
        }

        val candidates = mutableSetOf<Int>()
        val result = arrayOfNulls<RemoteMessage>(messageIds.size)
        metadata.forEach { (index, item) ->
            val payload = item.optJSONObject("payload")
            val headers = readHeaders(payload)
            val probableMc = headers.keys.any { it.startsWith("x-mc-") } ||
                headers["subject"].orEmpty().startsWith("[MC]", true)
            if (probableMc) candidates += index
            result[index] = remoteMessage(item, messageIds[index], headers, body = "")
        }

        candidates.sorted().chunked(10).forEach { indexes ->
            val chunk = indexes.map { messageIds[it] }
            val full = batchGetMessages(token, chunk, "full", indexes.first())
            indexes.forEachIndexed { offset, index ->
                val item = full[offset] ?: throw IllegalStateException("Gmail batch не вернул полное письмо с индексом $index")
                val payload = item.optJSONObject("payload")
                val headers = readHeaders(payload)
                result[index] = remoteMessage(item, messageIds[index], headers, extractBody(payload))
            }
        }

        onDiagnostic?.invoke("Gmail batch: проверено писем=${messageIds.size}, полное содержимое запрошено=${candidates.size}, размер пакета=10")
        return result.mapIndexed { index, message ->
            message ?: throw IllegalStateException("Gmail metadata не вернул письмо с индексом $index")
        }
    }

    private fun batchGetMessages(
        token: String,
        ids: List<String>,
        format: String,
        indexOffset: Int
    ): Map<Int, JSONObject> {
        if (ids.isEmpty()) return emptyMap()
        val boundary = "mc_batch_${UUID.randomUUID().toString().replace("-", "")}"
        val body = buildString {
            ids.forEachIndexed { index, id ->
                append("--").append(boundary).append("\r\nContent-Type: application/http\r\n")
                append("Content-ID: <mc-").append(index).append(">\r\n\r\n")
                append("GET /gmail/v1/users/me/messages/").append(id)
                    .append("?format=").append(format).append(" HTTP/1.1\r\n\r\n")
            }
            append("--").append(boundary).append("--\r\n")
        }
        val response = request("https://www.googleapis.com/batch/gmail/v1", token, "POST", body,
            "multipart/mixed; boundary=$boundary")
        val responseBoundary = Regex("(?m)^--([^\\r\\n]+)").find(response.trimStart())?.groupValues?.get(1)?.removeSuffix("--")
            ?: throw IllegalStateException("Gmail batch response has no boundary")
        val result = linkedMapOf<Int, JSONObject>()
        var fallbackIndex = 0
        response.split("--$responseBoundary").forEach { part ->
            val httpStart = part.indexOf("HTTP/1.")
            if (httpStart < 0) return@forEach
            val localIndex = Regex("(?i)Content-ID:\\s*<[^>]*mc-(\\d+)>")
                .find(part)?.groupValues?.get(1)?.toIntOrNull() ?: fallbackIndex
            fallbackIndex++
            val status = Regex("HTTP/1\\.[01]\\s+(\\d{3})").find(part)?.groupValues?.get(1)?.toIntOrNull()
                ?: throw IllegalStateException("Не удалось определить HTTP-статус письма в Gmail batch")
            if (status !in 200..299) {
                val failure = McGmailBatchError.fromPart(status, part)
                val detail = failure.diagnostic()
                onDiagnostic?.invoke("Gmail batch: письмо ${indexOffset + localIndex} HTTP $status; $detail")
                throw IllegalStateException("Gmail batch: письмо ${indexOffset + localIndex} вернуло HTTP $status ($detail)")
            }
            val jsonStart = part.indexOf("{", httpStart)
            val jsonEnd = part.lastIndexOf("}")
            if (jsonStart < 0 || jsonEnd < jsonStart) {
                throw IllegalStateException("Gmail batch: пустой ответ для письма ${indexOffset + localIndex}")
            }
            val item = JSONObject(part.substring(jsonStart, jsonEnd + 1))
            val requestedId = ids.getOrNull(localIndex)
                ?: throw IllegalStateException("Не удалось сопоставить ответ Gmail с запрошенным письмом")
            val returnedId = item.optString("id")
            if (returnedId.isNotBlank() && returnedId != requestedId) {
                throw IllegalStateException("Gmail batch: ID ответа не совпадает с запрошенным письмом")
            }
            result[localIndex] = item
        }
        if (result.size != ids.size) throw IllegalStateException("Gmail batch вернул неполный набор писем")
        return result
    }

    private fun readHeaders(payload: JSONObject?): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        val items = payload?.optJSONArray("headers") ?: return headers
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            headers[item.optString("name").lowercase()] = item.optString("value")
        }
        return headers
    }

    private fun remoteMessage(
        item: JSONObject,
        requestedId: String,
        headers: Map<String, String>,
        body: String
    ): RemoteMessage {
        val labels = item.optJSONArray("labelIds")
        val spam = (0 until (labels?.length() ?: 0)).any { labels!!.optString(it) == "SPAM" }
        return RemoteMessage(
            requestedId, item.optString("threadId"), headers["from"].orEmpty(),
            headers["to"].orEmpty(), headers["subject"].orEmpty(), body,
            item.optString("internalDate").toLongOrNull() ?: 0L, spam, headers,
            parseSenderDisplayName(headers["from"].orEmpty())
        )
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
