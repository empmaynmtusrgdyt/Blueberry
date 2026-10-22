package com.blueberry.app.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blueberry.app.data.ChatEntity
import com.blueberry.app.data.ConnState
import com.blueberry.app.data.Generation
import com.blueberry.app.data.Phase
import com.blueberry.app.net.ChatErrors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(chatId: Long, onBack: () -> Unit, vm: ChatViewModel = viewModel(key = "chat$chatId", factory = ChatViewModel.factory(chatId))) {
    val chat by vm.chat.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val gen by vm.generation.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val busy = gen != null && gen?.phase != Phase.ERROR

    val itemCount = messages.size + if (gen != null && gen?.phase != Phase.ERROR) 1 else 0
    LaunchedEffect(itemCount, gen?.text?.length) {
        if (itemCount > 0) listState.scrollToItem(itemCount - 1)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(chat?.title ?: "", maxLines = 1, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    StatusIndicator(conn)
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Настройки чата") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            (conn as? ConnState.Failed)?.let { ErrorCard(ChatErrors.message(it.kind)) }
            if (messages.isEmpty() && gen == null) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Berry(96.dp)
                    Text("Спросите что угодно", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    Text(
                        "Ответы генерируются на вашем маке, интернет не нужен.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else LazyColumn(
                Modifier.weight(1f).fillMaxWidth(), state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(messages, key = { it.id }) { m -> Bubble(m.role == "user", m.content) }
                if (gen != null && gen?.phase != Phase.ERROR) item(key = "gen") { GenerationBubble(gen!!) }
            }
            if (gen?.phase == Phase.ERROR) {
                ErrorBanner(gen!!, onRetry = vm::retry, onDismiss = vm::dismissError)
            }
            InputRow(
                value = input, onValueChange = { input = it }, busy = busy,
                onSend = { vm.send(input); input = "" }, onStop = vm::stop,
            )
        }
    }

    if (showSettings) chat?.let { ChatSettingsDialog(it, onDismiss = { showSettings = false }, onSave = { p, t, m -> vm.saveSettings(p, t, m); showSettings = false }) }
}

private val UserShape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
private val AssistantShape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(isUser: Boolean, text: String) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copy = {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
    }
    if (isUser) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                shape = UserShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.widthIn(max = 520.dp).combinedClickable(onClick = {}, onLongClick = copy),
            ) { Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge) }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            BerryAvatar(30.dp)
            Surface(
                shape = AssistantShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = 560.dp).combinedClickable(onClick = {}, onLongClick = copy),
            ) { MarkdownText(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
        }
    }
}

@Composable
private fun GenerationBubble(gen: Generation) {
    if (gen.text.isNotEmpty()) {
        Bubble(false, gen.text)
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        BerryAvatar(30.dp)
        Surface(shape = AssistantShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    if (gen.phase == Phase.MODEL_LOADING) "Модель загружается, это может занять до 30 секунд…" else "Думаю…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(gen: Generation, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                gen.error?.let(ChatErrors::message) ?: "Ошибка", Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onRetry) { Text("Повторить") }
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    }
}

@Composable
private fun InputRow(value: String, onValueChange: (String) -> Unit, busy: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            value, onValueChange, Modifier.weight(1f), placeholder = { Text("Сообщение") },
            maxLines = 5, shape = RoundedCornerShape(26.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
        if (busy) {
            FilledIconButton(
                onClick = onStop, modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Box(Modifier.size(16.dp).background(MaterialTheme.colorScheme.onError, RoundedCornerShape(3.dp))) }
        } else {
            FilledIconButton(onClick = onSend, enabled = value.isNotBlank(), modifier = Modifier.size(52.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, "Отправить")
            }
        }
    }
}

@Composable
private fun ChatSettingsDialog(chat: ChatEntity, onDismiss: () -> Unit, onSave: (String, Float, Int) -> Unit) {
    var prompt by remember(chat.id) { mutableStateOf(chat.systemPrompt) }
    var temp by remember(chat.id) { mutableStateOf(chat.temperature) }
    var maxTokens by remember(chat.id) { mutableStateOf(chat.maxTokens.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки чата") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(prompt, { prompt = it }, label = { Text("Системный промт") }, minLines = 3, maxLines = 6)
                Text("Температура: ${"%.1f".format(temp)}")
                Slider(temp, { temp = it }, valueRange = 0f..2f)
                OutlinedTextField(maxTokens, { maxTokens = it.filter(Char::isDigit).take(5) },
                    label = { Text("Макс. длина ответа (токены, 0 = без ограничения)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(prompt, temp, maxTokens.toIntOrNull() ?: 0) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
