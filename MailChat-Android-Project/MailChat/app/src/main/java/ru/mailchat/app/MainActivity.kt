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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.Security
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

private val Navy = Color(0xFF17283B)
private val Blue = Color(0xFF3390EC)

class MainActivity : ComponentActivity() {
    private lateinit var auth: McAuth
    private lateinit var repo: McRepository
    private lateinit var logger: McLogger
    private lateinit var identity: McIdentity
    private val gmail = GmailTransport()
    private val gmailAdmin = McGmailAdmin()
    private lateinit var mailManager: McMailManager
    private var mailMigrationBusy by mutableStateOf(false)
    private var mailMigrationText by mutableStateOf("")
    private var mailStatsBusy by mutableStateOf(false)
    private var mailStatsText by mutableStateOf("")
    private var mailDeleteBusy by mutableStateOf(false)
    private var mailDeleteProgress by mutableStateOf("")
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
        mailManager = McMailManager(gmailAdmin, repo, logger)
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
                token = null
                accountEmail = ""
                authResolving = false
                errorText = error.orEmpty()
                logger.error("Не удалось восстановить авторизацию: ${errorText.take(200)}")
                authChanged++
            }
        }
    }

    private fun loadProfileAndSetBaseline() {
        val t = token ?: return
        lifecycleScope.launch {
            try {
                logger.add("Запрос профиля Gmail")
                val profile = withContext(Dispatchers.IO) { gmail.profile(t) }
                accountEmail = profile.email
                repo.setEmail(profile.email)
                authResolving = false
                if (repo.baseline() == 0L) {
                    repo.setBaseline(System.currentTimeMillis() / 1000)
                    logger.add("Первичная точка синхронизации сохранена; старые письма не импортируются")
                }
                logger.add("Gmail подключён: ${profile.email}")
                try {
                    withContext(Dispatchers.IO) { mailManager.ensureLabel(t) }
                    if (!repo.migrationDone()) {
                        mailMigrationBusy = true
                        mailMigrationText = "Идёт первоначальная очистка старых писем MC..."
                        withContext(Dispatchers.IO) {
                            mailManager.migrateOldMessages(t, profile.email) { p ->
                                runOnUiThread {
                                    mailMigrationText = if (p.complete) {
                                        "Первоначальная очистка завершена. Проверено: ${p.processed}"
                                    } else {
                                        "Первоначальная очистка: проверено ${p.processed} из примерно ${p.estimate}"
                                    }
                                }
                            }
                        }
                    }
                } catch (mailError: Exception) {
                    mailMigrationText = "Ошибка первоначальной очистки: ${mailError.message.orEmpty().take(180)}"
                    logger.error("Ошибка управления письмами MC: ${mailMigrationText}")
                } finally {
                    mailMigrationBusy = false
                }
                if (repo.deleteJobConfirmed() && auth.hasPermanentDeleteScope()) {
                    runCatching {
                        mailDeleteBusy = true
                        withContext(Dispatchers.IO) {
                            mailManager.resumeConfirmedDelete(t) { done, total ->
                                runOnUiThread { mailDeleteProgress = "Удалено ${done} из ${total}" }
                            }
                        }
                    }.onFailure { logger.error("Ошибка продолжения удаления MC: ${it.message.orEmpty().take(200)}") }
                    mailDeleteBusy = false
                }
                authChanged++
                syncNow(source = "после авторизации")
            } catch (e: Exception) {
                errorText = e.message ?: "Ошибка Gmail API"
                logger.error("Ошибка подключения Gmail: ${e.javaClass.simpleName}: ${errorText.take(200)}")
                token = null
                accountEmail = ""
                authResolving = false
                authChanged++
            }
        }
    }

    private fun sendMessage(to: String, text: String, done: (String?) -> Unit) {
        val t = token ?: run { done("Сначала подключите Gmail"); return }
        lifecycleScope.launch {
            try {
                logger.add("Начата отправка сообщения получателю: $to")
                val id = withContext(Dispatchers.IO) {
                    val pair = identity.keyPair()
                    val ownProfile = runCatching { gmail.ownProfile(t) }.onFailure {
                        logger.error("Google profile: получение не удалось: ${it.javaClass.simpleName}")
                    }.getOrNull()
                    val sentId = gmail.send(t, accountEmail.ifBlank { repo.myEmail() }, to, text, to.lowercase(), identity.publicKey(), ownProfile?.name, ownProfile?.photoUrl) { bytes -> McCrypto.sign(pair, bytes) }
                    runCatching {
                        val labelId = mailManager.ensureLabel(t)
                        gmailAdmin.modifyMessages(t, listOf(sentId), add = listOf(labelId))
                    }.onFailure { logger.error("Исходящее письмо отправлено, но метка MC не установлена: ${it.message.orEmpty().take(160)}") }
                    sentId
                }
                logger.add("Сообщение отправлено в Gmail: $id")
                done(null)
            } catch (e: Exception) {
                logger.error("Ошибка отправки: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(200)}")
                done(e.message ?: "Ошибка отправки")
            }
        }
    }

    private fun syncNow(source: String = "ручная", done: (String) -> Unit = {}) {
        if (syncInProgress) {
            done("Проверка уже выполняется")
            return
        }
        val t = token ?: run { done("Нет подключения Gmail"); return }
        if (!repo.acquireSyncLock()) { done("Проверка уже выполняется"); return }
        val since = repo.baseline().coerceAtLeast(1L)
        syncInProgress = true
        logger.info("Начата $source проверка почты. Точка синхронизации: $since")

        lifecycleScope.launch {
            var result = "Проверка завершена"
            try {
                logger.add("Запрос списка входящих сообщений Gmail")
                val incoming = withContext(Dispatchers.IO) {
                    gmail.list(t, "after:$since {in:inbox in:spam}", 50)
                }
                logger.add("Gmail вернул сообщений: ${incoming.size}")

                var accepted = 0
                var skipped = 0
                val archiveIds = mutableListOf<String>()

                for (mail in incoming) {
                    if (repo.isProcessed(mail.id)) {
                        skipped++
                        continue
                    }

                    logger.debug("Обработка письма Gmail ID: ${mail.id}")

                    val mcBlock = mail.body.substringAfter("-----BEGIN MC DATA-----", "").substringBefore("-----END MC DATA-----")
                    logger.debug("MC DATA extracted: markers=${mail.body.contains("-----BEGIN MC DATA-----") && mail.body.contains("-----END MC DATA-----")}, signature=${mcBlock.contains("X-MC-Signature:", ignoreCase = true)}")
                    val signatureLines = mcBlock.lineSequence()
                        .filter { it.contains("signature", ignoreCase = true) }
                        .toList()
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
                        if (mail.subject == "[MC] Message") {
                            logger.error("Сообщение MC отклонено. Gmail ID: ${mail.id}. Причина: ${verification.reason}")
                        }
                        continue
                    }
                    logger.info("Криптографическая подпись подтверждена. MC messageId: ${parsed.messageId}")
                    val current = repo.chats().toMutableList()
                    if (current.any { chat -> chat.messages.any { it.id == parsed.messageId } }) {
                        archiveIds += mail.id
                        skipped++
                        continue
                    }

                    val index = current.indexOfFirst { it.email.equals(parsed.sender, true) }
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

                    val incomingName = parsed.senderName.trim().takeIf { it.isNotBlank() }
                    val incomingAvatar = parsed.senderAvatarUrl.trim().takeIf { it.isNotBlank() }
                    val needsContactProfile = incomingName == null || incomingAvatar == null
                    val contactProfile = if (needsContactProfile) {
                        try {
                            withContext(Dispatchers.IO) { gmail.contactProfile(t, parsed.sender) }
                        } catch (e: Exception) {
                            logger.error("People API: ошибка получения профиля: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(500)}")
                            null
                        }
                    } else null
                    logger.info("Профиль контакта: lookup=${if (needsContactProfile) "выполнен" else "пропущен"}, result=${contactProfile != null}, namePresent=${!contactProfile?.name.isNullOrBlank()}, photoPresent=${!contactProfile?.photoUrl.isNullOrBlank()}, senderHeaderNamePresent=${!incomingName.isNullOrBlank()}")
                    val resolvedName = incomingName ?: contactProfile?.name
                    val resolvedAvatar = incomingAvatar ?: contactProfile?.photoUrl
                    if (index >= 0) {
                        val existing = current[index]
                        current[index] = existing.copy(
                            title = if (!existing.nameManuallySet && resolvedName != null) resolvedName else existing.title,
                            senderName = resolvedName ?: existing.senderName,
                            avatarUrl = resolvedAvatar ?: existing.avatarUrl,
                            messages = existing.messages + message
                        )
                    } else {
                        current.add(McChat(parsed.sender, title = resolvedName ?: parsed.sender,
                            messages = listOf(message), senderName = resolvedName,
                            avatarUrl = resolvedAvatar))
                    }

                    repo.save(current)
                    archiveIds += mail.id
                    val savedChat = current.firstOrNull { it.email.equals(parsed.sender, true) }
                    logger.info("Профиль в локальной модели: сохранение выполнено, namePresent=${!savedChat?.senderName.isNullOrBlank()}, titlePresent=${!savedChat?.title.isNullOrBlank()}, avatarPresent=${!savedChat?.avatarUrl.isNullOrBlank()}, nameSource=${when { incomingName != null -> "подписанный MC"; contactProfile?.name != null -> "People API"; else -> "нет" }}")
                    accepted++
                    logger.info("Новое сообщение MC сохранено в локальном чате")
                }

                if (archiveIds.isNotEmpty()) {
                    val labelId = withContext(Dispatchers.IO) { mailManager.ensureLabel(t) }
                    withContext(Dispatchers.IO) {
                        gmailAdmin.modifyMessages(t, archiveIds, add = listOf(labelId), remove = listOf("INBOX", "SPAM"))
                    }
                    archiveIds.distinct().forEach { repo.markProcessed(it) }
                    logger.info("Обработанные письма MC помечены и архивированы: ${archiveIds.distinct().size}")
                }

                chats = repo.chats()
                logger.info("Синхронизация завершена пакетно: 1 запрос списка + 1 batch-запрос содержимого, независимо от числа чатов")
                if (accepted > 0) playIncomingSound()
                logger.add("Проверка Gmail завершена. Получено: $accepted; пропущено: $skipped; всего проверено: ${incoming.size}")
                result = "Проверка завершена. Новых сообщений: $accepted"
            } catch (e: Exception) {
                result = e.message ?: "Ошибка синхронизации"
                logger.error("Ошибка синхронизации: ${e.javaClass.simpleName}: ${result.take(250)}")
            } finally {
                repo.releaseSyncLock()
                syncInProgress = false
                authChanged++
                done(result)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun McApp() {
        var selected by remember {
            mutableStateOf(repo.lastChat().takeIf { saved -> saved.isNotBlank() && chats.any { it.email.equals(saved, true) } })
        }
        var settings by remember { mutableStateOf(false) }
        var logs by remember { mutableStateOf(false) }
        var showNew by remember { mutableStateOf(false) }
        var editContactProfile by remember { mutableStateOf(false) }
        var contactName by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("") }
        var deleteSnapshot by remember { mutableStateOf<List<String>>(emptyList()) }
        var showDeleteConfirm by remember { mutableStateOf(false) }
        var logText by remember { mutableStateOf(logger.read()) }
        var logSize by remember { mutableLongStateOf(logger.sizeBytes()) }
        var openSeconds by remember { mutableStateOf(repo.openIntervalSeconds().toString()) }
        var minimizedSeconds by remember { mutableStateOf(repo.minimizedIntervalSeconds().toString()) }
        var closedSeconds by remember { mutableStateOf(repo.closedIntervalSeconds().toString()) }
        var diagnosticClick by remember { mutableStateOf(repo.diagnosticClickEnabled()) }
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
                diagnosticClick
            )
            McSyncScheduler.schedule(this@MainActivity, repo.closedIntervalSeconds())
            restartAutoSyncLoop()
            logger.add("Настройки интервалов синхронизации применены")
        }
        val clipboardManager = remember {
            getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        }
        val refresh = authChanged
        BackHandler(enabled = settings || logs || selected != null) {
            if (settings) applySyncSettings()
            if (logs) logs = false else { settings = false; selected = null; repo.setLastChat(null) }
        }
        MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Color(0xFF101820), surface = Color(0xFF172330), onSurface = Color(0xFFEAF0F6))) {
            Scaffold(
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
                                    val deletionChat = chats.firstOrNull { it.email.equals(deletion.key, true) }
                                    val deletionName = deletionChat?.senderName?.takeIf { it.isNotBlank() }
                                        ?: deletionChat?.title?.takeIf { !it.equals(deletion.key, true) }
                                        ?: deletion.key
                                    Text("Удаление чата с ${deletionName} через $secondsLeft", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
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
                    Column {
                        Text(
                            if (logs) "Логирование" else if (settings) "Настройки"
                            else if (authResolving) "Подключение..." else selectedChat?.title ?: "MC",
                            fontWeight = FontWeight.Bold
                        )
                        val subtitle = if (selectedChat != null) {
                            selectedChat.email.takeIf { !selectedChat.title.equals(selectedChat.email, true) }.orEmpty()
                        } else if (token != null) accountEmail.ifBlank { repo.myEmail() } else "Мессенджер на Gmail"
                        if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }, navigationIcon = {
                    if (selected != null || settings || logs) IconButton(onClick = {
                        if (settings) applySyncSettings()
                        selected = null; repo.setLastChat(null); settings = false; logs = false
                    }) { Icon(Icons.Default.ArrowBack, "Назад") }
                }, actions = {
                    if (!settings && !logs && selected == null) {
                        IconButton(onClick = { showNew = true }) { Icon(Icons.Default.Add, "Новый чат") }
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
                    settings -> Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Аккаунт Google", fontWeight = FontWeight.Bold)
                        Text(if (token != null) accountEmail.ifBlank { repo.myEmail() } else "Не подключён")
                        if (token == null) {
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
                        if (token != null) OutlinedButton(onClick = { auth.disconnect(); token = null; accountEmail = ""; logger.add("Аккаунт отключён"); authChanged++ }) { Text("Отключить аккаунт") }
                        Text("После обновления отключите и снова подключите Google-аккаунт, чтобы разрешить MC получать имя и фото профиля отправителя.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Text("Ручная проверка", fontWeight = FontWeight.Bold)
                        Button(
                            onClick = { syncNow { status = it } },
                            enabled = token != null && !syncInProgress
                        ) { Text(if (syncInProgress) "Проверяем..." else "Проверить новые сообщения") }
                        Text("Старые письма не импортируются. Точка начала синхронизации устанавливается при первом подключении.", fontSize = 13.sp)
                        HorizontalDivider()
                        Text("Управление письмами MC", fontWeight = FontWeight.Bold)
                        Text(
                            mailMigrationText.ifBlank {
                                if (repo.migrationDone()) "Первоначальная очистка старых писем MC завершена."
                                else "Первоначальная очистка ещё не завершена."
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                val currentToken = token
                                if (currentToken != null && !mailMigrationBusy) {
                                    mailMigrationBusy = true
                                    lifecycleScope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                mailManager.migrateOldMessages(currentToken, accountEmail.ifBlank { repo.myEmail() }) { p ->
                                                    runOnUiThread {
                                                        mailMigrationText = if (p.complete) "Очистка завершена. Проверено: " + p.processed
                                                        else "Очистка: проверено " + p.processed + " из примерно " + p.estimate
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            mailMigrationText = "Ошибка очистки: " + e.message.orEmpty().take(180)
                                            logger.error("Ошибка повторной миграции MC: " + e.message.orEmpty().take(180))
                                        } finally {
                                            mailMigrationBusy = false
                                        }
                                    }
                                }
                            },
                            enabled = token != null && !mailMigrationBusy && !repo.migrationDone()
                        ) { Text(if (mailMigrationBusy) "Очистка..." else "Продолжить первоначальную очистку") }
                        Button(
                            onClick = {
                                val currentToken = token
                                if (currentToken != null && !mailStatsBusy) {
                                    mailStatsBusy = true
                                    mailStatsText = "Получаем статистику Gmail..."
                                    lifecycleScope.launch {
                                        try {
                                            val stats = withContext(Dispatchers.IO) { mailManager.statistics(currentToken) }
                                            mailStatsText = buildString {
                                                append("Всего уникальных писем: ").append(stats.uniqueMessages).append("\n")
                                                stats.labels.forEach { label ->
                                                    append(label.name).append(": ").append(label.total)
                                                        .append(" (непрочитанных: ").append(label.unread).append(")\n")
                                                }
                                            }
                                        } catch (e: Exception) {
                                            mailStatsText = "Ошибка получения статистики: " + e.message.orEmpty().take(180)
                                        } finally {
                                            mailStatsBusy = false
                                        }
                                    }
                                }
                            },
                            enabled = token != null && !mailStatsBusy
                        ) { Text(if (mailStatsBusy) "Получаем статистику..." else "Статистика всех папок") }
                        if (mailStatsText.isNotBlank()) {
                            Text(mailStatsText, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            onClick = {
                                val currentToken = token
                                if (currentToken == null || mailDeleteBusy) return@Button
                                if (!auth.hasPermanentDeleteScope()) {
                                    mailDeleteProgress = "Для этой операции требуется отдельное разрешение Google на полный доступ к Gmail. Сейчас откроется повторное согласие."
                                    try {
                                        auth.start(this@MainActivity, includePermanentDeleteScope = true)
                                    } catch (e: Exception) {
                                        mailDeleteProgress = "Не удалось запросить разрешение: " + e.message.orEmpty().take(160)
                                    }
                                } else {
                                    mailDeleteBusy = true
                                    lifecycleScope.launch {
                                        try {
                                            deleteSnapshot = withContext(Dispatchers.IO) { mailManager.previewDelete(currentToken) }
                                            showDeleteConfirm = deleteSnapshot.isNotEmpty()
                                            mailDeleteProgress = if (deleteSnapshot.isEmpty()) "Писем с меткой MC не найдено."
                                                else "Найдено писем MC: " + deleteSnapshot.size
                                        } catch (e: Exception) {
                                            mailDeleteProgress = "Ошибка поиска писем MC: " + e.message.orEmpty().take(180)
                                        } finally {
                                            mailDeleteBusy = false
                                        }
                                    }
                                }
                            },
                            enabled = token != null && !mailDeleteBusy
                        ) { Text(if (mailDeleteBusy) "Подготовка..." else "Удалить сообщения MC безвозвратно") }
                        if (mailDeleteProgress.isNotBlank()) {
                            Text(mailDeleteProgress, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                        Text("Логирование", fontWeight = FontWeight.Bold)
                        Button(onClick = { logs = true; logText = logger.read(); logSize = logger.sizeBytes() }) { Text("Открыть журнал") }
                        Text("Безопасность: текст первой версии не зашифрован. Закрытый ключ подписи хранится в зашифрованных локальных настройках.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        Text("Версия: ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Номер сборки: ${BuildConfig.VERSION_CODE}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (errorText.isNotBlank()) Text(errorText, color = MaterialTheme.colorScheme.error)
                        if (status.isNotBlank()) Text(status)
                    }
                    selected != null -> {
                        val email = selected!!
                        val chat = chats.firstOrNull { it.email.equals(email, true) } ?: McChat(email)
                        Conversation(chat, Modifier.padding(padding), isSyncing = syncInProgress, onRefresh = { syncNow(source = "жест обновления переписки") }, onSend = { body ->
                            val outgoing = McMessage(chatId = email.lowercase(), sender = accountEmail, recipient = email, text = body, incoming = false, state = DeliveryState.QUEUED)
                            chats = (chats.filterNot { it.email.equals(email, true) } + chat.copy(messages = chat.messages + outgoing)).sortedByDescending { it.messages.maxOfOrNull(McMessage::timestamp) ?: 0L }
                            repo.save(chats)
                            sendMessage(email, body) { error ->
                                val updated = chats.map { c -> if (c.email.equals(email, true)) c.copy(messages = c.messages.map { m -> if (m.id == outgoing.id) m.copy(state = if (error == null) DeliveryState.SENT_TO_GMAIL else DeliveryState.FAILED) else m }) else c }
                                chats = updated; repo.save(chats); status = error ?: "Принято Gmail"; authChanged++
                            }
                        })
                    }
                    else -> Column(Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
                        if (token == null) {
                            Text("Добро пожаловать в MC", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("Подключите Gmail, чтобы начать обмен сообщениями.")
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { settings = true }) { Text("Подключить Gmail") }
                        } else {
                            val chatListState = rememberLazyListState()
                            var bottomPullDistance by remember { mutableFloatStateOf(0f) }
                            val density = LocalDensity.current
                            val bottomRefreshConnection = remember(chatListState) {
                                object : NestedScrollConnection {
                                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                                        if (!chatListState.canScrollForward && available.y > 0f) bottomPullDistance = (bottomPullDistance + available.y).coerceAtMost(with(density) { 120.dp.toPx() })
                                        else if (available.y != 0f) bottomPullDistance = 0f
                                        return Offset.Zero
                                    }
                                    override suspend fun onPreFling(available: Velocity): Velocity {
                                        if (bottomPullDistance >= with(density) { 72.dp.toPx() } && !chatListState.canScrollForward) { bottomPullDistance = 0f; syncNow(source = "жест обновления списка чатов") }
                                        else bottomPullDistance = 0f
                                        return Velocity.Zero
                                    }
                                }
                            }
                            Column(Modifier.fillMaxSize()) {
                                if (syncInProgress || bottomPullDistance > 0f) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    if (syncInProgress) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    else Text("Потяните вниз для проверки новых сообщений", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (syncInProgress) Spacer(Modifier.width(8.dp))
                                    if (syncInProgress) Text("Проверяем почту...", fontSize = 12.sp)
                                }
                                LazyColumn(state = chatListState, modifier = Modifier.weight(1f).nestedScroll(bottomRefreshConnection), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                                items(chats.filterNot { pendingDeleteDeadlines.keys.any { pending -> pending.equals(it.email, true) } }.sortedByDescending { it.messages.maxOfOrNull(McMessage::timestamp) ?: 0L }, key = { it.email }) { chat ->
                                    val dragOffset = remember(chat.email) { mutableFloatStateOf(0f) }
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
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
                                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                            if (!chat.avatarUrl.isNullOrBlank()) {
                                                AsyncImage(model = chat.avatarUrl, contentDescription = "Фото контакта",
                                                    modifier = Modifier.size(50.dp).clip(CircleShape).background(Blue),
                                                    contentScale = ContentScale.Crop)
                                            } else {
                                                Box(Modifier.size(50.dp).background(Blue, CircleShape), contentAlignment = Alignment.Center) {
                                                    Text(chat.title.take(1).uppercase(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                            Spacer(Modifier.width(13.dp))
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(chat.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color(0xFFF1F5F9), maxLines = 1)
                                                Text(chat.messages.lastOrNull()?.text ?: "", color = Color(0xFF9EADBC).copy(alpha = 0.85f), maxLines = 1, fontSize = 13.sp)
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(chat.messages.lastOrNull()?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.timestamp * 1000L)) } ?: "", color = Color(0xFF8FA1B3), fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
            if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { if (!mailDeleteBusy) showDeleteConfirm = false },
                title = { Text("Безвозвратно удалить письма MC?") },
                text = {
                    Text("Будет удалено без возможности восстановления: " + deleteSnapshot.size +
                        " писем с меткой MC. Локальные чаты и сообщения на телефоне останутся. Обычная почта не затрагивается.")
                },
                confirmButton = {
                    TextButton(
                        enabled = !mailDeleteBusy,
                        onClick = {
                            val currentToken = token ?: return@TextButton
                            showDeleteConfirm = false
                            mailDeleteBusy = true
                            mailDeleteProgress = "Удаление..."
                            lifecycleScope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        mailManager.deleteConfirmedSnapshot(currentToken, deleteSnapshot) { done, total ->
                                            runOnUiThread { mailDeleteProgress = "Удалено " + done + " из " + total }
                                        }
                                    }
                                    mailDeleteProgress = if (result.changed) {
                                        "Список писем изменился после подсчёта. Ничего не удалено — выполните поиск заново."
                                    } else {
                                        "Безвозвратно удалено писем MC: " + result.deleted
                                    }
                                    deleteSnapshot = emptyList()
                                    mailStatsText = ""
                                } catch (e: Exception) {
                                    mailDeleteProgress = "Удаление прервано; прогресс сохранён: " + e.message.orEmpty().take(180)
                                    logger.error("Ошибка безвозвратного удаления MC: " + e.message.orEmpty().take(200))
                                } finally {
                                    mailDeleteBusy = false
                                }
                            }
                        }
                    ) { Text("Удалить безвозвратно") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена") }
                }
            )
        }
        if (showNew) {
                var email by remember { mutableStateOf("") }
                var displayName by remember { mutableStateOf("") }
                AlertDialog(onDismissRequest = { showNew = false }, title = { Text("Новый пользователь") }, text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = displayName, onValueChange = { displayName = it }, label = { Text("Имя") }, singleLine = true)
                        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Gmail собеседника") }, singleLine = true)
                    }
                }, confirmButton = { TextButton(onClick = {
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
                }) { Text("Создать") } }, dismissButton = { TextButton(onClick = { showNew = false }) { Text("Отмена") } })
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

    @Composable private fun Conversation(chat: McChat, modifier: Modifier, isSyncing: Boolean, onRefresh: () -> Unit, onSend: (String) -> Unit) {
        var draft by remember { mutableStateOf("") }
        val listState = rememberLazyListState()
        var topPullDistance by remember(chat.email) { mutableFloatStateOf(0f) }
        val density = LocalDensity.current
        val imeInsets = WindowInsets.ime
        val topRefreshConnection = remember(listState, onRefresh) {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (!listState.canScrollBackward && available.y < 0f) topPullDistance = (topPullDistance - available.y).coerceAtMost(with(density) { 120.dp.toPx() })
                    else if (available.y != 0f) topPullDistance = 0f
                    return Offset.Zero
                }
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (topPullDistance >= with(density) { 72.dp.toPx() } && !listState.canScrollBackward) { topPullDistance = 0f; onRefresh() }
                    else topPullDistance = 0f
                    return Velocity.Zero
                }
            }
        }
        var scrollAfterSend by remember(chat.email) { mutableStateOf(false) }
        LaunchedEffect(chat.email) { if (chat.messages.isNotEmpty()) listState.scrollToItem(chat.messages.lastIndex) }
        LaunchedEffect(chat.email) {
            snapshotFlow { imeInsets.getBottom(density) }.collect {
                val lastIndex = chat.messages.lastIndex
                if (lastIndex >= 0) listState.scrollToItem(lastIndex)
            }
        }
        LaunchedEffect(chat.messages.size) {
            if (scrollAfterSend && chat.messages.isNotEmpty()) {
                listState.animateScrollToItem(chat.messages.lastIndex)
                scrollAfterSend = false
            }
        }
        Column(modifier.fillMaxSize().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (isSyncing || topPullDistance > 0f) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (isSyncing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Потяните вверх для проверки новых сообщений", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isSyncing) Spacer(Modifier.width(8.dp))
                if (isSyncing) Text("Проверяем почту...", fontSize = 12.sp)
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().nestedScroll(topRefreshConnection)) {
                items(chat.messages, key = { it.id }) { message ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.incoming) Arrangement.Start else Arrangement.End) {
                        Surface(
                            color = if (message.incoming) Color(0xFF24384A) else Color(0xFF145B49),
                            shape = RoundedCornerShape(
                                topStart = 18.dp, topEnd = 18.dp,
                                bottomStart = if (message.incoming) 5.dp else 18.dp,
                                bottomEnd = if (message.incoming) 18.dp else 5.dp
                            )
                        ) {
                            Column(Modifier.widthIn(max = 320.dp).padding(horizontal = 13.dp, vertical = 10.dp)) {
                                if (message.recoveredFromSpam) Text("Сообщение попало в спам и было автоматически добавлено как не спам", fontSize = 10.sp, color = Color(0xFFFFC56B))
                                Text(message.text)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault())
                                            .format(Date(message.timestamp * 1000L)),
                                        fontSize = 10.sp,
                                        color = Color.LightGray
                                    )
                                    if (!message.incoming) {
                                        Spacer(Modifier.width(4.dp))
                                        val statusColor = if (message.state == DeliveryState.FAILED) Color(0xFFFF6262) else Color(0xFF55D68B)
                                        when (message.state) {
                                            DeliveryState.QUEUED -> Text("◷", color = statusColor, fontSize = 7.sp, lineHeight = 8.sp)
                                            DeliveryState.SENT_TO_GMAIL -> Text("✓", color = statusColor, fontSize = 7.sp, lineHeight = 8.sp)
                                            DeliveryState.DELIVERED -> Box(Modifier.width(10.dp).height(9.dp)) {
                                                Text("✓", color = statusColor, fontSize = 7.sp, lineHeight = 8.sp)
                                                Text("✓", modifier = Modifier.offset(x = 3.dp), color = statusColor, fontSize = 7.sp, lineHeight = 8.sp)
                                            }
                                            DeliveryState.FAILED -> Text("!", color = statusColor, fontSize = 7.sp, lineHeight = 8.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(7.dp))
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
                        modifier = Modifier.weight(1f),
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
                    IconButton(onClick = { if (draft.isNotBlank()) { scrollAfterSend = true; onSend(draft.trim()); draft = "" } }, enabled = draft.isNotBlank()) {
                        Icon(Icons.Default.Send, "Отправить", tint = if (draft.isNotBlank()) Blue else Color.Gray)
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
