package com.baidu.carlife.xiaomi

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        setContent { CarNatTheme { CarNatApp() } }
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
