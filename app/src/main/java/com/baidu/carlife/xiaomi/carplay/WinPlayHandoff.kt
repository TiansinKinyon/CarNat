package com.baidu.carlife.xiaomi.carplay

import android.content.Context
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** One authenticated endpoint passed only to the process consuming the CarWith launch ticket. */
internal class WinPlayHandoff(
    val wifi: ReceiverWifi,
    val host: String,
    val port: Int,
    val certificate: X509Certificate,
    val peer: String,
    val deviceId: String,
) {
    companion object {
        private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "winplay-launch-ticket"))

        fun save(context: Context, token: String, handoff: WinPlayHandoff): Boolean {
            val file = file(context)
            return runCatching {
                val stream = file.startWrite()
                try {
                    val output = DataOutputStream(stream)
                    output.writeUTF(token)
                    output.writeUTF(handoff.wifi.ssid)
                    output.writeUTF(handoff.wifi.passphrase)
                    output.writeInt(handoff.wifi.security)
                    output.writeUTF(handoff.host)
                    output.writeInt(handoff.port)
                    output.writeUTF(handoff.peer)
                    output.writeUTF(handoff.deviceId)
                    val certificate = handoff.certificate.encoded
                    require(certificate.size in 1..8192)
                    output.writeInt(certificate.size)
                    output.write(certificate)
                    output.flush()
                    file.finishWrite(stream)
                } catch (failure: Exception) {
                    file.failWrite(stream)
                    throw failure
                }
            }.isSuccess
        }

        fun read(context: Context, token: String): WinPlayHandoff? = runCatching {
            DataInputStream(file(context).openRead()).use { input ->
                if (input.readUTF() != token) return null
                val wifi = ReceiverWifi(input.readUTF(), input.readUTF(), input.readInt())
                val host = input.readUTF()
                val port = input.readInt()
                val peer = input.readUTF()
                val deviceId = input.readUTF()
                val size = input.readInt()
                require(size in 1..8192 && host.length in 1..255 && port in 1..65535)
                val bytes = ByteArray(size).also(input::readFully)
                require(input.read() == -1)
                val certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(bytes.inputStream()) as X509Certificate
                MfiTrust.validate(certificate)
                WinPlayHandoff(wifi, host, port, certificate, peer, deviceId)
            }
        }.getOrNull()

        fun clear(context: Context) = file(context).delete()

        fun isWinPlay(protocol: ReceiverProtocol, identity: String?): Boolean =
            protocol == ReceiverProtocol.GENERIC && identity?.startsWith("WinPlay", ignoreCase = true) == true
    }
}
