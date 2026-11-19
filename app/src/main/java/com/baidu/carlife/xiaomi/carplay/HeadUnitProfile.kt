package com.baidu.carlife.xiaomi.carplay

import android.content.Context

internal enum class HeadUnitProfile(val id: String, val label: String, val summary: String) {
    GENERIC("generic", "通用", "适用于 DiPlay、WinPlay 和其他接收端"),
    BMW("bmw", "宝马（ID7–ID9）", "适用于 MGU 和 IDC23 无线 CarPlay 车机"),
    VOLKSWAGEN("volkswagen", "大众（MIB）", "先选择已配对的车机，并连接车机 Wi-Fi");

    fun save(context: Context) {
        settings(context).edit().putString("profile", id).apply()
    }

    fun protocol(model: String): ReceiverProtocol = when {
        this == GENERIC -> ReceiverProtocol.GENERIC
        this == VOLKSWAGEN -> ReceiverProtocol.VOLKSWAGEN
        model.startsWith("IDC23", ignoreCase = true) -> ReceiverProtocol.BMW_ID9
        else -> ReceiverProtocol.BMW_MGU
    }

    companion object {
        fun load(context: Context): HeadUnitProfile {
            val id = settings(context).getString("profile", null)
            return entries.firstOrNull { it.id == id } ?: GENERIC
        }

        private fun settings(context: Context) =
            context.getSharedPreferences("carplay_head_unit", Context.MODE_PRIVATE)
    }
}

internal enum class ReceiverProtocol {
    GENERIC, BMW_MGU, BMW_ID9, VOLKSWAGEN;

    val isBmw get() = this == BMW_MGU || this == BMW_ID9
    val isVehicle get() = this != GENERIC
}
