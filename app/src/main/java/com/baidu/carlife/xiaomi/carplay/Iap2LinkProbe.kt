package com.baidu.carlife.xiaomi.carplay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ProtocolException
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

internal object Iap2LinkProbe {
    private val detection = byteArrayOf(-1, 0x55, 2, 0, -18, 16)

    fun run(input: InputStream, output: OutputStream, onStage: (String) -> Unit): Pair<String, Int> {
        val incoming = DataInputStream(input)
        var repliedToDetection = false
        var synchronized = false
        var identificationSent = false
        var peerSequence = 0
        var localSequence = 99
        var controlSession = 0
        val controlData = ByteArrayOutputStream()
        var awaitingCertificate = false
        var challenge: ByteArray? = null
        var leaf: X509Certificate? = null
        var authenticated = false

        fun sendControl(id: Int, value: ByteArray? = null, parameterId: Int = 0) {
            localSequence = (localSequence + 1) and 255
            val size = if (value == null) 6 else 10 + value.size
            val header = byteArrayOf(0x40, 0x40, (size ushr 8).toByte(), size.toByte(),
                (id ushr 8).toByte(), id.toByte())
            val request = if (value == null) header else {
                val parameterLength = 4 + value.size
                header + byteArrayOf((parameterLength ushr 8).toByte(), parameterLength.toByte(),
                    (parameterId ushr 8).toByte(), parameterId.toByte()) + value
            }
            output.write(frame(0x40, localSequence, peerSequence, request, controlSession))
            output.flush()
        }

        fun sendIdentification() {
            sendControl(0x1d00)
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
                            if (controlData.size() + length - 10 > 8192) {
                                throw ProtocolException("iAP2 control message exceeds the size limit")
                            }
                            controlData.write(packet, 9, length - 10)
                            peerSequence = sequence
                        }
                        output.write(frame(0x40, localSequence, sequence))
                        output.flush()
                        while (true) {
                            val message = takeMessage(controlData) ?: break
                            val id = unsignedShort(message, 4)
                            if (authenticated) {
                                if (id == 0x4301) {
                                    val endpoint = receiverEndpoint(message)
                                    onStage("已收到 CarPlay 网络端点：${endpoint.first}:${endpoint.second}")
                                    return endpoint
                                }
                            } else if (!awaitingCertificate) {
                                if (id != 0x1d01) throw ProtocolException("Expected receiver identification")
                                onStage("已收到 iAP2 接收端身份：${receiverIdentity(message)}")
                                sendControl(0x1d02)
                                sendControl(0xaa00)
                                awaitingCertificate = true
                            } else if (challenge == null) {
                                if (id != 0xaa01) throw ProtocolException("Expected receiver certificate")
                                val certificate = parameters(message)[0]
                                    ?: throw ProtocolException("Receiver did not provide a certificate")
                                val parsed = CertificateFactory.getInstance("X.509")
                                    .generateCertificates(ByteArrayInputStream(certificate))
                                leaf = parsed.filterIsInstance<X509Certificate>()
                                    .singleOrNull { it.basicConstraints < 0 }
                                    ?: throw ProtocolException("Expected one receiver end-entity certificate")
                                MfiTrust.validate(leaf)
                                onStage("MFi 证书通过 Apple 配件信任链验证")
                                val nonce = ByteArray(32).also(SecureRandom()::nextBytes)
                                challenge = nonce
                                sendControl(0xaa02, nonce)
                            } else {
                                if (id != 0xaa03) throw ProtocolException("Expected receiver challenge signature")
                                val signature = parameters(message)[0]
                                    ?: throw ProtocolException("Receiver signature is missing")
                                if (!MfiChallenge.verify(checkNotNull(leaf).publicKey, checkNotNull(challenge), signature)) {
                                    throw ProtocolException("Receiver challenge signature did not match its certificate")
                                }
                                onStage("MFi 挑战签名验证通过")
                                sendControl(0xaa05)
                                authenticated = true
                                // Availability group 1 contains parameter 0 (wireless available = true).
                                sendControl(0x4300, byteArrayOf(0, 5, 0, 0, 1), parameterId = 1)
                            }
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

    private fun takeMessage(buffer: ByteArrayOutputStream): ByteArray? {
        val message = buffer.toByteArray()
        if (message.size < 6) return null
        val size = unsignedShort(message, 2)
        if (unsignedShort(message, 0) != 0x4040 || size !in 6..8192) {
            throw ProtocolException("Invalid iAP2 control message")
        }
        if (message.size < size) return null
        buffer.reset()
        buffer.write(message, size, message.size - size)
        return message.copyOf(size)
    }

    private fun parameters(message: ByteArray, start: Int = 6): Map<Int, ByteArray> {
        val fields = mutableMapOf<Int, ByteArray>()
        var offset = start
        while (offset < message.size) {
            if (message.size - offset < 4) throw ProtocolException("Truncated iAP2 parameter")
            val length = unsignedShort(message, offset)
            if (length !in 4..(message.size - offset)) throw ProtocolException("Invalid iAP2 parameter")
            val field = unsignedShort(message, offset + 2)
            if (fields.put(field, message.copyOfRange(offset + 4, offset + length)) != null) {
                throw ProtocolException("Duplicate iAP2 parameter")
            }
            offset += length
        }
        return fields
    }

    private fun receiverEndpoint(message: ByteArray): Pair<String, Int> {
        val fields = parameters(message)
        val network = fields[1]?.let { parameters(it, start = 0) }
            ?: throw ProtocolException("Missing CarPlay network parameters")
        val host = network[3]?.toString(Charsets.US_ASCII)?.substringBefore('\u0000')
            ?.takeIf { it.isNotBlank() && it.all { character -> character in '!'..'~' } }
            ?: throw ProtocolException("Missing CarPlay endpoint address")
        val portBytes = fields[2]?.takeIf { it.size == 4 }
            ?: throw ProtocolException("Invalid CarPlay endpoint port")
        val port = java.nio.ByteBuffer.wrap(portBytes).int
        if (port !in 1..65535) throw ProtocolException("Invalid CarPlay endpoint port")
        return host to port
    }
    private fun receiverIdentity(message: ByteArray): String {
        val fields = parameters(message)
        fun text(field: Int): String = fields[field]?.toString(Charsets.UTF_8)
            ?.substringBefore('\u0000')?.filterNot(Char::isISOControl)?.take(64).orEmpty()
        val name = text(0)
        if (name.isBlank()) throw ProtocolException("iAP2 receiver name is missing")
        return listOf(name, text(4)).filter(String::isNotEmpty).joinToString(" ")
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
