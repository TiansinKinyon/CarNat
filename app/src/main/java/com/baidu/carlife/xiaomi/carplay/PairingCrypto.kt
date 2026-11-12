package com.baidu.carlife.xiaomi.carplay

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object PairingCrypto {
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

    fun generate(algorithm: String): KeyPair {
        val generator = if (algorithm == "X25519") {
            KeyPairGenerator.getInstance("XDH").apply { initialize(NamedParameterSpec.X25519) }
        } else {
            KeyPairGenerator.getInstance(algorithm)
        }
        return generator.generateKeyPair()
    }

    fun publicBytes(pair: KeyPair): ByteArray = pair.public.encoded.takeLast(32).toByteArray()

    fun sharedSecret(pair: KeyPair, receiverPublicKey: ByteArray): ByteArray =
        KeyAgreement.getInstance("XDH").run {
            init(pair.private)
            doPhase(importKey("XDH", 110, receiverPublicKey), true)
            generateSecret()
        }

    fun sign(pair: KeyPair, message: ByteArray): ByteArray = Signature.getInstance("Ed25519").run {
        initSign(pair.private)
        update(message)
        sign()
    }

    fun verify(key: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        Signature.getInstance("Ed25519").run {
            initVerify(importKey("Ed25519", 112, key))
            update(message)
            verify(signature)
        }

    fun seal(key: ByteArray, nonceLabel: String, data: ByteArray): ByteArray =
        crypt(Cipher.ENCRYPT_MODE, key, nonceLabel, data)

    fun open(key: ByteArray, nonceLabel: String, data: ByteArray): ByteArray =
        crypt(Cipher.DECRYPT_MODE, key, nonceLabel, data)

    private fun importKey(algorithm: String, oid: Byte, raw: ByteArray) =
        KeyFactory.getInstance(algorithm).generatePublic(
            X509EncodedKeySpec(byteArrayOf(48, 42, 48, 5, 6, 3, 43, 101, oid, 3, 33, 0) + raw),
        )

    private fun crypt(mode: Int, key: ByteArray, label: String, data: ByteArray): ByteArray =
        Cipher.getInstance("ChaCha20-Poly1305").run {
            val nonce = ByteArray(4) + label.toByteArray(Charsets.US_ASCII)
            init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            doFinal(data)
        }
}
