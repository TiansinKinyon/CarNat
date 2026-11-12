package com.baidu.carlife.xiaomi.carplay

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

internal object PairingCrypto {
    private val random = SecureRandom()

    fun hash(vararg fields: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").run {
        fields.forEach(::update)
        digest()
    }

    fun derive(secret: ByteArray, salt: String, info: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(salt.toByteArray(), "HmacSHA512"))
        val prk = mac.doFinal(secret)
        mac.init(SecretKeySpec(prk, "HmacSHA512"))
        mac.update(info.toByteArray())
        return mac.doFinal(byteArrayOf(1)).copyOf(32)
    }

    // Use the lightweight API without installing a process-wide JCA provider.
    fun signingKey() = Ed25519PrivateKeyParameters(random)
    fun ephemeralKey() = X25519PrivateKeyParameters(random)

    fun sharedSecret(key: X25519PrivateKeyParameters, receiverPublicKey: ByteArray): ByteArray =
        ByteArray(32).also { key.generateSecret(X25519PublicKeyParameters(receiverPublicKey, 0), it, 0) }

    fun sign(key: Ed25519PrivateKeyParameters, message: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, key)
        update(message, 0, message.size)
        generateSignature()
    }

    fun verify(key: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(key, 0))
            update(message, 0, message.size)
            verifySignature(signature)
        }

    fun seal(key: ByteArray, nonceLabel: String, data: ByteArray): ByteArray =
        crypt(Cipher.ENCRYPT_MODE, key, nonceLabel, data)

    fun open(key: ByteArray, nonceLabel: String, data: ByteArray): ByteArray =
        crypt(Cipher.DECRYPT_MODE, key, nonceLabel, data)

    private fun crypt(mode: Int, key: ByteArray, label: String, data: ByteArray): ByteArray =
        Cipher.getInstance("ChaCha20-Poly1305").run {
            val nonce = ByteArray(4) + label.toByteArray(Charsets.US_ASCII)
            init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            doFinal(data)
        }
}
