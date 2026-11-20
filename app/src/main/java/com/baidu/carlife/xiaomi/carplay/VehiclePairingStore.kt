package com.baidu.carlife.xiaomi.carplay

import android.util.AtomicFile
import java.io.DataInputStream
import java.io.IOException
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate

internal class PairingCredentials(
    val controllerId: ByteArray,
    val controllerKey: ByteArray,
    val receiverId: ByteArray,
    val receiverKey: ByteArray,
)

/** App-private, excluded from Android backup, and scoped to a verified Bluetooth peer. */
internal class VehiclePairingStore(directory: File, phoneId: String, carAddress: String, certificate: X509Certificate, protocol: ReceiverProtocol) {
    private val file: AtomicFile

    init {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(phoneId.toByteArray(Charsets.US_ASCII))
        digest.update(carAddress.toByteArray(Charsets.US_ASCII))
        val fingerprint = digest.digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
        val brand = if (protocol.isBmw) "bmw" else "volkswagen"
        file = AtomicFile(File(directory, "carplay-$brand-$fingerprint"))
    }

    fun read(): PairingCredentials? = try {
        DataInputStream(file.openRead()).use { input ->
            if (input.readUnsignedByte() != 1) return null
            val controllerId = ByteArray(36).also(input::readFully)
            val controllerKey = ByteArray(32).also(input::readFully)
            val peerLength = input.readUnsignedByte()
            if (peerLength == 0) return null
            val receiverId = ByteArray(peerLength).also(input::readFully)
            val receiverKey = ByteArray(32).also(input::readFully)
            if (input.read() != -1) return null
            PairingCredentials(controllerId, controllerKey, receiverId, receiverKey)
        }
    } catch (_: IOException) {
        null
    }

    @Throws(IOException::class)
    fun write(credentials: PairingCredentials) {
        val output = file.startWrite()
        try {
            output.write(1)
            output.write(credentials.controllerId)
            output.write(credentials.controllerKey)
            output.write(credentials.receiverId.size)
            output.write(credentials.receiverId)
            output.write(credentials.receiverKey)
            file.finishWrite(output)
        } catch (error: IOException) {
            file.failWrite(output)
            throw error
        }
    }
}
