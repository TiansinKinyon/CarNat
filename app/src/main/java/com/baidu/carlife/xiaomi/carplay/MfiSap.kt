package com.baidu.carlife.xiaomi.carplay

import java.io.ByteArrayInputStream
import java.net.ProtocolException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object MfiSap {
    fun verify(control: RtspControl, bluetoothCertificate: X509Certificate) {
        val ephemeral = PairingCrypto.ephemeralKey()
        val localPublic = ephemeral.generatePublicKey().encoded
        val reply = control.request(
            "POST",
            "/auth-setup",
            mapOf("Content-Type" to "application/octet-stream"),
            byteArrayOf(1) + localPublic,
        )
        if (reply.status !in 200..299) throw ProtocolException("MFi-SAP rejected by receiver (RTSP ${reply.status})")
        val body = ByteBuffer.wrap(reply.body)
        if (body.remaining() < 40) throw ProtocolException("Truncated MFi-SAP response")
        val remotePublic = ByteArray(32).also { body.get(it) }
        val certificateBytes = body.readField(8192)
        val encryptedSignature = body.readField(64)
        if (encryptedSignature.size != 64 || body.hasRemaining()) {
            throw ProtocolException("Invalid MFi-SAP signature field")
        }
        val leaf = CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(certificateBytes))
            .filterIsInstance<X509Certificate>()
            .singleOrNull { it.basicConstraints < 0 }
            ?: throw ProtocolException("Expected one MFi-SAP end-entity certificate")
        if (!MessageDigest.isEqual(bluetoothCertificate.encoded, leaf.encoded)) {
            throw ProtocolException("MFi-SAP certificate does not match the authenticated Bluetooth receiver")
        }

        val shared = PairingCrypto.sharedSecret(ephemeral, remotePublic)
        val signature = Cipher.getInstance("AES/CTR/NoPadding").run {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(legacyKey("AES-KEY", shared), "AES"),
                IvParameterSpec(legacyKey("AES-IV", shared)),
            )
            doFinal(encryptedSignature)
        }
        val transcript = MessageDigest.getInstance("SHA-256").digest(remotePublic + localPublic)
        if (!MfiChallenge.verify(leaf.publicKey, transcript, signature)) {
            throw ProtocolException("MFi-SAP signature did not match the authenticated receiver")
        }
    }

    private fun legacyKey(label: String, shared: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(label.toByteArray(Charsets.US_ASCII) + shared).copyOf(16)

    private fun ByteBuffer.readField(max: Int): ByteArray {
        if (remaining() < 4) throw ProtocolException("Missing MFi-SAP field length")
        val size = int
        if (size !in 1..minOf(max, remaining())) throw ProtocolException("Invalid MFi-SAP field length")
        return ByteArray(size).also { get(it) }
    }
}
