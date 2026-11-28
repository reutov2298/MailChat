package ru.mailchat.app

import android.content.ClipData
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.security.Security
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private val Navy = Color(0xFF17283B)
private val Blue = Color(0xFF3390EC)

private fun profileInitials(name: String): String {
    val normalized = name.substringBefore('@').replace(Regex("[._-]+"), " ").trim()
    val parts = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> (parts.first().first().toString() + parts.last().first().toString()).uppercase(Locale.getDefault())
        parts.isNotEmpty() -> parts.first().take(2).uppercase(Locale.getDefault())
        else -> "?"
    }
}

@Composable
private fun HeaderAvatar(
    photoUrl: String?,
    displayName: String,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    endPadding: androidx.compose.ui.unit.Dp = 12.dp
) {
    val avatarHost = remember(photoUrl) {
        if (photoUrl.isNullOrBlank()) "none"
        else runCatching { java.net.URI(photoUrl).host }.getOrNull() ?: "unknown"
    }
    Box(
        Modifier.padding(end = endPadding).size(size).clip(CircleShape).background(Blue),
        contentAlignment = Alignment.Center
    ) {
        // Initials remain visible as the background while Coil loads, and if loading fails.
        Text(
            profileInitials(displayName),
            color = Color.White,
            fontSize = if (size >= 48.dp) 20.sp else 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (!photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = photoUrl,
                contentDescription = "Аватар профиля",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onLoading = {
                    android.util.Log.i("MCAvatar", "Image load started: host=$avatarHost")
                },
                onSuccess = { state ->
                    android.util.Log.i(
                        "MCAvatar",
                        "Image load succeeded: host=$avatarHost, size=${state.result.drawable.intrinsicWidth}x${state.result.drawable.intrinsicHeight}"
                    )
                },
                onError = { state ->
                    android.util.Log.w(
                        "MCAvatar",
                        "Image load failed: host=$avatarHost, error=${state.result.throwable.javaClass.simpleName}"
                    )
                }
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var auth: McAuth
    private lateinit var repo: McRepository
    private lateinit var logger: McLogger
    private lateinit var identity: McIdentity
    private val gmail = GmailTransport()
    private var token: String? = null
    private var accountEmail: String = ""
    private var errorText by mutableStateOf("")
    private var authChanged by mutableIntStateOf(0)
    private var authResolving by mutableStateOf(false)
    private var chats by mutableStateOf<List<McChat>>(emptyList())
    private var syncInProgress by mutableStateOf(false)
    private var appForeground = true
    private var autoSyncJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Security.setProperty("crypto.policy", "unlimited")
        auth = McAuth(this); repo = McRepository(this); logger = McLogger(this); identity = McIdentity(this)
        gmail.onRequestStarted = { if (repo.diagnosticClickEnabled()) playDiagnosticClick() }
        gmail.onServerRequest = { logger.server("Запрос Gmail API: $it") }
        gmail.onDiagnostic = { logger.debug(it) }
        McSyncScheduler.schedule(this, repo.closedIntervalSeconds())
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4102)
        }
        repo.finalizeExpiredChatDeletions()
        chats = repo.chats()
        logger.add("Приложение запущено. Восстановлено чатов: ${chats.size}")
        if (!handleAuthIntent(intent) && auth.isConnected()) refreshToken()
        setContent { McApp() }
        restartAutoSyncLoop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthIntent(intent)
    }

    private suspend fun freshAccessToken(forceRefresh: Boolean = false): String =
        suspendCancellableCoroutine { continuation ->
            auth.accessToken(forceRefresh) { access, error ->
                if (continuation.isActive) {
                    if (access != null) {
                        token = access
                        continuation.resume(access)
                    } else {
                        continuation.resumeWithException(IllegalStateException(error ?: "Не удалось обновить токен Google"))
                    }
                }
            }
        }

    // Refresh once on HTTP 401. Transient failures must not clear the persisted OAuth session.
    private suspend fun <T> gmailRequest(block: suspend (String) -> T): T {
        val current = freshAccessToken()
        return try {
            block(current)
        } catch (e: GmailTransport.GmailApiException) {
            if (e.statusCode != 401) throw e
            logger.error("Gmail вернул HTTP 401; выполняется принудительное обновление токена")
            val renewed = freshAccessToken(forceRefresh = true)
            logger.add("Токен Google обновлён после HTTP 401; запрос повторяется один раз")
            block(renewed)
        }
    }

    private fun handleAuthIntent(intent: Intent): Boolean {
        authResolving = true
        val handled = auth.handle(intent) { access, error ->
            if (access != null) {
                token = access
                logger.add("Авторизация Google выполнена")
                authChanged++
                loadProfileAndSetBaseline()
            } else if (error != null) {
                errorText = error
                logger.error("Ошибка OAuth: $errorText")
                authResolving = false
                authChanged++
            }
        }
        if (!handled) authResolving = false
        return handled
    }

    private fun refreshToken() {
        authResolving = true
        auth.accessToken { access, error ->
            if (access != null) {
                token = access
                logger.add("Токен Google обновлён")
                authChanged++
                loadProfileAndSetBaseline()
            } else {
                // Keep the saved OAuth session and last known account on transient refresh failures.
                authResolving = false
                errorText = error.orEmpty()
                logger.error("Не удалось обновить токен Google (сессия сохранена): ${errorText.take(200)}")
                authChanged++
            }
        }
    }

    private fun loadProfileAndSetBaseline() {
        val t = token ?: return
        lifecycleScope.launch {
            try {
                logger.add("Запрос профиля Gmail")
                val profile = gmailRequest { fresh -> withContext(Dispatchers.IO) { gmail.profile(fresh) } }
                val ownProfile = runCatching { gmailRequest { fresh -> withContext(Dispatchers.IO) { gmail.ownProfile(fresh) } } }
                    .onFailure { logger.error("Не удалось получить профиль Google: ${it.javaClass.simpleName}") }
                    .getOrNull()
                if (ownProfile != null) {
                    repo.setOwnProfile(ownProfile.name, ownProfile.photoUrl)
                    logger.add("Профиль Google сохранён локально")
                }
                accountEmail = profile.email
                repo.setEmail(profile.email)
                val labelId = gmailRequest { fresh -> withContext(Dispatchers.IO) { gmail.ensureMcLabel(fresh) } }
                repo.setMcLabelId(labelId)
                authResolving = false
                if (repo.baseline() == 0L) {
                    repo.setBaseline(System.currentTimeMillis() / 1000)
                    repo.setHistoryId(profile.historyId)
                    logger.add("Первичная точка синхронизации сохранена; старые письма не импортируются")
                }
                logger.add("Gmail подключён: ${profile.email}")
                authChanged++
                syncNow(source = "после авторизации")
            } catch (e: Exception) {
                errorText = e.message ?: "Ошибка Gmail API"
                logger.error("Ошибка подключения Gmail: ${e.javaClass.simpleName}: ${errorText.take(200)}; OAuth-сессия сохранена")
                authResolving = false
                authChanged++
            }
        }
    }

    private fun sendMessage(to: String, message: McMessage, messageType: String, done: (String?) -> Unit) {
        val t = token ?: run { done("Сначала подключите Gmail"); return }
        lifecycleScope.launch {
            try {
                logger.add("Начата отправка события $messageType получателю: $to")
                val account = accountEmail.ifBlank { repo.myEmail() }
                val sent = gmailRequest { fresh ->
                    withContext(Dispatchers.IO) {
                        val pair = identity.keyPair()
                        gmail.send(
                            fresh, account, to, message.text,
                            message.chatId, identity.publicKey(),
                            messageId = message.id, sequence = message.sequence,
                            messageVersion = message.version, messageType = messageType,
                            messageTimestamp = message.timestamp,
                            senderName = repo.ownProfileName(),
                            senderAvatarUrl = repo.ownProfileAvatar(),
                            threadId = repo.gmailThreadId(account, to),
                            previousRfcMessageId = repo.gmailLastMessageId(account, to),
                            showTechnicalInfo = repo.showTechnicalInfoEnabled()
                        ) { bytes -> McCrypto.sign(pair, bytes) }
                    }
                }
                // Persist the successful send before the independent label request. A label failure
                // must never cause gmailRequest to resend the already delivered message.
                repo.saveGmailThread(account, to, sent.threadId, sent.rfcMessageId)
                repo.markProcessed(sent.id)
                runCatching {
                    gmailRequest { fresh ->
                        withContext(Dispatchers.IO) {
                            val labelId = repo.mcLabelId().ifBlank { gmail.ensureMcLabel(fresh).also(repo::setMcLabelId) }
                            gmail.labelSent(fresh, sent.id, labelId)
                        }
                    }
                }.onSuccess(repo::setMcLabelId)
                    .onFailure { logger.error("Сообщение отправлено, но не удалось добавить метку MC: ${it.javaClass.simpleName}") }
                logger.add("Событие MC отправлено в Gmail: ${sent.id}; messageId=${message.id}; sequence=${message.sequence}; version=${message.version}")
                done(null)
            } catch (e: Exception) {
                logger.error("Ошибка отправки: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(200)}")
                done(e.message ?: "Ошибка отправки")
            }
        }
    }

    private val syncQueue = mutableListOf<Pair<String, MutableList<(String) -> Unit>>>()
    private var syncQueueDraining = false

    private fun syncNow(source: String = "ручная", done: (String) -> Unit = {}) {
        if (token == null) {
            done("Нет подключения Gmail")
            return
        }
        if (syncQueueDraining) {
            if (syncQueue.isEmpty()) syncQueue.add(source to mutableListOf(done))
            else syncQueue[0].second.add(done)
            return
        }
        syncQueue.add(source to mutableListOf(done))
        syncQueueDraining = true
        syncInProgress = true
        lifecycleScope.launch {
            try {
                while (syncQueue.isNotEmpty()) {
                    val request = syncQueue.removeAt(0)
                    var result = "Проверка завершена"
                    performSync(request.first) { result = it }
                    request.second.forEach { callback -> runCatching { callback(result) } }
                }
            } finally {
                syncQueueDraining = false
                syncInProgress = false
            }
        }
    }

    private suspend fun performSync(source: String, done: (String) -> Unit) {
        val t = token ?: run { done("Нет подключения Gmail"); return }
        while (!repo.acquireSyncLock()) {
            delay(500)
            if (token == null) { done("Нет подключения Gmail"); return }
        }
        val since = repo.baseline().coerceAtLeast(1L)
        logger.info("Начата $source проверка почты. Точка синхронизации: " + repo.historyId().ifBlank { since.toString() })
        var nextHistoryId = ""
        var result = "Проверка завершена"
            try {
                val labelId = repo.mcLabelId().ifBlank {
                    gmailRequest { fresh -> withContext(Dispatchers.IO) { gmail.ensureMcLabel(fresh) } }.also(repo::setMcLabelId)
                }
                logger.add("Получение изменений Gmail через History API")
                val incoming = gmailRequest { fresh ->
                    withContext(Dispatchers.IO) {
                        val completedPending = repo.pendingHistoryIds().filter(repo::isProcessed)
                        repo.removePendingHistoryIds(completedPending)
                        val cursor = repo.historyId()
                        if (cursor.isNotBlank()) {
                            try {
                                val changes = gmail.history(fresh, cursor)
                                nextHistoryId = changes.historyId
                                val plan = GmailHistorySyncPolicy.plan(changes.messageIds, changes.deletedMessageIds)
                                plan.deletedIds.forEach { id ->
                                    logger.debug("Gmail History: подтверждено удаление письма; Gmail ID=$id")
                                    repo.markProcessed(id)
                                    repo.removePendingHistoryIds(listOf(id))
                                }
                                repo.addPendingHistoryIds(plan.idsToFetch)
                                val pendingIds = repo.pendingHistoryIds()
                                    .filterNot(repo::isProcessed)
                                    .filter(repo::isPendingHistoryRetryDue)
                                val fetched = gmail.getMessages(fresh, pendingIds)
                                val fetchedIds = fetched.map { it.id }.toSet()
                                val disappeared = GmailHistorySyncPolicy.confirmedNotFoundIds(pendingIds, fetchedIds)
                                disappeared.forEach { id ->
                                    logger.debug("Gmail History: Gmail подтвердил отсутствие письма (HTTP 404); ID=$id")
                                    repo.markProcessed(id)
                                }
                                if (disappeared.isNotEmpty()) repo.removePendingHistoryIds(disappeared)
                                logger.debug("Gmail History: запрошено ID=${pendingIds.size}; получено=${fetchedIds.size}; подтверждённо удалено=${disappeared.size}")
                                val trashedIds = fetched.filter { it.trashed }.map { it.id }
                                trashedIds.forEach { id ->
                                    logger.debug("Gmail: письмо уже находится в корзине TRASH; импорт пропущен; ID=$id")
                                    repo.markProcessed(id)
                                }
                                if (trashedIds.isNotEmpty()) repo.removePendingHistoryIds(trashedIds)
                                fetched.filterNot { it.trashed }
                            } catch (e: GmailTransport.GmailApiException) {
                                if (e.statusCode != 404) throw e
                                logger.error("Gmail historyId устарел; выполняется восстановительная синхронизация")
                                nextHistoryId = gmail.profile(fresh).historyId
                                val recovered = gmail.list(fresh, "after:$since {in:inbox in:spam}", 50, repo.processedIds())
                                repo.addPendingHistoryIds(recovered.map { it.id })
                                recovered
                            }
                        } else {
                            logger.info("Для этого аккаунта ещё нет History cursor; выполняется первичная выборка после контрольной даты")
                            nextHistoryId = gmail.profile(fresh).historyId
                            val recovered = gmail.list(fresh, "after:$since {in:inbox in:spam}", 50, repo.processedIds())
                            repo.addPendingHistoryIds(recovered.map { it.id })
                            recovered
                        }
                    }
                }
                suspend fun labelIncomingOrAcceptDeletion(messageId: String): String? {
                    return try {
                        withContext(Dispatchers.IO) {
                            gmailRequest { fresh -> gmail.labelIncoming(fresh, messageId, labelId) }
                        }
                    } catch (e: GmailTransport.GmailApiException) {
                        if (!GmailHistorySyncPolicy.isConfirmedDeletionStatus(e.statusCode)) throw e
                        logger.debug("Gmail: письмо удалено до установки метки MC; локальная копия сохранена; ID=$messageId")
                        null
                    }
                }

                logger.add("Gmail: получено новых сообщений для проверки: ${incoming.size}")

                var accepted = 0
                var skipped = 0

                for (mail in incoming) {
                    if (repo.isProcessed(mail.id)) {
                        repo.removePendingHistoryIds(listOf(mail.id))
                        skipped++
                        continue
                    }

                    logger.debug("Обработка письма Gmail ID: ${mail.id}")

                    val mcBlock = mail.body.substringAfter("-----BEGIN MC DATA-----", "").substringBefore("-----END MC DATA-----")
                    val bodyMarkers = mail.body.contains("-----BEGIN MC DATA-----") && mail.body.contains("-----END MC DATA-----")
                    val bodySignature = mcBlock.contains("X-MC-Signature:", ignoreCase = true)
                    val headerSignature = mail.headers.keys.any { it.equals("x-mc-signature", true) }
                    logger.debug("MC DATA extracted: bodyMarkers=$bodyMarkers, bodySignature=$bodySignature, mimeHeaderSignature=$headerSignature, signaturePresent=${bodySignature || headerSignature}")
                    val signatureLines = (mcBlock.lineSequence()
                        .filter { it.contains("signature", ignoreCase = true) }.toList() +
                        mail.headers.filterKeys { it.contains("signature", ignoreCase = true) }
                            .map { (key, value) -> "$key: length=${value.length}" })
                    logger.debug("MC signature diagnostics: " + signatureLines.map { line ->
                        "length=${line.length}, colon=${line.indexOf(':')}, chars=" +
                            line.takeWhile { it != ':' }.map { it.code.toString(16) }.joinToString(",")
                    }.joinToString("; "))

                    val verification = McProtocol.verifyDetailed(
                        mail, accountEmail.ifBlank { repo.myEmail() }
                    )
                    verification.diagnostics.forEach { logger.debug(it) }
                    val parsed = verification.parsed

                    if (parsed == null) {
                        if (McProtocol.isMcCandidate(mail)) {
                            val reason = verification.reason ?: "Сообщение MC не прошло проверку"
                            val diagnostic = buildList {
                                add("Gmail ID: ${mail.id}")
                                add("MIME: ${mail.mimeTypes.joinToString(",")}")
                                add("Количество заголовков: ${mail.headers.size}")
                                add("Размер тела письма: ${mail.body.toByteArray(Charsets.UTF_8).size} байт")
                                addAll(verification.diagnostics)
                            }.joinToString("\n")
                            repo.recordRejectedIncoming(
                                McProtocol.senderAddress(mail), mail.id, verification.errorCode, reason, diagnostic
                            )
                            logger.error("Сообщение MC отклонено и помещено в карантин. Gmail ID: ${mail.id}; код: ${verification.errorCode}; причина: $reason")
                            skipped++
                            continue
                        }
                        // A normal non-MC email is a terminal skip, not a failed MC event.
                        repo.markProcessed(mail.id)
                        repo.removePendingHistoryIds(listOf(mail.id))
                        skipped++
                        continue
                    }
                    logger.info("Криптографическая подпись подтверждена. MC messageId: ${parsed.messageId}")

                    val current = repo.chats().toMutableList()
                    val index = current.indexOfFirst { it.email.equals(parsed.sender, true) }
                    val message = McMessage(
                        id = parsed.messageId,
                        chatId = parsed.chatId,
                        sender = parsed.sender,
                        recipient = parsed.recipient,
                        text = if (parsed.messageType == "delete") "" else parsed.text,
                        timestamp = parsed.timestamp,
                        incoming = true,
                        state = DeliveryState.DELIVERED,
                        recoveredFromSpam = mail.spam,
                        sequence = parsed.sequence,
                        version = parsed.messageVersion,
                        deleted = parsed.messageType == "delete"
                    )
                    val existingIndex = if (index >= 0) current[index].messages.indexOfFirst { it.id == parsed.messageId } else -1
                    if (existingIndex >= 0) {
                        val existing = current[index].messages[existingIndex]
                        if (parsed.messageVersion > existing.version) {
                            val updatedMessages = current[index].messages.toMutableList()
                            updatedMessages[existingIndex] = existing.copy(
                                text = message.text,
                                timestamp = if (parsed.messageType == "edit") System.currentTimeMillis() / 1000L else existing.timestamp,
                                version = parsed.messageVersion,
                                sequence = parsed.sequence, deleted = message.deleted
                            )
                            current[index] = current[index].copy(messages = repo.orderedMessages(updatedMessages))
                        } else {
                            repo.saveGmailThread(accountEmail.ifBlank { repo.myEmail() }, parsed.sender, mail.threadId, mail.headers["message-id"])
                            labelIncomingOrAcceptDeletion(mail.id)?.let(repo::setMcLabelId)
                            repo.markProcessed(mail.id)
                            repo.removePendingHistoryIds(listOf(mail.id))
                            skipped++
                            continue
                        }
                    }

                    val incomingName = parsed.senderName.trim().takeIf { it.isNotBlank() }
                    val incomingAvatar = parsed.senderAvatarUrl.trim().takeIf { it.startsWith("https://") }
                    if (index >= 0) {
                        val existing = current[index]
                        current[index] = existing.copy(
                            title = if (!existing.nameManuallySet && incomingName != null) incomingName else existing.title,
                            senderName = incomingName ?: existing.senderName,
                            avatarUrl = incomingAvatar ?: existing.avatarUrl,
                            messages = if (existingIndex >= 0) existing.messages else repo.orderedMessages(existing.messages + message)
                        )
                    } else {
                        current.add(McChat(parsed.sender, title = incomingName ?: parsed.sender,
                            messages = listOf(message), senderName = incomingName,
                            avatarUrl = incomingAvatar))
                    }

                    repo.save(current)
                    repo.saveGmailThread(accountEmail.ifBlank { repo.myEmail() }, parsed.sender, mail.threadId, mail.headers["message-id"])
                    labelIncomingOrAcceptDeletion(mail.id)?.let(repo::setMcLabelId)
                    repo.markProcessed(mail.id)
                    repo.removePendingHistoryIds(listOf(mail.id))
                    accepted++
                    logger.info("Новое сообщение MC сохранено в локальном чате")
                }

                val unresolvedHistoryIds = GmailHistorySyncPolicy.unresolvedIds(
                    repo.pendingHistoryIds(), repo.processedIds()
                )
                if (unresolvedHistoryIds.isNotEmpty()) {
                    logger.error("Gmail History: cursor не продвигается; необработанные ID=${unresolvedHistoryIds.size}; примеры=${unresolvedHistoryIds.take(5).joinToString()}")
                    throw IllegalStateException("Остались необработанные Gmail ID: ${unresolvedHistoryIds.size}; History cursor сохранён без продвижения")
                }
                if (nextHistoryId.isNotBlank()) repo.setHistoryId(nextHistoryId)
                chats = repo.chats()
                logger.info("Синхронизация завершена. History cursor сохранён; обработка повторных Gmail ID исключена")
                if (accepted > 0) playIncomingSound()
                logger.add("Проверка Gmail завершена. Получено: $accepted; пропущено: $skipped; всего проверено: ${incoming.size}")
                result = "Проверка завершена. Новых сообщений: $accepted"
            } catch (e: Exception) {
                result = e.message ?: "Ошибка синхронизации"
                logger.error("Ошибка синхронизации: ${e.javaClass.simpleName}: ${result.take(250)}")
            } finally {
                repo.releaseSyncLock()
                authChanged++
                done(result)
            }
    }
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun McApp() {
        var selected by remember {
            mutableStateOf(repo.lastChat().takeIf { saved -> saved.isNotBlank() && chats.any { it.email.equals(saved, true) } })
        }
        var settings by remember { mutableStateOf(false) }
        var screenSettings by remember { mutableStateOf(false) }
        var chatAppearanceSettings by remember { mutableStateOf(false) }
        var chatMetadataAlphaPercent by remember { mutableIntStateOf(repo.chatMetadataAlphaPercent()) }
        var chatMetadataFontSizeSp by remember { mutableIntStateOf(repo.chatMetadataFontSizeSp()) }
        var chatCardAlphaPercent by remember { mutableIntStateOf(repo.chatCardAlphaPercent()) }
        var chatMessageFontSizeSp by remember { mutableIntStateOf(repo.chatMessageFontSizeSp()) }
        var logs by remember { mutableStateOf(false) }
        var showNew by remember { mutableStateOf(false) }
        var editContactProfile by remember { mutableStateOf(false) }
        var contactName by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("") }
        var logText by remember { mutableStateOf(logger.read()) }
        var logSize by remember { mutableLongStateOf(logger.sizeBytes()) }
        var openSeconds by remember { mutableStateOf(repo.openIntervalSeconds().toString()) }
        var minimizedSeconds by remember { mutableStateOf(repo.minimizedIntervalSeconds().toString()) }
        var closedSeconds by remember { mutableStateOf(repo.closedIntervalSeconds().toString()) }
        var diagnosticClick by remember { mutableStateOf(repo.diagnosticClickEnabled()) }
        var showTechnicalInfo by remember { mutableStateOf(repo.showTechnicalInfoEnabled()) }
        val pendingDeleteDeadlines = remember { mutableStateMapOf<String, Long>().apply { putAll(repo.pendingChatDeletions()) } }
        var deleteCountdownNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val logListState = rememberLazyListState()
        val logLines = remember(logText) { logText.lineSequence().filter { it.isNotBlank() }.toList() }
        val selectedChat = chats.firstOrNull { selected != null && it.email.equals(selected, true) }

        LaunchedEffect(logs) {
            while (logs) {
                val latest = logger.read()
                if (latest != logText) logText = latest
                logSize = logger.sizeBytes()
                delay(250)
            }
        }
        LaunchedEffect(logs, logLines.size) {
            if (logs && logLines.isNotEmpty()) logListState.scrollToItem(logLines.lastIndex)
        }
        LaunchedEffect(Unit) {
            while (true) {
                deleteCountdownNow = System.currentTimeMillis()
                val expired = pendingDeleteDeadlines.filterValues { it <= deleteCountdownNow }.keys.toList()
                expired.forEach { email ->
                    repo.deleteChat(email)
                    pendingDeleteDeadlines.remove(email)
                    chats = repo.chats()
                    logger.info("Удаление диалога окончательно подтверждено: $email")
                }
                delay(100)
            }
        }
        val applySyncSettings = {
            repo.saveSyncSettings(
                openSeconds.toIntOrNull() ?: repo.openIntervalSeconds(),
                minimizedSeconds.toIntOrNull() ?: repo.minimizedIntervalSeconds(),
                closedSeconds.toIntOrNull() ?: repo.closedIntervalSeconds(),
                diagnosticClick,
                showTechnicalInfo
            )
            McSyncScheduler.schedule(this@MainActivity, repo.closedIntervalSeconds())
            restartAutoSyncLoop()
            logger.add("Настройки интервалов синхронизации применены")
        }
        val clipboardManager = remember {
            getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        }
        val refresh = authChanged
        BackHandler(enabled = settings || logs || screenSettings || chatAppearanceSettings || selected != null) {
            when {
                chatAppearanceSettings -> chatAppearanceSettings = false
                screenSettings -> screenSettings = false
                logs -> logs = false
                settings -> { applySyncSettings(); settings = false }
                selected != null -> { selected = null; repo.setLastChat(null) }
            }
        }
        MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Color(0xFF101820), surface = Color(0xFF172330), onSurface = Color(0xFFEAF0F6))) {
            Scaffold(
                floatingActionButton = {
                    if (!settings && !logs && !screenSettings && !chatAppearanceSettings && selected == null) {
                        FloatingActionButton(onClick = { showNew = true }, shape = CircleShape) {
                            Icon(Icons.Default.Add, contentDescription = "Новый чат")
                        }
                    }
                },
                bottomBar = {
                    val deletions = pendingDeleteDeadlines.entries.sortedBy { it.value }
                    if (deletions.isNotEmpty()) Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).padding(bottom = 72.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(18.dp), tonalElevation = 4.dp, shadowElevation = 6.dp
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                            deletions.forEach { deletion ->
                                val secondsLeft = ceil(((deletion.value - deleteCountdownNow).coerceAtLeast(0L)) / 1000.0).toInt()
                                if (secondsLeft > 0) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    val deletionName = chats.firstOrNull { it.email.equals(deletion.key, true) }?.title?.takeIf { !it.equals(deletion.key, true) } ?: deletion.key
                                    Text("Удаление ${deletionName} через $secondsLeft", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
                                    TextButton(onClick = {
                                        repo.cancelChatDeletion(deletion.key)
                                        pendingDeleteDeadlines.remove(deletion.key)
                                        logger.info("Удаление диалога отменено: ${deletion.key}")
                                    }) { Text("Отмена") }
                                }
                            }
                        }
                    }
                },
                topBar = {
                TopAppBar(title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!logs && !settings && !screenSettings && !chatAppearanceSettings) {
                            if (selectedChat != null) {
                                HeaderAvatar(
                                    selectedChat.avatarUrl,
                                    selectedChat.senderName?.takeIf { it.isNotBlank() } ?: selectedChat.title
                                )
                            } else {
                                val ownName = repo.ownProfileName().ifBlank {
                                    accountEmail.ifBlank { repo.myEmail() }
                                }
                                HeaderAvatar(repo.ownProfileAvatar(), ownName)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    logs -> "Логирование"
                                    chatAppearanceSettings -> "Настройка чата"
                                    screenSettings -> "Настройки экранов"
                                    settings -> "Настройки"
                                    authResolving -> "Подключение..."
                                    else -> selectedChat?.title ?: "MC"
                                },
                                fontWeight = FontWeight.Bold
                            )
                            val subtitle = if (selectedChat != null) {
                                selectedChat.email.takeIf { !selectedChat.title.equals(selectedChat.email, true) }.orEmpty()
                            } else if (token != null) accountEmail.ifBlank { repo.myEmail() } else "Мессенджер на Gmail"
                            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }, navigationIcon = {
                    if (selected != null || settings || logs || screenSettings || chatAppearanceSettings) IconButton(onClick = {
                        when {
                            chatAppearanceSettings -> chatAppearanceSettings = false
                            screenSettings -> screenSettings = false
                            logs -> logs = false
                            settings -> { applySyncSettings(); settings = false }
                            selected != null -> { selected = null; repo.setLastChat(null) }
                        }
                    }) { Icon(Icons.Default.ArrowBack, "Назад") }
                }, actions = {
                    if (!settings && !logs && !screenSettings && !chatAppearanceSettings && selected == null) {
                        IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Настройки") }
                    } else if (!settings && !logs && selectedChat != null) {
                        IconButton(onClick = {
                            contactName = if (selectedChat.nameManuallySet) selectedChat.title else selectedChat.senderName.orEmpty()
                            editContactProfile = true
                        }) { Icon(Icons.Default.Edit, "Профиль собеседника") }
                    }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy))
            }) { padding ->
                when {
                    authResolving -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text("Проверяем подключение к Gmail...")
                        }
                    }
                    logs -> Column(Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
                        Text("Размер файла: ${logSize} байт", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    syncNow {
                                        status = it
                                        logText = logger.read()
                                        logSize = logger.sizeBytes()
                                    }
                                },
                                enabled = token != null && !syncInProgress
                            ) {
                                Text(if (syncInProgress) "Проверяем..." else "Проверить почту")
                            }
                            IconButton(onClick = {
                                clipboardManager.setPrimaryClip(
                                    ClipData.newPlainText("MC log", logText)
                                )
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Копировать лог")
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                logger.clear()
                                logText = logger.read()
                                logSize = logger.sizeBytes()
                            }) { Text("Очистить лог") }
                        }
                        LazyColumn(
                            state = logListState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 6.dp)
                        ) {
                            items(logLines) { line ->
                                val color = when {
                                    line.contains("[SERVER]") -> Color(0xFF55D68B)
                                    line.contains("[ERROR]") || line.contains("[WARN]") -> Color(0xFFFF6262)
                                    else -> Color.White
                                }
                                Text(line, color = color, fontSize = 12.sp, lineHeight = 17.sp,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp))
                            }
                        }
                    }
                    chatAppearanceSettings -> Column(
                        Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Прозрачность даты, времени и надписи «изменено»", fontWeight = FontWeight.SemiBold)
                        Text("$chatMetadataAlphaPercent%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(
                            value = chatMetadataAlphaPercent.toFloat(),
                            onValueChange = {
                                chatMetadataAlphaPercent = it.roundToInt().coerceIn(0, 100)
                                repo.saveChatAppearanceSettings(chatMetadataAlphaPercent, chatMetadataFontSizeSp, chatCardAlphaPercent, chatMessageFontSizeSp)
                            },
                            valueRange = 0f..100f,
                            steps = 99
                        )
                        HorizontalDivider()
                        Text("Размер шрифта даты, времени и надписи «изменено»", fontWeight = FontWeight.SemiBold)
                        Text("$chatMetadataFontSizeSp sp", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(
                            value = chatMetadataFontSizeSp.toFloat(),
                            onValueChange = {
                                chatMetadataFontSizeSp = ChatAppearanceSettingsPolicy.normalizeFontSizeSp(it.roundToInt())
                                repo.saveChatAppearanceSettings(chatMetadataAlphaPercent, chatMetadataFontSizeSp, chatCardAlphaPercent, chatMessageFontSizeSp)
                            },
                            valueRange = ChatAppearanceSettingsPolicy.MIN_FONT_SIZE_SP.toFloat()..ChatAppearanceSettingsPolicy.MAX_FONT_SIZE_SP.toFloat(),
                            steps = 7
                        )
                        HorizontalDivider()
                        HorizontalDivider()
                        Text("Прозрачность карточки сообщения", fontWeight = FontWeight.SemiBold)
                        Text("$chatCardAlphaPercent%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(
                            value = chatCardAlphaPercent.toFloat(),
                            onValueChange = {
                                chatCardAlphaPercent = ChatAppearanceSettingsPolicy.normalizeCardAlphaPercent(it.roundToInt())
                                repo.saveChatAppearanceSettings(chatMetadataAlphaPercent, chatMetadataFontSizeSp, chatCardAlphaPercent, chatMessageFontSizeSp)
                            },
                            valueRange = 0f..100f,
                            steps = 99
                        )
                        HorizontalDivider()
                        Text("Размер шрифта текста сообщения", fontWeight = FontWeight.SemiBold)
                        Text("$chatMessageFontSizeSp sp", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(
                            value = chatMessageFontSizeSp.toFloat(),
                            onValueChange = {
                                chatMessageFontSizeSp = ChatAppearanceSettingsPolicy.normalizeMessageFontSizeSp(it.roundToInt())
                                repo.saveChatAppearanceSettings(chatMetadataAlphaPercent, chatMetadataFontSizeSp, chatCardAlphaPercent, chatMessageFontSizeSp)
                            },
                            valueRange = ChatAppearanceSettingsPolicy.MIN_MESSAGE_FONT_SIZE_SP.toFloat()..ChatAppearanceSettingsPolicy.MAX_MESSAGE_FONT_SIZE_SP.toFloat(),
                            steps = 13
                        )
                        HorizontalDivider()
                        Text("Пример отображения", fontWeight = FontWeight.SemiBold)
                        Surface(
                            color = Color(0xFF145B49).copy(alpha = chatCardAlphaPercent / 100f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("Пример текста сообщения", color = Color.White, fontSize = chatMessageFontSizeSp.sp, lineHeight = (chatMessageFontSizeSp * 1.125f).sp)
                                Text(
                                    "изменено   28.11.26   09:42",
                                    color = Color.LightGray.copy(alpha = chatMetadataAlphaPercent / 100f),
                                    fontSize = chatMetadataFontSizeSp.sp,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                )
                            }
                        }
                        Text("Изменения применяются сразу и сохраняются автоматически.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    screenSettings -> Column(
                        Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("Настройки экранов", fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = { chatAppearanceSettings = true }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Настройка чата")
                                Spacer(Modifier.weight(1f))
                                Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.graphicsLayer(rotationZ = 180f))
                            }
                        }
                    }
                    settings -> Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Интерфейс", fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = { screenSettings = true }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Настройки экранов")
                                Spacer(Modifier.weight(1f))
                                Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.graphicsLayer(rotationZ = 180f))
                            }
                        }
                        HorizontalDivider()
                        Text("Аккаунт Google", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                token != null -> accountEmail.ifBlank { repo.myEmail() }
                                auth.isConnected() -> "Аккаунт сохранён — восстанавливаем подключение"
                                else -> "Не подключён"
                            }
                        )
                        if (token == null && !auth.isConnected()) {
                            Button(onClick = {
                                try {
                                    auth.start(this@MainActivity)
                                } catch (e: Exception) {
                                    errorText = e.message ?: "Ошибка OAuth"
                                    logger.error("Ошибка запуска OAuth: ${errorText.take(200)}")
                                    authChanged++
                                }
                            }) { Text("Продолжить с Google") }
                        }
                        if (token == null && auth.isConnected()) {
                            OutlinedButton(onClick = { refreshToken() }) { Text("Повторить подключение") }
                        }
                        if (token != null || auth.isConnected()) OutlinedButton(onClick = { auth.disconnect(); repo.setOwnProfile(null, null); token = null; accountEmail = ""; logger.add("Аккаунт отключён"); authChanged++ }) { Text("Отключить аккаунт") }
                        Text("Имя и фото профиля Google передаются собеседнику вместе с вашими сообщениями. Для выдачи нового разрешения profile переподключите Google-аккаунт.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        Text("Отправка сообщений", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = showTechnicalInfo, onCheckedChange = { showTechnicalInfo = it })
                            Text("Показывать техническую информацию в письме", fontSize = 14.sp)
                        }
                        Text("Если флажок снят, в теле письма будет только текст сообщения. Служебные данные MC всё равно передаются в MIME-заголовках и используются для проверки и синхронизации.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        Text("Логирование", fontWeight = FontWeight.Bold)
                        Button(onClick = { logs = true; logText = logger.read(); logSize = logger.sizeBytes() }) { Text("Открыть журнал") }
                        Text("Безопасность: текст первой версии не зашифрован. Закрытый ключ подписи хранится в зашифрованных локальных настройках.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        Text("Автоматическая проверка почты", fontWeight = FontWeight.Bold)
                        OutlinedTextField(value = openSeconds, onValueChange = { openSeconds = it.filter(Char::isDigit).take(6) }, label = { Text("Каждые N секунд — приложение открыто") }, singleLine = true)
                        OutlinedTextField(value = minimizedSeconds, onValueChange = { minimizedSeconds = it.filter(Char::isDigit).take(6) }, label = { Text("Каждые N секунд — приложение свёрнуто") }, singleLine = true)
                        OutlinedTextField(value = closedSeconds, onValueChange = { closedSeconds = it.filter(Char::isDigit).take(6) }, label = { Text("Каждые N секунд — приложение закрыто") }, singleLine = true)
                        Text("Для фоновой проверки Android ограничивает периодические задачи минимум 15 минутами. Принудительная остановка приложения системой или пользователем может остановить фоновые проверки.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = diagnosticClick, onCheckedChange = { diagnosticClick = it })
                            Text("Звук при каждом обращении к серверу", fontSize = 14.sp)
                        }
                        HorizontalDivider()
                        Text("Версия: ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Номер сборки: ${BuildConfig.VERSION_CODE}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (errorText.isNotBlank()) Text(errorText, color = MaterialTheme.colorScheme.error)
                        if (status.isNotBlank()) Text(status)
                    }
                    selected != null -> {
                        val email = selected!!
                        val chat = chats.firstOrNull { it.email.equals(email, true) } ?: McChat(email)
                        Conversation(
                            chat, Modifier.padding(padding), isSyncing = syncInProgress,
                            chatMetadataAlphaPercent = chatMetadataAlphaPercent,
                            chatMetadataFontSizeSp = chatMetadataFontSizeSp,
                            chatCardAlphaPercent = chatCardAlphaPercent,
                            chatMessageFontSizeSp = chatMessageFontSizeSp,
                            onRefresh = { syncNow(source = "жест обновления переписки") },
                            onSend = { body, editing ->
                                val outgoing = if (editing == null) {
                                    McMessage(
                                        chatId = email.lowercase(), sender = accountEmail, recipient = email,
                                        text = body, incoming = false, state = DeliveryState.QUEUED,
                                        sequence = repo.nextSequence(email.lowercase(), accountEmail)
                                    )
                                } else editing.copy(
                                    text = body, timestamp = System.currentTimeMillis() / 1000L,
                                    version = editing.version + 1L, deleted = false,
                                    sequence = repo.sequenceForMutation(email.lowercase(), accountEmail, editing.id, editing.sequence),
                                    state = DeliveryState.QUEUED
                                )
                                val updatedChats = chats.map { currentChat ->
                                    if (currentChat.email.equals(email, true)) {
                                        val messages = if (editing == null) currentChat.messages + outgoing
                                        else currentChat.messages.map { if (it.id == editing.id) outgoing else it }
                                        currentChat.copy(messages = repo.orderedMessages(messages))
                                    } else currentChat
                                }
                                chats = if (updatedChats.any { it.email.equals(email, true) }) updatedChats
                                    else updatedChats + McChat(email, messages = listOf(outgoing))
                                repo.save(chats)
                                sendMessage(email, outgoing, if (editing == null) "text" else "edit") { error ->
                                    chats = chats.map { currentChat ->
                                        if (currentChat.email.equals(email, true)) currentChat.copy(
                                            messages = currentChat.messages.map { item ->
                                                if (item.id == outgoing.id && item.version == outgoing.version)
                                                    item.copy(state = if (error == null) DeliveryState.SENT_TO_GMAIL else DeliveryState.FAILED)
                                                else item
                                            }
                                        ) else currentChat
                                    }
                                    repo.save(chats); status = error ?: "Принято Gmail"; authChanged++
                                }
                            },
                            onDeleteMessage = { target ->
                                val deleted = target.copy(
                                    text = "", version = target.version + 1L, deleted = true,
                                    sequence = repo.sequenceForMutation(email.lowercase(), accountEmail, target.id, target.sequence),
                                    state = DeliveryState.QUEUED
                                )
                                chats = chats.map { currentChat ->
                                    if (currentChat.email.equals(email, true)) currentChat.copy(
                                        messages = repo.orderedMessages(currentChat.messages.map { if (it.id == target.id) deleted else it })
                                    ) else currentChat
                                }
                                repo.save(chats)
                                sendMessage(email, deleted, "delete") { error ->
                                    chats = chats.map { currentChat ->
                                        if (currentChat.email.equals(email, true)) currentChat.copy(
                                            messages = currentChat.messages.map { item ->
                                                if (item.id == deleted.id && item.version == deleted.version)
                                                    item.copy(state = if (error == null) DeliveryState.SENT_TO_GMAIL else DeliveryState.FAILED)
                                                else item
                                            }
                                        ) else currentChat
                                    }
                                    repo.save(chats); status = error ?: "Сообщение удалено"; authChanged++
                                }
                            }
                        )
                    }
                    else -> Column(Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
                        if (token == null) {
                            Text("Добро пожаловать в MC", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("Подключите Gmail, чтобы начать обмен сообщениями.")
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { settings = true }) { Text("Подключить Gmail") }
                        } else {
                            val chatListState = rememberLazyListState()
                            val density = LocalDensity.current
                            Box(
                                Modifier.fillMaxSize().pointerInput(chatListState, syncInProgress, token) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(
                                            requireUnconsumed = false,
                                            pass = PointerEventPass.Initial
                                        )
                                        val startedAtTop = chatListState.firstVisibleItemIndex == 0 &&
                                            chatListState.firstVisibleItemScrollOffset == 0
                                        var totalY = 0f
                                        var totalX = 0f
                                        var pointerDown = true
                                        while (pointerDown) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            totalX += change.position.x - change.previousPosition.x
                                            totalY += change.position.y - change.previousPosition.y
                                            pointerDown = change.pressed
                                        }
                                        val refreshThreshold = with(density) { 72.dp.toPx() }
                                        val stillAtTop = chatListState.firstVisibleItemIndex == 0 &&
                                            chatListState.firstVisibleItemScrollOffset == 0
                                        if (
                                            startedAtTop && stillAtTop &&
                                            totalY >= refreshThreshold &&
                                            totalY > kotlin.math.abs(totalX) * 1.25f &&
                                            !syncInProgress && token != null
                                        ) {
                                            syncNow(source = "жест обновления списка чатов")
                                        }
                                    }
                                }
                            ) {
                                LazyColumn(state = chatListState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                                items(chats.filterNot { pendingDeleteDeadlines.keys.any { pending -> pending.equals(it.email, true) } }.sortedByDescending { it.messages.maxOfOrNull(McMessage::timestamp) ?: 0L }, key = { it.email }) { chat ->
                                    val dragOffset = remember(chat.email) { mutableFloatStateOf(0f) }
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                            .graphicsLayer {
                                                translationX = dragOffset.floatValue
                                                val threshold = size.width * 0.30f
                                                alpha = if (threshold > 0f) 1f - (kotlin.math.abs(dragOffset.floatValue) / threshold).coerceIn(0f, 1f) else 1f
                                            }
                                            .pointerInput(chat.email) {
                                                detectHorizontalDragGestures(
                                                    onHorizontalDrag = { change, amount -> dragOffset.floatValue = (dragOffset.floatValue + amount).coerceAtMost(0f); change.consume() },
                                                    onDragEnd = {
                                                        val threshold = size.width * 0.30f
                                                        if (threshold > 0f && dragOffset.floatValue <= -threshold) {
                                                            val deadline = System.currentTimeMillis() + 5000L
                                                            repo.scheduleChatDeletion(chat.email, deadline)
                                                            pendingDeleteDeadlines[chat.email] = deadline
                                                            logger.info("Диалог скрыт на 5 секунд; доступна отмена: ${chat.email}")
                                                        }
                                                        dragOffset.floatValue = 0f
                                                    },
                                                    onDragCancel = { dragOffset.floatValue = 0f }
                                                )
                                            }
                                            .clickable { selected = chat.email; repo.setLastChat(chat.email) },
                                        shape = RoundedCornerShape(0.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            HeaderAvatar(
                                                chat.avatarUrl,
                                                chat.senderName?.takeIf { it.isNotBlank() } ?: chat.title,
                                                size = 44.dp,
                                                endPadding = 0.dp
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(
                                                Modifier.weight(1f).fillMaxWidth().padding(end = 8.dp),
                                                verticalArrangement = Arrangement.spacedBy(0.dp)
                                            ) {
                                                Text(
                                                    chat.title,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 15.sp,
                                                    color = Color(0xFFF1F5F9),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    chat.messages.lastOrNull()?.text ?: "",
                                                    modifier = Modifier.fillMaxWidth(),
                                                    color = Color(0xFF9EADBC).copy(alpha = 0.85f),
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontSize = 12.sp,
                                                    lineHeight = 14.sp
                                                )
                                            }
                                            Column(
                                                modifier = Modifier.widthIn(min = 40.dp),
                                                horizontalAlignment = Alignment.End
                                            ) {
                                                Text(
                                                    chat.messages.lastOrNull()?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.timestamp * 1000L)) } ?: "",
                                                    color = Color(0xFF8FA1B3),
                                                    fontSize = 11.sp,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                                }
                                if (syncInProgress) {
                                    Surface(
                                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                                        color = Navy.copy(alpha = 0.85f),
                                        shape = RoundedCornerShape(20.dp),
                                        shadowElevation = 4.dp
                                    ) {
                                        Row(
                                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.width(8.dp))
                                            Text("Проверяем почту...", fontSize = 12.sp, color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (showNew) {
                var email by remember { mutableStateOf("") }
                var displayName by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { showNew = false },
                    shape = RoundedCornerShape(28.dp),
                    tonalElevation = 6.dp,
                    title = { Text("Новый чат") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = displayName,
                                onValueChange = { displayName = it },
                                label = { Text("Имя") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text("Gmail собеседника") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = { TextButton(onClick = {
                    if (email.contains("@")) {
                        val normalized = email.trim().lowercase()
                        val manualName = displayName.trim()
                        repo.restoreChat(normalized)
                        val updated = if (chats.none { it.email.equals(normalized, true) }) {
                            chats + McChat(normalized, title = manualName.ifBlank { normalized }, nameManuallySet = manualName.isNotBlank())
                        } else chats.map { existing ->
                            if (existing.email.equals(normalized, true)) existing.copy(
                                title = manualName.ifBlank { existing.senderName ?: normalized },
                                nameManuallySet = manualName.isNotBlank()
                            ) else existing
                        }
                        chats = updated
                        repo.save(updated)
                        selected = normalized
                        repo.setLastChat(normalized)
                        showNew = false
                    }
                }) { Text("Добавить") } },
                    dismissButton = { TextButton(onClick = { showNew = false }) { Text("Отмена") } }
                )
            }
        }
        if (editContactProfile && selectedChat != null) {
            AlertDialog(
                onDismissRequest = { editContactProfile = false },
                title = { Text("Профиль собеседника") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(selectedChat.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = contactName, onValueChange = { contactName = it },
                            label = { Text("Имя") }, singleLine = true)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val name = contactName.trim()
                        val updated = chats.map { chat ->
                            if (chat.email.equals(selectedChat.email, true)) {
                                if (name.isBlank()) chat.copy(title = chat.senderName ?: chat.email, nameManuallySet = false)
                                else chat.copy(title = name, nameManuallySet = true)
                            } else chat
                        }
                        chats = updated
                        repo.save(updated)
                        editContactProfile = false
                    }) { Text("Сохранить") }
                },
                dismissButton = { TextButton(onClick = { editContactProfile = false }) { Text("Отмена") } }
            )
        }
    }

    @Composable private fun Conversation(
        chat: McChat,
        modifier: Modifier,
        isSyncing: Boolean,
        chatMetadataAlphaPercent: Int,
        chatMetadataFontSizeSp: Int,
        chatCardAlphaPercent: Int,
        chatMessageFontSizeSp: Int,
        onRefresh: () -> Unit,
        onSend: (String, McMessage?) -> Unit,
        onDeleteMessage: (McMessage) -> Unit
    ) {
        var draft by remember(chat.email) { mutableStateOf(TextFieldValue("")) }
        var editingMessage by remember(chat.email) { mutableStateOf<McMessage?>(null) }
        var actionMessage by remember(chat.email) { mutableStateOf<McMessage?>(null) }
        var actionMenuPosition by remember(chat.email) { mutableStateOf(IntOffset.Zero) }
        val listState = rememberLazyListState()
        val clipboardManager = LocalClipboardManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        val messageInputFocusRequester = remember { FocusRequester() }
        LaunchedEffect(editingMessage) {
            if (editingMessage != null) {
                messageInputFocusRequester.requestFocus()
                keyboardController?.show()
            }
        }
        val density = LocalDensity.current
        val imeInsets = WindowInsets.ime
        val baseViewConfiguration = LocalViewConfiguration.current
        val longPressViewConfiguration = remember(baseViewConfiguration) {
            object : ViewConfiguration by baseViewConfiguration {
                override val longPressTimeoutMillis: Long = 600L
            }
        }
        var scrollAfterSend by remember(chat.email) { mutableStateOf(false) }
        val orderedMessages = remember(chat.messages) {
            chat.messages.sortedWith(compareBy<McMessage> { it.orderTimestamp }.thenBy { it.sequence }.thenBy { it.id })
        }
        var knownIncomingMessageIds by remember(chat.email) {
            mutableStateOf(chat.messages.filter { it.incoming }.map { it.id }.toSet())
        }
        LaunchedEffect(chat.email) { if (orderedMessages.isNotEmpty()) listState.scrollToItem(orderedMessages.lastIndex) }
        LaunchedEffect(chat.email, orderedMessages.size) {
            snapshotFlow { imeInsets.getBottom(density) }.collect {
                val lastIndex = orderedMessages.lastIndex
                if (lastIndex >= 0) listState.scrollToItem(lastIndex)
            }
        }
        LaunchedEffect(chat.email, orderedMessages.map { it.id to it.incoming }) {
            val currentIncomingIds = orderedMessages.filter { it.incoming }.map { it.id }.toSet()
            val newlyReceived = orderedMessages.withIndex().filter { (_, message) ->
                message.incoming && !message.deleted && message.id !in knownIncomingMessageIds
            }
            knownIncomingMessageIds = currentIncomingIds
            if (newlyReceived.isNotEmpty()) {
                val targetIndex = newlyReceived.last().index
                withFrameNanos { }
                withFrameNanos { }
                if (targetIndex in orderedMessages.indices) {
                    listState.animateScrollToItem(targetIndex)
                }
            }
        }
        LaunchedEffect(chat.messages.size) {
            if (scrollAfterSend && orderedMessages.isNotEmpty()) {
                listState.animateScrollToItem(orderedMessages.lastIndex)
                scrollAfterSend = false
            }
        }
        Column(modifier.fillMaxSize().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Box(
                Modifier.weight(1f).fillMaxWidth().pointerInput(listState, onRefresh) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val startedAtNewestEnd = !listState.canScrollForward
                        var upwardDistance = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val deltaY = change.previousPosition.y - change.position.y
                            if (deltaY > 0f) upwardDistance += deltaY
                            else if (deltaY < 0f) upwardDistance = 0f
                        }
                        if (startedAtNewestEnd && upwardDistance >= with(density) { 72.dp.toPx() }) {
                            onRefresh()
                        }
                    }
                }
            ) {
            CompositionLocalProvider(LocalViewConfiguration provides longPressViewConfiguration) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(orderedMessages, key = { it.id }) { message ->
                    var messageCoordinates by remember(message.id) { mutableStateOf<LayoutCoordinates?>(null) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.incoming) Arrangement.Start else Arrangement.End) {
                        val messageCardColor = if (message.isProcessingError) Color(0xFF3B3024) else if (message.deleted) Color(0xFF293440) else if (message.incoming) Color(0xFF24384A) else Color(0xFF145B49)
                        Surface(
                            modifier = Modifier
                                .then(if (message.deleted || message.isProcessingError) Modifier.fillMaxWidth(0.88f) else Modifier.widthIn(max = 320.dp))
                                .onGloballyPositioned { messageCoordinates = it }
                                .pointerInput(message.id, message.deleted, message.incoming) {
                                    detectTapGestures(onLongPress = { localPoint ->
                                        val canOpen = !message.deleted && !message.isProcessingError && (message.incoming ||
                                            message.state in setOf(DeliveryState.SENT_TO_GMAIL, DeliveryState.DELIVERED))
                                        if (canOpen) {
                                            actionMenuPosition = messageCoordinates?.localToWindow(localPoint)?.round() ?: IntOffset.Zero
                                            actionMessage = message
                                        }
                                    })
                                }
                                .graphicsLayer(alpha = if (message.deleted) 0.75f else 1f),
                            color = messageCardColor.copy(alpha = chatCardAlphaPercent / 100f),
                            shape = RoundedCornerShape(
                                topStart = 18.dp, topEnd = 18.dp,
                                bottomStart = if (message.incoming) 5.dp else 18.dp,
                                bottomEnd = if (message.incoming) 18.dp else 5.dp
                            )
                        ) {
                            if (message.isProcessingError) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp)) {
                                    Text("⚠ Не удалось обработать сообщение", color = Color(0xFFFFC56B), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Spacer(Modifier.height(5.dp))
                                    Text(message.errorReason.ifBlank { message.text }, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp)
                                    Spacer(Modifier.height(5.dp))
                                    Text("Код: ${message.errorCode}", color = Color(0xFFD8C5A8), fontSize = 11.sp)
                                    Text("Gmail ID: ${message.gmailId}", color = Color(0xFFD8C5A8), fontSize = 10.sp)
                                    TextButton(
                                        onClick = { clipboardManager.setText(AnnotatedString(message.supportDiagnosticText())) },
                                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Скопировать для техподдержки", fontSize = 12.sp)
                                    }
                                }
                            } else if (message.deleted) {
                                Text(
                                    "delete messages",
                                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                    fontSize = 12.sp
                                )
                            } else {
                                Column(Modifier.widthIn(max = 320.dp).padding(start = 13.dp, end = 13.dp, top = 6.dp, bottom = 3.dp)) {
                                    if (message.recoveredFromSpam) Text("Сообщение попало в спам и было автоматически добавлено как не спам", fontSize = 10.sp, color = Color(0xFFFFC56B))
                                    Text(message.text, fontSize = chatMessageFontSizeSp.sp, lineHeight = (chatMessageFontSizeSp * 1.125f).sp)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val messageDate = Date(message.timestamp * 1000L)
                                        val dateTimeColor = Color.LightGray.copy(alpha = chatMetadataAlphaPercent / 100f)
                                        if (message.version > 1L) {
                                            Text("изменено   ", fontSize = chatMetadataFontSizeSp.sp, color = dateTimeColor, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                        }
                                        Text(
                                            SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(messageDate),
                                            fontSize = chatMetadataFontSizeSp.sp, color = dateTimeColor
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            SimpleDateFormat("HH:mm", Locale.getDefault()).format(messageDate),
                                            fontSize = chatMetadataFontSizeSp.sp, color = dateTimeColor
                                        )
                                        if (!message.incoming) {
                                            Spacer(Modifier.width(4.dp))
                                            val statusColor = if (message.state == DeliveryState.FAILED) Color(0xFFFF6262) else Color(0xFF55D68B)
                                            when (message.state) {
                                                DeliveryState.QUEUED -> Text("◷", color = statusColor, fontSize = 10.sp, lineHeight = 10.sp)
                                                DeliveryState.SENT_TO_GMAIL -> Text("✓", color = statusColor, fontSize = 10.sp, lineHeight = 10.sp)
                                                DeliveryState.DELIVERED -> Box(Modifier.width(13.dp).height(10.dp)) {
                                                    Text("✓", color = statusColor, fontSize = 10.sp, lineHeight = 10.sp)
                                                    Text("✓", modifier = Modifier.offset(x = 3.dp), color = statusColor, fontSize = 10.sp, lineHeight = 10.sp)
                                                }
                                                DeliveryState.FAILED -> Text("!", color = statusColor, fontSize = 10.sp, lineHeight = 10.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                }
            }
            }
            if (isSyncing) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    color = Navy.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(20.dp),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Проверяем почту...", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
            }
            if (editingMessage != null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Редактирование сообщения", modifier = Modifier.weight(1f), color = Blue, fontSize = 12.sp)
                    TextButton(onClick = { editingMessage = null; draft = TextFieldValue("") }) { Text("Отмена") }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF1A2A39),
                shape = RoundedCornerShape(26.dp)
            ) {
                Row(Modifier.padding(start = 12.dp, end = 5.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f).focusRequester(messageInputFocusRequester),
                        placeholder = { Text("Сообщение...") },
                        shape = RoundedCornerShape(22.dp),
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent
                        )
                    )
                    IconButton(onClick = {
                        if (draft.text.isNotBlank()) {
                            scrollAfterSend = true
                            onSend(draft.text.trim(), editingMessage)
                            draft = TextFieldValue("")
                            editingMessage = null
                        }
                    }, enabled = draft.text.isNotBlank()) {
                        Icon(Icons.Default.Send, "Отправить", tint = if (draft.text.isNotBlank()) Blue else Color.Gray)
                    }
                }
            }
        }
        actionMessage?.let { selectedMessage ->
            Popup(
                popupPositionProvider = object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: androidx.compose.ui.unit.IntRect,
                        windowSize: IntSize,
                        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                        popupContentSize: IntSize
                    ): IntOffset {
                        val margin = with(density) { 8.dp.roundToPx() }
                        val x = (actionMenuPosition.x - popupContentSize.width)
                            .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
                        val y = actionMenuPosition.y
                            .coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin))
                        return IntOffset(x, y)
                    }
                },
                onDismissRequest = { actionMessage = null }
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(Modifier.widthIn(max = 220.dp).padding(horizontal = 8.dp, vertical = 6.dp)) {
                        DropdownMenuItem(
                            text = { Text("Копировать") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            onClick = {
                                clipboardManager.setText(AnnotatedString(selectedMessage.text))
                                actionMessage = null
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        )
                        if (!selectedMessage.incoming) {
                            DropdownMenuItem(
                                text = { Text("Редактировать") },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    draft = TextFieldValue(selectedMessage.text, selection = TextRange(selectedMessage.text.length))
                                    editingMessage = selectedMessage
                                    actionMessage = null
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            )
                            DropdownMenuItem(
                                text = { Text("Удалить") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = {
                                    onDeleteMessage(selectedMessage)
                                    actionMessage = null
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun restartAutoSyncLoop() {
        autoSyncJob?.cancel()
        if (!::repo.isInitialized) return
        autoSyncJob = lifecycleScope.launch {
            while (isActive) {
                val seconds = if (appForeground) repo.openIntervalSeconds() else repo.minimizedIntervalSeconds()
                delay(seconds.coerceAtLeast(5) * 1000L)
                if (token != null) {
                    // Start the next poll interval only after the current sync finishes.
                    // This prevents timer ticks from piling up behind a slow request.
                    if (!syncInProgress) syncNow(source = "автоматическая")
                    while (isActive && syncInProgress) delay(250)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appForeground = true
        if (::repo.isInitialized) chats = repo.chats()
        restartAutoSyncLoop()
    }

    override fun onPause() {
        appForeground = false
        restartAutoSyncLoop()
        super.onPause()
    }

    private fun playDiagnosticClick() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 18)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 55)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 150)
        }
    }

    private fun playIncomingSound() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 180)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 250)
        }
    }

    override fun onDestroy() { autoSyncJob?.cancel(); super.onDestroy(); if (::auth.isInitialized) auth.close() }
}