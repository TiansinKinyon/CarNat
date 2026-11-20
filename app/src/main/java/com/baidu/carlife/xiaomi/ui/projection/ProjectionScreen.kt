package com.baidu.carlife.xiaomi.ui.projection

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.baidu.carlife.service.CapturePhase
import com.baidu.carlife.service.CaptureStatus
import com.baidu.carlife.service.CarlifeConnectService
import com.baidu.carlife.service.ReceiverConnection
import com.baidu.carlife.xiaomi.R
import com.baidu.carlife.xiaomi.carplay.ReceiverSettings
import com.baidu.carlife.xiaomi.carplay.ConnectionMode
import com.baidu.carlife.xiaomi.carplay.HeadUnitProfile
import com.baidu.carlife.xiaomi.carplay.ProjectionLinkStatus
import com.baidu.carlife.xiaomi.carplay.connectionStatus
import com.baidu.carlife.xiaomi.carplay.missingReceiverPermissions
import com.baidu.carlife.xiaomi.ui.theme.CarNatTheme
import java.util.Locale

@Composable
internal fun ProjectionScreen(active: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by CarlifeConnectService.state.collectAsState()
    var mode by remember(context) { mutableStateOf(ConnectionMode.load(context)) }
    var autoNeedsPermissions by remember { mutableStateOf(missingReceiverPermissions(context).isNotEmpty()) }
    val headUnitProfile by remember(context) { HeadUnitProfile.observe(context) }
        .collectAsState(initial = HeadUnitProfile.load(context))
    val volkswagen = headUnitProfile == HeadUnitProfile.VOLKSWAGEN
    var settings by remember { mutableStateOf(ReceiverSettings.load(context)) }
    var phoneAddress by rememberSaveable { mutableStateOf(settings.address.uppercase(Locale.ROOT)) }
    var error by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<List<PairedReceiver>?>(null) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(active, status.active) {
        if (active) {
            val selectedMode = ConnectionMode.load(context)
            if (mode != selectedMode) error = null
            mode = selectedMode
            autoNeedsPermissions = missingReceiverPermissions(context).isNotEmpty()
        }
        if (!active || status.active) expanded = false
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            devices = pairedReceivers(context)
            expanded = true
        } else {
            error = "投屏需要附近设备权限"
        }
    }
    fun beginProjection() {
        val receiver = pairedReceivers(context)?.firstOrNull {
            it.address.equals(settings.receiverAddress, ignoreCase = true)
        }
        val current = ReceiverSettings.load(context)
        error = when {
            receiver == null -> "请开启蓝牙并选择已配对的接收端"
            !ReceiverSettings.validAddress(phoneAddress) -> "请填写有效的本机蓝牙地址"
            else -> startProjection(context, current.copy(name = receiver.name, address = phoneAddress))
        }
    }
    val notificationPermissions = remember(context) {
        context.getSharedPreferences("projection_notifications", Context.MODE_PRIVATE)
    }
    val projectionPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (Manifest.permission.POST_NOTIFICATIONS in grants) {
            notificationPermissions.edit().putBoolean("requested", true).apply()
        }
        autoNeedsPermissions = missingReceiverPermissions(context).isNotEmpty()
        if (autoNeedsPermissions) error = "请允许附近设备和局域网访问权限，以连接车机"
        else if (mode == ConnectionMode.MANUAL) beginProjection()
    }
    ProjectionContent(
        modifier = modifier,
        status = status,
        manual = mode == ConnectionMode.MANUAL,
        autoNeedsPermissions = autoNeedsPermissions,
        settings = settings,
        phoneAddress = phoneAddress,
        onAddressChange = { input ->
            val address = input.trim().uppercase(Locale.ROOT).take(17)
            phoneAddress = address
            if (mode == ConnectionMode.CAR_WITH && address != settings.address && ReceiverSettings.validAddress(address)) {
                val updated = settings.copy(address = address)
                if (updated.savePhoneAddress(context)) {
                    settings = updated
                    error = null
                } else {
                    error = "无法保存本机蓝牙地址"
                }
            }
        },
        onOpenDeviceInfo = { openDeviceInfoSettings(context) },
        error = error,
        supported = Build.VERSION.SDK_INT >= 33,
        brandPicker = {
            HeadUnitPicker(headUnitProfile, active && !status.active) { profile ->
                if (!CarlifeConnectService.state.value.active) {
                    profile.save(context)
                    error = null
                    expanded = false
                }
            }
        },
        receiverPicker = {
            val selectedName = settings.name.takeIf { settings.receiverAddress.isNotEmpty() }.orEmpty()
            ReceiverPicker(
                selectedName = selectedName,
                selectedAddress = settings.receiverAddress,
                devices = devices,
                enabled = active && !status.active && (mode == ConnectionMode.MANUAL || volkswagen),
                managedByCarWith = mode == ConnectionMode.CAR_WITH && !volkswagen,
                expanded = expanded && active,
                onDismiss = { expanded = false },
                onOpen = {
                    error = null
                    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        devices = pairedReceivers(context)
                        expanded = true
                    } else {
                        permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                },
                onSelect = { device ->
                    val updated = settings.copy(name = device.name, receiverAddress = device.address)
                    if (updated.saveReceiver(context)) settings = updated
                    else error = "无法保存接收端"
                    expanded = false
                },
            )
        },
        onProjectionClick = {
            error = null
            if (status.active) {
                CarlifeConnectService.stopDiagnostic(context)
            } else {
                val missing = missingReceiverPermissions(context).toMutableList()
                if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                    !notificationPermissions.getBoolean("requested", false)
                ) {
                    missing.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (missing.isNotEmpty()) projectionPermissions.launch(missing.toTypedArray())
                else if (mode == ConnectionMode.MANUAL) beginProjection()
            }
        },
    )
}

