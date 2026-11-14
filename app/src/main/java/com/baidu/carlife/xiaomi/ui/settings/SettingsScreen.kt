package com.baidu.carlife.xiaomi.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.baidu.carlife.xiaomi.ui.theme.CarNatTheme

internal enum class SettingEntry(val route: String, val title: String) {
    WIFI("settings/wifi", "Wi-Fi"),
    ACCOUNT("settings/account", "账号"),
    DEVICES("settings/devices", "已配对设备"),
}

@Composable
internal fun SettingsScreen(onOpen: (SettingEntry) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingEntry.entries.forEachIndexed { index, setting ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListItem(
                headlineContent = { Text(setting.title) },
                modifier = Modifier.clickable(role = Role.Button) { onOpen(setting) },
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SettingsPreview() {
    CarNatTheme { SettingsScreen(onOpen = {}) }
}
