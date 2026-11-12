package com.baidu.carlife.xiaomi.carplay

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ProtocolException

internal object Iap2LinkProbe {
    private val detection = byteArrayOf(-1, 0x55, 2, 0, -18, 16)

    fun run(input: InputStream, output: OutputStream, onStage: (String) -> Unit) {
        val incoming = DataInputStream(input)
        var repliedToDetection = false
        var synchronized = false
        var peerSequence = 0
        val localSequence = 99
        while (true) {
            if (incoming.readUnsignedByte() != 0xff) throw ProtocolException("Invalid iAP2 start byte")
            when (incoming.readUnsignedByte()) {
                0x55 -> {
                    val tail = ByteArray(4).also(incoming::readFully)
                    if (!tail.contentEquals(detection.copyOfRange(2, 6))) {
                        throw ProtocolException("Invalid iAP2 detection marker")
                    }
                    if (!repliedToDetection) {
                        output.write(detection)
                        output.flush()
                        repliedToDetection = true
                        onStage("iAP2 检测已回应")
                    }
                }
                0x5a -> {
                    val length = incoming.readUnsignedShort()
                    if (length !in 9..8192 || length == 10) throw ProtocolException("Invalid iAP2 frame length")
                    val packet = ByteArray(length)
                    packet[0] = -1
                    packet[1] = 0x5a
                    packet[2] = (length ushr 8).toByte()
                    packet[3] = length.toByte()
                    incoming.readFully(packet, 4, length - 4)
                    if (checksum(packet, 0, 9) != 0 || length > 9 && checksum(packet, 9, length) != 0) {
                        throw ProtocolException("Invalid iAP2 checksum")
                    }

                    val flags = packet[4].toInt() and 255
                    val sequence = packet[5].toInt() and 255
                    if (flags and 0x10 != 0) throw ProtocolException("Receiver reset the iAP2 link")
                    if (flags and 0x80 != 0) {
                        if (length < 20) throw ProtocolException("Incomplete iAP2 synchronization parameters")
                        peerSequence = sequence
                        val parameters = packet.copyOfRange(9, length - 1)
                        val reply = if (flags and 0x40 == 0) {
                            frame(0xc0, localSequence, peerSequence, parameters)
                        } else {
                            frame(0x40, localSequence, peerSequence)
                        }
                        output.write(reply)
                        output.flush()
                        synchronized = true
                        onStage("iAP2 链路已交换同步帧")
                    } else if (synchronized && length > 9 && packet[7] != 0.toByte()) {
                        if (sequence != ((peerSequence + 1) and 255)) {
                            throw ProtocolException("Out-of-order iAP2 control frame")
                        }
                        val message = packet.copyOfRange(9, length - 1)
                        val id = if (message.size >= 6 && message[0] == 0x40.toByte() &&
                            message[1] == 0x40.toByte()
                        ) {
                            ((message[4].toInt() and 255) shl 8) or (message[5].toInt() and 255)
                        } else null
                        output.write(frame(0x40, localSequence, sequence))
                        output.flush()
                        onStage("已收到 iAP2 控制帧" + (id?.let { " 0x${it.toString(16)}" } ?: ""))
                        return
                    }
                }
                else -> throw ProtocolException("Unknown iAP2 frame marker")
            }
        }
    }

    private fun frame(flags: Int, sequence: Int, ack: Int, data: ByteArray = byteArrayOf()): ByteArray {
        val length = if (data.isEmpty()) 9 else data.size + 10
        val packet = ByteArray(length)
        packet[0] = -1
        packet[1] = 0x5a
        packet[2] = (length ushr 8).toByte()
        packet[3] = length.toByte()
        packet[4] = flags.toByte()
        packet[5] = sequence.toByte()
        packet[6] = ack.toByte()
        packet[8] = (-checksum(packet, 0, 8)).toByte()
        if (data.isNotEmpty()) {
            data.copyInto(packet, 9)
            packet[length - 1] = (-checksum(data, 0, data.size)).toByte()
        }
        return packet
    }

    private fun checksum(bytes: ByteArray, start: Int, end: Int): Int {
        var sum = 0
        for (index in start until end) sum += bytes[index].toInt() and 255
        return sum and 255
    }
}
