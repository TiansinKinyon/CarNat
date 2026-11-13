package com.baidu.carlife.xiaomi.carplay

import android.media.AudioFormat
import android.util.Log
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Sends the native CarLife callback's PCM16 as authenticated CarPlay RTP. */
internal class CarPlayPcmAudio(host: String, port: Int, key: ByteArray, private val rate: Int) : Closeable {
    private val socket = DatagramSocket().apply { connect(InetSocketAddress(host, port)) }
    private val secret = SecretKeySpec(key, "ChaCha20")
    private val queue = ArrayBlockingQueue<Chunk>(16)
    private var sequence = 0L
    private var samples = 0
    private var remainder = 0
    private var sourceRate = 0
    private val payload = ByteBuffer.allocate(240 * 4)
    private val sender = Thread({
        try {
            while (!socket.isClosed) send(queue.take())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Exception) {
            if (!socket.isClosed) Log.w(TAG, "Audio sender stopped", failure)
        }
    }, "CarPlay audio").apply { isDaemon = true }

    fun start() {
        synchronized(Companion) {
            check(active == null)
            active = this
        }
        sender.start()
    }

    private fun send(chunk: Chunk) {
        if (sourceRate != chunk.rate) {
            sourceRate = chunk.rate
            remainder = 0
        }
        val input = ByteBuffer.wrap(chunk.bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (input.remaining() >= chunk.channels * 2) {
            val left = input.short
            val right = if (chunk.channels == 2) input.short else left
            remainder += rate
            while (remainder >= sourceRate) {
                remainder -= sourceRate
                payload.putShort(left).putShort(right)
                if (!payload.hasRemaining()) {
                    sendPacket()
                    payload.clear()
                }
            }
        }
    }

    private fun sendPacket() {
        val header = ByteBuffer.allocate(12).put(0x80.toByte())
            .put((if (sequence == 0L) 0x80 or TYPE else TYPE).toByte())
            .putShort(sequence.toShort()).putInt(samples).putInt(0).array()
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(4, sequence).array()
        cipher.init(Cipher.ENCRYPT_MODE, secret, IvParameterSpec(nonce))
        cipher.updateAAD(header, 4, 8)
        val packet = header + cipher.doFinal(payload.array()) + nonce.copyOfRange(4, 12)
        socket.send(DatagramPacket(packet, packet.size))
        if (sequence == 0L) Log.i(TAG, "Sent first CarWith PCM packet at $rate Hz")
        sequence++
        samples += payload.capacity() / 4
    }

    private val cipher = Cipher.getInstance("ChaCha20-Poly1305")

    override fun close() {
        synchronized(Companion) { if (active === this) active = null }
        socket.close()
        sender.interrupt()
    }

    private data class Chunk(val bytes: ByteArray, val rate: Int, val channels: Int)

    companion object {
        const val TYPE = 100
        private const val TAG = "CarNatAudio"
        @Volatile private var active: CarPlayPcmAudio? = null

        fun offer(data: ByteArray?, rate: Int, channels: Int, format: Int) {
            val stream = active ?: return
            if (data == null || data.size !in 1..65_536 || rate !in 8_000..96_000 || channels !in 1..2) return
            val pcm = when (format) {
                AudioFormat.ENCODING_PCM_16BIT -> data
                AudioFormat.ENCODING_PCM_8BIT -> ByteBuffer.allocate(data.size * 2)
                    .order(ByteOrder.LITTLE_ENDIAN).apply {
                        for (sample in data) putShort((((sample.toInt() and 255) - 128) shl 8).toShort())
                    }.array()
                else -> return
            }
            if (pcm.size % (channels * 2) != 0) return
            val chunk = Chunk(pcm, rate, channels)
            if (!stream.queue.offer(chunk)) {
                stream.queue.poll()
                stream.queue.offer(chunk)
            }
        }
    }
}
