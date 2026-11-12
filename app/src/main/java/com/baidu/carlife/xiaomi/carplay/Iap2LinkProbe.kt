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
        var identificationSent = false
        var peerSequence = 0
        var localSequence = 99
        var controlSession = 0
        val identity = java.io.ByteArrayOutputStream()

        fun sendIdentification() {
            localSequence = (localSequence + 1) and 255
            val request = byteArrayOf(0x40, 0x40, 0, 6, 0x1d, 0)
            output.write(frame(0x40, localSequence, peerSequence, request, controlSession))
            output.flush()
            identificationSent = true
            onStage("已请求 iAP2 接收端身份")
        }
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
                        val parameters = packet.copyOfRange(9, length - 1)
                        controlSession = controlSession(parameters)
                        peerSequence = sequence
                        val reply = if (flags and 0x40 == 0) {
                            frame(0xc0, localSequence, peerSequence, parameters)
                        } else {
                            frame(0x40, localSequence, peerSequence)
                        }
                        output.write(reply)
                        output.flush()
                        synchronized = true
                        onStage("iAP2 链路已交换同步帧")
                        if (flags and 0x40 != 0 && !identificationSent) sendIdentification()
                    } else if (synchronized && length == 9 && flags == 0x40 && !identificationSent &&
                        packet[6].toInt() and 255 == localSequence
                    ) {
                        sendIdentification()
                    } else if (identificationSent && length > 9 && (packet[7].toInt() and 255) == controlSession) {
                        if (sequence != peerSequence && sequence != ((peerSequence + 1) and 255)) {
                            throw ProtocolException("Out-of-order iAP2 control frame")
                        }
                        if (sequence != peerSequence) {
                            if (identity.size() + length - 10 > 8192) {
                                throw ProtocolException("iAP2 identification exceeds the size limit")
                            }
                            identity.write(packet, 9, length - 10)
                            peerSequence = sequence
                        }
                        output.write(frame(0x40, localSequence, sequence))
                        output.flush()
                        val label = receiverIdentity(identity.toByteArray())
                        if (label != null) {
                            onStage("已收到 iAP2 接收端身份：$label")
                            return
                        }
                    }
                }
                else -> throw ProtocolException("Unknown iAP2 frame marker")
            }
        }
    }

    private fun controlSession(parameters: ByteArray): Int {
        if (parameters.size < 13 || parameters[0] != 1.toByte() || (parameters.size - 10) % 3 != 0) {
            throw ProtocolException("Invalid iAP2 synchronization parameters")
        }
        for (offset in 10 until parameters.size step 3) {
            if (parameters[offset + 1] == 0.toByte() && parameters[offset] != 0.toByte()) {
                return parameters[offset].toInt() and 255
            }
        }
        throw ProtocolException("Receiver did not advertise an iAP2 control session")
    }

    private fun receiverIdentity(message: ByteArray): String? {
        if (message.size < 6) return null
        val size = unsignedShort(message, 2)
        if (unsignedShort(message, 0) != 0x4040 || size !in 6..8192) {
            throw ProtocolException("Invalid iAP2 identification message")
        }
        if (message.size < size) return null
        if (unsignedShort(message, 4) != 0x1d01) {
            throw ProtocolException("Expected the iAP2 receiver identification response")
        }
        var name = ""
        var version = ""
        var offset = 6
        while (offset < size) {
            if (size - offset < 4) throw ProtocolException("Truncated iAP2 identification field")
            val length = unsignedShort(message, offset)
            if (length !in 4..(size - offset)) throw ProtocolException("Invalid iAP2 identification field")
            val field = unsignedShort(message, offset + 2)
            if (field == 0 || field == 4) {
                val value = message.copyOfRange(offset + 4, offset + length).toString(Charsets.UTF_8)
                    .substringBefore('\u0000').filterNot(Char::isISOControl).take(64)
                if (field == 0) name = value else version = value
            }
            offset += length
        }
        if (name.isBlank()) throw ProtocolException("iAP2 receiver name is missing")
        return listOf(name, version).filter(String::isNotEmpty).joinToString(" ")
    }

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)

    private fun frame(
        flags: Int,
        sequence: Int,
        ack: Int,
        data: ByteArray = byteArrayOf(),
        session: Int = 0,
    ): ByteArray {
        val length = if (data.isEmpty()) 9 else data.size + 10
        val packet = ByteArray(length)
        packet[0] = -1
        packet[1] = 0x5a
        packet[2] = (length ushr 8).toByte()
        packet[3] = length.toByte()
        packet[4] = flags.toByte()
        packet[5] = sequence.toByte()
        packet[6] = ack.toByte()
        packet[7] = session.toByte()
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
