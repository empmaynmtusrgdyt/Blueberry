package com.blueberry.app

import android.app.Application
import androidx.room.Room
import com.blueberry.app.data.ChatDatabase
import com.blueberry.app.data.ChatEngine
import com.blueberry.app.data.ConnectionRepository
import com.blueberry.app.data.SettingsStore
import com.blueberry.app.data.TokenStore
import com.blueberry.app.net.BlueberryClient
import com.blueberry.app.net.Discovery
import com.blueberry.app.net.LocalNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Простейший контейнер зависимостей приложения. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = Room.databaseBuilder(app, ChatDatabase::class.java, "blueberry.db").build()
    val settings = SettingsStore(app)
    val tokens = TokenStore(app)
    private val localNetwork = LocalNetwork(app)
    val client = BlueberryClient(
        socketFactoryFor = { localNetwork.socketFactoryFor(it) },
        hasLocalNetwork = { localNetwork.hasLocalNetwork() },
    )
    val discovery = Discovery(app)
    val connection = ConnectionRepository(settings, tokens, client, discovery, scope)
    val engine = ChatEngine(db.dao(), settings, tokens, client, scope)
}

class BlueberryApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
