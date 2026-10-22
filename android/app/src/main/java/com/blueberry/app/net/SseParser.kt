package com.blueberry.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Событие SSE-потока. */
sealed interface SseEvent {
    data class Data(val data: String) : SseEvent
    data class Comment(val text: String) : SseEvent
}

/**
 * Построчный разбор Server-Sent Events. Строки подаются без перевода строки.
 * Пустая строка завершает событие; строки `:` — комментарии (прокси шлёт `: loading` на холодном старте).
 */
class SseParser {
    private val data = StringBuilder()
    private var hasData = false

    fun feedLine(rawLine: String): SseEvent? {
        val line = rawLine.trimEnd('\r')
        if (line.isEmpty()) {
            if (!hasData) return null
            val event = SseEvent.Data(data.toString())
            data.clear(); hasData = false
            return event
        }
        if (line.startsWith(":")) return SseEvent.Comment(line.substring(1).trim())
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        if (field == "data") {
            if (hasData) data.append('\n')
            data.append(value); hasData = true
        }
        return null
    }

    /** Конец потока без завершающей пустой строки: отдать накопленное событие. */
    fun flush(): SseEvent? = if (hasData) SseEvent.Data(data.toString()).also { data.clear(); hasData = false } else null
}

/** Результат разбора одного JSON-чанка chat.completion.chunk. */
sealed interface ChunkResult {
    data class Delta(val text: String, val finished: Boolean) : ChunkResult
    data class Error(val message: String) : ChunkResult
    data object Done : ChunkResult
}

object ChatChunkParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(data: String): ChunkResult {
        if (data.trim() == "[DONE]") return ChunkResult.Done
        val root = try {
            json.parseToJsonElement(data).jsonObject
        } catch (e: Exception) {
            return ChunkResult.Error("Некорректный ответ сервера")
        }
        root["error"]?.let { err ->
            val msg = (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                ?: (err as? JsonPrimitive)?.contentOrNull ?: "Ошибка сервера"
            return ChunkResult.Error(msg)
        }
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return ChunkResult.Delta("", false)
        val text = choice["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        val finished = choice["finish_reason"]?.let { it !is kotlinx.serialization.json.JsonNull } == true
        return ChunkResult.Delta(text, finished)
    }
}
