package com.baidu.carlife.xiaomi.ui.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.baidu.carlife.xiaomi.R
import com.baidu.carlife.xiaomi.ui.theme.CarNatTheme

@Composable
internal fun HelpScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HelpSection(
            "安全驾驶须知",
            "本应用程序并未获得手机厂商与汽车厂商的官方支持，请勿依赖该软件。\n\n" +
                    "请严格遵守交通法规，保持安全驾驶，严禁在行驶途中操作本应用程序。\n\n" +
                    "若在驾驶途中遇到断开连接等故障，严禁在行驶途中调试本软件，在确认安全的前提下驻车进行检查。\n" +
                    "若行驶中导航功能失效，应当按照当前道路与交通标志要求安全行驶，在确认安全的前提下驻车进行检查。",
            leadingIcon = { Icon(painterResource(R.drawable.ic_warning), contentDescription = null) },
        )

        HelpSection(
            "免责声明",
            "本软件（或项目）仅供个人学习、技术研究与交流使用，严禁用于任何商业用途或非法用途。使用本软件所产生的一切后果及法律责任均由使用者本人承担，作者及贡献者不承担任何直接或间接的责任。请在下载后 24 小时内从您的设备中删除本软件。如您不同意上述条款，请立即停止使用并卸载。",
            leadingIcon = { Icon(painterResource(R.drawable.ic_gavel), contentDescription = null) }
        )

        HelpSection(
            "CarWith 发起方式进行连接（推荐）",
            "使用 CarWith 发起方式支持后续上车自动连接，并且无需操作本软件。建议在连接前先填写本机蓝牙地址，部分车机可能需要该内容。\n\n" +
                    "请在 CarWith > CarLife 连接 > 无线连接 > 车辆热点 选择车机蓝牙并打开WLAN，然后开始连接。\n\n" +
                    "自动连接：连接成功后，在 CarWith 我的车 中点击相应车辆，打开“无线自动连接”（默认开启）",
            leadingIcon = { Icon(painterResource(R.drawable.ic_link), contentDescription = null) }
        )

        HelpSection(
            "CarNat 接管连接",
            "请先打开蓝牙，完成手机和车机的蓝牙配对。然后前往手机系统设置，在 系统设置 > 关于手机 里找到本机蓝牙地址。\n\n" +
                    "小米 HyperOS：我的设备 > 全部参数与信息 > 状态信息 > 蓝牙地址\n\n" +
                    "在“连接”页面，选择对应的车载蓝牙名称，并填写本机蓝牙地址即可开始投屏。尽量同时发起车机端和手机端的连接请求，连接过程中请留意并允许手机连接到车机的热点。\n\n" +
                    "CarWith 启动过程中 CarNat 自动退回到桌面是正常现象，启动完成后就可以返回 CarNat 主界面\n\n" +
                    "※ 部分车机可能需要预先手动连接热点，请打开车机的热点并在手机上完成连接，然后再发起连接请求。\n\n" +
                    "加快连接速度：由于 CarWith 在启动时会先强行停止 CarLife 组件（CarNat），然后再重新启动。为实现跟随车机的分辨率与帧率设置，会在正式连接前先进行一次 Carplay 连接获取相应数据。\n" +
                    "为加快连接速度，可以在 设置 页面自定义分辨率和帧率。当应用自定义分辨率和帧率时，会先启动 CarWith，然后再连接 Carplay。通过减少一次连接以加快速度。\n" +
                    "可以在“检测”页面找到车机的 请求分辨率、接收帧率上限，作为参考。",
            leadingIcon = { Icon(painterResource(R.drawable.ic_link), contentDescription = null) }
        )

        HelpSection(
            "麦克风输入（实验性）",
            "默认使用手机麦克风作为 CarWith 麦克风。切换后，必须在 CarWith 车机端设置中同步切换麦克风输入。\n\n" +
                    "设置 > 音频设置 > 麦克风",
            leadingIcon = { Icon(painterResource(R.drawable.ic_mic), contentDescription = null) }
        )
    }
}

@Composable
private fun HelpSection(
    title: String,
    description: String,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leadingIcon?.invoke()
                Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun HelpPreview() {
    CarNatTheme { HelpScreen() }
}
