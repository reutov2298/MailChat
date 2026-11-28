package ru.mailchat.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class McSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val auth = McAuth(applicationContext)
        val repo = McRepository(applicationContext)
        val logger = McLogger(applicationContext)
        logger.info("Запущена фоновая автоматическая проверка почты")
        var lockAcquired = false
        return try {
            if (!repo.acquireSyncLock()) return Result.success()
            lockAcquired = true
            var token = suspendCancellableCoroutine<String?> { continuation ->
                auth.accessToken { access, _ ->
                    if (continuation.isActive) continuation.resume(access)
                }
            } ?: return Result.success()

            val gmail = GmailTransport().apply {
                onRequestStarted = {
                    if (repo.diagnosticClickEnabled()) playDiagnosticClick()
                }
                onServerRequest = { logger.server("Запрос Gmail API (фоновый): $it") }
                onDiagnostic = { logger.debug(it) }
            }
            suspend fun <T> gmailCall(block: suspend (String) -> T): T {
                return try {
                    block(token)
                } catch (e: GmailTransport.GmailApiException) {
                    if (e.statusCode != 401) throw e
                    logger.error("Фоновая синхронизация получила HTTP 401; принудительно обновляем токен")
                    token = suspendCancellableCoroutine { continuation ->
                        auth.accessToken(forceRefresh = true) { access, _ ->
                            if (continuation.isActive) continuation.resume(access)
                        }
                    } ?: throw IllegalStateException("Не удалось обновить токен Google")
                    block(token)
                }
            }
            val since = repo.baseline().coerceAtLeast(1L)
            val labelId = gmailCall { fresh -> withContext(Dispatchers.IO) {
                repo.mcLabelId().ifBlank { gmail.ensureMcLabel(fresh).also(repo::setMcLabelId) }
            } }
            var nextHistoryId = ""
            val incoming = gmailCall { fresh ->
                withContext(Dispatchers.IO) {
                    val cursor = repo.historyId()
                    if (cursor.isNotBlank()) {
                        try {
                            val changes = gmail.history(fresh, cursor)
                            nextHistoryId = changes.historyId
                            gmail.getMessages(fresh, changes.messageIds.filterNot(repo::isProcessed))
                        } catch (e: GmailTransport.GmailApiException) {
                            if (e.statusCode != 404) throw e
                            logger.error("Фоновая синхронизация: historyId устарел, выполняется восстановительная выборка")
                            nextHistoryId = gmail.profile(fresh).historyId
                            gmail.list(fresh, "after:$since {in:inbox in:spam}", 50)
                        }
                    } else {
                        nextHistoryId = gmail.profile(fresh).historyId
                        gmail.list(fresh, "after:$since {in:inbox in:spam}", 50)
                    }
                }
            }
            var accepted = 0
            var chats = repo.chats().toMutableList()

            for (mail in incoming) {
                if (repo.isProcessed(mail.id)) continue
                val verification = McProtocol.verifyDetailed(mail, repo.myEmail())
                verification.diagnostics.forEach { logger.debug(it) }
                val parsed = verification.parsed
                if (parsed == null) {
                    if (mail.subject == "[MC] Message") {
                        logger.error("Фоновая проверка отклонила сообщение MC. Gmail ID: ${mail.id}. Причина: ${verification.reason}")
                    }
                    continue
                }
                logger.info("Фоновая проверка: подпись подтверждена. MC messageId: ${parsed.messageId}")
                val message = McMessage(
                    id = parsed.messageId,
                    chatId = parsed.chatId,
                    sender = parsed.sender,
                    recipient = parsed.recipient,
                    text = parsed.text,
                    timestamp = parsed.timestamp,
                    incoming = true,
                    state = DeliveryState.DELIVERED,
                    recoveredFromSpam = mail.spam,
                    sequence = parsed.sequence,
                    version = parsed.messageVersion,
                    deleted = parsed.messageType == "delete"
                )
                val index = chats.indexOfFirst { it.email.equals(parsed.sender, true) }
                val existingIndex = if (index >= 0) chats[index].messages.indexOfFirst { it.id == parsed.messageId } else -1
                if (existingIndex >= 0) {
                    val existing = chats[index].messages[existingIndex]
                    if (parsed.messageVersion <= existing.version) {
                        repo.saveGmailThread(repo.myEmail(), parsed.sender, mail.threadId, mail.headers["message-id"])
                        withContext(Dispatchers.IO) { gmailCall { fresh -> gmail.labelIncoming(fresh, mail.id, labelId) } }
                        repo.markProcessed(mail.id)
                        continue
                    }
                    val updatedMessages = chats[index].messages.toMutableList()
                    updatedMessages[existingIndex] = existing.copy(
                        text = if (parsed.messageType == "delete") "" else parsed.text,
                        timestamp = if (parsed.messageType == "edit") System.currentTimeMillis() / 1000L else existing.timestamp,
                        version = parsed.messageVersion,
                        sequence = parsed.sequence,
                        deleted = parsed.messageType == "delete"
                    )
                    chats[index] = chats[index].copy(messages = repo.orderedMessages(updatedMessages))
                } else if (index >= 0) {
                    chats[index] = chats[index].copy(
                        title = if (!chats[index].nameManuallySet && mail.senderName.isNotBlank()) mail.senderName else chats[index].title,
                        senderName = mail.senderName.takeIf { it.isNotBlank() } ?: chats[index].senderName,
                        messages = repo.orderedMessages(chats[index].messages + message)
                    )
                } else {
                    chats.add(McChat(parsed.sender, title = mail.senderName.ifBlank { parsed.sender }, messages = listOf(message), senderName = mail.senderName.takeIf { it.isNotBlank() }))
                }
                repo.save(chats)
                repo.saveGmailThread(repo.myEmail(), parsed.sender, mail.threadId, mail.headers["message-id"])
                withContext(Dispatchers.IO) { gmailCall { fresh -> gmail.labelIncoming(fresh, mail.id, labelId) } }
                repo.markProcessed(mail.id)
                accepted++
            }

            if (nextHistoryId.isNotBlank()) repo.setHistoryId(nextHistoryId)
            if (accepted > 0) showIncomingNotification(accepted)
            logger.info("Фоновая проверка завершена. Новых сообщений: $accepted; History cursor сохранён")
            Result.success()
        } catch (e: Exception) {
            logger.error("Ошибка фоновой проверки: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(200)}")
            Result.retry()
        } finally {
            if (lockAcquired) repo.releaseSyncLock()
            auth.close()
        }
    }

    private fun playDiagnosticClick() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 18)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 55)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 150)
        }
    }

    private fun showIncomingNotification(count: Int) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "mc_incoming"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Входящие сообщения MC",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Уведомления о новых сообщениях MC"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                )
            }
            manager.createNotificationChannel(channel)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(applicationContext, channelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(applicationContext)
        }.setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("MC")
            .setContentText(if (count == 1) "Новое входящее сообщение" else "Новых сообщений: $count")
            .setAutoCancel(true)
            .build()
        manager.notify(1001, notification)
    }
}
