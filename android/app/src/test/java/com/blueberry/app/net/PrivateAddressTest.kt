package com.blueberry.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateAddressTest {
    @Test fun `локальные адреса разрешены`() {
        listOf("10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.43.1", "169.254.1.1", "127.0.0.1",
            "mac.local", "MacBook-Air.local", "localhost").forEach { assertTrue(it, PrivateAddress.isAllowedHost(it)) }
    }

    @Test fun `публичные и странные адреса запрещены`() {
        listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "192.169.0.1", "example.com", "10.0.0.256", "", "10.0.0",
            "evil.local.example.com", "192.168.1.1.evil.com").forEach { assertFalse(it, PrivateAddress.isAllowedHost(it)) }
    }
}

class LocalNetworkTest {
    private fun ip(vararg b: Int) = ByteArray(4) { b[it].toByte() }

    @Test fun `подсеть сравнивается по префиксу`() {
        assertTrue(LocalNetwork.sameSubnet(ip(192, 168, 0, 5), ip(192, 168, 0, 100), 24))
        assertFalse(LocalNetwork.sameSubnet(ip(192, 168, 1, 5), ip(192, 168, 0, 100), 24))
        assertTrue(LocalNetwork.sameSubnet(ip(10, 1, 2, 3), ip(10, 200, 0, 1), 8))
        assertTrue(LocalNetwork.sameSubnet(ip(192, 168, 43, 130), ip(192, 168, 43, 200), 25))
        assertFalse(LocalNetwork.sameSubnet(ip(192, 168, 43, 130), ip(192, 168, 43, 10), 25))
    }
}

class ChatErrorsTest {
    @Test fun `коды HTTP превращаются в понятные ошибки`() {
        assertEquals(ChatErrorKind.UNAUTHORIZED, ChatErrors.fromHttpStatus(401).kind)
        assertEquals(ChatErrorKind.BUSY, ChatErrors.fromHttpStatus(429).kind)
        assertEquals(ChatErrorKind.MODEL_LOAD_FAILED, ChatErrors.fromHttpStatus(503).kind)
        assertEquals(ChatErrorKind.UNREACHABLE, ChatErrors.fromHttpStatus(502).kind)
        assertEquals(ChatErrorKind.BAD_RESPONSE, ChatErrors.fromHttpStatus(418).kind)
    }

    @Test fun `сетевые исключения`() {
        assertEquals(ChatErrorKind.TIMEOUT, ChatErrors.from(java.net.SocketTimeoutException(), true).kind)
        assertEquals(ChatErrorKind.UNREACHABLE, ChatErrors.from(java.net.ConnectException(), true).kind)
        assertEquals(ChatErrorKind.NO_NETWORK, ChatErrors.from(java.net.ConnectException(), false).kind)
        assertEquals(ChatErrorKind.NO_NETWORK, ChatErrors.from(java.net.UnknownHostException(), false).kind)
    }

    @Test fun `у каждого вида ошибки есть сообщение`() {
        ChatErrorKind.entries.forEach { assertTrue(ChatErrors.message(it).isNotBlank()) }
    }
}
