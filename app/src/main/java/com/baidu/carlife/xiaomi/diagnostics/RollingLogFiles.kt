package com.baidu.carlife.xiaomi.diagnostics

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** File access is serialized by ProjectionLogBuffer. */
internal class RollingLogFiles(
    private val directory: File,
    private val fileLimit: Int = 128 * 1024,
    private val fileCount: Int = 3,
) {
    init {
        require(fileLimit > 0 && fileCount > 0)
    }

    fun append(entry: String) {
        val bytes = entry.toByteArray()
        require(bytes.size <= fileLimit)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create log directory")
        val active = file(0)
        if (active.length() + bytes.size > fileLimit) {
            val oldest = file(fileCount - 1)
            if (oldest.exists() && !oldest.delete()) throw IOException("Cannot remove oldest log")
            for (index in fileCount - 2 downTo 0) {
                val source = file(index)
                if (source.exists() && !source.renameTo(file(index + 1))) {
                    throw IOException("Cannot rotate projection log")
                }
            }
        }
        active.appendBytes(bytes)
    }

    fun snapshot(): String = (fileCount - 1 downTo 0).joinToString("") { index ->
        val log = file(index)
        if (log.isFile) readTail(log) else ""
    }

    private fun readTail(log: File): String = RandomAccessFile(log, "r").use { input ->
        val length = input.length()
        val size = minOf(length, fileLimit.toLong()).toInt()
        val start = length - size
        input.seek(start)
        val bytes = ByteArray(size).also(input::readFully)
        val firstLine = if (start == 0L) {
            0
        } else {
            input.seek(start - 1)
            if (input.read() == '\n'.code) 0 else {
                val newline = bytes.indexOf('\n'.code.toByte())
                if (newline < 0) size else newline + 1
            }
        }
        bytes.decodeToString(firstLine)
    }

    private fun file(index: Int) = File(directory, "CarNat-$index.log")
}
