package com.baidu.carlife.xiaomi.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.baidu.carlife.xiaomi.R
import com.baidu.carlife.xiaomi.ui.theme.CarNatTheme

internal enum class SettingEntry(val route: String, val title: String) {
    WIFI("settings/wifi", "Wi-Fi"),
    ACCOUNT("settings/account", "账号"),
    DEVICES("settings/devices", "已配对设备"),
}

@Composable
internal fun SettingsScreen(onOpen: (SettingEntry) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            SettingEntry.entries.forEachIndexed { index, setting ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListItem(
                    leadingContent = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
                    headlineContent = { Text(setting.title) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(role = Role.Button) { onOpen(setting) },
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SettingsPreview() {
    CarNatTheme { SettingsScreen(onOpen = {}) }
}
