package com.baidu.carlife.mix.xiaomi

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.baidu.carlife.service.CapturePhase
import com.baidu.carlife.service.CarlifeConnectService
import com.baidu.carlife.xiaomi.carplay.ConnectionMode
import com.baidu.carlife.xiaomi.carplay.missingReceiverPermissions

class XiaomiPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val armed = CarlifeConnectService.state.value.phase == CapturePhase.ARMED
        val wireless = Build.VERSION.SDK_INT >= 33 && ConnectionMode.load(this) == ConnectionMode.CAR_WITH &&
            missingReceiverPermissions(this).isEmpty()
        val ready = callingPackage == "com.miui.carlink" && (armed || wireless)
        Log.i("CarNatCapture", "CarWith setup readiness: $ready")
        setResult(if (ready) RESULT_OK else RESULT_CANCELED)
        finish()
    }
}
