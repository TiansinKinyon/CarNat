package com.baidu.carlife.xiaomi.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baidu.carlife.service.CarlifeConnectService
import com.baidu.carlife.xiaomi.R
import com.baidu.carlife.xiaomi.carplay.AudioPreference
import com.baidu.carlife.xiaomi.carplay.ProjectionPreferences
import com.baidu.carlife.xiaomi.carplay.ReceiverSettings
import com.baidu.carlife.xiaomi.ui.theme.CarNatTheme
import kotlin.math.roundToInt

internal enum class SettingEntry(val route: String, val title: String) {
    DEVELOPER("settings/developer", "开发者选项"),
}

private enum class Editor(val title: String) {
    RESOLUTION("输出分辨率"), FPS("本机帧率上限"), BITRATE("视频码率"),
    AUDIO("音频格式与缓冲"), PIN("网络配对码"),
}

@Composable
internal fun SettingsScreen(active: Boolean = true, onOpen: (SettingEntry) -> Unit) {
    val context = LocalContext.current
    val capture by CarlifeConnectService.state.collectAsStateWithLifecycle()
    var preferences by remember(context) { mutableStateOf(ProjectionPreferences.load(context)) }
    var editor by remember { mutableStateOf<Editor?>(null) }
    var error by remember { mutableStateOf(false) }
    val editable = active && !capture.active

    fun save(updated: ProjectionPreferences) {
        if (!editable) return
        error = !updated.save(context)
        if (!error) {
            preferences = updated
            editor = null
        }
    }

    SettingsLayout {
        SettingsCard {
            SettingsRow(Editor.RESOLUTION.title, preferences.width?.let { "$it × ${preferences.height}" } ?: "跟随车机", active, capture.active) {
                editor = Editor.RESOLUTION
            }
            SettingsDivider()
            SettingsRow(Editor.FPS.title, preferences.fpsLimit?.let { "$it fps" } ?: "跟随车机", active, capture.active) {
                editor = Editor.FPS
            }
            SettingsDivider()
            SettingsRow(Editor.BITRATE.title, "${preferences.videoBitrateKbps} kbps", active, capture.active) {
                editor = Editor.BITRATE
            }
            SettingsDivider()
            val audioSummary = listOfNotNull(
                preferences.audioPreference.label,
                preferences.mediaBufferMs?.let { "媒体 $it ms" },
                preferences.navigationBufferMs?.let { "导航 $it ms" },
            ).joinToString(" · ")
            SettingsRow(Editor.AUDIO.title, audioSummary, active, capture.active) {
                editor = Editor.AUDIO
            }
        }
        if (error) SaveError()
        SettingsCard {
            SettingsRow("开发者选项", "网络配对码", active, capture.active) { onOpen(SettingEntry.DEVELOPER) }
        }
    }

    val selected = editor
    if (selected != null && editable) {
        ProjectionEditor(selected, preferences, onDismiss = { editor = null }, onSave = ::save)
    }
}

