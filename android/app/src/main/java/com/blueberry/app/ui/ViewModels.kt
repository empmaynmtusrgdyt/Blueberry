package com.blueberry.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.blueberry.app.AppContainer
import com.blueberry.app.BlueberryApp
import com.blueberry.app.data.ChatDefaults
import com.blueberry.app.data.ChatEntity
import com.blueberry.app.data.ConnState
import com.blueberry.app.data.Generation
import com.blueberry.app.data.MessageEntity
import com.blueberry.app.data.QrPayload
import com.blueberry.app.data.ServerAddress
import com.blueberry.app.net.ChatErrors
import com.blueberry.app.net.ChatException
import com.blueberry.app.net.ModelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private fun <T> kotlinx.coroutines.flow.Flow<T>.state(vm: ViewModel, initial: T): StateFlow<T> =
    stateIn(vm.viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

class ChatListViewModel(app: Application) : AndroidViewModel(app) {
    private val c: AppContainer = (app as BlueberryApp).container
    val chats: StateFlow<List<ChatEntity>?> = c.db.dao().observeChats().map<List<ChatEntity>, List<ChatEntity>?> { it }.state(this, null)
    val connection: StateFlow<ConnState> = c.connection.state

    fun createChat(onCreated: (Long) -> Unit) = viewModelScope.launch { onCreated(c.engine.createChat()) }
    fun rename(id: Long, title: String) = viewModelScope.launch { c.db.dao().rename(id, title.trim().ifBlank { "Без названия" }) }
    fun delete(chat: ChatEntity) = viewModelScope.launch { c.db.dao().deleteChat(chat) }
}

class ChatViewModel(app: Application, private val chatId: Long) : AndroidViewModel(app) {
    private val c: AppContainer = (app as BlueberryApp).container
    val chat: StateFlow<ChatEntity?> = c.db.dao().observeChat(chatId).state(this, null)
    val messages: StateFlow<List<MessageEntity>> = c.db.dao().observeMessages(chatId).state(this, emptyList())
    val generation: StateFlow<Generation?> = c.engine.generations.map { it[chatId] }.state(this, null)
    val connection: StateFlow<ConnState> = c.connection.state

    fun send(text: String) = c.engine.send(chatId, text)
    fun stop() = c.engine.stop(chatId)
    fun retry() = c.engine.retry(chatId)
    fun dismissError() = c.engine.dismissError(chatId)

    fun saveSettings(systemPrompt: String, temperature: Float, maxTokens: Int) = viewModelScope.launch {
        c.db.dao().getChat(chatId)?.let {
            c.db.dao().updateChat(it.copy(systemPrompt = systemPrompt, temperature = temperature, maxTokens = maxTokens))
        }
    }

    companion object {
        fun factory(chatId: Long) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                return ChatViewModel(app, chatId) as T
            }
        }
    }
}

data class TestResult(val ok: Boolean, val message: String)

class ConnectionViewModel(app: Application) : AndroidViewModel(app) {
    private val c: AppContainer = (app as BlueberryApp).container

    val host = MutableStateFlow("")
    val port = MutableStateFlow("8787")
    val token = MutableStateFlow("")
    val testing = MutableStateFlow(false)
    val testResult = MutableStateFlow<TestResult?>(null)
    val defaults = MutableStateFlow(ChatDefaults())
    val saved = MutableStateFlow(false)
    val connection: StateFlow<ConnState> = c.connection.state

    private var mdns = ""

    init {
        viewModelScope.launch {
            val a = c.settings.address.first()
            host.value = a.host; port.value = a.port.toString(); mdns = a.mdns
            token.value = c.tokens.get()
            defaults.value = c.settings.defaults.first()
        }
    }

    /** Возвращает false, если это не QR Blueberry. */
    fun applyQr(text: String): Boolean {
        val p = QrPayload.parse(text) ?: return false
        host.value = p.host; port.value = p.port.toString(); token.value = p.token; mdns = p.mdns
        testResult.value = null
        return true
    }

    private fun address(): ServerAddress? {
        val p = port.value.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return host.value.trim().takeIf { it.isNotEmpty() }?.let { ServerAddress(it, p, mdns) }
    }

    fun test() {
        val a = address() ?: run { testResult.value = TestResult(false, "Введите адрес и порт"); return }
        viewModelScope.launch {
            testing.value = true
            testResult.value = try {
                val model = c.connection.test(a, token.value.trim())
                TestResult(true, when (model) {
                    ModelState.LOADED -> "Подключено. Модель готова."
                    ModelState.UNLOADED -> "Подключено. Модель спит, загрузится при первом сообщении (до ~20 с)."
                    ModelState.LOADING -> "Подключено. Модель загружается."
                    ModelState.UNKNOWN -> "Подключено."
                })
            } catch (e: ChatException) {
                TestResult(false, ChatErrors.message(e.kind))
            }
            testing.value = false
        }
    }

    fun save() {
        val a = address() ?: run { testResult.value = TestResult(false, "Введите адрес и порт"); return }
        viewModelScope.launch {
            c.settings.saveAddress(a)
            c.tokens.set(token.value)
            c.settings.saveDefaults(defaults.value)
            saved.update { true }
        }
    }

    fun updateDefaults(f: (ChatDefaults) -> ChatDefaults) { defaults.update(f) }
}
