package com.blueberry.app.net

import com.blueberry.app.data.ServerAddress
import com.blueberry.app.domain.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

sealed interface ChatEvent {
    /** Сервер загружает модель после простоя (холодный старт). */
    data object ModelLoading : ChatEvent
    data class Delta(val text: String) : ChatEvent
    data object Done : ChatEvent
}

data class ChatParams(val temperature: Float, val maxTokens: Int)

enum class ModelState { LOADED, LOADING, UNLOADED, UNKNOWN }
data class Health(val ok: Boolean, val model: ModelState)

/**
 * Клиент прокси Blueberry. Всегда обращается только к одному настроенному серверу и только по локальным адресам.
 * [socketFactoryFor] позволяет привязать сокеты к Wi-Fi-сети (см. [LocalNetwork]).
 */
class BlueberryClient(
    private val socketFactoryFor: (host: String) -> SocketFactory? = { null },
    private val hasLocalNetwork: () -> Boolean = { true },
    private val baseClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val localOnly = Interceptor { chain ->
        val host = chain.request().url.host
        if (!PrivateAddress.isAllowedHost(host)) throw ChatException(ChatErrorKind.FORBIDDEN_HOST, host)
        chain.proceed(chain.request())
    }

    private fun client(address: ServerAddress, readTimeoutSec: Long, connectTimeoutSec: Long = 4): OkHttpClient =
        baseClient.newBuilder()
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)  // короткий: сервер рядом или его нет
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)        // длинный для стрима и холодного старта
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .apply { socketFactoryFor(address.host)?.let { socketFactory(it) } }
            .addInterceptor(localOnly)
            .build()

    private fun request(address: ServerAddress, path: String, token: String?) = Request.Builder()
        .url(address.baseUrl + path)
        .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }

    /** GET /health (без токена). */
    suspend fun health(address: ServerAddress, timeoutSec: Long = 3): Health = withContext(Dispatchers.IO) {
        try {
            client(address, timeoutSec, timeoutSec).newCall(request(address, "/health", null).build()).execute().use { r ->
                if (!r.isSuccessful) return@use Health(false, ModelState.UNKNOWN)
                val obj = json.parseToJsonElement(r.body.string()).jsonObject
                val model = when (obj["model"]?.jsonPrimitive?.contentOrNull) {
                    "loaded" -> ModelState.LOADED
                    "loading" -> ModelState.LOADING
                    "unloaded" -> ModelState.UNLOADED
                    else -> ModelState.UNKNOWN
                }
                Health(obj["status"]?.jsonPrimitive?.contentOrNull == "ok", model)
            }
        } catch (e: Exception) {
            throw ChatErrors.from(e, hasLocalNetwork())
        }
    }

    /** GET /v1/models: проверяет токен и возвращает идентификаторы моделей. */
    suspend fun models(address: ServerAddress, token: String): List<String> = withContext(Dispatchers.IO) {
        try {
            client(address, 8).newCall(request(address, "/v1/models", token).build()).execute().use { r ->
                if (!r.isSuccessful) throw ChatErrors.fromHttpStatus(r.code)
                json.parseToJsonElement(r.body.string()).jsonObject["data"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.orEmpty()
            }
        } catch (e: Exception) {
            throw ChatErrors.from(e, hasLocalNetwork())
        }
    }

    internal fun buildBody(messages: List<ChatTurn>, systemPrompt: String, params: ChatParams): String =
        buildJsonObject {
            put("model", "gemma") // прокси подставляет настроенную модель
            put("stream", true)
            put("temperature", params.temperature)
            if (params.maxTokens > 0) put("max_tokens", params.maxTokens)
            put("messages", buildJsonArray {
                if (systemPrompt.isNotBlank()) add(buildJsonObject { put("role", "system"); put("content", systemPrompt) })
                messages.forEach { add(buildJsonObject { put("role", it.role); put("content", it.content) }) }
            })
        }.toString()

    /**
     * POST /v1/chat/completions со stream=true. Отмена коллектора отменяет HTTP-вызов, прокси закрывает
     * upstream, и модель прекращает генерацию. Ошибки приходят как [ChatException].
     */
    fun streamChat(
        address: ServerAddress,
        token: String,
        messages: List<ChatTurn>,
        systemPrompt: String,
        params: ChatParams,
    ): Flow<ChatEvent> = channelFlow {
        val body = buildBody(messages, systemPrompt, params).toRequestBody("application/json".toMediaType())
        val call = client(address, readTimeoutSec = 120).newCall(request(address, "/v1/chat/completions", token).post(body).build())
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        job?.invokeOnCompletion { call.cancel() }
        withContext(Dispatchers.IO) {
            try {
                call.execute().use { r ->
                    if (!r.isSuccessful) throw ChatErrors.fromHttpStatus(r.code)
                    val source = r.body.source()
                    val parser = SseParser()
                    var loadingSent = false
                    fun handle(e: SseEvent?): Boolean { // true — поток закончился
                        when (e) {
                            is SseEvent.Comment -> if (!loadingSent && e.text.contains("loading")) {
                                loadingSent = true; trySend(ChatEvent.ModelLoading)
                            }
                            is SseEvent.Data -> when (val c = ChatChunkParser.parse(e.data)) {
                                is ChunkResult.Delta -> if (c.text.isNotEmpty()) trySend(ChatEvent.Delta(c.text))
                                is ChunkResult.Error -> throw ChatException(
                                    if (c.message.contains("model_load_failed")) ChatErrorKind.MODEL_LOAD_FAILED else ChatErrorKind.BAD_RESPONSE,
                                    c.message,
                                )
                                ChunkResult.Done -> return true
                            }
                            null -> {}
                        }
                        return false
                    }
                    while (isActive) {
                        val line = source.readUtf8Line() ?: break
                        if (handle(parser.feedLine(line))) break
                    }
                    handle(parser.flush())
                }
                trySend(ChatEvent.Done)
            } catch (e: Exception) {
                if (!isActive) return@withContext // отмена пользователем: не ошибка
                throw ChatErrors.from(e, hasLocalNetwork())
            }
        }
    }
}
