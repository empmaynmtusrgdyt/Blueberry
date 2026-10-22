package com.blueberry.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    private fun events(vararg lines: String): List<SseEvent> {
        val p = SseParser()
        return lines.mapNotNull { p.feedLine(it) } + listOfNotNull(p.flush())
    }

    @Test fun `событие завершается пустой строкой`() {
        assertEquals(listOf(SseEvent.Data("{\"a\":1}")), events("data: {\"a\":1}", ""))
    }

    @Test fun `многострочные data склеиваются через перевод строки`() {
        assertEquals(listOf(SseEvent.Data("a\nb")), events("data: a", "data: b", ""))
    }

    @Test fun `комментарий проходит как Comment`() {
        assertEquals(listOf(SseEvent.Comment("loading")), events(": loading", ""))
    }

    @Test fun `CRLF и поле без пробела после двоеточия`() {
        assertEquals(listOf(SseEvent.Data("x")), events("data:x\r", "\r"))
    }

    @Test fun `пустая строка без данных ничего не даёт`() {
        assertNull(SseParser().feedLine(""))
    }

    @Test fun `неизвестные поля игнорируются`() {
        assertEquals(listOf(SseEvent.Data("x")), events("event: message", "id: 5", "data: x", ""))
    }

    @Test fun `flush отдаёт незавершённое событие`() {
        assertEquals(listOf(SseEvent.Data("tail")), events("data: tail"))
    }
}

class ChatChunkParserTest {
    @Test fun `извлекает текст дельты`() {
        val r = ChatChunkParser.parse("""{"choices":[{"delta":{"content":"Привет"},"finish_reason":null}]}""")
        assertEquals(ChunkResult.Delta("Привет", false), r)
    }

    @Test fun `finish_reason помечает конец`() {
        val r = ChatChunkParser.parse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""")
        assertEquals(ChunkResult.Delta("", true), r)
    }

    @Test fun `DONE`() { assertEquals(ChunkResult.Done, ChatChunkParser.parse("[DONE]")) }

    @Test fun `ошибка сервера`() {
        val r = ChatChunkParser.parse("""{"error":"model_load_failed"}""")
        assertTrue(r is ChunkResult.Error && r.message.contains("model_load_failed"))
    }

    @Test fun `мусор не роняет парсер`() {
        assertTrue(ChatChunkParser.parse("не json") is ChunkResult.Error)
    }

    @Test fun `чанк без choices безопасен`() {
        assertEquals(ChunkResult.Delta("", false), ChatChunkParser.parse("""{"id":"x"}"""))
    }
}
