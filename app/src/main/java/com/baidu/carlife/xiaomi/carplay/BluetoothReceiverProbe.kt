package com.baidu.carlife.xiaomi.carplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import com.baidu.carlife.service.CarlifeConnectService
import com.baidu.carlife.xiaomi.diagnostics.ProjectionLog
import java.io.Closeable
import java.io.IOException
import java.util.UUID

internal data class ReceiverDiagnostics(
    val resolution: CarPlayResolution? = null,
    val wifi: ReceiverWifi? = null,
    val receiverName: String? = null,
    val receiverAddress: String? = null,
    val phoneAddress: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val wifiInterface: String? = null,
    val phoneIps: List<String> = emptyList(),
    val mediaAudio: CarPlayAudioInfo? = null,
    val navigationAudio: CarPlayAudioInfo? = null,
)

internal data class CarPlayAudioInfo(
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val bufferMs: Int,
    val port: Int,
)

internal class BluetoothReceiverProbe(
    private val connectivity: ConnectivityManager,
) : Closeable {
    private var wifiRequest: Closeable? = null
    private var sockets: CarPlaySockets? = null
    private var server: BluetoothServerSocket? = null
    private var peer: BluetoothSocket? = null
    @Volatile private var closed = false

    fun run(
        adapter: BluetoothAdapter,
        receiverName: String,
        pin: String,
        deviceId: String,
        onStreamStarted: () -> Unit,
        receiverAddress: String = "",
        onResolution: (CarPlayResolution) -> Unit = {},
        onDiagnostics: (ReceiverDiagnostics) -> Unit = {},
        readResolutionOnly: Boolean = false,
        preferences: ProjectionPreferences = ProjectionPreferences(),
        onReconnecting: () -> Unit = {},
        onStage: (String) -> Unit,
    ) {
        try {
            var reconnectUntil = 0L
            var attemptNumber = 0
            while (!closed) {
                var streamed = false
                val started = SystemClock.elapsedRealtime()
                attemptNumber++
                val mode = if (readResolutionOnly) "预连接" else "正式连接"
                ProjectionLog.event("CarPlay $mode · 尝试 $attemptNumber")
                try {
                    val acceptTimeout = if (reconnectUntil == 0L) 30_000 else
                        (reconnectUntil - SystemClock.elapsedRealtime()).coerceIn(1L, 30_000L).toInt()
                    runAttempt(adapter, receiverName, pin, deviceId, acceptTimeout, receiverAddress, {
                        streamed = true
                        onStreamStarted()
                    }, onResolution, onDiagnostics, readResolutionOnly, preferences, onStage)
                } catch (error: IOException) {
                    if (closed) return
                    ProjectionLog.failure("CarPlay $mode · 尝试 $attemptNumber · 首帧=$streamed", error)
                    if (!captureReady() || (!streamed && SystemClock.elapsedRealtime() >= reconnectUntil)) throw error
                    onStage("投屏连接已断开 · 等待接收端重新连接")
                } finally {
                    closeAttempt()
                    ProjectionLog.event("CarPlay $mode · 尝试 $attemptNumber 结束 · 用时=${SystemClock.elapsedRealtime() - started}ms · 首帧=$streamed · 主动关闭=$closed")
                    onDiagnostics(ReceiverDiagnostics())
                }
                if (readResolutionOnly || !captureReady()) break
                if (streamed) reconnectUntil = SystemClock.elapsedRealtime() + 90_000
                if (SystemClock.elapsedRealtime() >= reconnectUntil) break
                if (streamed) onReconnecting()
            }
        } finally {
            close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun runAttempt(
        adapter: BluetoothAdapter,
        receiverName: String,
        pin: String,
        deviceId: String,
        acceptTimeout: Int,
        receiverAddress: String,
        onStreamStarted: () -> Unit,
        onResolution: (CarPlayResolution) -> Unit,
        onDiagnostics: (ReceiverDiagnostics) -> Unit,
        readResolutionOnly: Boolean,
        preferences: ProjectionPreferences,
        onStage: (String) -> Unit,
    ) {
        val attempt = CarPlaySockets()
        val listener = synchronized(this) {
            if (closed) throw IOException("蓝牙连接已停止")
            sockets = attempt
            adapter.listenUsingRfcommWithServiceRecord("CarPlay", SERVICE_UUID).also { server = it }
        }
        onStage("正在等待配对设备 $receiverName 连接")
        ProjectionLog.event("蓝牙 RFCOMM · 监听窗口=${acceptTimeout}ms")
        val listeningSince = SystemClock.elapsedRealtime()
        val connection = listener.accept(acceptTimeout)
        ProjectionLog.event("蓝牙 RFCOMM · 已接受连接 · 用时=${SystemClock.elapsedRealtime() - listeningSince}ms")
        synchronized(this) {
            if (closed) {
                connection.close()
                throw IOException("蓝牙连接已停止")
            }
            peer = connection
            runCatching { listener.close() }
            server = null
        }
        val device = connection.remoteDevice
        val matches = if (receiverAddress.isEmpty()) device.name.equals(receiverName, ignoreCase = true)
        else device.address.equals(receiverAddress, ignoreCase = true)
        val bondState = device.bondState
        if (bondState != BluetoothDevice.BOND_BONDED || !matches) {
            ProjectionLog.event("蓝牙 RFCOMM · 忽略连接 · bondState=$bondState · 匹配所选设备=$matches")
            throw IOException("忽略了来自其他蓝牙设备的连接")
        }
        onStage("蓝牙 RFCOMM 已连接")
        var accessPoint: ReceiverWifiRequest? = null
        var diagnostics = ReceiverDiagnostics(receiverName = receiverName, receiverAddress = device.address, phoneAddress = deviceId)
        onDiagnostics(diagnostics)
        fun publishWifi(wifi: ReceiverWifi) {
            if (diagnostics.wifi !== wifi) {
                diagnostics = diagnostics.copy(wifi = wifi)
                onDiagnostics(diagnostics)
            }
        }
        fun requestWifi(wifi: ReceiverWifi) {
            if (pin.isEmpty() || Build.VERSION.SDK_INT < 29 || accessPoint != null) return
            onStage("正在连接车机 Wi-Fi · 如有系统提示，请确认")
            val request = ReceiverWifiRequest(connectivity, attempt::close)
            synchronized(this) {
                if (closed) throw IOException("CarPlay 会话已停止")
                request.start(wifi)
                wifiRequest = request
                accessPoint = request
            }
        }
        val endpoint = Iap2LinkProbe.run(connection.inputStream, connection.outputStream, { wifi ->
            publishWifi(wifi)
            requestWifi(wifi)
        }, onStage)
        endpoint.wifi?.let(::publishWifi)
        val addressKind = if (':' in endpoint.host) "IPv6"
            else if (endpoint.host.all { it.isDigit() || it == '.' }) "IPv4" else "主机名"
        ProjectionLog.event("iAP2 · 网络端点类型=$addressKind · 提供热点配置=${endpoint.wifi != null}")
        diagnostics = diagnostics.copy(host = endpoint.host, port = endpoint.port)
        onDiagnostics(diagnostics)
        if (pin.isNotEmpty()) {
            endpoint.wifi?.let(::requestWifi)
            if (Build.VERSION.SDK_INT >= 29) accessPoint?.let { request ->
                val network = request.awaitNetwork()
                val link = connectivity.getLinkProperties(network)
                attempt.useNetwork(network, link?.interfaceName)
                diagnostics = diagnostics.copy(
                    wifiInterface = link?.interfaceName,
                    phoneIps = link?.linkAddresses?.mapNotNull { it.address.hostAddress }.orEmpty(),
                )
                onDiagnostics(diagnostics)
                ProjectionLog.event("Wi-Fi · 已绑定车机网络 · 可用本机 IP 数=${diagnostics.phoneIps.size}")
                onStage("已连接车机 Wi-Fi")
            }
            onStage(CarPlayReceiverProbe.verify(
                endpoint.host, endpoint.port, pin, endpoint.certificate, deviceId, attempt, onStreamStarted,
                onResolution = { resolution ->
                    diagnostics = diagnostics.copy(resolution = resolution)
                    onDiagnostics(diagnostics)
                    onResolution(resolution)
                },
                readResolutionOnly = readResolutionOnly,
                preferences = preferences,
                onAudio = { navigation, info ->
                    diagnostics = if (navigation) diagnostics.copy(navigationAudio = info) else diagnostics.copy(mediaAudio = info)
                    onDiagnostics(diagnostics)
                },
                onStage = onStage,
            ))
        }
    }

    private fun captureReady() = CarlifeConnectService.state.value.displayId != null &&
        CarlifeConnectService.state.value.active

    @Synchronized
    private fun closeAttempt() {
        sockets?.close()
        runCatching { wifiRequest?.close() }
        wifiRequest = null
        runCatching { peer?.close() }
        runCatching { server?.close() }
        sockets = null
        peer = null
        server = null
    }

    @Synchronized
    override fun close() {
        closed = true
        closeAttempt()
    }

    private companion object {
        val SERVICE_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
    }
}
