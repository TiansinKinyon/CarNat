package com.baidu.carlife.xiaomi.ui.settings

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.baidu.carlife.xiaomi.diagnostics.ProjectionLog
import com.baidu.carlife.xiaomi.ui.theme.CarNatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProjectionLogScreen(active: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isPreview = LocalInspectionMode.current
    var recent by remember {
        mutableStateOf(
            if (isPreview) {
                "14:28:01 · 已收到 CarPlay 网络端点\n" +
                    "14:28:03 · 主屏视频 TCP 已连接，RECORD 已确认\n" +
                    "14:28:04 · 正在投屏 · 已发送 CarWith 首个视频关键帧"
            } else {
                ""
            },
        )
    }
    var refresh by remember { mutableStateOf(0) }
    var exporting by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(active, refresh) {
        if (!active || isPreview) return@LaunchedEffect
        val snapshot = withContext(Dispatchers.IO) { runCatching { ProjectionLog.snapshot() } }
        snapshot.onSuccess { recent = it.lines().takeLast(81).joinToString("\n").trimEnd() }
            .onFailure { error = "无法读取投屏日志" }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("最近记录", style = MaterialTheme.typography.titleLarge)
        Text("导出目录为「Download/CarNat」。再次导出会替换上一份手动报告。异常退出报告会在下次启动时保存到同一目录。")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = active && !exporting && Build.VERSION.SDK_INT >= 29,
                onClick = {
                    if (!isPreview && Build.VERSION.SDK_INT >= 29) {
                        exporting = true
                        error = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { ProjectionLog.export(context) } }
                            result.onSuccess { exported = it }
                                .onFailure { error = "导出失败，请检查下载目录和剩余存储空间" }
                            exporting = false
                        }
                    }
                },
            ) { Text(if (exporting) "正在导出" else "导出日志") }
            OutlinedButton(onClick = { refresh++ }, enabled = active) { Text("刷新") }
            val uri = exported
            if (uri != null) {
                OutlinedButton(
                    enabled = active && !exporting,
                    onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri("CarNat 日志", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(Intent.createChooser(share, "分享投屏日志")) }
                            .onFailure { error = "无法打开分享面板" }
                    },
                ) { Text("分享") }
            }
        }
        if (Build.VERSION.SDK_INT < 29) Text("导出到下载目录需要 Android 10 或更高版本。")
        val message = error
        if (message != null) {
            Text(message, color = MaterialTheme.colorScheme.error)
        } else if (exported != null) {
            Text("已导出到「Download/CarNat」", color = MaterialTheme.colorScheme.primary)
        }
        Card(Modifier.fillMaxWidth()) {
            SelectionContainer {
                Text(
                    text = recent.ifEmpty { "暂无投屏日志" },
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ProjectionLogPreview() {
    CarNatTheme(dynamicColor = false) {
        ProjectionLogScreen(active = true)
    }
}
