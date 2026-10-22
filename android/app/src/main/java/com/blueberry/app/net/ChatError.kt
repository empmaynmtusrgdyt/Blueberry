package com.blueberry.app.net

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class ChatErrorKind { NO_NETWORK, UNAUTHORIZED, UNREACHABLE, TIMEOUT, BUSY, MODEL_LOAD_FAILED, FORBIDDEN_HOST, BAD_RESPONSE }

class ChatException(val kind: ChatErrorKind, val detail: String? = null, cause: Throwable? = null) :
    IOException(detail ?: kind.name, cause)

object ChatErrors {
    /** Понятное пользователю сообщение. */
    fun message(kind: ChatErrorKind): String = when (kind) {
        ChatErrorKind.NO_NETWORK -> "Нет подключения к сети. Подключитесь к Wi-Fi мака или включите точку доступа и дождитесь подключения мака."
        ChatErrorKind.UNAUTHORIZED -> "Неверный токен. Отсканируйте QR заново или введите токен вручную."
        ChatErrorKind.UNREACHABLE -> "Сервер недоступен. Проверьте, что мак включён, режим blueberry on активен и вы в одной сети."
        ChatErrorKind.TIMEOUT -> "Сервер не ответил вовремя. Попробуйте ещё раз."
        ChatErrorKind.BUSY -> "Сервер занят другими запросами. Повторите через несколько секунд."
        ChatErrorKind.MODEL_LOAD_FAILED -> "Не удалось загрузить модель на сервере."
        ChatErrorKind.FORBIDDEN_HOST -> "Адрес не из локальной сети: подключение запрещено."
        ChatErrorKind.BAD_RESPONSE -> "Сервер вернул неожиданный ответ."
    }

    fun fromHttpStatus(code: Int): ChatException = when (code) {
        401, 403 -> ChatException(ChatErrorKind.UNAUTHORIZED)
        429 -> ChatException(ChatErrorKind.BUSY)
        503 -> ChatException(ChatErrorKind.MODEL_LOAD_FAILED)
        502, 504 -> ChatException(ChatErrorKind.UNREACHABLE, "HTTP $code")
        else -> ChatException(ChatErrorKind.BAD_RESPONSE, "HTTP $code")
    }

    /** Приводит любое сетевое исключение к ChatException. [hasLocalNetwork] — есть ли Wi-Fi/Ethernet. */
    fun from(t: Throwable, hasLocalNetwork: Boolean): ChatException = when {
        t is ChatException -> t
        t is SocketTimeoutException -> ChatException(ChatErrorKind.TIMEOUT, cause = t)
        t is UnknownHostException || t is ConnectException || t is NoRouteToHostException ->
            ChatException(if (hasLocalNetwork) ChatErrorKind.UNREACHABLE else ChatErrorKind.NO_NETWORK, cause = t)
        t is IOException -> ChatException(if (hasLocalNetwork) ChatErrorKind.UNREACHABLE else ChatErrorKind.NO_NETWORK, t.message, t)
        else -> ChatException(ChatErrorKind.BAD_RESPONSE, t.message, t)
    }
}
