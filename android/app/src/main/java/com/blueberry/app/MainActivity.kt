package com.blueberry.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.blueberry.app.ui.BlueberryTheme
import com.blueberry.app.ui.ChatListScreen
import com.blueberry.app.ui.ChatScreen
import com.blueberry.app.ui.ConnectionScreen
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BlueberryTheme { AppNav() } }
    }
}

@Composable
private fun AppNav() {
    val container = (LocalContext.current.applicationContext as BlueberryApp).container
    var start by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val configured = container.settings.address.first().isSet && container.tokens.get().isNotBlank()
        start = if (configured) "chats" else "connection"
    }
    val startRoute = start ?: return
    val nav = rememberNavController()
    NavHost(nav, startDestination = startRoute) {
        composable("chats") {
            ChatListScreen(
                onOpen = { nav.navigate("chat/$it") },
                onSettings = { nav.navigate("connection") },
            )
        }
        composable("chat/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            ChatScreen(chatId = entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
        }
        composable("connection") {
            ConnectionScreen(
                canGoBack = startRoute == "chats" || nav.previousBackStackEntry != null,
                onDone = {
                    if (!nav.popBackStack()) nav.navigate("chats") { popUpTo("connection") { inclusive = true } }
                },
            )
        }
    }
}
