package com.baidu.carlife.xiaomi.ui.settings

import android.content.Context
import android.graphics.drawable.Icon
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
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
    LOGS("settings/logs", "投屏日志"),
    DEVELOPER("settings/developer", "开发者选项"),
    UPDATE("settings/update", "应用更新"),
    ABOUT("settings/about", "关于"),
}

private enum class Editor(val title: String) {
    RESOLUTION("输出分辨率"), FPS("输出帧率上限"), BITRATE("视频目标码率"),
    AUDIO_FORMAT("音频格式"), AUDIO_BUFFER("音频缓冲"), PIN("网络配对码"),
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
            SettingsRow(Editor.RESOLUTION.title, preferences.width?.let { "$it × ${preferences.height}" } ?: "跟随车机", active, capture.active, leadingIcon = { Icon(painterResource(R.drawable.ic_aspect_ratio), contentDescription = null) }) {
                editor = Editor.RESOLUTION
            }
            SettingsDivider()
            SettingsRow(Editor.FPS.title, preferences.fpsLimit?.let { "$it fps" } ?: "跟随车机", active, capture.active, leadingIcon = { Icon(painterResource(R.drawable.ic_animation), contentDescription = null) }) {
                editor = Editor.FPS
            }
            SettingsDivider()
            SettingsRow(Editor.BITRATE.title, "${preferences.videoBitrateKbps} Kbps", active, capture.active, leadingIcon = { Icon(painterResource(R.drawable.ic_mist), contentDescription = null) }) {
                editor = Editor.BITRATE
            }
            SettingsDivider()
            SettingsRow(Editor.AUDIO_FORMAT.title, preferences.audioPreference.label, active, capture.active, leadingIcon = { Icon(painterResource(R.drawable.ic_audio_file), contentDescription = null) }) {
                editor = Editor.AUDIO_FORMAT
            }
            SettingsDivider()
            val audioBufferSummary = listOfNotNull(
                preferences.mediaBufferMs?.let { "媒体 $it ms" },
                preferences.navigationBufferMs?.let { "导航 $it ms" },
            ).joinToString(" · ").ifBlank { "自动" }
            SettingsRow(Editor.AUDIO_BUFFER.title, audioBufferSummary, active, capture.active, leadingIcon = { Icon(painterResource(R.drawable.ic_edit_audio), contentDescription = null) }) {
                editor = Editor.AUDIO_BUFFER
            }
        }
        if (error) SaveError()
        SettingsCard {
            SettingsRow("投屏日志", "查看与导出", active, false, leadingIcon = {
                Icon(painterResource(R.drawable.ic_unknown_document), contentDescription = null)
            }) { onOpen(SettingEntry.LOGS) }
            SettingsDivider()
            SettingsRow("开发者选项", "网络配对码", active, capture.active, leadingIcon = { Icon(painterResource(R.drawable.ic_code_xml), contentDescription = null) }) { onOpen(SettingEntry.DEVELOPER) }
            SettingsDivider()
            SettingsRow("应用更新", "检查新版本与切换更新源", active, false, leadingIcon = {
                Icon(painterResource(R.drawable.ic_update), contentDescription = null)
            }) { onOpen(SettingEntry.UPDATE) }
            SettingsDivider()
            SettingsRow("关于", "版本与项目信息", active, false, leadingIcon = {
                Icon(painterResource(R.drawable.ic_info), contentDescription = null)
            }) { onOpen(SettingEntry.ABOUT) }
        }
    }

    val selected = editor
    if (selected != null && editable) {
        ProjectionEditor(selected, preferences, onDismiss = { editor = null }, onSave = ::save)
    }
}

