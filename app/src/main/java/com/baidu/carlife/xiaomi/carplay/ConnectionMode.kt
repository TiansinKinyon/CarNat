package com.baidu.carlife.xiaomi.carplay

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale

internal enum class ConnectionMode(val label: String, val summary: String) {
    CAR_WITH("CarWith 发起", "通过 CarWith 的车辆热点连接，使用其已保存的连接记录自动启动"),
    MANUAL("CarNat 接管", "在 CarNat 选择已配对的车机并开始投屏");

    fun save(context: Context) = preferences(context).edit().putString("mode", name).apply()

    companion object {
        private fun preferences(context: Context) =
            context.getSharedPreferences("connection_mode", Context.MODE_PRIVATE)

        fun load(context: Context): ConnectionMode {
            val saved = preferences(context).getString("mode", null)
            return entries.firstOrNull { it.name == saved } ?: CAR_WITH
        }

        /** Stable per-install identity for receivers that do not require the hardware address. */
        fun automaticDeviceId(context: Context): String {
            val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest("CarNat:$id".toByteArray()).copyOf(6)
            bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
            return bytes.joinToString(":") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xff) }
        }
    }
}
