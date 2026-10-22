package com.blueberry.app.data

import com.blueberry.app.net.BlueberryClient
import com.blueberry.app.net.ChatErrorKind
import com.blueberry.app.net.ChatException
import com.blueberry.app.net.Discovery
import com.blueberry.app.net.ModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ConnState {
    data object NotConfigured : ConnState
    data object Searching : ConnState
    data class Connected(val host: String, val model: ModelState) : ConnState
    data class Failed(val kind: ChatErrorKind) : ConnState
}

/**
 * Состояние соединения. Порядок способов подключения:
 *  1) сохранённые данные (из QR или ручного ввода);
 *  2) автообнаружение через NsdManager, если сохранённый адрес не отвечает (IP мог смениться);
 *  3) ручной ввод — на экране подключения.
 */
class ConnectionRepository(
    private val settings: SettingsStore,
    private val tokens: TokenStore,
    private val client: BlueberryClient,
    private val discovery: Discovery,
    scope: CoroutineScope,
) {
    val state: StateFlow<ConnState> = flow {
        while (true) {
            val result = check()
            emit(result)
            delay(if (result is ConnState.Connected) 8_000 else 4_000)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ConnState.Searching)

    private suspend fun FlowCollector<ConnState>.check(): ConnState {
        val address = settings.address.first()
        val token = tokens.get()
        if (!address.isSet || token.isBlank()) return ConnState.NotConfigured
        return try {
            probe(address, token)
        } catch (e: ChatException) {
            if (e.kind == ChatErrorKind.UNAUTHORIZED) return ConnState.Failed(e.kind)
            emit(ConnState.Searching)
            val found = discover(address) ?: return ConnState.Failed(e.kind)
            settings.saveAddress(found)
            try { probe(found, token) } catch (e2: ChatException) { ConnState.Failed(e2.kind) }
        }
    }

    private suspend fun probe(address: ServerAddress, token: String): ConnState {
        val health = client.health(address)
        client.models(address, token) // проверка токена (401 -> UNAUTHORIZED)
        return ConnState.Connected(address.host, health.model)
    }

    private suspend fun discover(old: ServerAddress): ServerAddress? = withTimeoutOrNull(6_000) {
        discovery.discover().mapNotNull { s ->
            val candidate = ServerAddress(s.host, s.port, old.mdns)
            candidate.takeIf { runCatching { client.health(it).ok }.getOrDefault(false) }
        }.first()
    }

    /** Кнопка «Проверить соединение»: /health затем /v1/models. Бросает [ChatException]. */
    suspend fun test(address: ServerAddress, token: String): ModelState {
        val health = client.health(address)
        client.models(address, token)
        return health.model
    }
}
