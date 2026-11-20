package com.baidu.carlife.xiaomi.carplay

import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale

/** Stores the verified receiver certificate and the associated hotspot name. */
internal class VolkswagenPeerStore(directory: File, phoneId: String, bluetoothAddress: String) {
    private val file = AtomicFile(File(directory, "volkswagen-peer-" + MessageDigest.getInstance("SHA-256")
        .digest("$phoneId|${bluetoothAddress.uppercase(Locale.ROOT)}".toByteArray(Charsets.US_ASCII))
        .joinToString("") { "%02x".format(it.toInt() and 255) }))

    private val hotspot = AtomicFile(File(file.baseFile.path + ".ssid"))

    fun readSsid(): String? = runCatching {
        hotspot.openRead().use { input ->
            val bytes = ByteArray(128)
            val length = input.read(bytes)
            if (length < 1 || input.read() != -1) return@use null
            String(bytes, 0, length, Charsets.UTF_8)
        }
    }.getOrNull()

    fun rememberSsid(ssid: String) {
        val bytes = ssid.toByteArray(Charsets.UTF_8)
        if (bytes.size !in 1..128) return
        runCatching {
            val stream = hotspot.startWrite()
            try {
                stream.write(bytes)
                hotspot.finishWrite(stream)
            } catch (failure: Exception) {
                hotspot.failWrite(stream)
                throw failure
            }
        }
    }

    fun read(): X509Certificate? = runCatching {
        DataInputStream(file.openRead()).use { input ->
            if (input.readUnsignedByte() != 1) return null
            val size = input.readUnsignedShort()
            if (size !in 1..8192) return null
            val bytes = ByteArray(size).also(input::readFully)
            if (input.read() != -1) return null
            (CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate)
                .also(MfiTrust::validate)
        }
    }.getOrNull()

    fun write(certificate: X509Certificate): Boolean = runCatching {
        val bytes = certificate.encoded
        require(bytes.size in 1..8192)
        val stream = file.startWrite()
        try {
            val output = DataOutputStream(stream)
            output.writeByte(1)
            output.writeShort(bytes.size)
            output.write(bytes)
            output.flush()
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }.isSuccess
}
