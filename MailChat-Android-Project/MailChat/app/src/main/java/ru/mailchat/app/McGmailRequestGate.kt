package ru.mailchat.app

/**
 * One process-wide gate for all Gmail API traffic. The lock covers the complete
 * HTTP exchange so sync, migration, stats and delete jobs cannot overlap requests.
 */
internal object McGmailRequestGate {
    private val monitor = Any()
    private var lastStartedAt = 0L
    private var blockedUntil = 0L

    fun deferFor(delayMs: Long) = synchronized(monitor) {
        blockedUntil = maxOf(blockedUntil, System.currentTimeMillis() + delayMs.coerceAtLeast(0L))
    }

    fun <T> execute(minGapMs: Long, action: () -> T): T = synchronized(monitor) {
        val now = System.currentTimeMillis()
        val gapWait = minGapMs - (now - lastStartedAt)
        val cooldownWait = blockedUntil - now
        val waitMs = maxOf(gapWait, cooldownWait)
        if (waitMs > 0L) Thread.sleep(waitMs)
        lastStartedAt = System.currentTimeMillis()
        action()
    }
}

internal object McGmailRetryPolicy {
    fun isRetryable(status: Int, reason: String?, message: String?): Boolean {
        if (status == 429 || status in setOf(500, 502, 503, 504)) return true
        if (status != 403) return false
        return reason == "rateLimitExceeded" || reason == "userRateLimitExceeded" ||
            (reason == "backendError" && !message.isNullOrBlank())
    }

    fun delayMs(
        status: Int,
        reason: String?,
        message: String?,
        attempt: Int,
        retryAfterSeconds: Long? = null
    ): Long {
        val shift = attempt.coerceIn(0, 5)
        val detail = message.orEmpty()
        val base = when {
            detail.contains("Quota exceeded", true) ||
                detail.contains("Units per minute per user", true) -> minOf(120_000L, 60_000L shl shift)
            detail.contains("Too many concurrent requests", true) -> minOf(60_000L, 5_000L shl shift)
            status == 429 -> minOf(60_000L, 5_000L shl shift)
            else -> minOf(60_000L, 2_000L shl shift)
        }
        val serverDelay = (retryAfterSeconds ?: 0L).coerceAtLeast(0L).coerceAtMost(120L) * 1_000L
        return maxOf(base, serverDelay)
    }
}
