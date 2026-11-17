package com.baidu.carlife.xiaomi.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Event producers share only the in-memory queue. File access has a separate lock. */
internal class ProjectionLogBuffer(
    private val files: RollingLogFiles,
    private val executor: Executor,
    private val capacity: Int = 128,
    private val onWriteFailure: (Exception) -> Unit,
) {
    init {
        require(capacity > 0)
    }

    private data class Entry(val timeMs: Long, val message: String)

    private val pending = ArrayDeque<Entry>()
    private var scheduled = false
    private var dropped = 0L
    private val disk = ReentrantLock()
    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT)
    private var writeFailed = false

    fun enqueue(message: String) {
        val entry = entry(message)
        val startWriter = synchronized(pending) {
            if (pending.size == capacity) {
                pending.removeFirst()
                dropped++
            }
            pending.addLast(entry)
            if (scheduled) false else {
                scheduled = true
                true
            }
        }
        if (startWriter) {
            try {
                executor.execute(::writePending)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // A subsequent event can retry scheduling. Snapshots can still flush the queue.
                synchronized(pending) { scheduled = false }
            }
        }
    }

    fun snapshot(): String = disk.withLock {
        writeBatch(capacity)
        files.snapshot()
    }

    /** Crash handling must never wait for a file operation on another thread. */
    fun tryWriteCrash(message: String) {
        if (!disk.tryLock()) return
        try {
            write(entry(message))
        } finally {
            disk.unlock()
        }
    }

    private fun writePending() {
        do {
            disk.withLock { writeBatch(16) }
            val more = synchronized(pending) {
                pending.isNotEmpty().also { scheduled = it }
            }
        } while (more)
    }

    private fun writeBatch(limit: Int) {
        val (batch, skipped) = synchronized(pending) {
            if (pending.isEmpty()) return
            val batch = List(minOf(limit, pending.size)) { pending.removeFirst() }
            (batch to dropped).also { dropped = 0 }
        }
        if (skipped > 0) write(Entry(batch.first().timeMs, "日志写入繁忙 · 已略过 $skipped 条记录"))
        batch.forEach(::write)
    }

    private fun write(entry: Entry) {
        try {
            files.append("${timestamp.format(Date(entry.timeMs))} ${entry.message}\n")
            writeFailed = false
        } catch (error: Exception) {
            if (!writeFailed) {
                writeFailed = true
                onWriteFailure(error)
            }
        }
    }

    private fun entry(message: String) = Entry(System.currentTimeMillis(), message.take(2_000))
}
