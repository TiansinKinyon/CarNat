package com.baidu.carlife.xiaomi.carplay

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import com.dd.plist.NSString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.ProtocolException
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VideoNegotiationTest {
    @Test
    fun hevcRequiresAnExplicitOfferAndAcceptance() {
        assertEquals(VideoCodec.HEVC, negotiate(true, reply(1, 200, enabledHevc())))
        assertEquals(VideoCodec.H264, negotiate(false, reply(1, 200, enabledHevc())))
        assertEquals(VideoCodec.H264, negotiate(true, reply(1, 200, NSDictionary())))
    }

    @Test
    fun retriesFormatRejectionButNeverAuthorizationFailure() {
        assertEquals(VideoCodec.H264, negotiate(true, reply(1, 415) + reply(2, 200, enabledHevc())))
        assertThrows(ProtocolException::class.java) { negotiate(true, reply(1, 403)) }
    }

    @Test
    fun hevcConfigurationAndScreenHeadersMatchTheWireFormat() {
        val sps = byteArrayOf(66, 1, 17, 1, 96, 0, 0, 3, 0, 0, 3, 0, 0, 3, 0, 0, 3, 0, 120, -96, 3, -64, -128, 17, 7, -53, -64)
        val config = HevcPayload.configuration(listOf(
            ByteBuffer.wrap(byteArrayOf(64, 1, 12)), ByteBuffer.wrap(sps),
            ByteBuffer.wrap(byteArrayOf(68, 1, -64)),
        ), 1920, 1080)
        assertEquals("hvc1", config.copyOfRange(4, 8).toString(Charsets.US_ASCII))
        assertEquals("hvcC", config.copyOfRange(90, 94).toString(Charsets.US_ASCII))
        assertEquals(3, config[116].toInt())
        val output = ByteArrayOutputStream()
        val writer = CarPlayVideoWriter(output, ByteArray(32))
        writer.writeConfiguration(config, 1920, 1080, VideoCodec.HEVC)
        writer.writeAccessUnit(byteArrayOf(0, 0, 0, 2, 38, 1), 1)
        val wire = output.toByteArray()
        assertArrayEquals(byteArrayOf(1, 0, 0x1e, 1), wire.copyOfRange(4, 8))
        assertArrayEquals(config, wire.copyOfRange(128, 128 + config.size))
        assertArrayEquals(byteArrayOf(0, 0x10, 0, 0), wire.copyOfRange(132 + config.size, 136 + config.size))
    }

    private fun enabledHevc() = NSDictionary().apply { put("enabledFeatures", NSArray(NSString("hevc"))) }

    private fun negotiate(offer: Boolean, responses: ByteArray): VideoCodec =
        RtspControl(ByteArrayInputStream(responses), ByteArrayOutputStream(), Closeable {}).use { control ->
            CarPlayScreenProbe.negotiateVideoCodec(control, "/setup", NSDictionary(), offer).second
        }

    private fun reply(seq: Int, status: Int, plist: NSDictionary? = null): ByteArray {
        val body = plist?.let(BinaryPropertyListWriter::writeToArray) ?: byteArrayOf()
        return "RTSP/1.0 $status Reply\r\nCSeq: $seq\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body
    }
}
