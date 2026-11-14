package com.baidu.carlife.xiaomi.ui.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.baidu.carlife.xiaomi.ui.theme.CarNatTheme

@Composable
internal fun HelpScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        HelpSection(
            "首次连接",
            "先在系统蓝牙设置中配对接收端，并让手机接入接收端热点或可互通的局域网。回到「连接」页选择已配对的接收端并填写本机蓝牙地址，然后点「开始投屏」。",
        )
        HelpSection(
            "网络配对码",
            "通常可以使用默认配对码。如果接收端要求特定的网络配对码，可以在「连接」页展开自定义输入。",
        )
        HelpSection(
            "连接中断",
            "确认手机仍能访问接收端所在网络。如果画面没有恢复，停止投屏后重试，并记下页面提示，便于排查。",
        )
    }
}

@Composable
private fun HelpSection(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun HelpPreview() {
    CarNatTheme { HelpScreen() }
}
