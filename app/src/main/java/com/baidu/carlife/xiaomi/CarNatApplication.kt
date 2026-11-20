package com.baidu.carlife.xiaomi

import android.app.Application
import com.baidu.carlife.xiaomi.diagnostics.ProjectionLog

class CarNatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ProjectionLog.initialize(this)
        com.baidu.carlife.xiaomi.carplay.VolkswagenWifiWake.start(this)
    }
}
