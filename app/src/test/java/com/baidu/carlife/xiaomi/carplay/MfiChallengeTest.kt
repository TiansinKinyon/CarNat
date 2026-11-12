package com.baidu.carlife.xiaomi.carplay

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MfiChallengeTest {
    @Test
    fun verifiesRawWinPlaySignatureAndRejectsADifferentChallengeOrKey() {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val key = generator.generateKeyPair()
        val other = generator.generateKeyPair()
        val challenge = ByteArray(32) { (it * 3).toByte() }
        val signature = Signature.getInstance("NONEwithECDSAinP1363Format").run {
            initSign(key.private)
            update(challenge)
            sign()
        }

        assertTrue(MfiChallenge.verify(key.public, challenge, signature))
        assertFalse(MfiChallenge.verify(other.public, challenge, signature))
        val changed = challenge.copyOf().apply { this[0] = 1 }
        assertFalse(MfiChallenge.verify(key.public, changed, signature))
    }
}
