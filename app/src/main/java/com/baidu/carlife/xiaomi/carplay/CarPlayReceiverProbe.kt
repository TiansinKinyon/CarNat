package com.baidu.carlife.xiaomi.carplay

import com.baidu.carlife.xiaomi.diagnostics.ProjectionLog
import com.dd.plist.NSString
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.security.cert.X509Certificate

internal object CarPlayReceiverProbe {
    fun verify(
        host: String, port: Int, pin: String,
        bluetoothCertificate: X509Certificate? = null,
        deviceId: String = "",
        sockets: CarPlaySockets = CarPlaySockets(),
        onStreamStarted: () -> Unit = {},
        onResolution: (CarPlayResolution, VideoCodec) -> Unit = { _, _ -> },
        readResolutionOnly: Boolean = false,
        preferences: ProjectionPreferences = ProjectionPreferences(),
        onAudio: (Boolean, CarPlayAudioInfo) -> Unit = { _, _ -> },
        protocol: ReceiverProtocol = ReceiverProtocol.GENERIC,
        pairingStore: VehiclePairingStore? = null,
        mguHealth: MguWifiHealth? = null,
        onTransportAuthenticated: () -> Unit = {},
        power: () -> Iap2PowerStatus = { Iap2PowerStatus() },
        fallbackPorts: List<Int> = emptyList(),
        beforeConnect: (InetAddress) -> Unit = {},
        beforeHandshake: (InetAddress) -> Unit = {},
        requireSavedPairing: Boolean = false,
        onStage: (String) -> Unit,
    ): String {
        val saved = pairingStore?.read()
        if (requireSavedPairing && saved == null) throw ProtocolException("车机尚无可恢复的 AirPlay 配对记录")
        val address = sockets.resolve(host)
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) {
            throw ProtocolException("CarPlay endpoint is not a remote unicast address")
        }
        val ports = (listOf(port) + fallbackPorts).filter { it in 1..65535 }.distinct().take(4)
        fun openControl(): java.net.Socket {
            var lastFailure: IOException? = null
            for (candidate in ports) {
                beforeConnect(address)
                val socket = sockets.open()
                try {
                    socket.connect(InetSocketAddress(address, candidate), if (ports.size == 1) 5_000 else 2_000)
                    return socket
                } catch (failure: IOException) {
                    lastFailure = failure
                    sockets.release(socket)
                }
            }
            throw lastFailure ?: ProtocolException("CarPlay 接收端没有有效控制端口")
        }
        fun connect(saved: PairingCredentials?): String {
            onStage("正在连接接收端")
            val socket = openControl()
            return try {
                beforeHandshake(address)
                socket.soTimeout = 8_000
                RtspControl(socket.getInputStream(), socket.getOutputStream(), socket).use controlScope@{ control ->
                    val info = when (protocol) {
                        ReceiverProtocol.GENERIC -> readInfo(control, protocol)
                        ReceiverProtocol.BMW_MGU, ReceiverProtocol.VOLKSWAGEN -> readInfo(control, protocol, required = false)
                    }
                    val effectiveProtocol = if (protocol.isBmw && info != null) {
                        val identity = CarPlayPlist.readDictionary(info)
                        protocol.forReceiver(*listOf("name", "model", "manufacturer").map {
                            (identity.objectForKey(it) as? NSString)?.content
                        }.toTypedArray())
                    } else protocol
                    if (effectiveProtocol != protocol) onStage("已识别桌面接收端 · 使用通用 CarPlay 协议")
                    onStage("正在验证配对 PIN 和接收端身份")
                    val pairing = CarPlayPairing(control, saved.takeIf { effectiveProtocol == protocol }, effectiveProtocol)
                    val keys = pairing.pair(pin)
                    if (bluetoothCertificate != null) {
                        onStage("正在验证加密网络会话的 MFi-SAP 签名")
                        MfiSap.verify(control, bluetoothCertificate)
                        onTransportAuthenticated()
                        if (saved == null && pairingStore != null && effectiveProtocol == protocol) {
                            pairing.credentials()?.let { credentials ->
                                try {
                                    pairingStore.write(credentials)
                                } catch (failure: IOException) {
                                    ProjectionLog.failure("无法保存车机网络配对记录", failure)
                                }
                            }
                        }
                        val authenticated = "加密网络会话和 MFi-SAP 签名验证通过"
                        if (deviceId.isEmpty()) return@controlScope authenticated
                        onStage(authenticated)
                        if (readResolutionOnly && !effectiveProtocol.isVehicle) {
                            val response = checkNotNull(info)
                            onResolution(CarPlayDisplay.fromReceiverInfo(CarPlayPlist.readDictionary(response)).resolution, VideoCodec.H264)
                            return@controlScope "已读取车机主屏分辨率"
                        }
                        return@controlScope CarPlayScreenProbe.run(
                            socket.inetAddress, control, info, deviceId, keys, bluetoothCertificate,
                            sockets, onStreamStarted, onResolution, preferences, onAudio, onStage, effectiveProtocol, readResolutionOnly,
                            receiverPort = socket.port, power = power, mguHealth = mguHealth,
                        )
                    }
                    "接收端身份验证通过 · /info ${info?.size ?: 0} 字节"
                }
            } finally {
                sockets.release(socket)
            }
        }

        if (saved == null) return connect(null)
        return try {
            connect(saved)
        } catch (failure: ReceiverPairingMissing) {
            if (requireSavedPairing) throw failure
            onStage("车机配对记录已清除 · 正在重新配对")
            connect(null)
        }
    }

    /** An HTTP/RTSP rejection can be retried on this socket. A transport failure ends the attempt. */
    fun readInfo(control: RtspControl, protocol: ReceiverProtocol, required: Boolean = true): ByteArray? {
        val version = if (protocol == ReceiverProtocol.VOLKSWAGEN) CarPlayControlInvitation.SOURCE_VERSION else "540.31"
        val headers = mapOf("User-Agent" to "AirPlay/$version")
        val get = control.request("GET", "/info", headers)
        if (get.status in 200..299 && (!protocol.isVehicle || get.body.isNotEmpty())) return get.body
        if (protocol.isVehicle) {
            val post = control.request("POST", "/info",
                headers + ("Content-Type" to "application/x-apple-binary-plist"),
                com.dd.plist.BinaryPropertyListWriter.writeToArray(com.dd.plist.NSDictionary()))
            if (post.status in 200..299 && post.body.isNotEmpty()) return post.body
            if (!required) return null
            throw ProtocolException("车机未提供 /info（GET ${get.status}，POST ${post.status}）")
        }
        throw ProtocolException("接收端拒绝 /info（RTSP ${get.status}）")
    }
}
