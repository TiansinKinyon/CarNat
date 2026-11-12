package com.baidu.carlife.xiaomi.carplay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CarPlayControlRecordsTest {
    private val readKey = ByteArray(32) { it.toByte() }
    private val writeKey = ByteArray(32) { (it + 64).toByte() }

    @Test
    fun negotiatesRtspOverSeparateDirectionalKeysAndMultipleRecords() {
        val responseBody = ByteArray(1800) { (it * 3).toByte() }
        val requestBody = ByteArray(1800) { (it * 7).toByte() }
        val plainResponse = "RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n"
        val responseHeader = "RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Length: 1800\r\n\r\n"
        val outbound = ByteArrayOutputStream()
        val response = ByteArrayOutputStream().also {
            CarPlayControlRecords.output(it, readKey).apply {
                write(responseHeader.toByteArray())
                write(responseBody)
                flush()
            }
        }.toByteArray()

        val firstLength = (response[0].toInt() and 255) + ((response[1].toInt() and 255) shl 8)
        assertEquals(responseHeader.length, firstLength)
        val secondRecord = firstLength + 18
        assertEquals(1422, (response[secondRecord].toInt() and 255) +
            ((response[secondRecord + 1].toInt() and 255) shl 8))
        val control = RtspControl(ByteArrayInputStream(plainResponse.toByteArray() + response), outbound, Closeable {})
        assertEquals(200, control.request("POST", "/pair-verify").status)
        val plainRequestSize = outbound.size()
        control.enableEncryption(readKey, writeKey)
        assertThrows(IllegalStateException::class.java) { control.enableEncryption(readKey, writeKey) }
        assertArrayEquals(responseBody, control.request("SETUP", "/", body = requestBody).body)

        val securedRequest = outbound.toByteArray().copyOfRange(plainRequestSize, outbound.size())
        val decoded = CarPlayControlRecords.input(ByteArrayInputStream(securedRequest), writeKey).readBytes()
        val requestHeader = "SETUP / RTSP/1.0\r\nCSeq: 2\r\nContent-Length: 1800\r\n\r\n"
        assertArrayEquals(requestHeader.toByteArray() + requestBody, decoded)
    }

    @Test
    fun rejectsAlteredAndTruncatedRecordsWithoutReturningPlaintext() {
        val wire = ByteArrayOutputStream().also {
            CarPlayControlRecords.output(it, readKey).apply {
                write(byteArrayOf(4, 5, 6))
                flush()
            }
        }.toByteArray()

        val corrupted = wire.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        assertThrows(IOException::class.java) {
            CarPlayControlRecords.input(ByteArrayInputStream(corrupted), readKey).read()
        }
        assertThrows(EOFException::class.java) {
            CarPlayControlRecords.input(ByteArrayInputStream(wire.copyOf(wire.size - 1)), readKey).read()
        }
    }
}
