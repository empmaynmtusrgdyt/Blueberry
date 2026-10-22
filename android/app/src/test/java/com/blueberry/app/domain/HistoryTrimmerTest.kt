package com.blueberry.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTrimmerTest {
    private fun turn(i: Int, len: Int = 400) = ChatTurn(if (i % 2 == 0) "user" else "assistant", "x".repeat(len))

    @Test fun `короткая история не меняется`() {
        val h = listOf(turn(0, 10), turn(1, 10), turn(2, 10))
        assertEquals(h, HistoryTrimmer.trim("sys", h, 8192, 1024))
    }

    @Test fun `старые сообщения отбрасываются, новые остаются`() {
        val h = (0 until 40).map { turn(it) } // user на чётных, 40 штук по ~164 токена
        val r = HistoryTrimmer.trim("sys", h, 2048, 512)
        assertTrue(r.size < h.size)
        assertEquals(h.last(), r.last())
        val total = r.sumOf { HistoryTrimmer.estimateTokens(it.content) }
        assertTrue("влезает в бюджет", total <= 2048 - 512)
    }

    @Test fun `разговор начинается с реплики пользователя`() {
        val h = (0 until 40).map { turn(it) }
        val r = HistoryTrimmer.trim("sys", h, 2048, 512)
        assertEquals("user", r.first().role)
    }

    @Test fun `последнее сообщение остаётся даже если оно огромное`() {
        val h = listOf(turn(0, 10), ChatTurn("user", "y".repeat(100_000)))
        val r = HistoryTrimmer.trim("sys", h, 2048, 512)
        assertEquals(listOf(h.last()), r)
    }

    @Test fun `длинный системный промт уменьшает бюджет истории`() {
        val h = (0 until 20).map { turn(it) }
        val small = HistoryTrimmer.trim("s", h, 4096, 512)
        val big = HistoryTrimmer.trim("s".repeat(4000), h, 4096, 512)
        assertTrue(big.size < small.size)
    }

    @Test fun `пустая история`() { assertEquals(emptyList<ChatTurn>(), HistoryTrimmer.trim("s", emptyList(), 100, 10)) }
}
