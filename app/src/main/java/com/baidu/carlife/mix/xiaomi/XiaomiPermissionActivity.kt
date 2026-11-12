package com.baidu.carlife.mix.xiaomi

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.baidu.carlife.service.CapturePhase
import com.baidu.carlife.service.CarlifeConnectService

class XiaomiPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The local capture diagnostic needs no location or Bluetooth access.
        // Only an active opt-in can authorize CarWith to continue its setup.
        val ready = callingPackage == "com.miui.carlink" &&
            CarlifeConnectService.state.value.phase == CapturePhase.ARMED
        if (ready) CarlifeConnectService.markSetupReady()
        Log.i("CarNatCapture", "CarWith setup readiness: $ready")
        setResult(if (ready) RESULT_OK else RESULT_CANCELED)
        finish()
    }
}