@Composable
internal fun DeveloperSettingsScreen(active: Boolean) {
    val context = LocalContext.current
    val capture by CarlifeConnectService.state.collectAsStateWithLifecycle()
    var settings by remember(context) { mutableStateOf(ReceiverSettings.load(context)) }
    var editor by remember { mutableStateOf<Editor?>(null) }
    var error by remember { mutableStateOf(false) }
    val editable = active && !capture.active

    fun save(pin: String) {
        if (!editable) return
        val updated = ReceiverSettings.load(context).copy(pin = pin)
        error = !updated.save(context)
        if (!error) {
            settings = updated
            editor = null
        }
    }

    SettingsLayout {
        SettingsCard {
            SettingsRow(Editor.PIN.title, if (settings.pin.isEmpty()) "默认（3939）" else "已自定义", active, capture.active) { editor = Editor.PIN }
        }
        OutlinedButton(
            onClick = { if (capture.active) showSettingsLocked(context) else save("") },
            enabled = active && (capture.active || settings.pin.isNotEmpty()),
            modifier = Modifier.settingsLockAppearance(capture.active),
        ) {
            Text("恢复默认")
        }
        if (error) SaveError()
    }
    val selected = editor
    if (selected != null && editable) {
        var value by remember(selected) { mutableStateOf(settings.pin) }
        val normalized = value.trim()
        val valid = ReceiverSettings.validPin(normalized)
        EditorDialog(
            title = selected.title,
            valid = valid,
            onDismiss = { editor = null },
            onSave = { save(normalized) },
            onReset = { save("") },
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(16) },
                label = { Text(selected.title) },
                placeholder = { Text("3939") },
                supportingText = {
                    Text("仅在接收端使用其他网络配对码时填写。")
                },
                singleLine = true,
                isError = !valid,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectionEditor(
    editor: Editor,
    current: ProjectionPreferences,
    onDismiss: () -> Unit,
    onSave: (ProjectionPreferences) -> Unit,
) {
    when (editor) {
        Editor.RESOLUTION -> {
            var width by remember { mutableStateOf(current.width?.toString().orEmpty()) }
            var height by remember { mutableStateOf(current.height?.toString().orEmpty()) }
            val w = width.toIntOrNull()
            val h = height.toIntOrNull()
            val valid = width.isBlank() && height.isBlank() ||
                w != null && h != null && w in ProjectionPreferences.dimensions &&
                h in ProjectionPreferences.dimensions && w % 2 == 0 && h % 2 == 0
            EditorDialog(editor.title, valid, onDismiss, { onSave(current.copy(width = w, height = h)) },
                { onSave(current.copy(width = null, height = null)) }) {
                NumberField("宽度（px）", width) { width = it }
                NumberField("高度（px）", height) { height = it }
                Text("留空跟随车机。自定义宽高须为 320–4096 范围内的偶数。")
            }
        }
        Editor.FPS -> {
            var value by remember { mutableStateOf(current.fpsLimit?.toString().orEmpty()) }
            val limit = value.toIntOrNull()
            EditorDialog(editor.title, value.isBlank() || limit in ProjectionPreferences.frameRates, onDismiss,
                { onSave(current.copy(fpsLimit = limit)) }, { onSave(current.copy(fpsLimit = null)) }) {
                NumberField("帧率上限（fps）", value) { value = it }
                Text("留空跟随车机。自定义 10–120 fps，且以车机上限为限。")
            }
        }
        Editor.BITRATE -> {
            var value by remember { mutableStateOf(current.videoBitrateKbps.toString()) }
            val bitrate = value.toIntOrNull()
            val range = ProjectionPreferences.videoBitrates
            EditorDialog(editor.title, bitrate in range, onDismiss,
                { bitrate?.let { onSave(current.copy(videoBitrateKbps = it)) } },
                { onSave(current.copy(videoBitrateKbps = ProjectionPreferences.DEFAULT_BITRATE_KBPS)) }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("流畅" to 2_000, "均衡" to 4_000, "清晰" to 8_000).forEach { (label, preset) ->
                        FilterChip(selected = bitrate == preset, onClick = { value = preset.toString() },
                            label = { Text(label) })
                    }
                }
                Slider(
                    value = (bitrate ?: ProjectionPreferences.DEFAULT_BITRATE_KBPS).coerceIn(range).toFloat(),
                    onValueChange = { value = (it / 250).roundToInt().times(250).toString() },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                )
                NumberField("自定义（1000–20000 kbps）", value) { value = it }
            }
        }
        Editor.AUDIO -> {
            var format by remember { mutableStateOf(current.audioPreference) }
            var media by remember { mutableStateOf(current.mediaBufferMs?.toString().orEmpty()) }
            var navigation by remember { mutableStateOf(current.navigationBufferMs?.toString().orEmpty()) }
            val range = ProjectionPreferences.audioBuffers
            val valid = (media.isBlank() || media.toIntOrNull() in range) &&
                (navigation.isBlank() || navigation.toIntOrNull() in range)
            EditorDialog(editor.title, valid, onDismiss,
                { onSave(current.copy(audioPreference = format, mediaBufferMs = media.toIntOrNull(), navigationBufferMs = navigation.toIntOrNull())) },
                { onSave(current.copy(audioPreference = AudioPreference.AUTO, mediaBufferMs = null, navigationBufferMs = null)) }) {
                AudioPreference.entries.forEach { entry ->
                    ListItem(
                        headlineContent = { Text(entry.label) },
                        leadingContent = { RadioButton(selected = format == entry, onClick = null) },
                        modifier = Modifier.clickable(role = Role.RadioButton) { format = entry },
                    )
                }
                NumberField("媒体缓冲（ms）", media) { media = it }
                NumberField("导航缓冲（ms）", navigation) { navigation = it }
                Text("留空自动。可填 40–1000 ms，作为发送给车机的缓冲请求。")
            }
        }
        else -> Unit
    }
}

@Composable
private fun SettingsLayout(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) { content() }
}

@Composable
private fun SettingsRow(title: String, summary: String, active: Boolean, locked: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    ListItem(
        leadingContent = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .settingsLockAppearance(locked)
            .clickable(enabled = active, role = Role.Button) {
                if (locked) showSettingsLocked(context) else onClick()
            },
    )
}

private fun Modifier.settingsLockAppearance(locked: Boolean): Modifier =
    if (!locked) this
    else alpha(0.38f).semantics { stateDescription = "投屏期间不可修改" }

private fun showSettingsLocked(context: Context) {
    Toast.makeText(context, "投屏期间此项已禁用", Toast.LENGTH_SHORT).show()
}

@Composable
private fun SettingsDivider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp))

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(5)) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EditorDialog(
    title: String,
    valid: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = valid) { Text("保存") } },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun SaveError() {
    Text("无法保存设置", color = MaterialTheme.colorScheme.error)
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SettingsPreview() {
    CarNatTheme { SettingsScreen(onOpen = {}) }
}
