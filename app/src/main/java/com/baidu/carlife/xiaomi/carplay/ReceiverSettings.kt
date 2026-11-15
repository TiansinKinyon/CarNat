package com.baidu.carlife.xiaomi.carplay

import android.content.Context

internal data class ReceiverSettings(val name: String = "", val address: String = "", val pin: String = "", val receiverAddress: String = "") {
    val valid: Boolean get() = name.isNotBlank() && CarPlayScreenProbe.bluetoothAddress.matches(address.trim()) &&
        (pin.isEmpty() || pin.length <= 16 && pin.count { it in '0'..'9' } >= 4 && pin.all { it in '0'..'9' || it == '-' })

    fun saveReceiver(context: Context): Boolean = preferences(context).edit()
        .putString("name", name).putString("receiverAddress", receiverAddress).commit()

    private val resolutionKey: String
        get() = "display_" + receiverAddress.uppercase(java.util.Locale.ROOT)

    fun loadResolution(context: Context): CarPlayResolution? {
        if (!CarPlayScreenProbe.bluetoothAddress.matches(receiverAddress)) return null
        val values = preferences(context)
        val width = values.getInt(resolutionKey + "_width", 0)
        val height = values.getInt(resolutionKey + "_height", 0)
        return if (width > 0 && height > 0) CarPlayResolution(width, height) else null
    }

    fun saveResolution(context: Context, resolution: CarPlayResolution) {
        if (!CarPlayScreenProbe.bluetoothAddress.matches(receiverAddress)) return
        preferences(context).edit()
            .putInt(resolutionKey + "_width", resolution.width)
            .putInt(resolutionKey + "_height", resolution.height)
            .apply()
    }

    val networkPairingCode: String get() = pin.ifEmpty { "3939" }

    fun save(context: Context): Boolean = preferences(context).edit()
        .putString("name", name.trim()).putString("receiverAddress", receiverAddress).putString("address", address.trim()).putString("pin", pin).commit()

    companion object {
        private fun preferences(context: Context) = context.getSharedPreferences("receiver_settings", Context.MODE_PRIVATE)

        fun load(context: Context): ReceiverSettings = preferences(context).let {
            ReceiverSettings(it.getString("name", "").orEmpty(), it.getString("address", "").orEmpty(),
                it.getString("pin", "").orEmpty().takeUnless { code -> code == "3939" }.orEmpty(),
                it.getString("receiverAddress", "").orEmpty())
        }
    }
}
