package com.baidu.carlife.xiaomi.diagnostics

import java.io.File
import java.io.IOException

/** Access is serialized by ProjectionLog. */
internal class RollingLogFiles(
    private val directory: File,
    private val fileLimit: Int = 128 * 1024,
    private val fileCount: Int = 3,
) {
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
        if (log.isFile) log.readText() else ""
    }

    private fun file(index: Int) = File(directory, "CarNat-$index.log")
}
