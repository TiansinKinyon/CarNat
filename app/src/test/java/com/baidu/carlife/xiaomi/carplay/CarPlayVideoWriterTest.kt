package com.baidu.carlife.xiaomi.carplay

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CarPlayVideoWriterTest {
    @Test
    fun writesConfigurationAndAuthenticatesFramesWithIncrementingNonces() {
        val key = ByteArray(32) { it.toByte() }
        val output = ByteArrayOutputStream()
        val writer = CarPlayVideoWriter(output, key)
        val configuration = byteArrayOf(1, 66, 0, 31)
        val accessUnit = byteArrayOf(0, 0, 0, 2, 0x65, 0x10)

        assertThrows(IllegalStateException::class.java) { writer.writeAccessUnit(accessUnit, 1) }
        writer.writeConfiguration(configuration, 1280, 720)
        writer.writeAccessUnit(accessUnit, 0x0102030405060708)
        writer.writeAccessUnit(accessUnit, 0x0102030405060709)

        val wire = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        val configHeader = ByteBuffer.wrap(wire.take(128)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(12, configHeader.getInt(0))
        assertEquals(1, configHeader.get(4).toInt())
        assertEquals(4, configHeader.get(6).toInt())
        assertEquals(1280f, configHeader.getFloat(16), 0f)
        assertEquals(720f, configHeader.getFloat(20), 0f)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 12, 0x61, 0x76, 0x63, 0x43) + configuration,
            wire.take(12),
        )

        for (counter in 0L..1L) {
            val header = wire.take(128)
            val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(accessUnit.size + 16, fields.getInt(0))
            assertEquals(0, fields.get(4).toInt())
            assertEquals(0x0102030405060708 + counter, fields.getLong(8))
            val payload = wire.take(fields.getInt(0))
            assertArrayEquals(accessUnit, decrypt(key, counter, header, payload))

            header[8] = 0
            assertThrows(AEADBadTagException::class.java) { decrypt(key, counter, header, payload) }
        }
        assertEquals(0, wire.remaining())
    }

    private fun decrypt(key: ByteArray, counter: Long, header: ByteArray, payload: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(4, counter).array()
        return Cipher.getInstance("ChaCha20-Poly1305").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            updateAAD(header)
            doFinal(payload)
        }
    }

    private fun ByteBuffer.take(size: Int): ByteArray = ByteArray(size).also { get(it) }
}
