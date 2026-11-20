package com.baidu.carlife.xiaomi.carplay

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Writes the ChaCha20-Poly1305 variant of the CarPlay mirroring stream.
 * Create one writer per negotiated stream, with a fresh video key from the verified session.
 */
internal class CarPlayVideoWriter(
    private val output: OutputStream,
    videoKey: ByteArray,
) {
    init {
        require(videoKey.size == 32) { "The video key must contain 32 bytes" }
    }

    private val key = SecretKeySpec(videoKey, "ChaCha20")
    private val cipher = Cipher.getInstance("ChaCha20-Poly1305")
    private var counter = 0L
    private var configured = false
    private var codec = VideoCodec.H264
    private var failed = false

    /** Sends the encoder's AVCDecoderConfigurationRecord (avcC). */
    fun writeConfiguration(avcC: ByteArray, width: Int, height: Int, codec: VideoCodec = VideoCodec.H264) {
        check(!failed) { "The video stream must be reopened after a write failure" }
        require(avcC.isNotEmpty() && width > 0 && height > 0)
        val configuration = if (codec == VideoCodec.HEVC) avcC else ByteBuffer.allocate(8 + avcC.size)
            .putInt(8 + avcC.size)
            .putInt(AVCC_BOX)
            .put(avcC)
            .array()
        val header = header(configuration.size, CONFIGURATION, 0).apply {
            put(6, if (codec == VideoCodec.HEVC) 0x1e.toByte() else 4.toByte())
            if (codec == VideoCodec.HEVC) put(7, 1)
            putFloat(16, width.toFloat())
            putFloat(20, height.toFloat())
        }
        writePacket(header.array(), configuration)
        configured = true
        this.codec = codec
    }

    /** Sends one AVC access unit with four-byte big-endian NAL lengths. */
    fun writeAccessUnit(avccAccessUnit: ByteArray, ntpTimestamp: Long) {
        check(configured && !failed) { "A working video stream must send its configuration first" }
        require(avccAccessUnit.isNotEmpty())
        check(counter != Long.MAX_VALUE) { "The video stream must renegotiate its key" }

        val header = header(avccAccessUnit.size + TAG_BYTES, VIDEO, ntpTimestamp).apply {
            if (codec == VideoCodec.HEVC) put(5, 0x10)
        }.array()
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(4, counter++)
            .array()
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(nonce))
        cipher.updateAAD(header)
        writePacket(header, cipher.doFinal(avccAccessUnit))
    }

    fun writeKeepAlive() {
        check(!failed) { "The video stream must be reopened after a write failure" }
        writePacket(header(0, 2, 0).array(), byteArrayOf())
    }

    private fun writePacket(header: ByteArray, payload: ByteArray) {
        // A partial write cannot be retried on this stream.
        failed = true
        output.write(header)
        output.write(payload)
        output.flush()
        failed = false
    }

    private fun header(size: Int, type: Byte, timestamp: Long): ByteBuffer =
        ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0, size)
            .put(4, type)
            .putLong(8, timestamp)

    private companion object {
        const val HEADER_BYTES = 128
        const val TAG_BYTES = 16
        const val VIDEO: Byte = 0
        const val CONFIGURATION: Byte = 1
        const val AVCC_BOX = 0x61766343
    }
}
