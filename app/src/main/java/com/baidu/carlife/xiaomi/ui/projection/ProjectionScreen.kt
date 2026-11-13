package com.baidu.carlife.xiaomi.ui.projection

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.baidu.carlife.service.CapturePhase
import com.baidu.carlife.service.CarlifeConnectService
import com.baidu.carlife.xiaomi.carplay.ReceiverSettings

@Composable
internal fun ProjectionScreen() {
    val context = LocalContext.current
    val status by CarlifeConnectService.state.collectAsState()
    var settings by remember { mutableStateOf(ReceiverSettings.load(context)) }
    var showCustomCode by remember { mutableStateOf(settings.pin.isNotBlank()) }
    var error by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) error = startProjection(context, settings)
        else error = "投屏需要附近设备权限"
    }
    Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
        Column(
            Modifier.padding(insets).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("CarNat", style = MaterialTheme.typography.headlineLarge)
            if (Build.VERSION.SDK_INT < 33) {
                Text("CarPlay 投屏需要 Android 13 或更新版本")
            } else {
                if (!status.active) {
                    OutlinedTextField(settings.name, { settings = settings.copy(name = it) },
                        label = { Text("接收端蓝牙名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(settings.address, { settings = settings.copy(address = it) },
                        label = { Text("本机蓝牙地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = {
                        showCustomCode = !showCustomCode
                        if (!showCustomCode) settings = settings.copy(pin = "")
                    }) { Text(if (showCustomCode) "使用默认网络配对码" else "使用自定义网络配对码") }
                    if (showCustomCode) {
                        OutlinedTextField(settings.pin, { settings = settings.copy(pin = it.take(16)) },
                            label = { Text("网络配对码") }, visualTransformation = PasswordVisualTransformation(),
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                Button(enabled = status.active || settings.valid, onClick = {
                    error = null
                    if (status.active) CarlifeConnectService.stopDiagnostic(context)
                    else if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        error = startProjection(context, settings)
                    } else permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }) { Text(if (status.active) "停止投屏" else "开始投屏") }
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
