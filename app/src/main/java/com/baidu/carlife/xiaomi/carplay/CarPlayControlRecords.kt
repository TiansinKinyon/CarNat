package com.baidu.carlife.xiaomi.carplay

import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ProtocolException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object CarPlayControlRecords {
    fun input(stream: InputStream, readKey: ByteArray): InputStream {
        val cipher = RecordCipher(readKey, Cipher.DECRYPT_MODE)
        return object : InputStream() {
            private var plaintext = byteArrayOf()
            private var position = 0

            override fun read(): Int {
                if (!readable()) return -1
                return plaintext[position++].toInt() and 255
            }

            override fun read(destination: ByteArray, offset: Int, length: Int): Int {
                checkBounds(destination.size, offset, length)
                if (length == 0) return 0
                if (!readable()) return -1
                val count = minOf(length, plaintext.size - position)
                plaintext.copyInto(destination, offset, position, position + count)
                position += count
                return count
            }

            private fun readable(): Boolean {
                while (position == plaintext.size) {
                    val low = stream.read()
                    if (low == -1) return false
                    val high = stream.read()
                    if (high == -1) throw EOFException("Truncated CarPlay control record length")

                    val header = byteArrayOf(low.toByte(), high.toByte())
                    val ciphertext = ByteArray(low + (high shl 8) + TAG_BYTES)
                    var received = 0
                    while (received < ciphertext.size) {
                        val count = stream.read(ciphertext, received, ciphertext.size - received)
                        if (count == -1) throw EOFException("Truncated CarPlay control record")
                        received += count
                    }
                    plaintext = cipher.process(header, ciphertext, 0, ciphertext.size)
                    position = 0
                }
                return true
            }
        }
    }

    fun output(stream: OutputStream, writeKey: ByteArray): OutputStream {
        val cipher = RecordCipher(writeKey, Cipher.ENCRYPT_MODE)
        val records = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

            override fun write(data: ByteArray, offset: Int, length: Int) {
                checkBounds(data.size, offset, length)
                var position = offset
                val end = offset + length
                while (position < end) {
                    val count = minOf(end - position, MAX_OUTBOUND_RECORD)
                    val header = byteArrayOf(count.toByte(), (count ushr 8).toByte())
                    val ciphertext = cipher.process(header, data, position, count)
                    stream.write(header)
                    stream.write(ciphertext)
                    position += count
                }
            }

            override fun flush() = stream.flush()
        }
        return BufferedOutputStream(records, MAX_OUTBOUND_RECORD)
    }

    private class RecordCipher(keyBytes: ByteArray, private val mode: Int) {
        init {
            require(keyBytes.size == 32) { "The control record key must contain 32 bytes" }
        }

        private val key = SecretKeySpec(keyBytes, "ChaCha20")
        private val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        private var counter = 0L
        private var failed = false

        fun process(header: ByteArray, data: ByteArray, offset: Int, length: Int): ByteArray {
            if (failed || counter == Long.MAX_VALUE) {
                throw ProtocolException("CarPlay control stream requires a new session key")
            }
            val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(4, counter++).array()
            return try {
                cipher.init(mode, key, IvParameterSpec(nonce))
                cipher.updateAAD(header)
                cipher.doFinal(data, offset, length)
            } catch (error: GeneralSecurityException) {
                failed = true
                throw IOException("CarPlay control record authentication failed", error)
            }
        }
    }

    private fun checkBounds(size: Int, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > size - length) throw IndexOutOfBoundsException()
    }

    private const val MAX_OUTBOUND_RECORD = 1422
    private const val TAG_BYTES = 16
}
