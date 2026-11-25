package ru.mailchat.app

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

class McGmailAdmin {
    companion object {
        private val requestMonitor = Any()
        private var lastRequestStartedAt = 0L
        private const val MIN_REQUEST_GAP_MS = 250L
    }
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()

    data class Page(val ids: List<String>, val nextPageToken: String?, val estimate: Int)
    data class LabelStat(val id: String, val name: String, val type: String, val total: Int, val unread: Int, val error: String? = null)
    data class Candidate(val message: GmailTransport.RemoteMessage, val labels: Set<String>)

    private fun call(url: String, token: String, method: String = "GET", body: String? = null,
                     contentType: String = "application/json; charset=utf-8"): String = synchronized(requestMonitor) {
        val waitMs = MIN_REQUEST_GAP_MS - (System.currentTimeMillis() - lastRequestStartedAt)
        if (waitMs > 0) Thread.sleep(waitMs)
        lastRequestStartedAt = System.currentTimeMillis()
        val builder = Request.Builder().url(url).header("Authorization", "Bearer " + token)
        when (method) {
            "POST" -> builder.post((body ?: "").toRequestBody(contentType.toMediaType()))
            "DELETE" -> builder.delete()
            else -> builder.get()
        }
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Gmail API " + response.code + ": " + text.take(700))
            text
        }
    }

    fun ensureMcLabel(token: String): String {
        val labels = JSONObject(call(GmailConfig.GMAIL_API + "/labels", token)).optJSONArray("labels")
        if (labels != null) for (i in 0 until labels.length()) {
            val label = labels.optJSONObject(i) ?: continue
            if (label.optString("name") == "MC") return label.optString("id")
        }
        val body = JSONObject().put("name", "MC").put("labelListVisibility", "labelShow")
            .put("messageListVisibility", "show").toString()
        val created = JSONObject(call(GmailConfig.GMAIL_API + "/labels", token, "POST", body))
        return created.optString("id").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Gmail не вернул ID метки MC")
    }

    fun modifyMessages(token: String, ids: List<String>, add: List<String> = emptyList(), remove: List<String> = emptyList()) {
        ids.distinct().chunked(1000).forEach { chunk ->
            val body = JSONObject().put("ids", JSONArray(chunk))
                .put("addLabelIds", JSONArray(add.distinct()))
                .put("removeLabelIds", JSONArray(remove.distinct())).toString()
            call(GmailConfig.GMAIL_API + "/messages/batchModify", token, "POST", body)
        }
    }

    fun listIdsPage(token: String, query: String? = null, labelId: String? = null,
                    pageToken: String? = null, max: Int = 500): Page {
        val params = mutableListOf("maxResults=" + max.coerceIn(1, 500), "includeSpamTrash=true")
        if (!query.isNullOrBlank()) params += "q=" + URLEncoder.encode(query, "UTF-8")
        if (!labelId.isNullOrBlank()) params += "labelIds=" + URLEncoder.encode(labelId, "UTF-8")
        if (!pageToken.isNullOrBlank()) params += "pageToken=" + URLEncoder.encode(pageToken, "UTF-8")
        val root = JSONObject(call(GmailConfig.GMAIL_API + "/messages?" + params.joinToString("&"), token))
        val items = root.optJSONArray("messages")
        val ids = if (items == null) emptyList() else (0 until items.length())
            .mapNotNull { items.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
        return Page(ids, root.optString("nextPageToken").takeIf { it.isNotBlank() }, root.optInt("resultSizeEstimate"))
    }

    fun mcMessageIds(token: String, labelId: String): List<String> {
        val result = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val page = listIdsPage(token, labelId = labelId, pageToken = pageToken)
            result += page.ids
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return result.distinct()
    }

    fun countUniqueMessages(token: String): Int {
        var total = 0
        var pageToken: String? = null
        do {
            val page = listIdsPage(token, pageToken = pageToken)
            total += page.ids.size
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return total
    }

    fun labelStats(token: String): List<LabelStat> {
        val root = JSONObject(call(GmailConfig.GMAIL_API + "/labels", token))
        val labels = root.optJSONArray("labels") ?: return emptyList()
        return (0 until labels.length()).mapNotNull { i ->
            val label = labels.optJSONObject(i) ?: return@mapNotNull null
            val id = label.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val name = label.optString("name")
            val type = label.optString("type")
            val detail = runCatching { JSONObject(call(GmailConfig.GMAIL_API + "/labels/" + id, token)) }
            if (detail.isSuccess) {
                val value = detail.getOrThrow()
                LabelStat(id, name, type, value.optInt("messagesTotal"), value.optInt("messagesUnread"))
            } else {
                LabelStat(id, name, type, 0, 0, detail.exceptionOrNull()?.message.orEmpty().take(160))
            }
        }.sortedWith(compareBy<LabelStat> { !it.type.equals("system", true) }.thenBy { it.name.lowercase() })
    }

    fun messageLabelIds(token: String, ids: List<String>): Map<String, Set<String>> {
        if (ids.isEmpty()) return emptyMap()
        val boundary = "mc_verify_" + UUID.randomUUID().toString().replace("-", "")
        val body = buildString {
            ids.forEachIndexed { index, id ->
                append("--").append(boundary).append("\\r\\nContent-Type: application/http\\r\\n")
                append("Content-ID: <check-").append(index).append(">\\r\\n\\r\\n")
                append("GET /gmail/v1/users/me/messages/").append(id).append("?format=metadata HTTP/1.1\\r\\n\\r\\n")
            }
            append("--").append(boundary).append("--\\r\\n")
        }
        val response = call("https://www.googleapis.com/batch/gmail/v1", token, "POST", body,
            "multipart/mixed; boundary=" + boundary)
        val responseBoundary = Regex("(?m)^--([^\\\\r\\\\n]+)").find(response.trimStart())?.groupValues?.get(1)?.removeSuffix("--")
            ?: throw IllegalStateException("Не найден разделитель ответа проверки меток")
        return response.split("--" + responseBoundary).mapNotNull { part ->
            val start = part.indexOf("HTTP/1.")
            if (start < 0) return@mapNotNull null
            val jsonStart = part.indexOf("{", start)
            val jsonEnd = part.lastIndexOf("}")
            if (jsonStart < 0 || jsonEnd < jsonStart) return@mapNotNull null
            val item = runCatching { JSONObject(part.substring(jsonStart, jsonEnd + 1)) }.getOrNull()
                ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val labelArray = item.optJSONArray("labelIds")
            val labels = (0 until (labelArray?.length() ?: 0)).map { labelArray!!.optString(it) }.toSet()
            id to labels
        }.toMap()
    }

    fun batchDelete(token: String, ids: List<String>) {
        ids.distinct().chunked(1000).forEach { chunk ->
            call(GmailConfig.GMAIL_API + "/messages/batchDelete", token, "POST",
                JSONObject().put("ids", JSONArray(chunk)).toString())
        }
    }

    fun candidatePage(token: String, pageToken: String? = null, max: Int = 50): Pair<List<Candidate>, Page> {
        val page = listIdsPage(token, query = "in:anywhere \"BEGIN MC DATA\"", pageToken = pageToken, max = max)
        if (page.ids.isEmpty()) return emptyList<Candidate>() to page
        val boundary = "mc_migrate_" + UUID.randomUUID().toString().replace("-", "")
        val body = buildString {
            page.ids.forEachIndexed { index, id ->
                append("--").append(boundary).append("\r\nContent-Type: application/http\r\n")
                append("Content-ID: <mc-").append(index).append(">\r\n\r\n")
                append("GET /gmail/v1/users/me/messages/").append(id).append("?format=full HTTP/1.1\r\n\r\n")
            }
            append("--").append(boundary).append("--\r\n")
        }
        val response = call("https://www.googleapis.com/batch/gmail/v1", token, "POST", body,
            "multipart/mixed; boundary=" + boundary)
        val responseBoundary = Regex("(?m)^--([^\\r\\n]+)").find(response.trimStart())?.groupValues?.get(1)?.removeSuffix("--")
            ?: throw IllegalStateException("Не найден разделитель ответа Gmail batch")
        val candidates = response.split("--" + responseBoundary).mapNotNull { part ->
            val start = part.indexOf("HTTP/1.")
            if (start < 0) return@mapNotNull null
            val jsonStart = part.indexOf("{", start)
            val jsonEnd = part.lastIndexOf("}")
            if (jsonStart < 0 || jsonEnd < jsonStart) return@mapNotNull null
            val j = runCatching { JSONObject(part.substring(jsonStart, jsonEnd + 1)) }.getOrNull()
                ?: return@mapNotNull null
            val payload = j.optJSONObject("payload")
            val headers = linkedMapOf<String, String>()
            val headerArray = payload?.optJSONArray("headers")
            if (headerArray != null) for (h in 0 until headerArray.length()) {
                val item = headerArray.optJSONObject(h) ?: continue
                headers[item.optString("name").lowercase()] = item.optString("value")
            }
            val labelArray = j.optJSONArray("labelIds")
            val labels = (0 until (labelArray?.length() ?: 0)).map { labelArray!!.optString(it) }.toSet()
            val from = headers["from"].orEmpty()
            val to = headers["to"].orEmpty()
            val subject = headers["subject"].orEmpty()
            val message = GmailTransport.RemoteMessage(
                j.optString("id"), j.optString("threadId"), from, to, subject,
                extractBody(payload), j.optString("internalDate").toLongOrNull() ?: 0L,
                "SPAM" in labels, headers, ""
            )
            Candidate(message, labels)
        }
        if (candidates.size != page.ids.size) throw IllegalStateException("Gmail batch вернул неполный набор писем для миграции")
        return candidates to page
    }

    private fun extractBody(payload: JSONObject?): String {
        if (payload == null) return ""
        val parts = mutableListOf<String>()
        fun walk(part: JSONObject) {
            if (part.optString("mimeType").equals("text/plain", true)) {
                val data = part.optJSONObject("body")?.optString("data").orEmpty()
                if (data.isNotBlank()) {
                    val decoded = runCatching {
                        String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
                    }.getOrDefault("")
                    if (decoded.isNotBlank()) parts += decoded
                }
            }
            val children = part.optJSONArray("parts") ?: return
            for (i in 0 until children.length()) children.optJSONObject(i)?.let(::walk)
        }
        walk(payload)
        return parts.firstOrNull { it.contains("-----BEGIN MC DATA-----") } ?: parts.firstOrNull().orEmpty()
    }
}
