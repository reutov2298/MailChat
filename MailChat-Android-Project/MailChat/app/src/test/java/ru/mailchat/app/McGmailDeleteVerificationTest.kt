package ru.mailchat.app

import org.junit.Assert.assertEquals
import org.junit.Test

class McGmailDeleteVerificationTest {
    @Test fun onlyMessagesConfirmedMissingAreDeleted() {
        val snapshot = McGmailAdmin.MessageLabelSnapshot(
            present = mapOf("still-here" to setOf("SENT"), "other" to emptySet()),
            missing = setOf("gone")
        )
        assertEquals(listOf("still-here", "other"),
            McGmailDeleteVerification.remainingIds(listOf("gone", "still-here", "other"), snapshot))
    }

    @Test fun duplicateIdsAreCheckedOnce() {
        val snapshot = McGmailAdmin.MessageLabelSnapshot(
            present = mapOf("still-here" to setOf("SENT")),
            missing = emptySet()
        )
        assertEquals(listOf("still-here"),
            McGmailDeleteVerification.remainingIds(listOf("still-here", "still-here"), snapshot))
    }

    @Test fun allMissingMeansDeletionConfirmed() {
        val snapshot = McGmailAdmin.MessageLabelSnapshot(
            present = emptyMap(),
            missing = setOf("one", "two")
        )
        assertEquals(emptyList<String>(),
            McGmailDeleteVerification.remainingIds(listOf("one", "two"), snapshot))
    }
}
