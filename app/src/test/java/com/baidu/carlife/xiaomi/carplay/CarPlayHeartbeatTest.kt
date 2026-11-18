package com.baidu.carlife.xiaomi.carplay

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayHeartbeatTest {
    @Test
    fun sendsUdpPingAndRtspFeedbackWhenReceiverAdvertisesKeepAlive() {
        val peer = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, peer).use { receiver ->
            receiver.soTimeout = 3_000
            val response = "RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n"
            val requests = ByteArrayOutputStream()
            CarPlaySockets().use { sockets ->
                sockets.open().use { video ->
                    RtspControl(response.byteInputStream(), requests, Socket()).use { control ->
                        val worker = CarPlayScreenProbe.startHeartbeat(control, sockets, video, peer, receiver.localPort)
                        try {
                            val ping = DatagramPacket(ByteArray(16), 16)
                            receiver.receive(ping)
                            assertEquals(4, ping.length)
                            val deadline = System.nanoTime() + 1_000_000_000L
                            while (requests.size() == 0 && System.nanoTime() - deadline < 0) {
                                Thread.sleep(5)
                            }
                            assertTrue(requests.toString(Charsets.US_ASCII.name()).startsWith("POST /feedback RTSP/1.0\r\n"))
                        } finally {
                            worker.interrupt()
                            worker.join(3_000)
                            assertFalse(worker.isAlive)
                        }
                    }
                }
            }
        }
    }
}
