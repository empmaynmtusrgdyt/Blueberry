package com.blueberry.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Blueberry") }, actions = {
                StatusIndicator(conn)
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Подключение и настройки") }
            })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.createChat(onOpen) }) { Icon(Icons.Default.Add, "Новый чат") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            (conn as? ConnState.Failed)?.let {
                Text(ChatErrors.message(it.kind), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            val list = chats
            when {
                list == null -> Box(Modifier.fillMaxSize())
                list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Чатов пока нет. Нажмите «+», чтобы начать.", style = MaterialTheme.typography.bodyLarge)
                }
                else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    items(list, key = { it.id }) { chat ->
                        ListItem(
                            headlineContent = { Text(chat.title, maxLines = 1) },
                            supportingContent = { Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(chat.updatedAt))) },
                            trailingContent = { ChatActions({ renaming = chat }, { deleting = chat }) },
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(chat.id) },
                        )
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
private fun ChatActions(onRename: () -> Unit, onDelete: () -> Unit) {
    Row {
        IconButton(onClick = onRename) { Icon(Icons.Default.Edit, "Переименовать") }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Удалить") }
    }
}
