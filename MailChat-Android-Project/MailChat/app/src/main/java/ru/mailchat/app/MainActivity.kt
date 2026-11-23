package ru.mailchat.app

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Security.setProperty("crypto.policy", "unlimited")
        auth = McAuth(this); repo = McRepository(this); logger = McLogger(this); identity = McIdentity(this)
        chats = repo.chats()
        logger.add("Приложение запущено. Восстановлено чатов: ${chats.size}")
        if (!handleAuthIntent(intent) && auth.isConnected()) refreshToken()
        setContent { McApp() }
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

    private fun syncNow(done: (String) -> Unit = {}) {
        if (syncInProgress) {
            done("Проверка уже выполняется")
            return
        }
        val t = token ?: run { done("Нет подключения Gmail"); return }
        val since = repo.baseline().coerceAtLeast(1L)
        syncInProgress = true
        logger.add("Начата проверка почты. Точка синхронизации: $since")

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

                    val verification = McProtocol.verifyDetailed(
                        mail, accountEmail.ifBlank { repo.myEmail() }
                    )
                    val parsed = verification.parsed

                    if (parsed == null) {
                        if (mail.subject == "[MC] Message") {
                            logger.warn("Сообщение MC отклонено. Gmail ID: ${mail.id}. Причина: ${verification.reason}")
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
                logger.add("Проверка Gmail завершена. Получено: $accepted; пропущено: $skipped; всего проверено: ${incoming.size}")
                result = "Проверка завершена. Новых сообщений: $accepted"
            } catch (e: Exception) {
                result = e.message ?: "Ошибка синхронизации"
                logger.error("Ошибка синхронизации: ${e.javaClass.simpleName}: ${result.take(250)}")
            } finally {
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
        val clipboardManager = remember {
            getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        }
        val refresh = authChanged
        MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Color(0xFF101820), surface = Color(0xFF172330), onSurface = Color(0xFFEAF0F6))) {
            Scaffold(topBar = {
                TopAppBar(title = {
                    Column {
                        Text(if (logs) "Логирование" else if (settings) "Настройки" else selected?.substringBefore('@') ?: "MC", fontWeight = FontWeight.Bold)
                        Text(if (token != null) accountEmail.ifBlank { repo.myEmail() } else "Мессенджер на Gmail", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }, navigationIcon = {
                    if (selected != null || settings || logs) IconButton(onClick = { selected = null; repo.setLastChat(null); settings = false; logs = false }) { Icon(Icons.Default.ArrowBack, "Назад") }
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
                        Text(
                            logText,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.weight(1f).fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        )
                    }
                    settings -> Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                        Text("Синхронизация", fontWeight = FontWeight.Bold)
                        Button(
                            onClick = { syncNow { status = it } },
                            enabled = token != null && !syncInProgress
                        ) { Text(if (syncInProgress) "Проверяем..." else "Проверить новые сообщения") }
                        Text("Старые письма не импортируются. Точка начала синхронизации устанавливается при первом подключении.", fontSize = 13.sp)
                        HorizontalDivider()
                        Text("Логирование", fontWeight = FontWeight.Bold)
                        Button(onClick = { logs = true; logText = logger.read(); logSize = logger.sizeBytes() }) { Text("Открыть журнал") }
                        Text("Безопасность: текст первой версии не зашифрован. Закрытый ключ подписи хранится в зашифрованных локальных настройках.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        }, onDelete = { repo.deleteChat(email); chats = repo.chats(); selected = null; repo.setLastChat(null) })
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
                                    Row(Modifier.fillMaxWidth().clickable { selected = chat.email; repo.setLastChat(chat.email) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                AlertDialog(onDismissRequest = { showNew = false }, title = { Text("Новый чат") }, text = {
                    OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Gmail собеседника") }, singleLine = true)
                }, confirmButton = { TextButton(onClick = {
                    if (email.contains("@")) { val normalized = email.trim().lowercase(); if (chats.none { it.email == normalized }) { chats = chats + McChat(normalized); repo.save(chats) }; selected = normalized; repo.setLastChat(normalized); showNew = false }
                }) { Text("Создать") } }, dismissButton = { TextButton(onClick = { showNew = false }) { Text("Отмена") } })
            }
        }
    }

    @Composable private fun Conversation(chat: McChat, modifier: Modifier, onSend: (String) -> Unit, onDelete: () -> Unit) {
        var draft by remember { mutableStateOf("") }
        Column(modifier.fillMaxSize().padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onDelete) { Icon(Icons.Default.Delete, null); Text("Удалить чат") } }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
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

    override fun onDestroy() { super.onDestroy(); if (::auth.isInitialized) auth.close() }
}
