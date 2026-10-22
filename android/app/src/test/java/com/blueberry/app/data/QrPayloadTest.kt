package com.blueberry.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPayloadTest {
    @Test fun `разбор QR от blueberry qr`() {
        val p = QrPayload.parse("""{"v":1,"name":"Blueberry","host":"192.168.43.7","port":8787,"mdns":"mac.local","token":"abc"}""")!!
        assertEquals("192.168.43.7", p.host); assertEquals(8787, p.port); assertEquals("abc", p.token)
    }

    @Test fun `посторонний QR отвергается`() {
        assertNull(QrPayload.parse("https://example.com"))
        assertNull(QrPayload.parse("""{"host":"1.2.3.4"}""")) // нет токена
        assertNull(QrPayload.parse("""{"host":"","token":"t"}"""))
        assertNull(QrPayload.parse("""{"host":"h","token":"t","port":70000}"""))
    }

    @Test fun `лишние поля игнорируются`() {
        assertEquals("h", QrPayload.parse("""{"host":"h","token":"t","extra":5}""")!!.host)
    }
}
