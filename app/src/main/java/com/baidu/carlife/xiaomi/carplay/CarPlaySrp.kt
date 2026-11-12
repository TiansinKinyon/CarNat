package com.baidu.carlife.xiaomi.carplay

import java.math.BigInteger
import java.net.ProtocolException
import java.security.SecureRandom

internal object CarPlaySrp {
    val modulus = BigInteger(
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD" +
            "3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5" +
            "AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F35" +
            "6208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28" +
            "FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A85521A" +
            "BDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE" +
            "6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5B" +
            "FCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF", 16,
    )
    val generator: BigInteger = BigInteger.valueOf(5)
    private val identity = "Pair-Setup".toByteArray()
    val multiplier = number(PairingCrypto.hash(pad(modulus), pad(generator)))

    fun prove(pin: String, salt: ByteArray, serverKey: ByteArray): Proof {
        val serverPublic = number(serverKey)
        if (serverPublic.signum() == 0 || serverPublic >= modulus) {
            throw ProtocolException("Invalid receiver SRP public key")
        }

        val privateKey = BigInteger(256, SecureRandom()).setBit(255)
        val publicKey = pad(generator.modPow(privateKey, modulus))
        val verifierExponent = number(PairingCrypto.hash(salt, PairingCrypto.hash(identity, byteArrayOf(58), pin.toByteArray())))
        val scramble = number(PairingCrypto.hash(publicKey, pad(serverPublic)))
        if (scramble.signum() == 0) throw ProtocolException("Invalid SRP scramble")
        val base = (serverPublic - multiplier * generator.modPow(verifierExponent, modulus)).mod(modulus)
        if (base.signum() == 0) throw ProtocolException("Invalid SRP shared base")
        val secret = base.modPow(privateKey + scramble * verifierExponent, modulus)
        val sessionKey = PairingCrypto.hash(unsigned(secret))
        val modulusHash = PairingCrypto.hash(unsigned(modulus))
        val generatorHash = PairingCrypto.hash(unsigned(generator))
        val groupProof = ByteArray(64) { (modulusHash[it].toInt() xor generatorHash[it].toInt()).toByte() }
        val clientProof = PairingCrypto.hash(
            groupProof, PairingCrypto.hash(identity), salt, publicKey, pad(serverPublic), sessionKey,
        )
        return Proof(publicKey, clientProof, PairingCrypto.hash(publicKey, clientProof, sessionKey), sessionKey)
    }

    fun pad(value: BigInteger): ByteArray {
        val bytes = unsigned(value)
        return ByteArray(384 - bytes.size) + bytes
    }

    fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    fun number(value: ByteArray) = BigInteger(1, value)

    class Proof(val publicKey: ByteArray, val clientProof: ByteArray, val serverProof: ByteArray, val key: ByteArray)
}
