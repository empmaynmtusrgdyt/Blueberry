package com.blueberry.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.blueberry.app.data.ConnState
import com.blueberry.app.net.ModelState

data class StatusUi(val label: String, val color: Color)

@Composable
fun statusUi(state: ConnState): StatusUi = when (state) {
    ConnState.NotConfigured -> StatusUi("Не настроено", MaterialTheme.colorScheme.outline)
    ConnState.Searching -> StatusUi("Ищу сервер…", Color(0xFFE0A100))
    is ConnState.Connected -> when (state.model) {
        ModelState.LOADING -> StatusUi("Модель загружается…", Color(0xFFE0A100))
        ModelState.UNLOADED -> StatusUi("Подключено · модель спит", Color(0xFF2E9E5B))
        else -> StatusUi("Подключено", Color(0xFF2E9E5B))
    }
    is ConnState.Failed -> StatusUi("Ошибка", MaterialTheme.colorScheme.error)
}

/** Состояние соединения в виде «пилюли» с цветной точкой. */
@Composable
fun StatusIndicator(state: ConnState, modifier: Modifier = Modifier) {
    val ui = statusUi(state)
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(ui.color))
            Spacer(Modifier.width(6.dp))
            Text(ui.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}
