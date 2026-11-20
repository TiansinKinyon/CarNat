package com.baidu.carlife.xiaomi.carplay

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

internal data class Iap2PowerStatus(
    val plugged: Boolean? = null,
    val state: Int? = null,
    val level: Int? = null,
) {
    fun parameters(requested: Set<Int>): List<Pair<Int, ByteArray>> = buildList {
        if (4 in requested) plugged?.let { add(4 to byteArrayOf(if (it) 1 else 0)) }
        if (5 in requested) state?.takeIf { it in 0..2 }?.let { add(5 to byteArrayOf(it.toByte())) }
        if (6 in requested) level?.takeIf { it in 0..100 }?.let { add(6 to byteArrayOf(0, it.toByte())) }
    }

    companion object {
        fun read(context: Context): Iap2PowerStatus {
            val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
                .getOrNull() ?: return Iap2PowerStatus()
            val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val state = when (battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> 1
                BatteryManager.BATTERY_STATUS_FULL -> 2
                BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> 0
                else -> null
            }
            return Iap2PowerStatus(
                plugged.takeIf { it >= 0 }?.let { it != 0 },
                state,
                if (scale > 0 && level in 0..scale) (level.toLong() * 100 / scale).toInt() else null,
            )
        }
    }
}
