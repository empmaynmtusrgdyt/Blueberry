package com.blueberry.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blueberry.app.data.ServerAddress
import com.blueberry.app.domain.ChatTurn
import com.blueberry.app.net.BlueberryClient
import com.blueberry.app.net.ChatErrorKind
import com.blueberry.app.net.ChatEvent
import com.blueberry.app.net.ChatException
import com.blueberry.app.net.ChatParams
import com.blueberry.app.net.LocalNetwork
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
import org.junit.runner.RunWith

/**
 * Подключение приложения к mock-серверу на устройстве: настоящие сокеты Android, правила cleartext
 * (network security config) и сетевой клиент целиком. Запуск: ./gradlew :app:connectedDebugAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class MockServerConnectionTest {
    private lateinit var server: MockWebServer
    private lateinit var address: ServerAddress
    private lateinit var client: BlueberryClient

    @Before fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val local = LocalNetwork(ctx)
        client = BlueberryClient(socketFactoryFor = { local.socketFactoryFor(it) }, hasLocalNetwork = { local.hasLocalNetwork() })
        server = MockWebServer().apply { start() }
        address = ServerAddress("127.0.0.1", server.port)
    }

    @After fun tearDown() { server.close() }

    @Test fun healthModelsAndStreamingChat() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"status": "ok", "model": "loaded"}""").build())
        assertTrue(client.health(address).ok)

        server.enqueue(MockResponse.Builder().body("""{"data":[{"id":"gemma"}]}""").build())
        assertEquals(listOf("gemma"), client.models(address, "tok"))

        val chunk = """data: {"choices":[{"delta":{"content":"Привет"},"finish_reason":null}]}""" + "\n\n"
        server.enqueue(MockResponse.Builder().body(": loading\n\n$chunk" + "data: [DONE]\n\n").build())
        val events = client.streamChat(address, "tok", listOf(ChatTurn("user", "hi")), "", ChatParams(0.7f, 64)).toList()
        assertEquals(ChatEvent.ModelLoading, events.first())
        assertEquals("Привет", events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text })
    }

    @Test fun wrongTokenGivesUnauthorized() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())
        try { client.models(address, "bad"); fail("ожидался 401") }
        catch (e: ChatException) { assertEquals(ChatErrorKind.UNAUTHORIZED, e.kind) }
    }
}
