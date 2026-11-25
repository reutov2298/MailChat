package ru.mailchat.app

import org.json.JSONObject

/**
 * Parses per-request errors returned inside a Gmail multipart batch response.
 * Only stable Google error reason codes are used to decide whether a 403 is retryable.
 */
internal data class McGmailBatchError(
    val status: Int,
    val reason: String?,
    val message: String?
) {
    fun isRetryable(): Boolean =
        status == 429 || status in setOf(500, 502, 503, 504) ||
            (status == 403 && reason != null && reason in RETRYABLE_403_REASONS)

    fun diagnostic(): String {
        val safeReason = reason?.takeIf { it.isNotBlank() } ?: "unknown"
        val safeMessage = message.orEmpty()
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(240)
        return "reason=$safeReason; message=${safeMessage.ifBlank { "not provided" }}"
    }

    companion object {
        private val RETRYABLE_403_REASONS = setOf(
            "rateLimitExceeded",
            "userRateLimitExceeded"
        )

        fun fromPart(status: Int, part: String): McGmailBatchError {
            val jsonStart = part.indexOf('{')
            val jsonEnd = part.lastIndexOf('}')
            if (jsonStart < 0 || jsonEnd < jsonStart) {
                return McGmailBatchError(status, null, null)
            }
            return runCatching {
                val root = JSONObject(part.substring(jsonStart, jsonEnd + 1))
                val error = root.optJSONObject("error") ?: root
                val errors = error.optJSONArray("errors")
                var reason: String? = null
                if (errors != null) {
                    for (index in 0 until errors.length()) {
                        val item = errors.optJSONObject(index) ?: continue
                        val candidate = item.optString("reason").takeIf { it.isNotBlank() }
                        if (candidate != null) {
                            reason = candidate
                            break
                        }
                    }
                }
                McGmailBatchError(
                    status = status,
                    reason = reason,
                    message = error.optString("message").takeIf { it.isNotBlank() }
                )
            }.getOrElse { McGmailBatchError(status, null, null) }
        }

        fun retryDelayMsException(message: String?, attempt: Int): Long {
            val text = message.orEmpty()
            val status = Regex("Gmail batch(?: migration)?: письмо \\d+ вернуло HTTP (\\d{3})")
                .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return 5_000L
            val reason = Regex("reason=([A-Za-z0-9_.-]+)").find(text)?.groupValues?.get(1)
            val detail = Regex("message=(.*)").find(text)?.groupValues?.get(1)
            return McGmailRetryPolicy.delayMs(status, reason, detail, attempt)
        }

        fun isRetryableException(message: String?): Boolean {
            val text = message.orEmpty()
            val status = Regex("Gmail batch: письмо \\d+ вернуло HTTP (\\d{3})")
                .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return false
            val reason = Regex("reason=([A-Za-z0-9_.-]+)")
                .find(text)?.groupValues?.get(1)
            return McGmailBatchError(status, reason, null).isRetryable()
        }
    }
}
