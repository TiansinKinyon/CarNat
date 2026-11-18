package com.baidu.carlife.xiaomi.carplay

import com.baidu.carlife.service.CarlifeConnectService
import com.baidu.carlife.xiaomi.diagnostics.ProjectionLog
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ProtocolException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class MicrophoneSample(
    val level: Float,
    val timeMs: Long,
)

internal class CarPlayMicrophone private constructor(
    private val socket: DatagramSocket,
    private val host: InetAddress,
    key: ByteArray,
) : Closeable {
    val format = CarPlayAudioInfo("PCM 16 位", 16_000, 1, BUFFER_MS, socket.localPort)
    private val decoder = MicrophoneRtpDecoder(key)

    private val sample = MutableStateFlow<MicrophoneSample?>(null)
    val samples = sample.asStateFlow()

    fun start() {
        activeStream.value = this
        Thread({
            val packet = DatagramPacket(ByteArray(4096), 4096)
            var received = false
            var delivered = false
            var nextSampleAt = 0L
            var peak = 0
            try {
                while (!socket.isClosed) {
                    packet.length = packet.data.size
                    socket.receive(packet)
                    if (packet.address != host) continue
                    val pcm = decoder.decode(packet.data, packet.length) ?: continue
                    if (!received) {
                        received = true
                        ProjectionLog.event("车机麦克风 · 收到并解密 PCM")
                    }
                    val forwarded = CarlifeConnectService.remoteMicrophone(pcm)
                    for (index in pcm.indices step 2) {
                        val value = (pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 255)
                        peak = maxOf(peak, kotlin.math.abs(value))
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now >= nextSampleAt) {
                        sample.value = MicrophoneSample(peak / 32768f, now)
                        nextSampleAt = now + 100
                        peak = 0
                    }
                    if (forwarded && !delivered) {
                        delivered = true
                        ProjectionLog.event("车机麦克风 · 已向 CarWith 音频回调发送 PCM")
                    }
                }
            } catch (error: IOException) {
                if (!socket.isClosed) ProjectionLog.failure("车机麦克风 · 接收失败", error)
            } finally {
                close()
            }
        }, "CarPlay microphone").apply { isDaemon = true }.start()
    }

    override fun close() {
        socket.close()
        activeStream.compareAndSet(this, null)
    }

    companion object {
        private val activeStream = MutableStateFlow<CarPlayMicrophone?>(null)
        val active = activeStream.asStateFlow()

        private const val AUDIO_TYPE = "speechRecognition"
        private const val PCM_16K_MONO = 0x10
        private const val BUFFER_MS = 100

        fun open(
            info: NSDictionary,
            control: RtspControl,
            keys: CarPlaySessionKeys,
            sockets: CarPlaySockets,
            host: InetAddress,
        ): CarPlayMicrophone? {
            val formats = info.objectForKey("audioFormats") as? NSArray
            val supported = formats?.array?.filterIsInstance<NSDictionary>()?.any {
                (it.objectForKey("type") as? NSNumber)?.intValue() == CarPlayMediaAudio.PCM_TYPE &&
                    (it.objectForKey("audioType") as? NSString)?.content.equals(AUDIO_TYPE, ignoreCase = true) &&
                    ((it.objectForKey("audioInputFormats") as? NSNumber)?.intValue() ?: 0) and PCM_16K_MONO != 0
            } == true
            if (!supported) {
                ProjectionLog.event("车机麦克风 · 接收端未声明 16 kHz 单声道语音输入")
                return null
            }

            val socket = try {
                sockets.openDatagram()
            } catch (error: IOException) {
                ProjectionLog.failure("车机麦克风 · 无法打开 UDP 端口", error)
                return null
            }
            val id = (SecureRandom().nextLong() ushr 1).coerceAtLeast(1)
            val stream = NSDictionary().apply {
                put("type", CarPlayMediaAudio.PCM_TYPE)
                put("audioType", AUDIO_TYPE)
                put("audioFormat", PCM_16K_MONO)
                put("audioLatencyMs", BUFFER_MS)
                put("streamConnectionID", id)
                put("dataPort", socket.localPort)
                put("input", true)
            }
            val body = NSDictionary().apply { put("streams", NSArray(1).apply { setValue(0, stream) }) }
            return try {
                val reply = control.request(
                    "SETUP", "/", mapOf("Content-Type" to "application/x-apple-binary-plist"),
                    BinaryPropertyListWriter.writeToArray(body),
                )
                if (reply.status !in 200..299) throw ProtocolException("Microphone SETUP returned ${reply.status}")
                val streams = CarPlayPlist.readDictionary(reply.body).objectForKey("streams") as? NSArray
                val accepted = streams?.array?.filterIsInstance<NSDictionary>()?.any {
                    (it.objectForKey("type") as? NSNumber)?.intValue() == CarPlayMediaAudio.PCM_TYPE &&
                        (it.objectForKey("streamConnectionID") as? NSNumber)?.longValue()?.let { it == id } != false
                } == true
                if (!accepted) throw ProtocolException("Receiver did not return the microphone stream")
                ProjectionLog.event("车机麦克风 · 已协商语音上行，等待 PCM")
                CarPlayMicrophone(socket, host, keys.microphone(id))
            } catch (error: IOException) {
                socket.close()
                ProjectionLog.failure("车机麦克风 · 语音流协商失败", error)
                null
            }
        }
    }
}

internal class MicrophoneRtpDecoder(key: ByteArray) {
    private val secret = SecretKeySpec(key, "ChaCha20")
    private var lastNonce: ByteArray? = null

    fun decode(packet: ByteArray, length: Int): ByteArray? {
        if (length !in 38..packet.size || (packet[0].toInt() and 0xe0) != 0x80) return null
        if (packet[1].toInt() and 0x7f != CarPlayMediaAudio.PCM_TYPE) return null
        val nonceOffset = length - 8
        var headerSize = 12 + (packet[0].toInt() and 0x0f) * 4
        if (packet[0].toInt() and 0x10 != 0) {
            if (headerSize + 4 > nonceOffset - 16) return null
            val words = (packet[headerSize + 2].toInt() and 255) * 256 + (packet[headerSize + 3].toInt() and 255)
            headerSize += 4 + 4 * words
        }
        val sampleBytes = nonceOffset - 16 - headerSize
        if (sampleBytes <= 0 || sampleBytes % 2 != 0) return null
        val nonce = ByteArray(12)
        packet.copyInto(nonce, 4, nonceOffset, length)
        if (lastNonce?.let { previous ->
                (11 downTo 4).firstOrNull { nonce[it] != previous[it] }
                    ?.let { (nonce[it].toInt() and 255) <= (previous[it].toInt() and 255) } ?: true
            } == true
        ) return null
        val pcm = try {
            Cipher.getInstance("ChaCha20-Poly1305").run {
                init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(nonce))
                updateAAD(packet, 4, 8)
                doFinal(packet, headerSize, nonceOffset - headerSize)
            }
        } catch (_: GeneralSecurityException) {
            return null
        }
        lastNonce = nonce
        for (index in pcm.indices step 2) {
            val high = pcm[index]
            pcm[index] = pcm[index + 1]
            pcm[index + 1] = high
        }
        return pcm
    }
}
