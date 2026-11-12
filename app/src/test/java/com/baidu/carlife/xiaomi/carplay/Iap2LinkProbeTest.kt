package com.baidu.carlife.xiaomi.carplay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Iap2LinkProbeTest {
    private val marker = byteArrayOf(-1, 0x55, 2, 0, -18, 16)

    @Test
    fun respondsToDetectionOnceAndAcknowledgesValidControlData() {
        val parameters = byteArrayOf(1, 4, 0x10, 0, 1, 0, 0, 0, 4, 1, 0, 1)
        val control = byteArrayOf(0x40, 0x40, 0, 6, 0x4e, 0)
        val input = marker + marker + packet(0x80, 255, 0, 0, parameters) +
            packet(0x40, 0, 99, 1, control)
        val output = ByteArrayOutputStream()
        val stages = mutableListOf<String>()

        Iap2LinkProbe.run(ByteArrayInputStream(input), output, stages::add)

        assertArrayEquals(
            marker + packet(0xc0, 99, 255, 0, parameters) + packet(0x40, 99, 0, 0, byteArrayOf()),
            output.toByteArray(),
        )
        assertEquals(3, stages.size)
        assertEquals("已收到 iAP2 控制帧 0x4e00", stages.last())
    }

    @Test
    fun rejectsACorruptedPacket() {
        val input = packet(0x80, 5, 0, 0, ByteArray(12)).apply { this[8] = 0 }
        assertThrows(ProtocolException::class.java) {
            Iap2LinkProbe.run(ByteArrayInputStream(input), ByteArrayOutputStream()) {}
        }
    }

    private fun packet(flags: Int, sequence: Int, ack: Int, session: Int, payload: ByteArray): ByteArray {
        val length = if (payload.isEmpty()) 9 else 10 + payload.size
        val header = byteArrayOf(-1, 0x5a, (length shr 8).toByte(), length.toByte(),
            flags.toByte(), sequence.toByte(), ack.toByte(), session.toByte())
        val headerChecksum = (-header.sumOf { it.toInt() }).toByte()
        return if (payload.isEmpty()) header + headerChecksum else
            header + headerChecksum + payload + (-payload.sumOf { it.toInt() }).toByte()
    }
}
