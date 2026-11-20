package com.baidu.carlife.xiaomi.carplay

import com.baidu.carlife.xiaomi.diagnostics.ProjectionLog
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
    output: OutputStream,
    private val transport: Closeable,
) : Closeable {
    private var input = BufferedInputStream(input)
    private var output = output
    private var nextSequence = 1
    private var closed = false
    private var encrypted = false
    private var deferredSequence: Int? = null

    @Volatile var onPeerCommand: ((ByteArray) -> Boolean)? = null

    /** Queue a single command without holding the request/response lock while the peer changes transports. */
    @Synchronized
    fun sendDeferredCommand(body: ByteArray) {
        if (closed || !encrypted || deferredSequence != null) throw IOException("CarPlay 交接命令不可发送")
        require(body.size <= MAX_PEER_COMMAND_BYTES)
        val sequence = nextSequence++
        val head = "POST /command RTSP/1.0\r\nCSeq: $sequence\r\n" +
            "Content-Type: application/x-apple-binary-plist\r\nContent-Length: ${body.size}\r\n\r\n"
        try {
            output.write(head.toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.flush()
            deferredSequence = sequence
        } catch (failure: IOException) {
            runCatching { close() }
            throw failure
        }
    }

    /** Switches the existing connection after pair verification has succeeded. */
    @Synchronized
    fun enableEncryption(readKey: ByteArray, writeKey: ByteArray) {
        check(!closed && !encrypted) { "RTSP encryption can only be enabled once" }
        require(!readKey.contentEquals(writeKey)) { "RTSP directions must use different keys" }
        val securedInput = CarPlayControlRecords.input(input, readKey)
        val securedOutput = CarPlayControlRecords.output(output, writeKey)
        input = BufferedInputStream(securedInput)
        output = securedOutput
        encrypted = true
    }

    @Synchronized
    fun request(
        method: String,
        target: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = byteArrayOf(),
    ): RtspResponse {
        if (closed) throw IOException("RTSP connection is closed")
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

        val started = System.nanoTime()
        val safeTarget = when (target) {
            "*", "/", "/setup", "/info", "/pair-setup", "/pair-verify", "/auth-setup", "/command", "/feedback" -> target
            else -> "<动态路径>"
        }
        val requestName = "RTSP $method $safeTarget · CSeq=$sequence · 加密=$encrypted"
        try {
            output.write(requestHead)
            output.write(body)
            output.flush()
            val response = readResponse(sequence)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val heartbeat = method == "OPTIONS" || (method == "POST" && target == "/feedback")
            if (!heartbeat || response.status !in 200..299 || elapsed >= 1_000) {
                ProjectionLog.event("$requestName · 状态=${response.status} · 用时=${elapsed}ms · 响应=${response.body.size}B")
            }
            return response
        } catch (error: IOException) {
            ProjectionLog.failure("$requestName · 用时=${(System.nanoTime() - started) / 1_000_000}ms", error)
            try {
                close()
            } catch (closeError: IOException) {
                error.addSuppressed(closeError)
            }
            throw error
        }
    }

    private fun readResponse(sequence: Int): RtspResponse {
        repeat(32) {
            val line = readLine()
            val parts = line.split(' ', limit = 3)
            val status = parts.getOrNull(1)?.takeIf { it.length == 3 }?.toIntOrNull()
            if (parts.firstOrNull() == "RTSP/1.0" && status != null && status in 100..599) {
                val headers = readHeaders(line)
                val responseSequence = headers["cseq"]?.toIntOrNull()
                    ?: throw ProtocolException("RTSP response has no valid CSeq")
                val body = readBody(headers, MAX_BODY_BYTES)
                if (responseSequence == sequence) return RtspResponse(status, parts.getOrElse(2) { "" }, headers, body)
                if (responseSequence == deferredSequence) {
                    deferredSequence = null
                    ProjectionLog.event("宝马蓝牙交接请求应答 · RTSP $status")
                } else throw ProtocolException("RTSP response CSeq does not match the request")
            } else {
                val handler = onPeerCommand ?: throw ProtocolException("Invalid RTSP response status")
                val dialect = parts.getOrNull(2)
                if (!encrypted || parts.firstOrNull() != "POST" || parts.getOrNull(1) != "/command" ||
                    dialect !in setOf("RTSP/1.0", "HTTP/1.0", "HTTP/1.1")
                ) throw ProtocolException("Unsupported peer command on CarPlay control channel")
                val headers = readHeaders(line)
                val peerSequence = headers["cseq"]?.toIntOrNull()
                if ((headers.containsKey("cseq") || dialect == "RTSP/1.0") && peerSequence == null) {
                    throw ProtocolException("Invalid peer command CSeq")
                }
                val accepted = handler(readBody(headers, MAX_PEER_COMMAND_BYTES))
                val reply = buildString {
                    append(dialect)
                    append(if (accepted) " 200 OK\r\n" else " 400 Bad Request\r\n")
                    if (peerSequence != null) append("CSeq: $peerSequence\r\n")
                    append("Content-Length: 0\r\n\r\n")
                }
                output.write(reply.toByteArray(Charsets.US_ASCII))
                output.flush()
            }
        }
        throw ProtocolException("Too many peer commands while waiting for an RTSP response")
    }

    private fun readHeaders(statusLine: String): Map<String, String> {
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

        return headers
    }

    private fun readBody(headers: Map<String, String>, limit: Int): ByteArray {
        val bodyLength = headers["content-length"]?.let {
            it.toIntOrNull()?.takeIf { length -> length in 0..limit }
                ?: throw ProtocolException("Invalid RTSP content length")
        } ?: 0
        val body = ByteArray(bodyLength)
        var offset = 0
        while (offset < body.size) {
            val received = input.read(body, offset, body.size - offset)
            if (received <= 0) throw EOFException("Truncated RTSP message body")
            offset += received
        }
        return body
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
        const val MAX_PEER_COMMAND_BYTES = 65_536
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
    }
}

internal data class RtspResponse(
    val status: Int,
    val reason: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)
