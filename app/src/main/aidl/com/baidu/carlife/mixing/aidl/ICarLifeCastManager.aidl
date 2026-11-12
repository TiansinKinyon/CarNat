package com.baidu.carlife.mixing.aidl;

import android.content.Intent;
import android.os.Bundle;
import com.baidu.carlife.mixing.CastConfig;
import com.baidu.carlife.mixing.aidl.ICarLifeCastCallback;
import com.baidu.carlife.mixing.aidl.ISystemAudioCallback;

interface ICarLifeCastManager {
    void prepareCast(in CastConfig config);
    void stopCast();
    void setSystemAudioCallback(ISystemAudioCallback callback);
    void setCastCallback(ICarLifeCastCallback callback);
    Bundle invokeMethod(in Intent intent);
}
