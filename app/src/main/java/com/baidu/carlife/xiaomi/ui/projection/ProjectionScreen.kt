package com.baidu.carlife.xiaomi.ui.projection

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.baidu.carlife.service.CapturePhase
import com.baidu.carlife.service.CaptureStatus
import com.baidu.carlife.service.CarlifeConnectService
import com.baidu.carlife.xiaomi.carplay.ReceiverSettings
import com.baidu.carlife.xiaomi.ui.theme.CarNatTheme

@Composable
internal fun ProjectionScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by CarlifeConnectService.state.collectAsState()
    var settings by remember { mutableStateOf(ReceiverSettings.load(context)) }
    var showCustomCode by remember { mutableStateOf(settings.pin.isNotBlank()) }
    var error by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<List<PairedReceiver>?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            devices = pairedReceivers(context)
            expanded = true
        }
        else error = "投屏需要附近设备权限"
    }
    ProjectionContent(
        modifier = modifier,
        status = status,
        settings = settings,
        showCustomCode = showCustomCode,
        error = error,
        supported = Build.VERSION.SDK_INT >= 33,
        receiverPicker = {
            val selectedName = settings.name.takeIf { settings.receiverAddress.isNotEmpty() }.orEmpty()
            ReceiverPicker(
                selectedName, devices, expanded, onDismiss = { expanded = false },
                onOpen = {
                    error = null
                    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        devices = pairedReceivers(context)
                        expanded = true
                    } else {
                        permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                },
                onSelect = { device ->
                    val updated = settings.copy(name = device.name, receiverAddress = device.address)
                    if (updated.saveReceiver(context)) settings = updated
                    else error = "无法保存接收端"
                    expanded = false
                },
            )
        },
        onSettingsChange = { settings = it },
        onToggleCustomCode = {
            showCustomCode = !showCustomCode
            if (!showCustomCode) settings = settings.copy(pin = "")
        },
        onProjectionClick = {
            error = null
            if (status.active) {
                CarlifeConnectService.stopDiagnostic(context)
            } else if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                val receiver = pairedReceivers(context)?.firstOrNull {
                    it.address.equals(settings.receiverAddress, ignoreCase = true)
                }
                error = if (receiver == null) "请开启蓝牙并选择已配对的接收端"
                else startProjection(context, settings.copy(name = receiver.name))
            } else {
                permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        },
    )
}

@Composable
private fun ProjectionContent(
    status: CaptureStatus,
    settings: ReceiverSettings,
    showCustomCode: Boolean,
    error: String?,
    supported: Boolean,
    receiverPicker: @Composable () -> Unit,
    onSettingsChange: (ReceiverSettings) -> Unit,
    onToggleCustomCode: () -> Unit,
    onProjectionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("CarNat", style = MaterialTheme.typography.headlineLarge)
        if (!supported) {
            Text("CarPlay 投屏需要 Android 13 或更新版本")
        } else {
            if (!status.active) {
                receiverPicker()
                OutlinedTextField(settings.address, { onSettingsChange(settings.copy(address = it)) },
                    label = { Text("本机蓝牙地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = onToggleCustomCode) { Text(if (showCustomCode) "使用默认网络配对码" else "使用自定义网络配对码") }
                if (showCustomCode) {
                    OutlinedTextField(settings.pin, { onSettingsChange(settings.copy(pin = it.take(16))) },
                        label = { Text("网络配对码") }, visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
            Button(enabled = status.active || (settings.valid && settings.receiverAddress.isNotBlank()), onClick = onProjectionClick) { Text(if (status.active) "停止投屏" else "开始投屏") }
            val message = error ?: status.error ?: status.receiverStatus ?: when (status.phase) {
                CapturePhase.ARMED -> "正在启动 CarWith"
                CapturePhase.BINDING, CapturePhase.WAITING_FOR_FRAMES -> "正在准备 CarWith 画面"
                CapturePhase.STOPPED -> "投屏已停止"
                else -> null
            }
            if (message != null) Text(message)
        }
    }
}

private fun startProjection(context: android.content.Context, settings: ReceiverSettings): String? {
    if (!settings.valid || !settings.save(context)) return "无法保存接收端设置"
    val entry = ComponentName("com.miui.carlink", "com.miui.carlink.castfwk.usb.CarlifeAdbActivity")
    CarlifeConnectService.startDiagnostic(context)
    return try {
        context.startActivity(Intent().setComponent(entry))
        null
    } catch (_: ActivityNotFoundException) {
        CarlifeConnectService.stopDiagnostic(context)
        "当前 CarWith 版本没有公开启动入口"
    } catch (_: SecurityException) {
        CarlifeConnectService.stopDiagnostic(context)
        "CarWith 未开放投屏启动入口"
    }
}

@Preview(name = "待连接", showBackground = true, widthDp = 360)
@Composable
private fun ProjectionReadyPreview() {
    CarNatTheme { ProjectionReadyPreviewContent() }
}

@Preview(name = "投屏中", showBackground = true, widthDp = 360)
@Composable
private fun ProjectionActivePreview() {
    CarNatTheme {
        ProjectionPreviewContent(CaptureStatus(phase = CapturePhase.RECEIVING, receiverStatus = "正在投屏"))
    }
}

@Composable
internal fun ProjectionReadyPreviewContent() {
    ProjectionPreviewContent(CaptureStatus())
}

@Composable
private fun ProjectionPreviewContent(status: CaptureStatus) {
    ProjectionContent(
        status = status,
        settings = ReceiverSettings(name = "CarPlay 接收端", address = "02:12:34:56:78:90", receiverAddress = "02:00:00:00:00:01"),
        showCustomCode = false,
        error = null,
        supported = true,
        receiverPicker = { ReceiverPicker("CarPlay 接收端", emptyList(), false, {}, {}, {}) },
        onSettingsChange = {},
        onToggleCustomCode = {},
        onProjectionClick = {},
    )

}

private data class PairedReceiver(val name: String, val address: String)

@SuppressLint("MissingPermission")
private fun pairedReceivers(context: Context): List<PairedReceiver>? = try {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    if (adapter?.isEnabled != true) null
    else adapter.bondedDevices.orEmpty().map {
        PairedReceiver(it.name?.takeIf(String::isNotBlank) ?: "未命名设备", it.address)
    }.sortedWith(compareBy({ it.name }, { it.address }))
} catch (_: SecurityException) {
    null
}

@Composable
private fun ReceiverPicker(
    selectedName: String,
    devices: List<PairedReceiver>?,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onSelect: (PairedReceiver) -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Text(selectedName.ifBlank { "选择已配对的蓝牙接收端" })
            Text(" ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            if (devices.isNullOrEmpty()) {
                DropdownMenuItem(
                    text = { Text(if (devices == null) "请开启蓝牙并检查附近设备权限" else "请先在系统蓝牙设置中配对") },
                    onClick = onDismiss,
                )
            }
            devices.orEmpty().forEach { device ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(device.name)
                            Text(device.address, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = { onSelect(device) },
                )
            }
        }
    }
}
