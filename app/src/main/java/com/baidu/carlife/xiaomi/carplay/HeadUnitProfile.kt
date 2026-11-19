package com.baidu.carlife.xiaomi.carplay

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

internal enum class HeadUnitProfile(val id: String, val label: String, val summary: String, val protocol: ReceiverProtocol) {
    GENERIC("generic", "通用", "适用于 DiPlay、WinPlay 和其他接收端", ReceiverProtocol.GENERIC),
    BMW("bmw", "宝马（ID7–ID9）", "适用于 MGU-55 / MGU-86，首次请在 iDrive 确认授权", ReceiverProtocol.BMW_MGU),
    VOLKSWAGEN("volkswagen", "大众（MIB）", "先选择已配对的车机，并连接车机 Wi-Fi", ReceiverProtocol.VOLKSWAGEN);

    fun save(context: Context) {
        settings(context).edit().putString("profile", id).apply()
    }

    companion object {
        fun load(context: Context): HeadUnitProfile {
            val id = settings(context).getString("profile", null)
            return entries.firstOrNull { it.id == id } ?: GENERIC
        }

        fun observe(context: Context) = callbackFlow {
            val preferences = settings(context)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "profile" || key == null) trySend(load(context))
            }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            trySend(load(context))
            awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }

        private fun settings(context: Context) =
            context.getSharedPreferences("carplay_head_unit", Context.MODE_PRIVATE)
    }
}

internal enum class ReceiverProtocol {
    GENERIC, BMW_MGU, VOLKSWAGEN;

    val isBmw get() = this == BMW_MGU
    val isVehicle get() = this != GENERIC
}
