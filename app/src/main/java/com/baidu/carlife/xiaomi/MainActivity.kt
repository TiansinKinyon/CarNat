package com.baidu.carlife.xiaomi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baidu.carlife.service.CapturePhase
import com.baidu.carlife.service.CaptureStatus
import com.baidu.carlife.service.CarlifeConnectService
import com.baidu.carlife.xiaomi.ui.theme.CarNatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val status by CarlifeConnectService.state.collectAsState()
            CarNatTheme {
                CaptureScreen(
                    status = status,
                    onStart = { CarlifeConnectService.startDiagnostic(this) },
                    onStop = { CarlifeConnectService.stopDiagnostic(this) },
                )
            }
        }
    }

    override fun onStop() {
        CarlifeConnectService.stopDiagnostic(this)
        super.onStop()
    }
}

@Composable
private fun CaptureScreen(status: CaptureStatus, onStart: () -> Unit, onStop: () -> Unit) {
    val phase = when (status.phase) {
        CapturePhase.IDLE -> "等待测试"
        CapturePhase.BINDING -> "正在绑定 CarWith"
        CapturePhase.WAITING_FOR_FRAMES -> "正在等待 CarWith 画面"
        CapturePhase.RECEIVING -> "正在接收 H.264 视频帧"
        CapturePhase.STOPPED -> "测试已停止"
        CapturePhase.FAILED -> "测试失败"
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
        Column(
            modifier = Modifier.padding(insets).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("CarNat", style = MaterialTheme.typography.headlineLarge)
            Text("CarWith 投屏诊断", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(phase, style = MaterialTheme.typography.titleMedium)
                    status.displayId?.let { Text("虚拟显示屏 ID  $it") }
                    Text("视频帧  ${status.frames}")
                    Text("编码数据  ${status.bytes} 字节")
                    status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            Button(onClick = if (status.active) onStop else onStart) {
                Text(if (status.active) "停止测试" else "测试 CarWith 画面")
            }
        }
    }
}
