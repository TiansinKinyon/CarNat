package com.baidu.carlife.xiaomi.carplay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ProtocolException
import java.nio.ByteBuffer
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

internal object Iap2LinkProbe {
    private val identificationGroups = setOf(0x0a, 0x0b, 0x0c, 0x12)
    private val detection = byteArrayOf(-1, 0x55, 2, 0, -18, 16)

    class Endpoint(
        val host: String?, val port: Int?, val certificate: X509Certificate, val wifi: ReceiverWifi?,
        val protocol: ReceiverProtocol,
        val identity: String? = null,
    )

    fun run(
        input: InputStream, output: OutputStream, onWifi: (ReceiverWifi) -> Unit = {},
        profile: HeadUnitProfile = HeadUnitProfile.GENERIC, deviceId: String = "",
        onBmwAppsWindow: (Boolean) -> Unit = {},
        initiateLink: Boolean = false,
        onStage: (String) -> Unit,
    ): Endpoint = exchange(input, output, null, onWifi, onStage, profile, deviceId, onBmwAppsWindow, initiateLink)

    fun runNetwork(input: InputStream, output: OutputStream, certificate: X509Certificate, onStage: (String) -> Unit) {
        exchange(input, output, certificate, {}, onStage)
    }

    private fun exchange(
        input: InputStream, output: OutputStream, expectedCertificate: X509Certificate?,
        onWifi: (ReceiverWifi) -> Unit,
        onStage: (String) -> Unit,
        profile: HeadUnitProfile = HeadUnitProfile.GENERIC,
        deviceId: String = "",
        onBmwAppsWindow: (Boolean) -> Unit = {},
        initiateLink: Boolean = false,
    ): Endpoint {
        val incoming = DataInputStream(input)
        var repliedToDetection = false
        var synchronized = false
        var identificationSent = false
        var peerSequence = 0
        var localSequence = 99
        var lastSyncRequest: ByteArray? = null
        var lastSyncReply: ByteArray? = null
        var controlSession = 0
        val controlData = ByteArrayOutputStream()
        var awaitingCertificate = false
        var challenge: ByteArray? = null
        var leaf: X509Certificate? = null
        var authenticated = false
        var requestWifi = false
        val protocol = profile.protocol
        var declaresAvailability = false
        var declaresWifiConfiguration = false
        var declaresStartSession = false
        var legacyMguWifi = false
        var bmwAppsOnly = false
        var requestedFullCertificate = false
        var model = ""
        var identityText: String? = null
        var wifi: ReceiverWifi? = null

        fun sendControlBody(id: Int, body: ByteArray) {
            localSequence = (localSequence + 1) and 255
            val size = 6 + body.size
            val header = byteArrayOf(0x40, 0x40, (size ushr 8).toByte(), size.toByte(),
                (id ushr 8).toByte(), id.toByte())
            output.write(frame(0x40, localSequence, peerSequence, header + body, controlSession))
            output.flush()
        }

        fun sendControl(id: Int, value: ByteArray? = null, parameterId: Int = 0) {
            sendControlBody(id, value?.let { parameter(parameterId, it) } ?: byteArrayOf())
        }

        fun sendAvailability() {
            val available = parameter(0, byteArrayOf(1))
            val identity = if (protocol.isVehicle) parameter(1, cString(deviceId)) else byteArrayOf()
            sendControl(0x4300, available + identity, parameterId = 1)
        }

        fun sendBmwGreetings() {
            val time = Date()
            val zone = TimeZone.getDefault()
            val uuid = UUID.nameUUIDFromBytes("CarNat:iAP2:$deviceId".toByteArray(Charsets.US_ASCII))
            sendControl(0x4e0c, cString(uuid.toString().uppercase(Locale.ROOT)))
            sendControl(0x4e0d, byteArrayOf(1))
            sendControl(0x4e0a, cString(Locale.getDefault().language.ifBlank { "en" }))
            sendControl(0x4e09, cString("CarNat"))
            val date = parameter(0, ByteBuffer.allocate(8).putLong(time.time / 1000).array())
            val offset = parameter(1, ByteBuffer.allocate(2).putShort((zone.getOffset(time.time) / 60_000).toShort()).array())
            val daylight = parameter(2, byteArrayOf(if (zone.inDaylightTime(time)) 1 else 0))
            sendControlBody(0x4e0b, date + offset + daylight)
        }

        fun readCapabilities(identity: Map<Int, ByteArray>) {
            requestWifi = containsMessage(identity[7], 0x5702)
            declaresAvailability = containsMessage(identity[7], 0x4300)
            declaresWifiConfiguration = containsMessage(identity[6], 0x5703)
            declaresStartSession = containsMessage(identity[6], 0x4301)
            legacyMguWifi = model.equals("MGU-55-BMW", ignoreCase = true) &&
                requestWifi && declaresWifiConfiguration && !declaresAvailability
            bmwAppsOnly = protocol == ReceiverProtocol.BMW_MGU &&
                model.startsWith("MGU-", ignoreCase = true) &&
                !requestWifi && !declaresAvailability && !declaresWifiConfiguration && !declaresStartSession &&
                (0x0a..0x0c).any { id ->
                    identity[id]?.toString(Charsets.UTF_8)?.contains("de.bmw.a4a.") == true
                } && (0x0a..0x0c).none { id ->
                    identity[id]?.toString(Charsets.UTF_8)?.contains("com.bmw.carplay") == true
                }
        }

        fun startMguWifi() {
            if (requestWifi || declaresWifiConfiguration || !declaresAvailability) sendControl(0x5702)
            if (declaresAvailability) sendAvailability()
        }

        fun sendIdentification() {
            sendControl(0x1d00)
            identificationSent = true
            onStage("已请求 iAP2 接收端身份")
        }
        if (initiateLink) {
            // The phone initiates detection and SYN only on a selected outgoing accessory socket.
            val sync = byteArrayOf(1, 4, 0x20, 0, 0, 100, 0, 50, 4, 3, 10, 0, 2)
            output.write(detection)
            output.write(frame(0x80, localSequence, 0, sync))
            output.flush()
            repliedToDetection = true
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
                            if (lastSyncRequest?.contentEquals(packet) == true) {
                                checkNotNull(lastSyncReply)
                            } else {
                                localSequence = (localSequence + 1) and 255
                                lastSyncRequest = packet
                                frame(0xc0, localSequence, peerSequence, parameters).also { lastSyncReply = it }
                            }
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
                                if (expectedCertificate == null) {
                                    when (id) {
                                        0x1d01 -> if (protocol == ReceiverProtocol.BMW_MGU && bmwAppsOnly) {
                                            val update = parameters(message, repeatableFields = identificationGroups)
                                            val updatedModel = update[1]?.toString(Charsets.UTF_8)?.substringBefore('\u0000')
                                            if (!model.equals(updatedModel, ignoreCase = true)) {
                                                throw ProtocolException("iAP2 接收端型号在连接中变化")
                                            }
                                            readCapabilities(update)
                                            sendControl(0x1d02)
                                            onBmwAppsWindow(bmwAppsOnly)
                                            if (!bmwAppsOnly) startMguWifi()
                                        }
                                        0x5703 -> if (wifi == null) {
                                            wifi = ReceiverWifi.fromConfiguration(parameters(message))
                                            wifi?.let(onWifi)
                                            if (protocol == ReceiverProtocol.BMW_MGU && legacyMguWifi && wifi != null) {
                                                sendControl(0x4e0e, cString(deviceId))
                                                return Endpoint(null, null, checkNotNull(leaf), wifi, protocol)
                                            }
                                            if (protocol == ReceiverProtocol.VOLKSWAGEN && !declaresStartSession) {
                                                sendControl(0x4e0e, cString(deviceId))
                                                return Endpoint(null, null, checkNotNull(leaf), wifi, protocol)
                                            }
                                            if (protocol == ReceiverProtocol.BMW_MGU && !declaresAvailability) sendAvailability()
                                        }
                                        0x4301 -> {
                                            val endpoint = receiverEndpoint(message, checkNotNull(leaf), wifi, protocol, identityText)
                                            if (protocol == ReceiverProtocol.BMW_MGU || protocol == ReceiverProtocol.VOLKSWAGEN) sendControl(0x4e0e, cString(deviceId))
                                            val address = endpoint.host?.let { "$it:${endpoint.port}" }
                                            onStage(if (address != null) "已收到 CarPlay 网络端点：$address"
                                                else "已收到车机热点信息 · 等待 AirPlay 服务发现")
                                            return endpoint
                                        }
                                    }
                                }
                            } else if (!awaitingCertificate) {
                                if (id != 0x1d01) throw ProtocolException("Expected receiver identification")
                                identityText = receiverIdentity(message)
                                onStage("已收到 iAP2 接收端身份：$identityText")
                                val identity = parameters(message, repeatableFields = identificationGroups)
                                model = identity[1]?.toString(Charsets.UTF_8)?.substringBefore('\u0000').orEmpty()
                                readCapabilities(identity)
                                sendControl(0x1d02)
                                val askForSerial = protocol.isVehicle && containsMessage(identity[6], 0xaa06)
                                sendControl(0xaa00, if (askForSerial) byteArrayOf() else null)
                                requestedFullCertificate = !askForSerial
                                awaitingCertificate = true
                            } else if (challenge == null) {
                                if (id == 0xaa06 && protocol.isVehicle && !requestedFullCertificate) {
                                    if (parameters(message)[0]?.isNotEmpty() != true) {
                                        throw ProtocolException("车机未提供 MFi 序列号")
                                    }
                                    sendControl(0xaa00)
                                    requestedFullCertificate = true
                                    continue
                                }
                                if (id != 0xaa01) throw ProtocolException("Expected receiver certificate")
                                val certificate = parameters(message)[0]
                                    ?: throw ProtocolException("Receiver did not provide a certificate")
                                val parsed = CertificateFactory.getInstance("X.509")
                                    .generateCertificates(ByteArrayInputStream(certificate))
                                leaf = parsed.filterIsInstance<X509Certificate>()
                                    .singleOrNull { it.basicConstraints < 0 }
                                    ?: throw ProtocolException("Expected one receiver end-entity certificate")
                                if (expectedCertificate == null) MfiTrust.validate(leaf)
                                else if (!leaf.encoded.contentEquals(expectedCertificate.encoded)) {
                                    throw ProtocolException("Network iAP2 certificate does not match Bluetooth")
                                }
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
                                if (expectedCertificate == null) {
                                    when (protocol) {
                                        ReceiverProtocol.GENERIC -> {
                                            if (requestWifi) sendControl(0x5702)
                                            sendAvailability()
                                        }
                                        ReceiverProtocol.BMW_MGU -> {
                                            sendBmwGreetings()
                                            onBmwAppsWindow(bmwAppsOnly)
                                            if (bmwAppsOnly) onStage("宝马控制链路已认证 · 等待车机开启 CarPlay")
                                            else startMguWifi()
                                        }
                                        ReceiverProtocol.VOLKSWAGEN -> {
                                            sendAvailability()
                                            if (requestWifi || declaresWifiConfiguration) sendControl(0x5702)
                                            else if (!declaresStartSession) {
                                                sendControl(0x4e0e, cString(deviceId))
                                                return Endpoint(null, null, checkNotNull(leaf), null, protocol)
                                            }
                                        }
                                    }
                                } else {
                                    onStage("网络 iAP2 已认证")
                                }
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

    private fun parameters(
        message: ByteArray, start: Int = 6, repeatableFields: Set<Int> = emptySet(),
        onRepeated: (Int, ByteArray) -> Unit = { _, _ -> },
    ): Map<Int, ByteArray> {
        val fields = mutableMapOf<Int, ByteArray>()
        var offset = start
        while (offset < message.size) {
            if (message.size - offset < 4) throw ProtocolException("Truncated iAP2 parameter")
            val length = unsignedShort(message, offset)
            if (length !in 4..(message.size - offset)) throw ProtocolException("Invalid iAP2 parameter")
            val field = unsignedShort(message, offset + 2)
            if (field !in fields) {
                fields[field] = message.copyOfRange(offset + 4, offset + length)
            } else {
                if (field !in repeatableFields) throw ProtocolException("Duplicate iAP2 parameter")
                onRepeated(field, message.copyOfRange(offset + 4, offset + length))
            }
            offset += length
        }
        return fields
    }

    private fun receiverEndpoint(
        message: ByteArray, certificate: X509Certificate, configuration: ReceiverWifi?,
        protocol: ReceiverProtocol,
        identity: String?,
    ): Endpoint {
        val fields = parameters(message)
        val wireless = fields[1] ?: throw ProtocolException("Missing CarPlay network parameters")
        // Some receivers shift the wireless field IDs by one. Either layout can repeat its IP field.
        val alternatives = mutableListOf<ByteArray>()
        val network = parameters(wireless, start = 0, repeatableFields = setOf(3, 4)) { _, bytes ->
            if (protocol.isVehicle && alternatives.size < 8) alternatives.add(bytes)
        }
        val offset = if (0 !in network && 5 in network) 1 else 0
        val primaryHost = network[3 + offset]?.let(::endpointText)
        val layoutB = protocol.isVehicle && network[2]?.lastOrNull() == 0.toByte() &&
            network[3]?.singleOrNull()?.toInt()?.let { it in 0..4 } == true &&
            network[4]?.singleOrNull() != null
        val wifi = if (!protocol.isVehicle) {
            configuration ?: ReceiverWifi.fromStartSession(network, offset)
        } else {
            val advertised = if (layoutB) ReceiverWifi.fromConfiguration(network)
                else ReceiverWifi.fromStartSession(network, offset)
            if (configuration != null && advertised != null && configuration.ssid != advertised.ssid) {
                throw ProtocolException("车机在协商期间更改热点，需重新连接")
            }
            configuration ?: advertised
        }
        val host = if (protocol.isVehicle) {
            val alternateHosts = (alternatives + listOfNotNull(network[4], network[5], fields[7]))
                .mapNotNull(::endpointText)
            val numericAddresses = (listOfNotNull(primaryHost) + alternateHosts).filter(::isNumericAddress)
            numericAddresses.firstOrNull { ':' !in it } ?: numericAddresses.firstOrNull()
                ?: primaryHost.takeUnless { layoutB }
        } else primaryHost
        val portBytes = fields[2]
        val port = portBytes?.let {
            if (it.size != 4) throw ProtocolException("Invalid CarPlay endpoint port")
            ByteBuffer.wrap(it).int.takeIf { value -> value in 1..65535 }
                ?: throw ProtocolException("Invalid CarPlay endpoint port")
        }
        if ((host == null || port == null) && (!protocol.isVehicle || (wifi == null && protocol != ReceiverProtocol.VOLKSWAGEN))) {
            throw ProtocolException("Missing CarPlay endpoint address or port")
        }
        return Endpoint(host, port, certificate, wifi, protocol, identity)
    }
    private fun endpointText(bytes: ByteArray): String? =
        bytes.toString(Charsets.US_ASCII).substringBefore('\u0000')
            .takeIf { it.isNotBlank() && it.all { character -> character in '!'..'~' } }

    private fun isNumericAddress(value: String): Boolean {
        if (value.contains(':')) {
            val unscoped = value.substringBefore('%')
            return unscoped.count { it == ':' } >= 2 && !CarPlayScreenProbe.bluetoothAddress.matches(unscoped) &&
                unscoped.all { it in "0123456789abcdefABCDEF:." }
        }
        val octets = value.split('.')
        return octets.size == 4 && octets.all { it.toIntOrNull()?.let { number -> number in 0..255 } == true }
    }

    private fun receiverIdentity(message: ByteArray): String {
        val fields = parameters(message, repeatableFields = identificationGroups)
        fun text(field: Int): String = fields[field]?.toString(Charsets.UTF_8)
            ?.substringBefore('\u0000')?.filterNot(Char::isISOControl)?.take(64).orEmpty()
        val name = text(0)
        if (name.isBlank()) throw ProtocolException("iAP2 receiver name is missing")
        return listOf(name, text(4)).filter(String::isNotEmpty).joinToString(" ")
    }

    private fun cString(value: String) = value.toByteArray(Charsets.UTF_8) + 0.toByte()

    private fun parameter(id: Int, value: ByteArray) = ByteBuffer.allocate(4 + value.size)
        .putShort((4 + value.size).toShort()).putShort(id.toShort()).put(value).array()

    private fun containsMessage(ids: ByteArray?, message: Int): Boolean =
        ids != null && (0 until ids.size - 1 step 2).any { unsignedShort(ids, it) == message }

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
