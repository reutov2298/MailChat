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
import org.json.JSONObject
import kotlin.coroutines.resume

class McSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val auth = McAuth(applicationContext)
        val repo = McRepository(applicationContext)
        return try {
            if (!repo.acquireSyncLock()) return Result.success()
            val token = suspendCancellableCoroutine<String?> { continuation ->
                auth.accessToken { access, _ ->
                    if (continuation.isActive) continuation.resume(access)
                }
            } ?: return Result.retry()

            val gmail = GmailTransport().apply {
                onRequestStarted = {
                    if (repo.diagnosticClickEnabled()) playDiagnosticClick()
                }
            }
            val since = repo.baseline().coerceAtLeast(1L)
            val incoming = withContext(Dispatchers.IO) {
                gmail.list(token, "after:$since {in:inbox in:spam}", 50)
            }
            var accepted = 0
            var chats = repo.chats().toMutableList()

            for (mail in incoming) {
                if (repo.isProcessed(mail.id)) continue
                val verification = McProtocol.verifyDetailed(mail, repo.myEmail())
                val parsed = verification.parsed ?: continue

                if (chats.any { chat -> chat.messages.any { it.id == parsed.messageId } }) {
                    repo.markProcessed(mail.id)
                    continue
                }

                if (mail.spam) {
                    withContext(Dispatchers.IO) { gmail.moveOutOfSpam(token, mail.id) }
                }

                val message = McMessage(
                    id = parsed.messageId,
                    chatId = parsed.chatId,
                    sender = parsed.sender,
                    recipient = parsed.recipient,
                    text = parsed.text,
                    timestamp = parsed.timestamp,
                    incoming = true,
                    state = DeliveryState.DELIVERED,
                    recoveredFromSpam = mail.spam
                )
                val index = chats.indexOfFirst { it.email.equals(parsed.sender, true) }
                if (index >= 0) {
                    chats[index] = chats[index].copy(messages = chats[index].messages + message)
                } else {
                    chats.add(McChat(parsed.sender, messages = listOf(message)))
                }
                repo.save(chats)
                repo.markProcessed(mail.id)
                accepted++
            }

            if (accepted > 0) showIncomingNotification(accepted)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        } finally {
            repo.releaseSyncLock()
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
