package com.baidu.carlife.xiaomi.carplay

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ReceiverProbe() {
    if (Build.VERSION.SDK_INT < 33) return

    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("7000") }
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val portNumber = port.toIntOrNull()
    val valid = host.isNotBlank() && portNumber != null && portNumber in 1..65535 &&
        pin.length in 4..16 && pin.all { it in '0'..'9' || it == '-' }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("CarPlay 接收端配对测试", style = MaterialTheme.typography.titleMedium)
        BluetoothProbePanel()
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("接收端 IP 或主机名") },
            enabled = !running,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = { Text("RTSP 端口") },
            enabled = !running,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.take(16) },
            label = { Text("接收端配对 PIN") },
            enabled = !running,
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !running && valid,
            onClick = {
                val endpoint = host.trim()
                val endpointPort = checkNotNull(portNumber)
                val receiverPin = pin
                pin = ""
                running = true
                message = ""
                scope.launch {
                    try {
                        message = withContext(Dispatchers.IO) {
                            CarPlayReceiverProbe.verify(endpoint, endpointPort, receiverPin) { stage ->
                                scope.launch { if (running) message = stage }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        message = "配对测试失败：${error.message ?: error.javaClass.simpleName}"
                    } finally {
                        running = false
                    }
                }
            },
        ) {
            Text(if (running) "正在验证" else "验证接收端配对")
        }
        if (message.isNotBlank()) Text(message)
    }
}
