package com.blueberry.app.domain

data class ChatTurn(val role: String, val content: String) // role: "user" | "assistant"

/**
 * Обрезка истории под контекст модели. Системный промт всегда остаётся, последнее сообщение пользователя
 * тоже; лишние самые старые сообщения отбрасываются. Оценка токенов грубая (~2.5 символа на токен
 * для русского текста), с запасом.
 */
object HistoryTrimmer {
    fun estimateTokens(text: String): Int = (text.length * 2 + 4) / 5 + 4

    /**
     * @param contextTokens размер контекста модели на сервере
     * @param replyTokens   резерв под ответ (max_tokens, а если не задан, разумное значение)
     */
    fun trim(
        systemPrompt: String,
        turns: List<ChatTurn>,
        contextTokens: Int,
        replyTokens: Int,
    ): List<ChatTurn> {
        if (turns.isEmpty()) return turns
        var budget = contextTokens - replyTokens - estimateTokens(systemPrompt) - SAFETY_TOKENS
        val kept = ArrayDeque<ChatTurn>()
        for (turn in turns.asReversed()) {
            val cost = estimateTokens(turn.content)
            if (kept.isNotEmpty() && cost > budget) break
            kept.addFirst(turn)
            budget -= cost
        }
        // разговор должен начинаться с реплики пользователя
        while (kept.size > 1 && kept.first().role != "user") kept.removeFirst()
        return kept.toList()
    }

    private const val SAFETY_TOKENS = 64
}
