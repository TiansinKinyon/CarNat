package com.baidu.carlife.xiaomi.carplay

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AvcPayloadTest {
    @Test
    fun buildsConfigurationFromAndroidCsdAndAcceptsExistingAvcC() {
        val sps = byteArrayOf(0x67, 0x64, 0, 0x1f, 0x11)
        val pps = byteArrayOf(0x68, 0x22)
        val expected = byteArrayOf(1, 0x64, 0, 0x1f, -1, -31, 0, 5) +
            sps + byteArrayOf(1, 0, 2) + pps

        assertArrayEquals(expected, AvcPayload.configuration(
            ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sps),
            ByteBuffer.wrap(byteArrayOf(0, 0, 1) + pps),
        ))
        assertArrayEquals(expected, AvcPayload.configuration(ByteBuffer.wrap(expected), null))
    }

    @Test
    fun convertsMixedStartCodesWithoutMovingTheCodecBuffer() {
        val input = ByteBuffer.wrap(byteArrayOf(9, 0, 0, 0, 1, 0x65, 2, 0, 0, 1, 0x06, 3))
        input.position(1)
        val expected = byteArrayOf(0, 0, 0, 2, 0x65, 2, 0, 0, 0, 2, 0x06, 3)

        assertArrayEquals(expected, AvcPayload.accessUnit(input))
        assertArrayEquals(expected, AvcPayload.accessUnit(ByteBuffer.wrap(expected)))
        assertEquals(1, input.position())
        assertThrows(IllegalArgumentException::class.java) {
            AvcPayload.accessUnit(ByteBuffer.wrap(byteArrayOf(0, 0, 0, 5, 0x65)))
        }
    }
}
