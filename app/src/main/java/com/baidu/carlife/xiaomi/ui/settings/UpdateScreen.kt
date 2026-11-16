package com.baidu.carlife.xiaomi.ui.settings

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.baidu.carlife.xiaomi.R
import com.baidu.carlife.xiaomi.ui.theme.CarNatTheme
import com.baidu.carlife.xiaomi.update.ReleaseClient
import com.baidu.carlife.xiaomi.update.ReleaseHttpException
import com.baidu.carlife.xiaomi.update.ReleaseVersion
import com.baidu.carlife.xiaomi.update.UpdateRelease
import com.baidu.carlife.xiaomi.update.UpdateSource
import java.io.IOException
import java.net.SocketTimeoutException
import org.json.JSONException

private sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object NoRelease : UpdateStatus
    data class UpToDate(val tag: String) : UpdateStatus
    data class Available(val release: UpdateRelease) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

@Composable
internal fun UpdateScreen(active: Boolean) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val settings = remember(context) { context.getSharedPreferences("app_update", 0) }
    val installedVersion = remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    var sourceName by rememberSaveable {
        mutableStateOf(settings.getString("source", null) ?: UpdateSource.GITHUB.name)
    }
    val source = UpdateSource.entries.firstOrNull { it.name == sourceName } ?: UpdateSource.GITHUB
    var status by remember(source) { mutableStateOf<UpdateStatus>(UpdateStatus.Idle) }
    var launchError by remember(source) { mutableStateOf(false) }

    val checking = status == UpdateStatus.Checking
    LaunchedEffect(source, checking, active) {
        if (!checking) return@LaunchedEffect
        if (!active) {
            status = UpdateStatus.Idle
            return@LaunchedEffect
        }
        status = checkUpdate(source, installedVersion)
    }

    UpdateContent(
        installedVersion = installedVersion,
        source = source,
        status = status,
        active = active,
        launchError = launchError,
        onSourceSelected = { selected ->
            sourceName = selected.name
            settings.edit().putString("source", selected.name).apply()
        },
        onCheck = {
            launchError = false
            status = UpdateStatus.Checking
        },
        onOpenRelease = { tag ->
            launchError = try {
                uriHandler.openUri(source.releasePage(tag))
                false
            } catch (_: ActivityNotFoundException) {
                true
            } catch (_: IllegalArgumentException) {
                true
            }
        },
    )
}

private suspend fun checkUpdate(source: UpdateSource, installedVersion: String): UpdateStatus {
    val current = ReleaseVersion.parse(installedVersion)
        ?: return UpdateStatus.Failed("无法识别当前安装版本")
    return try {
        val latest = ReleaseClient.latestStable(source) ?: return UpdateStatus.NoRelease
        if (latest.version > current) UpdateStatus.Available(latest) else UpdateStatus.UpToDate(latest.tag)
    } catch (error: ReleaseHttpException) {
        val message = when (error.status) {
            404 -> "无法访问更新源，请重试或切换更新源。"
            403, 429 -> "更新源拒绝请求或限制了访问频率，请稍后重试。"
            else -> "更新源返回 HTTP ${error.status}，请稍后重试或切换更新源。"
        }
        UpdateStatus.Failed(message)
    } catch (_: SocketTimeoutException) {
        UpdateStatus.Failed("连接更新源超时，请重试或切换更新源。")
    } catch (_: JSONException) {
        UpdateStatus.Failed("更新源返回了无法识别的发行信息。")
    } catch (_: IOException) {
        UpdateStatus.Failed("无法连接更新源，请检查网络或切换更新源。")
    }
}

@Composable
private fun UpdateContent(
    installedVersion: String,
    source: UpdateSource,
    status: UpdateStatus,
    active: Boolean,
    launchError: Boolean,
    onSourceSelected: (UpdateSource) -> Unit,
    onCheck: () -> Unit,
    onOpenRelease: (String) -> Unit,
) {
    SettingsLayout {
        Text("当前版本 ${installedVersion.ifBlank { "未知" }}", style = MaterialTheme.typography.titleLarge)
        Text("更新源", style = MaterialTheme.typography.titleMedium)
        SettingsCard {
            Column(Modifier.selectableGroup()) {
                UpdateSource.entries.forEachIndexed { index, option ->
                    if (index > 0) SettingsDivider()
                    val selected = option == source
                    ListItem(
                        headlineContent = { Text(option.label) },
                        leadingContent = {
                            val icon = if (option == UpdateSource.GITHUB) R.drawable.ic_github else R.drawable.ic_gitee
                            Icon(painterResource(icon), contentDescription = null)
                        },
                        trailingContent = { RadioButton(selected = selected, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.selectable(
                            selected = selected,
                            enabled = active,
                            role = Role.RadioButton,
                            onClick = { onSourceSelected(option) },
                        ),
                    )
                }
            }
        }
        Button(
            onClick = onCheck,
            enabled = active && status != UpdateStatus.Checking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (status == UpdateStatus.Checking) "正在检查" else "检查更新")
        }
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            when (status) {
                UpdateStatus.Idle -> Unit
                UpdateStatus.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                UpdateStatus.NoRelease -> Text("${source.label} 暂无可用的更新")
                is UpdateStatus.UpToDate -> Text("已是最新版本 ${source.label} ${status.tag}")
                is UpdateStatus.Failed -> Text(status.message, color = MaterialTheme.colorScheme.error)
                is UpdateStatus.Available -> SettingsCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("发现新版本 ${status.release.tag}", style = MaterialTheme.typography.titleLarge)
                        Text("更新日志", style = MaterialTheme.typography.titleMedium)
                        SelectionContainer {
                            ReleaseNotes(status.release.notes.ifBlank { "此版本未填写更新日志。" })
                        }
                        Button(onClick = { onOpenRelease(status.release.tag) }, enabled = active) {
                            Text("前往发行页下载")
                        }
                        if (launchError) Text("无法打开发行页", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private val heading = Regex("^#{1,6}\\s+(.+)$")
private val bullet = Regex("^([-*+])\\s+")
private val noteLink = Regex("\\[([^\\]\\n]+)]\\((https://[^\\s)]+)\\)")

@Composable
private fun ReleaseNotes(notes: String) {
    val linkColor = MaterialTheme.colorScheme.primary
    val text = remember(notes, linkColor) {
        buildAnnotatedString {
            notes.lineSequence().forEachIndexed { index, raw ->
                if (index > 0) append('\n')
                val title = heading.matchEntire(raw.trim())?.groupValues?.get(1)
                if (title != null) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(title) }
                } else {
                    val line = raw.replaceFirst(bullet, "• ")
                    var start = 0
                    for (link in noteLink.findAll(line)) {
                        append(line.substring(start, link.range.first))
                        withLink(LinkAnnotation.Url(link.groupValues[2], TextLinkStyles(SpanStyle(color = linkColor)))) {
                            append(link.groupValues[1])
                        }
                        start = link.range.last + 1
                    }
                    append(line.substring(start))
                }
            }
        }
    }
    Text(text)
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun UpdatePreview() {
    CarNatTheme(dynamicColor = false) {
        UpdateContent(
            installedVersion = "1.1",
            source = UpdateSource.GITHUB,
            status = UpdateStatus.Available(UpdateRelease("v1.2.0", ReleaseVersion(1, 2, 0), "• 改进连接稳定性")),
            active = true,
            launchError = false,
            onSourceSelected = {},
            onCheck = {},
            onOpenRelease = {},
        )
    }
}
