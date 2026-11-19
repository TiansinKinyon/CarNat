package com.baidu.carlife.xiaomi.carplay

import android.util.Log
import android.view.KeyEvent
import com.baidu.carlife.service.CarlifeConnectService
import com.baidu.carlife.service.RemoteContact
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
    private val onCommand: ((NSDictionary) -> Unit)? = null,
    private val protocol: ReceiverProtocol = ReceiverProtocol.GENERIC,
) {
    private var contacts = emptyList<RemoteContact>()
    private var knobButtons = 0

    fun start() {
        synchronized(inputOwnerLock) {
            if (inputOwner != null) CarlifeConnectService.remoteTouch(emptyList(), cancel = true)
            inputOwner = this
        }
        Thread({
            try {
                receive()
                if (!socket.isClosed) Log.i("CarNatTouch", "Receiver closed the event channel")
            } catch (error: Exception) {
                if (!socket.isClosed) Log.w("CarNatTouch", "Event channel ended", error)
            } finally {
                synchronized(inputOwnerLock) {
                    if (inputOwner === this) {
                        inputOwner = null
                        CarlifeConnectService.remoteTouch(emptyList(), cancel = true)
                    }
                }
                onClosed()
            }
        }, "CarPlay events").apply { isDaemon = true }.start()
    }

    private fun receive() {
        val input = DataInputStream(BufferedInputStream(CarPlayControlRecords.input(
            socket.getInputStream(), keys.eventRead(),
        )))
        val output = CarPlayControlRecords.output(socket.getOutputStream(), keys.eventWrite())
        val incomingIap = PipedInputStream(IAP_BUFFER_BYTES)
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
        iapWriter.use {
            readCommands(input, output) { bytes ->
                // Only this thread writes to the pipe. Its reader can only free capacity.
                // A blocked iAP2 consumer must never block event replies and touch dispatch.
                if (bytes.size > IAP_BUFFER_BYTES - incomingIap.available()) {
                    throw ProtocolException("Network iAP2 event backlog exceeds the size limit")
                }
                it.write(bytes)
                it.flush()
            }
        }
    }

    private fun readCommands(input: DataInputStream, output: OutputStream, forwardIap: (ByteArray) -> Unit) {
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
            if (start.startsWith("RTSP/1.0 ") || (protocol.isBmw &&
                    (start.startsWith("HTTP/1.0 ") || start.startsWith("HTTP/1.1 ")))
            ) continue
            val version = start.substringAfterLast(' ')
            val isHttp = protocol.isBmw && (version == "HTTP/1.0" || version == "HTTP/1.1")
            val seq = fields["cseq"]?.let {
                it.toIntOrNull() ?: throw ProtocolException("Invalid event CSeq")
            }
            if (seq == null && !isHttp) throw ProtocolException("Missing event CSeq")
            val command = if ((start == "POST /command RTSP/1.0" ||
                    (isHttp && start == "POST /command $version")) && body.isNotEmpty()
            ) {
                CarPlayPlist.readDictionary(body)
            } else null
            if (command != null) {
                val type = (command.objectForKey("type") as? NSString)?.content
                val uuid = (command.objectForKey("uuid") as? NSString)?.content
                if (type == "hidSendReport" && uuid.equals("2a2a2a2a", ignoreCase = true) && width > 0 && height > 0) {
                    (command.objectForKey("hidReport") as? NSData)?.bytes()?.let { report ->
                        dispatchInput { touch(report) }
                    }
                } else if (type == "hidSendReport" && uuid.equals("2a2a2a2b", ignoreCase = true)) {
                    (command.objectForKey("hidReport") as? NSData)?.bytes()?.let { report ->
                        dispatchInput { knob(report) }
                    }
                } else if (type == "forceKeyFrame") {
                    dispatchInput { CarlifeConnectService.requestKeyFrame() }
                } else if (type == "iAPSendMessage") {
                    val params = command.objectForKey("params") as? NSDictionary
                    (params?.objectForKey("data") as? NSData)?.bytes()?.let(forwardIap)
                }
            }
            synchronized(output) {
                val response = buildString {
                    append(if (isHttp) version else "RTSP/1.0")
                    append(" 200 OK\r\n")
                    if (seq != null) append("CSeq: $seq\r\n")
                    append("Content-Length: 0\r\n\r\n")
                }
                output.write(response.toByteArray(Charsets.US_ASCII))
                output.flush()
            }
            if (command != null) onCommand?.invoke(command)
        }
    }

    private inline fun dispatchInput(dispatch: () -> Unit) {
        synchronized(inputOwnerLock) {
            if (inputOwner === this && !socket.isClosed) dispatch()
        }
    }

    private fun touch(report: ByteArray) {
        if (report.size != 12 || report[0] != 0.toByte() || report[6] != 1.toByte()) return
        fun coordinate(index: Int) = (report[index].toInt() and 255) or ((report[index + 1].toInt() and 255) shl 8)
        contacts = listOf(0, 6).mapNotNull { offset ->
            val id = offset / 6
            val down = report[offset + 1].toInt() and 1 != 0
            val previous = contacts.firstOrNull { it.id == id && it.down }
            if (!down && previous == null) return@mapNotNull null
            val x = coordinate(offset + 2)
            val y = coordinate(offset + 4)
            if (!down && x == 0 && y == 0 && previous != null) previous.copy(down = false)
            else RemoteContact(id, (x.toFloat() / width).coerceIn(0f, 1f),
                (y.toFloat() / height).coerceIn(0f, 1f), down)
        }
        CarlifeConnectService.remoteTouch(contacts)
    }

    private fun knob(report: ByteArray) {
        if (report.size != 4) return
        val buttons = report[0].toInt() and 7
        val pressed = buttons and knobButtons.inv()
        knobButtons = buttons
        if (pressed and 1 != 0) CarlifeConnectService.remoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
        if (pressed and 4 != 0) CarlifeConnectService.remoteKey(KeyEvent.KEYCODE_BACK)
        val x = report[1].toInt()
        val y = report[2].toInt()
        val wheel = report[3].toInt()
        if (x != 0) CarlifeConnectService.remoteKey(if (x < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
        if (y != 0) CarlifeConnectService.remoteKey(if (y < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
        repeat(kotlin.math.abs(wheel).coerceAtMost(8)) {
            CarlifeConnectService.remoteKey(if (wheel < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
        }
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

    private companion object {
        const val IAP_BUFFER_BYTES = 65_536
        val inputOwnerLock = Any()
        var inputOwner: CarPlayTouchEvents? = null
    }
}
