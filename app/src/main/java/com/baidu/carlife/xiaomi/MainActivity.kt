package com.baidu.carlife.xiaomi

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.baidu.carlife.service.CarlifeConnectService
import com.baidu.carlife.xiaomi.diagnostics.ProjectionLog
import com.baidu.carlife.xiaomi.ui.CarNatApp
import com.baidu.carlife.xiaomi.ui.theme.CarNatTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CarNatTheme {
                CarNatApp()
                StartupDialogs()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                CarlifeConnectService.state.collect {
                    if (CarlifeConnectService.claimCarWithLaunch(this@MainActivity)) openCarWith()
                }
            }
        }
    }

    private fun openCarWith() {
        val entry = ComponentName("com.miui.carlink", "com.miui.carlink.castfwk.usb.CarlifeAdbActivity")
        try {
            ProjectionLog.expectCarWithRestart(this)
            startActivity(Intent().setComponent(entry))
        } catch (error: ActivityNotFoundException) {
            ProjectionLog.failure("打开 CarWith 失败", error)
            CarlifeConnectService.reportCarWithLaunchFailure(this, "当前 CarWith 版本没有公开启动入口")
        } catch (error: SecurityException) {
            ProjectionLog.failure("CarWith 启动入口权限不足", error)
            CarlifeConnectService.reportCarWithLaunchFailure(this, "CarWith 未开放投屏启动入口")
        }
    }
}

@Composable
private fun StartupDialogs() {
    var step by rememberSaveable { mutableIntStateOf(0) }
    when (step) {
        0 -> StartupDialog(
            icon = R.drawable.ic_warning,
            title = "安全驾驶须知",
            message = "本应用程序并未获得手机厂商与汽车厂商的官方支持，请勿依赖该软件。\n\n" +
                    "请严格遵守交通法规，保持安全驾驶，严禁在行驶途中操作本应用程序。\n\n" +
                    "若在驾驶途中遇到断开连接等故障，严禁在行驶途中调试本软件，在确认安全的前提下驻车进行检查。\n" +
                    "若行驶中导航功能失效，应当按照当前道路与交通标志要求安全行驶，在确认安全的前提下驻车进行检查。",
            confirmLabel = "确定",
            onClose = { step = 1 },
        )
        1 -> StartupDialog(
            icon = R.drawable.ic_gavel,
            title = "免责声明",
            message = "本软件（或项目）仅供个人学习、技术研究与交流使用，严禁用于任何商业用途或非法用途。使用本软件所产生的一切后果及法律责任均由使用者本人承担，作者及贡献者不承担任何直接或间接的责任。请在下载后 24 小时内从您的设备中删除本软件。如您不同意上述条款，请立即停止使用并卸载。",
            confirmLabel = "确定",
            onClose = { step = 2 },
        )
    }
}

@Composable
private fun StartupDialog(
    @DrawableRes icon: Int,
    title: String,
    message: String,
    confirmLabel: String,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(painterResource(icon), contentDescription = null) },
        title = title.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        text = message.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = onClose) { Text(confirmLabel) } },
    )
}
