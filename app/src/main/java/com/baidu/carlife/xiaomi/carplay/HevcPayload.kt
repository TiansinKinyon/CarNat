package com.baidu.carlife.xiaomi.carplay

import java.nio.ByteBuffer

/** AirPlay's HEVC configuration contains an hvc1 sample entry with an hvcC box. */
internal object HevcPayload {
    fun configuration(buffers: List<ByteBuffer>, width: Int, height: Int): ByteArray {
        require(width in 2..65535 && height in 2..65535)
        require(buffers.sumOf { it.remaining().toLong() } in 1..AvcPayload.MAX_CONFIGURATION_BYTES.toLong())
        val units = buffers.flatMap { buffer ->
            val bytes = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
            AvcPayload.splitAnnexB(bytes).ifEmpty { listOf(bytes) }
        }
        val sets = (32..34).map { kind ->
            units.firstOrNull {
                it.size in 2..65535 && (it[0].toInt() and 0x80) == 0 &&
                    (it[0].toInt() ushr 1 and 63) == kind && (it[1].toInt() and 7) != 0
            } ?: throw IllegalArgumentException("Missing HEVC parameter set $kind")
        }
        val bits = SpsBits(sets[1])
        bits.read(4)
        val layers = bits.read(3)
        require(layers <= 6)
        val nested = bits.read(1)
        val profile = ByteArray(12) { bits.read(8).toByte() }
        val flags = List(layers) { bits.read(2) }
        if (layers > 0) repeat(8 - layers) { bits.read(2) }
        for (flag in flags) {
            if ((flag and 2) != 0) repeat(11) { bits.read(8) }
            if ((flag and 1) != 0) bits.read(8)
        }
        require(bits.unsignedExpGolomb() <= 15)
        val chroma = bits.unsignedExpGolomb()
        require(chroma <= 3)
        if (chroma == 3) bits.read(1)
        require(bits.unsignedExpGolomb() in 2..65535)
        require(bits.unsignedExpGolomb() in 2..65535)
        if (bits.read(1) != 0) repeat(4) { bits.unsignedExpGolomb() }
        val lumaDepth = bits.unsignedExpGolomb()
        val chromaDepth = bits.unsignedExpGolomb()
        require(lumaDepth <= 7 && chromaDepth <= 7)

        val recordSize = 23 + sets.sumOf { 5 + it.size }
        require(94 + recordSize <= AvcPayload.MAX_CONFIGURATION_BYTES)
        val output = ByteBuffer.allocate(94 + recordSize)
        output.putInt(output.capacity()).putInt(0x68766331) // hvc1
        output.putShort(14, (-1).toShort())
        output.putShort(26, 512).putShort(30, 512)
        output.putShort(32, width.toShort()).putShort(34, height.toShort())
        output.putInt(36, 72 shl 16).putInt(40, 72 shl 16)
        output.putShort(48, 1).position(50)
        output.put(byteArrayOf(4, 72, 69, 86, 67))
        output.putShort(82, 24).putShort(84, (-1).toShort()).position(86)
        output.putInt(8 + recordSize).putInt(0x68766343) // hvcC
        output.put(1).put(profile).putShort(0xf000.toShort()).put(0xfc.toByte())
        output.put((0xfc or chroma).toByte()).put((0xf8 or lumaDepth).toByte()).put((0xf8 or chromaDepth).toByte())
        output.putShort(0).put((((layers + 1) shl 3) or (nested shl 2) or 3).toByte()).put(3)
        sets.forEachIndexed { index, nal ->
            output.put((0x80 or (32 + index)).toByte()).putShort(1).putShort(nal.size.toShort()).put(nal)
        }
        return output.array()
    }

    private class SpsBits(nal: ByteArray) {
        private val rbsp = ByteArray(nal.size - 2)
        private var length = 0
        private var bit = 0

        init {
            var zeros = 0
            for (index in 2 until nal.size) {
                val value = nal[index]
                if (zeros >= 2 && value == 3.toByte()) {
                    zeros = 0
                    continue
                }
                rbsp[length++] = value
                zeros = if (value == 0.toByte()) zeros + 1 else 0
            }
        }

        fun read(count: Int): Int {
            require(count <= 30 && bit + count <= length * 8) { "Truncated HEVC SPS" }
            var result = 0
            repeat(count) {
                result = (result shl 1) or ((rbsp[bit / 8].toInt() ushr (7 - bit % 8)) and 1)
                bit++
            }
            return result
        }

        fun unsignedExpGolomb(): Int {
            var zeros = 0
            while (read(1) == 0) require(++zeros <= 30)
            return (1 shl zeros) - 1 + read(zeros)
        }
    }
}
