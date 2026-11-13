package com.baidu.carlife.xiaomi.carplay

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Sends the native CarLife callback's PCM as authenticated CarPlay RTP. */
internal class CarPlayMediaAudio(host: String, port: Int, key: ByteArray, private val rate: Int, private val aac: Boolean, private val stream: Int, private val outputChannels: Int) : Closeable {
    private val socket = DatagramSocket().apply { connect(InetSocketAddress(host, port)) }
    private val secret = SecretKeySpec(key, "ChaCha20")
    private val queue = ArrayBlockingQueue<Chunk>(16)
    private val droppedChunks = AtomicLong()
    private var encoderInputMisses = 0L
    private var nextPacketAt = 0L
    private var lastReportAt = 0L
    private val packetInterval = (if (aac) 1024L else (rate / 200).toLong()) * 1_000_000_000 / rate
    private var sequence = 0L
    private var samples = 0
    private var remainder = 0
    private var sourceRate = 0
    private val payload = ByteBuffer.allocate((if (aac) 1024 else rate / 200) * outputChannels * 2)
        .order(if (aac) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
    private val sender = Thread({
        try {
            if (aac) {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).useCodec { encoder ->
                    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2)
                    format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    format.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    encoder.start()
                    while (!socket.isClosed) send(queue.take(), encoder)
                }
            } else {
                while (!socket.isClosed) send(queue.take(), null)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Exception) {
            if (!socket.isClosed) Log.w(TAG, "Audio sender stopped", failure)
        }
    }, "CarPlay audio").apply { isDaemon = true }

    fun start() {
        synchronized(Companion) {
            check(active[stream] == null)
            active[stream] = this
        }
        sender.start()
    }

    private fun send(chunk: Chunk, encoder: MediaCodec?) {
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
                if (outputChannels == 1) payload.putShort(((left.toInt() + right.toInt()) / 2).toShort())
                else payload.putShort(left).putShort(right)
                if (!payload.hasRemaining()) {
                    if (encoder == null) sendPacket(payload.array()) else encode(encoder)
                    payload.clear()
                }
            }
        }
    }

    private var inputSamples = 0L
    private val outputInfo = MediaCodec.BufferInfo()

    private fun encode(encoder: MediaCodec) {
        val index = encoder.dequeueInputBuffer(20_000)
        if (index >= 0) {
            checkNotNull(encoder.getInputBuffer(index)).put(payload.array())
            encoder.queueInputBuffer(index, 0, payload.capacity(), inputSamples * 1_000_000 / rate, 0)
            inputSamples += 1024
        } else {
            encoderInputMisses++
        }
        while (true) {
            val output = encoder.dequeueOutputBuffer(outputInfo, 0)
            if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (output < 0) return
            try {
                if (outputInfo.size > 0 && outputInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    val buffer = checkNotNull(encoder.getOutputBuffer(output))
                    buffer.position(outputInfo.offset)
                    buffer.limit(outputInfo.offset + outputInfo.size)
                    sendPacket(ByteArray(outputInfo.size).also { buffer.get(it) })
                }
            } finally {
                encoder.releaseOutputBuffer(output, false)
            }
        }
    }

    private inline fun MediaCodec.useCodec(block: (MediaCodec) -> Unit) {
        try {
            block(this)
        } finally {
            release()
        }
    }

    private fun sendPacket(audio: ByteArray) {
        val type = if (aac) AAC_TYPE else PCM_TYPE
        val header = ByteBuffer.allocate(12).put(0x80.toByte())
            .put((if (sequence == 0L) 0x80 or type else type).toByte())
            .putShort(sequence.toShort()).putInt(samples).putInt(0).array()
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(4, sequence).array()
        cipher.init(Cipher.ENCRYPT_MODE, secret, IvParameterSpec(nonce))
        cipher.updateAAD(header, 4, 8)
        val packet = header + cipher.doFinal(audio) + nonce.copyOfRange(4, 12)
        // Binder and MediaCodec may deliver several access units in a single burst.
        // Re-anchor after a stall so the next burst cannot flood the receiver.
        if (aac) {
            var wait = nextPacketAt - System.nanoTime()
            while (wait > 0) {
                TimeUnit.NANOSECONDS.sleep(wait)
                wait = nextPacketAt - System.nanoTime()
            }
            val now = System.nanoTime()
            nextPacketAt = now + packetInterval
        }
        socket.send(DatagramPacket(packet, packet.size))
        val sentAt = System.nanoTime()
        if (sequence == 0L || sentAt - lastReportAt >= 5_000_000_000L) {
            Log.i(TAG, "Audio stream=$stream packets=${sequence + 1} queue=${queue.size} droppedChunks=${droppedChunks.get()} encoderInputMisses=$encoderInputMisses")
            lastReportAt = sentAt
        }
        if (sequence == 0L) Log.i(TAG, "Sent first CarWith audio packet, AAC=$aac rate=$rate")
        sequence++
        samples += payload.capacity() / (outputChannels * 2)
    }

    private val cipher = Cipher.getInstance("ChaCha20-Poly1305")

    override fun close() {
        synchronized(Companion) { active.remove(stream, this) }
        socket.close()
        sender.interrupt()
    }

    private data class Chunk(val bytes: ByteArray, val rate: Int, val channels: Int)

    companion object {
        const val PCM_TYPE = 100
        const val AAC_TYPE = 102
        private const val TAG = "CarNatAudio"
        private val active = java.util.concurrent.ConcurrentHashMap<Int, CarPlayMediaAudio>()

        fun offer(data: ByteArray?, sourceStream: Int, rate: Int, channels: Int, format: Int) {
            val stream = active[sourceStream] ?: return
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
                if (stream.queue.poll() != null) stream.droppedChunks.incrementAndGet()
                stream.queue.offer(chunk)
            }
        }
    }
}
