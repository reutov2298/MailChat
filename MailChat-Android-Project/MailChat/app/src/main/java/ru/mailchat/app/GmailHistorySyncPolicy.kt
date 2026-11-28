package ru.mailchat.app

/**
 * Pure rules for reconciling Gmail History events with the durable local queue.
 * A missing message is considered deleted only when Gmail explicitly returns HTTP 404
 * for that message subrequest; transport/auth/server failures must remain retryable.
 */
internal object GmailHistorySyncPolicy {
    data class ChangePlan(
        val idsToFetch: List<String>,
        val deletedIds: List<String>
    )

    fun plan(addedIds: Collection<String>, deletedIds: Collection<String>): ChangePlan {
        val deleted = deletedIds.filter(String::isNotBlank).toCollection(linkedSetOf())
        val toFetch = addedIds.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .filterNot(deleted::contains)
            .toList()
        return ChangePlan(toFetch, deleted.toList())
    }

    /** The caller may use this only after getMessages has validated every batch subresponse. */
    fun confirmedNotFoundIds(requestedIds: Collection<String>, fetchedIds: Collection<String>): Set<String> {
        val requested = requestedIds.filter(String::isNotBlank).toSet()
        val fetched = fetchedIds.toSet()
        require(fetched.all(requested::contains)) { "Gmail returned an ID that was not requested" }
        return requested - fetched
    }

    fun unresolvedIds(pendingIds: Collection<String>, processedIds: Collection<String>): Set<String> =
        pendingIds.filter(String::isNotBlank).toSet() - processedIds.toSet()

    fun canAdvanceCursor(pendingIds: Collection<String>, processedIds: Collection<String>): Boolean =
        unresolvedIds(pendingIds, processedIds).isEmpty()

    fun isConfirmedDeletionStatus(httpStatusCode: Int): Boolean = httpStatusCode == 404

    fun hasTrashLabel(labelIds: Collection<String>): Boolean = "TRASH" in labelIds
}
