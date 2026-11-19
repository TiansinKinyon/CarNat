package com.baidu.carlife.xiaomi.carplay

import com.baidu.carlife.xiaomi.diagnostics.ProjectionLog
import java.io.IOException
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
        onResolution: (CarPlayResolution) -> Unit = {},
        readResolutionOnly: Boolean = false,
        preferences: ProjectionPreferences = ProjectionPreferences(),
        onAudio: (Boolean, CarPlayAudioInfo) -> Unit = { _, _ -> },
        protocol: ReceiverProtocol = ReceiverProtocol.GENERIC,
        customPin: Boolean = false,
        pairingStore: BmwPairingStore? = null,
        onStage: (String) -> Unit,
    ): String {
        fun connect(saved: PairingCredentials?, replacing: Boolean): String = sockets.open().use { socket ->
            onStage("正在连接接收端")
            val address = sockets.resolve(host)
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) {
                throw ProtocolException("CarPlay endpoint is not a remote unicast address")
            }
            socket.connect(InetSocketAddress(address, port), 5_000)
            socket.soTimeout = 8_000
            RtspControl(socket.getInputStream(), socket.getOutputStream(), socket).use controlScope@{ control ->
                val info = when (protocol) {
                    ReceiverProtocol.GENERIC -> readInfo(control, protocol)
                    ReceiverProtocol.BMW_MGU -> readInfo(control, protocol, required = false)
                    ReceiverProtocol.BMW_ID9 -> null
                }
                onStage("正在验证配对 PIN 和接收端身份")
                val pairing = CarPlayPairing(control, saved, protocol)
                val keys = pairing.pair(pin)
                if (bluetoothCertificate != null) {
                    onStage("正在验证加密网络会话的 MFi-SAP 签名")
                    val sapVerified = MfiSap.verify(
                        control, bluetoothCertificate,
                        allowUnsupported = protocol == ReceiverProtocol.BMW_ID9 && !replacing && customPin && pin != "3939",
                    )
                    if (saved == null && pairingStore != null) {
                        pairing.credentials()?.let { credentials ->
                            try {
                                pairingStore.write(credentials)
                            } catch (failure: IOException) {
                                ProjectionLog.failure("无法保存宝马网络配对记录", failure)
                            }
                        }
                    }
                    val authenticated = if (sapVerified) "加密网络会话和 MFi-SAP 签名验证通过"
                        else "加密网络配对通过 · 车机未提供 MFi-SAP 接口"
                    if (deviceId.isEmpty()) return@controlScope authenticated
                    onStage(authenticated)
                    if (readResolutionOnly && !protocol.isBmw) {
                        val response = checkNotNull(info)
                        onResolution(CarPlayDisplay.fromReceiverInfo(CarPlayPlist.readDictionary(response)).resolution)
                        return@controlScope "已读取车机主屏分辨率"
                    }
                    return@controlScope CarPlayScreenProbe.run(
                        socket.inetAddress, control, info, deviceId, keys, bluetoothCertificate,
                        sockets, onStreamStarted, onResolution, preferences, onAudio, onStage, protocol, readResolutionOnly,
                    )
                }
                "接收端身份验证通过 · /info ${info?.size ?: 0} 字节"
            }
        }

        val saved = pairingStore?.read()
        if (saved == null) return connect(null, replacing = false)
        return try {
            connect(saved, replacing = false)
        } catch (_: ReceiverPairingMissing) {
            onStage("车机配对记录已清除 · 正在重新配对")
            connect(null, replacing = true)
        }
    }

    /** An HTTP/RTSP rejection can be retried on this socket. A transport failure ends the attempt. */
    fun readInfo(control: RtspControl, protocol: ReceiverProtocol, required: Boolean = true): ByteArray? {
        val headers = mapOf("User-Agent" to "AirPlay/540.31")
        val get = control.request("GET", "/info", headers)
        if (get.status in 200..299 && (!protocol.isBmw || get.body.isNotEmpty())) return get.body
        if (protocol.isBmw) {
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
