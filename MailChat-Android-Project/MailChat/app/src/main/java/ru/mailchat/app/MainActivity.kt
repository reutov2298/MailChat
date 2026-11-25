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
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
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

    private fun sendMessage(to: String, message: McMessage, messageType: String, done: (String?) -> Unit) {
        val t = token ?: run { done("Сначала подключите Gmail"); return }
        lifecycleScope.launch {
            try {
                logger.add("Начата отправка события $messageType получателю: $to")
                val id = withContext(Dispatchers.IO) {
                    val pair = identity.keyPair()
                    gmail.send(
                        t, accountEmail.ifBlank { repo.myEmail() }, to, message.text,
                        message.chatId, identity.publicKey(),
                        messageId = message.id, sequence = message.sequence,
                        messageVersion = message.version, messageType = messageType,
                        messageTimestamp = message.timestamp
                    ) { bytes -> McCrypto.sign(pair, bytes) }
                }
                logger.add("Событие MC отправлено в Gmail: $id; messageId=${message.id}; sequence=${message.sequence}; version=${message.version}")
                done(null)
            } catch (e: Exception) {
                logger.error("Ошибка отправки: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(200)}")
                done(e.message ?: "Ошибка отправки")
            }
        }
    }

    private val syncQueue = mutableListOf<Pair<String, (String) -> Unit>>()
    private var syncQueueDraining = false

    private fun syncNow(source: String = "ручная", done: (String) -> Unit = {}) {
        if (token == null) {
            done("Нет подключения Gmail")
            return
        }
        syncQueue.add(source to done)
        if (syncQueueDraining) return

        syncQueueDraining = true
        syncInProgress = true
        lifecycleScope.launch {
            try {
                while (syncQueue.isNotEmpty()) {
                    val request = syncQueue.removeAt(0)
                    performSync(request.first, request.second)
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
        logger.info("Начата $source проверка почты. Точка синхронизации: $since")

        var result = "Проверка завершена"
            try {
                logger.add("Запрос списка входящих сообщений Gmail")
                val incoming = withContext(Dispatchers.IO) {
                    gmail.list(t, "after:$since {in:inbox in:spam}", 50)
                }
                logger.add("Gmail вернул сообщений: ${incoming.size}")

                var accepted = 0
                var skipped = 0

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
                    if (mail.spam) {
                        withContext(Dispatchers.IO) { gmail.moveOutOfSpam(t, mail.id) }
                        logger.info("Подтверждённое сообщение MC возвращено из спама")
                    }

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
                                text = message.text, version = parsed.messageVersion,
                                sequence = parsed.sequence, deleted = message.deleted
                            )
                            current[index] = current[index].copy(messages = repo.orderedMessages(updatedMessages))
                        } else {
                            repo.markProcessed(mail.id)
                            skipped++
                            continue
                        }
                    }

                    val incomingName = mail.senderName.trim().takeIf { it.isNotBlank() }
                    val needsContactProfile = index < 0 || current[index].avatarUrl.isNullOrBlank() ||
                        (incomingName == null && current[index].senderName.isNullOrBlank())
                    val contactProfile = if (needsContactProfile) {
                        try {
                            withContext(Dispatchers.IO) { gmail.contactProfile(t, parsed.sender) }
                        } catch (e: Exception) {
                            logger.debug("Профиль контакта недоступен для ${parsed.sender}: ${e.message.orEmpty().take(120)}")
                            null
                        }
                    } else null
                    val resolvedName = incomingName ?: contactProfile?.name
                    if (index >= 0) {
                        val existing = current[index]
                        current[index] = existing.copy(
                            title = if (!existing.nameManuallySet && resolvedName != null) resolvedName else existing.title,
                            senderName = resolvedName ?: existing.senderName,
                            avatarUrl = contactProfile?.photoUrl ?: existing.avatarUrl,
                            messages = if (existingIndex >= 0) existing.messages else repo.orderedMessages(existing.messages + message)
                        )
                    } else {
                        current.add(McChat(parsed.sender, title = resolvedName ?: parsed.sender,
                            messages = listOf(message), senderName = resolvedName,
                            avatarUrl = contactProfile?.photoUrl))
                    }

                    repo.save(current)
                    repo.markProcessed(mail.id)
                    accepted++
                    logger.info("Новое сообщение MC сохранено в локальном чате")
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
                        Text("Для загрузки имён и фотографий из Google Контактов после обновления переподключите аккаунт: отключите его и подключите снова.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            onRefresh = { syncNow(source = "жест обновления переписки") },
                            onSend = { body, editing ->
                                val outgoing = if (editing == null) {
                                    McMessage(
                                        chatId = email.lowercase(), sender = accountEmail, recipient = email,
                                        text = body, incoming = false, state = DeliveryState.QUEUED,
                                        sequence = repo.nextSequence(email.lowercase(), accountEmail)
                                    )
                                } else editing.copy(
                                    text = body, version = editing.version + 1L, deleted = false,
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
                            var bottomPullDistance by remember { mutableFloatStateOf(0f) }
                            val density = LocalDensity.current
                            val bottomRefreshConnection = remember(chatListState) {
                                object : NestedScrollConnection {
                                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                                        if (!chatListState.canScrollBackward && available.y > 0f) bottomPullDistance = (bottomPullDistance + available.y).coerceAtMost(with(density) { 120.dp.toPx() })
                                        else if (available.y != 0f) bottomPullDistance = 0f
                                        return Offset.Zero
                                    }
                                    override suspend fun onPreFling(available: Velocity): Velocity {
                                        if (bottomPullDistance >= with(density) { 72.dp.toPx() } && !chatListState.canScrollBackward) { bottomPullDistance = 0f; syncNow(source = "жест обновления списка чатов") }
                                        else bottomPullDistance = 0f
                                        return Velocity.Zero
                                    }
                                }
                            }
                            Box(Modifier.fillMaxSize()) {
                                LazyColumn(state = chatListState, modifier = Modifier.fillMaxSize().nestedScroll(bottomRefreshConnection), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
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

    @Composable private fun Conversation(
        chat: McChat,
        modifier: Modifier,
        isSyncing: Boolean,
        onRefresh: () -> Unit,
        onSend: (String, McMessage?) -> Unit,
        onDeleteMessage: (McMessage) -> Unit
    ) {
        var draft by remember(chat.email) { mutableStateOf("") }
        var editingMessage by remember(chat.email) { mutableStateOf<McMessage?>(null) }
        var actionMessage by remember(chat.email) { mutableStateOf<McMessage?>(null) }
        val listState = rememberLazyListState()
        val density = LocalDensity.current
        val imeInsets = WindowInsets.ime
        val baseViewConfiguration = LocalViewConfiguration.current
        val longPressViewConfiguration = remember(baseViewConfiguration) {
            object : ViewConfiguration by baseViewConfiguration {
                override val longPressTimeoutMillis: Long = 800L
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
                items(chat.messages.sortedWith(compareBy<McMessage> { it.timestamp }.thenBy { it.sequence }.thenBy { it.id }), key = { it.id }) { message ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.incoming) Arrangement.Start else Arrangement.End) {
                        Surface(
                            modifier = Modifier
                                .then(if (message.deleted) Modifier.fillMaxWidth(0.72f) else Modifier.widthIn(max = 320.dp))
                                .pointerInput(message.id, message.deleted, message.incoming) {
                                    detectTapGestures(onLongPress = {
                                        if (!message.incoming && !message.deleted &&
                                            message.state in setOf(DeliveryState.SENT_TO_GMAIL, DeliveryState.DELIVERED)
                                        ) actionMessage = message
                                    })
                                }
                                .graphicsLayer(alpha = if (message.deleted) 0.75f else 1f),
                            color = if (message.deleted) Color(0xFF293440) else if (message.incoming) Color(0xFF24384A) else Color(0xFF145B49),
                            shape = RoundedCornerShape(
                                topStart = 18.dp, topEnd = 18.dp,
                                bottomStart = if (message.incoming) 5.dp else 18.dp,
                                bottomEnd = if (message.incoming) 18.dp else 5.dp
                            )
                        ) {
                            if (message.deleted) {
                                Text(
                                    "delete messages",
                                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                    fontSize = 12.sp
                                )
                            } else {
                                Column(Modifier.widthIn(max = 320.dp).padding(horizontal = 13.dp, vertical = 10.dp)) {
                                    if (message.recoveredFromSpam) Text("Сообщение попало в спам и было автоматически добавлено как не спам", fontSize = 10.sp, color = Color(0xFFFFC56B))
                                    Text(message.text)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault()).format(Date(message.timestamp * 1000L)),
                                            fontSize = 10.sp, color = Color.LightGray
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
                    TextButton(onClick = { editingMessage = null; draft = "" }) { Text("Отмена") }
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
                    IconButton(onClick = {
                        if (draft.isNotBlank()) {
                            scrollAfterSend = true
                            onSend(draft.trim(), editingMessage)
                            draft = ""
                            editingMessage = null
                        }
                    }, enabled = draft.isNotBlank()) {
                        Icon(Icons.Default.Send, "Отправить", tint = if (draft.isNotBlank()) Blue else Color.Gray)
                    }
                }
            }
        }
        actionMessage?.let { selectedMessage ->
            AlertDialog(
                onDismissRequest = { actionMessage = null },
                title = { Text("Сообщение") },
                text = { Text("Что сделать с этим сообщением?") },
                confirmButton = {
                    Row {
                        TextButton(onClick = {
                            editingMessage = selectedMessage
                            draft = selectedMessage.text
                            actionMessage = null
                        }) { Text("Редактировать") }
                        TextButton(onClick = {
                            onDeleteMessage(selectedMessage)
                            actionMessage = null
                        }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                    }
                },
                dismissButton = { TextButton(onClick = { actionMessage = null }) { Text("Отмена") } }
            )
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
