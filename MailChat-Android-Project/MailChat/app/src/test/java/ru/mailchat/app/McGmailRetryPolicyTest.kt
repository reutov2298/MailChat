package ru.mailchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McGmailRetryPolicyTest {
    @Test fun quota403IsRetryableOnlyForRateLimitReasons() {
        assertTrue(McGmailRetryPolicy.isRetryable(403, "rateLimitExceeded", "Quota exceeded"))
        assertTrue(McGmailRetryPolicy.isRetryable(403, "userRateLimitExceeded", "rate limited"))
        assertFalse(McGmailRetryPolicy.isRetryable(403, "forbidden", "permission denied"))
    }

    @Test fun quotaLimitWaitsAtLeastOneMinuteAndGrows() {
        assertEquals(60_000L, McGmailRetryPolicy.delayMs(403, "rateLimitExceeded", "Quota exceeded for Units per minute per user", 0))
        assertEquals(120_000L, McGmailRetryPolicy.delayMs(403, "rateLimitExceeded", "Quota exceeded", 1))
    }

    @Test fun concurrentRequestLimitUsesSlowerBackoff() {
        assertEquals(5_000L, McGmailRetryPolicy.delayMs(429, "rateLimitExceeded", "Too many concurrent requests for user", 0))
        assertEquals(10_000L, McGmailRetryPolicy.delayMs(429, "rateLimitExceeded", "Too many concurrent requests for user", 1))
    }

    @Test fun retryAfterIsRespected() {
        assertEquals(15_000L, McGmailRetryPolicy.delayMs(429, null, null, 0, 15))
    }
}
