package com.baidu.carlife.xiaomi.carplay

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleScreenResourcesTest {
    @Test
    fun borrowAndReturnCloseOnlyVideoAndRejectTheOldGrant() {
        for (protocol in listOf(ReceiverProtocol.BMW_MGU, ReceiverProtocol.VOLKSWAGEN)) {
            val state = VehicleScreenResources(NSDictionary(), protocol)
            val before = state.snapshot()
            Socket().use { video ->
                Socket().use { control ->
                    assertTrue(state.install(before, video))
                    state.onCommand(change(3, priority = 100, unborrow = 500))?.afterReply?.invoke()
                    assertTrue(video.isClosed)
                    assertFalse(control.isClosed)
                    assertFalse(state.snapshot().owned)
                    state.onCommand(change(4))?.afterReply?.invoke()
                    assertTrue(state.snapshot().owned)
                    assertFalse(state.allows(before))
                    Socket().use { rebuilt ->
                        assertFalse(state.install(before, rebuilt))
                        assertTrue(state.install(state.snapshot(), rebuilt))
                    }
                }
            }
        }
    }

    @Test
    fun requestUiDoesNotInvalidateAnExistingGrantAndRespectsNever() {
        val state = VehicleScreenResources(NSDictionary(), ReceiverProtocol.BMW_MGU)
        val granted = state.snapshot()
        state.onCommand(requestUi())
        assertEquals(granted.grant, state.snapshot().grant)
        state.onCommand(change(1, priority = 100, take = 1000, borrow = 100))?.afterReply?.invoke()
        state.onCommand(requestUi())
        assertFalse(state.snapshot().owned)
        val returned = requireNotNull(state.onCommand(change(2)))
        assertFalse(state.snapshot().owned)
        val params = returned.body.objectForKey("params") as NSDictionary
        val released = (params.objectForKey("resources") as NSArray).array.first() as NSDictionary
        assertEquals("0", released.objectForKey("entity").toString())
        returned.afterReply()
        assertTrue(state.snapshot().owned)
    }

    @Test
    fun rejectsMalformedTransferWithoutInvalidatingTheGrant() {
        val state = VehicleScreenResources(NSDictionary(), ReceiverProtocol.VOLKSWAGEN)
        val prior = state.snapshot()
        state.onCommand(change(1, priority = 100, take = 999, borrow = 100))?.afterReply?.invoke()
        assertEquals(prior, state.snapshot())
    }

    @Test
    fun rebuildsVideoOnTheExistingControlSessionWithoutSendingRecordAgain() {
        val host = InetAddress.getLoopbackAddress()
        ServerSocket(0, 4, host).use { data ->
            val setup = NSDictionary().apply {
                put("streams", NSArray(NSDictionary().apply {
                    put("type", 110)
                    put("dataPort", data.localPort)
                }))
            }
            val replies = (1..7).fold(byteArrayOf()) { all, seq ->
                val body = if (seq == 2 || seq == 6) BinaryPropertyListWriter.writeToArray(setup) else byteArrayOf()
                all + "RTSP/1.0 200 OK\r\nCSeq: $seq\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body
            }
            val output = ByteArrayOutputStream()
            var running = true
            var started = 0
            val keys = mutableListOf<ByteArray>()
            val resources = VehicleScreenResources(NSDictionary(), ReceiverProtocol.BMW_MGU)
            Socket().use { events ->
                CarPlaySockets().use { sockets ->
                    RtspControl(ByteArrayInputStream(replies), output, Closeable {}).use { control ->
                        CarPlayScreenProbe.runVehicleScreen(control, sockets, events, host, "main", "/",
                            ReceiverProtocol.BMW_MGU, CarPlaySessionKeys(ByteArray(32)), VideoCodec.H264, resources,
                            onStreamStarted = { started++ },
                            onStage = {
                                if (it.contains("等待车机恢复主屏")) resources.onCommand(change(4))?.afterReply?.invoke()
                            },
                            keepRunning = { running },
                            pump = { video, key, _, firstFrame, _, _ ->
                                keys.add(key)
                                firstFrame()
                                if (keys.size == 1) {
                                    resources.onCommand(change(3, priority = 100, unborrow = 500))?.afterReply?.invoke()
                                    assertTrue(video.isClosed)
                                    assertFalse(events.isClosed)
                                } else running = false
                                "done"
                            },
                        )
                    }
                }
            }
            assertEquals(1, started)
            assertEquals(2, keys.size)
            assertFalse(keys[0].contentEquals(keys[1]))
            val bytes = output.toByteArray()
            val wire = bytes.toString(Charsets.ISO_8859_1)
            val requests = Regex("(POST|SETUP|TEARDOWN|RECORD) [^ ]+ RTSP/1.0")
                .findAll(wire).toList()
            assertEquals(listOf("POST", "SETUP", "TEARDOWN", "POST", "POST", "SETUP", "TEARDOWN"),
                requests.map { it.groupValues[1] })
            for (request in requests.filter { it.groupValues[1] == "TEARDOWN" }) {
                val end = wire.indexOf("\r\n\r\n", request.range.first)
                val header = wire.substring(request.range.first, end)
                val size = Regex("Content-Length: ([0-9]+)").find(header)!!.groupValues[1].toInt()
                val body = CarPlayPlist.readDictionary(bytes.copyOfRange(end + 4, end + 4 + size))
                val streams = body.objectForKey("streams") as NSArray
                assertEquals(1, streams.array.size)
                val onlyVideo = streams.array.single() as NSDictionary
                assertEquals("110", onlyVideo.objectForKey("type").toString())
                assertEquals("main", onlyVideo.objectForKey("uuid").toString())
            }
        }
    }

    private fun requestUi() = NSDictionary().apply { put("type", "requestUI") }

    private fun change(
        type: Int, priority: Int? = null, take: Int? = null, borrow: Int? = null, unborrow: Int? = null,
    ) = NSDictionary().apply {
        put("type", "changeModes")
        put("params", NSDictionary().apply {
            put("resources", NSArray(NSDictionary().apply {
                put("resourceID", 1)
                put("transferType", type)
                priority?.let { put("transferPriority", it) }
                take?.let { put("takeConstraint", it) }
                borrow?.let { put("borrowConstraint", it) }
                unborrow?.let { put("unborrowConstraint", it) }
            }))
        })
    }
}