@Composable
private fun ProjectionContent(
    status: CaptureStatus,
    settings: ReceiverSettings,
    manual: Boolean = true,
    autoNeedsPermissions: Boolean = false,
    phoneAddress: String,
    onAddressChange: (String) -> Unit,
    onOpenDeviceInfo: () -> Unit,
    error: String?,
    supported: Boolean,
    brandPicker: @Composable () -> Unit,
    receiverPicker: @Composable () -> Unit,
    onProjectionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val validAddress = ReceiverSettings.validAddress(phoneAddress)
    val addressFocus = remember { FocusRequester() }
    var addressRequired by rememberSaveable { mutableStateOf(false) }
    var showAddressHelp by rememberSaveable { mutableStateOf(false) }
    val addressError = !validAddress && (phoneAddress.isNotEmpty() || manual && addressRequired)
    val (carWithLink, receiverLink, message) = status.connectionStatus(supported, error)
    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = if (supported) 96.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = colors.secondaryContainer,
                    contentColor = colors.onSecondaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    ProjectionNode("CarWith", R.drawable.ic_mobile_cast, Modifier.weight(1.5f))
                    ProjectionLink(
                        label = "CarWith 到 CarNat",
                        status = carWithLink,
                        modifier = Modifier.weight(1f),
                    )
                    ProjectionNode("CarNat", R.drawable.ic_extension, Modifier.weight(1.5f))
                    ProjectionLink(
                        label = "CarNat 到 CarPlay",
                        status = receiverLink,
                        modifier = Modifier.weight(1f),
                    )
                    ProjectionNode("CarPlay", R.drawable.ic_car_gear, Modifier.weight(1.5f))
                }
                ProjectionStatusMessage(message, receiverLink)
            }
            if (supported) {
                if (!manual) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = colors.surfaceContainer,
                            contentColor = colors.onSurface,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_info),
                                contentDescription = null,
                                tint = colors.primary,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("从 CarWith 开始连接", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "当前连接方式为 CarWith 发起，请在 CarWith > CarLife 连接 > 无线连接 > 车辆热点 中选择车机蓝牙并开始连接。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                brandPicker()
                receiverPicker()
                OutlinedTextField(
                    leadingIcon = { Icon(painterResource(R.drawable.ic_settings_bluetooth), contentDescription = null) },
                    trailingIcon = {
                        IconButton(enabled = !status.active, onClick = { showAddressHelp = true }) {
                            Icon(
                                painterResource(R.drawable.ic_help),
                                contentDescription = "如何查找本机蓝牙地址",
                            )
                        }
                    },
                    value = phoneAddress,
                    onValueChange = {
                        if (ReceiverSettings.validAddress(it)) addressRequired = false
                        onAddressChange(it)
                    },
                    label = { Text("本机蓝牙地址") },
                    placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                    supportingText = {
                        Text(when {
                            addressError && phoneAddress.isBlank() -> "请填写本机蓝牙地址"
                            addressError -> "请输入有效的蓝牙地址"
                            !manual -> "建议填写，部分车机连接时可能需要该内容"
                            else -> "在 关于手机 的 设备信息或状态 中查找蓝牙地址"
                        })
                    },
                    isError = addressError,
                    enabled = !status.active,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(addressFocus),
                )
            }
        }
        if (supported && (manual || status.active || autoNeedsPermissions)) {
            val buttonReady = status.active || !manual || (settings.receiverAddress.isNotBlank() && validAddress)
            ExtendedFloatingActionButton(
                onClick = {
                    when {
                        buttonReady -> onProjectionClick()
                        !validAddress -> {
                            addressRequired = true
                            addressFocus.requestFocus()
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 24.dp),
                containerColor = if (buttonReady) colors.primaryContainer else colors.surfaceContainerHighest,
                contentColor = if (buttonReady) colors.onPrimaryContainer else colors.onSurface.copy(alpha = 0.38f),
                icon = { Icon(painterResource(R.drawable.ic_cast), contentDescription = null) },
                text = {
                    Text(when {
                        status.active -> "停止投屏"
                        manual -> "开始投屏"
                        else -> "授予连接权限"
                    })
                },
            )
        }
    }
    if (supported && showAddressHelp) {
        AlertDialog(
            onDismissRequest = { showAddressHelp = false },
            title = { Text("如何查找本机蓝牙地址") },
            text = { Text("在 系统设置 > 关于手机 里查找本机蓝牙地址，因机型而异。\n\n" +
                    "小米 HyperOS：我的设备 > 全部参数与信息 > 状态信息 > 蓝牙地址 \n\n" +
                    "在 CarWith 发起模式下，部分车机需要该内容才能发起连接。") },
            dismissButton = {
                TextButton(onClick = { showAddressHelp = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAddressHelp = false
                    onOpenDeviceInfo()
                }) { Text("打开设置") }
            },
        )
    }
}

