package com.blueberry.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(onDone: () -> Unit, canGoBack: Boolean, vm: ConnectionViewModel = viewModel()) {
    val ctx = LocalContext.current
    val host by vm.host.collectAsStateWithLifecycle()
    val port by vm.port.collectAsStateWithLifecycle()
    val token by vm.token.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val result by vm.testResult.collectAsStateWithLifecycle()
    val defaults by vm.defaults.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    var showToken by remember { mutableStateOf(false) }

    LaunchedEffect(saved) { if (saved) onDone() }

    fun scan() {
        // Google code scanner: окно сканирования предоставляет Google Play Services, разрешение CAMERA приложению не нужно.
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(ctx, options).startScan()
            .addOnSuccessListener { code ->
                val ok = code.rawValue?.let(vm::applyQr) == true
                Toast.makeText(ctx, if (ok) "QR распознан" else "Это не QR Blueberry", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(ctx, "Сканер недоступен. Введите данные вручную.", Toast.LENGTH_LONG).show()
            }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Подключение") },
            navigationIcon = {
                if (canGoBack) IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            },
            actions = { StatusIndicator(conn) },
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Откройте на маке `blueberry qr` и отсканируйте код, либо введите адрес и токен вручную. " +
                    "Сканер QR запускается средствами Google Play Services, приложению не нужен доступ к камере.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = ::scan, Modifier.fillMaxWidth()) { Text("Сканировать QR") }

            OutlinedTextField(host, { vm.host.value = it }, label = { Text("Адрес мака (IP)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(port, { vm.port.value = it.filter(Char::isDigit).take(5) }, label = { Text("Порт") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(token, { vm.token.value = it }, label = { Text("Токен") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Скрыть" else "Показать") } })

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::test, enabled = !testing) { Text(if (testing) "Проверяю…" else "Проверить соединение") }
                Button(onClick = vm::save, enabled = host.isNotBlank() && token.isNotBlank()) { Text("Сохранить") }
            }
            result?.let {
                Text(it.message, color = if (it.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }

            Text("Новые чаты", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            OutlinedTextField(defaults.systemPrompt, { v -> vm.updateDefaults { it.copy(systemPrompt = v) } },
                label = { Text("Системный промт по умолчанию") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Text("Температура: ${"%.1f".format(defaults.temperature)}")
            Slider(defaults.temperature, { v -> vm.updateDefaults { it.copy(temperature = v) } }, valueRange = 0f..2f)
            OutlinedTextField(defaults.maxTokens.toString(), { v -> vm.updateDefaults { it.copy(maxTokens = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) } },
                label = { Text("Максимальная длина ответа (токены, 0 = без ограничения)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(defaults.contextTokens.toString(), { v -> vm.updateDefaults { it.copy(contextTokens = (v.filter(Char::isDigit).take(6).toIntOrNull() ?: 0)) } },
                label = { Text("Размер контекста сервера (токены)") }, singleLine = true,
                supportingText = { Text("Должен совпадать с MODEL_CONTEXT на маке; по нему обрезается история") },
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
    }
}
