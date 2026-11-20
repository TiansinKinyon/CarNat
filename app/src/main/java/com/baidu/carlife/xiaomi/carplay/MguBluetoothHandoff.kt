package com.baidu.carlife.xiaomi.carplay

import com.baidu.carlife.xiaomi.diagnostics.ProjectionLog
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSDictionary

/** Asks the receiver to retire Bluetooth. The RFCOMM reader owns its own lifetime. */
internal class MguBluetoothHandoff(
    private val control: RtspControl,
    private val deviceId: String,
) {
    private var requested = false

    @Synchronized
    fun request() {
        if (requested) return
        val body = NSDictionary().apply {
            put("type", "disableBluetooth")
            put("params", NSDictionary().apply { put("deviceID", deviceId) })
        }
        control.sendDeferredCommand(BinaryPropertyListWriter.writeToArray(body))
        requested = true
        ProjectionLog.event("宝马已完成初始主屏决策 · 请求蓝牙交接")
    }
}

/** State belongs to one receiver attempt and uses a monotonic clock. */
internal class MguWifiHealth(private val now: () -> Long = android.os.SystemClock::elapsedRealtime) {
    private var ready = false
    private var lastFeedback: Long? = null

    @Synchronized fun ready() { ready = true }
    @Synchronized fun feedback(status: Int) { lastFeedback = if (status == 200) now() else null }
    @Synchronized fun canKeep(): Boolean =
        ready && lastFeedback?.let { now() - it < 5_000L } == true
}
