package com.baidu.carlife.xiaomi.carplay

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ProtocolException
import java.util.Locale

/**
 * Serial RTSP requests over the CarPlay control channel.
 * The owner supplies the streams and configures the transport timeout and session encryption.
 * The separate receiver event channel does not share this connection.
 */
internal class RtspControl(
    input: InputStream,
    private val output: OutputStream,
    private val transport: Closeable,
) : Closeable {
    private val input = BufferedInputStream(input)
    private var nextSequence = 1
    private var closed = false

    @Synchronized
    fun request(
        method: String,
        target: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = byteArrayOf(),
    ): RtspResponse {
        check(!closed) { "RTSP connection is closed" }
        require(TOKEN.matches(method) && target.isNotEmpty() && target.all { it in '!'..'~' })
        require(body.size <= MAX_BODY_BYTES)
        headers.forEach { (name, value) ->
            require(TOKEN.matches(name) && name.lowercase(Locale.ROOT) !in OWNED_HEADERS)
            require(value.all { it == '\t' || it in ' '..'~' })
        }

        val sequence = nextSequence++
        val requestHead = buildString {
            append("$method $target RTSP/1.0\r\n")
            append("CSeq: $sequence\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Content-Length: ${body.size}\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        require(requestHead.size <= MAX_HEAD_BYTES)

        try {
            output.write(requestHead)
            output.write(body)
            output.flush()
            return readResponse(sequence)
        } catch (error: IOException) {
            try {
                close()
            } catch (closeError: IOException) {
                error.addSuppressed(closeError)
            }
            throw error
        }
    }

    private fun readResponse(sequence: Int): RtspResponse {
        val statusLine = readLine()
        val parts = statusLine.split(' ', limit = 3)
        val status = parts.getOrNull(1)?.takeIf { it.length == 3 }?.toIntOrNull()
        if (parts.firstOrNull() != "RTSP/1.0" || status == null || status !in 100..599) {
            throw ProtocolException("Invalid RTSP response status")
        }

        var headBytes = statusLine.length + 2
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine()
            headBytes += line.length + 2
            if (headBytes > MAX_HEAD_BYTES) throw ProtocolException("RTSP response headers are too large")
            if (line.isEmpty()) break

            val separator = line.indexOf(':')
            if (separator < 1) throw ProtocolException("Invalid RTSP response header")
            val name = line.substring(0, separator).lowercase(Locale.ROOT)
            if (!TOKEN.matches(name)) throw ProtocolException("Invalid RTSP response header name")
            val value = line.substring(separator + 1).trim()
            if (headers.put(name, value) != null && name in OWNED_HEADERS) {
                throw ProtocolException("Duplicate RTSP $name header")
            }
        }

        if (headers["cseq"]?.toIntOrNull() != sequence) {
            throw ProtocolException("RTSP response CSeq does not match the request")
        }
        val bodyLength = headers["content-length"]?.let {
            it.toIntOrNull()?.takeIf { length -> length in 0..MAX_BODY_BYTES }
                ?: throw ProtocolException("Invalid RTSP content length")
        } ?: 0
        val body = ByteArray(bodyLength)
        var offset = 0
        while (offset < body.size) {
            val received = input.read(body, offset, body.size - offset)
            if (received < 0) throw EOFException("Truncated RTSP response body")
            offset += received
        }
        return RtspResponse(status, parts.getOrElse(2) { "" }, headers, body)
    }

    private fun readLine(): String {
        val line = StringBuilder()
        while (line.length <= MAX_LINE_BYTES) {
            when (val value = input.read()) {
                -1 -> throw EOFException("Truncated RTSP response headers")
                13 -> {
                    if (input.read() != 10) throw ProtocolException("Expected RTSP CRLF")
                    return line.toString()
                }
                9, in 32..126 -> line.append(value.toChar())
                else -> throw ProtocolException("Invalid character in RTSP response headers")
            }
        }
        throw ProtocolException("RTSP response line is too large")
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        transport.close()
    }

    private companion object {
        val TOKEN = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")
        val OWNED_HEADERS = setOf("cseq", "content-length")
        const val MAX_LINE_BYTES = 8192
        const val MAX_HEAD_BYTES = 32768
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
    }
}

internal data class RtspResponse(
    val status: Int,
    val reason: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)
