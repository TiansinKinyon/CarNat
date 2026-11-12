package com.baidu.carlife.mixing.aidl;

import android.content.Intent;
import android.os.Bundle;
import com.baidu.carlife.mixing.MusicInfo;
import com.baidu.carlife.mixing.aidl.IRemoteAudioListener;
import com.baidu.carlife.mixing.aidl.IRemoteKeyListener;
import com.baidu.carlife.mixing.aidl.IRemoteTouchListener;

interface ICarLifeCastCallback {
    void onServiceReady();
    void onCastPrepared(int displayId);
    void addTouchListener(IRemoteTouchListener listener);
    void removeTouchListener(IRemoteTouchListener listener);
    void addKeyListener(IRemoteKeyListener listener);
    void removeKeyListener(IRemoteKeyListener listener);
    void sendMusicInfo(in MusicInfo info);
    void notifyMusicProgress(long positionMs);
    Bundle invokeMethod(in Intent intent);
    void onServiceDestroy();
    void notifyAudioState(int state);
    void addAudioListener(IRemoteAudioListener listener);
    void removeAudioListener(IRemoteAudioListener listener);
}
