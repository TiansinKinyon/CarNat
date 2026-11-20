package com.baidu.carlife.xiaomi.carplay

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSDictionary
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.ProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadUnitProtocolTest {
    @Test
    fun bmwUsesOnlyTheMguProfile() {
        assertEquals(ReceiverProtocol.BMW_MGU, HeadUnitProfile.BMW.protocol)
        assertEquals(ReceiverProtocol.GENERIC, HeadUnitProfile.GENERIC.protocol)
        assertEquals(ReceiverProtocol.VOLKSWAGEN, HeadUnitProfile.VOLKSWAGEN.protocol)
    }

    @Test
    fun onlyBmwRetriesInfoWithPostAfterGetRejection() {
        val data = BinaryPropertyListWriter.writeToArray(NSDictionary())
        val responses = reply(1, 455) + reply(2, 200, data)
        val genericOutput = ByteArrayOutputStream()
        assertThrows(ProtocolException::class.java) {
            CarPlayReceiverProbe.readInfo(control(responses, genericOutput), ReceiverProtocol.GENERIC)
        }
        assertEquals(1, Regex("RTSP/1.0").findAll(genericOutput.toString(Charsets.US_ASCII)).count())

        val output = ByteArrayOutputStream()
        assertArrayEquals(data, CarPlayReceiverProbe.readInfo(control(responses, output), ReceiverProtocol.BMW_MGU))
        assertTrue(output.toString(Charsets.US_ASCII).contains("POST /info RTSP/1.0"))
    }

    @Test
    fun bmwCanDeferInfoAndRejectsAnExplicitResourceFailure() {
        val output = ByteArrayOutputStream()
        val control = control(reply(1, 455) + reply(2, 455), output)
        assertNull(CarPlayReceiverProbe.readInfo(control, ReceiverProtocol.BMW_MGU, required = false))

        val body = BinaryPropertyListWriter.writeToArray(NSDictionary().apply { put("status", 1) })
        val rejected = control(reply(1, 200, body), ByteArrayOutputStream()).request("POST", "/command")
        assertThrows(ProtocolException::class.java) {
            VehicleScreenResources.requireAcknowledgement("资源声明", rejected)
        }
    }

    @Test
    fun volkswagenKeepsItsOwnProtocolAndAcceptsOnlyConnectInvitations() {
        assertFalse(ReceiverProtocol.VOLKSWAGEN.isBmw)
        assertTrue(CarPlayControlInvitation.isConnectRequest("GET /ctrl-int/1/connect?version=1 HTTP/1.1"))
        assertFalse(CarPlayControlInvitation.isConnectRequest("POST /ctrl-int/1/connect HTTP/1.1"))
        assertFalse(CarPlayControlInvitation.isConnectRequest("GET /ctrl-int/1/connect-extra HTTP/1.1"))
        assertFalse(CarPlayControlInvitation.isConnectRequest("GET /ctrl-int/1/connect RTSP/1.0"))
    }

    @Test
    fun desktopIdentityOverridesBmwButKeepsMguAndVolkswagenPolicies() {
        assertEquals(ReceiverProtocol.GENERIC, ReceiverProtocol.BMW_MGU.forReceiver("DiPlay 0.1.0"))
        assertEquals(ReceiverProtocol.GENERIC, ReceiverProtocol.BMW_MGU.forReceiver("Receiver", null, "WINPLAY"))
        assertEquals(ReceiverProtocol.BMW_MGU, ReceiverProtocol.BMW_MGU.forReceiver("MGU-55-BMW", "BMW Group"))
        assertEquals(ReceiverProtocol.VOLKSWAGEN, ReceiverProtocol.VOLKSWAGEN.forReceiver("VW4131"))
    }

    private fun reply(seq: Int, status: Int, body: ByteArray = byteArrayOf()): ByteArray =
        "RTSP/1.0 $status Reply\r\nCSeq: $seq\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body

    private fun control(bytes: ByteArray, output: ByteArrayOutputStream) =
        RtspControl(ByteArrayInputStream(bytes), output, Closeable {})
}
