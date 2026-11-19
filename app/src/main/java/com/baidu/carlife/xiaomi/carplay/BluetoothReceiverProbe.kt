package com.baidu.carlife.xiaomi.carplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.os.Build
import android.os.SystemClock
import com.baidu.carlife.service.CarlifeConnectService
import com.baidu.carlife.xiaomi.diagnostics.ProjectionLog
import java.io.Closeable
import java.io.IOException
import java.io.File
import java.net.Inet4Address
import java.net.ProtocolException
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
    private val profile: HeadUnitProfile = HeadUnitProfile.GENERIC,
    private val discovery: NsdManager? = null,
    private val customPin: Boolean = false,
    private val pairingDirectory: File? = null,
    private val hasConfiguredPhoneAddress: Boolean = false,
) : Closeable {
    private var wifiRequest: Closeable? = null
    private var sockets: CarPlaySockets? = null
    private var server: BluetoothServerSocket? = null
    private var discoveryMarker: BluetoothServerSocket? = null
    private var volkswagenInvitation: VolkswagenInvitation? = null
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
        retryAfterDisconnect: Boolean = true,
        onReconnecting: () -> Unit = {},
        onStage: (String) -> Unit,
    ) {
        try {
            var reconnectUntil = 0L
            val firstConnectionDeadline = SystemClock.elapsedRealtime() + 85_000L
            var attemptNumber = 0
            while (!closed) {
                var streamed = false
                val started = SystemClock.elapsedRealtime()
                attemptNumber++
                val mode = if (readResolutionOnly) "预连接" else "正式连接"
                ProjectionLog.event("CarPlay $mode · 尝试 $attemptNumber")
                try {
                    val acceptDeadline = when {
                        reconnectUntil != 0L -> reconnectUntil
                        profile == HeadUnitProfile.BMW -> firstConnectionDeadline
                        else -> started + 30_000L
                    }
                    val acceptTimeout = (acceptDeadline - started).coerceIn(1L, 30_000L).toInt()
                    runAttempt(adapter, receiverName, pin, deviceId, acceptTimeout, receiverAddress, {
                        streamed = true
                        onStreamStarted()
                    }, onResolution, onDiagnostics, readResolutionOnly, preferences, onStage)
                } catch (error: IOException) {
                    if (closed) return
                    val windowDeadline = if (reconnectUntil != 0L) reconnectUntil else firstConnectionDeadline
                    if (error is BmwWindowPending && SystemClock.elapsedRealtime() < windowDeadline) {
                        onStage("已完成宝马蓝牙认证 · 等待 CarPlay 链路")
                        continue
                    }
                    ProjectionLog.failure("CarPlay $mode · 尝试 $attemptNumber · 首帧=$streamed", error)
                    if (!retryAfterDisconnect || !captureReady() ||
                        (!streamed && SystemClock.elapsedRealtime() >= reconnectUntil)
                    ) throw error
                    onStage("投屏连接已断开 · 等待接收端重新连接")
                } finally {
                    closeAttempt()
                    ProjectionLog.event("CarPlay $mode · 尝试 $attemptNumber 结束 · 用时=${SystemClock.elapsedRealtime() - started}ms · 首帧=$streamed · 主动关闭=$closed")
                    onDiagnostics(ReceiverDiagnostics())
                }
                if (!retryAfterDisconnect || readResolutionOnly || !captureReady()) break
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
            val name = if (profile == HeadUnitProfile.BMW) "Wireless iAP" else "CarPlay"
            adapter.listenUsingRfcommWithServiceRecord(name, SERVICE_UUID).also { listener ->
                server = listener
                if (profile == HeadUnitProfile.BMW) {
                    discoveryMarker = try {
                        adapter.listenUsingRfcommWithServiceRecord("CarPlay Discovery", BMW_DISCOVERY_UUID)
                    } catch (_: IOException) {
                        null
                    } catch (_: SecurityException) {
                        null
                    }
                }
            }
        }
        val invitation = if (profile == HeadUnitProfile.VOLKSWAGEN) {
            if (Build.VERSION.SDK_INT < 33 || discovery == null) {
                throw IOException("大众车机的 Wi-Fi 广播需要 Android 13 或更新版本")
            }
            if (!hasConfiguredPhoneAddress || !ReceiverSettings.validAddress(deviceId)) {
                throw IOException("请先设置本机真实蓝牙地址")
            }
            if (receiverAddress.isEmpty() || adapter.bondedDevices.none { it.address.equals(receiverAddress, ignoreCase = true) }) {
                throw IOException("请先选择已配对的大众车机")
            }
            val network = currentVolkswagenWifi() ?: throw IOException("请先连接大众车机 Wi-Fi")
            val link = connectivity.getLinkProperties(network) ?: throw IOException("无法读取车机 Wi-Fi 网络")
            val localAddress = link.linkAddresses.firstOrNull { it.address is Inet4Address }?.address
                ?: throw IOException("大众车机 Wi-Fi 未分配 IPv4 地址")
            attempt.useNetwork(network, link.interfaceName)
            synchronized(this) {
                if (closed) throw IOException("CarPlay 会话已停止")
                VolkswagenInvitation(connectivity, discovery, network, localAddress, deviceId).also {
                    volkswagenInvitation = it
                    it.start()
                }
            }
        } else null
        onStage(if (receiverName.isBlank()) "正在等待已配对车机连接" else "正在等待配对设备 $receiverName 连接")
        ProjectionLog.event("蓝牙 RFCOMM · 监听窗口=${acceptTimeout}ms")
        val listeningSince = SystemClock.elapsedRealtime()
        var outbound = false
        val connection = if (invitation == null) {
            listener.accept(acceptTimeout)
        } else {
            val inboundWindow = minOf(8_000, acceptTimeout)
            try {
                listener.accept(inboundWindow)
            } catch (failure: IOException) {
                if (closed || acceptTimeout <= inboundWindow) throw failure
                val dialed = dialVolkswagen(adapter.getRemoteDevice(receiverAddress), minOf(8_000, acceptTimeout - inboundWindow))
                if (dialed != null) {
                    outbound = true
                    dialed
                } else {
                    val remaining = acceptTimeout - (SystemClock.elapsedRealtime() - listeningSince)
                    if (remaining <= 0) throw failure
                    listener.accept(remaining.toInt())
                }
            }
        }
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
        val matches = when {
            receiverAddress.isNotEmpty() -> device.address.equals(receiverAddress, ignoreCase = true)
            receiverName.isNotEmpty() -> device.name.equals(receiverName, ignoreCase = true)
            else -> true
        }
        val bondState = device.bondState
        if (bondState != BluetoothDevice.BOND_BONDED || !matches) {
            ProjectionLog.event("蓝牙 RFCOMM · 忽略连接 · bondState=$bondState · 匹配所选设备=$matches")
            throw IOException("忽略了来自其他蓝牙设备的连接")
        }
        onStage("蓝牙 RFCOMM 已连接")
        var accessPoint: ReceiverWifiRequest? = null
        var diagnostics = ReceiverDiagnostics(receiverName = receiverName.ifBlank { device.name ?: "CarPlay 接收端" }, receiverAddress = device.address, phoneAddress = deviceId)
        onDiagnostics(diagnostics)
        fun publishWifi(wifi: ReceiverWifi) {
            if (diagnostics.wifi !== wifi) {
                diagnostics = diagnostics.copy(wifi = wifi)
                onDiagnostics(diagnostics)
            }
        }
        fun requestWifi(wifi: ReceiverWifi) {
            if (invitation != null || pin.isEmpty() || Build.VERSION.SDK_INT < 29 || accessPoint != null) return
            onStage("正在连接车机 Wi-Fi · 如有系统提示，请确认")
            val request = ReceiverWifiRequest(connectivity, attempt::close)
            synchronized(this) {
                if (closed) throw IOException("CarPlay 会话已停止")
                request.start(wifi)
                wifiRequest = request
                accessPoint = request
            }
        }
        val awaitingEndpoint = java.util.concurrent.atomic.AtomicBoolean(true)
        val timeout = Runnable {
            if (awaitingEndpoint.getAndSet(false)) runCatching { connection.close() }
        }
        val timer = android.os.Handler(android.os.Looper.getMainLooper())
        timer.postDelayed(timeout, 45_000)
        var waitingForBmwWindow = false
        val endpoint = try {
            Iap2LinkProbe.run(
                connection.inputStream, connection.outputStream,
                onWifi = { wifi ->
                    publishWifi(wifi)
                    requestWifi(wifi)
                },
                profile = profile,
                deviceId = deviceId,
                onBmwAppsWindow = { waitingForBmwWindow = it },
                initiateLink = outbound,
                onStage = onStage,
            )
        } catch (failure: IOException) {
            if (waitingForBmwWindow && failure !is ProtocolException) throw BmwWindowPending(failure)
            throw failure
        } finally {
            awaitingEndpoint.set(false)
            timer.removeCallbacks(timeout)
        }
        endpoint.wifi?.let(::publishWifi)
        val addressKind = when {
            endpoint.host == null -> "需要网络发现"
            ':' in endpoint.host -> "IPv6"
            endpoint.host.all { it.isDigit() || it == '.' } -> "IPv4"
            else -> "主机名"
        }
        ProjectionLog.event("iAP2 · 网络端点类型=$addressKind · 提供热点配置=${endpoint.wifi != null}")
        diagnostics = diagnostics.copy(host = endpoint.host, port = endpoint.port)
        onDiagnostics(diagnostics)
        if (pin.isNotEmpty()) {
            endpoint.wifi?.let(::requestWifi)
            var vehicleNetwork: Network? = null
            var vehicleLink: LinkProperties? = null
            if (Build.VERSION.SDK_INT >= 29) accessPoint?.let { request ->
                val network = request.awaitNetwork()
                val link = connectivity.getLinkProperties(network)
                vehicleNetwork = network
                vehicleLink = link
                attempt.useNetwork(network, link?.interfaceName)
                diagnostics = diagnostics.copy(
                    wifiInterface = link?.interfaceName,
                    phoneIps = link?.linkAddresses?.mapNotNull { it.address.hostAddress }.orEmpty(),
                )
                onDiagnostics(diagnostics)
                ProjectionLog.event("Wi-Fi · 已绑定车机网络 · 可用本机 IP 数=${diagnostics.phoneIps.size}")
                onStage("已连接车机 Wi-Fi")
            }
            val address = if (invitation != null) {
                onStage("正在等待大众车机本轮 CarPlay 邀请")
                val advertisedHost = endpoint.host?.let(attempt::resolve)
                val invitedHost = invitation.await(advertisedHost)
                val advertisedPort = endpoint.port ?: if (Build.VERSION.SDK_INT >= 34) {
                    CarAirPlayDiscovery.findAdvertised(discovery, invitation.network)
                        ?.takeIf { attempt.resolve(it.first) == invitedHost }?.second
                } else null
                invitation.requireFresh(invitedHost)
                (invitedHost.hostAddress ?: throw IOException("大众车机地址不可用")) to (advertisedPort ?: 7000)
            } else if (endpoint.host != null && endpoint.port != null) {
                if (endpoint.protocol.isBmw && vehicleNetwork == null) {
                    val current = connectivity.activeNetwork
                    val isWifi = current?.let(connectivity::getNetworkCapabilities)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    if (current == null || !isWifi) throw IOException("请先连接车机 Wi-Fi")
                    attempt.useNetwork(current, connectivity.getLinkProperties(current)?.interfaceName)
                }
                endpoint.host to endpoint.port
            } else {
                val network = vehicleNetwork ?: throw IOException("车机未提供端点，且没有已绑定的车机热点")
                val link = vehicleLink ?: throw IOException("无法读取车机热点地址")
                onStage("正在发现宝马 AirPlay 服务")
                CarAirPlayDiscovery.find(discovery, network, link)
            }
            diagnostics = diagnostics.copy(host = address.first, port = address.second)
            onDiagnostics(diagnostics)
            onStage(CarPlayReceiverProbe.verify(
                address.first, address.second, pin, endpoint.certificate, deviceId, attempt, onStreamStarted,
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
                protocol = endpoint.protocol,
                customPin = customPin,
                beforeHandshake = { host -> invitation?.requireFresh(host, consume = true) },
                pairingStore = pairingDirectory?.takeIf { endpoint.protocol.isVehicle }?.let {
                    VehiclePairingStore(it, deviceId, device.address, endpoint.certificate, endpoint.protocol)
                },
            ))
        }
    }

    @Suppress("DEPRECATION")
    private fun currentVolkswagenWifi(): Network? {
        fun isWifi(network: Network) =
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        return connectivity.activeNetwork?.takeIf(::isWifi)
            ?: connectivity.allNetworks.filter(::isWifi).singleOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun dialVolkswagen(device: BluetoothDevice, timeoutMillis: Int): BluetoothSocket? {
        val socket = try {
            device.createRfcommSocketToServiceRecord(ACCESSORY_IAP2_UUID)
        } catch (_: IOException) {
            return null
        }
        synchronized(this) {
            if (closed) {
                socket.close()
                throw IOException("CarPlay 会话已停止")
            }
            peer = socket
        }
        val pending = java.util.concurrent.atomic.AtomicBoolean(true)
        val timer = android.os.Handler(android.os.Looper.getMainLooper())
        val timeout = Runnable { if (pending.getAndSet(false)) runCatching { socket.close() } }
        timer.postDelayed(timeout, timeoutMillis.toLong())
        return try {
            socket.connect()
            if (pending.getAndSet(false)) socket else null
        } catch (_: IOException) {
            runCatching { socket.close() }
            null
        } finally {
            pending.set(false)
            timer.removeCallbacks(timeout)
        }
    }

    private class BmwWindowPending(cause: IOException) : IOException("宝马暂未开启 CarPlay", cause)

    private fun captureReady() = CarlifeConnectService.state.value.displayId != null &&
        CarlifeConnectService.state.value.active

    @Synchronized
    private fun closeAttempt() {
        sockets?.close()
        volkswagenInvitation?.close()
        volkswagenInvitation = null
        runCatching { wifiRequest?.close() }
        wifiRequest = null
        runCatching { peer?.close() }
        runCatching { discoveryMarker?.close() }
        discoveryMarker = null
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
        private val BMW_DISCOVERY_UUID = UUID.fromString("02030302-1d19-415f-86f2-22a2106a0a77")
        private val ACCESSORY_IAP2_UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")
        val SERVICE_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
    }
}
