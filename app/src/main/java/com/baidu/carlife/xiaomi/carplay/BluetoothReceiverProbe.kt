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
    private val pairingDirectory: File? = null,
    private val hasConfiguredPhoneAddress: Boolean = false,
    private val context: android.content.Context? = null,
    private val volkswagenWakeNetwork: Network? = null,
    private val power: () -> Iap2PowerStatus = { Iap2PowerStatus() },
) : Closeable {
    private var wifiRequest: Closeable? = null
    private var sockets: CarPlaySockets? = null
    private var server: BluetoothServerSocket? = null
    private var discoveryMarker: BluetoothServerSocket? = null
    private var controlInvitation: CarPlayControlInvitation? = null
    private var peer: BluetoothSocket? = null
    @Volatile private var closed = false

    @SuppressLint("MissingPermission")
    fun run(
        adapter: BluetoothAdapter,
        receiverName: String,
        pin: String,
        deviceId: String,
        onStreamStarted: () -> Unit,
        receiverAddress: String = "",
        onResolution: (CarPlayResolution, VideoCodec) -> Unit = { _, _ -> },
        onDiagnostics: (ReceiverDiagnostics) -> Unit = {},
        readResolutionOnly: Boolean = false,
        preferences: ProjectionPreferences = ProjectionPreferences(),
        retryAfterDisconnect: Boolean = true,
        onReconnecting: () -> Unit = {},
        handoff: WinPlayHandoff? = null,
        onWinPlayPreflight: (WinPlayHandoff) -> Unit = {},
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
                val resume = handoff?.takeIf {
                    attemptNumber == 1 && !readResolutionOnly && profile == HeadUnitProfile.GENERIC &&
                        Build.VERSION.SDK_INT >= 29 && it.deviceId == deviceId &&
                        it.peer.equals(receiverAddress, ignoreCase = true) &&
                        adapter.bondedDevices.any { device -> device.address.equals(it.peer, ignoreCase = true) }
                }
                val onStarted = {
                    streamed = true
                    onStreamStarted()
                }
                try {
                    if (resume != null && Build.VERSION.SDK_INT >= 29) {
                        resumeWinPlay(resume, receiverName, pin, onStarted, onResolution, onDiagnostics, preferences, onStage)
                    } else {
                        val acceptDeadline = when {
                            reconnectUntil != 0L -> reconnectUntil
                            profile == HeadUnitProfile.BMW -> firstConnectionDeadline
                            else -> started + 30_000L
                        }
                        val acceptTimeout = (acceptDeadline - started).coerceIn(1L, 30_000L).toInt()
                        runAttempt(adapter, receiverName, pin, deviceId, acceptTimeout, receiverAddress, onStarted,
                            onResolution, onDiagnostics, readResolutionOnly, preferences, onWinPlayPreflight, onStage)
                    }
                } catch (error: IOException) {
                    if (closed) return
                    if (resume != null && !streamed) {
                        ProjectionLog.failure("WinPlay 热点恢复未完成，继续等待蓝牙", error)
                        continue
                    }
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

    @androidx.annotation.RequiresApi(29)
    private fun resumeWinPlay(
        handoff: WinPlayHandoff,
        receiverName: String,
        pin: String,
        onStreamStarted: () -> Unit,
        onResolution: (CarPlayResolution, VideoCodec) -> Unit,
        onDiagnostics: (ReceiverDiagnostics) -> Unit,
        preferences: ProjectionPreferences,
        onStage: (String) -> Unit,
    ) {
        val attempt = CarPlaySockets()
        val request = synchronized(this) {
            if (closed) throw IOException("CarPlay 会话已停止")
            sockets = attempt
            ReceiverWifiRequest(connectivity, attempt::close).also {
                wifiRequest = it
                it.start(handoff.wifi)
            }
        }
        onStage("CarWith 已重启 · 正在重新连接 WinPlay 热点")
        val network = request.awaitNetwork(timeoutMs = 15_000)
        val link = connectivity.getLinkProperties(network)
        attempt.useNetwork(network, link?.interfaceName)
        var diagnostics = ReceiverDiagnostics(
            wifi = handoff.wifi, receiverName = receiverName, receiverAddress = handoff.peer,
            phoneAddress = handoff.deviceId, host = handoff.host, port = handoff.port,
            wifiInterface = link?.interfaceName,
            phoneIps = link?.linkAddresses?.mapNotNull { it.address.hostAddress }.orEmpty(),
        )
        onDiagnostics(diagnostics)
        onStage("已重新连接 WinPlay 热点")
        onStage(CarPlayReceiverProbe.verify(
            handoff.host, handoff.port, pin, handoff.certificate, handoff.deviceId, attempt, onStreamStarted,
            onResolution = { resolution, codec ->
                diagnostics = diagnostics.copy(resolution = resolution)
                onDiagnostics(diagnostics)
                onResolution(resolution, codec)
            },
            preferences = preferences,
            onAudio = { navigation, info ->
                diagnostics = if (navigation) diagnostics.copy(navigationAudio = info) else diagnostics.copy(mediaAudio = info)
                onDiagnostics(diagnostics)
            },
            onStage = onStage,
        ))
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
        onResolution: (CarPlayResolution, VideoCodec) -> Unit,
        onDiagnostics: (ReceiverDiagnostics) -> Unit,
        readResolutionOnly: Boolean,
        preferences: ProjectionPreferences,
        onWinPlayPreflight: (WinPlayHandoff) -> Unit,
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
            val network = volkswagenWakeNetwork ?: awaitVolkswagenWifi(acceptTimeout, onStage)
            val link = connectivity.getLinkProperties(network) ?: throw IOException("无法读取车机 Wi-Fi 网络")
            val localAddress = link.linkAddresses.firstOrNull { it.address is Inet4Address }?.address
                ?: throw IOException("大众车机 Wi-Fi 未分配 IPv4 地址")
            attempt.useNetwork(network, link.interfaceName)
            synchronized(this) {
                if (closed) throw IOException("CarPlay 会话已停止")
                CarPlayControlInvitation(connectivity, discovery, network, localAddress, deviceId).also {
                    controlInvitation = it
                    it.start()
                }
            }
        } else null
        val volkswagenPeer = if (invitation != null) pairingDirectory?.let {
            VolkswagenPeerStore(it, deviceId, receiverAddress)
        } else null
        val savedCertificate = volkswagenPeer?.takeUnless { readResolutionOnly }?.read()
        val savedPairing = savedCertificate?.let {
            pairingDirectory?.let { directory ->
                VehiclePairingStore(directory, deviceId, receiverAddress.uppercase(java.util.Locale.ROOT), it,
                    ReceiverProtocol.VOLKSWAGEN)
            }
        }?.takeIf { it.read() != null }

        fun tryRestore(): Boolean {
            if (Build.VERSION.SDK_INT < 33 || invitation == null || savedCertificate == null ||
                savedPairing == null
            ) return false
            val invited = invitation.peek() ?: return false
            return restoreVolkswagen(
                invitation, invited, savedCertificate, savedPairing, attempt, receiverAddress, receiverName, pin, deviceId,
                onStreamStarted, onResolution, onDiagnostics, preferences, onStage,
            )
        }

        onStage(if (savedPairing != null) "正在等待已配对大众车机本轮 Wi-Fi 或蓝牙连接"
            else if (receiverName.isBlank()) "正在等待已配对车机连接" else "正在等待配对设备 $receiverName 连接")
        ProjectionLog.event("蓝牙 RFCOMM · 监听窗口=${acceptTimeout}ms")
        val listeningSince = SystemClock.elapsedRealtime()
        var outbound = false
        val connection = if (invitation == null) {
            listener.accept(acceptTimeout)
        } else {
            val inboundDeadline = listeningSince + minOf(8_000, acceptTimeout)
            val deadline = listeningSince + acceptTimeout
            var accepted: BluetoothSocket? = null
            var dialAttempted = false
            var lastFailure: IOException? = null
            while (accepted == null && !closed && SystemClock.elapsedRealtime() < deadline) {
                if (tryRestore()) return
                val now = SystemClock.elapsedRealtime()
                val remaining = deadline - now
                if (remaining <= 0) break
                if (!dialAttempted && now >= inboundDeadline) {
                    dialAttempted = true
                    accepted = dialVolkswagen(adapter.getRemoteDevice(receiverAddress), minOf(8_000L, remaining).toInt())
                    outbound = accepted != null
                } else {
                    val window = if (!dialAttempted) minOf(inboundDeadline - now, remaining) else remaining
                    val wait = if (savedPairing != null) minOf(window, 1_500L) else window
                    val acceptStarted = SystemClock.elapsedRealtime()
                    try {
                        accepted = listener.accept(wait.coerceAtLeast(1).toInt())
                    } catch (failure: IOException) {
                        if (closed || SystemClock.elapsedRealtime() - acceptStarted + 100 < wait) throw failure
                        lastFailure = failure
                    }
                }
            }
            accepted ?: throw lastFailure ?: IOException("等待大众车机连接超时")
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
        val waitingForBmwWindow = java.util.concurrent.atomic.AtomicBoolean()
        fun exchange(onBmwEndpoint: ((Iap2LinkProbe.Endpoint) -> Unit)? = null) = Iap2LinkProbe.run(
            connection.inputStream, connection.outputStream,
            onWifi = { wifi ->
                publishWifi(wifi)
                requestWifi(wifi)
            },
            profile = profile,
            deviceId = deviceId,
            onBmwAppsWindow = waitingForBmwWindow::set,
            initiateLink = outbound,
            power = power,
            onBmwEndpoint = onBmwEndpoint,
            onStage = onStage,
        )
        val endpoint = try {
            if (profile == HeadUnitProfile.BMW) {
                val ready = java.util.concurrent.CompletableFuture<Iap2LinkProbe.Endpoint>()
                Thread({
                    try {
                        ready.complete(exchange { ready.complete(it) })
                    } catch (failure: Exception) {
                        if (!ready.completeExceptionally(failure) && synchronized(this) { !closed && peer === connection }) {
                            ProjectionLog.failure("宝马蓝牙控制链路已结束", failure)
                        }
                    }
                }, "BMW Bluetooth iAP2").apply { isDaemon = true; start() }
                try {
                    ready.get(46, java.util.concurrent.TimeUnit.SECONDS)
                } catch (failure: java.util.concurrent.ExecutionException) {
                    throw failure.cause ?: failure
                } catch (failure: java.util.concurrent.TimeoutException) {
                    throw IOException("宝马蓝牙端点等待超时", failure)
                } catch (failure: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("已停止等待宝马蓝牙端点").apply { initCause(failure) }
                }
            } else exchange()
        } catch (failure: IOException) {
            if (waitingForBmwWindow.get() && failure !is ProtocolException) throw BmwWindowPending(failure)
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
                    ?: throw IOException("未收到本轮大众 CarPlay 邀请，请在车机上启动 Apple CarPlay")
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
                discoverBmwEndpoint(network, link, deviceId)
            }
            diagnostics = diagnostics.copy(host = address.first, port = address.second)
            onDiagnostics(diagnostics)
            val mguHealth = if (endpoint.protocol.isBmw) MguWifiHealth() else null
            val acl = mguHealth?.let { health -> watchBmwAcl(device.address, health) }
            try {
                onStage(CarPlayReceiverProbe.verify(
                    address.first, address.second, pin, endpoint.certificate, deviceId, attempt, {
                        if (volkswagenPeer != null && invitation != null && volkswagenPeer.write(endpoint.certificate)) {
                        VolkswagenWifiWake.remember(volkswagenPeer, invitation.network)
                    }
                        onStreamStarted()
                    },
                    onResolution = { resolution, codec ->
                        diagnostics = diagnostics.copy(resolution = resolution)
                        onDiagnostics(diagnostics)
                        onResolution(resolution, codec)
                    },
                    readResolutionOnly = readResolutionOnly,
                    preferences = preferences,
                    onAudio = { navigation, info ->
                        diagnostics = if (navigation) diagnostics.copy(navigationAudio = info) else diagnostics.copy(mediaAudio = info)
                        onDiagnostics(diagnostics)
                    },
                    onStage = onStage,
                    protocol = endpoint.protocol,
                    mguHealth = mguHealth,
                    power = power,
                    fallbackPorts = if (endpoint.protocol.isVehicle && endpoint.port == null) listOf(7000, 7001, 7100, 17000) else emptyList(),
                    beforeConnect = { host -> invitation?.requireFresh(host) },
                    beforeHandshake = { host -> invitation?.requireFresh(host, consume = true) },
                    pairingStore = pairingDirectory?.takeIf { endpoint.protocol.isVehicle }?.let {
                        VehiclePairingStore(it, deviceId, device.address, endpoint.certificate, endpoint.protocol)
                    },
                ))
                val wifi = endpoint.wifi
                if (readResolutionOnly && wifi != null && WinPlayHandoff.isWinPlay(endpoint.protocol, endpoint.identity)) {
                    onWinPlayPreflight(WinPlayHandoff(wifi, address.first, address.second, endpoint.certificate, device.address, deviceId))
                }
            } finally {
                acl?.close()
            }
        }
    }

    @androidx.annotation.RequiresApi(33)
    @Suppress("DEPRECATION")
    private fun watchBmwAcl(address: String, health: MguWifiHealth): Closeable? {
        val app = context ?: return null
        val receiver = object : android.content.BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                if (intent.action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (!address.equals(device.address, ignoreCase = true) || closed) return
                if (health.canKeep()) {
                    ProjectionLog.event("宝马蓝牙 ACL 已断开 · 保留当前 Wi-Fi CarPlay 会话")
                }
                // Once RTSP is active, its heartbeat and the network own session teardown.
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(app, receiver,
            android.content.IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        return Closeable { app.unregisterReceiver(receiver) }
    }

    private fun restoreVolkswagen(
        invitation: CarPlayControlInvitation, invited: CarPlayControlInvitation.Invite,
        certificate: java.security.cert.X509Certificate, store: VehiclePairingStore, fallback: CarPlaySockets,
        receiverAddress: String, receiverName: String, pin: String, deviceId: String, onStreamStarted: () -> Unit,
        onResolution: (CarPlayResolution, VideoCodec) -> Unit, onDiagnostics: (ReceiverDiagnostics) -> Unit,
        preferences: ProjectionPreferences,
        onStage: (String) -> Unit,
    ): Boolean {
        val host = invited.host.hostAddress ?: return false
        val candidate = CarPlaySockets()
        var started = false
        synchronized(this) {
            if (closed) throw IOException("CarPlay 会话已停止")
            sockets = candidate
        }
        return try {
            val link = connectivity.getLinkProperties(invitation.network)
            candidate.useNetwork(invitation.network, link?.interfaceName)
            val port = CarAirPlayDiscovery.findAdvertised(discovery, invitation.network)
                ?.takeIf { candidate.resolve(it.first) == invited.host }?.second ?: 7000
            var diagnostics = ReceiverDiagnostics(
                receiverName = receiverName, receiverAddress = receiverAddress, phoneAddress = deviceId,
                host = host, port = port, wifiInterface = link?.interfaceName,
                phoneIps = link?.linkAddresses?.mapNotNull { it.address.hostAddress }.orEmpty(),
            )
            onDiagnostics(diagnostics)
            onStage("已收到本轮邀请 · 正在验证大众车机身份")
            onStage(CarPlayReceiverProbe.verify(
                host, port, pin, certificate, deviceId, candidate,
                onStreamStarted = { started = true; onStreamStarted() },
                onResolution = { resolution, codec ->
                    diagnostics = diagnostics.copy(resolution = resolution)
                    onDiagnostics(diagnostics)
                    onResolution(resolution, codec)
                },
                onAudio = { navigation, info ->
                    diagnostics = if (navigation) diagnostics.copy(navigationAudio = info)
                        else diagnostics.copy(mediaAudio = info)
                    onDiagnostics(diagnostics)
                },
                preferences = preferences,
                protocol = ReceiverProtocol.VOLKSWAGEN, pairingStore = store, requireSavedPairing = true,
                fallbackPorts = listOf(7000, 7001, 7100, 17000), power = power,
                beforeConnect = { invitation.requireFresh(it, expected = invited) },
                beforeHandshake = { invitation.requireFresh(it, consume = true, expected = invited) },
                onStage = onStage,
            ))
            true
        } catch (failure: Exception) {
            if (started || closed || failure is InterruptedException || failure is java.io.InterruptedIOException &&
                Thread.currentThread().isInterrupted
            ) throw failure
            ProjectionLog.failure("大众 Wi-Fi 恢复失败 · 继续本轮蓝牙认证", failure)
            false
        } finally {
            invitation.discard(invited)
            candidate.close()
            synchronized(this) { if (!closed && sockets === candidate) sockets = fallback }
        }
    }

    private fun discoverBmwEndpoint(network: Network, link: LinkProperties, deviceId: String): Pair<String, Int> {
        if (Build.VERSION.SDK_INT >= 33 && discovery != null) {
            val local = link.linkAddresses.firstOrNull { it.address is Inet4Address }?.address
            if (local != null) {
                val invitation = try {
                    synchronized(this) {
                        if (closed) throw IOException("CarPlay 会话已停止")
                        CarPlayControlInvitation(
                            connectivity, discovery, network, local, deviceId,
                            instanceName = "CarNat", sourceVersion = "540.31",
                        ).also {
                            controlInvitation = it
                            it.start()
                        }
                    }
                } catch (failure: IOException) {
                    if (closed) throw failure
                    ProjectionLog.failure("宝马控制服务广播不可用", failure)
                    null
                }
                val invited = invitation?.await(expected = null, timeoutMs = 3_000)
                if (invited != null) {
                    val host = invited.hostAddress ?: throw IOException("宝马车机地址不可用")
                    val advertised = CarAirPlayDiscovery.findAdvertised(discovery, network)
                    return host to (advertised?.takeIf { it.first.substringBefore('%') == host.substringBefore('%') }?.second ?: 7000)
                }
            }
        }
        return CarAirPlayDiscovery.find(discovery, network, link)
    }

    @Suppress("DEPRECATION")
    private fun awaitVolkswagenWifi(timeoutMs: Int, onStage: (String) -> Unit): Network {
        currentVolkswagenWifi()?.let { return it }
        onStage("等待接入大众车机 Wi-Fi")
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val signal = java.util.concurrent.Semaphore(0)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (signal.availablePermits() == 0) signal.release()
            }
        }
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(request, callback)
        try {
            while (!closed) {
                currentVolkswagenWifi()?.let { return it }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                signal.tryAcquire(minOf(remaining, 500), java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("等待大众车机 Wi-Fi 已取消").apply { initCause(error) }
        } finally {
            connectivity.unregisterNetworkCallback(callback)
        }
        throw IOException(if (closed) "CarPlay 会话已停止" else "等待大众车机 Wi-Fi 超时")
    }

    private fun currentVolkswagenWifi(): Network? {
        fun isWifi(network: Network) =
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                connectivity.getLinkProperties(network)?.linkAddresses?.any { it.address is Inet4Address } == true
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
        controlInvitation?.close()
        controlInvitation = null
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
