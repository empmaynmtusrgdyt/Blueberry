package com.blueberry.app.data

import com.blueberry.app.domain.ChatTurn
import com.blueberry.app.domain.HistoryTrimmer
import com.blueberry.app.net.BlueberryClient
import com.blueberry.app.net.ChatErrorKind
import com.blueberry.app.net.ChatErrors
import com.blueberry.app.net.ChatEvent
import com.blueberry.app.net.ChatParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Phase { WAITING, MODEL_LOADING, STREAMING, ERROR }

data class Generation(val text: String = "", val phase: Phase = Phase.WAITING, val error: ChatErrorKind? = null)

/**
 * Генерация ответов. Живёт в области приложения, а не экрана: поворот, навигация и сворачивание
 * не прерывают идущий стрим. Состояние отдаётся ViewModel через [generations].
 */
class ChatEngine(
    private val dao: ChatDao,
    private val settings: SettingsStore,
    private val tokens: TokenStore,
    private val client: BlueberryClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _generations = MutableStateFlow<Map<Long, Generation>>(emptyMap())
    val generations: StateFlow<Map<Long, Generation>> = _generations
    private val jobs = HashMap<Long, Job>()

    suspend fun createChat(): Long {
        val d = settings.defaults.first()
        val now = clock()
        return dao.insertChat(ChatEntity(title = DEFAULT_TITLE, systemPrompt = d.systemPrompt,
            temperature = d.temperature, maxTokens = d.maxTokens, createdAt = now, updatedAt = now))
    }

    fun send(chatId: Long, text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || isBusy(chatId)) return
        scope.launch {
            val now = clock()
            dao.insertMessage(MessageEntity(chatId = chatId, role = "user", content = clean, createdAt = now))
            dao.touch(chatId, now)
            dao.getChat(chatId)?.takeIf { it.title == DEFAULT_TITLE }?.let { dao.rename(chatId, clean.take(40)) }
            start(chatId)
        }
    }

    /** Повтор после ошибки: история уже содержит последний вопрос пользователя. */
    fun retry(chatId: Long) { if (!isBusy(chatId)) scope.launch { start(chatId) } }

    fun stop(chatId: Long) { jobs[chatId]?.cancel() }

    fun dismissError(chatId: Long) { _generations.update { it - chatId } }

    private fun isBusy(chatId: Long) = jobs[chatId]?.isActive == true

    private fun start(chatId: Long) {
        _generations.update { it + (chatId to Generation()) }
        jobs[chatId] = scope.launch { generate(chatId) }
    }

    private suspend fun generate(chatId: Long) {
        val acc = StringBuilder()
        var error: ChatErrorKind? = null
        try {
            val chat = dao.getChat(chatId) ?: return
            val turns = dao.getMessages(chatId).map { ChatTurn(it.role, it.content) }
            val defaults = settings.defaults.first()
            val trimmed = HistoryTrimmer.trim(
                chat.systemPrompt, turns, defaults.contextTokens,
                replyTokens = if (chat.maxTokens > 0) chat.maxTokens else 1024,
            )
            client.streamChat(settings.address.first(), tokens.get(), trimmed, chat.systemPrompt,
                ChatParams(chat.temperature, chat.maxTokens)).collect { ev ->
                when (ev) {
                    ChatEvent.ModelLoading -> setGen(chatId) { it.copy(phase = Phase.MODEL_LOADING) }
                    is ChatEvent.Delta -> {
                        acc.append(ev.text)
                        val snapshot = acc.toString()
                        setGen(chatId) { it.copy(text = snapshot, phase = Phase.STREAMING) }
                    }
                    ChatEvent.Done -> {}
                }
            }
        } catch (e: CancellationException) {
            throw e // остановка пользователем: частичный ответ сохранится в finally
        } catch (e: Throwable) {
            error = ChatErrors.from(e, hasLocalNetwork = true).kind
        } finally {
            withContext(NonCancellable) {
                if (error == null && acc.isNotBlank()) {
                    val now = clock()
                    dao.insertMessage(MessageEntity(chatId = chatId, role = "assistant", content = acc.toString(), createdAt = now))
                    dao.touch(chatId, now)
                }
                _generations.update { map ->
                    if (error != null) map + (chatId to Generation(phase = Phase.ERROR, error = error)) else map - chatId
                }
            }
        }
    }

    private fun setGen(chatId: Long, f: (Generation) -> Generation) {
        _generations.update { map -> map[chatId]?.let { map + (chatId to f(it)) } ?: map }
    }

    companion object { const val DEFAULT_TITLE = "Новый чат" }
}
