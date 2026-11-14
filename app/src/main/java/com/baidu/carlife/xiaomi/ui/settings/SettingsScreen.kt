package com.baidu.carlife.xiaomi.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.baidu.carlife.xiaomi.ui.theme.CarNatTheme

internal enum class SettingEntry(val route: String, val title: String, val summary: String) {
    WIFI("settings/wifi", "Wi-Fi", "在系统设置中管理无线网络"),
    ACCOUNT("settings/account", "账号", "登录与同步 · 示例"),
    DEVICES("settings/devices", "已配对设备", "在系统设置中管理蓝牙设备"),
}

@Composable
internal fun SettingsScreen(onOpen: (SettingEntry) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("设置", Modifier.padding(24.dp), style = MaterialTheme.typography.headlineLarge)
        SettingEntry.entries.forEachIndexed { index, setting ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListItem(
                headlineContent = { Text(setting.title) },
                supportingContent = { Text(setting.summary) },
                modifier = Modifier.clickable(role = Role.Button) { onOpen(setting) },
            )
        }
    }
}

@Composable
internal fun SettingDetailScreen(setting: SettingEntry, onBack: () -> Unit) {
    val context = LocalContext.current
    var error by remember(setting) { mutableStateOf(false) }
    val action = when (setting) {
        SettingEntry.WIFI -> Settings.ACTION_WIFI_SETTINGS
        SettingEntry.DEVICES -> Settings.ACTION_BLUETOOTH_SETTINGS
        SettingEntry.ACCOUNT -> null
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("返回设置") }
        Text(setting.title, style = MaterialTheme.typography.headlineLarge)
        Text(
            when (setting) {
                SettingEntry.WIFI -> "让手机连接接收端热点，或接入与接收端可互通的局域网。"
                SettingEntry.DEVICES -> "首次投屏前，请在系统蓝牙设置中与接收端配对。"
                SettingEntry.ACCOUNT -> "当前版本无需登录。此页预留给账号与同步设置。"
            },
        )
        if (action != null) {
            Button(onClick = {
                error = try {
                    context.startActivity(Intent(action))
                    false
                } catch (_: ActivityNotFoundException) {
                    true
                }
            }) { Text("打开系统设置") }
        }
        if (error) Text("无法打开系统设置", color = MaterialTheme.colorScheme.error)
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SettingsPreview() {
    CarNatTheme { SettingsScreen(onOpen = {}) }
}