@Composable
private fun ProjectionNode(label: String, @DrawableRes icon: Int, modifier: Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(32.dp))
        Box(Modifier.heightIn(min = 20.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ProjectionStatusMessage(message: String, status: ProjectionLinkStatus) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val (container, content) = when (status) {
        ProjectionLinkStatus.CONNECTED -> if (dark) {
            Color(0xFF214E34) to Color(0xFFC2F2CE)
        } else {
            Color(0xFFCDEFD5) to Color(0xFF143923)
        }
        ProjectionLinkStatus.FAILED -> colors.errorContainer to colors.onErrorContainer
        else -> colors.surfaceContainer to colors.onSurface
    }

    Surface(Modifier.fillMaxWidth(), color = container, contentColor = content) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = if (status == ProjectionLinkStatus.CONNECTED) R.drawable.ic_link else R.drawable.ic_link_off
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ProjectionLink(
    label: String,
    status: ProjectionLinkStatus,
    modifier: Modifier,
) {
    val color = MaterialTheme.colorScheme.onSecondaryContainer
    val track = color.copy(alpha = 0.18f)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
            val indicator = Modifier.fillMaxWidth().height(4.dp)
            when (status) {
                ProjectionLinkStatus.CONNECTING ->
                    LinearProgressIndicator(modifier = indicator, color = color, trackColor = track)
                ProjectionLinkStatus.CONNECTED ->
                    LinearProgressIndicator(progress = { 1f }, modifier = indicator, color = color, trackColor = track)
                else -> Box(indicator.background(track, RoundedCornerShape(50)))
            }
        }
        Icon(
            painterResource(status.icon),
            contentDescription = "$label：${status.description}",
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun startProjection(context: Context, settings: ReceiverSettings): String? {
    if (!settings.valid || !settings.save(context)) return "无法保存接收端设置"
    CarlifeConnectService.startDiagnostic(context)
    return null
}

private fun openDeviceInfoSettings(context: Context) {
    for (action in listOf(Settings.ACTION_DEVICE_INFO_SETTINGS, Settings.ACTION_SETTINGS)) {
        try {
            context.startActivity(Intent(action))
            return
        } catch (_: ActivityNotFoundException) {
            continue
        } catch (_: SecurityException) {
            continue
        }
    }
    Toast.makeText(context, "无法打开系统设置", Toast.LENGTH_SHORT).show()
}

@Preview(name = "待连接", showBackground = true, widthDp = 360)
@Composable
private fun ProjectionReadyPreview() {
    CarNatTheme { ProjectionReadyPreviewContent() }
}

@Preview(name = "投屏中", showBackground = true, widthDp = 360)
@Composable
private fun ProjectionActivePreview() {
    CarNatTheme {
        ProjectionPreviewContent(
            CaptureStatus(
                phase = CapturePhase.RECEIVING,
                receiverStatus = "正在投屏",
                receiverConnection = ReceiverConnection.CONNECTED,
            ),
        )
    }
}

@Preview(name = "正在连接", showBackground = true, widthDp = 360)
@Composable
private fun ProjectionConnectingPreview() {
    CarNatTheme { ProjectionPreviewContent(CaptureStatus(phase = CapturePhase.WAITING_FOR_FRAMES)) }
}

@Preview(name = "连接失败", showBackground = true, widthDp = 360)
@Composable
private fun ProjectionFailedPreview() {
    CarNatTheme {
        ProjectionPreviewContent(CaptureStatus(phase = CapturePhase.FAILED, error = "等待 CarPlay 接收端超时"))
    }
}

@Composable
internal fun ProjectionReadyPreviewContent() {
    ProjectionPreviewContent(CaptureStatus())
}

@Preview(name = "CarWith 发起", showBackground = true, widthDp = 360)
@Composable
private fun ProjectionCarWithPreview() {
    CarNatTheme { ProjectionPreviewContent(CaptureStatus(), manual = false) }
}

@Composable
private fun ProjectionPreviewContent(status: CaptureStatus, manual: Boolean = true) {
    ProjectionContent(
        status = status,
        manual = manual,
        settings = ReceiverSettings(name = "CarPlay 接收端", receiverAddress = "02:00:00:00:00:01"),
        phoneAddress = "02:12:34:56:78:90",
        onAddressChange = {},
        onOpenDeviceInfo = {},
        error = null,
        supported = true,
        brandPicker = { HeadUnitPicker(HeadUnitProfile.GENERIC, !status.active) {} },
        receiverPicker = {
            ReceiverPicker(
                selectedName = "CarPlay 接收端",
                selectedAddress = "02:00:00:00:00:01",
                devices = emptyList(),
                enabled = manual && !status.active,
                managedByCarWith = !manual,
                expanded = false,
                onDismiss = {},
                onOpen = {},
                onSelect = {},
            )
        },
        onProjectionClick = {},
    )
}

private data class PairedReceiver(val name: String, val address: String)

@SuppressLint("MissingPermission")
private fun pairedReceivers(context: Context): List<PairedReceiver>? = try {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    if (adapter?.isEnabled != true) null
    else adapter.bondedDevices.orEmpty().map {
        PairedReceiver(it.name?.takeIf(String::isNotBlank) ?: "未命名设备", it.address)
    }.sortedWith(compareBy({ it.name }, { it.address }))
} catch (_: SecurityException) {
    null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeadUnitPicker(
    selected: HeadUnitProfile,
    enabled: Boolean,
    onSelect: (HeadUnitProfile) -> Unit,
) {
    var expanded by remember(enabled) { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            enabled = enabled,
            readOnly = true,
            singleLine = true,
            leadingIcon = { Icon(painterResource(R.drawable.ic_car_gear), contentDescription = null) },
            label = { Text("车机品牌") },
            supportingText = {
                Text(when (selected) {
                    HeadUnitProfile.GENERIC -> "DiPlay、WinPlay 或不确定车型时选通用。"
                    HeadUnitProfile.BMW -> "在 iDrive 中选择 Apple CarPlay 并确认授权。"
                    HeadUnitProfile.VOLKSWAGEN -> "选已配对车机，填本机蓝牙地址，连车机 Wi-Fi。"
                })
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            HeadUnitProfile.entries.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.label) },
                    trailingIcon = {
                        if (profile == selected) {
                            Icon(painterResource(R.drawable.ic_check_circle), contentDescription = "当前选中")
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(profile)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiverPicker(
    selectedName: String,
    selectedAddress: String,
    devices: List<PairedReceiver>?,
    enabled: Boolean,
    managedByCarWith: Boolean,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onSelect: (PairedReceiver) -> Unit,
) {
    val menuExpanded = enabled && expanded
    ExposedDropdownMenuBox(
        expanded = menuExpanded,
        onExpandedChange = { if (enabled && it) onOpen() else onDismiss() },
    ) {
        OutlinedTextField(
            value = if (managedByCarWith) "" else selectedName,
            onValueChange = {},
            enabled = enabled,
            readOnly = true,
            singleLine = true,
            leadingIcon = { Icon(painterResource(R.drawable.ic_bluetooth_drive), contentDescription = null) },
            label = { Text("车载蓝牙") },
            placeholder = { Text("选择已配对设备") },
            supportingText = if (managedByCarWith) {
                { Text("CarWith 发起模式下无需选择") }
            } else null,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menuExpanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = onDismiss,
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            if (devices.isNullOrEmpty()) {
                DropdownMenuItem(
                    text = { Text(if (devices == null) "请开启蓝牙并检查附近设备权限" else "请先在系统蓝牙设置中完成配对") },
                    onClick = onDismiss,
                )
            }
            devices.orEmpty().forEach { device ->
                val selected = device.address.equals(selectedAddress, ignoreCase = true)
                val colors = MaterialTheme.colorScheme
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(device.name, color = if (selected) colors.onSecondaryContainer else colors.onSurface)
                            Text(device.address, style = MaterialTheme.typography.bodySmall,
                                color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant)
                        }
                    },
                    leadingIcon = if (selected) {
                        { SelectionCheck() }
                    } else null,
                    onClick = { onSelect(device) },
                    modifier = if (selected) Modifier.padding(horizontal = 4.dp)
                        .background(colors.secondaryContainer, RoundedCornerShape(12.dp))
                    else Modifier,
                )
            }
        }
    }
}

@Composable
private fun SelectionCheck() {
    val color = MaterialTheme.colorScheme.onSecondaryContainer
    Canvas(Modifier.size(20.dp)) {
        val start = androidx.compose.ui.geometry.Offset(size.width * .2f, size.height * .5f)
        val corner = androidx.compose.ui.geometry.Offset(size.width * .4f, size.height * .7f)
        val end = androidx.compose.ui.geometry.Offset(size.width * .8f, size.height * .3f)
        drawLine(color, start, corner, strokeWidth = 2.dp.toPx())
        drawLine(color, corner, end, strokeWidth = 2.dp.toPx())
    }
}
