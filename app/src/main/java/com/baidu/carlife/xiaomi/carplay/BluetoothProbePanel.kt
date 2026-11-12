package com.baidu.carlife.xiaomi.carplay

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun BluetoothProbePanel(pin: String, validPin: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var active by remember { mutableStateOf<BluetoothReceiverProbe?>(null) }

    fun start() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            message = "手机没有蓝牙适配器"
            return
        }
        val receiver = name.trim()
        val receiverPin = pin
        val localBluetoothAddress = deviceId.trim()
        val probe = BluetoothReceiverProbe()
        active = probe
        message = "正在启动蓝牙诊断"
        val timeout = scope.launch {
            delay(45_000)
            if (active == probe) {
                message = "蓝牙诊断超时"
                active = null
                probe.close()
            }
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    probe.run(adapter, receiver, receiverPin, localBluetoothAddress, timeout::cancel) { stage ->
                        Log.i("CarNatIap2", stage)
                        scope.launch { if (active == probe) message = stage }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (active == probe) {
                    message = "蓝牙诊断结束：${error.message ?: error.javaClass.simpleName}"
                    Log.i("CarNatIap2", message)
                }
            } finally {
                timeout.cancel()
                if (active == probe) active = null
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else message = "需要附近设备权限才能测试蓝牙"
    }
    DisposableEffect(active) {
        val probe = active
        onDispose { probe?.close() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("已配对接收端的蓝牙名称") },
            enabled = active == null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (validPin) {
            OutlinedTextField(
                value = deviceId,
                onValueChange = { deviceId = it },
                label = { Text("本机蓝牙地址（主屏连接测试）") },
                enabled = active == null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            enabled = active != null || name.isNotBlank() && (pin.isEmpty() || validPin) &&
                (deviceId.isBlank() || validPin && CarPlayScreenProbe.bluetoothAddress.matches(deviceId.trim())),
            onClick = {
                val probe = active
                if (probe != null) {
                    active = null
                    probe.close()
                    message = "蓝牙诊断已停止"
                } else if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    start()
                } else {
                    permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            },
        ) {
            Text(if (active == null) "等待接收端蓝牙连接" else "停止蓝牙诊断")
        }
        if (message.isNotBlank()) Text(message)
    }
}
