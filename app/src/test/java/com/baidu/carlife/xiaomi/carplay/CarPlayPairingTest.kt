package com.baidu.carlife.xiaomi.carplay

import java.math.BigInteger
import java.net.ProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayPairingTest {
    @Test
    fun srpClientProofMatchesReceiverProofForTheCorrectPin() {
        val salt = ByteArray(16) { (it + 11).toByte() }
        val receiverPrivate = BigInteger("2934152037950412505327827")
        val pin = "5432"
        val group = CarPlaySrp.modulus
        val generator = CarPlaySrp.generator
        assertEquals(3072, group.bitLength())

        val identity = "Pair-Setup".toByteArray()
        val exponent = CarPlaySrp.number(PairingCrypto.hash(salt, PairingCrypto.hash("Pair-Setup:$pin".toByteArray())))
        val verifier = generator.modPow(exponent, group)
        val receiverPublic = (CarPlaySrp.multiplier * verifier + generator.modPow(receiverPrivate, group)).mod(group)
        val receiverPublicBytes = CarPlaySrp.pad(receiverPublic)
        val proof = CarPlaySrp.prove(pin, salt, receiverPublicBytes)

        val scramble = CarPlaySrp.number(PairingCrypto.hash(proof.publicKey, receiverPublicBytes))
        val clientPublic = CarPlaySrp.number(proof.publicKey)
        val shared = (clientPublic * verifier.modPow(scramble, group)).modPow(receiverPrivate, group)
        val receiverKey = PairingCrypto.hash(CarPlaySrp.unsigned(shared))
        val groupHash = PairingCrypto.hash(CarPlaySrp.unsigned(group))
        val generatorHash = PairingCrypto.hash(CarPlaySrp.unsigned(generator))
        val xorHash = ByteArray(64) { (groupHash[it].toInt() xor generatorHash[it].toInt()).toByte() }
        val expectedProof = PairingCrypto.hash(
            xorHash, PairingCrypto.hash(identity), salt, proof.publicKey, receiverPublicBytes, receiverKey,
        )

        assertArrayEquals(receiverKey, proof.key)
        assertArrayEquals(expectedProof, proof.clientProof)
        assertArrayEquals(PairingCrypto.hash(proof.publicKey, expectedProof, receiverKey), proof.serverProof)
        assertFalse(CarPlaySrp.prove("5433", salt, receiverPublicBytes).key.contentEquals(receiverKey))
        assertThrows(ProtocolException::class.java) { CarPlaySrp.prove(pin, salt, byteArrayOf(0)) }
    }

    @Test
    fun authenticatesTheEphemeralKeyAgainstThePairedReceiverIdentity() {
        val controller = PairingCrypto.ephemeralKey()
        val receiver = PairingCrypto.ephemeralKey()
        val controllerPublic = controller.generatePublicKey().encoded
        val receiverPublic = receiver.generatePublicKey().encoded
        val secret = PairingCrypto.sharedSecret(controller, receiverPublic)
        assertArrayEquals(secret, PairingCrypto.sharedSecret(receiver, controllerPublic))

        val signingIdentity = PairingCrypto.signingKey()
        val differentIdentity = PairingCrypto.signingKey()
        val transcript = receiverPublic + "receiver".toByteArray() + controllerPublic
        val signature = PairingCrypto.sign(signingIdentity, transcript)
        assertTrue(PairingCrypto.verify(signingIdentity.generatePublicKey().encoded, transcript, signature))
        assertFalse(PairingCrypto.verify(differentIdentity.generatePublicKey().encoded, transcript, signature))
        val key = PairingCrypto.derive(secret, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val sealed = PairingCrypto.seal(key, "PV-Msg02", signature)
        assertArrayEquals(signature, PairingCrypto.open(key, "PV-Msg02", sealed))
    }

    @Test
    fun reassemblesLongPairingFieldsAndRejectsDuplicateState() {
        val key = ByteArray(384) { it.toByte() }
        val valid = PairingTlv.encode(
            PairingTlv.STATE to byteArrayOf(2),
            PairingTlv.PUBLIC_KEY to key,
        )
        assertArrayEquals(key, PairingTlv.response(valid, 2)[PairingTlv.PUBLIC_KEY])
        assertThrows(ProtocolException::class.java) { PairingTlv.response(valid, 4) }
        assertThrows(ProtocolException::class.java) {
            PairingTlv.decode(valid + byteArrayOf(PairingTlv.STATE.toByte(), 1, 2))
        }
    }
}
