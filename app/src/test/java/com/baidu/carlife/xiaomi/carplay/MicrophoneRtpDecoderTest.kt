package com.baidu.carlife.xiaomi.carplay

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicrophoneRtpDecoderTest {
    @Test
    fun decodesPcmAndRejectsTamperingAndReplay() {
        val key = ByteArray(32) { it.toByte() }
        val header = byteArrayOf(0x80.toByte(), 100, 0, 2, 0, 0, 1, 2, 0, 0, 0, 0)
        val nonce = ByteArray(12).apply { this[4] = 2 }
        val sealed = Cipher.getInstance("ChaCha20-Poly1305").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            updateAAD(header, 4, 8)
            doFinal(byteArrayOf(0x12, 0x34, 0x56, 0x78))
        }
        val packet = header + sealed + nonce.copyOfRange(4, 12)
        val decoder = MicrophoneRtpDecoder(key)
        val tampered = packet.copyOf().apply { this[12] = (this[12].toInt() xor 1).toByte() }
        assertNull(decoder.decode(tampered, tampered.size))
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0x78, 0x56), decoder.decode(packet, packet.size))
        assertNull(decoder.decode(packet, packet.size))
    }

    @Test
    fun decodesAcrossRtpSequenceWrapAndNonceCarry() {
        val key = ByteArray(32)
        val decoder = MicrophoneRtpDecoder(key)
        for (counter in 0xffff_fffcL..0x1_0000_0004L) {
            val header = ByteBuffer.allocate(12)
                .put(0x80.toByte()).put(100.toByte()).putShort(counter.toShort())
                .putInt((counter * 320).toInt()).putInt(0).array()
            val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(4, counter).array()
            val sealed = Cipher.getInstance("ChaCha20-Poly1305").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
                updateAAD(header, 4, 8)
                doFinal(byteArrayOf(0x12, 0x34))
            }
            val packet = header + sealed + nonce.copyOfRange(4, 12)
            assertArrayEquals(byteArrayOf(0x34, 0x12), decoder.decode(packet, packet.size))
        }
    }
}
