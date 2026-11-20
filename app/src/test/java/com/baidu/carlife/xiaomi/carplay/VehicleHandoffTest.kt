package com.baidu.carlife.xiaomi.carplay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.ProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleHandoffTest {
    private val readKey = ByteArray(32) { 1 }
    private val writeKey = ByteArray(32) { 2 }

    @Test
    fun mguAclRetentionRequiresReadyTransportAndFreshSuccessfulFeedback() {
        var now = 100L
        val health = MguWifiHealth { now }

        health.feedback(200)
        assertFalse(health.canKeep())
        health.ready()
        assertTrue(health.canKeep())
        now += 5_000
        assertFalse(health.canKeep())
        health.feedback(200)
        assertTrue(health.canKeep())
        health.feedback(500)
        assertFalse(health.canKeep())
        assertFalse(MguWifiHealth { now }.canKeep())
    }

    @Test
    fun deferredHandoffAckAndInboundIapCommandDoNotInterruptTheControlResponse() {
        val source = ByteArrayOutputStream()
        CarPlayControlRecords.output(source, readKey).apply {
            write(("POST /command RTSP/1.0\r\nCSeq: 55\r\nContent-Length: 3\r\n\r\niap" +
                "RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n" +
                "RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Length: 0\r\n\r\n").toByteArray())
            flush()
        }
        val sent = ByteArrayOutputStream()
        val control = RtspControl(ByteArrayInputStream(source.toByteArray()), sent, Closeable {})
        control.enableEncryption(readKey, writeKey)
        val received = mutableListOf<String>()
        control.onPeerCommand = { received.add(it.toString(Charsets.US_ASCII)); true }
        val handoff = MguBluetoothHandoff(control, "00:11:22:33:44:55")
        handoff.request()
        assertEquals(200, control.request("OPTIONS", "*").status)
        assertEquals(listOf("iap"), received)
        handoff.request()
        val decoded = CarPlayControlRecords.input(ByteArrayInputStream(sent.toByteArray()), writeKey)
            .readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(decoded.contains("RTSP/1.0 200 OK\r\nCSeq: 55"))
        assertEquals(1, Regex("POST /command RTSP/1.0").findAll(decoded).count())
    }

    @Test
    fun sharedWifiIngressPreservesOrderAndRejectsBacklogBeyondItsBudget() {
        val input = NetworkIapInput(4)
        assertTrue(input.offer(byteArrayOf(1, 2)))
        assertTrue(input.offer(byteArrayOf(3, 4)))
        assertFalse(input.offer(byteArrayOf(5)))
        assertEquals(1, input.read())
        assertTrue(input.offer(byteArrayOf(5)))
        assertEquals(2, input.read())
        assertEquals(3, input.read())
        assertEquals(4, input.read())
        assertEquals(5, input.read())
        input.close()
        assertEquals(-1, input.read())
        assertFalse(input.offer(byteArrayOf(6)))
    }

    @Test
    fun wifiOnlyRecoveryNeverStartsUnpairedSetup() {
        assertThrows(ProtocolException::class.java) {
            CarPlayReceiverProbe.verify(
                "", 7000, "3939", protocol = ReceiverProtocol.VOLKSWAGEN,
                requireSavedPairing = true,
            ) {}
        }
    }
}
