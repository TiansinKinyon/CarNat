package com.baidu.carlife.xiaomi.carplay

import java.nio.ByteBuffer

internal object AvcPayload {
    fun configuration(spsBuffer: ByteBuffer, ppsBuffer: ByteBuffer?): ByteArray {
        val spsBytes = spsBuffer.copyBytes()
        val ppsBytes = ppsBuffer?.copyBytes()
        val units = parameterSets(spsBytes) + (ppsBytes?.let(::parameterSets) ?: emptyList())
        val sequenceSets = units.filter { it[0].toInt() and 31 == 7 }
        val pictureSets = units.filter { it[0].toInt() and 31 == 8 }
        require(sequenceSets.size in 1..31 && pictureSets.size in 1..255) { "Missing AVC SPS or PPS" }
        require(sequenceSets.first().size >= 4 && units.all { it.size <= 65535 }) {
            "Invalid AVC parameter set"
        }

        val firstSps = sequenceSets.first()
        val output = ByteBuffer.allocate(7 + (sequenceSets + pictureSets).sumOf { it.size + 2 })
        output.put(1)
        output.put(firstSps[1]).put(firstSps[2]).put(firstSps[3])
        output.put(0xff.toByte()).put((0xe0 or sequenceSets.size).toByte())
        sequenceSets.forEach { output.putShort(it.size.toShort()).put(it) }
        output.put(pictureSets.size.toByte())
        pictureSets.forEach { output.putShort(it.size.toShort()).put(it) }
        return output.array()
    }

    fun accessUnit(buffer: ByteBuffer): ByteArray {
        val source = buffer.duplicate()
        if (isLengthPrefixed(source)) return source.copyBytes()

        val units = splitAnnexB(source.copyBytes())
        require(units.isNotEmpty()) { "Invalid AVC access unit" }
        val output = ByteBuffer.allocate(units.sumOf { 4 + it.size })
        units.forEach { output.putInt(it.size).put(it) }
        return output.array()
    }

    private fun parameterSets(bytes: ByteArray): List<ByteArray> {
        require(bytes.isNotEmpty()) { "Empty AVC parameter set" }
        return if (bytes[0] == 1.toByte()) readConfiguration(bytes) else {
            splitAnnexB(bytes).ifEmpty { listOf(bytes) }
        }
    }

    private fun readConfiguration(bytes: ByteArray): List<ByteArray> {
        require(bytes.size >= 7) { "Truncated AVC configuration" }
        val record = ByteBuffer.wrap(bytes)
        record.position(5)
        val sequenceCount = record.get().toInt() and 31
        val sequence = List(sequenceCount) { record.readParameterSet() }
        require(record.hasRemaining()) { "Missing AVC picture parameter sets" }
        val pictureCount = record.get().toInt() and 255
        return sequence + List(pictureCount) { record.readParameterSet() }
    }

    private fun ByteBuffer.readParameterSet(): ByteArray {
        require(remaining() >= 2) { "Truncated AVC parameter set length" }
        val size = getShort().toInt() and 65535
        require(size in 1..remaining()) { "Invalid AVC parameter set length" }
        return ByteArray(size).also { get(it) }
    }

    private fun isLengthPrefixed(source: ByteBuffer): Boolean {
        val data = source.duplicate()
        if (!data.hasRemaining()) return false
        while (data.hasRemaining()) {
            if (data.remaining() < 4) return false
            val size = data.int
            if (size <= 0 || size > data.remaining()) return false
            data.position(data.position() + size)
        }
        return true
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val units = mutableListOf<ByteArray>()
        var start = -1
        var zeros = 0
        for (index in data.indices) {
            when {
                data[index] == 0.toByte() -> zeros++
                data[index] == 1.toByte() && zeros >= 2 -> {
                    val end = index - zeros
                    if (start < 0 && end != 0) return emptyList()
                    if (start >= 0) {
                        require(end > start) { "Empty Annex B NAL unit" }
                        units += data.copyOfRange(start, end)
                    }
                    start = index + 1
                    zeros = 0
                }
                else -> zeros = 0
            }
        }
        if (start >= 0) {
            val end = data.size - zeros
            require(end > start) { "Empty Annex B NAL unit" }
            units += data.copyOfRange(start, end)
        }
        return units
    }

    private fun ByteBuffer.copyBytes(): ByteArray = ByteArray(remaining()).also { duplicate().get(it) }
}
