package com.baidu.carlife.xiaomi.carplay

import java.net.ProtocolException

// Keep credentials in memory and out of diagnostic output.
internal class ReceiverWifi(val ssid: String, val passphrase: String, val security: Int, val channel: Int? = null) {
    companion object {
        fun fromStartSession(fields: Map<Int, ByteArray>, offset: Int): ReceiverWifi? =
            parse(fields[offset], fields[offset + 1], fields[offset + 4], fields[offset + 2])

        fun fromConfiguration(fields: Map<Int, ByteArray>): ReceiverWifi? =
            parse(fields[1], fields[2], fields[3], fields[4])

        private fun parse(ssidBytes: ByteArray?, passwordBytes: ByteArray?, securityBytes: ByteArray?, channelBytes: ByteArray?): ReceiverWifi? {
            val ssid = ssidBytes?.toString(Charsets.UTF_8)?.substringBefore('\u0000')
                ?.takeIf(String::isNotEmpty) ?: return null
            val password = passwordBytes?.toString(Charsets.UTF_8)?.substringBefore('\u0000').orEmpty()
            val security = securityBytes?.singleOrNull()?.toInt()?.and(255)
                ?: if (securityBytes == null) {
                    if (password.isEmpty()) return null
                    2
                } else {
                    throw ProtocolException("Invalid CarPlay Wi-Fi security type")
                }
            val channel = channelBytes?.singleOrNull()?.toInt()?.and(255)?.takeIf { it != 0 }
            return ReceiverWifi(ssid, password, security, channel)
        }
    }
}
