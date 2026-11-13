package com.baidu.carlife.xiaomi.carplay

import android.content.Context

internal data class ReceiverSettings(val name: String = "", val address: String = "", val pin: String = "") {
    val valid: Boolean get() = name.isNotBlank() && CarPlayScreenProbe.bluetoothAddress.matches(address.trim()) &&
        pin.length <= 16 && pin.count { it in '0'..'9' } >= 4 && pin.all { it in '0'..'9' || it == '-' }

    fun save(context: Context): Boolean = preferences(context).edit()
        .putString("name", name.trim()).putString("address", address.trim()).putString("pin", pin).commit()

    companion object {
        private fun preferences(context: Context) = context.getSharedPreferences("receiver_settings", Context.MODE_PRIVATE)

        fun load(context: Context): ReceiverSettings = preferences(context).let {
            ReceiverSettings(it.getString("name", "").orEmpty(), it.getString("address", "").orEmpty(),
                it.getString("pin", "").orEmpty())
        }
    }
}
