package com.baidu.carlife.xiaomi.carplay

import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket
import java.security.cert.X509Certificate

internal object CarPlayReceiverProbe {
    fun verify(
        host: String, port: Int, pin: String,
        bluetoothCertificate: X509Certificate? = null,
        deviceId: String = "",
        onStage: (String) -> Unit,
    ): String {
        Socket().use { socket ->
            onStage("正在连接接收端")
            socket.connect(InetSocketAddress(host, port), 5_000)
            socket.soTimeout = 8_000
            RtspControl(socket.getInputStream(), socket.getOutputStream(), socket).use { control ->
                onStage("正在读取接收端信息")
                val info = control.request("GET", "/info", mapOf("User-Agent" to "AirPlay/540.31"))
                if (info.status !in 200..299) {
                    throw ProtocolException("接收端拒绝 /info（RTSP ${info.status}）")
                }
                onStage("正在验证配对 PIN 和接收端身份")
                val keys = CarPlayPairing(control).pair(pin)
                if (bluetoothCertificate != null) {
                    onStage("正在验证加密网络会话的 MFi-SAP 签名")
                    MfiSap.verify(control, bluetoothCertificate)
                    val authenticated = "加密网络会话和 MFi-SAP 签名验证通过"
                    if (deviceId.isEmpty()) return authenticated
                    onStage(authenticated)
                    return CarPlayScreenProbe.run(host, control, info.body, deviceId, keys, onStage)
                }
                return "接收端身份验证通过 · /info ${info.body.size} 字节"
            }
        }
    }
}
