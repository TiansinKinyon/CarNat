package com.baidu.carlife.xiaomi.carplay

import java.security.PublicKey
import java.security.Signature

internal object MfiChallenge {
    /** WinPlay's MFi 3 signer signs the 32-byte challenge directly as a P-256 digest. */
    fun verify(publicKey: PublicKey, challenge: ByteArray, compactSignature: ByteArray): Boolean {
        if (publicKey.algorithm != "EC" || challenge.size != 32 || compactSignature.size != 64) return false
        val r = derInteger(compactSignature, 0)
        val s = derInteger(compactSignature, 32)
        val der = byteArrayOf(0x30, (r.size + s.size).toByte()) + r + s
        return Signature.getInstance("NONEwithECDSA").run {
            initVerify(publicKey)
            update(challenge)
            verify(der)
        }
    }

    private fun derInteger(signature: ByteArray, offset: Int): ByteArray {
        val end = offset + 32
        var start = offset
        while (start < end - 1 && signature[start] == 0.toByte()) start++
        val bytes = signature.copyOfRange(start, end)
        val signed = if (bytes[0] < 0) byteArrayOf(0) + bytes else bytes
        return byteArrayOf(2, signed.size.toByte()) + signed
    }
}
