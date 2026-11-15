package com.baidu.carlife.xiaomi.ui.projection

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import com.baidu.carlife.xiaomi.carplay.missingReceiverPermissions
import com.baidu.carlife.xiaomi.ui.theme.CarNatTheme
import java.util.Locale

@Composable
internal fun ProjectionScreen(active: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by CarlifeConnectService.state.collectAsState()
    var settings by remember { mutableStateOf(ReceiverSettings.load(context)) }
    var phoneAddress by rememberSaveable { mutableStateOf(settings.address.uppercase(Locale.ROOT)) }
    var error by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<List<PairedReceiver>?>(null) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(active, status.active) {
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
        if (missingReceiverPermissions(context).isEmpty()) beginProjection()
        else error = "请允许附近设备和局域网访问权限，以连接车机"
    }
    ProjectionContent(
        modifier = modifier,
        status = status,
        settings = settings,
        phoneAddress = phoneAddress,
        onAddressChange = { phoneAddress = it.trim().uppercase(Locale.ROOT).take(17) },
        error = error,
        supported = Build.VERSION.SDK_INT >= 33,
        receiverPicker = {
            val selectedName = settings.name.takeIf { settings.receiverAddress.isNotEmpty() }.orEmpty()
            ReceiverPicker(
                selectedName = selectedName,
                selectedAddress = settings.receiverAddress,
                devices = devices,
                enabled = !status.active,
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
                if (missing.isEmpty()) beginProjection()
                else projectionPermissions.launch(missing.toTypedArray())
            }
        },
    )
}

@Composable
private fun ProjectionContent(
    status: CaptureStatus,
    settings: ReceiverSettings,
    phoneAddress: String,
    onAddressChange: (String) -> Unit,
    error: String?,
    supported: Boolean,
    receiverPicker: @Composable () -> Unit,
    onProjectionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val validAddress = ReceiverSettings.validAddress(phoneAddress)
    val addressFocus = remember { FocusRequester() }
    var addressRequired by rememberSaveable { mutableStateOf(false) }
    val addressError = !validAddress && (addressRequired || phoneAddress.isNotEmpty())
    val connectionError = error ?: status.error
    val showError = supported && (
        connectionError != null || status.phase == CapturePhase.FAILED ||
            status.receiverConnection == ReceiverConnection.FAILED
        )
    val showSuccess = supported && !showError && status.active &&
        status.receiverConnection == ReceiverConnection.CONNECTED
    val carWithLink = when {
        supported && status.phase == CapturePhase.RECEIVING -> ProjectionLinkStatus.CONNECTED
        showError -> ProjectionLinkStatus.FAILED
        !supported -> ProjectionLinkStatus.PENDING
        else -> when (status.phase) {
            CapturePhase.ARMED, CapturePhase.BINDING, CapturePhase.WAITING_FOR_FRAMES ->
                ProjectionLinkStatus.CONNECTING
            else -> ProjectionLinkStatus.PENDING
        }
    }
    val receiverLink = when {
        showError -> ProjectionLinkStatus.FAILED
        showSuccess -> ProjectionLinkStatus.CONNECTED
        supported && status.active -> ProjectionLinkStatus.CONNECTING
        else -> ProjectionLinkStatus.PENDING
    }
    val message = if (!supported) {
        "CarPlay 投屏需要 Android 13 或更新版本"
    } else {
        connectionError ?: status.receiverStatus ?: when (status.phase) {
            CapturePhase.WAITING_FOR_RECEIVER -> "正在等待车机 CarPlay 连接"
            CapturePhase.READY_TO_START_CAR_WITH -> "车机已就绪，返回 CarNat 即可启动 CarWith"
            CapturePhase.ARMED -> "正在启动 CarWith"
            CapturePhase.BINDING, CapturePhase.WAITING_FOR_FRAMES -> "正在准备 CarWith 画面"
            CapturePhase.RECEIVING -> "正在投屏"
            CapturePhase.STOPPED -> "投屏已停止"
            else -> "等待连接"
        }
    }
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
                receiverPicker()
                OutlinedTextField(
                    leadingIcon = { Icon(painterResource(R.drawable.ic_settings_bluetooth), contentDescription = null) },
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
                            else -> "可在手机系统的状态信息中查看"
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
        if (supported) {
            val buttonReady = status.active || (settings.receiverAddress.isNotBlank() && validAddress)
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
                text = { Text(if (status.active) "停止投屏" else "开始投屏") },
            )
        }
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

private enum class ProjectionLinkStatus(@get:DrawableRes val icon: Int, val description: String) {
    PENDING(R.drawable.ic_more_horiz, "未连接"),
    CONNECTING(R.drawable.ic_more_horiz, "正在连接"),
    CONNECTED(R.drawable.ic_check_circle, "已连接"),
    FAILED(R.drawable.ic_error, "连接失败"),
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

@Composable
private fun ProjectionPreviewContent(status: CaptureStatus) {
    ProjectionContent(
        status = status,
        settings = ReceiverSettings(name = "CarPlay 接收端", receiverAddress = "02:00:00:00:00:01"),
        phoneAddress = "02:12:34:56:78:90",
        onAddressChange = {},
        error = null,
        supported = true,
        receiverPicker = {
            ReceiverPicker(
                selectedName = "CarPlay 接收端",
                selectedAddress = "02:00:00:00:00:01",
                devices = emptyList(),
                enabled = !status.active,
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
private fun ReceiverPicker(
    selectedName: String,
    selectedAddress: String,
    devices: List<PairedReceiver>?,
    enabled: Boolean,
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
            value = selectedName,
            onValueChange = {},
            enabled = enabled,
            readOnly = true,
            singleLine = true,
            leadingIcon = { Icon(painterResource(R.drawable.ic_bluetooth_drive), contentDescription = null) },
            label = { Text("车载蓝牙") },
            placeholder = { Text("选择已配对设备") },
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
                    text = { Text(if (devices == null) "请开启蓝牙并检查附近设备权限" else "请先在系统蓝牙设置中配对") },
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
