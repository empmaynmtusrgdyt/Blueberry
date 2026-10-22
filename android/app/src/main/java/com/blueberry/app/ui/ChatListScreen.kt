package com.blueberry.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blueberry.app.data.ChatEntity
import com.blueberry.app.data.ConnState
import com.blueberry.app.net.ChatErrors
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(onOpen: (Long) -> Unit, onSettings: () -> Unit, vm: ChatListViewModel = viewModel()) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<ChatEntity?>(null) }
    var deleting by remember { mutableStateOf<ChatEntity?>(null) }
    val list = chats

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { BrandTitle("Blueberry") },
                actions = {
                    StatusIndicator(conn)
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Подключение и настройки") }
                },
            )
        },
        floatingActionButton = {
            if (!list.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { vm.createChat(onOpen) },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Новый чат") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            (conn as? ConnState.Failed)?.let { ErrorCard(ChatErrors.message(it.kind)) }
            when {
                list == null -> Box(Modifier.fillMaxSize())
                list.isEmpty() -> EmptyState(onNew = { vm.createChat(onOpen) })
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(list, key = { it.id }) { chat ->
                        ChatCard(chat, onOpen = { onOpen(chat.id) }, onRename = { renaming = chat }, onDelete = { deleting = chat })
                    }
                }
            }
        }
    }

    renaming?.let { chat ->
        var title by remember(chat.id) { mutableStateOf(chat.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Переименовать чат") },
            text = { OutlinedTextField(title, { title = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(chat.id, title); renaming = null }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Отмена") } },
        )
    }
    deleting?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить чат?") },
            text = { Text("«${chat.title}» и вся переписка будут удалены.") },
            confirmButton = { TextButton(onClick = { vm.delete(chat); deleting = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ChatCard(chat: ChatEntity, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BerryAvatar(44.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(chat.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(chat.updatedAt)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Действия") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onNew: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Berry(120.dp)
        Spacer(Modifier.height(16.dp))
        Text("Здесь пока пусто", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Начните разговор с локальной моделью. Всё работает по вашей сети и без интернета.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onNew) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.height(0.dp).padding(start = 8.dp))
            Text("Новый чат")
        }
    }
}

@Composable
fun ErrorCard(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}
