package ru.mailchat.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McGmailBatchErrorTest {
    @Test fun userRateLimit403IsRetryable() {
        val error = McGmailBatchError.fromPart(
            403,
            """HTTP/1.1 403 Forbidden
Content-Type: application/json

{"error":{"code":403,"message":"Rate limit exceeded","errors":[{"domain":"global","reason":"userRateLimitExceeded","message":"Rate limit exceeded"}]}}"""
        )
        assertTrue(error.isRetryable())
        assertTrue(error.diagnostic().contains("reason=userRateLimitExceeded"))
    }

    @Test fun rateLimit403IsRetryable() {
        val error = McGmailBatchError.fromPart(
            403,
            """{"error":{"message":"Rate limit exceeded","errors":[{"reason":"rateLimitExceeded"}]}}"""
        )
        assertTrue(error.isRetryable())
    }

    @Test fun permission403IsNotRetryable() {
        val error = McGmailBatchError.fromPart(
            403,
            """{"error":{"message":"Insufficient Permission","errors":[{"reason":"forbidden"}]}}"""
        )
        assertFalse(error.isRetryable())
    }

    @Test fun malformed403IsNotRetryable() {
        assertFalse(McGmailBatchError.fromPart(403, "not json").isRetryable())
    }

    @Test fun ordinary429AndServerErrorsRemainRetryable() {
        assertTrue(McGmailBatchError.fromPart(429, "{}").isRetryable())
        assertTrue(McGmailBatchError.fromPart(503, "{}").isRetryable())
    }

    @Test fun retryDecisionIsExtractedFromExceptionText() {
        assertTrue(McGmailBatchError.isRetryableException(
            "Gmail batch: письмо 7 вернуло HTTP 403 (reason=userRateLimitExceeded; message=Rate limit)"
        ))
        assertFalse(McGmailBatchError.isRetryableException(
            "Gmail batch: письмо 7 вернуло HTTP 403 (reason=forbidden; message=Denied)"
        ))
    }
}
