package com.baidu.carlife.xiaomi.carplay

import com.dd.plist.BinaryPropertyListParser
import com.dd.plist.NSDictionary
import java.net.ProtocolException

internal object CarPlayPlist {
    fun readDictionary(bytes: ByteArray): NSDictionary {
        if (bytes.size !in 40..MAX_BYTES || !HEADER.indices.all { bytes[it] == HEADER[it] }) invalid()
        Budget(bytes).check()
        val value = try {
            BinaryPropertyListParser.parse(bytes)
        } catch (failure: Exception) {
            throw ProtocolException("Invalid CarPlay binary plist").apply { initCause(failure) }
        }
        return value as? NSDictionary ?: invalid()
    }

    // The parser creates a new object for each reference, including repeated references.
    private class Budget(private val bytes: ByteArray) {
        private val trailer = bytes.size - 32
        private val offsetWidth = bytes[trailer + 6].toInt() and 255
        private val referenceWidth = bytes[trailer + 7].toInt() and 255
        private val objectCount = number(trailer + 8, 8, bytes.size)
        private val table = number(trailer + 24, 8, bytes.size)
        private var remaining = 8 * 1024 * 1024L
        private var visits = 0

        fun check() {
            if (table !in 8..trailer || offsetWidth !in 1..8 || referenceWidth !in 1..8 ||
                objectCount !in 1..MAX_OBJECTS || objectCount > (trailer - table) / offsetWidth
            ) invalid()
            visit(number(trailer + 16, 8, bytes.size), depth = 0)
        }

        private fun visit(reference: Int, depth: Int, dictionaryKey: Boolean = false) {
            if (depth > 32 || ++visits > MAX_OBJECTS || reference !in 0 until objectCount) invalid()
            val offset = number(table + reference * offsetWidth, offsetWidth, trailer)
            if (offset !in 8 until table) invalid()
            val marker = bytes[offset].toInt() and 255
            val type = marker ushr 4
            val inlineLength = marker and 15
            if (dictionaryKey && type !in 5..7) invalid()
            remaining -= 128
            when (type) {
                0 -> if (inlineLength !in setOf(0, 8, 9)) invalid()
                1, 2, 3, 8 -> {
                    val size = when (type) {
                        1 -> {
                            if (inlineLength > 4) invalid()
                            1 shl inlineLength
                        }
                        2 -> {
                            if (inlineLength !in 2..3) invalid()
                            1 shl inlineLength
                        }
                        3 -> {
                            if (inlineLength != 3) invalid()
                            8
                        }
                        else -> inlineLength + 1
                    }
                    if (size > table - offset - 1) invalid()
                    if (type == 1 && size == 16) {
                        // dd-plist keeps the low 64 bits. Only accept values that fit those bits.
                        val start = offset + 1
                        val zeroExtended = (start until start + 8).all { bytes[it] == 0.toByte() }
                        val signExtended = bytes[start + 8] < 0 &&
                            (start until start + 8).all { bytes[it] == (-1).toByte() }
                        if (!zeroExtended && !signExtended) invalid()
                    }
                }
                4, 5, 6, 7, 10, 13 -> {
                    var start = offset + 1
                    val length = if (inlineLength < 15) inlineLength else {
                        if (start >= table) invalid()
                        val lengthMarker = bytes[start++].toInt() and 255
                        if (lengthMarker !in 0x10..0x13) invalid()
                        val width = 1 shl (lengthMarker and 15)
                        number(start, width, table).also { start += width }
                    }
                    if (type == 10 || type == 13) {
                        val references = length.toLong() * if (type == 13) 2 else 1
                        if (references > (table - start) / referenceWidth || references > MAX_OBJECTS - visits) invalid()
                        for (index in 0 until references.toInt()) {
                            visit(
                                number(start + index * referenceWidth, referenceWidth, table),
                                depth + 1,
                                dictionaryKey = type == 13 && index < length,
                            )
                        }
                    } else {
                        val size = length.toLong() * when (type) {
                            6 -> 2
                            7 -> 4
                            else -> 1
                        }
                        val minimumSize = if (type == 7) length.toLong() else size
                        if (minimumSize > table - start) invalid()
                        remaining -= size * 2
                    }
                }
                else -> invalid()
            }
            if (remaining < 0) invalid()
        }

        private fun number(start: Int, width: Int, end: Int): Int {
            if (start < 0 || start > end - width) invalid()
            var value = 0L
            for (index in start until start + width) {
                value = (value shl 8) or (bytes[index].toLong() and 255)
                if (value > Int.MAX_VALUE) invalid()
            }
            return value.toInt()
        }
    }

    private fun invalid(): Nothing = throw ProtocolException("Binary plist exceeds CarPlay format or resource limits")

    private val HEADER = "bplist00".toByteArray(Charsets.US_ASCII)
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_OBJECTS = 16_384
}
