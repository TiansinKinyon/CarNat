package com.baidu.carlife.xiaomi.carplay

import java.io.InputStream
import java.io.InterruptedIOException
import java.util.ArrayDeque

/** Bounded ingress shared by the authenticated AirPlay event and control channels. */
internal class NetworkIapInput(private val capacity: Int) : InputStream() {
    private val lock = java.lang.Object()
    private val pending = ArrayDeque<ByteArray>()
    private var queued = 0
    private var offset = 0
    private var closed = false

    fun offer(bytes: ByteArray): Boolean = synchronized(lock) {
        if (closed) return false
        if (bytes.isNotEmpty()) {
            if (bytes.size > capacity - queued || pending.size >= 256) return false
            pending.addLast(bytes)
            queued += bytes.size
            lock.notifyAll()
        }
        true
    }

    override fun read(): Int = synchronized(lock) {
        val first = awaitChunk() ?: return -1
        (first[offset].toInt() and 255).also { consume(1, first.size) }
    }

    override fun read(bytes: ByteArray, start: Int, length: Int): Int = synchronized(lock) {
        if (start < 0 || length < 0 || start > bytes.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        val first = awaitChunk() ?: return -1
        val count = minOf(length, first.size - offset)
        first.copyInto(bytes, start, offset, offset + count)
        consume(count, first.size)
        count
    }

    private fun awaitChunk(): ByteArray? {
        while (pending.isEmpty() && !closed) {
            try {
                lock.wait()
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Wi-Fi iAP2 已取消").apply { initCause(failure) }
            }
        }
        return pending.peekFirst()
    }

    private fun consume(count: Int, size: Int) {
        queued -= count
        offset += count
        if (offset == size) {
            pending.removeFirst()
            offset = 0
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            pending.clear()
            queued = 0
            offset = 0
            lock.notifyAll()
        }
    }
}
