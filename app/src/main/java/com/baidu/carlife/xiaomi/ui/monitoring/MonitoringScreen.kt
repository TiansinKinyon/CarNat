package com.baidu.carlife.xiaomi.ui.monitoring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baidu.carlife.service.CapturePhase
import com.baidu.carlife.service.CaptureStatus
import com.baidu.carlife.service.CarlifeConnectService
import com.baidu.carlife.service.ReceiverConnection
import com.baidu.carlife.xiaomi.carplay.CarPlayAudioInfo
import com.baidu.carlife.xiaomi.carplay.CarPlayResolution
import com.baidu.carlife.xiaomi.carplay.CarPlayMicrophone
import com.baidu.carlife.xiaomi.carplay.ReceiverDiagnostics
import com.baidu.carlife.xiaomi.carplay.ReceiverWifi
import com.baidu.carlife.xiaomi.ui.MicrophoneLevelMeter
import com.baidu.carlife.xiaomi.ui.liveMicrophoneSample
import com.baidu.carlife.xiaomi.ui.theme.CarNatTheme

@Composable
internal fun MonitoringScreen(active: Boolean) {
    val capture by CarlifeConnectService.state.collectAsStateWithLifecycle()
    val receiver by CarlifeConnectService.receiverDiagnostics.collectAsStateWithLifecycle()
    MonitoringContent(capture, receiver, active)
}

@Composable
private fun MonitoringContent(capture: CaptureStatus, receiver: ReceiverDiagnostics, active: Boolean) {
    val wifi = receiver.wifi
    var showPassword by remember(wifi, active) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MonitoringSection("视频") {
            Metric("输出分辨率", resolutionLabel(capture.outputWidth, capture.outputHeight))
            Metric("输出帧率上限", capture.encoderFpsLimit?.let { "$it fps" })
            Metric("实时帧率", capture.outputFps?.let { "%.1f fps".format(it) })
            Metric("视频编码", capture.videoCodec)
            Metric("视频目标码率", capture.targetVideoBitrateKbps?.let { "$it Kbps" })
            Metric("视频输出码率", capture.outputMbps?.let { "%.2f Mbps".format(it) })
            Metric("累计输出帧数", capture.frames.takeIf { capture.outputFps != null || it > 0 }?.toString())
            Metric("虚拟屏 ID", capture.displayId?.toString())
            Metric("请求分辨率", resolutionLabel(receiver.resolution?.width, receiver.resolution?.height))
            Metric("接收帧率上限", receiver.resolution?.maxFps?.let { "$it fps" })
            if (capture.receiverDisplayChanged) {
                Text("车机显示参数已变更，重新开始投屏后应用新参数。")
            }
            val connectionStatus = when {
                capture.error != null -> capture.error
                capture.active && capture.receiverConnection == ReceiverConnection.CONNECTED -> "连接成功"
                else -> capture.receiverStatus
            }
            Metric("连接状态", connectionStatus)
        }

        MonitoringSection("音频") {
            AudioMetrics("媒体", receiver.mediaAudio)
            AudioMetrics("导航", receiver.navigationAudio)
            MicrophoneMetrics()
        }

        MonitoringSection("连接") {
            Metric("车载蓝牙名称", receiver.receiverName)
            Metric("车载蓝牙地址", receiver.receiverAddress)
            Metric("本机蓝牙地址", receiver.phoneAddress)
            Metric("蓝牙传输协议", receiver.receiverAddress?.let { "经典蓝牙 · RFCOMM / iAP2" })
            Metric("车载 Wi-Fi 名称", wifi?.ssid)
            Metric("Wi-Fi 安全性", wifi?.let {
                when (it.security) {
                    0 -> "开放"
                    2 -> "WPA/WPA2"
                    3 -> "WPA2/WPA3 过渡模式"
                    4 -> "WPA3"
                    else -> "类型 ${it.security}"
                }
            })
            Metric("热点信道", wifi?.channel?.toString())
            ListItem(
                headlineContent = { Text("Wi-Fi 密码") },
                supportingContent = {
                    Text(
                        when {
                            wifi == null -> "N/A"
                            wifi.passphrase.isEmpty() -> "无密码"
                            active && showPassword -> wifi.passphrase
                            else -> "••••••••"
                        },
                    )
                },
                trailingContent = wifi?.takeIf { it.passphrase.isNotEmpty() }?.let {
                    {
                        TextButton(onClick = { showPassword = !showPassword }, enabled = active) {
                            Text(if (showPassword) "隐藏" else "显示")
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            Metric("车载 IP 地址", receiver.host)
            Metric("CarPlay 控制端口", receiver.port?.toString())
            Metric("投屏网络接口", receiver.wifiInterface)
            Metric("手机投屏 IP 地址", receiver.phoneIps.takeIf { it.isNotEmpty() }?.joinToString("\n"))
        }
    }
}

@Composable
private fun MicrophoneMetrics() {
    val stream by CarPlayMicrophone.active.collectAsStateWithLifecycle()
    val microphone = stream
    val reading = liveMicrophoneSample(microphone)
    Metric("车机麦克风上行状态", when {
        microphone == null -> "语音流未建立"
        reading == null -> "等待车机音频"
        else -> "正在接收"
    })
    AudioMetrics("车机麦克风", microphone?.format)
    ListItem(
        headlineContent = { Text("实时麦克风音量") },
        supportingContent = {
            MicrophoneLevelMeter(reading?.level ?: 0f, Modifier.padding(top = 8.dp))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun AudioMetrics(label: String, stream: CarPlayAudioInfo?) {
    Metric("${label}音频格式", stream?.let { "${it.codec} · ${it.sampleRate} Hz · ${it.channels} 声道" })
    Metric("${label}缓冲请求", stream?.let { "${it.bufferMs} ms" })
    Metric("$label UDP 端口", stream?.port?.toString())
}

@Composable
private fun MonitoringSection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp).semantics { heading() },
        )
        content()
    }
}

@Composable
private fun Metric(label: String, value: String?) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(value ?: "N/A") },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

private fun resolutionLabel(width: Int?, height: Int?): String? =
    if (width != null && height != null) "${width} × ${height}" else null

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun MonitoringPreview() {
    CarNatTheme {
        MonitoringContent(
            CaptureStatus(
                phase = CapturePhase.RECEIVING,
                frames = 1253,
                displayId = 42,
                outputWidth = 1920,
                outputHeight = 766,
                outputFps = 29.8,
                encoderFpsLimit = 60,
                targetVideoBitrateKbps = 4_000,
                outputMbps = 3.84,
                receiverStatus = "正在投屏",
            ),
            ReceiverDiagnostics(CarPlayResolution(1920, 766, 60), ReceiverWifi("CarPlay", "preview-password", 2)),
            active = true,
        )
    }
}
