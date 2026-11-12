package com.baidu.carlife.xiaomi.carplay

import java.io.ByteArrayOutputStream
import java.net.ProtocolException

internal object PairingTlv {
    const val METHOD = 0
    const val IDENTIFIER = 1
    const val SALT = 2
    const val PUBLIC_KEY = 3
    const val PROOF = 4
    const val ENCRYPTED = 5
    const val STATE = 6
    const val ERROR = 7
    const val SIGNATURE = 10

    fun encode(vararg fields: Pair<Int, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        for ((type, value) in fields) {
            var offset = 0
            do {
                val count = minOf(255, value.size - offset)
                output.write(type)
                output.write(count)
                output.write(value, offset, count)
                offset += count
            } while (offset < value.size)
        }
        return output.toByteArray()
    }

    fun decode(data: ByteArray): Map<Int, ByteArray> {
        if (data.size > 8192) throw ProtocolException("Pairing TLV is too large")
        val fields = mutableMapOf<Int, ByteArrayOutputStream>()
        var offset = 0
        var previousType = -1
        var previousLength = 0
        while (offset < data.size) {
            if (data.size - offset < 2) throw ProtocolException("Truncated pairing TLV header")
            val type = data[offset++].toInt() and 255
            val length = data[offset++].toInt() and 255
            if (length > data.size - offset) throw ProtocolException("Truncated pairing TLV value")
            if (type in fields && (type != previousType || previousLength != 255)) {
                throw ProtocolException("Duplicate pairing field $type")
            }
            fields.getOrPut(type, ::ByteArrayOutputStream).write(data, offset, length)
            offset += length
            previousType = type
            previousLength = length
        }
        return fields.mapValues { (_, value) -> value.toByteArray() }
    }

    fun response(data: ByteArray, state: Int): Map<Int, ByteArray> = decode(data).also { fields ->
        if (ERROR in fields) {
            throw ProtocolException("Receiver rejected pairing (code ${fields[ERROR]?.firstOrNull()?.toInt()?.and(255)})")
        }
        if (fields[STATE]?.contentEquals(byteArrayOf(state.toByte())) != true) {
            throw ProtocolException("Unexpected receiver pairing state, expected $state")
        }
    }

    fun requireField(fields: Map<Int, ByteArray>, type: Int, size: IntRange): ByteArray =
        fields[type]?.takeIf { it.size in size } ?: throw ProtocolException("Invalid pairing field $type")
}