@Composable
internal fun DeveloperSettingsScreen(
    layout: @Composable (actions: @Composable (Boolean) -> Unit, body: @Composable (Boolean) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val capture by CarlifeConnectService.state.collectAsStateWithLifecycle()
    var settings by remember(context) { mutableStateOf(ReceiverSettings.load(context)) }
    var editor by remember { mutableStateOf<Editor?>(null) }
    var error by remember { mutableStateOf(false) }

    fun save(pin: String) {
        if (CarlifeConnectService.state.value.active) return
        val updated = ReceiverSettings.load(context).copy(pin = pin)
        error = !updated.save(context)
        if (!error) {
            settings = updated
            editor = null
        }
    }

    layout(
        { active ->
            IconButton(
                onClick = { if (capture.active) showSettingsLocked(context) else save("") },
                enabled = active && (capture.active || settings.pin.isNotEmpty()),
                modifier = Modifier.settingsLockAppearance(capture.active),
            ) {
                Icon(painterResource(R.drawable.ic_reset_settings), contentDescription = "恢复默认")
            }
        },
        { active ->
            SettingsLayout {
                SettingsCard {
                    SettingsRow(
                        Editor.PIN.title,
                        if (settings.pin.isEmpty()) "默认（3939）" else "自定义",
                        active,
                        capture.active,
                        leadingIcon = { Icon(painterResource(R.drawable.ic_bluetooth_searching), contentDescription = null) }
                    ) { editor = Editor.PIN }
                }
                if (error) SaveError()
            }
            val selected = editor
            if (selected != null && active && !capture.active) {
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
                            Text("仅在接收端使用其他网络配对码时修改。")
                        },
                        singleLine = true,
                        isError = !valid,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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
            var followReceiver by remember { mutableStateOf(current.width == null || current.height == null) }
            val valid = followReceiver || w != null && h != null && w in ProjectionPreferences.dimensions &&
                h in ProjectionPreferences.dimensions && w % 2 == 0 && h % 2 == 0
            EditorDialog(editor.title, valid, onDismiss,
                { onSave(current.copy(width = w.takeUnless { followReceiver }, height = h.takeUnless { followReceiver })) },
                { onSave(current.copy(width = null, height = null)) }) {
                SettingCheckbox("跟随车机", followReceiver) { followReceiver = it }
                NumberField("水平分辨率（px）", width, enabled = !followReceiver, leadingIcon = { Icon(painterResource(R.drawable.ic_fit_page_width), contentDescription = null) }) { width = it }
                NumberField("垂直分辨率（px）", height, enabled = !followReceiver, leadingIcon = { Icon(painterResource(R.drawable.ic_fit_page_height), contentDescription = null) }) { height = it }
                Text("水平与垂直分辨率应为 320–4096 范围内的整数。")
            }
        }
        Editor.FPS -> {
            var value by remember { mutableStateOf(current.fpsLimit?.toString().orEmpty()) }
            val limit = value.toIntOrNull()
            var followReceiver by remember { mutableStateOf(current.fpsLimit == null) }
            EditorDialog(editor.title, followReceiver || limit in ProjectionPreferences.frameRates, onDismiss,
                { onSave(current.copy(fpsLimit = limit.takeUnless { followReceiver })) },
                { onSave(current.copy(fpsLimit = null)) }) {
                SettingCheckbox("跟随车机", followReceiver) { followReceiver = it }
                NumberField("帧率上限（fps）", value, enabled = !followReceiver) { value = it }
                Text("自定义范围为 10–120 fps。宽高也设为自定义时，直接按手动值启动。")
            }
        }
        Editor.BITRATE -> {
            var value by remember { mutableStateOf(current.videoBitrateKbps.toString()) }
            val bitrate = value.toIntOrNull()
            val range = ProjectionPreferences.videoBitrates
            val presets = listOf("流畅" to 2_000, "均衡" to 4_000, "清晰" to 8_000)
            EditorDialog(editor.title, bitrate in range, onDismiss,
                { bitrate?.let { onSave(current.copy(videoBitrateKbps = it)) } },
                { onSave(current.copy(videoBitrateKbps = ProjectionPreferences.DEFAULT_BITRATE_KBPS)) }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { (label, preset) ->
                        FilterChip(selected = bitrate == preset, onClick = { value = preset.toString() },
                            label = { Text(label) })
                    }
                }
                val sliderValue = (bitrate ?: ProjectionPreferences.DEFAULT_BITRATE_KBPS).coerceIn(range)
                val colors = MaterialTheme.colorScheme
                Slider(
                    value = sliderValue.toFloat(),
                    onValueChange = { value = (it / 250).roundToInt().times(250).toString() },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    track = { state ->
                        SliderDefaults.Track(
                            sliderState = state,
                            modifier = Modifier.drawWithContent {
                                drawContent()
                                fun x(value: Int): Float {
                                    val fraction = (value - range.first).toFloat() / (range.last - range.first)
                                    return size.width * if (layoutDirection == LayoutDirection.Ltr) fraction else 1f - fraction
                                }
                                presets.forEach { (_, preset) ->
                                    val position = x(preset)
                                    if (kotlin.math.abs(position - x(sliderValue)) >= 6.dp.toPx()) {
                                        drawCircle(
                                            color = if (preset < sliderValue) colors.onPrimary else colors.primary,
                                            radius = 2.dp.toPx(),
                                            center = Offset(position, center.y),
                                        )
                                    }
                                }
                            },
                        )
                    },
                )
                NumberField("自定义（1000–20000 Kbps）", value) { value = it }
            }
        }
        Editor.AUDIO_FORMAT -> {
            var format by remember { mutableStateOf(current.audioPreference) }
            EditorDialog(editor.title, true, onDismiss,
                { onSave(current.copy(audioPreference = format)) },
                { onSave(current.copy(audioPreference = AudioPreference.AUTO)) }) {
                Column(Modifier.selectableGroup()) {
                    AudioPreference.entries.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .selectable(selected = format == entry, role = Role.RadioButton) { format = entry }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = format == entry, onClick = null)
                            Text(entry.label)
                        }
                    }
                }
            }
        }
        Editor.AUDIO_BUFFER -> {
            var media by remember { mutableStateOf(current.mediaBufferMs?.toString().orEmpty()) }
            var navigation by remember { mutableStateOf(current.navigationBufferMs?.toString().orEmpty()) }
            var automatic by remember {
                mutableStateOf(current.mediaBufferMs == null && current.navigationBufferMs == null)
            }
            val mediaMs = media.toIntOrNull()
            val navigationMs = navigation.toIntOrNull()
            val range = ProjectionPreferences.audioBuffers
            val valid = automatic || (
                (media.isNotBlank() || navigation.isNotBlank()) &&
                    (media.isBlank() || mediaMs in range) &&
                    (navigation.isBlank() || navigationMs in range)
                )
            EditorDialog(editor.title, valid, onDismiss,
                {
                    onSave(current.copy(
                        mediaBufferMs = mediaMs.takeUnless { automatic },
                        navigationBufferMs = navigationMs.takeUnless { automatic },
                    ))
                },
                { onSave(current.copy(mediaBufferMs = null, navigationBufferMs = null)) }) {
                SettingCheckbox("自动", automatic) { automatic = it }
                NumberField("媒体缓冲（ms）", media, enabled = !automatic, leadingIcon = { Icon(painterResource(R.drawable.ic_music_note), contentDescription = null) }) { media = it }
                NumberField("导航缓冲（ms）", navigation, enabled = !automatic, leadingIcon = { Icon(painterResource(R.drawable.ic_navigation), contentDescription = null) }) { navigation = it }
                Text("自定义范围为 40–1000 ms，作为发送给车机的缓冲请求。")
            }
        }
        else -> Unit
    }
}

@Composable
internal fun SettingsLayout(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) { content() }
}

@Composable
private fun SettingsRow(
    title: String,
    summary: String,
    active: Boolean,
    locked: Boolean,
    leadingIcon: (@Composable () -> Unit)? = {
        Icon(painterResource(R.drawable.ic_settings), contentDescription = null)
    },
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    ListItem(
        leadingContent = leadingIcon,
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
    else alpha(0.38f).semantics { stateDescription = "投屏期间无法更改" }

private fun showSettingsLocked(context: Context) {
    Toast.makeText(context, "投屏期间无法更改", Toast.LENGTH_SHORT).show()
}

@Composable
internal fun SettingsDivider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp))

@Composable
private fun NumberField(
    label: String,
    value: String,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(5)) },
        label = { Text(label) },
        leadingIcon = leadingIcon,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SettingCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label)
    }
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
