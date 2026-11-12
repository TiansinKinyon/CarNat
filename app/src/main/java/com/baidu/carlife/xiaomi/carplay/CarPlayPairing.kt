package com.baidu.carlife.xiaomi.carplay

import java.net.ProtocolException
import java.security.MessageDigest
import java.util.UUID

internal class CarPlayPairing(private val control: RtspControl) {
    private val identity = UUID.randomUUID().toString().toByteArray()
    private val signingKey = PairingCrypto.signingKey()

    /** Establishes a receiver identity with its user-provided PIN, then authenticates the live connection. */
    fun pair(pin: String): CarPlaySessionKeys {
        require(pin.length in 4..16 && pin.all { it.isDigit() || it == '-' }) { "Enter the receiver's pairing PIN" }
        val receiver = setup(pin)
        return verify(receiver)
    }

    private fun setup(pin: String): ReceiverIdentity {
        val challenge = exchange("/pair-setup", 2, PairingTlv.METHOD to byteArrayOf(0))
        val salt = PairingTlv.requireField(challenge, PairingTlv.SALT, 16..64)
        val serverPublic = PairingTlv.requireField(challenge, PairingTlv.PUBLIC_KEY, 1..384)
        val srp = CarPlaySrp.prove(pin, salt, serverPublic)
        val proof = exchange(
            "/pair-setup", 4,
            PairingTlv.PUBLIC_KEY to srp.publicKey,
            PairingTlv.PROOF to srp.clientProof,
        )
        if (!MessageDigest.isEqual(srp.serverProof, PairingTlv.requireField(proof, PairingTlv.PROOF, 64..64))) {
            throw ProtocolException("Receiver's PIN proof did not match")
        }
        val encryptionKey = PairingCrypto.derive(srp.key, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val signingMaterial = PairingCrypto.derive(
            srp.key, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info",
        )
        val publicKey = signingKey.generatePublicKey().encoded
        val request = PairingTlv.encode(
            PairingTlv.IDENTIFIER to identity,
            PairingTlv.PUBLIC_KEY to publicKey,
            PairingTlv.SIGNATURE to PairingCrypto.sign(signingKey, signingMaterial + identity + publicKey),
        )
        val reply = exchange(
            "/pair-setup", 6,
            PairingTlv.ENCRYPTED to PairingCrypto.seal(encryptionKey, "PS-Msg05", request),
        )
        val receiver = readIdentity(reply, encryptionKey, "PS-Msg06")
        val receiverMaterial = PairingCrypto.derive(
            srp.key, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info",
        )
        val receiverKey = PairingTlv.requireField(receiver, PairingTlv.PUBLIC_KEY, 32..32)
        val receiverId = PairingTlv.requireField(receiver, PairingTlv.IDENTIFIER, 1..255)
        verifySignature(receiver, receiverKey, receiverMaterial + receiverId + receiverKey)
        return ReceiverIdentity(receiverId, receiverKey)
    }

    private fun verify(receiver: ReceiverIdentity): CarPlaySessionKeys {
        val ephemeral = PairingCrypto.ephemeralKey()
        val localPublic = ephemeral.generatePublicKey().encoded
        val challenge = exchange("/pair-verify", 2, PairingTlv.PUBLIC_KEY to localPublic)
        val peerPublic = PairingTlv.requireField(challenge, PairingTlv.PUBLIC_KEY, 32..32)
        val shared = PairingCrypto.sharedSecret(ephemeral, peerPublic)
        val key = PairingCrypto.derive(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val proof = readIdentity(challenge, key, "PV-Msg02")
        val receiverId = PairingTlv.requireField(proof, PairingTlv.IDENTIFIER, 1..255)
        if (!receiver.identifier.contentEquals(receiverId)) throw ProtocolException("Receiver identity changed")
        verifySignature(proof, receiver.key, peerPublic + receiverId + localPublic)

        val request = PairingTlv.encode(
            PairingTlv.IDENTIFIER to identity,
            PairingTlv.SIGNATURE to PairingCrypto.sign(signingKey, localPublic + identity + peerPublic),
        )
        exchange("/pair-verify", 4, PairingTlv.ENCRYPTED to PairingCrypto.seal(key, "PV-Msg03", request))
        control.enableEncryption(
            PairingCrypto.derive(shared, "Control-Salt", "Control-Read-Encryption-Key"),
            PairingCrypto.derive(shared, "Control-Salt", "Control-Write-Encryption-Key"),
        )
        return CarPlaySessionKeys(shared)
    }

    private fun readIdentity(
        fields: Map<Int, ByteArray>, key: ByteArray, nonce: String,
    ): Map<Int, ByteArray> {
        val encrypted = PairingTlv.requireField(fields, PairingTlv.ENCRYPTED, 16..8192)
        return PairingTlv.decode(PairingCrypto.open(key, nonce, encrypted))
    }

    private fun verifySignature(fields: Map<Int, ByteArray>, key: ByteArray, message: ByteArray) {
        val signature = PairingTlv.requireField(fields, PairingTlv.SIGNATURE, 64..64)
        if (!PairingCrypto.verify(key, message, signature)) throw ProtocolException("Receiver signature did not match")
    }

    private fun exchange(path: String, state: Int, vararg fields: Pair<Int, ByteArray>): Map<Int, ByteArray> {
        val protocolHeader = if (path == "/pair-setup") "X-Apple-HKP" to "2" else "X-Apple-PD" to "1"
        val response = control.request(
            "POST", path, mapOf(protocolHeader, "Content-Type" to "application/pairing+tlv8"),
            PairingTlv.encode(PairingTlv.STATE to byteArrayOf((state - 1).toByte()), *fields),
        )
        if (response.status !in 200..299) throw ProtocolException("$path rejected by receiver (RTSP ${response.status})")
        return PairingTlv.response(response.body, state)
    }

    private class ReceiverIdentity(val identifier: ByteArray, val key: ByteArray)
}

internal class CarPlaySessionKeys(private val sharedSecret: ByteArray) {
    fun eventRead() = PairingCrypto.derive(sharedSecret, "Events-Salt", "Events-Write-Encryption-Key")
    fun eventWrite() = PairingCrypto.derive(sharedSecret, "Events-Salt", "Events-Read-Encryption-Key")

    fun video(connectionId: Long): ByteArray = PairingCrypto.derive(
        sharedSecret, "DataStream-Salt$connectionId", "DataStream-Output-Encryption-Key",
    )
}
