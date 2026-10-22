package com.blueberry.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "blueberry_settings")

/** Параметры подключения (без токена: токен лежит в [TokenStore]). */
data class ServerAddress(val host: String, val port: Int, val mdns: String = "") {
    val isSet get() = host.isNotBlank()
    val baseUrl get() = "http://${if (host.contains(':')) "[$host]" else host}:$port"
}

/** Настройки по умолчанию для новых чатов + размер контекста сервера. */
data class ChatDefaults(
    val systemPrompt: String = "Ты — полезный ассистент. Отвечай кратко и по делу.",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 1024,
    val contextTokens: Int = 8192,
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val host = stringPreferencesKey("host")
        val port = intPreferencesKey("port")
        val mdns = stringPreferencesKey("mdns")
        val systemPrompt = stringPreferencesKey("default_system_prompt")
        val temperature = floatPreferencesKey("default_temperature")
        val maxTokens = intPreferencesKey("default_max_tokens")
        val contextTokens = intPreferencesKey("context_tokens")
    }

    val address: Flow<ServerAddress> = context.dataStore.data.map { p ->
        ServerAddress(p[Keys.host].orEmpty(), p[Keys.port] ?: 8787, p[Keys.mdns].orEmpty())
    }

    val defaults: Flow<ChatDefaults> = context.dataStore.data.map { p ->
        val d = ChatDefaults()
        ChatDefaults(
            systemPrompt = p[Keys.systemPrompt] ?: d.systemPrompt,
            temperature = p[Keys.temperature] ?: d.temperature,
            maxTokens = p[Keys.maxTokens] ?: d.maxTokens,
            contextTokens = p[Keys.contextTokens] ?: d.contextTokens,
        )
    }

    suspend fun saveAddress(a: ServerAddress) {
        context.dataStore.edit {
            it[Keys.host] = a.host; it[Keys.port] = a.port; it[Keys.mdns] = a.mdns
        }
    }

    suspend fun saveDefaults(d: ChatDefaults) {
        context.dataStore.edit {
            it[Keys.systemPrompt] = d.systemPrompt; it[Keys.temperature] = d.temperature
            it[Keys.maxTokens] = d.maxTokens; it[Keys.contextTokens] = d.contextTokens
        }
    }
}

/** Токен доступа в EncryptedSharedPreferences (ключ в Android Keystore). */
class TokenStore(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "blueberry_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun get(): String = prefs.getString(KEY, "").orEmpty()
    fun set(token: String) { prefs.edit().putString(KEY, token.trim()).apply() }

    private companion object { const val KEY = "token" }
}

/** Содержимое QR, который показывает `blueberry qr`. */
@Serializable
data class QrPayload(
    val v: Int = 1,
    val name: String = "Blueberry",
    val host: String = "",
    val port: Int = 8787,
    val mdns: String = "",
    val token: String = "",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** null — если это не QR Blueberry. */
        fun parse(text: String): QrPayload? = try {
            json.decodeFromString<QrPayload>(text.trim()).takeIf { it.host.isNotBlank() && it.token.isNotBlank() && it.port in 1..65535 }
        } catch (e: Exception) {
            null
        }
    }
}
