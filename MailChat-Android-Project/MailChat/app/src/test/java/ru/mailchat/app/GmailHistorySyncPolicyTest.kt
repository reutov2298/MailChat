package ru.mailchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailHistorySyncPolicyTest {

    @Test
    fun oneOrManyDeletedIdsAreExcludedFromFetch() {
        val plan = GmailHistorySyncPolicy.plan(
            addedIds = listOf("a", "b", "c"),
            deletedIds = listOf("b", "c")
        )
        assertEquals(listOf("a"), plan.idsToFetch)
        assertEquals(setOf("b", "c"), plan.deletedIds.toSet())
    }

    @Test
    fun deletingAllMessagesProducesEmptyFetchPlan() {
        val plan = GmailHistorySyncPolicy.plan(
            addedIds = listOf("a", "b", "c"),
            deletedIds = listOf("a", "b", "c")
        )
        assertTrue(plan.idsToFetch.isEmpty())
        assertEquals(3, plan.deletedIds.size)
    }

    @Test
    fun duplicateHistoryIdsAreDeduplicated() {
        val plan = GmailHistorySyncPolicy.plan(
            addedIds = listOf("a", "a", "b", " "),
            deletedIds = emptyList()
        )
        assertEquals(listOf("a", "b"), plan.idsToFetch)
    }

    @Test
    fun confirmed404IdsAreTheOnlyMissingIdsAcceptedAsDeleted() {
        assertTrue(GmailHistorySyncPolicy.isConfirmedDeletionStatus(404))
        assertFalse(GmailHistorySyncPolicy.isConfirmedDeletionStatus(401))
        assertFalse(GmailHistorySyncPolicy.isConfirmedDeletionStatus(403))
        assertFalse(GmailHistorySyncPolicy.isConfirmedDeletionStatus(429))
        assertEquals(setOf("b"), GmailHistorySyncPolicy.confirmedNotFoundIds(
            requestedIds = listOf("a", "b"),
            fetchedIds = listOf("a")
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unexpectedReturnedIdIsRejected() {
        GmailHistorySyncPolicy.confirmedNotFoundIds(
            requestedIds = listOf("a"),
            fetchedIds = listOf("other")
        )
    }

    @Test
    fun temporaryFailureLeavesPendingIdUnresolvedAndBlocksCursor() {
        val unresolved = GmailHistorySyncPolicy.unresolvedIds(
            pendingIds = listOf("a", "b"),
            processedIds = listOf("a")
        )
        assertEquals(setOf("b"), unresolved)
        assertFalse(GmailHistorySyncPolicy.canAdvanceCursor(
            pendingIds = listOf("a", "b"),
            processedIds = listOf("a")
        ))
    }

    @Test
    fun cursorCanAdvanceAfterEveryPendingIdIsProcessedOrConfirmedDeleted() {
        assertTrue(GmailHistorySyncPolicy.canAdvanceCursor(
            pendingIds = listOf("a", "b", "c"),
            processedIds = listOf("a", "b", "c")
        ))
    }
}
