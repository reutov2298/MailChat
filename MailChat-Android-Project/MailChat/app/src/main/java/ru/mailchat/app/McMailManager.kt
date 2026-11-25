package ru.mailchat.app

class McMailManager(
    private val admin: McGmailAdmin,
    private val repo: McRepository,
    private val logger: McLogger
) {
    data class MigrationProgress(val processed: Int, val estimate: Int, val complete: Boolean)
    data class Statistics(val labels: List<McGmailAdmin.LabelStat>, val uniqueMessages: Int?, val uniqueMessagesError: String? = null)
    data class DeleteResult(val deleted: Int, val changed: Boolean = false)

    fun ensureLabel(token: String): String {
        val existing = repo.mcLabelId()
        if (existing.isNotBlank()) return existing
        val id = admin.ensureMcLabel(token)
        repo.setMcLabelId(id)
        logger.info("Создана или найдена метка Gmail MC: " + id)
        return id
    }

    fun migrateOldMessages(token: String, ownEmail: String, progress: (MigrationProgress) -> Unit) {
        if (repo.migrationDone()) {
            progress(MigrationProgress(repo.migrationProcessed(), repo.migrationEstimate(), true))
            return
        }
        val labelId = ensureLabel(token)
        var pageToken = repo.migrationPageToken()
        var processed = repo.migrationProcessed()
        var estimate = repo.migrationEstimate()
        logger.info("Первоначальная очистка старых писем MC: продолжение с сохранённой позиции")
        while (true) {
            val (candidates, page) = admin.candidatePage(token, pageToken, 50)
            if (estimate == 0) estimate = page.estimate
            val incomingIds = mutableListOf<String>()
            val outgoingIds = mutableListOf<String>()
            for (candidate in candidates) {
                val mail = candidate.message
                val sent = "SENT" in candidate.labels
                val sender = mail.headers["x-mc-sender"].orEmpty()
                val recipient = mail.headers["x-mc-recipient"].orEmpty()
                val expectedRecipient = if (sent) recipient else ownEmail
                if (expectedRecipient.isBlank()) continue
                val verified = McProtocol.verifyDetailed(mail, expectedRecipient).parsed
                if (verified == null) continue
                if (sent) {
                    if (!sender.equals(ownEmail, true) || !verified.sender.equals(ownEmail, true)) continue
                    if (labelId !in candidate.labels) outgoingIds += mail.id
                } else {
                    if (!verified.recipient.equals(ownEmail, true)) continue
                    if (labelId !in candidate.labels || "INBOX" in candidate.labels || "SPAM" in candidate.labels) incomingIds += mail.id
                }
            }
            if (outgoingIds.isNotEmpty()) admin.modifyMessages(token, outgoingIds, add = listOf(labelId))
            if (incomingIds.isNotEmpty()) admin.modifyMessages(token, incomingIds, add = listOf(labelId), remove = listOf("INBOX", "SPAM"))
            processed += candidates.size
            pageToken = page.nextPageToken
            repo.saveMigrationCheckpoint(pageToken, processed, estimate)
            progress(MigrationProgress(processed, estimate, false))
            logger.info("Первоначальная очистка MC: проверено кандидатов " + processed + "; помечено входящих " + incomingIds.size + "; исходящих " + outgoingIds.size)
            if (pageToken == null) break
        }
        repo.finishMigration()
        progress(MigrationProgress(processed, estimate, true))
        logger.info("Первоначальная очистка старых писем MC завершена")
    }

    fun statistics(token: String): Statistics {
        val labels = admin.labelStats(token)
        val count = runCatching { admin.countUniqueMessages(token) }
        return Statistics(labels, count.getOrNull(), count.exceptionOrNull()?.message?.take(180))
    }

    fun previewDelete(token: String): List<String> {
        val labelId = ensureLabel(token)
        return admin.mcMessageIds(token, labelId)
    }

    fun deleteConfirmedSnapshot(token: String, snapshot: List<String>, progress: (Int, Int) -> Unit): DeleteResult {
        val labelId = ensureLabel(token)
        val current = admin.mcMessageIds(token, labelId).toSet()
        if (current != snapshot.toSet()) return DeleteResult(0, changed = true)
        repo.saveDeleteJob(snapshot, 0)
        return resumeConfirmedDelete(token, progress)
    }

    fun resumeConfirmedDelete(token: String, progress: (Int, Int) -> Unit): DeleteResult {
        if (!repo.deleteJobConfirmed()) return DeleteResult(0)
        val ids = repo.deleteJobIds()
        val labelId = repo.mcLabelId().takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Не сохранён ID метки MC для продолжения удаления")
        var index = repo.deleteJobIndex().coerceIn(0, ids.size)
        while (index < ids.size) {
            val chunk = ids.subList(index, minOf(index + 10, ids.size))
            val labelSnapshot = admin.messageLabelIds(token, chunk)
            val changedIds = chunk.filter { id ->
                id !in labelSnapshot.missing && labelId !in (labelSnapshot.present[id] ?: emptySet())
            }
            if (changedIds.isNotEmpty()) {
                repo.clearDeleteJob()
                logger.error("Удаление MC остановлено: метка MC изменилась у " + changedIds.size + " писем из текущей партии")
                return DeleteResult(index, changed = true)
            }
            val stillPresent = chunk.filter { it !in labelSnapshot.missing }
            if (stillPresent.isNotEmpty()) admin.batchDelete(token, stillPresent)
            index += chunk.size
            repo.updateDeleteJobIndex(index)
            progress(index, ids.size)
            logger.info("Безвозвратное удаление MC: удалено " + index + " из " + ids.size)
        }
        repo.clearDeleteJob()
        logger.info("Безвозвратное удаление писем MC завершено. Удалено: " + ids.size)
        return DeleteResult(ids.size)
    }
}
