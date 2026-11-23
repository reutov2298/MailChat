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
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    data class Profile(val email: String, val historyId: String)
    data class RemoteMessage(val id: String, val threadId: String?, val from: String, val to: String, val subject: String, val body: String, val internalDate: Long, val spam: Boolean, val headers: Map<String, String>)

    private fun request(url: String, token: String, method: String = "GET", body: String? = null): String {
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
        when (method) {
            "POST" -> builder.post((body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
            "PUT" -> builder.put((body ?: "").toRequestBody("application/json; charset=utf-8".toMediaType()))
            else -> builder.get()
        }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Gmail API ${response.code}: ${text.take(400)}")
            return text
        }
    }

    fun profile(token: String): Profile {
        val j = JSONObject(request("${GmailConfig.GMAIL_API}/profile", token))
        return Profile(j.optString("emailAddress"), j.optString("historyId"))
    }

    fun send(token: String, from: String, to: String, text: String, chatId: String, publicKey: String, sign: (ByteArray) -> String): String {
        val id = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis() / 1000
        val normalizedText = text.replace("\r\n", "\n").replace("\r", "\n").trimEnd('\n')
        val senderId = "android-${android.os.Build.MODEL}"
        val version = "1.0.0-prototype"
        val sequence = "1"
        val keyId = from
        val canonical = listOf("1", id, chatId, senderId, from, to, version, "text", ts.toString(), sequence, keyId, publicKey, normalizedText).joinToString("\n")
        val signature = sign(canonical.toByteArray(StandardCharsets.UTF_8))
        val technical = listOf(
            "X-MC-Protocol: 1", "X-MC-Message-ID: $id", "X-MC-Chat-ID: $chatId",
            "X-MC-Sender-ID: $senderId", "X-MC-Sender: $from", "X-MC-Recipient: $to",
            "X-MC-Version: $version", "X-MC-Message-Type: text", "X-MC-Timestamp: $ts",
            "X-MC-Sequence: $sequence", "X-MC-Key-ID: $keyId", "X-MC-Public-Key: $publicKey",
            "X-MC-Signature-Algorithm: Ed25519", "X-MC-Signature: $signature"
        )
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
        val result = mutableListOf<RemoteMessage>()
        for (i in 0 until ids.length()) {
            val id = ids.getJSONObject(i).optString("id")
            val j = JSONObject(request("${GmailConfig.GMAIL_API}/messages/$id?format=full", token))
            val payload = j.optJSONObject("payload")
            val headers = payload?.optJSONArray("headers")
            var from = ""; var to = ""; var subject = ""
            val headerMap = linkedMapOf<String, String>()
            if (headers != null) for (h in 0 until headers.length()) {
                val header = headers.getJSONObject(h)
                val hn = header.optString("name"); val hv = header.optString("value")
                headerMap[hn.lowercase()] = hv
                when (hn.lowercase()) {
                    "from" -> from = hv
                    "to" -> to = hv
                    "subject" -> subject = hv
                }
            }
            val body = extractBody(payload)
            val labels = j.optJSONArray("labelIds")
            val spam = (0 until (labels?.length() ?: 0)).any { labels!!.optString(it) == "SPAM" }
            result += RemoteMessage(id, j.optString("threadId"), from, to, subject, body, j.optString("internalDate").toLongOrNull() ?: 0L, spam, headerMap)
        }
        return result
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
