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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        return auth.handle(intent) { access, error ->
            if (access != null) {
                token = access
                logger.add("Авторизация Google выполнена")
                authChanged++
                loadProfileAndSetBaseline()
            } else if (error != null) {
                errorText = error
                logger.add("Ошибка OAuth: $errorText")
                authChanged++
            }
        }
    }

    private fun refreshToken() {
        auth.accessToken { access, error ->
            if (access != null) {
                token = access
                logger.add("Токен Google обновлён")
                authChanged++
                loadProfileAndSetBaseline()
            } else {
                token = null
                accountEmail = ""
                errorText = error.orEmpty()
                logger.add("Не удалось восстановить авторизацию: ${errorText.take(200)}")
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
                if (repo.baseline() == 0L) {
                    repo.setBaseline(System.currentTimeMillis() / 1000)
                    logger.add("Первичная точка синхронизации сохранена; старые письма не импортируются")
                }
                logger.add("Gmail подключён: ${profile.email}")
                authChanged++
                syncNow(source = "после авторизации")
            } catch (e: Exception) {
                errorText = e.message ?: "Ошибка Gmail API"
                logger.add("Ошибка подключения Gmail: ${e.javaClass.simpleName}: ${errorText.take(200)}")
                token = null
                accountEmail = ""
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
                    gmail.send(t, accountEmail.ifBlank { repo.myEmail() }, to, text, to.lowercase(), identity.publicKey()) { bytes -> McCrypto.sign(pair, bytes) }
                }
                logger.add("Сообщение отправлено в Gmail: $id")
                done(null)
            } catch (e: Exception) {
                logger.add("Ошибка отправки: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(200)}")
                done(e.message ?: "Ошибка отправки")
            }
        }
    }

    private fun syncNow(done: (String) -> Unit = {}, source: String = "ручная") {
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

                    if (mail.spam) {
                        withContext(Dispatchers.IO) { gmail.moveOutOfSpam(t, mail.id) }
                        logger.info("Подтверждённое сообщение MC возвращено из спама")
                    }

                    val current = repo.chats().toMutableList()
                    if (current.any { chat -> chat.messages.any { it.id == parsed.messageId } }) {
                        repo.markProcessed(mail.id)
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

                    if (index >= 0) {
                        current[index] = current[index].copy(messages = current[index].messages + message)
                    } else {
                        current.add(McChat(parsed.sender, messages = listOf(message)))
                    }

                    repo.save(current)
                    repo.markProcessed(mail.id)
                    accepted++
                    logger.info("Новое сообщение MC сохранено в локальном чате")
                }

                chats = repo.chats()
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
        var status by remember { mutableStateOf("") }
        var logText by remember { mutableStateOf(logger.read()) }
        var logSize by remember { mutableLongStateOf(logger.sizeBytes()) }
        var openSeconds by remember { mutableStateOf(repo.openIntervalSeconds().toString()) }
        var minimizedSeconds by remember { mutableStateOf(repo.minimizedIntervalSeconds().toString()) }
        var closedSeconds by remember { mutableStateOf(repo.closedIntervalSeconds().toString()) }
        var diagnosticClick by remember { mutableStateOf(repo.diagnosticClickEnabled()) }
        var pendingDeleteChat by remember { mutableStateOf<McChat?>(null) }
        val logListState = rememberLazyListState()
        val logLines = remember(logText) { logText.lineSequence().filter { it.isNotBlank() }.toList() }

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
        LaunchedEffect(pendingDeleteChat) {
            val item = pendingDeleteChat ?: return@LaunchedEffect
            delay(5000)
            if (pendingDeleteChat?.email.equals(item.email, true)) {
                repo.deleteChat(item.email)
                pendingDeleteChat = null
                logger.info("Удаление диалога окончательно подтверждено: ${item.email}")
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
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                TopAppBar(title = {
                    Column {
                        Text(if (logs) "Логирование" else if (settings) "Настройки" else selected?.substringBefore('@') ?: "MC", fontWeight = FontWeight.Bold)
                        Text(if (token != null) accountEmail.ifBlank { repo.myEmail() } else "Мессенджер на Gmail", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    }
                }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy))
            }) { padding ->
                when {
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
                        Conversation(chat, Modifier.padding(padding), onSend = { body ->
                            val outgoing = McMessage(chatId = email.lowercase(), sender = accountEmail, recipient = email, text = body, incoming = false, state = DeliveryState.QUEUED)
                            chats = (chats.filterNot { it.email.equals(email, true) } + chat.copy(messages = chat.messages + outgoing)).sortedBy { it.email }
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
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(chats) { chat ->
                                    Row(Modifier.fillMaxWidth().pointerInput(chat.email) {
                                    var drag = 0f
                                    detectHorizontalDragGestures(
                                        onHorizontalDrag = { change, amount -> drag += amount; change.consume() },
                                        onDragEnd = { if (drag < -100f) pendingDeleteChat = chat.email; drag = 0f },
                                        onDragCancel = { drag = 0f }
                                    )
                                }.clickable { selected = chat.email; repo.setLastChat(chat.email) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(48.dp).background(Blue, CircleShape), contentAlignment = Alignment.Center) { Text(chat.title.take(1).uppercase(), color = Color.White, fontSize = 20.sp) }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(chat.title, fontWeight = FontWeight.SemiBold)
                                            Text(chat.messages.lastOrNull()?.text ?: chat.email, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, fontSize = 13.sp)
                                        }
                                        Text(chat.messages.lastOrNull()?.let { when (it.state) { DeliveryState.QUEUED -> "◷"; DeliveryState.SENT_TO_GMAIL -> "✓"; DeliveryState.DELIVERED -> "✓✓"; DeliveryState.FAILED -> "!" } } ?: "", color = Blue)
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f))
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
                    if (email.contains("@")) { val normalized = email.trim().lowercase(); val title = displayName.trim().ifBlank { normalized.substringBefore("@") }; if (chats.none { it.email.equals(normalized, true) }) { chats = chats + McChat(normalized, title); repo.save(chats) } else { chats = chats.map { if (it.email.equals(normalized, true)) it.copy(title = title) else it }; repo.save(chats) }; selected = normalized; repo.setLastChat(normalized); showNew = false }
                }) { Text("Создать") } }, dismissButton = { TextButton(onClick = { showNew = false }) { Text("Отмена") } })
            }
            pendingDeleteChat?.let { email ->
                AlertDialog(
                    onDismissRequest = { pendingDeleteChat = null },
                    title = { Text("Удалить чат?") },
                    text = { Text("Переписка с $email будет удалена с этого устройства.") },
                    confirmButton = { TextButton(onClick = { repo.deleteChat(email); chats = repo.chats(); if (selected.equals(email, true)) selected = null; pendingDeleteChat = null }) { Text("Удалить") } },
                    dismissButton = { TextButton(onClick = { pendingDeleteChat = null }) { Text("Отмена") } }
                )
            }
        }
    }

    @Composable private fun Conversation(chat: McChat, modifier: Modifier, onSend: (String) -> Unit) {
        var draft by remember { mutableStateOf("") }
        val listState = rememberLazyListState()
        LaunchedEffect(chat.messages.size) {
            if (chat.messages.isNotEmpty()) listState.animateScrollToItem(chat.messages.lastIndex)
        }
        Column(modifier.fillMaxSize().imePadding().padding(10.dp)) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(chat.messages) { message ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.incoming) Arrangement.Start else Arrangement.End) {
                        Surface(color = if (message.incoming) Color(0xFF263746) else Color(0xFF185C45), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.widthIn(max = 300.dp).padding(10.dp)) {
                                if (message.recoveredFromSpam) Text("Сообщение попало в спам и было автоматически добавлено как не спам", fontSize = 10.sp, color = Color(0xFFFFC56B))
                                Text(message.text)
                                Text(
                                    SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault())
                                        .format(Date(message.timestamp * 1000L)),
                                    fontSize = 10.sp,
                                    color = Color.LightGray
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f), placeholder = { Text("Сообщение...") }, shape = RoundedCornerShape(24.dp), maxLines = 4)
                IconButton(onClick = { if (draft.isNotBlank()) { onSend(draft.trim()); draft = "" } }) { Icon(Icons.Default.Send, "Отправить", tint = Blue) }
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
