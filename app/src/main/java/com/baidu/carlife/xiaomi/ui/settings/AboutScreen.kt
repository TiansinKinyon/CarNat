package com.baidu.carlife.xiaomi.ui.settings

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.baidu.carlife.xiaomi.R
import com.baidu.carlife.xiaomi.update.UpdateSource
import com.baidu.carlife.xiaomi.ui.theme.CarNatTheme

private val appIconSize = 80.dp


@Composable
internal fun AboutScreen(active: Boolean) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val iconSizePx = with(LocalDensity.current) { appIconSize.roundToPx() }
    val icon = remember(context, iconSizePx) {
        BitmapPainter(
            context.applicationInfo.loadIcon(context.packageManager)
                .toBitmap(iconSizePx, iconSizePx).asImageBitmap(),
        )
    }
    val version = remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }

    AboutContent(
        version = version,
        active = active,
        appIcon = { Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize()) },
    ) { url ->
        try {
            uriHandler.openUri(url)
        } catch (_: IllegalArgumentException) {
            Toast.makeText(context, "无法打开仓库链接", Toast.LENGTH_SHORT).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "无法打开仓库链接", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun AboutContent(
    version: String?,
    active: Boolean,
    appIcon: @Composable () -> Unit,
    onOpenRepository: (String) -> Unit,
) {
    SettingsLayout {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(appIconSize).clip(CircleShape)) {
                appIcon()
            }
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            version?.let {
                Text("版本 $it", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SettingsCard {
            AboutRow("作者", "天星青阳（TiansinKinyon）", icon = R.drawable.ic_person)
            SettingsDivider()
            AboutRow("工作室", "Inspiremix Studio", icon = R.drawable.ic_home_work)
            SettingsDivider()
            AboutRow(
                "GitHub",
                "TiansinKinyon/CarNat",
                icon = R.drawable.ic_github,
                modifier = Modifier.clickable(enabled = active, role = Role.Button) {
                    onOpenRepository(UpdateSource.GITHUB.repository)
                },
            )
            SettingsDivider()
            AboutRow(
                "Gitee",
                "TiansinKinyon/CarNat",
                icon = R.drawable.ic_gitee,
                modifier = Modifier.clickable(enabled = active, role = Role.Button) {
                    onOpenRepository(UpdateSource.GITEE.repository)
                },
            )
        }
    }
}

@Composable
private fun AboutRow(
    title: String,
    detail: String,
    icon: Int,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        leadingContent = {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier,
    )
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun AboutPreview() {
    CarNatTheme(dynamicColor = false) {
        AboutContent(
            version = "1.1",
            active = true,
            appIcon = {
                Image(
                    painterResource(R.drawable.ic_launcher_background),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
                Image(
                    painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().scale(1.5f),
                )
            },
            onOpenRepository = {},
        )
    }
}
