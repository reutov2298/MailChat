package ru.mailchat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Navy = Color(0xFF17283B)
private val Blue = Color(0xFF3390EC)

data class ChatPreview(val name: String, val email: String, val preview: String, val time: String, val unread: Int)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MailChatApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailChatApp() {
    var selected by remember { mutableStateOf<ChatPreview?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showNewChat by remember { mutableStateOf(false) }
    val chats = remember {
        mutableStateListOf(
            ChatPreview("Алексей", "alex@example.com", "Привет! Проверяем MailChat", "10:24", 2),
            ChatPreview("Михаил", "mikhail@example.com", "Отправил фотографию", "Вчера", 0),
            ChatPreview("Елена", "elena@example.com", "Хорошо, договорились", "Вчера", 0)
        )
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Blue,
            background = Color(0xFF101820),
            surface = Color(0xFF172330),
            onSurface = Color(0xFFEAF0F6)
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(if (selected == null) "MailChat" else selected!!.name, fontWeight = FontWeight.Bold)
                            Text(
                                if (selected == null) "Мессенджер на электронной почте" else selected!!.email,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        if (selected != null || showSettings) {
                            IconButton(onClick = { selected = null; showSettings = false }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettings = !showSettings; selected = null }) {
                            Icon(Icons.Default.Settings, contentDescription = "Настройки")
                        }
                        IconButton(onClick = { showNewChat = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Новый чат")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy)
                )
            }
        ) { padding ->
            when {
                showSettings -> SettingsScreen(Modifier.padding(padding))
                selected != null -> ConversationScreen(selected!!, Modifier.padding(padding))
                else -> ChatList(chats, Modifier.padding(padding)) { selected = it }
            }
        }

        if (showNewChat) {
            var email by remember { mutableStateOf("") }
            var name by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showNewChat = false },
                title = { Text("Новый чат") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email собеседника") })
                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Имя") })
                        Text("Пока это только макет: письмо ещё не отправляется.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (email.contains("@")) {
                            val chat = ChatPreview(name.ifBlank { email.substringBefore("@") }, email, "Новый диалог", "Сейчас", 0)
                            chats.add(0, chat)
                            selected = chat
                            showNewChat = false
                        }
                    }) { Text("Создать") }
                },
                dismissButton = { TextButton(onClick = { showNewChat = false }) { Text("Отмена") } }
            )
        }
    }
}

@Composable
private fun ChatList(chats: List<ChatPreview>, modifier: Modifier, onOpen: (ChatPreview) -> Unit) {
    Column(modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = "",
            onValueChange = {},
            readOnly = true,
            placeholder = { Text("Поиск по чатам (макет)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text("ЧАТЫ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        LazyColumn {
            items(chats) { chat ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(chat) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(48.dp).background(Blue, CircleShape), contentAlignment = Alignment.Center) {
                        Text(chat.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(chat.name, fontWeight = FontWeight.SemiBold)
                        Text(chat.preview, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(chat.time, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        if (chat.unread > 0) {
                            Spacer(Modifier.height(5.dp))
                            Box(Modifier.background(Blue, CircleShape).padding(horizontal = 7.dp, vertical = 2.dp)) {
                                Text(chat.unread.toString(), color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
            }
        }
        Spacer(Modifier.weight(1f))
        Text("Демо-интерфейс · отправка почты ещё не подключена", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun ConversationScreen(chat: ChatPreview, modifier: Modifier) {
    var draft by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf("Привет! Это тестовый экран MailChat.", "Пока сообщения демонстрационные.") }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text("Сегодня", modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        messages.forEachIndexed { index, message ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (index % 2 == 0) Arrangement.Start else Arrangement.End) {
                Surface(
                    color = if (index % 2 == 0) Color(0xFF263746) else Color(0xFF185C45),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(message, Modifier.widthIn(max = 290.dp).padding(12.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Сообщение...") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4
            )
            IconButton(onClick = { if (draft.isNotBlank()) { messages.add(draft.trim()); draft = "" } }) {
                Icon(Icons.Default.Send, contentDescription = "Отправить", tint = Blue)
            }
        }
        Text("Демо: сообщения пока не отправляются по электронной почте.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Настройки", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Подключение почты", fontWeight = FontWeight.SemiBold)
        Text("Gmail — будет подключён первым через безопасную авторизацию Google OAuth 2.0.")
        Button(onClick = {}) { Text("Подключить Gmail (следующий этап)") }
        HorizontalDivider()
        Text("Старые письма", fontWeight = FontWeight.SemiBold)
        Text("При подключении можно будет выбрать: импортировать старые письма или начать с новых.")
        HorizontalDivider()
        Text("Обычная почта", fontWeight = FontWeight.SemiBold)
        Text("Настройка показа обычных писем появится после подключения почтового транспорта.")
        HorizontalDivider()
        Text("Безопасность", fontWeight = FontWeight.SemiBold)
        Text("В этой тестовой версии сквозное шифрование не реализовано. Не используйте её для конфиденциальной переписки.")
    }
}
