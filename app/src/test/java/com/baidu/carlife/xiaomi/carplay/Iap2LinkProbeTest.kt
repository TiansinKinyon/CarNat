package com.baidu.carlife.xiaomi.carplay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.ProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Iap2LinkProbeTest {
    private val marker = byteArrayOf(-1, 0x55, 2, 0, -18, 16)

    @Test
    fun requestsIdentificationAfterPeerAcknowledgesSyncAndReassemblesTheResponse() {
        val parameters = byteArrayOf(1, 4, 0x10, 0, 1, 0, 0, 0, 4, 1, 10, 0, 1)
        val request = byteArrayOf(0x40, 0x40, 0, 6, 0x1d, 0)
        val identity = byteArrayOf(0x40, 0x40, 0, 18, 0x1d, 1, 0, 12, 0, 0) +
            "WinPlay".toByteArray() + byteArrayOf(0)
        val input = marker + marker + packet(0x80, 255, 0, 0, parameters) +
            packet(0x40, 255, 99, 0, byteArrayOf()) +
            packet(0x40, 0, 100, 10, identity.copyOfRange(0, 8)) +
            packet(0x40, 1, 100, 10, identity.copyOfRange(8, identity.size))
        val output = ByteArrayOutputStream()
        val stages = mutableListOf<String>()

        assertThrows(EOFException::class.java) {
            Iap2LinkProbe.run(ByteArrayInputStream(input), output, stages::add)
        }

        assertArrayEquals(
            marker + packet(0xc0, 99, 255, 0, parameters) +
                packet(0x40, 100, 255, 10, request) +
                packet(0x40, 100, 0, 0, byteArrayOf()) + packet(0x40, 100, 1, 0, byteArrayOf()) +
                packet(0x40, 101, 1, 10, byteArrayOf(0x40, 0x40, 0, 6, 0x1d, 2)) +
                packet(0x40, 102, 1, 10, byteArrayOf(0x40, 0x40, 0, 6, 0xaa.toByte(), 0)),
            output.toByteArray(),
        )
        assertEquals(4, stages.size)
        assertEquals("已收到 iAP2 接收端身份：WinPlay", stages.last())
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
