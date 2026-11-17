package com.baidu.carlife.xiaomi.carplay

import android.content.Context

internal data class ReceiverSettings(val name: String = "", val address: String = "", val pin: String = "", val receiverAddress: String = "") {
    val valid: Boolean get() = name.isNotBlank() && validAddress(address) && validPin(pin)

    fun saveReceiver(context: Context): Boolean = preferences(context).edit()
        .putString("name", name).putString("receiverAddress", receiverAddress).commit()

    fun savePhoneAddress(context: Context): Boolean = preferences(context).edit()
        .putString("address", address).commit()

    val networkPairingCode: String get() = pin.ifEmpty { "3939" }

    fun save(context: Context): Boolean = preferences(context).edit()
        .putString("name", name.trim()).putString("receiverAddress", receiverAddress).putString("address", address.trim()).putString("pin", pin).commit()

    companion object {
        fun validAddress(value: String): Boolean {
            val address = value.trim()
            return CarPlayScreenProbe.bluetoothAddress.matches(address) &&
                address != "02:00:00:00:00:00" && address != "00:00:00:00:00:00"
        }

        fun validPin(value: String): Boolean = value.isEmpty() ||
            value.length <= 16 && value.count { it in '0'..'9' } >= 4 &&
            value.all { it in '0'..'9' || it == '-' }

        private fun preferences(context: Context) = context.getSharedPreferences("receiver_settings", Context.MODE_PRIVATE)

        fun load(context: Context): ReceiverSettings = preferences(context).let {
            ReceiverSettings(it.getString("name", "").orEmpty(), it.getString("address", "").orEmpty(),
                it.getString("pin", "").orEmpty().takeUnless { code -> code == "3939" }.orEmpty(),
                it.getString("receiverAddress", "").orEmpty())
        }
    }
}
