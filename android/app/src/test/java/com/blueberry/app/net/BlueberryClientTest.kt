package com.blueberry.app.net

import com.blueberry.app.data.ServerAddress
import com.blueberry.app.domain.ChatTurn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class BlueberryClientTest {
    private lateinit var server: MockWebServer
    private lateinit var address: ServerAddress
    private val client = BlueberryClient()
    private val params = ChatParams(0.7f, 128)
    private val turns = listOf(ChatTurn("user", "Привет"))

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        address = ServerAddress(server.hostName, server.port)
    }

    @After fun tearDown() { server.close() }

    private fun sse(vararg events: String) = events.joinToString("") { "$it\n\n" }
    private fun delta(t: String) = """data: {"choices":[{"delta":{"content":"$t"},"finish_reason":null}]}"""

    @Test fun `стрим собирается из дельт`() = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
            .body(sse(delta("При"), delta("вет"), "data: [DONE]")).build())
        val events = client.streamChat(address, "tok", turns, "sys", params).toList()
        assertEquals("Привет", events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text })
        assertEquals(ChatEvent.Done, events.last())
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("Bearer tok", req.headers["Authorization"])
        assertTrue(req.body!!.utf8().contains("\"stream\":true"))
    }

    @Test fun `комментарий loading даёт ModelLoading`() = runBlocking {
        server.enqueue(MockResponse.Builder().body(": loading\n\n: loading\n\n" + sse(delta("ok"), "data: [DONE]")).build())
        val events = client.streamChat(address, "t", turns, "", params).toList()
        assertEquals(1, events.count { it == ChatEvent.ModelLoading })
        assertEquals(ChatEvent.ModelLoading, events.first())
    }

    @Test fun `401 даёт UNAUTHORIZED`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("{}").build())
        try { client.streamChat(address, "bad", turns, "", params).toList(); fail() }
        catch (e: ChatException) { assertEquals(ChatErrorKind.UNAUTHORIZED, e.kind) }
    }

    @Test fun `429 даёт BUSY`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).build())
        try { client.streamChat(address, "t", turns, "", params).toList(); fail() }
        catch (e: ChatException) { assertEquals(ChatErrorKind.BUSY, e.kind) }
    }

    @Test fun `недоступный сервер даёт UNREACHABLE`() = runBlocking {
        val dead = ServerAddress("127.0.0.1", 1) // порт 1 закрыт
        try { client.streamChat(dead, "t", turns, "", params).toList(); fail() }
        catch (e: ChatException) { assertEquals(ChatErrorKind.UNREACHABLE, e.kind) }
    }

    @Test fun `публичный адрес запрещён`() = runBlocking {
        try { client.streamChat(ServerAddress("8.8.8.8", 8787), "t", turns, "", params).toList(); fail() }
        catch (e: ChatException) { assertEquals(ChatErrorKind.FORBIDDEN_HOST, e.kind) }
    }

    @Test fun `отмена после первого токена завершает поток без ошибки`() = runBlocking {
        server.enqueue(MockResponse.Builder().body(sse(delta("a"), delta("b"), delta("c"), "data: [DONE]"))
            .throttleBody(10, 200, TimeUnit.MILLISECONDS).build())
        val first = client.streamChat(address, "t", turns, "", params).first { it is ChatEvent.Delta }
        assertTrue(first is ChatEvent.Delta)
    }

    @Test fun `health разбирает состояние модели`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"status": "ok", "model": "unloaded"}""").build())
        val h = client.health(address)
        assertTrue(h.ok); assertEquals(ModelState.UNLOADED, h.model)
    }

    @Test fun `models проверяет токен`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"data":[{"id":"gemma"}]}""").build())
        assertEquals(listOf("gemma"), client.models(address, "t"))
        server.enqueue(MockResponse.Builder().code(401).build())
        try { client.models(address, "bad"); fail() } catch (e: ChatException) { assertEquals(ChatErrorKind.UNAUTHORIZED, e.kind) }
    }

    @Test fun `тело запроса содержит системный промт и параметры`() {
        val body = client.buildBody(turns, "Будь краток", ChatParams(0.3f, 0))
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("Будь краток"))
        assertTrue(!body.contains("max_tokens")) // 0 = без ограничения
    }
}
