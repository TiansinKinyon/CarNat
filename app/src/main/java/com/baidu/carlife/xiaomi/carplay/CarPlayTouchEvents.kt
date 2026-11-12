package com.baidu.carlife.xiaomi.carplay

import android.util.Log
import com.baidu.carlife.service.CarlifeConnectService
import com.dd.plist.BinaryPropertyListParser
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSString
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.ProtocolException
import java.net.Socket

/** WinPlay sends touchscreen HID reports on the encrypted RTSP event connection. */
internal class CarPlayTouchEvents(
    private val socket: Socket,
    private val keys: CarPlaySessionKeys,
    private val control: RtspControl,
    private val width: Int,
    private val height: Int,
    private val certificate: java.security.cert.X509Certificate,
    private val onClosed: () -> Unit,
    private val onStage: (String) -> Unit,
) {
    private var pointer: Int? = null
    private var x = 0f
    private var y = 0f

    fun start() {
        Thread({
            try {
                receive()
                if (!socket.isClosed) Log.i("CarNatTouch", "Receiver closed the event channel")
            } catch (error: Exception) {
                if (!socket.isClosed) Log.w("CarNatTouch", "Event channel ended", error)
            } finally {
                if (pointer != null) CarlifeConnectService.remoteTouch(x, y, down = false, cancel = true)
                onClosed()
            }
        }, "CarPlay events").apply { isDaemon = true }.start()
    }

    private fun receive() {
        val input = DataInputStream(BufferedInputStream(CarPlayControlRecords.input(
            socket.getInputStream(), keys.eventRead(),
        )))
        val output = CarPlayControlRecords.output(socket.getOutputStream(), keys.eventWrite())
        val incomingIap = PipedInputStream(65_536)
        val iapWriter = PipedOutputStream(incomingIap)
        val outgoingIap = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()))
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                val payload = NSDictionary().apply {
                    put("type", "iAPSendMessage")
                    put("params", NSDictionary().apply {
                        put("data", NSData(bytes.copyOfRange(offset, offset + length)))
                    })
                }
                val body = BinaryPropertyListWriter.writeToArray(payload)
                val response = control.request(
                    "POST", "/command", mapOf("Content-Type" to "application/x-apple-binary-plist"), body,
                )
                if (response.status !in 200..299) throw ProtocolException("iAP2 command rejected: ${response.status}")
            }
        }
        Thread({
            try {
                incomingIap.use { Iap2LinkProbe.runNetwork(it, outgoingIap, certificate, onStage) }
            } catch (error: Exception) {
                if (!socket.isClosed && error !is java.io.EOFException) {
                    Log.w("CarNatIap2", "Network iAP2 ended", error)
                }
            }
        }, "CarPlay network iAP2").apply { isDaemon = true }.start()
        iapWriter.use { readCommands(input, output, it) }
    }

    private fun readCommands(input: DataInputStream, output: OutputStream, iap: OutputStream) {
        while (true) {
            val start = line(input) ?: return
            val fields = mutableMapOf<String, String>()
            for (index in 0..32) {
                val header = line(input) ?: throw ProtocolException("Truncated event header")
                if (header.isEmpty()) break
                val split = header.indexOf(':')
                if (index == 32 || split <= 0) throw ProtocolException("Invalid event header")
                val key = header.substring(0, split).lowercase(java.util.Locale.ROOT)
                if (fields.put(key, header.substring(split + 1).trim()) != null) {
                    throw ProtocolException("Duplicate event header")
                }
            }
            val length = fields["content-length"]?.let { it.toIntOrNull() ?: throw ProtocolException("Invalid event length") } ?: 0
            if (length !in 0..65_536) throw ProtocolException("Invalid event length")
            val body = ByteArray(length).also(input::readFully)
            if (start.startsWith("RTSP/1.0 ")) continue
            val seq = fields["cseq"]?.toIntOrNull() ?: throw ProtocolException("Missing event CSeq")
            if (start == "POST /command RTSP/1.0" && body.isNotEmpty()) {
                val command = BinaryPropertyListParser.parse(body) as? NSDictionary
                val type = (command?.objectForKey("type") as? NSString)?.content
                val uuid = (command?.objectForKey("uuid") as? NSString)?.content
                if (type == "hidSendReport" && uuid.equals("2a2a2a2a", ignoreCase = true) && width > 0 && height > 0) {
                    (command?.objectForKey("hidReport") as? NSData)?.bytes()?.let(::touch)
                } else if (type == "forceKeyFrame") {
                    CarlifeConnectService.requestKeyFrame()
                } else if (type == "iAPSendMessage") {
                    val params = command?.objectForKey("params") as? NSDictionary
                    (params?.objectForKey("data") as? NSData)?.bytes()?.let(iap::write)
                    iap.flush()
                }
            }
            synchronized(output) {
                output.write("RTSP/1.0 200 OK\r\nCSeq: $seq\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
            }
        }
    }

    private fun touch(report: ByteArray) {
        if (report.size != 12 || report[0] != 0.toByte() || report[6] != 1.toByte()) return
        val offset = pointer ?: listOf(0, 6).firstOrNull { report[it + 1].toInt() and 1 != 0 } ?: return
        val down = report[offset + 1].toInt() and 1 != 0
        fun coordinate(index: Int): Int = (report[index].toInt() and 255) or ((report[index + 1].toInt() and 255) shl 8)
        if (down || coordinate(offset + 2) != 0 || coordinate(offset + 4) != 0) {
            x = (coordinate(offset + 2).toFloat() / width).coerceIn(0f, 1f)
            y = (coordinate(offset + 4).toFloat() / height).coerceIn(0f, 1f)
        }
        pointer = if (down) offset else null
        CarlifeConnectService.remoteTouch(x, y, down)
    }

    private fun line(input: DataInputStream): String? {
        val line = StringBuilder()
        while (line.length < 4096) {
            when (val value = input.read()) {
                -1 -> if (line.isEmpty()) return null else throw IOException("Truncated event line")
                13 -> {
                    if (input.read() != 10) throw ProtocolException("Invalid event line ending")
                    return line.toString()
                }
                in 32..126 -> line.append(value.toChar())
                else -> throw ProtocolException("Invalid event header character")
            }
        }
        throw ProtocolException("Event line too long")
    }
}
