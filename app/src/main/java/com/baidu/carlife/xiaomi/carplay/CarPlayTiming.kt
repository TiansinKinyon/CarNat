package com.baidu.carlife.xiaomi.carplay

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

internal class CarPlayTiming(host: String) : Closeable {
    private val peer = InetAddress.getByName(host)
    private val socket = DatagramSocket()
    val port: Int get() = socket.localPort

    init {
        Thread(::reply, "CarPlay timing").apply { isDaemon = true }.start()
    }

    private fun reply() {
        val request = DatagramPacket(ByteArray(33), 33)
        var first = true
        try {
            while (!socket.isClosed) {
                request.length = request.data.size
                socket.receive(request)
                val received = now()
                val data = request.data
                if (request.address != peer || request.length != 32 || data[0].toInt() and 0xc0 != 0x80 ||
                    data[1] != 0xd2.toByte() || data[2] != 0.toByte() || data[3] != 7.toByte()
                ) continue
                val response = ByteBuffer.allocate(32)
                    .put(0x80.toByte()).put(0xd3.toByte()).putShort(7).putInt(0)
                    .putLong(ByteBuffer.wrap(data).getLong(24)).putLong(received).putLong(now()).array()
                socket.send(DatagramPacket(response, response.size, request.address, request.port))
                if (first) {
                    first = false
                    Log.i("CarNatIap2", "已回应接收端媒体时钟同步")
                }
            }
        } catch (failure: IOException) {
            if (!socket.isClosed) Log.w("CarNatIap2", "媒体时钟连接结束", failure)
        }
    }

    override fun close() = socket.close()

    companion object {
        private val epochOffset = System.currentTimeMillis() * 1_000_000 - System.nanoTime()

        fun now(): Long {
            val nanos = System.nanoTime() + epochOffset
            return ((nanos / 1_000_000_000 + 2_208_988_800L) shl 32) or
                (((nanos % 1_000_000_000) shl 32) / 1_000_000_000)
        }
    }
}
