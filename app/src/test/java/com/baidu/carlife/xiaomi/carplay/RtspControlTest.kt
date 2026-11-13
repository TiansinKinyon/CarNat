package com.baidu.carlife.xiaomi.carplay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.ProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspControlTest {
    @Test
    fun preservesBinaryBodiesAndTheNextResponseWhenOneReadReturnsBoth() {
        val plist = byteArrayOf(0, 13, 10, -1)
        val first = "RTSP/1.0 200 OK\r\ncSeQ: 1\r\nContent-Length: 4\r\n\r\n"
        val second = "RTSP/1.0 403 Forbidden\r\nCSeq: 2\r\n\r\n"
        val output = ByteArrayOutputStream()
        val control = RtspControl(
            ByteArrayInputStream(first.toByteArray() + plist + second.toByteArray()),
            output,
            Closeable {},
        )

        assertArrayEquals(plist, control.request("GET", "/info").body)
        val reply = control.request("SETUP", "/", mapOf("Content-Type" to "application/x-apple-binary-plist"), plist)
        assertEquals(403, reply.status)
        assertEquals("Forbidden", reply.reason)
        assertEquals(0, reply.body.size)
        assertArrayEquals(
            ("GET /info RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n" +
                "SETUP / RTSP/1.0\r\nCSeq: 2\r\nContent-Type: application/x-apple-binary-plist\r\n" +
                "Content-Length: 4\r\n\r\n").toByteArray() + plist,
            output.toByteArray(),
        )
    }

    @Test
    fun closesOnInvalidResponseButRejectsCallerHeadersBeforeWriting() {
        var closed = false
        val output = ByteArrayOutputStream()
        val input = ByteArrayInputStream("RTSP/1.0 200 OK\r\nCSeq: 9\r\n\r\n".toByteArray())
        val control = RtspControl(input, output, Closeable { closed = true })
        assertThrows(IllegalArgumentException::class.java) {
            control.request("GET", "/info", mapOf("X-Name" to "test\r\nCSeq: 1"))
        }
        assertEquals(0, output.size())
        assertThrows(ProtocolException::class.java) { control.request("GET", "/info") }
        assertTrue(closed)
        assertThrows(java.io.IOException::class.java) { control.request("GET", "/info") }
    }
}
